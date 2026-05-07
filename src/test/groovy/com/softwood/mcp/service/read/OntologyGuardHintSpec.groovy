package com.softwood.mcp.service.read

import com.softwood.mcp.service.ContextServerClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Contract spec for FS 0.9.2 ontology guard hint (range suggestion).
 * Verifies that maybeAddOntologyGuardHint emits _ontology_guard_hint with concrete
 * startLine/maxLines bounds when getOntologyRange returns valid class bounds.
 *
 * OGH-CT-1: indexed file with range → _ontology_guard_warn + _ontology_guard_hint with correct values
 * OGH-CT-2: indexed file, null range fields → warn set, hint absent
 * OGH-CT-3: getOntologyRange returns null (CS down) → neither field set
 * OGH-CT-4: getOntologyRange returns found=false → neither field set
 * OGH-CT-5: guard disabled → neither field regardless of CS response
 * OGH-CT-6: transport independence -- guard logic lives in ReadResponseHelper (service layer),
 *           not in any controller or transport class; getOntologyRange is on ContextServerClient
 *           (service). Identical behaviour whether FS is called via stdio or HTTP transport.
 *
 * Pattern: @SpringBootTest wires real beans. Each test injects a Stub(ContextServerClient)
 * directly into ReadResponseHelper.contextServerClient (same field as FileHashAutoLookupSpec).
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class OntologyGuardHintSpec extends Specification {

    @Autowired ReadResponseHelper helper

    def setup() {
        helper.ontologyGuardEnabled = true
    }

    def cleanup() {
        helper.contextServerClient = null
        helper.ontologyGuardEnabled = true
    }

    // -----------------------------------------------------------------------
    // OGH-CT-1: full range → both _ontology_guard_warn and _ontology_guard_hint
    // -----------------------------------------------------------------------
    def 'OGH-CT-1: indexed file with range bounds emits both warn and hint with correct values'() {
        given: 'CS stub returns found=true with source_line=56 end_line=175'
        def stubClient = Stub(ContextServerClient)
        stubClient.isCsReachable() >> true
        stubClient.getOntologyRange('MyClass') >> [found: true, source_line: 56, end_line: 175]
        helper.contextServerClient = stubClient

        Map<String, Object> response = [:]

        when:
        helper.maybeAddOntologyGuardHint(response, '/some/path/MyClass.groovy')

        then: 'warn contains the file stem'
        (response._ontology_guard_warn as String)?.contains('MyClass')

        and: 'hint has correct startLine and maxLines (175-56+1=120)'
        response._ontology_guard_hint == 'Call range startLine=56 maxLines=120 instead (source: ontology index)'
    }

    // -----------------------------------------------------------------------
    // OGH-CT-2: found=true but source_line/end_line null → warn only, no hint
    // -----------------------------------------------------------------------
    def 'OGH-CT-2: indexed file with null range fields emits warn but no hint'() {
        given:
        def stubClient = Stub(ContextServerClient)
        stubClient.isCsReachable() >> true
        stubClient.getOntologyRange('MyClass') >> [found: true, source_line: null, end_line: null]
        helper.contextServerClient = stubClient

        Map<String, Object> response = [:]

        when:
        helper.maybeAddOntologyGuardHint(response, '/some/path/MyClass.groovy')

        then:
        response.containsKey('_ontology_guard_warn')
        !response.containsKey('_ontology_guard_hint')
    }

    // -----------------------------------------------------------------------
    // OGH-CT-3: getOntologyRange returns null (CS error / timeout) → neither field
    // -----------------------------------------------------------------------
    def 'OGH-CT-3: getOntologyRange returns null (CS down) emits neither guard field'() {
        given:
        def stubClient = Stub(ContextServerClient)
        stubClient.isCsReachable() >> true
        stubClient.getOntologyRange('MyClass') >> null
        helper.contextServerClient = stubClient

        Map<String, Object> response = [:]

        when:
        helper.maybeAddOntologyGuardHint(response, '/some/path/MyClass.groovy')

        then:
        !response.containsKey('_ontology_guard_warn')
        !response.containsKey('_ontology_guard_hint')
    }

    // -----------------------------------------------------------------------
    // OGH-CT-4: found=false → neither field
    // -----------------------------------------------------------------------
    def 'OGH-CT-4: getOntologyRange found=false emits neither guard field'() {
        given:
        def stubClient = Stub(ContextServerClient)
        stubClient.isCsReachable() >> true
        stubClient.getOntologyRange('MyClass') >> [found: false]
        helper.contextServerClient = stubClient

        Map<String, Object> response = [:]

        when:
        helper.maybeAddOntologyGuardHint(response, '/some/path/MyClass.groovy')

        then:
        !response.containsKey('_ontology_guard_warn')
        !response.containsKey('_ontology_guard_hint')
    }

    // -----------------------------------------------------------------------
    // OGH-CT-5: guard disabled → neither field even when CS would return range
    // -----------------------------------------------------------------------
    def 'OGH-CT-5: guard disabled emits neither field regardless of CS response'() {
        given:
        def stubClient = Stub(ContextServerClient)
        stubClient.isCsReachable() >> true
        stubClient.getOntologyRange('MyClass') >> [found: true, source_line: 10, end_line: 100]
        helper.contextServerClient = stubClient
        helper.ontologyGuardEnabled = false

        Map<String, Object> response = [:]

        when:
        helper.maybeAddOntologyGuardHint(response, '/some/path/MyClass.groovy')

        then:
        !response.containsKey('_ontology_guard_warn')
        !response.containsKey('_ontology_guard_hint')
    }

    // -----------------------------------------------------------------------
    // OGH-CT-6: transport independence -- guard lives in service layer only
    // -----------------------------------------------------------------------
    def 'OGH-CT-6: maybeAddOntologyGuardHint and getOntologyRange are on service-layer classes'() {
        expect: 'guard method is on ReadResponseHelper (Spring @Service / @Component), not a controller'
        ReadResponseHelper.getDeclaredMethods().any { it.name == 'maybeAddOntologyGuardHint' }

        and: 'getOntologyRange is on ContextServerClient (@Service), not a controller'
        ContextServerClient.getDeclaredMethods().any { it.name == 'getOntologyRange' }

        and: 'neither class is annotated @RestController or @Controller (transport-free)'
        !ReadResponseHelper.isAnnotationPresent(org.springframework.web.bind.annotation.RestController)
        !ContextServerClient.isAnnotationPresent(org.springframework.web.bind.annotation.RestController)
    }
}
