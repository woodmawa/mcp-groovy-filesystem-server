package com.softwood.mcp.service.read

import com.softwood.mcp.controller.McpController
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.StructureCache
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FS 0.9.35 WP-G G4 -- Claude is not sent what it already has.
 *
 * <p>Measured 2026-09-16 over Claude's own file_read calls since 09-13: 131 range reads, 74 of them
 * on a file already read that session (62k tokens); 34 repeat greps (26k); 10 repeat get_method
 * (11k). Across 867 range reads since 09-01, FOUR were served from the cache. Two reasons:</p>
 * <ul>
 *   <li>range de-duplication matched the EXACT (start, end) pair, and paging through a large file
 *       almost never asks for the same window twice -- it asks for overlapping ones;</li>
 *   <li>the get_method shortcut probed and recorded a (0,0) sentinel that CS's /rangeCache refuses
 *       ({@code startLine < 1}), so it never hit -- and had it worked, one method's hit would have
 *       answered for every other method in the file. CT-FS-GM-AUTO-1 proved it against a stub
 *       that accepted (0,0).</li>
 * </ul>
 *
 * CONTRACT:
 *  CT-RD-1  a range wholly inside lines already served is answered unchanged (and says so)
 *  CT-RD-2  an overlapping range returns only the lines not yet served, and names what was
 *  CT-RD-3  options.force=true always returns the full range
 *  CT-RD-4  a changed file (different hash) is served in full
 *  CT-RD-5  get_method repeat of the SAME method is unchanged; a DIFFERENT method is served
 *  CT-RD-6  grep repeat with the same pattern and options is unchanged; different options are served
 *  CT-RD-7  telemetry classifies a cache answer as 'unchanged', not 'success'
 *  CT-RD-8  RangeCoverage.uncovered: pure interval arithmetic
 */
@SpringBootTest
@ActiveProfiles('test')
class ReadDedupSpec extends Specification {

    @Autowired FileReadService    fileReadService
    @Autowired ReadResponseHelper helper

    @TempDir Path tempDir

    Map<String, List<List<Integer>>> served = [:]      // "path|hash" -> [[s,e],...]
    Set<String> keys = [] as Set                        // "path|key|hash"
    String hash = 'aabbccddeeff'

    def setup() {
        def cache = Stub(StructureCache) { getHash(_) >> { hash } }
        def cs = Stub(ContextServerClient) {
            isCsReachable() >> true
            storeFileHashAsync(_, _) >> {}
            lookupFileHash(_) >> null
            checkRangeCache(_, _, _, _) >> null
            recordRangeCacheAsync(_, _, _, _) >> { p, s, e, h -> served.computeIfAbsent("$p|$h" as String) { [] } << [s as int, e as int] }
            rangeCoverage(_, _) >> { p, h -> served.get("$p|$h" as String) ?: [] }
            servedKeySeen(_, _, _) >> { p, k, h -> keys.contains("$p|$k|$h" as String) }
            recordServedKeyAsync(_, _, _) >> { p, k, h -> keys << ("$p|$k|$h" as String) }
        }
        helper.contextServerClient = cs
        fileReadService.setContextServerClient(cs)
        fileReadService.setStructureCache(cache)
    }

    def cleanup() {
        helper.contextServerClient = null
        fileReadService.setContextServerClient(null)
        fileReadService.setStructureCache(null)
    }

    private File file(String name, int lines) {
        def f = tempDir.resolve(name).toFile()
        f.text = (1..lines).collect { "line$it" }.join('\n')
        f
    }

    private McpResponse call(String action, File f, Map opts) {
        fileReadService.handleToolCall('file_read', [action: action, path: f.absolutePath, options: opts],
            "req-${System.nanoTime()}")
    }

    private Map payload(McpResponse r) {
        def text = r.result?.content?.find { it.type == 'text' }?.text
        text ? new JsonSlurper().parseText(text) as Map : [:]
    }

