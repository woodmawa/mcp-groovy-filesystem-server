package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.read.ReadResponseHelper
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS 0.9.78 -- the worktree tool, and what stands down inside a worktree.
 *
 * AW's code harness (agents-working-together WP3) was first written with AW editing files in the worktree itself.
 * Will, 2026-10-09 12:52: better that FS does it, so the protections built into FS apply. This pins both halves:
 * every action against a REAL temporary git repository, asserted on the files and on git's own answers; and the
 * three Claude-facing mechanisms that must not fire on a worktree copy -- the ontology re-index after a write,
 * ONTOLOGY-GATE on a read, PLAN-GATE on a write or a build -- each with its control on a path outside the root.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS -- WT the worktree tool and the gates that stand down inside one')
class WorktreeServiceSpec extends Specification {

    @Autowired WorktreeService worktree
    @Autowired WorktreeRoots roots
    @Autowired FileReadService fileReadService
    @Autowired PathService pathService

    @TempDir Path tempDir

    Path repo
    String savedRoot
    FileWriteService savedWriter
    ReadResponseHelper savedHelper
    List<String> afterWrites = []

    static final String FOO = 'src/main/groovy/Foo.groovy'
    static final String SPEC = 'src/test/groovy/FooSpec.groovy'

    def setup() {
        savedRoot = roots.root
        savedWriter = worktree.fileWriteService
        savedHelper = fileReadService.responseHelper
        roots.root = tempDir.resolve('wts').toString()
        repo = tempDir.resolve('repo')
        Files.createDirectories(repo.resolve('src/main/groovy'))
        repo.resolve(FOO).text = 'class Foo {\n    int twice(int x) {\n        return x + x\n    }\n\n    int same(int x) {\n        return x\n    }\n}\n'
        repo.resolve('build.gradle').text = '// build\n'
        git(repo, 'init', '-q')
        git(repo, 'config', 'user.email', 'spec@example.test')
        git(repo, 'config', 'user.name', 'spec')
        git(repo, 'config', 'core.autocrlf', 'false')
        git(repo, 'add', '-A')
        git(repo, 'commit', '-q', '-m', 'base')
        // The failing spec Claude would supply: in the main tree, not committed.
        Files.createDirectories(repo.resolve('src/test/groovy'))
        repo.resolve(SPEC).text = 'class FooSpec { /* expects twice(2) == 5 */ }\n'
        // The after-write step, recorded instead of run.
        List<String> calls = afterWrites
        worktree.fileWriteService = new FileWriteService(pathService) {
            @Override
            void afterWrite(String normalizedPath, String hash) { calls << normalizedPath }
        }
    }

    def cleanup() {
        roots.root = savedRoot
        worktree.fileWriteService = savedWriter
        fileReadService.responseHelper = savedHelper
    }

    // ---------------------------------------------------------------- helpers

    static String git(Path dir, String... args) {
        Process p = new ProcessBuilder((['git', '-C', dir.toString()] + args.toList()) as List<String>).redirectErrorStream(true).start()
        String out = p.inputStream.getText('UTF-8')
        p.waitFor()
        out
    }

    private static Map body(McpResponse r) {
        String text = (r?.result?.content ? r.result.content[0].text : '') as String
        boolean isError = r?.result?.isError == true
        Map parsed = [:]
        try { parsed = new JsonSlurper().parseText(text) as Map } catch (Exception ignored) { }
        parsed + [_isError: isError, _text: text]
    }

    private Map call(Map args) { body(worktree.handleToolCall('worktree', args as Map<String, Object>, 'wt-spec')) }

    private String create(String name = 'task0001') {
        Map r = call(action: 'create', repoDir: repo.toString(), name: name, specFiles: [SPEC])
        assert !r._isError : r._text
        r.worktree as String
    }

    // ---------------------------------------------------------------- create

    def 'WT-1: create makes a detached worktree at HEAD under the root, with the uncommitted spec copied in'() {
        when:
        Map r = call(action: 'create', repoDir: repo.toString(), name: 'task0001', specFiles: [SPEC])
        Path wt = Path.of(r.worktree as String)

        then: 'a real worktree, where the root says'
        r.success == true
        wt.parent == roots.rootPath()
        wt.fileName.toString() == 'repo-task0001'
        git(repo, 'worktree', 'list').contains('repo-task0001')
        r.base == git(repo, 'rev-parse', 'HEAD').trim()

        and: 'the committed source and the uncommitted spec are both in it'
        wt.resolve(FOO).text == repo.resolve(FOO).text
        wt.resolve(SPEC).text == repo.resolve(SPEC).text
        (r.specHashes as Map)[SPEC] == WorktreeService.sha(repo.resolve(SPEC))

        and: 'the main tree is as it was'
        git(repo, 'status', '--porcelain').trim() == '?? src/test/'
    }

