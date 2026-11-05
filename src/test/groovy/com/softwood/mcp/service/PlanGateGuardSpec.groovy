package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS 0.9.37 C1 -- PLAN-GATE at FS's mutating entry points. CS decides; this pins how FS asks and
 * what it does with every kind of answer.
 *
 * Since FS 0.9.122 (decision 440) only execute can be refused (outward git, or a gradle run sent with an edit);
 * a file_write is noted to CS for the same-batch check and never refused, whatever CS answers.
 *
 * PGG-1  a refusal from CS becomes a message naming the practices and the retry
 * PGG-2  allow passes
 * PGG-3  CS unreachable (null answer) passes and counts unavailable
 * PGG-4  no claimed session passes without asking CS, and counts unavailable
 * PGG-5  execute is gated only for gradlew / git; everything else is not asked about
 * PGG-6  non-writing file_write actions are not noted
 * PGG-7  the gate switched off asks nothing
 * PGG-8  a throwing client passes
 * PGG-9  a scratch extension is never a plan, wherever it lives
 * PGG-10 a file outside any git repository is not a plan; inside one it is
 * PGG-20 FS 0.9.122: a write is noted to CS and never refused, even if CS answers with a refusal
 */
class PlanGateGuardSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)

    @TempDir Path tmp
    /** A code file inside a directory that carries a .git marker. */
    String repoFile
    String repoDir

    def setup() {
        telemetry.readActiveSessionId() >> 'sid-1'
        Files.createDirectories(tmp.resolve('repo/.git'))
        Files.createDirectories(tmp.resolve('repo/src'))
        repoFile = tmp.resolve('repo/src/Foo.groovy').toString()
        repoDir = tmp.resolve('repo').toString()
    }

    /** The one call that can still be refused: an outward git command. */
    private String push() { guard.checkExecute('git push origin main', repoDir) }

    def 'PGG-20: a write is noted to CS and never refused, whatever CS answers'() {
        when:
        guard.noteWrite('write', repoFile)

        then: 'CS is told about the write, so the same-batch gradle check can see it'
        1 * client.planGateCheck([tool: 'file_write', path: repoFile], 'sid-1') >> [
            allow: false, component: 'Foo', practices: [[id: 7, title: 't', summary: 's']]]
        noExceptionThrown()
    }

    def 'PGG-9: a scratch extension is never a plan, even inside a repo'() {
        expect:
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/src/alpha.txt').toString())
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/build.log').toString())

        when:
        guard.noteWrite('write', tmp.resolve('repo/src/alpha.txt').toString())

        then: 'so CS is never told about it'
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-10: outside a repository nothing is a plan; inside one a code file is'() {
        given:
        Files.createDirectories(tmp.resolve('notes'))

        expect:
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('notes/ARC-STATE.md').toString())
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('notes/Probe.groovy').toString())
        PlanGateGuard.isPlannedArtefact(repoFile)
        PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/build.gradle').toString())
    }

    def 'PGG-11: FS 0.9.57 -- docs are never a plan, even inside a repo; build.gradle still is'() {
        expect:
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/CHANGELOG.md').toString())
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/README.MD').toString())
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/docs/asciidoc/index.adoc').toString())
        PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/build.gradle').toString())

        when:
        guard.noteWrite('write', tmp.resolve('repo/CHANGELOG.md').toString())

        then: 'so CS is never told about it'
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-1: a refusal names the practices and says the retry passes'() {
        given:
        client.planGateCheck(_, 'sid-1') >> [
            allow: false, component: 'Foo',
            practices: [[id: 7, valence: 'proscriptive', title: 'never do X to Foo', summary: 'because Y']],
            retry: 'Read these, then repeat the same call unchanged -- a retry is never refused.']
        when:
        String msg = push()
        then:
        msg.startsWith("PLAN-GATE: first mutating call on 'Foo'")
        msg.contains('#7 [proscriptive] never do X to Foo -- because Y')
        msg.contains('never refused')
    }

    def 'PGG-15: FS 0.9.80 SB-3 -- a second ask is worded as one and still lists what it asks about'() {
        given:
        client.planGateCheck(_, 'sid-1') >> [
            allow: false, component: 'Foo', reason: 'ack-required',
            practices: [[id: 7, valence: 'proscriptive', title: 'never do X to Foo', summary: 'because Y']],
            retry: 'Answer with options.planAck="7:y|c|n". (Second ask.)']
        when:
        String msg = push()
        then:
        msg.startsWith("PLAN-GATE (second ask) on 'Foo'")
        !msg.contains('first mutating call')
        msg.contains('may not have reached you')
        msg.contains('#7 [proscriptive] never do X to Foo -- because Y')
    }

    def 'PGG-16: FS 0.9.80 SB-3 -- a refire says the earlier refusal went unanswered and is shown again'() {
        given:
        client.planGateCheck(_, 'sid-1') >> [
            allow: false, component: 'Foo', reason: 'refired',
            practices: [[id: 7, valence: 'proscriptive', title: 'never do X to Foo', summary: 'because Y']],
            retry: 'Answer with options.planAck="7:y|c|n".']
        when:
        String msg = push()
        then:
        msg.startsWith("PLAN-GATE (shown again) on 'Foo'")
        !msg.contains('first mutating call')
        msg.contains('#7 [proscriptive]')
    }

    def 'PGG-17: FS 0.9.80 SB-3 -- a refusal with no practices is never shown as one; the call passes'() {
        given:
        client.planGateCheck(_, 'sid-1') >> [
            allow: false, component: 'Foo', reason: 'ack-required', practices: [], retry: 'x']
        when:
        String msg = push()
        then:
        msg == null
        guard.emptyRefusals.get() == 1
    }

    def 'PGG-18: FS 0.9.83 (chain fd3f3867) -- a passed empty refusal is reported to CS under its own outcome'() {
        given:
        client.planGateCheck(_, 'sid-1') >> [
            allow: false, component: 'Foo', reason: 'ack-required', practices: [], retry: 'x']
        when:
        String msg = push()
        then:
        msg == null
        1 * telemetry.recordToolCall('sid-1', 'plan_gate', 0, _, 'execute', null, 'plan_gate_empty_refusal', _)
        0 * telemetry.recordToolCall(_, _, _, _, _, _, 'plan_gate_answered', _)
    }

    def 'PGG-19: FS 0.9.83 -- an ordinary answer is still reported plan_gate_answered'() {
        given:
        client.planGateCheck(_, 'sid-1') >> [allow: false, component: 'Foo',
            practices: [[id: 7, valence: 'proscriptive', title: 't', summary: 's']]]
        when:
        String msg = push()
        then:
        msg != null
        1 * telemetry.recordToolCall('sid-1', 'plan_gate', 0, _, 'execute', null, 'plan_gate_answered', _)
    }

    def 'PGG-2: allow passes'() {
        given:
        client.planGateCheck(_, _) >> [allow: true, reason: 'already-gated']
        expect:
        push() == null
    }

    def 'PGG-3: CS unreachable passes and is counted'() {
        given:
        client.planGateCheck(_, _) >> null
        when:
        String msg = push()
        then:
        msg == null
        guard.unavailable.get() == 1
    }

    def 'PGG-4: no claimed session passes without asking'() {
        given:
        PlanGateGuard g = new PlanGateGuard(contextServerClient: client, telemetryService: Mock(FilesystemTelemetryService))
        when:
        String msg = g.checkExecute('git push origin main', repoDir)
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
        1 * client.planGateCheck({ it.script.startsWith('git ') }, 'sid-1') >> [allow: false, component: 'r:git', practices: [[id: 9, title: 't']]]
        b == null
        c.contains("'r:git'")
    }

    def 'PGG-9b: git or gradle inside strings, comments and here-strings is not a command'() {
        when:
        String a = guard.checkExecute('$s = "then git push and gradlew build"; Write-Host $s', 'C:/r')
        String b = guard.checkExecute("Write-Host 'a; git push'\n# git commit -m x", 'C:/r')
        String c = guard.checkExecute("\$body = @'\ngit push origin main\n.\\gradlew.bat test\n'@\nSet-Content f.txt \$body", 'C:/r')
        String d = guard.checkExecute("cat <<'EOF' > notes.md\ngit push\nEOF", 'C:/r')
        then:
        [a, b, c, d].every { it == null }
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-10b: read-only git and gradle housekeeping are not asked about'() {
        when:
        guard.checkExecute('git status --porcelain; git log -1; git -C C:/x rev-parse HEAD', 'C:/r')
        guard.checkExecute('.\\gradlew.bat --stop', 'C:/r')
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-11b: a command in statement position is found, with the directory it really runs in'() {
        when:
        guard.checkExecute('Set-Location C:/other; $out = git commit -m "wip"', 'C:/r')
        guard.checkExecute('git -C C:/x push origin main', 'C:/r')
        guard.checkExecute('if ($ok) { & C:/y/gradlew.bat installDist }', 'C:/r', 'install the new build')
        guard.checkExecute('cmd /c "cd sub && gradlew.bat test"', 'C:/r')
        then:
        1 * client.planGateCheck({ it.kind == 'git' && it.workingDir == 'C:/other' && it.command == 'git commit -m wip' }, 'sid-1') >> [allow: true]
        1 * client.planGateCheck({ it.kind == 'git' && it.workingDir == 'C:/x' && it.command == 'git -C C:/x push origin main' }, 'sid-1') >> [allow: true]
        1 * client.planGateCheck({ it.kind == 'gradle' && it.workingDir == 'C:/y' && it.intent == 'install the new build' }, 'sid-1') >> [allow: true]
        1 * client.planGateCheck({ it.kind == 'gradle' && it.workingDir == 'C:/r/sub' && it.command == 'gradlew test' }, 'sid-1') >> [allow: true]
    }

    def 'PGG-12: two gated repos in one script are asked about together, so one retry clears both'() {
        when:
        String msg = guard.checkExecute('cd C:/a; git push; cd C:/b; git push', 'C:/r')
        then:
        1 * client.planGateCheck({ it.workingDir == 'C:/a' }, 'sid-1') >> [allow: false, component: 'a:git', practices: [[id: 9, title: 't']]]
        1 * client.planGateCheck({ it.workingDir == 'C:/b' }, 'sid-1') >> [allow: false, component: 'b:git', practices: [[id: 9, title: 't']]]
        msg.contains("'a:git'") && msg.contains("'b:git'")
    }

    def 'PGG-13: a directory argument holding a shell variable is not taken as the repo'() {
        when:
        guard.checkExecute('foreach ($r in $repos) { git -C "C:/x/$r" commit -am y }', 'C:/r')
        guard.checkExecute('cd %REPO%; git push', 'C:/q')
        then:
        1 * client.planGateCheck({ it.workingDir == 'C:/r' && it.command.startsWith('git -C') }, 'sid-1') >> [allow: true]
        1 * client.planGateCheck({ it.workingDir == 'C:/q' }, 'sid-1') >> [allow: true]
    }

    def 'PGG-14: listing forms of tag, branch, remote, stash and config are not asked about; their writing forms are'() {
        when:
        guard.checkExecute('git tag -l "v1.*"; git tag; git branch; git branch --list; git branch -a; git remote -v; git stash list; git config --get user.name', 'C:/r')
        then:
        0 * client.planGateCheck(_, _)

        when:
        guard.checkExecute('git tag v1.0.0', 'C:/t')
        guard.checkExecute('git branch feature-x', 'C:/b')
        then:
        1 * client.planGateCheck({ it.workingDir == 'C:/t' }, 'sid-1') >> [allow: true]
        1 * client.planGateCheck({ it.workingDir == 'C:/b' }, 'sid-1') >> [allow: true]
    }

    def 'PGG-6: non-writing file_write actions are not noted'() {
        when:
        guard.noteWrite('abort_write', repoFile)
        guard.noteWrite('chunk_status', repoFile)
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-7: switched off asks nothing'() {
        given:
        guard.enforced = false
        when:
        guard.noteWrite('write', repoFile)
        guard.checkExecute('gradlew test', 'C:/r')
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-8: a throwing client passes'() {
        given:
        client.planGateCheck(_, _) >> { throw new IllegalStateException('boom') }
        when:
        guard.noteWrite('write', repoFile)
        then:
        noExceptionThrown()
        push() == null
    }
}
