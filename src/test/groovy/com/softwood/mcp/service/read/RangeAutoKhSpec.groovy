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
 * Contract spec for FIX-KH-RANGE-AUTO (FS 0.8.81).
 *
 * Problem: range/get_method reads never auto-hit the range cache because
 * checkRangeCache is gated on the caller supplying knownHash. Even when CS
 * holds the hash (recorded on first read), the gate skips the check.
 *
 * Fix: Before the 'if (fileHash)' gate, derive hash from StructureCache
 * (in-memory/lazy). Fall through transparently on any miss.
 *
 * Testability: FileReadService exposes setContextServerClient() and
 * setStructureCache() — plain Groovy setter calls, no reflection needed.
 * Spec is intentionally NOT @CompileStatic — Spock runs on the dynamic
 * Groovy runtime anyway; static compilation on specs only causes pain.
 *
 * CT-FS-RANGE-AUTO-1: range auto-hits cache on second call, no knownHash
 * CT-FS-RANGE-AUTO-2: range auto-miss when checkRangeCache returns null
 * CT-FS-RANGE-AUTO-3: explicit knownHash backward-compat preserved
 * CT-FS-RANGE-AUTO-4: first read falls through (no prior cache entry)
 * CT-FS-GM-AUTO-1:    get_method auto-hits via (0,0) sentinel on second call
 * CT-FS-HINT-RANGE-1: _knownhash_hint emitted when no knownHash passed
 */
@SpringBootTest
@ActiveProfiles('test')
class RangeAutoKhSpec extends Specification {

    @Autowired FileReadService    fileReadService
    @Autowired ReadResponseHelper helper

    @TempDir Path tempDir

    def setup() {
        helper.autoKhLookupEnabled   = true
        helper.autoKhHintsSuppressed = false  // hints must flow for CT-FS-HINT-RANGE-1
    }