    def 'WT-1b: create refuses a repository outside the allowed directories, a bad name and a missing spec file'() {
        expect:
        call(action: 'create', repoDir: 'C:/Windows/System32', name: 'x')._isError
        call(action: 'create', repoDir: repo.toString(), name: '../escape')._isError
        call(action: 'create', repoDir: repo.toString(), name: 'ok', specFiles: ['no/such/Spec.groovy'])._isError
    }

    // ---------------------------------------------------------------- read

    def 'WT-2: read returns exact lines by relative path, and nothing from outside the worktree'() {
        given:
        String wt = create()

        when:
        Map r = call(action: 'read', worktree: wt, file: FOO, startLine: 2, endLine: 3)

        then:
        r.file == FOO
        r.lines == ['    int twice(int x) {', '        return x + x']
        r.totalLines == 9
        r.cut == false

        and: 'the main tree, by .. or by absolute path, is refused with none of its text'
        call(action: 'read', worktree: wt, file: '../../repo/' + FOO).refused == WorktreeService.READ_REFUSED
        Map abs = call(action: 'read', worktree: wt, file: repo.resolve(FOO).toString())
        abs.refused == WorktreeService.READ_REFUSED
        !abs._text.contains('twice')
    }

    // ---------------------------------------------------------------- edit

    def 'WT-3: edit replaces the one match and says what the file was before'() {
        given:
        String wt = create()
        Path f = Path.of(wt).resolve(FOO)
        String before = WorktreeService.sha(f)

        when:
        Map r = call(action: 'edit', worktree: wt, file: FOO, find: 'return x + x', replace: 'return x + x + 1')

        then:
        r.ok == true
        r.created == false
        r.originalSha == before
        f.text.contains('return x + x + 1')

        and: 'the main tree was not touched'
        repo.resolve(FOO).text.contains('return x + x\n')
    }

    def 'WT-3b: no match and two matches are refused by name and write nothing'() {
        given:
        String wt = create()
        Path f = Path.of(wt).resolve(FOO)
        String before = f.text

        expect:
        call(action: 'edit', worktree: wt, file: FOO, find: 'return y', replace: 'return 1').refused == WorktreeService.FIND_NOT_FOUND
        call(action: 'edit', worktree: wt, file: FOO, find: 'return x', replace: 'return 1').refused == WorktreeService.FIND_AMBIGUOUS
        call(action: 'edit', worktree: wt, file: FOO, find: '', replace: 'class Other {}').refused == WorktreeService.FIND_REQUIRED
        f.text == before
    }

    def 'WT-3c: an edit follows FS line-ending policy (source files are LF), and an empty find creates a new file with the text exactly as given'() {
        given:
        String wt = create()
        Path f = Path.of(wt).resolve(FOO)
        f.bytes = f.text.replace('\n', '\r\n').getBytes('UTF-8')

        when:
        Map edit = call(action: 'edit', worktree: wt, file: FOO, find: 'return x + x', replace: 'return 2 * x')
        Map made = call(action: 'edit', worktree: wt, file: 'src/main/groovy/Bar.groovy', find: '', replace: 'class Bar {\n    String s = "a\\nb"\n}\n')

        then: 'the match was found across CRLF, and the file is written LF as any FS write of a .groovy file is (WriteUtils.LF_EXTENSIONS)'
        edit.ok == true
        new String(f.bytes, 'UTF-8').contains('return 2 * x\n')
        !new String(f.bytes, 'UTF-8').contains('\r')

        and: 'the backslash-n inside the string literal is still two characters'
        made.ok == true
        made.created == true
        made.originalSha == WorktreeService.NEW_FILE
        Path.of(wt).resolve('src/main/groovy/Bar.groovy').text == 'class Bar {\n    String s = "a\\nb"\n}\n'
    }

    def 'WT-4: an edit cannot leave the worktree'() {
        given:
        String wt = create()
        String mainBefore = repo.resolve(FOO).text

        expect: 'by .., by absolute path, or into git\'s own data'
        call(action: 'edit', worktree: wt, file: '../../repo/' + FOO, find: 'return x + x', replace: 'return 0')._isError
        call(action: 'edit', worktree: wt, file: repo.resolve(FOO).toString(), find: 'return x + x', replace: 'return 0')._isError
        call(action: 'edit', worktree: wt, file: '.git', find: '', replace: 'x')._isError
        call(action: 'edit', worktree: repo.toString(), file: FOO, find: 'return x + x', replace: 'return 0')._isError
        repo.resolve(FOO).text == mainBefore
    }

