package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
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
 * FS 0.9.86 -- WP4 slice B (decision 357): a worktree can take its judge spec as it was at a COMMIT.
 *
 * <p>A gold case for code is a real past change: the worktree is detached at the commit before the fix, and the spec
 * that judges it must be the fix commit's version. create copied specs from the main working tree, where several
 * have since grown cases that need later code -- so an old base would be unsolvable. {@code specRef} names the commit
 * the spec files are taken from.</p>
 *
 * <p>WR-1 with specRef the spec is the ref's text (not the base's, not the main tree's). WR-2 a path absent at the ref
 * is refused before anything is made. WR-3 CONTROL without specRef the main tree's copy, as before. WR-4 remove takes
 * a worktree whose build output passes the Windows path limit (git refused one on 2026-10-13).</p>
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS -- WR worktree create from a spec ref')
@spock.lang.Tag('integration')   // WS10-C tiering: starts real processes (shell/git/javac)
class WorktreeSpecRefSpec extends Specification {

    @Autowired WorktreeService worktree
    @Autowired WorktreeRoots roots

    @TempDir Path tempDir

    Path repo
    String savedRoot
    String baseSha
    String fixSha

    static final String SPEC = 'src/test/groovy/FooSpec.groovy'

    def setup() {
        savedRoot = roots.root
        roots.root = tempDir.resolve('wts').toString()
        repo = tempDir.resolve('repo')
        Files.createDirectories(repo.resolve('src/test/groovy'))
        repo.resolve('build.gradle').text = '// build\n'
        repo.resolve(SPEC).text = 'class FooSpec { /* BASE version */ }\n'
        git(repo, 'init', '-q')
        git(repo, 'config', 'user.email', 'spec@example.test')
        git(repo, 'config', 'user.name', 'spec')
        git(repo, 'config', 'core.autocrlf', 'false')
        git(repo, 'add', '-A')
        git(repo, 'commit', '-q', '-m', 'base')
        baseSha = git(repo, 'rev-parse', 'HEAD').trim()
        repo.resolve(SPEC).text = 'class FooSpec { /* FIX version */ }\n'
        git(repo, 'add', '-A')
        git(repo, 'commit', '-q', '-m', 'fix')
        fixSha = git(repo, 'rev-parse', 'HEAD').trim()
        repo.resolve(SPEC).text = 'class FooSpec { /* MAIN TREE, grown since */ }\n'
    }

    def cleanup() { roots.root = savedRoot }

    static String git(Path dir, String... args) {
        Process p = new ProcessBuilder((['git', '-C', dir.toString()] + args.toList()) as List<String>).redirectErrorStream(true).start()
        String out = p.inputStream.getText('UTF-8')
        p.waitFor()
        out
    }

    private Map call(Map args) {
        McpResponse r = worktree.handleToolCall('worktree', args as Map<String, Object>, 'wr-spec')
        String text = (r?.result?.content ? r.result.content[0].text : '') as String
        Map parsed = [:]
        try { parsed = new JsonSlurper().parseText(text) as Map } catch (Exception ignored) { }
        parsed + [_isError: r?.result?.isError == true, _text: text]
    }

    def 'WR-1: with specRef the spec is the ref\'s version, at the base commit'() {
        when:
        Map r = call(action: 'create', repoDir: repo.toString(), name: 'wr1', baseRef: baseSha, specRef: fixSha, specFiles: [SPEC])
        Path wt = Path.of(r.worktree as String)

        then:
        !r._isError
        r.base == baseSha
        wt.resolve(SPEC).text == 'class FooSpec { /* FIX version */ }\n'
        r.specHashes[SPEC] == WorktreeService.sha(wt.resolve(SPEC))
    }

    def 'WR-2: a spec path absent at the ref is refused, and nothing is made'() {
        when:
        Map r = call(action: 'create', repoDir: repo.toString(), name: 'wr2', baseRef: baseSha, specRef: fixSha,
                     specFiles: ['src/test/groovy/NoSuchSpec.groovy'])

        then:
        r._isError
        r._text.contains('NoSuchSpec.groovy')
        !Files.exists(roots.rootPath().resolve('repo-wr2'))
        !git(repo, 'worktree', 'list').contains('repo-wr2')
    }

    def 'WR-3 CONTROL: without specRef the main tree copy, as before'() {
        when:
        Map r = call(action: 'create', repoDir: repo.toString(), name: 'wr3', baseRef: baseSha, specFiles: [SPEC])

        then:
        !r._isError
        Path.of(r.worktree as String).resolve(SPEC).text == 'class FooSpec { /* MAIN TREE, grown since */ }\n'
    }

    def 'WR-4: remove takes a worktree whose files pass the Windows path limit'() {
        given:
        Map r = call(action: 'create', repoDir: repo.toString(), name: 'wr4', specFiles: [SPEC])
        Path wt = Path.of(r.worktree as String)
        Path deep = wt
        (1..12).each { deep = deep.resolve('build-output-directory-segment-' + it) }
        Files.createDirectories(deep)
        Files.writeString(deep.resolve('generated-class-file-with-a-long-name.class'), 'x')

        expect: 'the fixture really is past MAX_PATH'
        deep.toString().length() > 260

        when:
        Map rm = call(action: 'remove', repoDir: repo.toString(), worktree: wt.toString())

        then:
        rm.removed == true
        !Files.exists(wt)
        !git(repo, 'worktree', 'list').contains('repo-wr4')
    }
}
