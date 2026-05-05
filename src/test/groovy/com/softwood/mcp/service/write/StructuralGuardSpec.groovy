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
 * StructuralGuardSpec -- contract tests for PR 1.3.
 * Target version: FS 0.9.0
 *
 * Verifies StructuralGuard unit contracts and MCP surface behaviour.
 *
 *   CT-GUARD-1 : Brace delta mismatch on .groovy -> hard reject, file unchanged
 *   CT-GUARD-2 : Paren delta mismatch on .groovy -> hard reject, file unchanged
 *   CT-GUARD-3 : Bare-box-drawing in updated content -> hard reject
 *   CT-GUARD-4 : Non-code file (.md, .sql) with unbalanced braces -> no check fired, write succeeds
 *   CT-GUARD-5 : Braces inside string literals do not cause false mismatch (string-strip heuristic)
 *   CT-GUARD-6 : Parens inside SQL triple-quoted strings do not cause false mismatch
 *   CT-GUARD-7 : Real delimiter removal outside strings/comments still blocked
 *   CT-GUARD-8 : .kt and .kts source files guarded consistently
 *   (D5)       : No brace_warning field in any MCP response
 *
 * Spock rules (practice #407): @CompileDynamic, @SpringBootTest, TempDir.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class StructuralGuardSpec extends Specification {

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
    // Unit-level tests on StructuralGuard directly
    // -----------------------------------------------------------------------

    def "StructuralGuard.isCodeFile() correctly classifies extensions"() {
        expect:
        StructuralGuard.isCodeFile('Foo.groovy')
        StructuralGuard.isCodeFile('Bar.java')
        StructuralGuard.isCodeFile('Baz.kt')
        StructuralGuard.isCodeFile('Qux.kts')
        !StructuralGuard.isCodeFile('readme.md')
        !StructuralGuard.isCodeFile('schema.sql')
        !StructuralGuard.isCodeFile('config.yml')
        !StructuralGuard.isCodeFile(null)
    }

    def "StructuralGuard.checkBraceDelta() returns null for balanced replacement"() {
        expect:
        StructuralGuard.checkBraceDelta('if (x) {\n    old\n}', 'if (x) {\n    new\n}', 'Foo.groovy') == null
    }

    def "StructuralGuard.checkBraceDelta() returns error for unbalanced replacement"() {
        when:
        String err = StructuralGuard.checkBraceDelta('if (x) {\n    body', '    flat content', 'Foo.groovy')

        then:
        err != null
        err.toLowerCase().contains('brace')
    }

    def "StructuralGuard.checkBraceDelta() returns null for non-code file"() {
        expect:
        StructuralGuard.checkBraceDelta('unbalanced { { {', 'new', 'readme.md') == null
    }

    def "StructuralGuard string-strip heuristic: braces inside string literals suppressed"() {
        given: "removed content has a brace inside a string literal"
        String removed = 'def q = "SELECT * FROM t WHERE json @> \'{"key": "val"}\'"\n'
        String newText = 'def q = "SELECT * FROM t WHERE id = 1"\n'
        // raw brace counts differ but string content has them -- strip should suppress

        when:
        String err = StructuralGuard.checkBraceDelta(removed, newText, 'Service.groovy')

        then:
        err == null  // string-strip heuristic suppresses the false positive
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-1: Brace delta mismatch -> hard reject, file unchanged
    // -----------------------------------------------------------------------
    def "CT-GUARD-1: replace on .groovy with unbalanced brace in newText is rejected"() {
        given:
        def f = writeFile('ct-guard-1.groovy',
            'class Guard1 {\n    void foo() {\n        def x = 1\n    }\n}\n')

        when: "newText has one fewer closing brace than removed"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '    void foo() {\n        def x = 1\n    }\n',
                newText     : '    void foo() {\n        def x = 2\n',   // missing closing }
                expectedHash: f.hash
            ]
        ], 'test')

        then: "structural check fired -- file unchanged"
        assertToolError(r, 'brace')
        readContent(f.path).contains('def x = 1')
        !readContent(f.path).contains('def x = 2')
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-2: Paren delta mismatch -> hard reject, file unchanged
    // -----------------------------------------------------------------------
    def "CT-GUARD-2: replace on .groovy with dropped closing paren is rejected"() {
        given:
        def f = writeFile('ct-guard-2.groovy',
            'class Guard2 {\n    def r = service.execute(query)\n}\n')

        when: "newText drops the closing paren"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : 'service.execute(query)',
                newText     : 'service.execute(newQuery',   // missing )
                expectedHash: f.hash
            ]
        ], 'test')

        then: "paren guard fires"
        assertToolError(r, 'paren')
        readContent(f.path).contains('execute(query)')
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-3: Bare-box-drawing in updated content -> hard reject
    // -----------------------------------------------------------------------
    def "CT-GUARD-3: replace producing bare box-drawing line-start in .groovy is rejected"() {
        given:
        def f = writeFile('ct-guard-3.groovy',
            'class Guard3 {\n    // \u2500\u2500\u2500 section\n    def x = 1\n}\n')

        when: "newText introduces a bare box-drawing char at line start"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '    // \u2500\u2500\u2500 section',
                newText     : '    \u2500\u2500\u2500 section',  // bare -- no // prefix
                expectedHash: f.hash
            ]
        ], 'test')

        then: "bare-box-drawing guard fires"
        assertToolError(r, 'box')
        readContent(f.path).contains('// \u2500\u2500\u2500 section')
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-4: Non-code file -- no check fired, write succeeds
    // -----------------------------------------------------------------------
    def "CT-GUARD-4: replace on .md with unbalanced braces succeeds without guard"() {
        given:
        def f = writeFile('ct-guard-4.md', '# Doc\n\nSome { unclosed\n\nEnd\n')

        when: "replace that adds more unclosed braces -- no guard on .md"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : 'Some { unclosed',
                newText     : 'Some { { unclosed',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "succeeds -- no guard on non-code files"
        r.result != null
        r.result.isError == null || r.result.isError == false
        readContent(f.path).contains('Some { { unclosed')
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-5: Braces inside string literals -- no false mismatch
    // -----------------------------------------------------------------------
    def "CT-GUARD-5: replace changing SQL string with unbalanced braces does not false-trigger guard"() {
        given:
        def f = writeFile('ct-guard-5.groovy',
            'class Guard5 {\n    String q = """SELECT * FROM t WHERE json @> \'{"a":1}\' """\n}\n')

        when: "replace the SQL string with a different one -- braces in string should be stripped"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '"""SELECT * FROM t WHERE json @> \'{"a":1}\' """',
                newText     : '"""SELECT * FROM t WHERE id = 1"""',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "succeeds -- string-strip heuristic prevents false positive"
        r.result != null
        r.result.isError == null || r.result.isError == false
        readContent(f.path).contains('WHERE id = 1')
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-6: Parens inside SQL triple-quoted strings -- no false mismatch
    // -----------------------------------------------------------------------
    def "CT-GUARD-6: replace removing SQL WHERE clause with parens does not false-trigger paren guard"() {
        given:
        def f = writeFile('ct-guard-6.groovy',
            'class Guard6 {\n    String q = """SELECT * FROM t WHERE (a = 1 OR b = 2)"""\n}\n')

        when: "replace the triple-quoted SQL string -- parens in string literal"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '"""SELECT * FROM t WHERE (a = 1 OR b = 2)"""',
                newText     : '"""SELECT * FROM t WHERE a = 1"""',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "succeeds -- string-strip heuristic prevents false paren positive"
        r.result != null
        r.result.isError == null || r.result.isError == false
        readContent(f.path).contains('WHERE a = 1')
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-7: Real brace removal outside strings -- still blocked
    // -----------------------------------------------------------------------
    def "CT-GUARD-7: replace genuinely dropping a closing brace outside strings is still blocked"() {
        given:
        def f = writeFile('ct-guard-7.groovy',
            'class Guard7 {\n    void run() {\n        log.info("done")\n    }\n}\n')

        when: "newText genuinely drops the closing brace of run()"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '    void run() {\n        log.info("done")\n    }\n',
                newText     : '    void run() {\n        log.info("updated")\n',   // missing }
                expectedHash: f.hash
            ]
        ], 'test')

        then: "brace guard fires -- real structural problem detected"
        assertToolError(r, 'brace')
        readContent(f.path).contains('log.info("done")')
    }

    // -----------------------------------------------------------------------
    // CT-GUARD-8: .kt and .kts guarded consistently
    // -----------------------------------------------------------------------
    def "CT-GUARD-8: brace delta mismatch on .kt file is rejected"() {
        given:
        def f = writeFile('ct-guard-8.kt',
            'class Guard8 {\n    fun foo() {\n        val x = 1\n    }\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '    fun foo() {\n        val x = 1\n    }\n',
                newText     : '    fun foo() {\n        val x = 2\n',   // missing }
                expectedHash: f.hash
            ]
        ], 'test')

        then: "brace guard fires on .kt"
        assertToolError(r, 'brace')
        readContent(f.path).contains('val x = 1')
    }

    // -----------------------------------------------------------------------
    // D5 contract: no brace_warning field in any MCP response
    // -----------------------------------------------------------------------
    def "D5: successful replace on .groovy never includes brace_warning field in response"() {
        given:
        def f = writeFile('ct-d5.groovy',
            'class D5 {\n    def x = 1\n}\n')

        when: "a normal balanced replace"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : 'def x = 1',
                newText     : 'def x = 2',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "success -- and no brace_warning in response"
        r.result != null
        r.result.isError == null || r.result.isError == false
        String text = (r.result.content[0] as Map).text as String
        !text.contains('brace_warning')
    }
}