    def 'WT-5: the structural guard is in front of an agent\'s edit -- a replacement that drops a brace is refused'() {
        given:
        String wt = create()
        Path f = Path.of(wt).resolve(FOO)
        String before = f.text

        when: 'the edit removes the closing brace of twice()'
        Map r = call(action: 'edit', worktree: wt, file: FOO, find: '        return x + x\n    }', replace: '        return x + x')

        then:
        r.refused == WorktreeService.EDIT_REFUSED
        (r.reason as String).toLowerCase().contains('structural')
        f.text == before
    }

    // ---------------------------------------------------------------- diff, hashes

    def 'WT-6: diff holds the edited and the created file and not the spec; hashes answers per file'() {
        given:
        String wt = create()
        call(action: 'edit', worktree: wt, file: FOO, find: 'return x + x', replace: 'return x + x + 1')
        call(action: 'edit', worktree: wt, file: 'src/main/groovy/Bar.groovy', find: '', replace: 'class Bar {}\n')

        when:
        Map d = call(action: 'diff', worktree: wt, files: [FOO, 'src/main/groovy/Bar.groovy'])
        Map h = call(action: 'hashes', worktree: wt, files: [SPEC, 'no/such.txt'])

        then:
        (d.diff as String).contains('+        return x + x + 1')
        (d.diff as String).contains('+class Bar {}')
        !(d.diff as String).contains('FooSpec')
        (h.hashes as Map)[SPEC] == WorktreeService.sha(repo.resolve(SPEC))
        (h.hashes as Map).containsKey('no/such.txt')
        (h.hashes as Map)['no/such.txt'] == null
    }

    // ---------------------------------------------------------------- apply

    def 'WT-7: apply copies the changed files onto the main tree and runs the after-write step for each'() {
        given:
        String wt = create()
        Map e1 = call(action: 'edit', worktree: wt, file: FOO, find: 'return x + x', replace: 'return x + x + 1')
        Map e2 = call(action: 'edit', worktree: wt, file: 'src/main/groovy/Bar.groovy', find: '', replace: 'class Bar {}\n')

        when:
        Map r = call(action: 'apply', worktree: wt, repoDir: repo.toString(),
                     edited: [(FOO): e1.originalSha, 'src/main/groovy/Bar.groovy': e2.originalSha])

        then: 'the main tree has the change, uncommitted'
        r.success == true
        r.applied as Set == [FOO, 'src/main/groovy/Bar.groovy'] as Set
        repo.resolve(FOO).text.contains('return x + x + 1')
        repo.resolve('src/main/groovy/Bar.groovy').text == 'class Bar {}\n'
        git(repo, 'status', '--porcelain').contains(' M ' + FOO)
        git(repo, 'log', '--oneline').readLines().size() == 1

        and: 'each applied file went through the after-write step (registry, ontology re-index)'
        afterWrites.size() == 2
        afterWrites.any { it.replace('\\', '/').endsWith('repo/' + FOO) }
    }

    def 'WT-7b: a file that changed in the main tree since is a conflict, and then NOTHING is applied'() {
        given:
        String wt = create()
        Map e1 = call(action: 'edit', worktree: wt, file: FOO, find: 'return x + x', replace: 'return x + x + 1')
        Map e2 = call(action: 'edit', worktree: wt, file: 'src/main/groovy/Bar.groovy', find: '', replace: 'class Bar {}\n')
        repo.resolve(FOO).text = repo.resolve(FOO).text.replace('return x\n', 'return x // touched by Will\n')
        String mainNow = repo.resolve(FOO).text

        when:
        Map r = call(action: 'apply', worktree: wt, repoDir: repo.toString(),
                     edited: ['src/main/groovy/Bar.groovy': e2.originalSha, (FOO): e1.originalSha])

        then:
        r.refused == WorktreeService.APPLY_CONFLICT
        (r.conflicts as List).size() == 1
        repo.resolve(FOO).text == mainNow

        and: 'not even the file that had no conflict'
        !Files.exists(repo.resolve('src/main/groovy/Bar.groovy'))
        afterWrites.isEmpty()
    }

    /** The main tree's copy with its line endings the other way round -- what git autocrlf does between two checkouts. */
    private void flipLineEndings(String file) {
        String t = new String(repo.resolve(file).bytes, 'ISO-8859-1')
        repo.resolve(file).bytes = (t.contains('\r\n') ? t.replace('\r\n', '\n') : t.replace('\n', '\r\n')).getBytes('ISO-8859-1')
    }

    def 'WT-7c: a main-tree file that differs from the worktree start only in its line endings is not a conflict'() {
        given: 'live, 2026-10-09: core.autocrlf=true checked the worktree out CRLF; the main tree, written by FS, held LF'
        String wt = create()
        Map e1 = call(action: 'edit', worktree: wt, file: FOO, find: 'return x + x', replace: 'return x + x + 1')
        String before = repo.resolve(FOO).text
        flipLineEndings(FOO)

        expect: 'the bytes really differ now'
        repo.resolve(FOO).text != before

        when:
        Map r = call(action: 'apply', worktree: wt, repoDir: repo.toString(), edited: [(FOO): e1.originalSha])

        then:
        r.success == true
        r.applied == [FOO]
        repo.resolve(FOO).text.contains('return x + x + 1')
    }

