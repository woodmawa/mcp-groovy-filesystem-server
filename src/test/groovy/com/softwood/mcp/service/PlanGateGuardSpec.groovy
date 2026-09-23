package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

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
 * PGG-9  FS 0.9.52: a scratch extension is never a plan, wherever it lives
 * PGG-10 FS 0.9.52: a file outside any git repository is not a plan; inside one it is
 */
class PlanGateGuardSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)

    @TempDir Path tmp
    /** A code file inside a directory that carries a .git marker -- what 'C:/r/Foo.groovy' used to stand for. */
    String repoFile

    def setup() {
        telemetry.readActiveSessionId() >> 'sid-1'
        Files.createDirectories(tmp.resolve('repo/.git'))
        Files.createDirectories(tmp.resolve('repo/src'))
        repoFile = tmp.resolve('repo/src/Foo.groovy').toString()
    }

    def 'PGG-9: a scratch extension is never a plan, even inside a repo'() {
        expect:
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/src/alpha.txt').toString())
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/build.log').toString())

        when:
        String msg = guard.checkWrite('write', tmp.resolve('repo/src/alpha.txt').toString())

        then: 'so CS is never asked about it'
        msg == null
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
        // Measured 7 days to 2026-09-23: 47 gate refusals on .md/.adoc (CHANGELOG, CLAUDE, README, the
        // book, usage and architecture docs). Their next call changed 0 of 17 times in the telemetry
        // window -- the practices shown there (#478 above all) were already being followed. build.gradle
        // carries real build logic as well as version stamps, so it stays gated.
        expect:
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/CHANGELOG.md').toString())
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/README.MD').toString())
        !PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/docs/asciidoc/index.adoc').toString())
        PlanGateGuard.isPlannedArtefact(tmp.resolve('repo/build.gradle').toString())

        when:
        String msg = guard.checkWrite('write', tmp.resolve('repo/CHANGELOG.md').toString())

        then: 'so CS is never asked about it'
        msg == null
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-1: a refusal names the practices and says the retry passes'() {
        given:
        client.planGateCheck([tool: 'file_write', path: repoFile], 'sid-1') >> [
            allow: false, component: 'Foo',
            practices: [[id: 7, valence: 'proscriptive', title: 'never do X to Foo', summary: 'because Y']],
            retry: 'Read these, then repeat the same call unchanged -- a retry is never refused.']
        when:
        String msg = guard.checkWrite('write', repoFile)
        then:
        msg.startsWith("PLAN-GATE: first mutating call on 'Foo'")
        msg.contains('#7 [proscriptive] never do X to Foo -- because Y')
        msg.contains('never refused')
    }

    def 'PGG-2: allow passes'() {
        given:
        client.planGateCheck(_, _) >> [allow: true, reason: 'already-gated']
        expect:
        guard.checkWrite('replace', repoFile) == null
    }

    def 'PGG-3: CS unreachable passes and is counted'() {
        given:
        client.planGateCheck(_, _) >> null
        when:
        String msg = guard.checkWrite('write', repoFile)
        then:
        msg == null
        guard.unavailable.get() == 1
    }

    def 'PGG-4: no claimed session passes without asking'() {
        given:
        PlanGateGuard g = new PlanGateGuard(contextServerClient: client, telemetryService: Mock(FilesystemTelemetryService))
        when:
        String msg = g.checkWrite('write', repoFile)
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
        guard.checkWrite('abort_write', repoFile)
        guard.checkWrite('chunk_status', repoFile)
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-7: switched off asks nothing'() {
        given:
        guard.enforced = false
        when:
        guard.checkWrite('write', repoFile)
        guard.checkExecute('gradlew test', 'C:/r')
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'PGG-8: a throwing client passes'() {
        given:
        client.planGateCheck(_, _) >> { throw new IllegalStateException('boom') }
        expect:
        guard.checkWrite('write', repoFile) == null
    }
}