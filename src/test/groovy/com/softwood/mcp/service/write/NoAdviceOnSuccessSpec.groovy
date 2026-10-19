package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Tag
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path

/**
 * WP2 (optimised-learning, decision 384, Will 2026-10-19): a successful FS response carries the answer, not advice.
 *
 * <p>The 2026-10-19 census found advice riding on successful calls that no verdict says ever changed a call: a size
 * note and a session token meter on reads, a "re-read, check the closing brace" note on every patch touching the last
 * line, a "patched recently" caution on sequential patches. Each is either deleted -- the mistake it warns of is already
 * refused elsewhere (StructuralGuard's brace delta, expectedHash) -- or, where the mistake is real and unguarded,
 * promoted to a refusal: an append to a code file is refused before anything is written.</p>
 *
 * NA-1  append to a .groovy file is refused and the file is untouched (RED on 0.9.106: appended, warned)
 * NA-2  CONTROL: suppressCodeAppendWarning=true appends, and says nothing more
 * NA-3  a patch touching the last line carries no boundary_warning; tail_content and requires_reread stay (RED)
 * NA-4  a second patch within a minute carries no 'patched recently' hint (RED)
 * NA-5  a large read carries no _sizeWarning, _session_read_tokens or _session_health (RED)
 */
@Title('WP2 -- a successful FS response carries no advice')
@Tag('integration')
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class NoAdviceOnSuccessSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    private Map setupFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [action: 'write', path: f.absolutePath, content: text], 'setup')
        Map parsed = new JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: parsed.file_content_hash as String, file: f]
    }

    private static Map parse(McpResponse r) {
        new JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    def 'NA-1: append to a code file is refused before anything is written'() {
        given:
        def f = setupFile('Appendable.groovy', 'class Appendable { void foo() { } }\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write',
            [action: 'append', path: f.path, content: '// extra\n', options: [:]], 'test')

        then:
        r.result.isError == true
        ((r.result.content[0] as Map).text as String).startsWith('CODE_APPEND')
        (f.file as File).text == 'class Appendable { void foo() { } }\n'
    }

    def 'NA-2: CONTROL -- suppressCodeAppendWarning=true appends, and says nothing more'() {
        given:
        def f = setupFile('Appendable2.groovy', 'class Appendable2 { }\n')

        when:
        Map p = parse(fileWriteService.handleToolCall('file_write',
            [action: 'append', path: f.path, content: '// extra\n', options: [suppressCodeAppendWarning: true]], 'test'))

        then:
        p.success == true
        !p.containsKey('code_append_warning')
        (f.file as File).text == 'class Appendable2 { }\n// extra\n'
    }

    def 'NA-3: a patch touching the last line carries data, not a boundary warning'() {
        given:
        def f = setupFile('Tail.groovy', 'class Tail {\n    int a = 1\n    int b = 2\n}\n')

        when:
        Map p = parse(fileWriteService.handleToolCall('file_write', [action: 'patch', path: f.path,
            options: [expectedHash: f.hash, replacements: [[startLine: 3, endLine: 4, newText: '    int b = 3\n}']]]], 'test'))

        then:
        p.success == true
        !p.containsKey('boundary_warning')
        p.requires_reread == true
        p.tail_content != null
    }

    def 'NA-4: a second patch within a minute carries no patched-recently hint'() {
        given:
        def f = setupFile('Twice.groovy', 'class Twice {\n    int a = 1\n    int b = 2\n    int c = 3\n}\n')
        Map first = parse(fileWriteService.handleToolCall('file_write', [action: 'patch', path: f.path,
            options: [expectedHash: f.hash, replacements: [[startLine: 2, endLine: 2, newText: '    int a = 10']]]], 'test'))

        when:
        Map second = parse(fileWriteService.handleToolCall('file_write', [action: 'patch', path: f.path,
            options: [expectedHash: first.file_content_hash, replacements: [[startLine: 3, endLine: 3, newText: '    int b = 20']]]], 'test'))

        then:
        second.success == true
        !second.containsKey('hint')
    }

    def 'NA-5: a large read carries no size note, token meter or health nag'() {
        given:
        def f = setupFile('big.txt', (1..400).collect { int i -> 'line ' + i + ' ' + ('y' * 100) }.join('\n'))

        when:
        Map p = parse(fileReadService.handleToolCall('file_read',
            [action: 'read', path: f.path, options: [force: true]], 'test'))

        then:
        p.content != null
        !p.containsKey('_sizeWarning')
        !p.containsKey('_session_read_tokens')
        !p.containsKey('_session_health')
    }
}
