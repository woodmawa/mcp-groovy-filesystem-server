package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * R3, FS half (decision 381; CS half is PendingEditGateSpec). CS refuses a gradle call while this session has a
 * PLAN-GATE-refused, unretried edit to the same repo -- reason PENDING_EDIT, with a message and no practices, because
 * there is nothing to ack: only landing the edit clears it. FS 0.9.80 SB-3 passes any refusal that names no practice
 * (defence in depth against an unanswerable gate), so without this the CS refusal would be dropped here and the test
 * would run on the old code -- the exact mistake R3 exists to stop.
 *
 * PER-1  a PENDING_EDIT refusal is shown with CS's message (RED on 0.9.104: passed)
 * PER-2  CONTROL: any other refusal with no practices is still passed (SB-3 unchanged)
 */
class PendingEditRefusalSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)

    @TempDir Path tmp
    String repoDir

    def setup() {
        Files.createDirectories(tmp.resolve('repo-a/.git'))
        repoDir = tmp.resolve('repo-a').toString()
        telemetry.readActiveSessionId() >> 'sid-1'
    }

    def 'PER-1: a PENDING_EDIT refusal is shown with its message'() {
        given:
        client.planGateCheck(_, _) >> [allow: false, reason: 'PENDING_EDIT', component: 'repo-a:gradle',
                                       pending_edits: ['repo-a/FooService'],
                                       message: 'PENDING_EDIT: PLAN-GATE refused your edit to repo-a/FooService and it has not been retried']

        when:
        String msg = guard.checkExecute('gradlew testFast', repoDir)

        then:
        msg != null
        msg.contains('PENDING_EDIT')
        msg.contains('repo-a/FooService')
    }

    def 'PER-2: CONTROL -- another refusal with no practices is still passed'() {
        given:
        client.planGateCheck(_, _) >> [allow: false, reason: 'first-call', component: 'repo-a:gradle']

        expect:
        guard.checkExecute('gradlew testFast', repoDir) == null
    }
}
