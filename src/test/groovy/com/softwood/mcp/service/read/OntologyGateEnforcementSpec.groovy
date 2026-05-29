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
 * OntologyGateEnforcementSpec -- E-5 acceptance gate (FS 0.9.8).
 *
 * <p>Verifies that the ONTOLOGY-GATE is promoted from warn-and-observe to
 * {@code block_and_observe} with {@code allowNoLocate} override, mirroring the
 * SQL-GATE pattern in CS {@code ContextLifecycleActionRouter.handleExecuteSql}.</p>
 *
 * <h3>Gate rule</h3>
 * <ul>
 *   <li>A {@code file_read} action {@code read}, {@code range}, or {@code get_method} on an
 *       ontology-indexed {@code .groovy} or {@code .java} file is BLOCKED when no
 *       {@code context_read scope=ontology action=locate} call has been recorded for that
 *       file stem this session.</li>
 *   <li>The block is bypassed when {@code options.allowNoLocate=true} is passed, but the
 *       block-count telemetry is still incremented on CS so the gate self-reports.</li>
 *   <li>Fail-open: CS down → gate not applied, read proceeds normally.</li>
 *   <li>Non-indexed files (.md, unknown stems) → gate not applied.</li>
 * </ul>
 *
 * <h3>Contracts covered</h3>
 * <ul>
 *   <li>{@code E5-ontology-enforced} (code, sql_min, warn) -- block-count > 0 after a negative test.</li>
 * </ul>
 *
 * <h3>Contract IDs (OGE-*)</h3>
 * <ul>
 *   <li>OGE-1: read on indexed file, no prior locate → BLOCKED_ONTOLOGY_GATE error</li>
 *   <li>OGE-2: range on indexed file, no prior locate → BLOCKED_ONTOLOGY_GATE error</li>
 *   <li>OGE-3: get_method on indexed file, no prior locate → BLOCKED_ONTOLOGY_GATE error</li>
 *   <li>OGE-4: read with prior locate recorded → allowed, content returned</li>
 *   <li>OGE-5: range with prior locate recorded → allowed, content returned</li>
 *   <li>OGE-6: read with allowNoLocate=true override → allowed; incrementHardGateBlockedToken called</li>
 *   <li>OGE-7: observation written to CS on block (signal_type=correction)</li>
 *   <li>OGE-8: CS down (isCsReachable=false) → fail-open, read proceeds</li>
 *   <li>OGE-9: non-indexed file → gate not applied, read proceeds</li>
 *   <li>OGE-10: gate disabled (ontologyGateEnabled=false) → read proceeds even for indexed file</li>
 *   <li>OGE-11: blocked response shape has error=BLOCKED_ONTOLOGY_GATE, hint, locate_query fields</li>
 * </ul>
 *
 * <h3>Wiring</h3>
 * <p>{@code @SpringBootTest} wires real beans. Each test injects a fresh
 * {@code Stub(ContextServerClient)} directly into
 * {@code ReadResponseHelper.contextServerClient}. The same singleton is shared by
 * {@code FileContentReader}, so stub injection is sufficient.</p>
 *
 * <p>Locate tracking uses a per-session {@code Set<String>} on {@code ContextServerClient}
 * (cleared by {@code clearSessionLocates()}). Tests drive it via
 * {@code stubClient.locateCalledThisSession(stem)} return values.</p>
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class OntologyGateEnforcementSpec extends Specification {

    @Autowired ReadResponseHelper       helper
    @Autowired FileContentReader        fileContentReader
    @Autowired com.softwood.mcp.service.PathService pathService

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

    /** Fresh stub: indexed, CS reachable, NO prior locate this session.
     *  The stub returns a getOntologyRange result whose source_file matches the
     *  provided absolutePath so the path-scope check in checkOntologyGate passes.
     */
    private ContextServerClient stubIndexed(String stem, boolean locateCalled = false,
                                             String absolutePath = null) {
        def s = Stub(ContextServerClient)
        s.isCsReachable()               >> true
        s.isOntologyIndexed(stem)       >> true
        s.locateCalledThisSession(stem) >> locateCalled
        // getOntologyRange must return source_file matching the actual file path.
        // If absolutePath is null the stub returns a path that won't match (safe default
        // for tests that never reach the path-scope check).
        String srcFile = absolutePath ?: "/some/project/src/main/groovy/${stem}.groovy"
        s.getOntologyRange(stem) >> [found: true, source_file: srcFile,
                                     source_line: 1, end_line: 10]
        s
    }

    /** Fresh stub: not indexed, CS reachable. */
    private ContextServerClient stubNotIndexed(String stem) {
        def s = Stub(ContextServerClient)
        s.isCsReachable()               >> true
        s.isOntologyIndexed(stem)       >> false
        s
    }

    def cleanup() {
        helper.contextServerClient = null
        helper.ontologyGateEnforced = true
        helper.ontologyGuardEnabled = true
    }

    // -----------------------------------------------------------------------
    // OGE-1: doRead on indexed file, no prior locate → BLOCKED
    // -----------------------------------------------------------------------
    def 'OGE-1: read on indexed .groovy file with no prior locate is blocked'() {
        given:
        File f    = writeGroovy('MyService.groovy')
        String np = norm(f)
        helper.contextServerClient = stubIndexed('MyService', false, np)

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-oge-1')
        Map data = parse(resp)

        then: 'response carries BLOCKED_ONTOLOGY_GATE error'
        data.error == 'BLOCKED_ONTOLOGY_GATE'

        and: 'hint explains how to proceed'
        (data.hint as String)?.contains('locate')

        and: 'locate_query is the file stem'
        data.locate_query == 'MyService'
    }

    // -----------------------------------------------------------------------
    // OGE-2: doRange on indexed file, no prior locate → BLOCKED
    // -----------------------------------------------------------------------
    def 'OGE-2: range on indexed .groovy file with no prior locate is blocked'() {
        given:
        File f    = writeGroovy('DomainService.groovy', 'class DomainService { void go() {} }')
        String np = norm(f)
        helper.contextServerClient = stubIndexed('DomainService', false, np)

        when:
        McpResponse resp = fileContentReader.doRange(np, [startLine: 1, maxLines: 10] as Map, 'req-oge-2')
        Map data = parse(resp)

        then:
        data.error == 'BLOCKED_ONTOLOGY_GATE'
        data.locate_query == 'DomainService'
    }

    // -----------------------------------------------------------------------
    // OGE-3: doGetMethod on indexed file, no prior locate → BLOCKED
    // -----------------------------------------------------------------------
    def 'OGE-3: get_method on indexed .groovy file with no prior locate is blocked'() {
        given:
        File f    = writeGroovy('WorkerBean.groovy', 'class WorkerBean { void execute() { println "hi" } }')
        String np = norm(f)
        helper.contextServerClient = stubIndexed('WorkerBean', false, np)

        when:
        McpResponse resp = fileContentReader.doGetMethod(np, [method: 'execute'] as Map, 'req-oge-3')
        Map data = parse(resp)

        then:
        data.error == 'BLOCKED_ONTOLOGY_GATE'
        data.locate_query == 'WorkerBean'
    }

    // -----------------------------------------------------------------------
    // OGE-4: read with prior locate recorded → allowed
    // -----------------------------------------------------------------------
    def 'OGE-4: read on indexed file is allowed when locate was called this session'() {
        given:
        File f    = writeGroovy('LocatedService.groovy', 'class LocatedService { String name = "ok" }')
        String np = norm(f)
        helper.contextServerClient = stubIndexed('LocatedService', true, np) // locate WAS called

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-oge-4')
        Map data = parse(resp)

        then: 'no block error'
        data.error == null

        and: 'actual content present'
        (data.content as String)?.contains('LocatedService')
    }

    // -----------------------------------------------------------------------
    // OGE-5: range with prior locate recorded → allowed
    // -----------------------------------------------------------------------
    def 'OGE-5: range on indexed file is allowed when locate was called this session'() {
        given:
        File f    = writeGroovy('LocatedRouter.groovy', 'class LocatedRouter { void route() {} }')
        String np = norm(f)
        helper.contextServerClient = stubIndexed('LocatedRouter', true, np)

        when:
        McpResponse resp = fileContentReader.doRange(np, [startLine: 1, maxLines: 5] as Map, 'req-oge-5')
        Map data = parse(resp)

        then:
        data.error == null
    }

    // -----------------------------------------------------------------------
    // OGE-6: allowNoLocate=true overrides block; incrementHardGateBlockedToken called
    // -----------------------------------------------------------------------
    def 'OGE-6: read with allowNoLocate=true is allowed but CS blocked-token counter is incremented'() {
        given:
        File f    = writeGroovy('OverrideService.groovy')
        String np = norm(f)
        def csMock = Mock(ContextServerClient)
        csMock.isCsReachable()                       >> true
        csMock.isOntologyIndexed('OverrideService')  >> true
        csMock.locateCalledThisSession('OverrideService') >> false
        csMock.getOntologyRange('OverrideService') >> [found: true, source_file: np,
                                                        source_line: 1, end_line: 10]
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(np, [allowNoLocate: true] as Map, 'req-oge-6')
        Map data = parse(resp)

        then: 'read proceeds (not blocked)'
        data.error == null

        and: 'block-count telemetry incremented on CS exactly once'
        1 * csMock.incrementOntologyGateBlockedToken('OverrideService')
    }

    // -----------------------------------------------------------------------
    // OGE-7: observation written to CS on block
    // -----------------------------------------------------------------------
    def 'OGE-7: block writes a correction observation to CS session_observations'() {
        given:
        File f    = writeGroovy('ObservableService.groovy')
        String np = norm(f)
        def csMock = Mock(ContextServerClient)
        csMock.isCsReachable()                            >> true
        csMock.isOntologyIndexed('ObservableService')     >> true
        csMock.locateCalledThisSession('ObservableService') >> false
        csMock.getOntologyRange('ObservableService') >> [found: true, source_file: np,
                                                          source_line: 1, end_line: 10]
        helper.contextServerClient = csMock

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-oge-7')

        then: 'block triggered'
        parse(resp).error == 'BLOCKED_ONTOLOGY_GATE'

        and: 'one correction observation written asynchronously'
        1 * csMock.writeOntologyGateObservationAsync('ObservableService', _ as String)
    }

    // -----------------------------------------------------------------------
    // OGE-8: CS down → fail-open, read proceeds
    // -----------------------------------------------------------------------
    def 'OGE-8: when CS is unreachable the gate is not applied and read proceeds'() {
        given:
        File f    = writeGroovy('OfflineService.groovy', 'class OfflineService { int x = 1 }')
        String np = norm(f)
        def s = Stub(ContextServerClient)
        s.isCsReachable() >> false
        helper.contextServerClient = s

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-oge-8')
        Map data = parse(resp)

        then:
        data.error == null
        (data.content as String)?.contains('OfflineService')
    }

    // -----------------------------------------------------------------------
    // OGE-9: non-indexed file → gate not applied
    // -----------------------------------------------------------------------
    def 'OGE-9: non-indexed .groovy file is not blocked even without a prior locate'() {
        given:
        File f    = writeGroovy('PlainScript.groovy', 'class PlainScript {}')
        String np = norm(f)
        helper.contextServerClient = stubNotIndexed('PlainScript')

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-oge-9')
        Map data = parse(resp)

        then:
        data.error == null
    }

    // -----------------------------------------------------------------------
    // OGE-10: gate feature-flag disabled → reads proceed unconditionally
    // -----------------------------------------------------------------------
    def 'OGE-10: gate disabled via feature flag allows reads without locate even on indexed files'() {
        given:
        File f    = writeGroovy('FlaggedService.groovy')
        String np = norm(f)
        helper.contextServerClient = stubIndexed('FlaggedService', false, np)
        helper.ontologyGateEnforced = false

        when:
        McpResponse resp = fileContentReader.doRead(np, [:], 'req-oge-10')

        then:
        parse(resp).error == null
    }

    // -----------------------------------------------------------------------
    // OGE-11: blocked response shape contract
    // -----------------------------------------------------------------------
    def 'OGE-11: blocked response has required fields: error, hint, locate_query, action'() {
        given:
        File f    = writeGroovy('ShapeCheck.groovy')
        String np = norm(f)
        helper.contextServerClient = stubIndexed('ShapeCheck', false, np)

        when:
        Map data = parse(fileContentReader.doRead(np, [:], 'req-oge-11'))

        then:
        data.error       == 'BLOCKED_ONTOLOGY_GATE'
        data.locate_query == 'ShapeCheck'
        (data.hint as String)?.length() > 0
        (data.action as String)?.length() > 0
    }
}
