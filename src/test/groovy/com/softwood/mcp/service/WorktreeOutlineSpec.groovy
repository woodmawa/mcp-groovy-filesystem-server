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
 * FS 0.9.87 (Will 2026-10-13, decision 358) -- worktree outline: where things are in a file, without reading it.
 *
 * <p>The first harness eval (AW run d0dbe851) failed two of three cases with ZERO edits: a local coder spent all 12
 * steps re-reading lines 1-200 of a 1,588- and a 552-line file and never reached the code it had to change. AW gives
 * the agent an outline of each target on its first step; AW does no file work, so FS answers it -- the same AST
 * scanner file_read structure uses, scoped to the worktree.</p>
 *
 * <p>WO-1 a Groovy file's classes and methods come back with their start and end lines. WO-2 a path outside the
 * worktree, or a missing file, is refused by name. WO-3 CONTROL a file with no code structure answers no entries.</p>
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS -- WO worktree outline')
@spock.lang.Tag('integration')   // WS10-C tiering: starts real processes (shell/git/javac)
class WorktreeOutlineSpec extends Specification {

    @Autowired WorktreeService worktree
    @Autowired WorktreeRoots roots

    @TempDir Path tempDir

    Path repo
    String savedRoot
    String wt

    static final String FOO = 'src/main/groovy/Foo.groovy'

    def setup() {
        savedRoot = roots.root
        roots.root = tempDir.resolve('wts').toString()
        repo = tempDir.resolve('repo')
        Files.createDirectories(repo.resolve('src/main/groovy'))
        repo.resolve('build.gradle').text = '// build\n'
        repo.resolve('README.txt').text = 'just words\n'
        repo.resolve(FOO).text = '''\
package demo

class Foo {
    int a = 1

    int twice(int x) {
        return x + x
    }

    static String name() {
        'foo'
    }
}
'''
        WorktreeSpecRefSpec.git(repo, 'init', '-q')
        WorktreeSpecRefSpec.git(repo, 'config', 'user.email', 'spec@example.test')
        WorktreeSpecRefSpec.git(repo, 'config', 'user.name', 'spec')
        WorktreeSpecRefSpec.git(repo, 'config', 'core.autocrlf', 'false')
        WorktreeSpecRefSpec.git(repo, 'add', '-A')
        WorktreeSpecRefSpec.git(repo, 'commit', '-q', '-m', 'base')
        wt = call([action: 'create', repoDir: repo.toString(), name: 'wo-spec']).worktree as String
    }

    def cleanup() {
        try { call([action: 'remove', worktree: wt, repoDir: repo.toString()]) } catch (Exception ignored) { }
        roots.root = savedRoot
    }

    private Map call(Map args) {
        McpResponse r = worktree.handleToolCall('worktree', args as Map<String, Object>, 'wo-spec')
        String text = (r?.result?.content ? r.result.content[0].text : '') as String
        Map parsed = [:]
        try { parsed = new JsonSlurper().parseText(text) as Map } catch (Exception ignored) { }
        parsed + [_isError: r?.result?.isError == true, _text: text]
    }

    def 'WO-1: a Groovy file outlines its classes and methods with start and end lines'() {
        when:
        Map o = call([action: 'outline', worktree: wt, file: FOO])

        then:
        !o._isError
        o.file == FOO
        o.totalLines == 13
        Map twice = (o.entries as List<Map>).find { it.type == 'method' && (it.content as String).contains('twice') }
        twice.line == 6
        twice.endLine == 8
        Map name = (o.entries as List<Map>).find { it.type == 'method' && (it.content as String).contains('name') }
        name.line == 10
        (o.entries as List<Map>).any { it.type == 'class' && (it.content as String).contains('Foo') && it.line == 3 }
    }

    def 'WO-2: a path outside the worktree, or a file that is not there, is refused by name'() {
        expect:
        call([action: 'outline', worktree: wt, file: '../../outside/Evil.groovy']).refused == 'WORKTREE_READ_REFUSED'
        call([action: 'outline', worktree: wt, file: 'src/main/groovy/Missing.groovy']).refused == 'WORKTREE_READ_REFUSED'
    }

    def 'WO-3: CONTROL -- a file with no code structure answers no entries, not an error'() {
        when:
        Map o = call([action: 'outline', worktree: wt, file: 'README.txt'])

        then:
        !o._isError
        !o.refused
        o.entries == []
        o.totalLines == 1
    }
}
