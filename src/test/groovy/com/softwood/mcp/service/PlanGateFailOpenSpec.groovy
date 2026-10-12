package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Stabilisation round 2, R2-2 (chain e05a071a). 2026-10-12: CS recorded PLAN-GATE 'fired' (plan_gate_events 1737)
 * while FS let the write through, because CS answered after FS's 1500 ms read timeout. FS counted it in an in-memory
 * AtomicInteger that nothing outside the process can read, so a fire nobody saw was invisible: CS had the fire, FS
 * had the pass, and no row joined them.
 *
 * From FS 0.9.81 every ask that reaches CS is reported to CS as a telemetry row: tool_name 'plan_gate', action = the
 * gated tool, outcome plan_gate_answered | plan_gate_unavailable, and duration_ms -- so the fail-open count and the
 * p50/p95 latency are SQL over tool_call_telemetry, and the timeout is tuned from a measurement, not a guess.
 * The CS half (duration_ms persisted) is CS TelemetryDurationSpec.
 *
 * PGF-1  CS unreachable (null answer) is reported plan_gate_unavailable with a duration
 * PGF-2  a SLOW null answer reports how long FS waited
 * PGF-3  an allow is reported plan_gate_answered
 * PGF-4  a refusal is reported plan_gate_answered too -- the gate was heard
 * PGF-5  a throwing client is reported plan_gate_unavailable
 * PGF-6  no claimed session: nothing asked, nothing reported here (an unclaimed process is SB-2's, not a fail-open)
 */
class PlanGateFailOpenSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)

    @TempDir Path tmp
    String repoFile

    def setup() {
        Files.createDirectories(tmp.resolve('repo/.git'))
        Files.createDirectories(tmp.resolve('repo/src'))
        repoFile = tmp.resolve('repo/src/Foo.groovy').toString()
    }

    def 'PGF-1: a null answer is reported plan_gate_unavailable with a duration'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-1'
        client.planGateCheck(_, _) >> null

        when:
        String msg = guard.checkWrite('write', repoFile)

        then:
        msg == null
        1 * telemetry.recordToolCall('sid-1', 'plan_gate', 0, _, 'file_write', null, 'plan_gate_unavailable',
            { Map d -> d.duration_ms instanceof Number && (d.duration_ms as long) >= 0 })
    }

    def 'PGF-2: a slow null answer reports how long FS waited'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-1'
        client.planGateCheck(_, _) >> { Thread.sleep(60); null }

        when:
        guard.checkWrite('write', repoFile)

        then:
        1 * telemetry.recordToolCall('sid-1', 'plan_gate', 0, _, 'file_write', null, 'plan_gate_unavailable',
            { Map d -> (d.duration_ms as long) >= 50 })
    }

    def 'PGF-3: an allow is reported plan_gate_answered'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-1'
        client.planGateCheck(_, _) >> [allow: true, reason: 'already-gated']

        when:
        String msg = guard.checkWrite('replace', repoFile)

        then:
        msg == null
        1 * telemetry.recordToolCall('sid-1', 'plan_gate', 0, _, 'file_write', null, 'plan_gate_answered',
            { Map d -> d.duration_ms instanceof Number })
    }

    def 'PGF-4: a refusal is reported plan_gate_answered'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-1'
        client.planGateCheck(_, _) >> [allow: false, reason: 'first-call', component: 'r/Foo',
                                       practices: [[id: 7, title: 't', summary: 's']]]

        when:
        String msg = guard.checkWrite('write', repoFile)

        then:
        msg != null
        1 * telemetry.recordToolCall('sid-1', 'plan_gate', 0, _, 'file_write', null, 'plan_gate_answered', _)
    }

    def 'PGF-5: a throwing client is reported plan_gate_unavailable'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-1'
        client.planGateCheck(_, _) >> { throw new RuntimeException('read timed out') }

        when:
        String msg = guard.checkWrite('write', repoFile)

        then:
        msg == null
        1 * telemetry.recordToolCall('sid-1', 'plan_gate', 0, _, 'file_write', null, 'plan_gate_unavailable', _)
    }

    def 'PGF-6: with no claimed session nothing is asked and no plan_gate row is written'() {
        given:
        telemetry.readActiveSessionId() >> null

        when:
        guard.checkWrite('write', repoFile)

        then:
        0 * client.planGateCheck(_, _)
        0 * telemetry.recordToolCall(_, 'plan_gate', _, _, _, _, _, _)
    }
}
