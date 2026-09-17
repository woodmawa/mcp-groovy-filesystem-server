package com.softwood.mcp.service.read

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
 * FS 0.9.40 K2 (decision 206): EVERY read action asks the served ledger, so a repeat read of
 * content this chat already holds is answered `unchanged` without the caller passing anything.
 *
 * Before 0.9.40 only range, grep and get_method asked. head, tail, whole-file read, structure,
 * multi and multi_grep re-sent in full; range truncation recorded lines it never sent, so the
 * next range call was told "already served" for text nobody saw.
 */
@SpringBootTest
@ActiveProfiles('test')
class ServedReadDedupSpec extends Specification {

    @Autowired FileReadService    fileReadService
    @Autowired ReadResponseHelper helper

    @TempDir Path tempDir

    Map<String, Object> store = [:]
    static final String H = 'aabbcc112233'

    def setup() {
        store.clear()
        def cache = Stub(StructureCache) { getHash(_) >> H; peekHash(_) >> H }
        def cs = Stub(ContextServerClient) {
            isCsReachable()                   >> true
            storeFileHashAsync(_, _)          >> {}
            lookupFileHash(_)                 >> null
            recordRangeCacheAsync(_, _, _, _) >> { p, s, e, h -> store["$p:$s:$e" as String] = 'r' }
            rangeCoverage(_, _)               >> { p, h -> store.keySet().findAll { it.startsWith("$p:") && !it.contains('#') }
                                                     .collect { String k -> k.tokenize(':')[-2..-1]*.toInteger() } }
            servedKeySeen(_, _, _)            >> { p, k, h -> store.containsKey("$p#$k" as String) }
            recordServedKeyAsync(_, _, _)     >> { p, k, h -> store["$p#$k" as String] = 'k' }
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

    private String file(String name, int lines, int width = 0) {
        File f = tempDir.resolve(name).toFile()
        f.text = (1..lines).collect { i -> width ? ('x' * width) : "line$i" }.join('\n')
        f.absolutePath.replace('\\', '/')
    }

    private Map call(String action, String path, Map options = [:]) {
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: action, path: path, options: options], "req-${System.nanoTime()}")
        assert r.error == null : "${action} failed: ${r.error?.message}"
        new JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    private String callMulti(String action, Map options) {
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: action, options: options], "req-${System.nanoTime()}")
        assert r.error == null : "${action} failed: ${r.error?.message}"
        r.result.content[0].text as String
    }

    private boolean recorded(String p, int s, int e) { store.containsKey("$p:$s:$e" as String) }

    def 'SRD-1: head records its lines and a repeat head is unchanged'() {
        given:
        String p = file('h.txt', 30)
        when:
        Map first  = call('head', p, [lines: 10])
        Map second = call('head', p, [lines: 10])
        then:
        (first.content as String).startsWith('line1')
        recorded(p, 1, 10)
        second.unchanged == true
        !second.content
    }

    def 'SRD-2: tail records its lines and a repeat tail is unchanged'() {
        given:
        String p = file('t.txt', 30)
        when:
        Map first  = call('tail', p, [lines: 5])
        Map second = call('tail', p, [lines: 5])
        then:
        (first.content as String).startsWith('line26')
        recorded(p, 26, 30)
        second.unchanged == true
    }

    def 'SRD-3: a whole-file read records every line; the repeat is unchanged; force re-sends'() {
        given:
        String p = file('r.txt', 20)
        when:
        Map first  = call('read', p)
        Map second = call('read', p)
        Map forced = call('read', p, [force: true])
        then:
        (first.content as String).contains('line20')
        recorded(p, 1, 20)
        second.unchanged == true
        !second.content
        (second.hint as String).contains('force=true')
        (forced.content as String).contains('line20')
    }

    def 'SRD-4: a range inside a whole-file read already served is unchanged'() {
        given:
        String p = file('rr.txt', 20)
        call('read', p)
        when:
        Map r = call('range', p, [startLine: 5, maxLines: 5])
        then:
        r.unchanged == true
    }

    def 'SRD-5: a truncated range records only the whole lines it actually sent'() {
        given:
        String p = file('wide.txt', 20, 1500)
        when:
        Map r = call('range', p, [startLine: 1, maxLines: 20])
        int sent = (r.content as String).split('\n').length
        then:
        r._truncated == true
        sent < 20
        r.endLine == sent
        recorded(p, 1, sent)
        !recorded(p, 1, 20)
        (r._truncatedNote as String).contains("startLine=${sent + 1}")
    }

    def 'SRD-6: a repeat structure is unchanged; force re-sends'() {
        given:
        File f = tempDir.resolve('S.groovy').toFile()
        f.text = 'class S {\n    void a() {}\n    void b() {}\n}\n'
        String p = f.absolutePath.replace('\\', '/')
        when:
        Map first  = call('structure', p)
        Map second = call('structure', p)
        Map forced = call('structure', p, [force: true])
        then:
        !first.unchanged
        second.unchanged == true
        !forced.unchanged
    }

    def 'SRD-7: multi does not re-send a file this chat already holds'() {
        given:
        String a = file('ma.txt', 5)
        String b = file('mb.txt', 5)
        call('read', a)
        when:
        Map m = new JsonSlurper().parseText(callMulti('multi', [paths: [a, b]])) as Map
        List<Map> results = (m.results ?: m.files) as List<Map>
        Map ra = results.find { (it.path as String).replace('\\', '/') == a }
        Map rb = results.find { (it.path as String).replace('\\', '/') == b }
        then:
        ra.unchanged == true
        !ra.content
        (rb.content as String).contains('line5')
        recorded(b, 1, 5)
    }

    def 'SRD-8: a repeat multi_grep answers the paths it already searched as unchanged'() {
        given:
        String a = file('ga.txt', 5)
        String b = file('gb.txt', 5)
        when:
        callMulti('multi_grep', [paths: [a, b], pattern: 'line3'])
        Map second = new JsonSlurper().parseText(callMulti('multi_grep', [paths: [a, b], pattern: 'line3'])) as Map
        then:
        (second.unchanged_paths as List).size() == 2
        !(second.results)
    }

    def 'SRD-9: no stale knownHash advice rides on reads when the ledger is live'() {
        given:
        String p = file('nh.txt', 10)
        when:
        Map r = call('range', p, [startLine: 1, maxLines: 3])
        then:
        !r.containsKey('_knownhash_hint')
    }

    def 'SRD-10: the missing-knownHash advisory is off by default -- a read without a hash is not a violation'() {
        expect:
        ReadResponseHelper.getDeclaredField('missingKhWarnEnabled')
            .getAnnotation(org.springframework.beans.factory.annotation.Value).value()
            .endsWith(':false}')
    }
}
