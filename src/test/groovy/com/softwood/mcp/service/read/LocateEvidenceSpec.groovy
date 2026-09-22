package com.softwood.mcp.service.read

import com.softwood.mcp.service.ContextServerClient
import spock.lang.Specification

/**
 * FS 0.9.43 N11 (close-the-loop-at-the-gate) -- a search hit is locate evidence, and grep is the
 * navigation primitive rather than something to be gated.
 *
 * <p>Live on 2026-09-17: a session ran file_search, got file and line back for the symbol it
 * wanted, and was still refused the read of that exact file -- twice -- and the grep it would have
 * used instead was itself gated. Both refusals were then filed against the caller.</p>
 */
class LocateEvidenceSpec extends Specification {

    LocateEvidenceRegistry registry = new LocateEvidenceRegistry()
    ReadResponseHelper     helper
    ContextServerClient    csMock = Mock(ContextServerClient)

    static final String SID  = '2026-09-17-17-45'
    static final String PATH = 'C:/repo/src/main/groovy/Foo.groovy'

    def setup() {
        helper = new ReadResponseHelper()
        helper.contextServerClient    = csMock
        helper.locateEvidenceRegistry = registry
        helper.ontologyGateEnforced   = true
        csMock.isCsReachable() >> true
    }

    def "LE-1: with no search hit, an indexed file is still blocked"() {
        given:
        csMock.ontologyGateCheck(PATH, _) >> ([allow: false, locate_query: 'abc:Foo'] as Map<String, Object>)

        when:
        Map<String, Object> entry = helper.ontologyGateEntry(PATH, [:] as Map<String, Object>, 'read')

        then:
        entry?.error == 'BLOCKED_ONTOLOGY_GATE'
        1 * csMock.writeOntologyGateObservationAsync(_, 'read')
    }

    def "LE-2: a search hit on that path satisfies the gate, and writes no gate notice"() {
        given:
        csMock.ontologyGateCheck(PATH, _) >> ([allow: false, locate_query: 'abc:Foo'] as Map<String, Object>)
        registry.recordHit(null, PATH)

        when:
        Map<String, Object> entry = helper.ontologyGateEntry(PATH, [:] as Map<String, Object>, 'read')

        then:
        entry == null
        0 * csMock.writeOntologyGateObservationAsync(_, _)
    }

    def "LE-3: evidence is per path -- a hit on one file does not unlock another"() {
        given:
        csMock.ontologyGateCheck(_, _) >> ([allow: false, locate_query: 'abc:Bar'] as Map<String, Object>)
        registry.recordHit(null, PATH)

        expect:
        helper.ontologyGateEntry('C:/repo/src/main/groovy/Bar.groovy', [:] as Map<String, Object>, 'read')?.error ==
            'BLOCKED_ONTOLOGY_GATE'
    }

    def "LE-4: evidence is dropped when cleared, so it cannot outlive its work"() {
        given:
        registry.recordHit(SID, PATH)

        expect:
        registry.isSatisfied(SID, PATH)

        when:
        registry.clear()

        then:
        !registry.isSatisfied(SID, PATH)
    }

    def "LE-5: evidence is scoped to the session that earned it"() {
        given:
        registry.recordHit('session-a', PATH)

        expect:
        registry.isSatisfied('session-a', PATH)
        !registry.isSatisfied('session-b', PATH)
    }

    def "LE-6: grep, multi_grep and structure are exempt at dispatch, and read/range are still gated"() {
        given:
        String src = new File('src/main/groovy/com/softwood/mcp/service/FileReadService.groovy').text
        int at = src.indexOf("!(action in ['exists'")

        expect: 'the anchor was found -- a source scan that cannot find its anchor proves nothing'
        at > 0

        and: 'FS 0.9.52: the navigation actions are named in the exempt set, the content actions are not'
        String block = src.substring(at, Math.min(src.length(), at + 400))
        block.contains("'grep'")
        block.contains("'multi_grep'")
        block.contains("'structure'")
        !block.contains("'read'")
        !block.contains("'range'")
    }

    def "LE-7: multi_grep no longer routes through gateMultiPaths -- the same lines grep returns, across files"() {
        given:
        String src = new File('src/main/groovy/com/softwood/mcp/service/FileReadService.groovy').text
        int caseAt = src.indexOf("case 'multi_grep'")
        int nextCase = src.indexOf("case 'multi'", caseAt + 1)

        expect: 'anchors found'
        caseAt > 0
        nextCase > caseAt

        and: 'the multi_grep case calls the served grep and nothing gates it in between'
        String body = src.substring(caseAt, nextCase)
        body.contains('servedMultiGrep(options, requestId)')
        !body.contains('gateMultiPaths(')
    }
}