    def cleanup() {
        // Restore production wiring via the explicit setters
        helper.contextServerClient = null
        fileReadService.setContextServerClient(null)
        fileReadService.setStructureCache(null)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private File writeFile(String name, String content) {
        def f = tempDir.resolve(name).toFile()
        f.text = content
        f
    }

    private McpResponse callRange(File f, int start, int max, Map extra = [:]) {
        fileReadService.handleToolCall('file_read',
            [action: 'range', path: f.absolutePath, options: [startLine: start, maxLines: max] + extra],
            "req-${System.nanoTime()}")
    }

    private McpResponse callGetMethod(File f, String method) {
        fileReadService.handleToolCall('file_read',
            [action: 'get_method', path: f.absolutePath, options: [method: method]],
            "req-${System.nanoTime()}")
    }

    private Map payload(McpResponse r) {
        def text = r.result?.content?.find { it.type == 'text' }?.text
        text ? new JsonSlurper().parseText(text) as Map : [:]
    }

    /**
     * Wire a stub CS + stub StructureCache into the service.
     * stubHash: what getHash() returns for any path (simulates warm cache).
     * rangeStore: shared map backing recordRangeCacheAsync / checkRangeCache.
     */
    private void wire(Map rangeStore, String stubHash) {
        def cache = Stub(StructureCache) { getHash(_) >> stubHash }
        def cs    = Stub(ContextServerClient) {
            isCsReachable()                   >> true
            storeFileHashAsync(_, _)          >> {}
            recordRangeCacheAsync(_, _, _, _) >> { p, s, e, h -> rangeStore["$p:$s:$e"] = 'cached-at' }
            checkRangeCache(_, _, _, _)       >> { p, s, e, h -> rangeStore["$p:$s:$e"] }
            lookupFileHash(_)                 >> null
        }
        helper.contextServerClient = cs
        fileReadService.setContextServerClient(cs)
        fileReadService.setStructureCache(cache)
    }

    // -----------------------------------------------------------------------
    // CT-FS-RANGE-AUTO-1
    // First call records entry; second call derives hash from StructureCache
    // and auto-hits the range cache without the caller passing knownHash.
    // -----------------------------------------------------------------------
    def 'CT-FS-RANGE-AUTO-1: range auto-hits cache on second call with no knownHash'() {
        given:
        def f = writeFile('auto1.txt', (1..10).collect { "line$it" }.join('\n'))
        wire([:], 'aabbcc112233')

        when: 'first read -- populates range cache'
        def first = callRange(f, 1, 5)

        then: 'full content, not a cache hit'
        first.error == null
        !payload(first).containsKey('cached')

        when: 'second read -- same lines, NO knownHash passed'
        def second = callRange(f, 1, 5)

        then: 'auto range-cache hit'
        second.error == null
        payload(second).cached         == true
        payload(second).is_repeat_call == true
    }

    // -----------------------------------------------------------------------
    // CT-FS-RANGE-AUTO-2
    // checkRangeCache returns null (hash mismatch / file changed) → full read.
    // -----------------------------------------------------------------------
    def 'CT-FS-RANGE-AUTO-2: range auto-miss when checkRangeCache returns null'() {
        given:
        def f = writeFile('auto2.txt', (1..10).collect { "line$it" }.join('\n'))
        // CS always misses regardless of stored hash
        def cs = Stub(ContextServerClient) {
            isCsReachable()                   >> true
            storeFileHashAsync(_, _)          >> {}
            recordRangeCacheAsync(_, _, _, _) >> {}
            checkRangeCache(_, _, _, _)       >> null
            lookupFileHash(_)                 >> null
        }
        helper.contextServerClient = cs
        fileReadService.setContextServerClient(cs)
        fileReadService.setStructureCache(Stub(StructureCache) { getHash(_) >> 'somehash000000' })

        when:
        def r = callRange(f, 1, 5)

        then: 'full content returned — not a cache hit'
        r.error == null
        !payload(r).containsKey('cached')
        !payload(r).containsKey('unchanged')
    }

    // -----------------------------------------------------------------------
    // CT-FS-RANGE-AUTO-3
    // Explicit knownHash takes priority over auto-derive and still works.
    // -----------------------------------------------------------------------
    def 'CT-FS-RANGE-AUTO-3: explicit knownHash still works (backward compat)'() {
        given:
        def f = writeFile('auto3.txt', (1..10).collect { "line$it" }.join('\n'))
        def cs = Stub(ContextServerClient) {
            isCsReachable()                          >> true
            storeFileHashAsync(_, _)                 >> {}
            recordRangeCacheAsync(_, _, _, _)        >> {}
            checkRangeCache(_, 1, 5, 'explicitHash') >> 'cached-at'
            checkRangeCache(_, _, _, _)              >> null
            lookupFileHash(_)                        >> null
        }
        helper.contextServerClient = cs
        fileReadService.setContextServerClient(cs)
        fileReadService.setStructureCache(Stub(StructureCache) { getHash(_) >> 'differentHash11' })

        when:
        def r = callRange(f, 1, 5, [knownHash: 'explicitHash'])

        then: 'cache hit via the explicit path'
        r.error == null
        def p = payload(r)
        p.cached == true || p.unchanged == true
    }

    // -----------------------------------------------------------------------
    // CT-FS-RANGE-AUTO-4
    // structureCache cold (returns null) → no lookup attempted → full content.
    // -----------------------------------------------------------------------
    def 'CT-FS-RANGE-AUTO-4: first range read falls through when cache is cold'() {
        given:
        def f = writeFile('auto4.txt', (1..10).collect { "line$it" }.join('\n'))
        wire([:], null)   // null hash = cold miss, skip lookup entirely

        when:
        def r = callRange(f, 1, 5)

        then:
        r.error == null
        !payload(r).containsKey('cached')
    }

    // -----------------------------------------------------------------------
    // CT-FS-GM-AUTO-1
    // get_method records both real line range AND (0,0) sentinel on first call.
    // Second call hits the (0,0) sentinel → cached=true without caller passing hash.
    // -----------------------------------------------------------------------
    def 'CT-FS-GM-AUTO-1: get_method auto-hits (0,0) sentinel on second call'() {
        given:
        def f = writeFile('Sample.groovy', '''\
class Sample {
    String hello() { return "hi" }
}
''')
        wire([:], 'ccddee334455')

        when: 'first call records real range + (0,0) sentinel'
        def first = callGetMethod(f, 'hello')

        then: 'content returned, no error'
        first.error == null

        when: 'second call — no knownHash, should hit sentinel'
        def second = callGetMethod(f, 'hello')

        then:
        second.error == null
        payload(second).cached         == true
        payload(second).is_repeat_call == true
    }

    // -----------------------------------------------------------------------
    // CT-FS-HINT-RANGE-1
    // _knownhash_hint emitted on range reads when hint suppression is off
    // (suppression is now scoped to whole-file reads only, not range).
    // -----------------------------------------------------------------------
    def 'CT-FS-HINT-RANGE-1: _knownhash_hint emitted in range response'() {
        given:
        def f = writeFile('hint1.txt', (1..20).collect { "line$it" }.join('\n'))
        wire([:], null)  // no cached entry, full read fires
        helper.autoKhHintsSuppressed = false

        when:
        def r = callRange(f, 1, 5)

        then:
        r.error == null
        def p = payload(r)
        p._knownhash_hint != null
        (p._knownhash_hint as String).contains('file_content_hash=')
    }
}
