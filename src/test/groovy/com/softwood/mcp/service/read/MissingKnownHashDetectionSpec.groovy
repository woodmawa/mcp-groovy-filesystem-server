package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.FilesystemTelemetryService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * MissingKnownHashDetectionSpec -- FS 0.9.9 knownHash discipline enforcement.
 *
 * <p>Verifies that {@link ReadResponseHelper#maybeWarnMissingKnownHash} detects and
 * reports when a caller issues {@code file_read action=read} or {@code action=get_method}
 * on a file the {@link com.softwood.mcp.service.StructureCache} already has a hash for,
 * without passing {@code options.knownHash}.</p>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Advisory only — does NOT block the read (gate is ONTOLOGY-GATE).</li>
 *   <li>Injects {@code _missing_knownhash} hint into response so distillation sees it.</li>
 *   <li>Fires a {@code correction} observation async via {@link ContextServerClient}.</li>
 *   <li>Increments {@link FilesystemTelemetryService#incrementMissingKhCount()} per session
 *       so {@code handleRecordSessionTelemetry} can report the count.</li>
 *   <li>Skipped when: knownHash WAS passed; file not in StructureCache; CS unreachable;
 *       feature flag disabled.</li>
 * </ul>
 *
 * <h3>Contract IDs (MKH-*)</h3>
 * <ul>
 *   <li>MKH-1: read without knownHash, file in StructureCache → _missing_knownhash injected</li>
 *   <li>MKH-2: read WITH knownHash supplied → no hint injected</li>
 *   <li>MKH-3: read, file NOT in StructureCache → no hint injected</li>
 *   <li>MKH-4: _missing_knownhash hint contains the correct expected hash</li>
 *   <li>MKH-5: correction observation written async to CS on violation</li>
 *   <li>MKH-6: FilesystemTelemetryService.incrementMissingKhCount called on violation</li>
 *   <li>MKH-7: feature flag disabled → no hint even when file is in cache</li>
 *   <li>MKH-8: doRead returns content normally (hint is additive, not blocking)</li>
 *   <li>MKH-9: doGetMethod without knownHash, file in cache → hint injected</li>
 * </ul>
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class MissingKnownHashDetectionSpec extends Specification {

    @Autowired ReadResponseHelper          helper
    @Autowired FileContentReader           fileContentReader
    @Autowired com.softwood.mcp.service.PathService pathService
    @Autowired com.softwood.mcp.service.StructureCache structureCache

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private File writeGroovy(String name, String body = "class ${name.replace('.groovy','')} {}") {
        File f = tempDir.resolve(name).toFile()
        f.text = body
        f
    }

    private String norm(File f) { pathService.normalizePath(f.absolutePath) }

    private Map parse(McpResponse resp) {
        String text = resp.result?.content?.find { it.type == 'text' }?.text
        text ? (Map) new JsonSlurper().parseText(text) : [:]
    }

    /** Prime StructureCache by calling getHash on the real file (computes + stores lazily). Returns the computed hash. */
    private String seedCache(String normalized) {
        return structureCache.getHash(normalized) ?: 'unknown'
    }

    private ContextServerClient makeReachableStub() {
        def s = Stub(ContextServerClient)
        s.isCsReachable()           >> true
        s.getOntologyRange(_)       >> null   // no ontology gate (not indexed)
        s
    }

    def cleanup() {
        helper.contextServerClient = null
        helper.missingKhWarnEnabled = true
    }

    // -----------------------------------------------------------------------
    // MKH-1: read without knownHash, file in StructureCache → hint injected
    // -----------------------------------------------------------------------
    def 'MKH-1: read without knownHash on cached file injects _missing_knownhash hint'() {
        given:
        File f    = writeGroovy('CachedService.groovy', 'class CachedService { int x = 1 }')
        String np = norm(f)
        seedCache(np)
        helper.contextServerClient = makeReachableStub()

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-mkh-1')
        Map data = parse(resp)

        then: 'read succeeds with content'
        data.error == null
        (data.content as String)?.contains('CachedService')

        and: '_missing_knownhash hint is present in response'
        data.containsKey('_missing_knownhash')
        (data._missing_knownhash as String)?.contains('knownHash')
    }

    // -----------------------------------------------------------------------
    // MKH-2: read WITH knownHash supplied → no hint
    // -----------------------------------------------------------------------
    def 'MKH-2: read with knownHash supplied does not inject hint'() {
        given:
        File f    = writeGroovy('PassedHashService.groovy', 'class PassedHashService {}')
        String np = norm(f)
        seedCache(np)
        helper.contextServerClient = makeReachableStub()

        when:
        // First read to get actual hash
        McpResponse r1   = fileContentReader.doRead(np, [:], 'prime')
        String actualHash = parse(r1).file_content_hash as String

        McpResponse resp = fileContentReader.doRead(np, [knownHash: actualHash] as Map, 'req-mkh-2')
        Map data = parse(resp)

        then: 'unchanged or content, no missing hint'
        !data.containsKey('_missing_knownhash')
    }

    // -----------------------------------------------------------------------
    // MKH-3: file NOT in StructureCache → no hint
    // -----------------------------------------------------------------------
    def 'MKH-3: read on file not in StructureCache does not inject hint'() {
        given:
        File f    = writeGroovy('UncachedService.groovy', 'class UncachedService {}')
        String np = norm(f)
        // Do NOT seed cache
        helper.contextServerClient = makeReachableStub()

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-mkh-3')
        Map data = parse(resp)

        then:
        data.error == null
        !data.containsKey('_missing_knownhash')
    }

    // -----------------------------------------------------------------------
    // MKH-4: hint contains the correct expected hash value
    // -----------------------------------------------------------------------
    def 'MKH-4: _missing_knownhash hint embeds the cached hash so caller can use it'() {
        given:
        File f    = writeGroovy('HashHintService.groovy', 'class HashHintService {}')
        String np    = norm(f)
        String prior = seedCache(np)
        helper.contextServerClient = makeReachableStub()

        when:
        Map data = parse(fileContentReader.doRead(np, [:], 'req-mkh-4'))

        then:
        (data._missing_knownhash as String)?.contains(prior)
    }

    // -----------------------------------------------------------------------
    // MKH-5: correction observation written async to CS on violation
    // -----------------------------------------------------------------------
    def 'MKH-5: missing knownHash violation writes correction observation async to CS'() {
        given:
        File f    = writeGroovy('ObsService.groovy', 'class ObsService {}')
        String np = norm(f)
        seedCache(np)
        def csMock = Mock(ContextServerClient)
        csMock.isCsReachable()     >> true
        csMock.getOntologyRange(_) >> null
        helper.contextServerClient = csMock

        when:
        fileContentReader.doRead(np, [:], 'req-mkh-5')

        then:
        1 * csMock.writeMissingKnownHashObservationAsync(_ as String, _ as String)
    }

    // -----------------------------------------------------------------------
    // MKH-6: FilesystemTelemetryService.incrementMissingKhCount called
    // -----------------------------------------------------------------------
    def 'MKH-6: missing knownHash increments FilesystemTelemetryService missing-kh counter'() {
        given:
        File f    = writeGroovy('CounterService.groovy', 'class CounterService {}')
        String np = norm(f)
        seedCache(np)
        helper.contextServerClient = makeReachableStub()

        when:
        int before = helper.telemetryService?.getMissingKhCount() ?: 0
        fileContentReader.doRead(np, [:], 'req-mkh-6')
        int after  = helper.telemetryService?.getMissingKhCount() ?: 0

        then: 'counter incremented by exactly one'
        after == before + 1
    }

    // -----------------------------------------------------------------------
    // MKH-7: feature flag disabled → no hint
    // -----------------------------------------------------------------------
    def 'MKH-7: missingKhWarnEnabled=false suppresses hint even on cached file'() {
        given:
        File f    = writeGroovy('FlaggedService.groovy', 'class FlaggedService {}')
        String np = norm(f)
        seedCache(np)
        helper.contextServerClient = makeReachableStub()
        helper.missingKhWarnEnabled = false

        when:
        Map data = parse(fileContentReader.doRead(np, [:], 'req-mkh-7'))

        then:
        !data.containsKey('_missing_knownhash')
    }

    // -----------------------------------------------------------------------
    // MKH-8: doRead returns full content (hint is purely additive)
    // -----------------------------------------------------------------------
    def 'MKH-8: missing knownHash hint is additive -- read still returns content normally'() {
        given:
        File f    = writeGroovy('NormalService.groovy', 'class NormalService { String name = "ok" }')
        String np = norm(f)
        seedCache(np)
        helper.contextServerClient = makeReachableStub()

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-mkh-8')
        Map data = parse(resp)

        then:
        data.error == null
        (data.content as String)?.contains('NormalService')
        data.containsKey('file_content_hash')
        data.containsKey('_missing_knownhash')   // both present
    }

    // -----------------------------------------------------------------------
    // MKH-9: doGetMethod without knownHash, file in cache → hint injected
    // -----------------------------------------------------------------------
    def 'MKH-9: get_method without knownHash on cached file injects hint'() {
        given:
        File f    = writeGroovy('MethodService.groovy',
                                'class MethodService { void execute() { println "ok" } }')
        String np = norm(f)
        seedCache(np)
        helper.contextServerClient = makeReachableStub()

        when:
        McpResponse resp = fileContentReader.doGetMethod(np, [method: 'execute'] as Map, 'req-mkh-9')
        Map data = parse(resp)

        then:
        data.error == null
        data.containsKey('_missing_knownhash')
    }
}