    def 'WT-7d: different line endings do not hide a change in the text -- still a conflict, nothing applied'() {
        given:
        String wt = create()
        Map e1 = call(action: 'edit', worktree: wt, file: FOO, find: 'return x + x', replace: 'return x + x + 1')
        repo.resolve(FOO).text = repo.resolve(FOO).text.replace('class Foo', 'class Foo /* touched */')
        flipLineEndings(FOO)
        byte[] mainNow = repo.resolve(FOO).bytes

        when:
        Map r = call(action: 'apply', worktree: wt, repoDir: repo.toString(), edited: [(FOO): e1.originalSha])

        then:
        r.refused == WorktreeService.APPLY_CONFLICT
        repo.resolve(FOO).bytes == mainNow
        afterWrites.isEmpty()
    }

    // ---------------------------------------------------------------- remove

    def 'WT-8: remove deletes the worktree and git forgets it; only a worktree under the root can be named'() {
        given:
        String wt = create()

        expect: 'the repository itself is not a worktree to remove'
        call(action: 'remove', worktree: repo.toString(), repoDir: repo.toString())._isError
        Files.exists(repo.resolve(FOO))

        when:
        Map r = call(action: 'remove', worktree: wt, repoDir: repo.toString())

        then:
        r.removed == true
        !Files.exists(Path.of(wt))
        !git(repo, 'worktree', 'list').contains('repo-task0001')
    }

    // ---------------------------------------------------------------- what stands down inside a worktree

    def 'WT-9: a write inside a worktree is not re-indexed; the same write outside it is'() {
        given:
        ContextServerClient cs = Mock()
        FileWriteService writer = new FileWriteService(pathService)
        writer.contextServerClient = cs
        writer.worktreeRoots = roots
        String inside = roots.rootPath().resolve('repo-task0001/src/main/groovy/Foo.groovy').toString()
        String outside = repo.resolve(FOO).toString()

        when:
        writer.afterWrite(inside, 'abc123abc123')

        then:
        0 * cs.reindexFileAsync(_)
        0 * cs.upsertFileRegistryAsync(*_)
        0 * cs.invalidateFileAsync(_)

        when: 'the control'
        writer.afterWrite(outside, 'abc123abc123')

        then:
        1 * cs.reindexFileAsync(outside)
        1 * cs.upsertFileRegistryAsync(outside, 'abc123abc123', _, _)
    }

    def 'WT-10: PLAN-GATE is not asked about a write or a build inside a worktree; outside it is'() {
        given:
        ContextServerClient cs = Mock()
        FilesystemTelemetryService telemetry = Mock()
        telemetry.readActiveSessionId() >> 'sid-1'
        PlanGateGuard guard = new PlanGateGuard(contextServerClient: cs, telemetryService: telemetry, worktreeRoots: roots)
        String wt = create()
        String insideFile = Path.of(wt).resolve(FOO).toString()

        expect: 'the worktree is a planned artefact by the old rule -- it has a .git -- so only the new rule spares it'
        PlanGateGuard.isPlannedArtefact(insideFile)

        when:
        String w = guard.checkWrite('replace', insideFile)
        String x = guard.checkExecute('gradlew test', wt)

        then:
        w == null
        x == null
        0 * cs.planGateCheck(_, _)

        when: 'the control: the same calls on the repository'
        guard.checkWrite('replace', repo.resolve(FOO).toString())
        guard.checkExecute('gradlew test', repo.toString())

        then:
        2 * cs.planGateCheck(_, 'sid-1') >> [allow: true]
    }

    def 'WT-11: ONTOLOGY-GATE is not consulted for a read inside a worktree; outside it is'() {
        given:
        String wt = create()
        ReadResponseHelper helper = Mock()
        fileReadService.responseHelper = helper

        when:
        fileReadService.handleToolCall('file_read', [action: 'range', path: Path.of(wt).resolve(FOO).toString(),
                                                     options: [startLine: 1, maxLines: 2]] as Map<String, Object>, 'wt-gate-in')

        then:
        0 * helper.checkOntologyGate(*_)

        when: 'the control'
        fileReadService.handleToolCall('file_read', [action: 'range', path: repo.resolve(FOO).toString(),
                                                     options: [startLine: 1, maxLines: 2]] as Map<String, Object>, 'wt-gate-out')

        then:
        1 * helper.checkOntologyGate(*_)
    }
}
