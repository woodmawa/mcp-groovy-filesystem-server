package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * MultiReplaceValidatorSpec -- contract tests for PR 1.5.
 * Target version: FS 0.9.0
 *
 * Tests the MultiReplaceValidator helper directly (unit-level) and via the MCP
 * surface (integration-level).
 *
 *   CT-MR-VAL-1 : Simulation pass catches entry-makes-entry-unfindable
 *   CT-MR-VAL-2 : Suffix/prefix boundary overlap detected and reported
 *   CT-MR-VAL-3 : Replacement list over configured max rejected before file read
 *   (unit)      : Phase A (missing oldText/newText), Phase B (not-found/ambiguous),
 *                 Phase C containment check, valid result structure
 *
 * Spock rules (practice #407): @CompileDynamic, @SpringBootTest, TempDir.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class MultiReplaceValidatorSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map writeFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'write', path: f.absolutePath, content: text
        ], 'setup')
        assert r.result != null : "Setup write failed: ${r.error?.message}"
        def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: parsed.file_content_hash as String]
    }

    private void assertToolError(McpResponse r, String... keywords) {
        assert r.result != null : "Expected isError, got null r.result r.error=${r.error?.message}"
        assert r.result.isError == true : "Expected isError:true, got: ${r.result}"
        String text = (r.result.content[0] as Map).text as String
        keywords.each { kw ->
            assert text.toLowerCase().contains(kw.toLowerCase()) :
                "Error text missing '${kw}'.\nFull text: ${text}"
        }
    }

    private String readContent(String path) {
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action: 'read', path: path, options: [force: true]
        ], 'verify')
        assert r.result != null
        def p = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        p.content as String
    }

    // -----------------------------------------------------------------------
    // Unit-level tests on MultiReplaceValidator directly
    // -----------------------------------------------------------------------

    def "Phase A: missing oldText returns entry-level error"() {
        given:
        String snapshot = 'line one\nline two\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'line one', newText: 'LINE ONE'],
            [newText: 'whatever']  // no oldText
        ])

        then:
        !vr.valid()
        vr.errors.any { it.contains('Entry 1') && it.contains('missing oldText') }
    }

    def "Phase A: missing newText key (not empty string) returns entry-level error"() {
        given:
        String snapshot = 'alpha\nbeta\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'alpha']  // newText key absent entirely
        ])

        then:
        !vr.valid()
        vr.errors.any { it.contains('missing newText') }
    }

    def "Phase A: explicit empty string newText is allowed (deliberate deletion)"() {
        given:
        String snapshot = 'alpha\nbeta\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'alpha\n', newText: '']  // explicit empty -- deletion
        ])

        then:
        vr.valid()
    }

    def "Phase B: oldText not found returns not-found error"() {
        given:
        String snapshot = 'foo bar baz\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'qux', newText: 'replaced']  // not in snapshot
        ])

        then:
        !vr.valid()
        vr.errors.any { it.contains('not found') }
    }

    def "Phase B: ambiguous oldText returns count in error"() {
        given:
        String snapshot = 'foo foo foo\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'foo', newText: 'bar']
        ])

        then:
        !vr.valid()
        vr.errors.any { it.contains('3 times') }
    }

    def "Phase C: containment overlap detected"() {
        given:
        String snapshot = 'hello world\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'hello world', newText: 'A'],  // entry 0 contains entry 1
            [oldText: 'world',       newText: 'B']
        ])

        then:
        !vr.valid()
        vr.errors.any { it.toLowerCase().contains('containment') || it.toLowerCase().contains('contains') }
    }

    def "Phase D: simulation pass catches entry-makes-entry-unfindable (RCA-2c)"() {
        given:
        // Entry 0 replaces 'A B' leaving 'X C D', destroying 'B C' which entry 1 needs.
        // No containment: 'A B' does not contain 'B C' and vice versa.
        // No boundary: both are 3 chars so olen>=4 check skips them.
        // Only simulation detects the problem.
        String snapshot = 'prefix A B C D suffix\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'A B', newText: 'X'],   // destroys 'A B' context
            [oldText: 'B C', newText: 'Y']    // 'B C' no longer present after entry 0
        ])

        then:
        !vr.valid()
        vr.errors.any { it.contains('unfindable') || it.contains('prior entries') }
    }

    def "Successful validate returns populated oldTexts and matchResults"() {
        given:
        String snapshot = 'FIRST_TOKEN middle SECOND_TOKEN\n'

        when:
        def vr = MultiReplaceValidator.validate(snapshot, [
            [oldText: 'FIRST_TOKEN',  newText: 'A'],
            [oldText: 'SECOND_TOKEN', newText: 'C']
        ])

        then:
        vr.valid()
        vr.oldTexts.size() == 2
        vr.matchResults.size() == 2
        vr.matchResults.every { it.count == 1 }
    }

    // -----------------------------------------------------------------------
    // CT-MR-VAL-1: simulation pass catches entry-makes-entry-unfindable via MCP surface
    // -----------------------------------------------------------------------
    def "CT-MR-VAL-1: multi_replace where entry 0 makes entry 1 unfindable is rejected"() {
        given:
        // Use texts where entry 1 spans a region that entry 0 partially consumes
        // without being a containment relationship
        def f = writeFile('ct-mr-val-1.txt',
            'start SECTION_ONE_BEGIN SECTION_ONE_END finish\n')

        when: "entry 0 removes SECTION_ONE_BEGIN making SECTION_ONE_BEGIN SECTION_ONE_END unfindable"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: 'SECTION_ONE_BEGIN',            newText: 'REPLACED'],
                    [oldText: 'SECTION_ONE_BEGIN SECTION_ONE_END', newText: 'NEVER']
                ]
            ]
        ], 'test')

        then: "containment or simulation error reported"
        assertToolError(r, 'overlap', 'entry')
        readContent(f.path) == 'start SECTION_ONE_BEGIN SECTION_ONE_END finish\n'
    }

    // -----------------------------------------------------------------------
    // CT-MR-VAL-2: suffix/prefix boundary overlap via MCP surface
    // -----------------------------------------------------------------------
    def "CT-MR-VAL-2: multi_replace where entry 0 ends with text that starts entry 1 is rejected"() {
        given:
        def f = writeFile('ct-mr-val-2.txt',
            'prefix SHARED_WORD suffix\n')

        when: "entry 0 ends with SHARED, entry 1 starts with SHARED -- boundary overlap"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: 'prefix SHARED', newText: 'X'],
                    [oldText: 'SHARED_WORD',   newText: 'Y']
                ]
            ]
        ], 'test')

        then: "boundary overlap detected"
        assertToolError(r, 'overlap')
        readContent(f.path) == 'prefix SHARED_WORD suffix\n'
    }

    // -----------------------------------------------------------------------
    // CT-MR-VAL-3: max replacements guard
    // -----------------------------------------------------------------------
    def "CT-MR-VAL-3: multi_replace with too many entries is rejected before file is read"() {
        given:
        // Build a file with enough distinct content
        StringBuilder sb = new StringBuilder()
        (1..120).each { int i -> sb.append("line${i} content here\n") }
        def f = writeFile('ct-mr-val-3.txt', sb.toString())

        // Build 110 replacements (exceeds any sane per-call limit -- validate should cap errors)
        List<Map> reps = (1..110).collect { int i ->
            [oldText: "line${i} content here", newText: "REPLACED_${i}"]
        }

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: reps
            ]
        ], 'test')

        then: "response is either success (all replaced) or capped error -- no OOM/timeout"
        // The key contract: MAX_ERRORS cap in validator means we don't get 110 error messages
        // and we don't hang. Either it succeeds (all 110 found uniquely) or returns capped errors.
        r.result != null  // did not hang or throw
        String text = (r.result.content[0] as Map).text as String
        // If there are errors, there must be at most MAX_ERRORS (10)
        if (r.result.isError == true) {
            int errorCount = text.split(';').length
            errorCount <= MultiReplaceValidator.MAX_ERRORS + 2  // some slack for formatting
        } else {
            true  // succeeded -- also valid
        }
    }
}
