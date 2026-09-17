package com.softwood.mcp.service

import spock.lang.Specification

/**
 * FS 0.9.37 C1 -- PLAN-GATE at FS's mutating entry points. CS decides; this pins how FS asks and
 * what it does with every kind of answer.
 *
 * PGG-1  a refusal from CS becomes a message naming the practices and the retry
 * PGG-2  allow passes
 * PGG-3  CS unreachable (null answer) passes and counts unavailable
 * PGG-4  no claimed session passes without asking CS, and counts unavailable
 * PGG-5  execute is gated only for gradlew / git; everything else is not asked about
 * PGG-6  non-writing file_write actions are not asked about
 * PGG-7  the gate switched off asks nothing
 * PGG-8  a throwing client passes
 */
class PlanGateGuardSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)

    def setup() { telemetry.readActiveSessionId() >> 'sid-1' }

    def 'PGG-1: a refusal names the practices and says the retry passes'() {
        given:
        client.planGateCheck([tool: 'file_write', path: 'C:/r/Foo.groovy'], 'sid-1') >> [
            allow: false, component: 'Foo',
            practices: [[id: 7, valence: 'proscriptive', title: 'never do X to Foo', summary: 'because Y']],
            retry: 'Read these, then repeat the same call unchanged -- a retry is never refused.']
        when:
        String msg = guard.checkWrite('write', 'C:/r/Foo.groovy')
        then:
        msg.startsWith("PLAN-GATE: first mutating call on 'Foo'")
        msg.contains('#7 [proscriptive] never do X to Foo -- because Y')
        msg.contains('never refused')
    }

    def 'PGG-2: allow passes'() {
        given:
        client.planGateCheck(_, _) >> [allow: true, reason: 'already-gated']
        expect:
        guard.checkWrite('replace', 'C:/r/Foo.groovy') == null
    }

    def 'PGG-3: CS unreachable passes and is counted'() {
        given:
        client.planGateCheck(_, _) >> null
        when:
        String msg = guard.checkWrite('write', 'C:/r/Foo.groovy')
        then:
        msg == null
        guard.unavailable.get() == 1
    }

    def 'PGG-4: no claimed session passes without asking'() {
        given:
        PlanGateGuard g = new PlanGateGuard(contextServerClient: client, telemetryService: Mock(FilesystemTelemetryService))
        when:
        String msg = g.checkWrite('write', 'C:/r/Foo.groovy')
        then:
        msg == null
        0 * client.planGateCheck(_, _)
        g.unavailable.get() == 1
    }

    def 'PGG-5: execute is asked about only for gradlew and git'() {
        when:
        String a = guard.checkExecute('Get-ChildItem .', 'C:/r')
        String b = guard.checkExecute('.\\gradlew.bat test', 'C:/r')
        String c = guard.checkExecute('git push origin main', 'C:/r')
        then:
        a == null
        1 * client.planGateCheck({ it.tool == 'execute' && it.workingDir == 'C:/r' && it.script.contains('gradlew') }, 'sid-1') >> [allow: true]
        1 * client.planGateCheck({ it.script.startsWith('git ') }, 'sid-1') >> [allow: false, component: 'r:git', practices: []]
        b == null
        c.contains("'r:git'")
    }

    def 'PGG-6: non-writing file_write actions are not asked about'() {
        when:
        guard.checkWrite('abort_write', 'C:/r/Foo.groovy')
        guard.checkWrite('chunk_status', 'C:/r/Foo.groovy')
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-7: switched off asks nothing'() {
        given:
        guard.enforced = false
        when:
        guard.checkWrite('write', 'C:/r/Foo.groovy')
        guard.checkExecute('gradlew test', 'C:/r')
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-8: a throwing client passes'() {
        given:
        client.planGateCheck(_, _) >> { throw new IllegalStateException('boom') }
        expect:
        guard.checkWrite('write', 'C:/r/Foo.groovy') == null
    }
}