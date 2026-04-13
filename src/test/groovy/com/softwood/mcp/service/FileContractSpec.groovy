package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FileContractSpec -- TDD contract tests for FS 0.8.48.
 *
 * These tests assert on what Claude Desktop ACTUALLY SEES:
 *   - Errors must come as isError:true in content, NOT as JSON-RPC error objects
 *   - Success responses are parsed from content[0].text JSON
 *
 * Tests CT-1..CT-13 are defined to FAIL against 0.8.47 on error-surface cases.
 * After 0.8.48 fixes land, all 13 must pass.
 *
 * TDD discipline:
 *   Run against 0.8.47 first -- confirm CT-1..CT-10 (and CT-11..CT-13 where applicable) fail.
 *   Then apply fixes, re-run to green.
 *
 * Spock rules (practice #407):
 *   - @CompileDynamic on spec class
 *   - @SpringBootTest wires real beans
 *   - TempDir for test isolation
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class FileContractSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // Helpers: mirror what Claude Desktop actually renders
    // -----------------------------------------------------------------------

    /**
     * Set up a scratch file. Returns [path, hash].
     */
    private Map writeFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'write',
            path   : f.absolutePath,
            content: text,
            options: [verbose: true]
        ], 'setup')
        assert r.error == null : "setup write failed: ${r.error}"
        def result = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: (result.file_content_hash ?: result.content_hash) as String]
    }

    /**
     * Parse the JSON payload Claude sees from a SUCCESS response.
     * Fails hard if the response has no result or content.
     */
    private Map parseContent(McpResponse r) {
        assert r.result != null : "Expected success response with content, got error: ${r.error?.message}"
        new groovy.json.JsonSlurper().parseText(
            (r.result.content[0] as Map).text as String
        ) as Map
    }

    /**
     * Assert the response is a tool-level error visible to Claude:
     *   r.result != null  (NOT a JSON-RPC error object)
     *   r.result.isError == true
     *   content[0].text contains every supplied keyword
     */
    private void assertToolError(McpResponse r, String... keywords) {
        assert r.result != null :
            "Expected isError content response, but r.result is null. " +
            "r.error=${r.error?.message} — this is the OLD broken contract (RCA-1)."
        assert r.result.isError == true :
            "Expected isError:true, got: ${r.result}"
        String text = (r.result.content[0] as Map).text as String
        keywords.each { kw ->
            assert text.toLowerCase().contains(kw.toLowerCase()) :
                "Error text missing keyword '${kw}'.\nFull text: ${text}"
        }
    }

    /**
     * Read current content of a file as a string.
     */
    private String readFileContent(String path) {
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action: 'read',
            path  : path,
            options: [force: true]
        ], 'verify')
        assert r.result != null : "Read failed for ${path}: ${r.error?.message}"
        def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        parsed.content as String
    }

    // -----------------------------------------------------------------------
    // CT-1: replace missing oldText -> visible error
    // -----------------------------------------------------------------------
    def "CT-1: replace with missing oldText surfaces visible error (not swallowed)"() {
        given:
        def f = writeFile('ct1.txt', "line1\nline2\nline3\n")

        when: "oldText does not exist in file"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: 'NOTEXIST_XYZ', newText: 'whatever', expectedHash: f.hash]
        ], 'ct1')

        then: "Claude sees an error, not silence"
        assertToolError(r, 'oldText', 'not found')
    }

    // -----------------------------------------------------------------------
    // CT-2: replace with no options -> visible error
    // -----------------------------------------------------------------------
    def "CT-2: replace with null/missing options surfaces visible error"() {
        given:
        def f = writeFile('ct2.txt', "line1\nline2\n")

        when: "no options provided at all"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'replace',
            path  : f.path
        ], 'ct2')

        then:
        assertToolError(r, 'oldText')
    }

    // -----------------------------------------------------------------------
    // CT-3: replace hash mismatch -> visible error
    // -----------------------------------------------------------------------
    def "CT-3: replace with wrong expectedHash surfaces visible error"() {
        given:
        def f = writeFile('ct3.txt', "line1\nline2\nline3\n")

        when: "deliberately wrong hash"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: 'line1', newText: 'replaced', expectedHash: '000000000000']
        ], 'ct3')

        then:
        assertToolError(r, 'hash')
    }

    // -----------------------------------------------------------------------
    // CT-4: multi_replace with boundary overlap -> visible error
    // -----------------------------------------------------------------------
    def "CT-4: multi_replace with suffix/prefix overlap is rejected with actionable message"() {
        given:
        def f = writeFile('ct4.txt', "    a()\n    b()\n    c()\n")

        when: "entry 0 ends with same line that entry 1 starts with"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                replacements: [
                    [oldText: "    a()\n    b()", newText: "    a()\n    B()"],
                    [oldText: "    b()\n    c()", newText: "    B()\n    c()"]
                ],
                expectedHash: f.hash
            ]
        ], 'ct4')

        then:
        assertToolError(r, 'overlap')
    }

    // -----------------------------------------------------------------------
    // CT-5: multi_replace partial apply (entry 1 made unfindable by entry 0)
    // -----------------------------------------------------------------------
    def "CT-5: multi_replace where entry 0 removes entry 1 oldText fails whole batch"() {
        given:
        def f = writeFile('ct5.txt', "    X()\n    Y()\n")
        String originalContent = readFileContent(f.path)

        when: "entry 0 removes the text that entry 1 needs"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                replacements: [
                    [oldText: "    X()", newText: "    Z()"],
                    [oldText: "    X()", newText: "    W()"]  // same as entry 0, now gone after first apply
                ],
                expectedHash: f.hash
            ]
        ], 'ct5')

        then: "whole batch fails, file is unchanged"
        assertToolError(r)
        readFileContent(f.path) == originalContent
    }

    // -----------------------------------------------------------------------
    // CT-6: patch missing replacements -> visible error
    // -----------------------------------------------------------------------
    def "CT-6: patch with no options.replacements surfaces visible error"() {
        given:
        def f = writeFile('ct6.txt', "line1\nline2\nline3\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [expectedHash: f.hash]   // no replacements key
        ], 'ct6')

        then:
        assertToolError(r, 'replacements')
    }

    // -----------------------------------------------------------------------
    // CT-7: patch hash mismatch -> visible error
    // -----------------------------------------------------------------------
    def "CT-7: patch with wrong expectedHash surfaces visible error"() {
        given:
        def f = writeFile('ct7.txt', "line1\nline2\nline3\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: '000000000000',
                replacements: [[startLine: 1, endLine: 1, newText: 'replaced']]
            ]
        ], 'ct7')

        then:
        assertToolError(r, 'hash')
    }

    // -----------------------------------------------------------------------
    // CT-8: patch invalid range (beyond file length) -> visible error
    // -----------------------------------------------------------------------
    def "CT-8: patch with out-of-range startLine surfaces visible error"() {
        given: "file has only 3 lines"
        def f = writeFile('ct8.txt', "line1\nline2\nline3\n")

        when: "patch targets lines 99-100 which don't exist"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[startLine: 99, endLine: 100, newText: 'X']]
            ]
        ], 'ct8')

        then:
        assertToolError(r, 'range')
    }

    // -----------------------------------------------------------------------
    // CT-9: server_transform unknown transform -> visible error
    // -----------------------------------------------------------------------
    def "CT-9: server_transform with unknown transform name surfaces visible error"() {
        given:
        def f = writeFile('ct9.groovy', "class Foo {\n    void bar() { }\n}\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform    : 'nonexistent_transform_xyz',
                expectedHash : f.hash
            ]
        ], 'ct9')

        then:
        assertToolError(r, 'unknown')
    }

    // -----------------------------------------------------------------------
    // CT-10: server_transform hash mismatch -> visible error
    // -----------------------------------------------------------------------
    def "CT-10: server_transform with wrong expectedHash surfaces visible error"() {
        given:
        def f = writeFile('ct10.groovy', "class Foo {\n    void bar() { }\n}\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform    : 'replace_method',
                expectedHash : '000000000000',
                methodName   : 'bar',
                newBody      : '    void bar() { println "new" }'
            ]
        ], 'ct10')

        then:
        assertToolError(r, 'hash')
    }

    // -----------------------------------------------------------------------
    // CT-11: multi_replace unbalanced brace -> BLOCKED before write
    // -----------------------------------------------------------------------
    def "CT-11: multi_replace with unbalanced brace in newText is blocked before file is written"() {
        given:
        def f = writeFile('ct11.groovy',
            "class Foo {\n    void bar() {\n        doSomething()\n    }\n}\n")
        String originalContent = readFileContent(f.path)

        when: "newText opens extra brace without closing it"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                replacements: [
                    [oldText: "        doSomething()", newText: "        doSomething() {\n            // extra open brace"]
                ],
                expectedHash: f.hash
            ]
        ], 'ct11')

        then: "error surfaced, file not modified"
        assertToolError(r, 'brace')
        readFileContent(f.path) == originalContent
    }

    // -----------------------------------------------------------------------
    // CT-12: boundary patch response contains requires_reread:true
    // -----------------------------------------------------------------------
    def "CT-12: boundary patch success response includes requires_reread:true"() {
        given: "file with at least 5 lines so we can target line 1 (boundary)"
        def f = writeFile('ct12.groovy',
            "line1\nline2\nline3\nline4\nline5\n")

        when: "patch at line 1 — start boundary"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[startLine: 1, endLine: 1, newText: 'LINE_ONE']]
            ]
        ], 'ct12')

        then: "success, and requires_reread is set"
        r.result != null
        r.result.isError == null || r.result.isError == false
        def parsed = parseContent(r)
        parsed.requires_reread == true
    }

    // -----------------------------------------------------------------------
    // CT-13: multi_replace applies in position order regardless of input order
    // -----------------------------------------------------------------------
    def "CT-13: multi_replace with reversed-order entries applies all correctly (position order)"() {
        given:
        def f = writeFile('ct13.txt', "aaa\nbbb\nccc\n")

        when: "entries given in reverse position order: ccc first, aaa second"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                replacements: [
                    [oldText: 'ccc', newText: 'CCC'],
                    [oldText: 'aaa', newText: 'AAA']
                ],
                expectedHash: f.hash
            ]
        ], 'ct13')

        then: "both replacements applied, position-order not input-order"
        r.result != null
        r.result.isError == null || r.result.isError == false
        readFileContent(f.path) == "AAA\nbbb\nCCC\n"
    }
}