    def 'CT-RD-1: a range inside served lines is unchanged'() {
        given:
        def f = file('a.txt', 200)
        call('range', f, [startLine: 1, maxLines: 100, allowNoLocate: true])

        when:
        Map p = payload(call('range', f, [startLine: 20, maxLines: 30, allowNoLocate: true]))

        then:
        p.unchanged == true
        p.cached == true
        !p.containsKey('content')
    }

    def 'CT-RD-2: an overlapping range returns only the new lines'() {
        given:
        def f = file('b.txt', 200)
        call('range', f, [startLine: 1, maxLines: 100, allowNoLocate: true])

        when:
        Map p = payload(call('range', f, [startLine: 50, maxLines: 101, allowNoLocate: true]))

        then:
        p.startLine == 101
        p.endLine == 150
        (p.content as String).startsWith('line101')
        p.already_served == ['1-100']

        and: 'and the new lines are now served too'
        payload(call('range', f, [startLine: 120, maxLines: 20, allowNoLocate: true])).unchanged == true
    }

    def 'CT-RD-3: force returns the full range'() {
        given:
        def f = file('c.txt', 50)
        call('range', f, [startLine: 1, maxLines: 50, allowNoLocate: true])

        expect:
        payload(call('range', f, [startLine: 1, maxLines: 50, force: true, allowNoLocate: true])).lines == 50
    }

    def 'CT-RD-4: a changed file is served in full'() {
        given:
        def f = file('d.txt', 50)
        call('range', f, [startLine: 1, maxLines: 50, allowNoLocate: true])
        hash = '112233445566'

        expect:
        payload(call('range', f, [startLine: 1, maxLines: 50, allowNoLocate: true])).lines == 50
    }

    def 'CT-RD-5: get_method is keyed by the method'() {
        given:
        def f = tempDir.resolve('G.groovy').toFile()
        f.text = 'class G {\n  def alpha() {\n    1\n  }\n  def beta() {\n    2\n  }\n}\n'
        Map first = payload(call('get_method', f, [method: 'alpha', allowNoLocate: true]))

        expect:
        !first.containsKey('unchanged')
        payload(call('get_method', f, [method: 'alpha', allowNoLocate: true])).unchanged == true
        !payload(call('get_method', f, [method: 'beta', allowNoLocate: true])).containsKey('unchanged')
    }

    def 'CT-RD-6: grep is keyed by pattern and options'() {
        given:
        def f = file('e.txt', 30)
        call('grep', f, [pattern: 'line1', allowNoLocate: true])

        expect:
        payload(call('grep', f, [pattern: 'line1', allowNoLocate: true])).unchanged == true
        !payload(call('grep', f, [pattern: 'line1', contextLines: 2, allowNoLocate: true])).containsKey('unchanged')
    }

    def 'CT-RD-7: a cache answer is classified unchanged'() {
        given:
        def f = file('h.txt', 20)
        call('range', f, [startLine: 1, maxLines: 20, allowNoLocate: true])

        expect:
        McpController.extractOutcome(call('range', f, [startLine: 1, maxLines: 10, allowNoLocate: true])) == 'unchanged'
    }

    def 'CT-RD-8: interval arithmetic'() {
        expect:
        RangeCoverage.uncovered(1, 100, [[1, 100]]) == null
        RangeCoverage.uncovered(50, 150, [[1, 100]]) == [101, 150]
        RangeCoverage.uncovered(1, 100, [[20, 30]]) == [1, 100]
        RangeCoverage.uncovered(1, 100, [[1, 30], [61, 100]]) == [31, 60]
        RangeCoverage.uncovered(1, 50, []) == [1, 50]
        RangeCoverage.uncovered(10, 20, [[1, 12], [15, 30]]) == [13, 14]
        RangeCoverage.describe([[1, 30], [20, 40], [61, 70]]) == ['1-40', '61-70']
    }
}
