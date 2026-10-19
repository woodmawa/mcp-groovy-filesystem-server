package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * optimised-learning WP3 (obs 13000, chain 1e7cd887). A flow node's FS call arrives on the HTTP companion, whose own
 * active session is null, and PlanGateGuard took its session only from there -- so a gradle-test-summary run never asked
 * PLAN-GATE at all, and R3 / same-batch could never refuse the route every edit-test-same-batch row used. The AW call
 * carries the flow's session (X-Mcp-Caller-Session) and run/node (X-Aw-Run / X-Aw-Node) in AwCallContext; the guard
 * now asks as that session and flags the ask flowNode, so CS applies only the pending-edit checks (a flow cannot ack).
 *
 * FN-1  a flow-node gradle call with no process session asks CS as the flow's session, flagged flowNode (red on 0.9.109)
 * FN-2  a PENDING_EDIT answer to it is shown, so the node fails with the message (red on 0.9.109: never asked)
 * FN-3  CONTROL: a chat call (no run/node) asks as the process session and is not flagged
 * FN-4  CONTROL: a call with a declared session but no run/node is not flagged a flow node
 */
class FlowNodePlanGateSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)

    @TempDir Path tmp
    String repoDir

    def setup() {
        Files.createDirectories(tmp.resolve('repo-a/.git'))
        repoDir = tmp.resolve('repo-a').toString()
    }

    def cleanup() { AwCallContext.clear() }

    def 'FN-1: a flow-node gradle call asks as the flow session, flagged flowNode'() {
        given:
        telemetry.readActiveSessionId() >> null
        AwCallContext.set('run-1', 'run-gradle', 'sid-flow')
        Map sent = null
        String sid = null

        when:
        guard.checkExecute('gradlew test', repoDir)

        then:
        1 * client.planGateCheck(_, _) >> { Map a, String s -> sent = a; sid = s; [allow: true] }
        sid == 'sid-flow'
        sent.flowNode == true
        sent.tool == 'execute'
    }

    def 'FN-2: a PENDING_EDIT answer on the flow route is shown'() {
        given:
        telemetry.readActiveSessionId() >> null
        AwCallContext.set('run-1', 'run-gradle', 'sid-flow')
        client.planGateCheck(_, _) >> [allow: false, reason: 'PENDING_EDIT', cause: 'same-batch', component: 'repo-a:gradle',
                                       message: 'PENDING_EDIT (same batch): a file_write to repo-a reached the gate 300 ms before']

        expect:
        guard.checkExecute('gradlew test', repoDir)?.contains('same batch')
    }

    def 'FN-3: CONTROL a chat call asks as the process session, unflagged'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-chat'
        Map sent = null
        String sid = null

        when:
        guard.checkExecute('gradlew test', repoDir)

        then:
        1 * client.planGateCheck(_, _) >> { Map a, String s -> sent = a; sid = s; [allow: true] }
        sid == 'sid-chat'
        !sent.containsKey('flowNode')
    }

    def 'FN-4: CONTROL a declared session without run/node is not a flow node'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-chat'
        AwCallContext.set(null, null, 'sid-declared')
        Map sent = null

        when:
        guard.checkExecute('gradlew test', repoDir)

        then:
        1 * client.planGateCheck(_, _) >> { Map a, String s -> sent = a; [allow: true] }
        !sent.containsKey('flowNode')
    }
}
