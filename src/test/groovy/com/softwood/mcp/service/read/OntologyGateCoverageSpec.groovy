package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.FileReadService
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FS 0.9.26 -- ONTOLOGY-GATE: the two defects that cancelled each other, and the coverage hole.
 *
 * <p>The gate is a declared HARD gate, enforced by default since FS 0.9.8. It had blocked
 * <b>once, ever</b> -- one observation on 2026-05-29 -- while 111 sessions performed 2,269 reads in
 * thirty days, 21 of them with zero locate calls, none refused.</p>
 *
 * <h3>Why it was inert</h3>
 * <ol>
 *   <li>It resolved the file by its bare STEM through a fuzzy locate, then returned null whenever
 *       the resolved path differed from the file being read. In a codebase where nearly every
 *       Foo.groovy has a FooSpec.groovy the stem resolves to the SPEC, so the guard -- added
 *       correctly, to stop a TempDir stem collision -- switched the gate off for most of the tree.</li>
 *   <li>The "was locate called" check read an in-process Set whose only writer had NO CALLERS
 *       anywhere in the repository, so it could only answer false.</li>
 * </ol>
 * <p>Repair one alone and every read blocks; repair the other alone and nothing changes. The gate
 * read as working <em>because</em> both were broken. That is what OGC-1 and OGC-2 hold shut.</p>
 *
 * <h3>And it covered the wrong third of the surface</h3>
 * <p>Only doRead, doRange and doGetMethod consulted it. grep, head, tail, structure and summary --
 * the CHEAP calls, which is to say the ones actually used -- walked straight past. OGC-3 and OGC-4
 * pin the gate at dispatch, with the exemptions named explicitly so a new action is gated by
 * default rather than quietly missed.</p>
 *
 * <h3>Every assertion runs over CODE LINES ONLY</h3>
 * <p>This release's own comments discuss {@code getOntologyRange}, {@code locateCalledThisSession}
 * and {@code recordLocateCalled} at length, and a raw-source absence check would be satisfied by
 * that prose -- which is how the same mistake red-built CS 1.0.55 earlier today. Only whole-line
 * comments are stripped, so no string literal is cut and no call site can hide.</p>
 *
 * @since FS 0.9.26
 */
@CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class OntologyGateCoverageSpec extends Specification {

    @Autowired ReadResponseHelper helper
    @Autowired FileReadService    fileReadService
    @Autowired com.softwood.mcp.service.PathService pathService

    @TempDir Path tempDir

    def cleanup() {
        helper.contextServerClient  = null
        helper.ontologyGateEnforced = true
    }

    private File writeGroovy(String name, String body) {
        File f = tempDir.resolve(name).toFile()
        f.text = body
        return f
    }

    private Map parse(McpResponse resp) {
        String text = resp.result?.content?.find { it.type == 'text' }?.text
        return text ? (Map) new JsonSlurper().parseText(text) : [:]
    }

    private static final String NL = Character.toString((char) 10)

    private static final File HELPER_SRC = new File(
        'src/main/groovy/com/softwood/mcp/service/read/ReadResponseHelper.groovy')
    private static final File CLIENT_SRC = new File(
        'src/main/groovy/com/softwood/mcp/service/ContextServerClient.groovy')
    private static final File DISPATCH_SRC = new File(
        'src/main/groovy/com/softwood/mcp/service/FileReadService.groovy')

    /** Source with whole-line comments removed; a comment must never satisfy an absence check. */
    private static String codeOnly(File src) {
        return src.text.readLines()
                  .findAll { String line ->
                      String t = line.trim()
                      !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*'))
                  }
                  .join(NL)
    }

    private static String methodBody(File src, String signatureFragment) {
        String text = codeOnly(src)
        int at = text.indexOf(signatureFragment)
        assert at >= 0, "signature not found in ${src.name}: ${signatureFragment}"
        return text.substring(at)
    }

    def "OGC-1: the gate asks CS about the PATH, and no longer resolves the file by its stem"() {
        given:
        String body = methodBody(HELPER_SRC, 'McpResponse checkOntologyGate(')

        expect: 'one question, asked of the server that owns both facts'
        body.contains('ontologyGateCheck(')

        and: 'the stem lookup is gone -- it resolved to the sibling Spec and disabled the gate'
        assert !body.contains('getOntologyRange('),
            'resolving by bare stem returns FooSpec for Foo, the path-scope guard then finds a ' +
            'mismatch, and the gate allows the read. That is why it fired once in three months.'

        and: 'and so is the in-process locate flag, which had no writer'
        assert !body.contains('locateCalledThisSession('),
            'locateCalledThisSession read a Set whose only writer had no callers, so it could ' +
            'only ever return false. Both halves had to go together.'
        true
    }

    def "OGC-2: the writerless located-stems set is gone from the client"() {
        given:
        String client = codeOnly(CLIENT_SRC)

        expect: 'the client offers the single gate question instead'
        client.contains('Map<String, Object> ontologyGateCheck(')

        and: 'and no longer keeps a set nothing writes'
        assert !client.contains('sessionLocatedStems'),
            'an in-memory Set in the FS process cannot know about a locate served by CS on the ' +
            "chat's own connection. The fact belongs where it is produced."

        and: 'nor the setter that nothing called'
        assert !client.contains('void recordLocateCalled('),
            'recordLocateCalled had no callers in main or test -- a writer that never ran, ' +
            'guarding a check that therefore never passed'
        true
    }

    def "OGC-3: the gate runs at dispatch, ahead of the action switch"() {
        given:
        String dispatch = codeOnly(DISPATCH_SRC)
        int gateAt   = dispatch.indexOf('responseHelper.checkOntologyGate(')
        int switchAt = dispatch.indexOf('switch (action) {')

        expect: 'both anchors are present -- a missing one must fail, not silently compare -1'
        gateAt >= 0
        switchAt >= 0

        and: 'and the gate is applied BEFORE the switch, so no action can be added past it'
        assert gateAt < switchAt,
            'gating inside doRead/doRange/doGetMethod covered three actions out of eight and ' +
            'missed grep, head, tail, structure and summary -- the cheap ones, which are the ' +
            'ones actually used. Same correction CS 1.0.52 made by moving its dirty-flag into ' +
            'dispatch ahead of the fast path.'
        true
    }

    def "OGC-4: content-returning actions are not exempt"() {
        given: 'the exempt list, read from the dispatch guard itself'
        String dispatch = codeOnly(DISPATCH_SRC)
        int from = dispatch.indexOf('responseHelper.checkOntologyGate(')
        int guardAt = dispatch.lastIndexOf('!(action in [', from)
        assert guardAt >= 0, 'no explicit exempt list found next to the dispatch gate'
        String guard = dispatch.substring(guardAt, from)

        expect: 'every action that hands back file content stays gated'
        ['grep', 'head', 'tail', 'structure', 'summary', 'read', 'range', 'get_method'].each {
            String contentAction ->
                assert !guard.contains("'${contentAction}'"),
                    "'${contentAction}' returns file content and must not be exempt from the gate"
        }

        and: 'and the exemptions are named rather than left to fall through'
        guard.contains("'exists'")
        guard.contains("'list'")
    }

    def "OGC-5: one gated file_read dispatch asks the gate exactly ONCE [#action]"() {
        given: 'a counting mock, and a gate that ALLOWS, so the read runs its whole path'
        File f = writeGroovy('CountedService.groovy',
                             'class CountedService { void execute() { println "x" } }')
        String np = pathService.normalizePath(f.absolutePath)
        ContextServerClient csMock = Mock(ContextServerClient)
        csMock.isCsReachable() >> true
        helper.contextServerClient = csMock

        when:
        fileReadService.handleToolCall('file_read',
            [action: action, path: np, options: opts] as Map<String, Object>,
            "req-ogc-5-${action}")

        then: 'exactly one question, asked at the layer that decides'
        1 * csMock.ontologyGateCheck(_, _) >> [indexed      : true,
                                               locate_called: true,
                                               allow        : true,
                                               reason       : 'locate-called']

        where: 'every action that was gated twice before FS 0.9.30'
        action       | opts
        'read'       | [:]
        'range'      | [startLine: 1, maxLines: 5]
        'get_method' | [method: 'execute']

        // Red at 2 before this release: dispatch asked, and then doRead / doRange / the
        // case 'get_method' branch each asked again. Two answers to one question is not twice as
        // safe -- it is one CS round trip wasted per read, and a gate that LOOKS covered at two
        // layers when only one of them decides. A third copy lived in FileContentReader.doGetMethod,
        // which production never calls, so its gate ran only when a spec called it.
    }

    def 'OGC-6: multi_grep and multi answer identically for an unlocated indexed path'() {
        given: 'one indexed-and-unlocated file and one the ontology does not know'
        File blockedFile = writeGroovy('BlockedOne.groovy', 'class BlockedOne { void run() {} }')
        File freeFile    = writeGroovy('FreeOne.groovy',    'class FreeOne { void run() {} }')
        String blockedPath = pathService.normalizePath(blockedFile.absolutePath)
        String freePath    = pathService.normalizePath(freeFile.absolutePath)

        and: 'CS answers per path, so the mixed case is exercised rather than assumed'
        ContextServerClient csStub = Stub(ContextServerClient)
        csStub.isCsReachable() >> true
        csStub.ontologyGateCheck(_, _) >> { String p, String s ->
            p.contains('BlockedOne')
                ? [indexed: true, locate_called: false, allow: false,
                   locate_query: 'class:filesystem/BlockedOne', reason: 'blocked-no-locate']
                : [indexed: false, allow: true, reason: 'not-indexed']
        }
        helper.contextServerClient = csStub

        when:
        Map multiData = parse(fileReadService.handleToolCall('file_read',
            [action: 'multi', options: [paths: [blockedPath, freePath]]] as Map<String, Object>,
            'req-ogc-6a'))
        Map grepData = parse(fileReadService.handleToolCall('file_read',
            [action     : 'multi_grep',
             options    : [paths: [blockedPath, freePath], pattern: 'class']] as Map<String, Object>,
            'req-ogc-6b'))

        then: 'multi_grep is gated at all -- until FS 0.9.30 it was the last action that was not'
        List grepBlocked = grepData.blocked as List
        grepBlocked?.size() == 1

        and: 'and it answers exactly as multi does, rather than in a second dialect'
        List multiBlocked = multiData.blocked as List
        multiBlocked?.size() == 1
        (grepBlocked[0] as Map).error        == (multiBlocked[0] as Map).error
        (grepBlocked[0] as Map).error        == 'BLOCKED_ONTOLOGY_GATE'
        (grepBlocked[0] as Map).locate_query == (multiBlocked[0] as Map).locate_query

        and: 'both hand back the node_id CS resolved -- not the bare stem Fix D used to return'
        (multiBlocked[0] as Map).locate_query == 'class:filesystem/BlockedOne'

        and: 'the unindexed file is still served by both'
        (multiBlocked[0] as Map).file != freePath
        (grepBlocked[0] as Map).file  != freePath

        // Two assertions that fail for different reasons before this release: multi_grep had no
        // gate at all, so grepData.blocked was null; and multi answered
        // BLOCKED_UNRANGED_INDEXED_READ with locate_query set to the bare file STEM -- the exact
        // hint CS 1.0.62 shipped to remove, because 2,012 names are shared across source_files and
        // following such a hint resolves to a different file and leaves you still blocked.
    }
}
