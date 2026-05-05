package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ContextServerClient
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Contract spec for FIX-KH-AUTO -- whole-file auto-knownHash lookup (FS 0.8.77).
 *
 * CT-KH-AUTO-1: auto-hit when no knownHash passed and CS returns cached hash matching disk
 * CT-KH-AUTO-2: auto-miss when CS cached hash does not match current disk hash (file changed)
 * CT-KH-AUTO-3: explicit knownHash takes priority over auto-lookup (lookupFileHash NOT called)
 * CT-KH-AUTO-4: hash stored after every content-returning doRead (storeFileHashAsync called)
 * CT-KH-AUTO-5: CS returns null (fail-open) -- normal read proceeds
 * CT-KH-AUTO-6: auto-lookup NOT applied for doRange (Option A -- partial content safety)
 * CT-KH-AUTO-7: auto-hit response shape has unchanged=true and _auto_kh=true
 * CT-KH-AUTO-8: feature flag disabled -> auto-lookup not called even for doRead
 *
 * Approach:
 *   @SpringBootTest wires real beans. Per-test we inject a fresh Mock/Stub of
 *   ContextServerClient directly into ReadResponseHelper.contextServerClient.
 *   Spring wires FileContentReader -> ReadResponseHelper (same singleton), so
 *   replacing the field on the helper instance is sufficient.
 *
 * Path normalisation note:
 *   FS normalizes all paths via PathService (forward slashes, canonical form).
 *   Tests derive the normalized path using PathService rather than File.absolutePath
 *   to avoid Windows backslash vs forward-slash mismatches.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class FileHashAutoLookupSpec extends Specification {

    @Autowired ReadResponseHelper helper
    @Autowired FileContentReader  fileContentReader
    @Autowired com.softwood.mcp.service.PathService pathService

    @TempDir Path tempDir

    def setup() {
        helper.autoKhLookupEnabled = true
    }

    def cleanup() {
        helper.contextServerClient = null
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private File writeFile(String name, String content) {
        File f = tempDir.resolve(name).toFile()
        f.text = content
        f
    }

    /** Normalize path the same way FS does internally. */
    private String normPath(File f) {
        pathService.normalizePath(f.absolutePath)
    }

    private Map parseResp(McpResponse resp) {
        String text = resp.result?.content?.find { it.type == 'text' }?.text
        text ? (Map) new JsonSlurper().parseText(text) : [:]
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-1: auto-hit when CS cached hash matches disk
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-1: auto-hit when no knownHash passed and CS cached hash matches disk"() {
        given:
        File f = writeFile('auto1.groovy', 'class Auto1 {}')
        String normalized = normPath(f)
        String diskHash = helper.structureCache.getHash(normalized)

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(normalized) >> diskHash
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-1')
        Map data = parseResp(resp)

        then:
        data.unchanged == true
        data._auto_kh  == true
        data.file_content_hash == diskHash
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-2: auto-miss when cached hash does not match disk
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-2: auto-miss when cached hash does not match disk -- returns full content"() {
        given:
        File f = writeFile('auto2.groovy', 'class Auto2 { def hello() { "hello" } }')
        String normalized = normPath(f)

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(normalized) >> 'stalehashabc1'  // hash that won't match
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-2')
        Map data = parseResp(resp)

        then:
        !data.unchanged
        data.content?.contains('hello')
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-3: explicit knownHash -- auto-lookup NOT invoked (use Mock)
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-3: explicit knownHash takes priority -- auto-lookup not invoked"() {
        given:
        File f = writeFile('auto3.groovy', 'class Auto3 {}')
        String normalized = normPath(f)
        String diskHash = helper.structureCache.getHash(normalized)

        ContextServerClient csMock = Mock(ContextServerClient)
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [knownHash: diskHash], 'req-3')
        Map data = parseResp(resp)

        then: 'explicit hit -- lookupFileHash never called'
        0 * csMock.lookupFileHash(_)
        data.unchanged == true
        !data._auto_kh
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-4: storeFileHashAsync called after content-returning doRead
    // Uses normalized path -- FS calls storeFileHashAsync(normalized, hash)
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-4: hash stored after every content-returning doRead"() {
        given:
        File f = writeFile('auto4.groovy', 'class Auto4 {}')
        String normalized = normPath(f)

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(normalized) >> null   // no cache hit -- proceed to content
        helper.contextServerClient = csMock

        when:
        fileContentReader.doRead(normalized, [:], 'req-4')

        then: 'async store called exactly once with the normalized path'
        1 * csMock.storeFileHashAsync(normalized, _)
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-5: CS returns null (unreachable / no entry) -- fail-open
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-5: CS returns null -- fail-open, full content returned"() {
        given:
        File f = writeFile('auto5.groovy', 'class Auto5 { String name = "failopen" }')
        String normalized = normPath(f)

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(normalized) >> null
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-5')
        Map data = parseResp(resp)

        then:
        !data.unchanged
        data.content?.contains('failopen')
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-6: doRange -- auto-lookup NOT applied (Option A)
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-6: doRange does NOT invoke auto-lookup"() {
        given:
        File f = writeFile('auto6.groovy', (1..20).collect { "// line $it" }.join('\n'))
        String normalized = normPath(f)

        ContextServerClient csMock = Mock(ContextServerClient)
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRange(normalized, [startLine: 1, maxLines: 5], 'req-6')
        Map data = parseResp(resp)

        then: 'lookupFileHash never called for range'
        0 * csMock.lookupFileHash(_)
        !data.unchanged
        !data._auto_kh
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-7: auto-hit response shape
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-7: auto-hit response has unchanged=true and _auto_kh=true"() {
        given:
        File f = writeFile('auto7.groovy', 'class Auto7 {}')
        String normalized = normPath(f)
        String diskHash = helper.structureCache.getHash(normalized)

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(normalized) >> diskHash
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-7')
        Map data = parseResp(resp)

        then:
        data.unchanged        == true
        data._auto_kh         == true
        data.file_content_hash == diskHash
        data._note?.toString()?.contains('auto-detected')
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-8: feature flag off -- auto-lookup not called
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-8: feature flag disabled -- auto-lookup not invoked"() {
        given:
        File f = writeFile('auto8.groovy', 'class Auto8 {}')
        String normalized = normPath(f)
        helper.autoKhLookupEnabled = false

        ContextServerClient csMock = Mock(ContextServerClient)
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-8')
        Map data = parseResp(resp)

        then:
        0 * csMock.lookupFileHash(_)
        !data.unchanged

        cleanup:
        helper.autoKhLookupEnabled = true
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-9: CS returns malformed/non-hex hash -> full content, no unchanged
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-9: CS returns malformed hash -- full content returned, no unchanged"() {
        given:
        File f = writeFile('auto9.groovy', 'class Auto9 {}')
        String normalized = normPath(f)

        ContextServerClient csMock = Mock(ContextServerClient)
        // Return something that passes the null check but would fail hash comparison
        csMock.lookupFileHash(normalized) >> 'not-valid-hex-!!'  // malformed
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-9')
        Map data = parseResp(resp)

        then: 'malformed hash cannot match disk hash -- full content returned'
        !data.unchanged
        data.content != null
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-10: CS returns null on every call (simulating persistent outage)
    //   -> fail-open, full content always returned, no exception
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-10: persistent CS null returns -- fail-open on every call"() {
        given:
        File f = writeFile('auto10.groovy', 'class Auto10 { int x = 42 }')
        String normalized = normPath(f)

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(_) >> null   // always null
        helper.contextServerClient = csMock

        when: 'read twice'
        McpResponse r1 = fileContentReader.doRead(normalized, [:], 'req-10a')
        McpResponse r2 = fileContentReader.doRead(normalized, [:], 'req-10b')
        Map d1 = parseResp(r1)
        Map d2 = parseResp(r2)

        then: 'both return full content -- no unchanged, no exception'
        !d1.unchanged
        !d2.unchanged
        d1.content?.contains('42')
        d2.content?.contains('42')
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-11: same-length content change is detected (hash is content-based)
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-11: same-length content change detected -- not unchanged"() {
        given:
        File f = writeFile('auto11.groovy', 'class Auto11 { String s = "hello" }')
        String normalized = normPath(f)
        // First read -- get the hash
        McpResponse first = fileContentReader.doRead(normalized, [:], 'req-11a')
        Map d1 = parseResp(first)
        String hashV1 = d1.file_content_hash as String

        // Modify file to same-length content ('hello' -> 'jello')
        f.text = 'class Auto11 { String s = "jello" }'

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(normalized) >> hashV1   // CS still has old hash
        helper.contextServerClient = csMock

        when: 'read again -- file changed but same length'
        McpResponse second = fileContentReader.doRead(normalized, [:], 'req-11b')
        Map d2 = parseResp(second)

        then: 'hash mismatch detected -- full new content returned'
        !d2.unchanged
        d2.content?.contains('jello')
        d2.file_content_hash != hashV1
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-12: hint suppressed when auto-lookup is active
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-12: hint suppressed when autoKhLookupEnabled and CS available"() {
        given:
        File f = writeFile('auto12.groovy', 'class Auto12 {}')
        String normalized = normPath(f)
        helper.autoKhHintsSuppressed = true   // default -- hints suppressed

        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.lookupFileHash(normalized) >> null   // miss -- content returned
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-12')
        Map data = parseResp(resp)

        then: 'no hint in response -- auto-lookup handles next read'
        !data._knownhash_hint
        !data.unchanged
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-13: hint emitted when auto-lookup disabled
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-13: hint emitted when auto-lookup is disabled"() {
        given:
        File f = writeFile('auto13.groovy', 'class Auto13 {}')
        String normalized = normPath(f)
        helper.autoKhLookupEnabled = false
        helper.autoKhHintsSuppressed = true   // flag on, but lookup disabled -- hint should appear
        helper.contextServerClient = null     // no CS

        when:
        McpResponse resp = fileContentReader.doRead(normalized, [:], 'req-13')
        Map data = parseResp(resp)

        then: 'hint present -- auto is off so caller needs it'
        data._knownhash_hint != null
        !data.unchanged

        cleanup:
        helper.autoKhLookupEnabled = true
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-14: circuit breaker CLOSED -> OPEN on first ConnectException
    //   isCsReachable() returns true initially, false after onCsConnectFailure(),
    //   and lookupFileHash() / storeFileHashAsync() gate on isCsReachable().
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-14: circuit starts CLOSED, moves to OPEN on connect failure, suppresses calls"() {
        given: 'a fresh ContextServerClient (unit test - no Spring context needed)'
        def client = new ContextServerClient()
        client.contextServerUrl = 'http://localhost:8082'
        client.structureGroupId = 'test'
        client.structurePersistEnabled = true
        client.directoryCacheEnabled = true
        client.readTimeoutMs = 300

        when: 'initial state is CLOSED -- reachable'
        boolean initiallyReachable = client.isCsReachable()

        then:
        initiallyReachable == true

        when: 'first connect failure fires'
        client.onCsConnectFailure()

        then: 'circuit moves to OPEN -- no longer reachable'
        client.isCsReachable() == false
        client.csCircuitState.name() == 'OPEN'
        client.csFailureCount == 1
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-15: circuit OPEN -> HALF_OPEN after backoff window expires
    //   After retryAfterMs elapses, next isCsReachable() returns true and
    //   state moves to HALF_OPEN (allows one probe).
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-15: circuit moves to HALF_OPEN after backoff expires"() {
        given:
        def client = new ContextServerClient()
        client.contextServerUrl = 'http://localhost:8082'
        client.structureGroupId = 'test'
        client.structurePersistEnabled = true
        client.directoryCacheEnabled = true
        client.readTimeoutMs = 300

        when: 'trigger failure then manually expire the backoff'
        client.onCsConnectFailure()
        // Manually set retryAfterMs to past so backoff window appears expired
        client.csRetryAfterMs = System.currentTimeMillis() - 1

        then: 'isCsReachable() returns true and transitions to HALF_OPEN'
        client.isCsReachable() == true
        client.csCircuitState.name() == 'HALF_OPEN'
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-16: circuit HALF_OPEN -> CLOSED on success (recovery)
    //   onCsSuccess() called after a probe succeeds -- resets to CLOSED.
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-16: onCsSuccess() in HALF_OPEN state closes circuit"() {
        given:
        def client = new ContextServerClient()
        client.contextServerUrl = 'http://localhost:8082'
        client.structureGroupId = 'test'
        client.structurePersistEnabled = true
        client.directoryCacheEnabled = true
        client.readTimeoutMs = 300
        // Put into HALF_OPEN state
        client.onCsConnectFailure()
        client.csRetryAfterMs = System.currentTimeMillis() - 1
        client.isCsReachable()  // triggers OPEN -> HALF_OPEN transition

        when:
        client.onCsSuccess()

        then: 'fully closed -- reachable, failure count reset'
        client.csCircuitState.name() == 'CLOSED'
        client.csFailureCount == 0
        client.isCsReachable() == true
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-17: backoff escalates with failure count
    //   First failure: 5s backoff. Second: 15s. Third: 30s. Fourth+: 60s.
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-17: backoff escalates with repeated failures"() {
        given:
        def client = new ContextServerClient()
        client.contextServerUrl = 'http://localhost:8082'
        client.structureGroupId = 'test'
        client.structurePersistEnabled = true
        client.directoryCacheEnabled = true
        client.readTimeoutMs = 300

        when: 'accumulate two failures checking backoff at each step'
        long t0 = System.currentTimeMillis()

        // Failure 1: count 0 -> 1, backoff = 5s (index 0)
        client.onCsConnectFailure()
        long retry1 = client.csRetryAfterMs
        int count1  = client.csFailureCount

        // Simulate recovery + second failure: force HALF_OPEN then fail again
        client.csRetryAfterMs = System.currentTimeMillis() - 1
        client.isCsReachable()   // OPEN -> HALF_OPEN transition
        // Failure 2: count 1 -> 2, backoff = 15s (index 1)
        client.onCsConnectFailure()
        long retry2 = client.csRetryAfterMs
        int count2  = client.csFailureCount

        then: 'backoffs escalate per CS_BACKOFF_MS = [5000, 15000, 30000, 60000]'
        count1 == 1
        (retry1 - t0) >= 4500L && (retry1 - t0) <= 6000L   // ~5s
        count2 == 2
        (retry2 - t0) >= 13000L   // 15s backoff from ~t0 gives >= 13s margin
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-18: shadowAutoKhProbe -- flag controls probe execution
    //   When autoKhShadowEnabled=false, shadowAutoKhProbe is a no-op.
    //   When enabled and no cached hash, _shadow_kh is absent from response.
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-18: shadowAutoKhProbe is no-op when autoKhShadowEnabled=false"() {
        given:
        def helper = new ReadResponseHelper(null)
        helper.autoKhShadowEnabled = false
        helper.autoKhLookupEnabled = true
        helper.contextServerClient = null   // would NPE if shadow ran
        Map<String, Object> resp = [file_content_hash: 'abc123456789']

        when:
        helper.shadowAutoKhProbe(resp, '/some/path.groovy', 'range')

        then: 'no-op -- response unchanged, no exception'
        !resp.containsKey('_shadow_kh')
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-19: shadowAutoKhProbe annotates _shadow_kh:true on hash match
    //   (unit-level -- stubs ContextServerClient.lookupFileHash and StructureCache.getHash)
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-19: shadowAutoKhProbe sets _shadow_kh:true when cached hash matches current"() {
        given: 'helper with shadow enabled and a mock CS client that returns a matching hash'
        def helper = new ReadResponseHelper(null)
        helper.autoKhShadowEnabled = true
        helper.autoKhLookupEnabled = true

        String testHash = 'aabbccddeeff'
        // Stub contextServerClient
        def mockClient = [isCsReachable: { true },
                          lookupFileHash: { String p -> testHash }] as ContextServerClient
        helper.contextServerClient = mockClient

        // Response already carries matching hash (simulating post-range-read state)
        Map<String, Object> resp = [file_content_hash: testHash, action: 'range',
                                    content: 'some content', lines: 5]

        when: 'shadow probe runs'
        // structureCache.getHash would be used if file_content_hash absent -- here it is present
        helper.shadowAutoKhProbe(resp, '/test/file.groovy', 'range')

        then: 'response annotated with shadow hit'
        resp._shadow_kh == true
        resp._shadow_kh_action == 'range'
    }

    // -----------------------------------------------------------------------
    // CT-KH-AUTO-20: shadowAutoKhProbe sets _shadow_kh:false on hash mismatch
    // -----------------------------------------------------------------------

    def "CT-KH-AUTO-20: shadowAutoKhProbe sets _shadow_kh:false when hashes differ"() {
        given:
        def helper = new ReadResponseHelper(null)
        helper.autoKhShadowEnabled = true
        helper.autoKhLookupEnabled = true

        String cachedHash  = 'aabbccddeeff'  // old hash in CS
        String currentHash = '112233445566'  // current disk hash

        boolean storeAsyncCalled = false
        def mockClient = [
            isCsReachable   : { true },
            lookupFileHash  : { String p -> cachedHash },
            storeFileHashAsync: { String p, String h -> storeAsyncCalled = true }
        ] as ContextServerClient
        helper.contextServerClient = mockClient

        Map<String, Object> resp = [file_content_hash: currentHash, action: 'get_method',
                                    content: 'method body', lines: 10]

        when:
        helper.shadowAutoKhProbe(resp, '/test/Service.groovy', 'get_method')

        then: 'stale annotated and async store triggered to update cache'
        resp._shadow_kh == false
        resp._shadow_kh_action == 'get_method'
        storeAsyncCalled
    }

}
