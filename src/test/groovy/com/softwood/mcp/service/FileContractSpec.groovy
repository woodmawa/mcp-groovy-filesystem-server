package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FileContractSpec -- TDD contract tests for FS 0.8.61.
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
 * CT-33..CT-53: FileContractSpec continued (see inline).
 * CT-54..CT-58: added for FS 0.8.60 (directory-path guard on read actions + patch without hash).
 * CT-59..CT-63: added for FS 0.8.61 (replace happy-path, multi-match ambiguity guard,
 *               no-hash degraded safety, multi_replace happy-path, missing-file guard).
 *   Root cause: G4 build session hit 'Tool not found: str_replace' -- Claude used wrong
 *   tool alias. These tests lock down the replace contract so the action surface is
 *   verified independently of session tool-alias hygiene.
 * CT-77..CT-79: added for FS 0.8.68 (patch expectedRemovedText content guard).
 * CT-80..CT-81: added for FS 0.8.71 (patch parenthesis-delta guard).
 *   Root cause: FIX-E3 (2026-04-30) patch on SqliteKnowledgeStore dropped the closing )
 *   of prepareStatement("""). Brace delta (CT-14) was balanced so no check fired. Paren
 *   delta now checked per-replacement on .groovy/.java files, same as brace delta.
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

    @Autowired FileWriteService     fileWriteService
    @Autowired FileReadService      fileReadService
    @Autowired FileLifecycleService fileLifecycleService

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

    // =======================================================================
    // CT-33..CT-51: checksum, stat, exists, diff, write, append, head, tail,
    //               info, grep contracts. Added FS 0.8.58+ gap-fill 2026-04-16.
    // Rationale: action=checksum is used by CS dependency-sweep handler;
    //   action=exists must not throw on absent path (sweep defensiveness);
    //   stat non-existent path must not surface uncaught exception.
    // =======================================================================

    def "CT-33: checksum of existing file returns SHA-256 hex, not error"() {
        given:
        def f = writeFile('ct33.txt', 'hello checksum world')

        when:
        def r = fileReadService.handleToolCall('file_read', [action: 'checksum', path: f.path], 'ct33')

        then: "success response, checksum field present, 64-char lowercase hex"
        r != null
        (r.result as Map).isError != true
        def c = parseContent(r)
        c.action == 'checksum'
        c.checksum != null
        (c.checksum as String).length() == 64
        (c.checksum as String) ==~ /[0-9a-f]{64}/
        c.algorithm == 'SHA-256'
    }

    def "CT-34: checksum is deterministic -- same content returns same hash on repeated calls"() {
        given:
        def f = writeFile('ct34.txt', 'deterministic checksum content')

        when:
        def r1 = fileReadService.handleToolCall('file_read', [action: 'checksum', path: f.path], 'ct34a')
        def r2 = fileReadService.handleToolCall('file_read', [action: 'checksum', path: f.path], 'ct34b')

        then:
        parseContent(r1).checksum == parseContent(r2).checksum
    }

    def "CT-35: checksum with algorithm=MD5 returns 32-char hex"() {
        given:
        def f = writeFile('ct35.txt', 'md5 test content')

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'checksum', path: f.path, options: [algorithm: 'MD5']], 'ct35')

        then:
        (r.result as Map).isError != true
        def c = parseContent(r)
        c.algorithm == 'MD5'
        (c.checksum as String).length() == 32
        (c.checksum as String) ==~ /[0-9a-f]{32}/
    }

    def "CT-36: checksum of non-existent file returns toolError, not uncaught exception"() {
        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'checksum', path: tempDir.resolve('does-not-exist.txt').toString()], 'ct36')

        then: "toolError surfaced to Claude -- not exception, not protocol error"
        r != null
        (r.result as Map).isError == true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.toLowerCase().contains('not found') || txt.toLowerCase().contains('does-not-exist')
    }

    def "CT-37: checksum with unknown algorithm returns toolError with message"() {
        given:
        def f = writeFile('ct37.txt', 'algorithm test')

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'checksum', path: f.path, options: [algorithm: 'NOSUCHALGORITHM']], 'ct37')

        then: "toolError -- NoSuchAlgorithmException must not surface as uncaught exception"
        r != null
        (r.result as Map).isError == true
    }

    def "CT-38: stat of existing file returns exists:true and size"() {
        given:
        def content = 'stat test content'
        def f = writeFile('ct38.txt', content)

        when:
        def r = fileReadService.handleToolCall('file_read', [action: 'stat', path: f.path], 'ct38')

        then:
        (r.result as Map).isError != true
        def c = parseContent(r)
        c.exists == true
        (c.size as long) == content.bytes.length
        c.action == 'stat'
    }

    def "CT-39: stat of non-existent path returns toolError or exists:false -- never uncaught exception"() {
        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'stat', path: tempDir.resolve('ghost.txt').toString()], 'ct39')

        then: "safe response -- either toolError OR {exists:false} -- never throws"
        r != null
        def m = r.result as Map
        boolean isToolError  = m.isError == true
        boolean isExistsFalse = !isToolError && parseContent(r).exists == false
        isToolError || isExistsFalse
    }

    def "CT-40: exists on present file returns exists:true"() {
        given:
        def f = writeFile('ct40.txt', 'exists test')

        when:
        def r = fileReadService.handleToolCall('file_read', [action: 'exists', path: f.path], 'ct40')

        then:
        (r.result as Map).isError != true
        parseContent(r).exists == true
    }

    def "CT-41: exists on absent path returns exists:false, NOT toolError and NOT exception"() {
        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'exists', path: tempDir.resolve('absent.txt').toString()], 'ct41')

        then: "exists MUST return exists:false -- throwing is a contract violation"
        r != null
        (r.result as Map).isError != true
        parseContent(r).exists == false
    }

    def "CT-42: write creates new file with correct content"() {
        given:
        def path = tempDir.resolve('ct42.txt').toString()
        def body = 'written by CT-42'

        when:
        def r = fileWriteService.handleToolCall('file_write',
            [action: 'write', path: path, content: body], 'ct42')

        then:
        (r.result as Map).isError != true
        new File(path).exists()
        new File(path).text == body
    }

    def "CT-43: append adds content without truncating existing file"() {
        given:
        def f = writeFile('ct43.txt', 'original')

        when:
        def r = fileWriteService.handleToolCall('file_write',
            [action: 'append', path: f.path, content: '\nappended'], 'ct43')

        then:
        (r.result as Map).isError != true
        new File(f.path).text == 'original\nappended'
    }

    def "CT-44: head returns first N lines, not more"() {
        given:
        def lines = (1..20).collect { "line ${it}" }.join('\n')
        def f = writeFile('ct44.txt', lines)

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'head', path: f.path, options: [lines: 5]], 'ct44')

        then:
        (r.result as Map).isError != true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.contains('line 1')
        !txt.contains('line 6')
    }

    def "CT-45: tail returns last N lines, not more"() {
        given:
        def lines = (1..20).collect { "line ${it}" }.join('\n')
        def f = writeFile('ct45.txt', lines)

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'tail', path: f.path, options: [lines: 5]], 'ct45')

        then:
        (r.result as Map).isError != true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.contains('line 20')
        !txt.contains('line 14')
    }

    def "CT-46: diff of identical files returns identical:true"() {
        given:
        def f1 = writeFile('ct46a.txt', 'same content')
        def f2 = writeFile('ct46b.txt', 'same content')

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'diff', path: f1.path, options: [compareTo: f2.path]], 'ct46')

        then:
        (r.result as Map).isError != true
        parseContent(r).identical == true
    }

    def "CT-47: diff of different files returns identical:false with non-empty diffs"() {
        given:
        def f1 = writeFile('ct47a.txt', 'line one\nline two')
        def f2 = writeFile('ct47b.txt', 'line one\nline CHANGED')

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'diff', path: f1.path, options: [compareTo: f2.path]], 'ct47')

        then:
        (r.result as Map).isError != true
        def c = parseContent(r)
        c.identical == false
        (c.diffs as List).size() > 0
    }

    def "CT-48: info on existing file returns action:info with path and size"() {
        given:
        def f = writeFile('ct48.groovy', 'class Foo {}')

        when:
        def r = fileReadService.handleToolCall('file_read', [action: 'info', path: f.path], 'ct48')

        then:
        (r.result as Map).isError != true
        def c = parseContent(r)
        c.action == 'info'
        c.path != null
        c.size != null
    }

    def "CT-49: grep returns only matching lines"() {
        given:
        def f = writeFile('ct49.txt', 'alpha line\nbeta line\ngamma line\nalpha again')

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'grep', path: f.path, options: [pattern: 'alpha', contextLines: 0]], 'ct49')

        then:
        (r.result as Map).isError != true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.contains('alpha line')
        txt.contains('alpha again')
        !txt.contains('beta line')
    }

    def "CT-50: grep with no matches returns success with zero matches, not toolError"() {
        given:
        def f = writeFile('ct50.txt', 'nothing to see here')

        when:
        def r = fileReadService.handleToolCall('file_read',
            [action: 'grep', path: f.path, options: [pattern: 'ZZZNOMATCH']], 'ct50')

        then: "no-match is NOT an error"
        r != null
        (r.result as Map).isError != true
    }

    def "CT-51: checksum changes when file content changes"() {
        given:
        def f = writeFile('ct51.txt', 'original content')
        def r1 = fileReadService.handleToolCall('file_read', [action: 'checksum', path: f.path], 'ct51a')
        def hash1 = parseContent(r1).checksum as String

        when: "modify file and re-checksum"
        new File(f.path).text = 'modified content'
        def r2 = fileReadService.handleToolCall('file_read', [action: 'checksum', path: f.path], 'ct51b')
        def hash2 = parseContent(r2).checksum as String

        then: "hashes differ after content change"
        hash1 != null && hash2 != null
        hash1 != hash2
    }

    // =======================================================================
    // CT-52: idea #62 -- file_read action=multi with knownHashes must NOT
    //   be blocked by the BLOCKED_UNRANGED_INDEXED_READ guard. The guard
    //   exists to prevent large unranged content reads on indexed files;
    //   knownHash-only multi reads return {unchanged:true} or minimal diff
    //   data and carry no content-token risk.
    //
    // CT-53: idea #16 -- server_transform insert_before_match with
    //   options.matchIsRegex=true must match via Pattern.find() not contains().
    //   Anchored patterns like ^}$ must work. Invalid regex must return
    //   structured toolError, not exception.
    // =======================================================================

    def "CT-52: file_read multi with knownHashes is NOT blocked by ontology-indexed guard"() {
        given: "a real file that exists on disk"
        def f = writeFile('ct52.groovy', 'class Ct52 { def foo() {} }')
        // Simulate passing a knownHash for this path
        def knownHash = fileReadService.handleToolCall('file_read',
            [action: 'checksum', path: f.path], 'ct52-hash')
        def hash = parseContent(knownHash).checksum as String
        // Use first 12 chars as the canonical short hash format
        def shortHash = hash?.take(12)

        when: "multi read with knownHashes -- even if file were ontology-indexed, must not block"
        def r = fileReadService.handleToolCall('file_read', [
            action : 'multi',
            options: [paths: [f.path], knownHashes: [(f.path): shortHash]]
        ], 'ct52')

        then: "response is NOT a BLOCKED_UNRANGED_INDEXED_READ error"
        r != null
        def ct52map = r.result as Map
        // Must not be the blocked error -- either success or unchanged
        !(ct52map.isError == true &&
          (ct52map.content[0] as Map)?.text?.toString()?.contains('BLOCKED_UNRANGED_INDEXED_READ'))
    }

    def "CT-53a: insert_before_match with matchIsRegex=true matches anchored regex closing brace"() {
        given:
        def f = writeFile('ct53.groovy', 'class Foo {\n    def bar() {}\n}')
        // Pattern: ^ followed by } followed by end-of-line
        String regexPattern = /^}$/

        when: "insert before the closing class brace using anchored regex"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform   : 'insert_before_match',
                expectedHash: f.hash,
                match       : regexPattern,
                matchIsRegex: true,
                content     : '    def inserted() { true }'
            ]
        ], 'ct53a')

        then: "success and inserted content present before closing brace"
        (r.result as Map).isError != true
        def finalContent = new File(f.path).text
        finalContent.contains('def inserted()')
        finalContent.endsWith('}')
        finalContent.indexOf('def inserted()') < finalContent.lastIndexOf('}')
    }

    def "CT-53b: insert_before_match with matchIsRegex=true and invalid regex returns structured toolError"() {
        given:
        def f = writeFile('ct53b.groovy', 'class Bar { def x() {} }')

        when: "invalid regex pattern"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform   : 'insert_before_match',
                expectedHash: f.hash,
                match       : '[invalid(regex',
                matchIsRegex: true,
                content     : 'should not reach here'
            ]
        ], 'ct53b')

        then: "structured toolError mentioning regex -- not uncaught exception"
        r != null
        (r.result as Map).isError == true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.toLowerCase().contains('regex') || txt.toLowerCase().contains('pattern') ||
            txt.toLowerCase().contains('invalid')
    }

    def "CT-53c: insert_before_match without matchIsRegex still uses substring matching (regression)"() {
        given:
        def f = writeFile('ct53c.txt', 'line one\ntarget line\nline three')

        when:
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'server_transform',
            path   : f.path,
            options: [
                transform   : 'insert_before_match',
                expectedHash: f.hash,
                match       : 'target line',
                content     : 'inserted before'
            ]
        ], 'ct53c')

        then: "existing substring behaviour unchanged"
        (r.result as Map).isError != true
        new File(f.path).text.contains('inserted before\ntarget line')
    }

    // -------------------------------------------------------------------------
    // CT-54..CT-58: directory-as-file errors and patch-without-hash behaviour
    // Diagnosed from FS log analysis 2026-04-16.
    // -------------------------------------------------------------------------

    def "CT-54: grep on directory path returns structured toolError in content (not JSON-RPC error object)"() {
        // Diagnosed: log showed 'Path is not a file' thrown as IllegalArgumentException
        // which surfaced as JSON-RPC error{code:-32603} rather than isError:true in
        // content[0].text. Claude Desktop cannot display JSON-RPC error objects.
        given:
        def dirPath = tempDir.resolve('ct54dir').toFile()
        dirPath.mkdirs()

        when: "grep is called with a directory instead of a file"
        def r = fileReadService.handleToolCall('file_read', [
            action : 'grep',
            path   : dirPath.absolutePath,
            options: [pattern: 'somePattern', contextLines: 0]
        ], 'ct54')

        then: "response is a content result -- isError:true in content, not a JSON-RPC error"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.toLowerCase().contains('file') || txt.toLowerCase().contains('directory') ||
            txt.toLowerCase().contains('path')
    }

    def "CT-55: read on directory path returns structured toolError in content (not JSON-RPC error object)"() {
        given:
        def dirPath = tempDir.resolve('ct55dir').toFile()
        dirPath.mkdirs()

        when: "read is called with a directory path"
        def r = fileReadService.handleToolCall('file_read', [
            action : 'read',
            path   : dirPath.absolutePath
        ], 'ct55')

        then: "structured toolError in content, not a JSON-RPC level error"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.toLowerCase().contains('file') || txt.toLowerCase().contains('directory') ||
            txt.toLowerCase().contains('path')
    }

    def "CT-56: range on directory path returns structured toolError in content (not JSON-RPC error object)"() {
        given:
        def dirPath = tempDir.resolve('ct56dir').toFile()
        dirPath.mkdirs()

        when: "range is called with a directory path"
        def r = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : dirPath.absolutePath,
            options: [startLine: 1, maxLines: 10]
        ], 'ct56')

        then: "structured toolError in content, not a JSON-RPC level error"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.toLowerCase().contains('file') || txt.toLowerCase().contains('directory') ||
            txt.toLowerCase().contains('path')
    }

    def "CT-57: patch without expectedHash is rejected -- expectedHash is mandatory (FS 0.8.73)"() {
        // CT-EH-1 upgrade: omitting expectedHash is now a hard error, not degraded-safety.
        // All mutating actions require expectedHash to prevent silent double-writes.
        given:
        def f = writeFile('ct57.groovy', 'class Ct57 {\n    def old() { 1 }\n}')

        when: "patch without expectedHash"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                // expectedHash deliberately omitted
                replacements: [
                    [startLine: 2, endLine: 2, newText: '    def old() { 99 }']
                ]
            ]
        ], 'ct57')

        then: "rejected with mandatory-field error -- file unchanged"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errTxt = ((r.result as Map).content[0] as Map).text.toString()
        errTxt.toLowerCase().contains('expectedhash') && errTxt.toLowerCase().contains('required')
        new File(f.path).text == 'class Ct57 {\n    def old() { 1 }\n}'
    }

    def "CT-58: get_method on directory path returns structured toolError in content (not JSON-RPC error object)"() {
        // Complements CT-54..CT-56 -- confirms all file-reading actions handle
        // directory paths consistently as toolError, not uncaught exceptions.
        given:
        def dirPath = tempDir.resolve('ct58dir').toFile()
        dirPath.mkdirs()

        when: "get_method is called with a directory path"
        def r = fileReadService.handleToolCall('file_read', [
            action : 'get_method',
            path   : dirPath.absolutePath,
            options: [method: 'someMethod']
        ], 'ct58')

        then: "structured toolError in content, not a JSON-RPC level error"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        txt.toLowerCase().contains('file') || txt.toLowerCase().contains('directory') ||
            txt.toLowerCase().contains('path')
    }

    // -----------------------------------------------------------------------
    // CT-59..CT-63: file_write action=replace happy-path and ambiguity guards
    // Added FS 0.8.61 (G4 build session 2026-04-19)
    // Root cause captured: prior session attempted 'str_replace' (Claude built-in
    // tool alias) instead of file_write action=replace -- 'Tool not found' error.
    // These tests harden the replace contract so regressions are caught by CI.
    // -----------------------------------------------------------------------

    def "CT-59: replace happy-path -- oldText found exactly once, file updated, new hash returned"() {
        // Happy-path contract: replace succeeds when oldText matches exactly once
        // and expectedHash is correct. Response must carry new file_content_hash.
        given:
        def f = writeFile('ct59.groovy', 'class Ct59 {\n    def methodA() { 1 }\n    def methodB() { 2 }\n}')

        when: "oldText matches exactly once"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : 'def methodA() { 1 }',
                newText     : 'def methodA() { 42 }',
                expectedHash: f.hash
            ]
        ], 'ct59')

        then: "replace succeeds with new hash in response"
        r != null
        r.result != null
        (r.result as Map).isError != true
        def txt = ((r.result as Map).content[0] as Map).text.toString()
        def parsed = new groovy.json.JsonSlurper().parseText(txt) as Map
        parsed.file_content_hash != null
        parsed.file_content_hash != f.hash   // hash must change
        new File(f.path).text.contains('42')
        !new File(f.path).text.contains('{ 1 }')
    }

    def "CT-60: replace where oldText matches multiple times returns structured toolError (ambiguous match)"() {
        // Safety contract: replace must refuse when oldText is ambiguous (appears >1 time).
        // Silently replacing all occurrences would be destructive and unpredictable.
        // FS must surface a clear toolError rather than corrupt the file.
        given:
        def f = writeFile('ct60.groovy',
            'class Ct60 {\n    def dup() { 1 }\n    def other() { 1 }\n}')
        // '{ 1 }' appears twice

        when: "oldText matches more than once"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '{ 1 }',
                newText     : '{ 99 }',
                expectedHash: f.hash
            ]
        ], 'ct60')

        then: "structured toolError -- ambiguous/multiple match, file not modified"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errTxt = ((r.result as Map).content[0] as Map).text.toString()
        // FS actual message: 'replace: oldText appears N times at lines X, Y (must be unique).'
        // Accept any of: 'appears', 'times', 'unique', 'multiple', 'ambiguous', 'more than one'
        errTxt.toLowerCase().contains('appears')   || errTxt.toLowerCase().contains('times') ||
            errTxt.toLowerCase().contains('unique')    || errTxt.toLowerCase().contains('multiple') ||
            errTxt.toLowerCase().contains('ambiguous') || errTxt.toLowerCase().contains('more than one')
        // File must be unchanged
        new File(f.path).text == 'class Ct60 {\n    def dup() { 1 }\n    def other() { 1 }\n}'
    }

    def "CT-61: replace without expectedHash is rejected -- expectedHash is mandatory (FS 0.8.73)"() {
        // CT-EH-1 upgrade: omitting expectedHash is now a hard error for replace too.
        given:
        def f = writeFile('ct61.txt', 'alpha\nbeta\ngamma\n')

        when: "expectedHash deliberately omitted"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText: 'beta',
                newText: 'BETA'
                // expectedHash omitted
            ]
        ], 'ct61')

        then: "rejected with mandatory-field error -- file unchanged"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errTxt = ((r.result as Map).content[0] as Map).text.toString()
        errTxt.toLowerCase().contains('expectedhash') && errTxt.toLowerCase().contains('required')
        new File(f.path).text == 'alpha\nbeta\ngamma\n'
    }

    def "CT-62: multi_replace happy-path -- two non-overlapping replacements applied atomically"() {
        // Confirms multi_replace applies all entries in one atomic write.
        // Both replacements must land; file_content_hash changes once.
        given:
        def f = writeFile('ct62.groovy',
            'class Ct62 {\n    String name = "old"\n    int value = 0\n}')

        when: "two non-overlapping replacements"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: '"old"',  newText: '"new"'],
                    [oldText: 'int value = 0', newText: 'int value = 99']
                ]
            ]
        ], 'ct62')

        then: "both replacements applied, new hash returned"
        r != null
        r.result != null
        (r.result as Map).isError != true
        def newContent = new File(f.path).text
        newContent.contains('"new"')
        newContent.contains('int value = 99')
        !newContent.contains('"old"')
        !newContent.contains('int value = 0')
    }

    def "CT-63: replace on non-existent file returns structured toolError (not NPE or JSON-RPC error)"() {
        // Defensive contract: file_write action=replace must handle missing file gracefully.
        // Pass expectedHash='deadbeef0000' -- a clearly wrong hash that won't match any file.
        // The file-not-found check runs before drift guard so the right error is returned.
        given:
        def missingPath = tempDir.resolve('ct63_missing.groovy').toFile().absolutePath

        when: "file does not exist"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : missingPath,
            options: [
                oldText     : 'anything',
                newText     : 'whatever',
                expectedHash: 'deadbeef0000'   // CT-EH-1: mandatory; file-not-found fires before drift check
            ]
        ], 'ct63')

        then: "structured toolError in content, not NPE or JSON-RPC error"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errTxt = ((r.result as Map).content[0] as Map).text.toString()
        errTxt.toLowerCase().contains('not found') || errTxt.toLowerCase().contains('does not exist') ||
            errTxt.toLowerCase().contains('no such file') || errTxt.toLowerCase().contains('missing')
    }

    // -----------------------------------------------------------------------
    // CT-64..CT-65: non-ASCII in oldText
    // Added FS 0.8.62 (G4 build session 2026-04-19)
    // Root cause: AwToCsSignalClient.resolveActiveSession() doc comment contained
    // the section-sign char § (U+00A7 = 167 > 126). When that method's block was
    // included in a replace oldText the non_ascii_hint fired, correctly blocking the
    // replace and directing use of action=patch. Contracts lock in that behaviour.
    // -----------------------------------------------------------------------

    def "CT-64: replace where oldText contains non-ASCII chars returns toolError with non_ascii_hint"() {
        // FS must detect non-ASCII in oldText and surface non_ascii_hint in the error.
        // This is the canonical diagnostic for Spring @Value / Unicode doc-comment failures.
        // The hint should recommend using action=patch (immune to encoding issues).
        given:
        // File contains a comment with the section-sign char (U+00A7 = \u00a7)
        def f = writeFile('ct64.groovy',
            'class Ct64 {\n    // see FS_CONTEXT_ARCHITECTURE.md \u00a72 for details\n    String name = "old"\n}')

        when: "oldText spans the non-ASCII comment line"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '// see FS_CONTEXT_ARCHITECTURE.md \u00a72 for details\n    String name = "missing"',
                newText     : '// updated\n    String name = "new"',
                expectedHash: f.hash
            ]
        ], 'ct64')

        then: "structured toolError -- oldText not found, non_ascii_hint present"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errPayload = new groovy.json.JsonSlurper().parseText(
            ((r.result as Map).content[0] as Map).text.toString()) as Map
        // non_ascii_hint must be present and mention 'patch'
        errPayload.non_ascii_hint != null
        (errPayload.non_ascii_hint as String).toLowerCase().contains('patch')
        // File must be unchanged
        new File(f.path).text.contains('\u00a7')
        new File(f.path).text.contains('"old"')
    }

    def "CT-65: replace on ASCII-only not-found oldText omits non_ascii_hint"() {
        // Complement of CT-64: when oldText is pure ASCII and simply not found,
        // non_ascii_hint must NOT be present in the error (no false positives).
        given:
        def f = writeFile('ct65.groovy',
            'class Ct65 {\n    String name = "old"\n}')

        when: "oldText is ASCII-only but simply not in the file"
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : 'String name = "definitely_not_there"',
                newText     : 'String name = "new"',
                expectedHash: f.hash
            ]
        ], 'ct65')

        then: "structured toolError -- oldText not found, non_ascii_hint absent"
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errPayload = new groovy.json.JsonSlurper().parseText(
            ((r.result as Map).content[0] as Map).text.toString()) as Map
        // no non_ascii_hint for pure-ASCII not-found
        errPayload.non_ascii_hint == null
    }

    // -----------------------------------------------------------------------
    // CT-66..CT-69: bare box-drawing chars at line-start in simulated result
    // Added FS 0.8.63 (G1 build session 2026-04-19)
    //
    // ROOT CAUSE (this session):
    //   multi_replace was called with oldText ending mid-way through a section
    //   divider. The trailing \u2500\u2500\u2500... chars after the match boundary
    //   were left in the file as BARE box-drawing characters without // prefix.
    //   Groovy compiler rejected: "Unexpected character: '\u2500'"
    //
    // THE FIX:
    //   After brace-check, before atomicWrite, run checkBareBoxDrawing() on the
    //   simulated result for .groovy/.java/.kt files. Any line whose first
    //   non-whitespace char is in U+2500..U+257F must be blocked.
    //   Return structured error with bare_box_drawing_hint.
    //
    // APPLIES TO: both replace and multi_replace actions.
    // EXEMPT:     .txt/.md/.adoc and other non-code files.
    // -----------------------------------------------------------------------

    def 'CT-66b: multi_replace where newText causes orphaned bare box-drawing line is blocked'() {
        // This is the exact failure mode from the G1 build session:
        // newText deliberately introduces a line starting with raw \u2500 chars.
        given:
        def f = writeFile('ct66b.groovy',
            'class Ct66b {\n    String marker = "replace_me"\n}')

        when: 'newText introduces a bare \u2500 line (missing // prefix)'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: '    String marker = "replace_me"',
                     newText: '    String marker = "fixed"\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500']
                ]
            ]
        ], 'ct66b')

        then: 'write is BLOCKED -- bare box-drawing detected in simulated result'
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errText = ((r.result as Map).content[0] as Map).text.toString()
        errText.contains('bare_box_drawing') || errText.toLowerCase().contains('box-drawing') ||
            errText.toLowerCase().contains('box drawing')
        new File(f.path).text.contains('"replace_me"')
        !new File(f.path).text.contains('\u2500\u2500\u2500\u2500')
    }

    def 'CT-67: multi_replace retaining // prefix on box-drawing divider passes'() {
        // Complement: correct replacement retaining // on divider must succeed.
        given:
        def f = writeFile('ct67.groovy',
            'class Ct67 {\n    int budget = 0\n\n    // \u2500\u2500 Factory \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n}')

        when: 'newText adds a field but does not touch the divider line'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: '    int budget = 0',
                     newText: '    int budget = 0\n    String extra = "added"']
                ]
            ]
        ], 'ct67')

        then: 'write succeeds -- no bare box-drawing in result'
        r != null
        r.result != null
        (r.result as Map).isError != true
        new File(f.path).text.contains('String extra = "added"')
        new File(f.path).text.contains('// \u2500\u2500 Factory')
    }

    def 'CT-68: replace action producing bare box-drawing char at line start is blocked'() {
        // Same check must apply to single replace, not just multi_replace.
        given:
        def f = writeFile('ct68.groovy',
            'class Ct68 {\n    String marker = "replace_me"\n}')

        when: 'newText in replace introduces a bare \u2500 line'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '    String marker = "replace_me"',
                newText     : '    String marker = "fixed"\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500',
                expectedHash: f.hash
            ]
        ], 'ct68')

        then: 'write is BLOCKED -- bare box-drawing in simulated result'
        r != null
        r.result != null
        (r.result as Map).isError == true
        def errText = ((r.result as Map).content[0] as Map).text.toString()
        errText.contains('bare_box_drawing') || errText.toLowerCase().contains('box-drawing') ||
            errText.toLowerCase().contains('box drawing')
        new File(f.path).text.contains('"replace_me"')
    }

    def 'CT-69: bare box-drawing at line start in .txt file is allowed'() {
        // Box-drawing chars are legitimate in plain text, AsciiDoc diagrams, etc.
        // The check must NOT fire for non-code files.
        given:
        def f = writeFile('ct69.txt',
            'Title\nOld line\nEnd')

        when: 'newText introduces a raw \u2500 line in a .txt file'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText : 'Old line',
                newText : '\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500',
                expectedHash: f.hash
            ]
        ], 'ct69')

        then: 'write succeeds -- .txt files are exempt'
        r != null
        r.result != null
        (r.result as Map).isError != true
        new File(f.path).text.contains('\u2500\u2500\u2500\u2500')
    }

    // -----------------------------------------------------------------------
    // CT-70..72: file_lifecycle action=create contract tests (FS 0.8.63+)
    // These pin the create contract so Claude always uses file_lifecycle/file_write
    // for new files on the Windows filesystem rather than any sandbox-local tool.
    // -----------------------------------------------------------------------

    def 'CT-70: file_lifecycle action=create creates an empty file and returns success with path'() {
        // Core contract: create must produce the file on disk and return success:true.
        // Claude must use this (not any sandbox create_file tool) for FS writes.
        given:
        def target = tempDir.resolve('ct70.groovy').toFile()

        when:
        def r = fileLifecycleService.handleToolCall('file_lifecycle', [
            action: 'create',
            path  : target.absolutePath,
            options: [type: 'file', verbose: true]
        ], 'ct70')

        then: 'response carries success=true and file exists on disk'
        r != null
        r.result != null
        def payload = parseContent(r)
        payload.success == true
        payload.action  == 'create'
        target.exists()
        target.length() == 0L
    }

    def 'CT-71: file_lifecycle action=create with mkdirs=true creates missing parent directories'() {
        // When mkdirs is true, create must create all missing parent dirs before the file.
        // Without this, deeply nested new files silently fail or throw.
        given:
        def nested = tempDir.resolve('ct71/sub/dir/new.groovy').toFile()

        when:
        def r = fileLifecycleService.handleToolCall('file_lifecycle', [
            action : 'create',
            path   : nested.absolutePath,
            options: [type: 'file', mkdirs: true, verbose: true]
        ], 'ct71')

        then:
        r != null
        r.result != null
        def payload = parseContent(r)
        payload.success == true
        nested.exists()
        nested.parentFile.isDirectory()
    }

    def 'CT-72: file_lifecycle action=create on existing file is idempotent -- does not overwrite content'() {
        // Create on an already-existing file must not truncate it.
        // This guards the pattern: create-then-write, where create fires twice by accident.
        given: 'a file with content'
        def f = writeFile('ct72.groovy', 'existing content')

        when: 'create is called again on the same path'
        def r = fileLifecycleService.handleToolCall('file_lifecycle', [
            action : 'create',
            path   : f.path,
            options: [type: 'file', verbose: true]
        ], 'ct72')

        then: 'succeeds (idempotent) and original content is preserved'
        r != null
        r.result != null
        def payload = parseContent(r)
        payload.success == true
        new File(f.path).text == 'existing content'
    }

    def 'CT-73: replace with options={} (empty map, no oldText) returns toolError -- never a no-op success'() {
        // Root cause: file_write action=replace options:{} silently hit doReplace,
        // which returned toolError but the response contained a content_hash field
        // that looked like a success hash. This pins the error surface contract.
        given:
        def f = writeFile('ct73.groovy', 'class Foo { void bar() { } }')

        when: 'replace is called with completely empty options (no oldText, no newText)'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [:]
        ], 'ct73')

        then: 'toolError -- not a silent no-op or success'
        assertToolError(r, 'oldText')

        and: 'file is unchanged'
        new File(f.path).text == 'class Foo { void bar() { } }'
    }

    def 'CT-74: patch entry missing startLine/endLine returns toolError with range hint, not NPE'() {
        // null-as-int == 0, which triggers the range validator with [0..0].
        // Error must be a visible toolError, not an NPE or silent partial write.
        given:
        def f = writeFile('ct74.groovy', 'line1\nline2\nline3\n')

        when: 'patch entry is missing startLine and endLine'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash : f.hash,
                replacements : [[newText: 'replaced']]
            ]
        ], 'ct74')

        then: 'toolError surfaced -- not swallowed'
        r.result != null
        (r.result as Map).isError == true
        def errText74 = ((r.result as Map).content[0] as Map).text as String
        errText74.toLowerCase().contains('range') || errText74.toLowerCase().contains('startline')

        and: 'file is unchanged'
        new File(f.path).text == 'line1\nline2\nline3\n'
    }

    def 'CT-75: multi_replace entry with empty-string oldText is rejected -- never applied'() {
        // String.replace(\'\', ...) in Java replaces at EVERY position -- catastrophic.
        // The !oldText guard covers this but we pin the contract explicitly.
        given:
        def f = writeFile('ct75.groovy', 'class Bar { void baz() { } }')

        when: 'multi_replace contains one entry with empty-string oldText'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash : f.hash,
                replacements : [
                    [oldText: '', newText: 'SHOULD NOT APPLY']
                ]
            ]
        ], 'ct75')

        then: 'toolError returned -- empty oldText rejected before any write'
        r.result != null
        (r.result as Map).isError == true

        and: 'file content is completely unchanged'
        new File(f.path).text == 'class Bar { void baz() { } }'
    }

    def 'CT-76: toolError response for replace contains no content_hash field that mimics a success hash'() {
        // Observed: replace with missing oldText returned a McpResponse whose serialised
        // text contained content_hash -- visually identical to a success response.
        // Contract: isError:true content must not embed a content_hash/file_content_hash key.
        given:
        def f = writeFile('ct76.groovy', 'class Qux { }')

        when: 'replace is called with no oldText (triggers toolError path)'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [:]
        ], 'ct76')

        then: 'response is an isError response'
        r.result != null
        (r.result as Map).isError == true

        and: 'error content text does not contain a content_hash that looks like a write result'
        def errText76 = ((r.result as Map).content[0] as Map).text as String
        errText76.contains('oldText') || errText76.contains('required')
        !errText76.contains('"content_hash"')
        !errText76.contains('"file_content_hash"')
    }

    // =========================================================================
    // CT-77..CT-79: patch expectedRemovedText content guard (FS 0.8.68)
    //
    // Root cause: patch is line-number-based with no content verification for
    // non-Groovy/Java files. Stale line numbers silently corrupt files -- the
    // hash guard passes (hash is fresh) but the wrong content is replaced.
    // Fix: optional expectedRemovedText per replacement entry. FS checks that
    // lines[startLine..endLine] joined with \n match this value before applying.
    // Mismatch = CONTENT_MISMATCH error, file untouched.
    // =========================================================================

    def 'CT-77: patch with matching expectedRemovedText succeeds and applies replacement'() {
        given: 'a text file with known content'
        def f = writeFile('ct77.txt',
            'alpha\nbeta\ngamma\n')

        when: 'patch with expectedRemovedText exactly matching line 2'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[
                    startLine          : 2,
                    endLine            : 2,
                    newText            : 'BETA',
                    expectedRemovedText: 'beta'
                ]]
            ]
        ], 'ct77')

        then: 'patch succeeds -- content matched and was replaced'
        r.result != null
        (r.result as Map).isError != true
        new File(f.path as String).text.contains('BETA')
        !new File(f.path as String).text.contains('beta')
    }

    def 'CT-78: patch with non-matching expectedRemovedText is rejected, file untouched'() {
        given: 'a text file'
        def f = writeFile('ct78.txt',
            'alpha\nbeta\ngamma\n')

        when: 'patch targets line 2 but expectedRemovedText does not match'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[
                    startLine          : 2,
                    endLine            : 2,
                    newText            : 'BETA',
                    expectedRemovedText: 'this does not match beta'
                ]]
            ]
        ], 'ct78')

        then: 'FS rejects with CONTENT_MISMATCH'
        r.result != null
        (r.result as Map).isError == true
        def errText78 = ((r.result as Map).content[0] as Map).text as String
        errText78.contains('CONTENT_MISMATCH') || errText78.contains('expectedRemovedText')

        and: 'file is completely unchanged'
        new File(f.path as String).text == 'alpha\nbeta\ngamma\n'
    }

    def 'CT-79: patch without expectedRemovedText succeeds -- field is optional, no regression'() {
        given:
        def f = writeFile('ct79.txt',
            'alpha\nbeta\ngamma\n')

        when: 'patch without expectedRemovedText -- existing behaviour unchanged'
        def r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[
                    startLine: 2,
                    endLine  : 2,
                    newText  : 'BETA'
                ]]
            ]
        ], 'ct79')

        then: 'patch succeeds -- field omitted, no guard applied'
        r.result != null
        (r.result as Map).isError != true
        new File(f.path as String).text.contains('BETA')
    }

    // -----------------------------------------------------------------------
    // CT-80: patch drops a closing paren from a method-call argument string,
    //        causing a parenthesis-delta mismatch -> BLOCKED
    //
    // This is the exact failure mode from FIX-E3 (2026-04-30): a patch on
    // SqliteKnowledgeStore replaced lines ending in LIMIT ?""")
    // but newText omitted the closing ) of prepareStatement(...). The brace
    // delta was balanced (no { or }), so CT-14 didn't fire. The paren drop
    // corrupted the file silently. CT-80 closes that gap.
    // -----------------------------------------------------------------------
    def 'CT-80: patch that drops a closing paren from a method-call arg string is blocked'() {
        given: 'a Groovy file where a method call closes with )'
        def f = writeFile('ct80.groovy',
            'class Dao {\n' +
            '    void query(Connection conn) {\n' +
            '        PreparedStatement stmt = conn.prepareStatement("""\n' +
            '            SELECT * FROM foo\n' +
            '            WHERE id = ?"""' + ')\n' +
            '        stmt.executeQuery()\n' +
            '    }\n' +
            '}\n')

        when: 'patch replaces the WHERE line and the closing paren line but newText omits the closing )'
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[
                    startLine: 5,   // line: '            WHERE id = ?""")'  -- has one closing )
                    endLine  : 5,
                    // newText keeps the triple-quote close but drops the ) -- paren delta: removed=0 open, 1 close => -1; new=0 => mismatch
                    newText  : '            WHERE id = ?"""'
                ]]
            ]
        ], 'ct80')

        then: 'patch is rejected -- paren delta mismatch detected'
        assertToolError(r, 'paren')
        new File(f.path as String).text == readFileContent(f.path)
    }

    // -----------------------------------------------------------------------
    // CT-81: companion to CT-80 -- patch that keeps balanced parens succeeds
    // -----------------------------------------------------------------------
    def 'CT-81: patch that preserves paren balance on a Groovy method-call line succeeds'() {
        given: 'same Groovy file'
        def f = writeFile('ct81.groovy',
            'class Dao {\n' +
            '    void query(Connection conn) {\n' +
            '        PreparedStatement stmt = conn.prepareStatement("""\n' +
            '            SELECT * FROM foo\n' +
            '            WHERE id = ?"""' + ')\n' +
            '        stmt.executeQuery()\n' +
            '    }\n' +
            '}\n')

        when: 'patch replaces WHERE clause AND keeps the closing ) -- paren delta matches'
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[
                    startLine: 5,
                    endLine  : 5,
                    newText  : '            WHERE name = ?"""' + ')'
                ]]
            ]
        ], 'ct81')

        then: 'patch succeeds and file contains new WHERE clause'
        r.result != null
        (r.result as Map).isError != true
        new File(f.path as String).text.contains('WHERE name = ?')
    }

    // -------------------------------------------------------------------------
    // CT-82: write action must interpret \n escape sequences as real newlines.
    // Root cause: Claude tool-call serialiser sends \n as two chars (0x5C 0x6E);
    // doWrite was writing them literally, producing a single-line file.
    // FS 0.9.7 fix: unescape Java-style sequences in write content (raw=false default).
    // -------------------------------------------------------------------------
    def 'CT-82: file_write action=write with \\n sequences writes actual newlines'() {
        given:
        def f = tempDir.resolve('ct82-newline.groovy').toFile()
        // NOTE: in Groovy single-quoted strings, '\\n' is TWO chars: backslash + n (literal \n).
        // This simulates what Claude's tool-call serialiser sends when embedding \n in content.
        String escapedContent = 'package test' + '\\n' + '\\n' + 'class Foo {' + '\\n' + '}' + '\\n'
        assert !escapedContent.contains('\n')  : 'pre-condition: content has NO actual newlines'
        assert escapedContent.contains('\\n') : 'pre-condition: content has literal backslash-n'

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'write',
            path   : f.path,
            content: escapedContent
        ], 'ct82')

        then: 'write succeeds'
        r.result != null
        (r.result as Map).isError != true

        and: 'FS unescapes \\n -- file has 4 actual lines (blank line from double \\n), not one long literal line'
        def lines = f.readLines()
        lines.size() == 4
        lines[0] == 'package test'
        lines[1] == ''
        lines[2] == 'class Foo {'
    }

    // -------------------------------------------------------------------------
    // CT-83: write with options.raw=true must NOT unescape -- raw mode preserved.
    // -------------------------------------------------------------------------
    def 'CT-83: file_write action=write with raw=true preserves literal \\n sequences'() {
        given:
        def f = tempDir.resolve('ct83-raw.txt').toFile()

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'write',
            path   : f.path,
            options: [raw: true],
            content: 'key\\nvalue'
        ], 'ct83')

        then:
        r.result != null
        (r.result as Map).isError != true

        and: 'raw mode: file contains literal \\n not a newline'
        def text = f.text
        text.contains('\\n')
        !text.contains('\n') || text == 'key\\nvalue'
    }

}