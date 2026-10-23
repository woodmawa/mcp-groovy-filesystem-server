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
 * FS 0.9.118 -- range reads are kept narrow at the tool (Will, 2026-10-23, CS decision 422).
 *
 * <p>The read guards stopped whole-file reads (full_read_pct 0 across the trend window) and repeats, but nothing
 * limited range WIDTH: session 2026-10-23-13-06 made 9 range reads averaging 149 lines and 2.1k tokens each, and the
 * avg_read_tok series turned regressing. The remedy is a guard, not a lesson (2026-10-19): a range wider than 80 lines
 * is refused RANGE_TOO_WIDE with the narrower routes named, unless options.force=true; a range with no width reads
 * 80 lines, not 100. head and tail share the served-ledger path but are not range reads and are not limited.</p>
 *
 * <ul>
 *   <li>RW-1 (RED on 0.9.117): maxLines 150 is refused RANGE_TOO_WIDE and returns no content.</li>
 *   <li>RW-2 (RED on 0.9.117): startLine 10 + endLine 160 (151 lines, the endLine form) is refused the same way.</li>
 *   <li>RW-3 (RED on 0.9.117): a range with no maxLines returns at most 80 lines.</li>
 *   <li>RW-4 CONTROL: exactly 80 lines is served, and 150 lines with force=true is served in full.</li>
 *   <li>RW-5 CONTROL: head lines=120 is not a range read and is served in full.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles('test')
class RangeWidthGuardSpec extends Specification {

    @Autowired FileReadService fileReadService

    @TempDir Path tempDir

    File f

    def setup() {
        f = tempDir.resolve('wide.txt').toFile()
        f.text = (1..300).collect { "line$it" }.join('\n')
    }

    private McpResponse read(String action, Map options) {
        fileReadService.handleToolCall('file_read', [action: action, path: f.absolutePath, options: options],
            "req-${System.nanoTime()}")
    }

    private String text(McpResponse r) { r.result?.content?.find { it.type == 'text' }?.text as String }

    private Map payload(McpResponse r) {
        String t = text(r)
        t ? new JsonSlurper().parseText(t) as Map : [:]
    }

    def 'RW-1 (RED on 0.9.117): a 150-line range is refused RANGE_TOO_WIDE with no content'() {
        when:
        McpResponse r = read('range', [startLine: 1, maxLines: 150])

        then:
        r.result?.isError == true
        text(r).startsWith('RANGE_TOO_WIDE')
        text(r).contains('80')
        !text(r).contains('line120')
    }

    def 'RW-2 (RED on 0.9.117): the endLine form over 80 lines is refused the same way'() {
        when:
        McpResponse r = read('range', [startLine: 10, endLine: 160])

        then:
        r.result?.isError == true
        text(r).startsWith('RANGE_TOO_WIDE')
    }

    def 'RW-3 (RED on 0.9.117): a range with no width reads 80 lines'() {
        when:
        Map p = payload(read('range', [startLine: 1, force: true]))

        then:
        p.startLine == 1
        p.endLine == 80
    }

    def 'RW-4 CONTROL: 80 lines is served, and 150 lines with force=true is served in full'() {
        when:
        Map eighty = payload(read('range', [startLine: 101, maxLines: 80]))
        Map forced = payload(read('range', [startLine: 1, maxLines: 150, force: true]))

        then:
        eighty.endLine == 180
        forced.endLine == 150
    }

    def 'RW-5 CONTROL: head is not a range read and is not limited'() {
        when:
        Map p = payload(read('head', [lines: 120, force: true]))

        then:
        (p.content as String).contains('line120')
    }
}
