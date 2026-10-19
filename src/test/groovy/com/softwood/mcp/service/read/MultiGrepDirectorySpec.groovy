package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * multi_grep given a directory.
 *
 * <p>FS 0.9.80 (obs 12291) made a directory a named refusal, MULTI_GREP_DIRECTORY, because before that it answered
 * inside a success envelope with totalMatches 0 -- which reads exactly like "no matches". The refusal was honest but
 * the mistake kept recurring (obs 12292, 12928: class multi-grep-directory). WP4 (2026-10-19, Will): remove the
 * mistake instead of reporting it -- a directory is expanded to its files and the expansion is REPORTED.</p>
 *
 * <ul>
 *   <li>MG-1 a directory among the paths is expanded, recursively, and its files are searched; the response says so</li>
 *   <li>MG-2 CONTROL files only search as before, with no expansion block</li>
 *   <li>MG-3 the expansion uses the file_search default code/text filter and skips build/VCS folders</li>
 *   <li>MG-4 an expansion over the cap is reported (expansion_capped, files_not_searched), never silently cut</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles('test')
@spock.lang.Tag('slow')   // WS10-C tiering: ~2.5 s -- a Spring context plus MG-4's 205-file cap fixture
class MultiGrepDirectorySpec extends Specification {

    @Autowired FileReadService fileReadService

    @TempDir Path tempDir

    private static String text(McpResponse r) { ((r.result as Map).content as List)[0].text as String }

    /** A refusal is plain text, not JSON: parse it as empty so a red lands on an assertion, never in when:. */
    private static Map parse(McpResponse r) {
        String t = text(r)
        return t?.trim()?.startsWith('{') ? (new JsonSlurper().parseText(t) as Map) : [:]
    }

    private File write(String rel, String content) {
        File f = tempDir.resolve(rel).toFile()
        f.parentFile.mkdirs()
        f.text = content
        return f
    }

    def "MG-1: a directory among the paths is expanded and its files searched"() {
        given:
        File top = write('top.groovy', 'class Top { def needle }')
        write('src/A.groovy', 'class A { def needle }')
        write('src/sub/B.java', 'class B { Object needle; }')
        File dir = tempDir.resolve('src').toFile()

        when:
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: 'multi_grep', options: [paths: [top.absolutePath, dir.absolutePath], pattern: 'needle']], 1)
        Map body = parse(r)
        List expanded = body.get('expanded') as List

        then:
        (r.result as Map).isError != true
        body.get('matchingFiles') == 3
        expanded.size() == 1
        (expanded[0] as Map).get('files') == 2
        ((expanded[0] as Map).get('dir') as String).replace('\\', '/').endsWith('/src')
    }

    def "MG-2 CONTROL: files only search as before"() {
        given:
        File f = write('b.groovy', 'class B { def needle }')

        when:
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: 'multi_grep', options: [paths: [f.absolutePath], pattern: 'needle']], 1)
        Map body = parse(r)

        then:
        (r.result as Map).isError != true
        body.get('matchingFiles') == 1
        !body.containsKey('expanded')
    }

    def "MG-3: expansion uses the default code/text filter and skips build and VCS folders"() {
        given:
        write('src/A.groovy', 'needle')
        write('src/C.bin', 'needle')
        write('src/build/D.groovy', 'needle')
        write('src/.git/E.txt', 'needle')
        write('src/node_modules/F.json', 'needle')
        File dir = tempDir.resolve('src').toFile()

        when:
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: 'multi_grep', options: [paths: [dir.absolutePath], pattern: 'needle']], 1)
        Map body = parse(r)
        List<String> hit = ((body.get('results') ?: []) as List).collect { ((it as Map).get('path') as String).replace('\\', '/') }

        then:
        body.get('matchingFiles') == 1
        hit.size() == 1
        hit[0].endsWith('/src/A.groovy')
    }

    def "MG-4: an expansion over the cap is reported, not silently cut"() {
        given:
        (1..205).each { int i -> write("many/f${i}.txt", 'needle') }
        File dir = tempDir.resolve('many').toFile()

        when:
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: 'multi_grep', options: [paths: [dir.absolutePath], pattern: 'needle', maxMatches: 1]], 1)
        Map body = parse(r)

        then:
        (r.result as Map).isError != true
        body.get('expansion_capped') == true
        body.get('files_not_searched') == 5
        body.get('matchingFiles') == 200
    }
}
