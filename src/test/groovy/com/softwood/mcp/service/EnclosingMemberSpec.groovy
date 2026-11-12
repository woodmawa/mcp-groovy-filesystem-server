package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FS 0.9.128 -- brief 2026-11-07 item 4 (a) and (b): make the symbol the data.
 *
 * <p>2026-11-07: 2 symbol reads against 51 line ranges found by 28 content searches and 14 greps -- e.g.
 * CompilerQueue.groovy 98-142 read to reach dispose(), where {@code symbol=CompilerQueue.dispose} was the exact call.
 * A grep hit in Groovy/Java now names the member it sits in ({@code in}); a line range lying inside one method names
 * it ({@code symbol}, {@code symbol_lines}). Asserted on the response as the chat receives it.</p>
 */
@CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class EnclosingMemberSpec extends Specification {

    @Autowired FileReadService   fileReadService
    @Autowired FileSearchService fileSearchService
    @Autowired PathService       pathService

    @TempDir Path tempDir

    static final String SRC = '''package demo

class CompilerQueue {

    int size = 0

    void dispose(String reason) {
        int closed = 0
        closed++
        println "disposed: " + reason
    }

    List<String> drain() {
        List<String> out = []
        out << 'disposed: drained'
        return out
    }
}
'''
    // dispose: lines 7-11; drain: lines 13-17; class: 3-18

    private File src() {
        File f = tempDir.resolve('CompilerQueue.groovy').toFile()
        f.text = SRC
        f
    }

    private String p(File f) { pathService.normalizePath(f.absolutePath) }

    private static Map body(McpResponse r) {
        assert r.error == null
        new JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    def 'EM-1: a grep hit inside a method names Class.method; a hit outside any method names nothing'() {
        given:
        File f = src()

        when:
        Map b = body(fileReadService.handleToolCall('file_read', [action: 'grep', path: p(f), options: [pattern: 'disposed|int size']], 'g'))
        Map byLine = (b.matches as List<Map>).collectEntries { [(it.line as int): it] }

        then:
        byLine[10].in == 'CompilerQueue.dispose'
        byLine[15].in == 'CompilerQueue.drain'
        byLine[5].in == null
    }

    def 'EM-1c: a COMPACT grep hit names its member too -- compact drops the path echo, not the symbol'() {
        given:
        File f = src()

        when:
        Map b = body(fileReadService.handleToolCall('file_read', [action: 'grep', path: p(f), options: [pattern: 'disposed|int size', compact: true]], 'gc'))
        Map byLine = (b.matches as List<Map>).collectEntries { [(it.line as int): it] }

        then:
        b.path == null
        byLine[10].in == 'CompilerQueue.dispose'
        byLine[15].in == 'CompilerQueue.drain'
        byLine[5].in == null
    }

    def 'EM-2: multi_grep and file_search content hits name their member too'() {
        given:
        File f = src()

        when:
        Map mg = body(fileReadService.handleToolCall('file_read',
            [action: 'multi_grep', options: [pattern: 'closed\\+\\+', paths: [p(f)]]], 'mg'))
        Map fs = body(fileSearchService.handleToolCall('file_search',
            [action: 'content', path: tempDir.toFile().absolutePath, options: [contentPattern: 'out <<']], 'fs'))

        then:
        (mg.results[0].matches as List<Map>)[0].in == 'CompilerQueue.dispose'
        (fs.results[0].matches as List<Map>)[0].in == 'CompilerQueue.drain'
    }

    def 'EM-3: a range lying inside one method names it and its lines; a range across two methods names none'() {
        given:
        File f = src()

        when:
        Map inside = body(fileReadService.handleToolCall('file_read',
            [action: 'range', path: p(f), options: [startLine: 8, maxLines: 3, force: true]], 'r1'))
        Map across = body(fileReadService.handleToolCall('file_read',
            [action: 'range', path: p(f), options: [startLine: 9, maxLines: 7, force: true]], 'r2'))

        then:
        inside.symbol == 'CompilerQueue.dispose'
        inside.symbol_lines == '7-11'
        across.symbol == null
    }

    def 'EM-4: a hit in a non-code file is left as it was'() {
        given:
        File f = tempDir.resolve('notes.md').toFile()
        f.text = 'dispose of this\n'

        when:
        Map b = body(fileReadService.handleToolCall('file_read', [action: 'grep', path: p(f), options: [pattern: 'dispose']], 'g2'))

        then:
        (b.matches as List<Map>)[0].containsKey('in') == false
    }
}
