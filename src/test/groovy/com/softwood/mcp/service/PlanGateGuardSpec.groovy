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

    def 'PGG-9: git or gradle inside strings, comments and here-strings is not a command'() {
        when:
        String a = guard.checkExecute('$s = "then git push and gradlew build"; Write-Host $s', 'C:/r')
        String b = guard.checkExecute("Write-Host 'a; git push'\n# git commit -m x", 'C:/r')
        String c = guard.checkExecute("\$body = @'\ngit push origin main\n.\\gradlew.bat test\n'@\nSet-Content f.txt \$body", 'C:/r')
        String d = guard.checkExecute("cat <<'EOF' > notes.md\ngit push\nEOF", 'C:/r')
        then:
        [a, b, c, d].every { it == null }
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-10: read-only git and gradle housekeeping are not asked about'() {
        when:
        guard.checkExecute('git status --porcelain; git log -1; git -C C:/x rev-parse HEAD', 'C:/r')
        guard.checkExecute('.\\gradlew.bat --stop', 'C:/r')
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-11: a command in statement position is found, with the directory it really runs in'() {
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
        String msg = guard.checkExecute('cd C:/a; git commit -am x; cd C:/b; git commit -am y', 'C:/r')
        then:
        1 * client.planGateCheck({ it.workingDir == 'C:/a' }, 'sid-1') >> [allow: false, component: 'a:git', practices: []]
        1 * client.planGateCheck({ it.workingDir == 'C:/b' }, 'sid-1') >> [allow: false, component: 'b:git', practices: []]
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