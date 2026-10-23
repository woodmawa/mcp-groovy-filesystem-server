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
 * FS 0.9.119 -- a multi read is held to the range width per file (Will, 2026-10-23, CS decision 424).
 *
 * <p>FS 0.9.118 capped range at 80 lines, but multi (up to 10 files, each whole) was untouched: over the 14 days to
 * 2026-10-23 it made 25 calls averaging 3.6k tokens, 19 of them over 2k, the largest 6.4k. Each file in a multi is now
 * served at most {@link com.softwood.mcp.service.FileReadService#MAX_RANGE_LINES} lines unless options.force=true, and
 * a capped file says so -- _truncated, total_lines and next_startLine -- so the rest is one range away and the served
 * ledger never records a partial file as whole (servedMulti skips _truncated files).</p>
 *
 * <ul>
 *   <li>MW-1 (RED on 0.9.118): a 300-line file in a multi is served lines 1-80, marked _truncated, total_lines 300,
 *       next_startLine 81.</li>
 *   <li>MW-2 CONTROL: a 10-line file in the same multi is served whole and is not marked.</li>
 *   <li>MW-3 CONTROL: with force=true the 300-line file is served whole.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles('test')
class MultiWidthGuardSpec extends Specification {

    @Autowired FileReadService fileReadService

    @TempDir Path tempDir

    File wide
    File narrow

    def setup() {
        wide = tempDir.resolve('wide.txt').toFile()
        wide.text = (1..300).collect { "line$it" }.join('\n')
        narrow = tempDir.resolve('narrow.txt').toFile()
        narrow.text = (1..10).collect { "row$it" }.join('\n')
    }

    private Map multi(Map options) {
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: 'multi', options: [paths: [wide.absolutePath, narrow.absolutePath]] + options],
            "req-${System.nanoTime()}")
        String t = r.result?.content?.find { it.type == 'text' }?.text as String
        t ? new JsonSlurper().parseText(t) as Map : [:]
    }

    private static Map fileFor(Map payload, String name) {
        (payload.files as List<Map>).find { (it.path as String).endsWith(name) }
    }

    def 'MW-1 (RED on 0.9.118): a 300-line file in a multi is served 80 lines and says where the rest starts'() {
        when:
        Map w = fileFor(multi([force: false]), 'wide.txt')
        List<String> lines = (w.content as String).readLines()

        then:
        lines.size() == 80
        lines.last() == 'line80'
        w._truncated == true
        w.total_lines == 300
        w.next_startLine == 81
    }

    def 'MW-2 CONTROL: a short file in the same multi is served whole and unmarked'() {
        when:
        Map n = fileFor(multi([force: false]), 'narrow.txt')

        then:
        (n.content as String).readLines().size() == 10
        !n._truncated
    }

    def 'MW-3 CONTROL: force=true serves the long file whole'() {
        when:
        Map w = fileFor(multi([force: true]), 'wide.txt')

        then:
        (w.content as String).readLines().size() == 300
        !w._truncated
    }
}
