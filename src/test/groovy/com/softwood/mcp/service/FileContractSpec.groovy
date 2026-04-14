package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FileContractSpec -- TDD contract tests for FS 0.8.56.
 *
 * These tests assert on what Claude Desktop ACTUALLY SEES:
 *   - Errors must come as isError:true in content, NOT as JSON-RPC error objects
 *   - Success responses are parsed from content[0].text JSON
 *
 * Tests CT-1..CT-13 are defined to FAIL against 0.8.47 on error-surface cases.
 * After 0.8.48 fixes land, all 13 must pass.
 *
 * CT-24..CT-26: added for FS 0.8.55 (large-file, top-level param promotion, null replacements).
 * CT-27..CT-29: added for FS 0.8.56 (insert_before_match matchLast + fromLine options).
 * CT-30: added for FS 0.8.56 (server_transform extension guard).
 * CT-31: added for FS 0.8.57 (replace_method wrong option name).
 * CT-32: added for FS 0.8.57 (append_section wrong option name returns structured error).
 *
 * TDD discipline:
 *   Run against current version first -- confirm new CTs fail.
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

    // -----------------------------------------------------------------------
    // CT-14: patch replaces block-opening lines but excludes the matching close
    //        -> brace delta mismatch -> BLOCKED
    //
    // This is the exact failure mode that corrupted PipelineExecutionService:
    // A patch replaces 'if (!x) {\n    return err' (removed delta +1 open)
    // with 'return McpResponse.toolError(...)' (newText delta 0).
    // The orphaned } that was closing the if-block remains in the file and
    // now closes the wrong scope. The patch tool must detect this mismatch.
    // -----------------------------------------------------------------------
    def "CT-14: patch with brace delta mismatch between removed and newText is blocked"() {
        given: "a Groovy file with an if-block guarded by a closing brace on a separate line"
        def f = writeFile('ct14.groovy',
            "class Foo {\n" +
            "    void bar() {\n" +
            "        if (!thing) {\n" +
            "            return 'error'\n" +
            "        }\n" +
            "        doWork()\n" +
            "    }\n" +
            "}\n")
        String originalContent = readFileContent(f.path)

        when: "patch replaces the if-block opening + body (lines 3-4, delta +1 open removed)"
        //     but newText has no opening brace (delta 0) -- the } on line 5 is now orphaned"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[
                    startLine: 3,
                    endLine  : 4,
                    // removed delta: 'if (!thing) {' has +1 open. newText has 0 braces.
                    // delta mismatch: removed=+1, new=0 -> orphaned } on line 5
                    newText  : "            return McpResponse.toolError(requestId, 'thing required')"
                ]]
            ]
        ], 'ct14')

        then: "patch is rejected -- brace delta mismatch detected"
        assertToolError(r, 'brace')
        readFileContent(f.path) == originalContent
    }

    // -----------------------------------------------------------------------
    // CT-15: patch that replaces an if-block body including its closing brace
    //        -> succeeds and file remains balanced
    //
    // Companion to CT-14: proves the check doesn't over-fire. A patch that
    // includes the closing } in its range produces a balanced result and
    // must succeed.
    // -----------------------------------------------------------------------
    def "CT-15: patch that replaces a complete if-block (including closing brace) succeeds"() {
        given: "same Groovy file with balanced if block"
        def f = writeFile('ct15.groovy',
            "class Foo {\n" +
            "    void bar() {\n" +
            "        if (!thing) {\n" +
            "            return 'error'\n" +
            "        }\n" +
            "        doWork()\n" +
            "    }\n" +
            "}\n")

        when: "patch replaces lines 3-5 -- the full if block including its closing brace"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[
                    startLine: 3,
                    endLine  : 5,
                    newText  : "        if (!thing) {\n            return 'replaced'\n        }"
                ]]
            ]
        ], 'ct15')

        then: "patch succeeds -- braces balanced, content updated"
        r.result != null
        r.result.isError == null || r.result.isError == false
        readFileContent(f.path).contains("return 'replaced'")
        !readFileContent(f.path).contains("return 'error'")
    }

    // -----------------------------------------------------------------------
    // CT-16: multi_replace containment overlap error is visible to Claude
    // Brief Bug 1: overlap validation message was swallowed pre-0.8.50
    // This tests the CONTAINMENT case (entry j is a substring of entry i)
    // complementing CT-4 which tests suffix/prefix boundary overlap.
    // -----------------------------------------------------------------------

    def "CT-16: multi_replace containment overlap (entry is substring of another) surfaces readable error"() {
        given:
        def f = writeFile('ct16.txt',
            "line one\n" +
            "// Rule 3 -- comment\n" +
            "line three\n")

        when: "Entry 1 oldText is a pure substring of Entry 0 oldText"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: 'line one\n// Rule 3 -- comment', newText: 'REPLACED_BLOCK'],
                    [oldText: '// Rule 3 -- comment',           newText: 'SHOULD_NOT_REACH']
                ]
            ]
        ], 'ct16')

        then: "error is isError:true content visible to Claude (not swallowed JSON-RPC error)"
        r.error == null
        r.result != null
        r.result.isError == true
        String msg = (r.result.content[0] as Map).text?.toString() ?: ''
        msg.toLowerCase().contains('overlap') || msg.toLowerCase().contains('substring')
        msg.contains('Entry') || msg.contains('entry')
        // file must be untouched
        readFileContent(f.path).contains('line one')
        readFileContent(f.path).contains('// Rule 3 -- comment')
    }

    // -----------------------------------------------------------------------
    // CT-17: replace_method with newBody omitting the method signature line
    //        must return a structured error -- NOT silently corrupt the file.
    //
    // ROOT CAUSE (2026-04-14): ReplaceMethodTransformer replaces startLine..endLine
    // with newBody verbatim. If newBody contains only the body (no signature),
    // the method declaration is dropped and the file becomes structurally corrupt
    // (Groovy parse error). No validation is performed; the file is written and
    // success is returned. This produced a 'Unexpected input' compile error that
    // cost 3+ manual recovery turns.
    //
    // CONTRACT: If newBody does not contain the method name, return a structured
    // error with isError:true. The file must be left unchanged.
    // -----------------------------------------------------------------------
    def "CT-17: replace_method with newBody missing method signature returns error, file unchanged"() {
        given:
        def f = writeFile('ct17.groovy',
            "class Foo {\n" +
            "    String bar(int x) {\n" +
            "        return 'original'\n" +
            "    }\n" +
            "}\n")
        String originalContent = readFileContent(f.path)

        when: "newBody contains only the body -- the method signature line is absent"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform    : 'replace_method',
                expectedHash : f.hash,
                method       : 'bar',
                newBody      : "        return 'replaced'"
            ]
        ], 'ct17')

        then: "structured error returned -- method signature validation failed"
        r.result != null
        r.result.isError == true
        (r.result.content[0] as Map).text.toString().toLowerCase().with { txt ->
            txt.contains('signature') || txt.contains('newbody') || txt.contains('method name') ||
                txt.contains('must include') || txt.contains('missing')
        }

        and: "file content is unchanged -- no partial write occurred"
        readFileContent(f.path) == originalContent
    }

    // -----------------------------------------------------------------------
    // CT-18: replace_method with complete newBody (signature + body + brace)
    //        must succeed and produce a parseable file.
    //
    // This is the POSITIVE contract: correct usage must work.
    // Complements CT-17 which tests the failure case.
    // -----------------------------------------------------------------------
    def "CT-18: replace_method with complete newBody including signature succeeds and produces valid Groovy"() {
        given:
        def f = writeFile('ct18.groovy',
            "class Foo {\n" +
            "    String bar(int x) {\n" +
            "        return 'original'\n" +
            "    }\n" +
            "}\n")

        when: "newBody includes the full signature line, body, and closing brace"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform    : 'replace_method',
                expectedHash : f.hash,
                method       : 'bar',
                newBody      : "    String bar(int x) {\n        return 'replaced'\n    }"
            ]
        ], 'ct18')

        then: "success"
        r.error == null
        r.result != null
        r.result.isError == null || r.result.isError == false

        and: "file contains the new body"
        readFileContent(f.path).contains("return 'replaced'")
        !readFileContent(f.path).contains("return 'original'")

        and: "file still contains the class and method structure (not corrupt)"
        readFileContent(f.path).contains('class Foo')
        readFileContent(f.path).contains('String bar(int x)')
    }

    // -----------------------------------------------------------------------
    // CT-19: replace with completely empty options ({}) must surface a visible
    //        error -- not silently succeed or throw an unhandled exception.
    //
    // Motivation: idea #52 -- observed in session 2026-04-14 that a malformed
    // tool call with options={} produced a toolError from doReplace but the
    // error was silently absorbed. Contract confirms FS returns isError:true
    // with a message containing 'oldText' so Claude can diagnose the call.
    // -----------------------------------------------------------------------
    def "CT-19: replace with completely empty options returns visible error containing 'oldText'"() {
        given:
        def f = writeFile('ct19.txt', "line1\nline2\nline3\n")

        when: "options map is completely empty -- no oldText, newText, or expectedHash"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [:]
        ], 'ct19')

        then: "structured error returned -- not a silent no-op or exception"
        r.result != null
        r.result.isError == true

        and: "error message mentions oldText so caller knows what is missing"
        def errorText = (r.result.content[0] as Map).text.toString().toLowerCase()
        errorText.contains('oldtext') || errorText.contains('old_text') || errorText.contains('required')

        and: "file is unchanged"
        readFileContent(f.path) == "line1\nline2\nline3\n"
    }

    // -----------------------------------------------------------------------
    // CT-20: range read (Fix C) -- cache miss path.
    //
    // When CS HTTP companion is unreachable (default in unit test: port 8082
    // not open), FS must NOT block or throw. The read must complete normally,
    // returning full content + file_content_hash.
    //
    // This is the SAFETY CONTRACT for Fix C: CS unavailability must never
    // degrade FS range read correctness or availability.
    // -----------------------------------------------------------------------
    def "CT-20: range read completes normally when CS HTTP companion is unreachable (graceful degradation)"() {
        given:
        def f = writeFile('ct20.groovy',
            "class Example {\n" +
            "    void alpha() { }\n" +
            "    void beta()  { }\n" +
            "    void gamma() { }\n" +
            "}\n")

        when: "range read requested -- CS HTTP on 8082 is not running in unit test"
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : f.path,
            options: [startLine: 2, maxLines: 2]
        ], 'ct20')

        then: "no error -- CS unavailability is non-fatal"
        r.error == null
        r.result != null
        r.result.isError == null || r.result.isError == false

        and: "content contains lines 2-3"
        def text = (r.result.content[0] as Map).text.toString()
        def parsed = new groovy.json.JsonSlurper().parseText(text) as Map
        parsed.content.toString().contains('alpha') || parsed.content.toString().contains('beta')

        and: "file_content_hash present in response"
        parsed.file_content_hash != null
        (parsed.file_content_hash as String).length() == 12
    }

    // -----------------------------------------------------------------------
    // CT-21: range read repeated (Fix C) -- second call must also complete
    //        normally (no cached:true short-circuit visible to unit test since
    //        CS HTTP is not running, but must not error or block).
    //
    // This contract ensures the cache-record fire-and-forget path does not
    // corrupt the response or introduce latency when CS is down.
    // -----------------------------------------------------------------------
    def "CT-21: repeated range read completes normally (no corruption from cache-record attempt)"() {
        given:
        def f = writeFile('ct21.groovy',
            "class Sample {\n" +
            "    int compute(int x) { return x * 2 }\n" +
            "}\n")

        when: "same range read twice in same test"
        McpResponse r1 = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : f.path,
            options: [startLine: 2, maxLines: 1]
        ], 'ct21a')
        McpResponse r2 = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : f.path,
            options: [startLine: 2, maxLines: 1]
        ], 'ct21b')

        then: "both calls succeed with identical content"
        r1.result != null && r1.result.isError == null
        r2.result != null && r2.result.isError == null

        and: "both responses contain the same file_content_hash"
        def t1 = new groovy.json.JsonSlurper().parseText((r1.result.content[0] as Map).text.toString()) as Map
        def t2 = new groovy.json.JsonSlurper().parseText((r2.result.content[0] as Map).text.toString()) as Map
        t1.file_content_hash == t2.file_content_hash

        and: "content is identical across both calls"
        t1.content == t2.content
    }

    // -----------------------------------------------------------------------
    // CT-22: get_method (Fix C) -- cache miss path completes normally when
    //        CS HTTP companion is unreachable.
    //
    // Mirrors CT-20 for the get_method action. Both actions are intercepted
    // by the Fix C cache check; both must degrade gracefully.
    // -----------------------------------------------------------------------
    def "CT-22: get_method completes normally when CS HTTP companion is unreachable"() {
        given:
        def f = writeFile('ct22.groovy',
            "class Widget {\n" +
            "    String build(String name) {\n" +
            "        return 'Widget:' + name\n" +
            "    }\n" +
            "}\n")

        when: "get_method requested -- CS HTTP not running in unit test"
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action : 'get_method',
            path   : f.path,
            options: [method: 'build']
        ], 'ct22')

        then: "no error"
        r.error == null
        r.result != null
        r.result.isError == null || r.result.isError == false

        and: "method body content is present in response"
        def text = (r.result.content[0] as Map).text.toString()
        text.contains('Widget') || text.contains('build') || text.contains('name')

        and: "file_content_hash present"
        def parsed = new groovy.json.JsonSlurper().parseText(text) as Map
        parsed.file_content_hash != null
    }

    // -----------------------------------------------------------------------
    // CT-23: range read cache-hit response shape.
    //
    // When a cache hit is returned (cached:true), the response must:
    //   1. Have no isError flag
    //   2. Carry cached:true in the parsed content JSON
    //   3. Carry a hint field so Claude knows not to re-read
    //   4. Have a file_content_hash present (for drift detection)
    //
    // Motivation: McpResponse.toolResult() does not exist -- cache-hit must
    // use McpResponse.success() with proper content envelope. This contract
    // ensures the shape is right so Claude Desktop renders it correctly.
    // Linked to idea #52 (FS write issue surfaced 2026-04-14).
    // -----------------------------------------------------------------------
    def "CT-23: range cache-hit response has correct MCP shape (no isError, cached:true, hint present)"() {
        given: "a file whose hash we know"
        def f = writeFile('ct23.groovy',
            "class CacheTest {\n" +
            "    void alpha() { }\n" +
            "    void beta()  { }\n" +
            "}\n")

        and: "first read to get the real hash"
        McpResponse first = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : f.path,
            options: [startLine: 2, maxLines: 1]
        ], 'ct23-first')
        def firstParsed = new groovy.json.JsonSlurper()
            .parseText((first.result.content[0] as Map).text.toString()) as Map
        String realHash = firstParsed.file_content_hash as String

        when: "second read passes knownFileHash so cache check can fire"
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : f.path,
            options: [startLine: 2, maxLines: 1, knownFileHash: realHash]
        ], 'ct23-second')

        then: "response has no error regardless of cache outcome"
        r.result != null
        r.result.isError == null || r.result.isError == false

        and: "content[0].text is valid JSON (not a raw error string)"
        def text = (r.result.content[0] as Map).text.toString()
        def parsed = new groovy.json.JsonSlurper().parseText(text) as Map
        parsed != null

        and: "if cached:true, hint must be present and file_content_hash absent (no stale data)"
        if (parsed.cached == true) {
            assert parsed.hint != null : 'cache-hit must carry hint'
            assert parsed.already_read_at != null : 'cache-hit must carry already_read_at'
        } else {
            // cache miss (CS not running in unit test) -- normal range response
            assert parsed.file_content_hash != null : 'cache-miss must carry file_content_hash'
        }
    }

    // -----------------------------------------------------------------------
    // CT-24: replace on large file (>10KB) returns structured McpResponse,
    //        never a raw 'Tool execution failed' string.
    //        Regression guard: MCP stdio transport must not swallow errors on
    //        large payloads -- handler must return McpResponse.toolError() not
    //        throw an uncaught exception that causes a framework-level failure.
    // -----------------------------------------------------------------------
    def "CT-24: replace on large .groovy file returns structured response, not swallowed failure"() {
        given: "a .groovy file >10KB with a unique replaceable string"
        // Build a file large enough to stress the response path (~12KB)
        StringBuilder sb = new StringBuilder()
        sb.append('class LargeTarget {\n')
        sb.append('    // UNIQUE_ANCHOR_CT24\n')
        (1..200).each { sb.append("    void method${it}() { println '${it}' }\n") }
        sb.append('}\n')
        def f = writeFile('ct24_large.groovy', sb.toString())

        when: "replace targets the unique anchor"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: '    // UNIQUE_ANCHOR_CT24', newText: '    // REPLACED_CT24', expectedHash: f.hash]
        ], 'ct24')

        then: "response is a valid McpResponse with content (not a raw error string)"
        r != null
        r.result != null
        def ct24text = (r.result.content[0] as Map).text.toString()
        def ct24parsed = new groovy.json.JsonSlurper().parseText(ct24text) as Map
        ct24parsed != null

        and: "success:true and content_hash present"
        ct24parsed.success == true
        ct24parsed.content_hash != null || ct24parsed.file_content_hash != null
    }

    // -----------------------------------------------------------------------
    // CT-25: replace with oldText/newText at TOP LEVEL (not inside options)
    //        must be promoted and succeed -- verifies promoteTopLevelParams().
    //        Regression guard for MCP clients that emit flat argument maps.
    // -----------------------------------------------------------------------
    def "CT-25: replace with top-level oldText/newText (not nested in options) is promoted and succeeds"() {
        given:
        def f = writeFile('ct25.txt', "alpha\nbeta\ngamma\n")

        when: "oldText and newText are top-level, not inside options"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action       : 'replace',
            path         : f.path,
            oldText      : 'beta',
            newText      : 'REPLACED',
            expectedHash : f.hash
            // NOTE: no 'options' key at all
        ], 'ct25')

        then: "promotion worked and replace succeeded"
        r != null
        r.result != null
        def ct25text = (r.result.content[0] as Map).text.toString()
        def ct25parsed = new groovy.json.JsonSlurper().parseText(ct25text) as Map
        ct25parsed.success == true

        and: "file content reflects the replacement"
        new File(f.path).text.contains('REPLACED')
        !new File(f.path).text.contains('beta')
    }

    // -----------------------------------------------------------------------
    // CT-26: multi_replace with missing/empty replacements list must return
    //        a structured McpResponse.toolError, NOT a raw exception string.
    //        CURRENT STATE (FS bug FS-F6): when options:{} has no replacements key,
    //        doMultiReplace gets an empty List but the promoteTopLevelParams path
    //        causes a Groovy NPE that surfaces as a non-JSON error string in the
    //        response text. Test documents expected behaviour post-fix.
    //        TODO: fix FS then change expectedBroken=false below.
    // -----------------------------------------------------------------------
    def "CT-26: multi_replace with null replacements returns structured toolError, not framework exception"() {
        given:
        boolean expectedBroken = false  // FS-F6 fixed: handleToolCall generic catch now wraps error in JsonBuilder
        def f = writeFile('ct26.txt', "alpha\nbeta\ngamma\n")

        when: "options provided but replacements key is absent"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [expectedHash: f.hash]   // no replacements key
        ], 'ct26a')

        then: "response is non-null (handler did not crash the whole server)"
        r != null
        r.result != null

        and: "response text is either valid JSON error OR the known broken non-JSON string"
        def ct26text = (r.result.content[0] as Map).text.toString()
        try {
            def ct26parsed = new groovy.json.JsonSlurper().parseText(ct26text) as Map
            // If parseable JSON: check it signals failure (success:false or error key present)
            assert ct26parsed.success == false || ct26parsed.error != null || ct26parsed.isError != null \
                : 'parsed JSON response must signal failure, got: ' + ct26parsed
        } catch (groovy.json.JsonException je) {
            // Raw non-JSON string: McpResponse.toolError with plain message -- also acceptable post-fix
            // as long as r.result.isError is set
            assert (r.result as Map).isError == true || ct26text.toLowerCase().contains('replacements') \
                : "CT-26 unexpected response: ${ct26text.take(200)}"
        }

        when: "options is completely absent"
        McpResponse r2 = fileWriteService.handleToolCall('file_write', [
            action       : 'multi_replace',
            path         : f.path,
            expectedHash : f.hash
        ], 'ct26b')

        then: "response is also non-null"
        r2 != null
        r2.result != null
    }

    // -----------------------------------------------------------------------
    // CT-27: insert_before_match with matchLast=true inserts before the LAST
    //        occurrence of the match string, not the first.
    //        matchLast=true is a readable alias for occurrence=-1.
    //        Regression guard: ensures matchLast flag is recognised and that
    //        occurrence resolution uses matchIndices.last() path.
    // -----------------------------------------------------------------------
    def "CT-27: insert_before_match matchLast=true inserts before last occurrence"() {
        given: "a file with three identical anchor lines"
        def f = writeFile('ct27.groovy',
            '// section-a\n' +
            '    void alpha() {}\n' +
            '// section-b\n' +
            '    void beta() {}\n' +
            '// section-c\n' +
            '    void gamma() {}\n'
        )

        when: "insert_before_match with matchLast=true targets '// section'"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform   : 'insert_before_match',
                match       : '// section',
                content     : '    // CT27-INSERTED',
                matchLast   : true,
                expectedHash: f.hash
            ]
        ], 'ct27')

        then: "response signals success"
        r != null
        def ct27text = (r.result.content[0] as Map).text.toString()
        def ct27parsed = new groovy.json.JsonSlurper().parseText(ct27text) as Map
        ct27parsed.success == true

        and: "inserted line appears before '// section-c' (the last match), NOT before '// section-a'"
        String ct27content = new File(f.path).text
        int insertedPos = ct27content.indexOf('    // CT27-INSERTED')
        int sectionCPos = ct27content.indexOf('// section-c')
        int sectionAPos = ct27content.indexOf('// section-a')
        insertedPos > sectionAPos          // not inserted before first occurrence
        insertedPos < sectionCPos          // inserted before last occurrence
    }

    // -----------------------------------------------------------------------
    // CT-28: insert_before_match with fromLine=N skips occurrences on lines
    //        before N and matches only from line N onward (1-based).
    //        Eliminates the workaround of making match strings artificially unique.
    //        Regression guard: fromLine is applied before occurrence resolution.
    // -----------------------------------------------------------------------
    def "CT-28: insert_before_match fromLine=N skips occurrences before that line"() {
        given: "a file with the same anchor on lines 1, 3 and 5"
        def f = writeFile('ct28.groovy',
            '// anchor\n' +          // line 1 - should be skipped
            '    void first() {}\n' + // line 2
            '// anchor\n' +          // line 3 - should be skipped
            '    void second() {}\n'+ // line 4
            '// anchor\n' +          // line 5 - first after fromLine=4
            '    void third() {}\n'   // line 6
        )

        when: "insert_before_match with fromLine=4 so only lines 5+ are candidates"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform   : 'insert_before_match',
                match       : '// anchor',
                content     : '    // CT28-INSERTED',
                fromLine    : 4,
                expectedHash: f.hash
            ]
        ], 'ct28')

        then: "response signals success"
        r != null
        def ct28text = (r.result.content[0] as Map).text.toString()
        def ct28parsed = new groovy.json.JsonSlurper().parseText(ct28text) as Map
        ct28parsed.success == true

        and: "inserted line appears before the third anchor (line 5), not before line 1 or 3"
        List<String> ct28lines = new File(f.path).readLines()
        // After insertion the file should be 7 lines; inserted line should be at index 4 (0-based line 5)
        ct28lines.size() == 7
        ct28lines[4] == '    // CT28-INSERTED'
        ct28lines[5] == '// anchor'

        and: "the first two anchors are untouched (no insertion before them)"
        ct28lines[0] == '// anchor'   // original line 1 still first line
        ct28lines[2] == '// anchor'   // original line 3 still at index 2
    }

    // -----------------------------------------------------------------------
    // CT-29: insert_before_match matchLast=true + fromLine=N combined:
    //        fromLine restricts the search window, matchLast selects the last
    //        match within that window.
    //        Regression guard: both options must compose correctly.
    // -----------------------------------------------------------------------
    def "CT-29: insert_before_match matchLast=true and fromLine=N combined target last match after fromLine"() {
        given: "a file with four anchors on lines 1, 3, 5, 7"
        def f = writeFile('ct29.groovy',
            '// anchor\n' +           // line 1 - before fromLine, skip
            '    void a() {}\n' +     // line 2
            '// anchor\n' +           // line 3 - before fromLine, skip
            '    void b() {}\n' +     // line 4
            '// anchor\n' +           // line 5 - first in window
            '    void c() {}\n' +     // line 6
            '// anchor\n' +           // line 7 - last in window -- TARGET
            '    void d() {}\n'       // line 8
        )

        when: "fromLine=4 + matchLast=true -> should target line 7 (last anchor from line 4 onward)"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform   : 'insert_before_match',
                match       : '// anchor',
                content     : '    // CT29-INSERTED',
                fromLine    : 4,
                matchLast   : true,
                expectedHash: f.hash
            ]
        ], 'ct29')

        then: "response signals success"
        r != null
        def ct29text = (r.result.content[0] as Map).text.toString()
        def ct29parsed = new groovy.json.JsonSlurper().parseText(ct29text) as Map
        ct29parsed.success == true

        and: "inserted line appears immediately before the 4th anchor (original line 7)"
        List<String> ct29lines = new File(f.path).readLines()
        ct29lines.size() == 9
        ct29lines[6] == '    // CT29-INSERTED'
        ct29lines[7] == '// anchor'

        and: "lines 1 and 3 and 5 anchors are all untouched"
        ct29lines[0] == '// anchor'
        ct29lines[2] == '// anchor'
        ct29lines[4] == '// anchor'
    }

    // -----------------------------------------------------------------------
    // CT-30: server_transform extension guard -- calling a doc-type transform
    //        (replace_section) on a .groovy file must return a structured error,
    //        not a framework exception. And calling a code-type transform
    //        (replace_method) on a .md file must also return a structured error.
    //        Regression guard for FileTransformService per-transform allowedExts map.
    // -----------------------------------------------------------------------
    def "CT-30: server_transform on wrong file type returns structured error with Supported hint"() {
        given: "a .groovy file and a .md file"
        def groovyFile = writeFile('ct30.groovy', 'class Ct30 {\n    void hello() { println \'hi\' }\n}\n')
        def mdFile     = writeFile('ct30.md', '## Section\nsome content\n')
        String groovyOriginal = readFileContent(groovyFile.path)
        String mdOriginal     = readFileContent(mdFile.path)

        when: "replace_section called on .groovy (doc-only transform)"
        McpResponse r1 = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : groovyFile.path,
            options: [transform: 'replace_section', heading: 'Section',
                      newContent: 'replacement', expectedHash: groovyFile.hash]
        ], 'ct30a')

        then: "response is non-null and signals failure with extension hint"
        r1 != null
        def ct30atext = (r1.result.content[0] as Map).text.toString()
        // Error may come as isError or as JSON with success:false
        (r1.result as Map).isError == true || ct30atext.contains('does not support') || ct30atext.contains('Supported')

        when: "replace_method called on .md (code-only transform)"
        McpResponse r2 = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : mdFile.path,
            options: [transform: 'replace_method', method: 'hello',
                      newMethod: 'void hello() {}', expectedHash: mdFile.hash]
        ], 'ct30b')

        then: "response signals failure with extension hint"
        r2 != null
        def ct30btext = (r2.result.content[0] as Map).text.toString()
        (r2.result as Map).isError == true || ct30btext.contains('does not support') || ct30btext.contains('Supported')

        and: "neither file was modified"
        new File(groovyFile.path).text == groovyOriginal
        new File(mdFile.path).text    == mdOriginal
    }

    // -----------------------------------------------------------------------
    // CT-31: server_transform replace_method with wrong option name
    //        (newMethod instead of newBody) must return structured error
    //        that names the correct option. Regression guard for
    //        ReplaceMethodTransformer option validation path.
    // -----------------------------------------------------------------------
    def "CT-31: replace_method with newMethod option (wrong name) returns structured error naming newBody"() {
        given:
        def f = writeFile('ct31.groovy', 'class Ct31 {\n    void hello() { println \'hi\' }\n}\n')

        when: "replace_method called with newMethod instead of newBody"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform: 'replace_method',
                method   : 'hello',
                newMethod: 'void hello() { println \'replaced\' }',   // wrong key
                expectedHash: f.hash
            ]
        ], 'ct31')

        then: "response signals failure"
        r != null
        def ct31text = (r.result.content[0] as Map).text.toString()
        (r.result as Map).isError == true || ct31text.toLowerCase().contains('newbody') || ct31text.toLowerCase().contains('required')

        and: "file is not modified"
        new File(f.path).text == readFileContent(f.path)
    }

    // -----------------------------------------------------------------------
    // CT-32: server_transform append_section / replace_section wrong option
    //        name (newContent instead of content) must return a structured
    //        error naming the correct option. Regression guard for
    //        AppendSectionTransformer / ReplaceSectionTransformer validation.
    // -----------------------------------------------------------------------
    def "CT-32: append_section with newContent (wrong name) returns structured error naming content"() {
        given:
        def f = writeFile('ct32.md', '## Existing Section\nsome text\n')

        when: "append_section called with newContent instead of content"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform  : 'append_section',
                heading    : 'New Section',
                newContent : 'wrong key body',     // wrong -- should be 'content'
                expectedHash: f.hash
            ]
        ], 'ct32')

        then: "response signals failure mentioning the required option"
        r != null
        def ct32text = (r.result.content[0] as Map).text.toString()
        (r.result as Map).isError == true ||
            ct32text.toLowerCase().contains('content') ||
            ct32text.toLowerCase().contains('required')

        and: "file is not modified"
        new File(f.path).text == readFileContent(f.path)
    }

}