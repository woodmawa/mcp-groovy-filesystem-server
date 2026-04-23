package com.softwood.mcp.service

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
 * FileReplaceAndPatchSpec -- targeted regression tests for FS 0.8.47 fixes.
 *
 * Fix A'' -- positionalReplace: unicode replace only touches matched region
 * Fix B   -- per-entry normalisation in multi_replace (mixed unicode+ASCII)
 * Fix C   -- sequential boundary patch within 60s is blocked
 * Fix D   -- lines_shifted in all patch responses
 * Fix E   -- tail_content on boundary patch responses
 * Fix F   -- pre-apply brace balance check rejects unbalanced Groovy boundary patch
 * Fix G   -- removed_lines snippet in patch response
 *
 * Spock rules (practice #407):
 *   - @CompileDynamic on spec class
 *   - No field-level Mock() -- @SpringBootTest wires real beans, no mocks needed
 *   - No split given:/then: for stubs
 *   - TempDir for isolation
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class FileReplaceAndPatchSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // Helper: write a file and return [path, hash]
    // -----------------------------------------------------------------------
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

    private Map parseResult(McpResponse r) {
        new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    /** Assert a response carries a visible tool-level error (RCA-1 new contract). */
    private void assertToolError(McpResponse r, String... keywords) {
        assert r.result != null :
            "Expected isError content response, but r.result is null. r.error=${r.error?.message} — OLD broken contract."
        assert r.result.isError == true : "Expected isError:true, got: ${r.result}"
        String text = (r.result.content[0] as Map).text as String
        keywords.each { kw ->
            assert text.toLowerCase().contains(kw.toLowerCase()) :
                "Error text missing '${kw}'. Full text: ${text}"
        }
    }

    // -----------------------------------------------------------------------
    // Fix A'' -- replace with em-dash only touches matched region
    // -----------------------------------------------------------------------

    def "replace succeeds when oldText contains em-dash written by prior write"() {
        given:
        String em = '\u2014'
        def f = writeFile('fix-a1.txt', "line1\ncomment ${em} note\nline3\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: "comment ${em} note", newText: 'comment -- replaced', expectedHash: f.hash, verbose: true]
        ], 't-a1')

        then:
        r.error == null
        def result = parseResult(r)
        result.success == true
        result.replacements == 1
        new File(f.path as String).text == "line1\ncomment -- replaced\nline3\n"
    }

    def "replace with em-dash does NOT normalise surrounding file content"() {
        given: "two unicode chars in separate lines"
        String em    = '\u2014'
        String arrow = '\u2192'
        def f = writeFile('fix-a2.txt', "method1 ${em} desc\nmethod2 ${arrow} other\n")

        when: "replace only the em-dash line"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: "method1 ${em} desc", newText: 'method1 -- replaced', expectedHash: f.hash, verbose: true]
        ], 't-a2')

        then:
        r.error == null
        String out = new File(f.path as String).text
        out.contains('method1 -- replaced')
        out.contains("method2 ${arrow} other")  // arrow MUST be preserved
    }

    // -----------------------------------------------------------------------
    // Fix B -- multi_replace: per-entry normalisation, ASCII entries unaffected
    // -----------------------------------------------------------------------

    def "multi_replace with one unicode and one ASCII entry both succeed cleanly"() {
        given:
        String em = '\u2014'
        def f = writeFile('fix-b1.txt', "section1 ${em} unicode\nsection2 ascii\nend\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                replacements: [
                    [oldText: "section1 ${em} unicode", newText: 'section1 -- fixed'],
                    [oldText: 'section2 ascii',         newText: 'section2 replaced']
                ],
                expectedHash: f.hash,
                verbose     : true
            ]
        ], 't-b1')

        then:
        r.error == null
        def result = parseResult(r)
        result.success == true
        result.applied == 2
        String out = new File(f.path as String).text
        out.contains('section1 -- fixed')
        out.contains('section2 replaced')
        out.contains('end')
    }

    def "multi_replace fails clearly when one entry is not found"() {
        given:
        def f = writeFile('fix-b2.txt', "alpha\nbeta\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                replacements: [
                    [oldText: 'alpha',    newText: 'ALPHA'],
                    [oldText: 'NOTEXIST', newText: 'X']
                ],
                expectedHash: f.hash
            ]
        ], 't-b2')

        then: "file NOT modified, clear error visible to Claude (RCA-1 fix)"
        assertToolError(r, 'validation failed')
        new File(f.path as String).text == "alpha\nbeta\n"
    }

    // -----------------------------------------------------------------------
    // Fix C -- sequential boundary patch within 60s is BLOCKED
    // -----------------------------------------------------------------------

    def "sequential boundary patch within 60s is rejected with clear error"() {
        given: "5-line file"
        def f = writeFile('fix-c1.txt', "line1\nline2\nline3\nline4\nline5\n")

        and: "first patch to boundary lines 4-5 -- succeeds"
        McpResponse r1 = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 4, endLine: 5, newText: 'replaced4\nreplaced5']],
                expectedHash: f.hash,
                verbose     : true
            ]
        ], 't-c1a')
        assert r1.error == null : "first patch failed: ${r1.error}"
        def r1result = parseResult(r1)
        String newHash = r1result.file_content_hash ?: r1result.content_hash

        when: "immediately patch boundary again (no re-read, within 60s)"
        McpResponse r2 = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 4, endLine: 5, newText: 'badpatch']],
                expectedHash: newHash
            ]
        ], 't-c1b')

        then: "second boundary patch BLOCKED, error visible to Claude (RCA-1 fix)"
        assertToolError(r2, 'Sequential boundary patch rejected')
    }

    // -----------------------------------------------------------------------
    // Fix D -- lines_shifted present in all patch responses
    // -----------------------------------------------------------------------

    def "patch response includes lines_shifted"() {
        given:
        def f = writeFile('fix-d1.txt', "a\nb\nc\nd\ne\n")

        when: "replace 2 lines with 4 lines -- shift should be +2"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 2, endLine: 3, newText: 'x\ny\nz\nw']],
                expectedHash: f.hash,
                verbose     : true
            ]
        ], 't-d1')

        then:
        r.error == null
        def result = parseResult(r)
        result.lines_shifted == 2
        result.result_lines  == 7
    }

    def "patch response includes lines_shifted when removing lines"() {
        given:
        def f = writeFile('fix-d2.txt', "a\nb\nc\nd\ne\n")

        when: "replace 3 lines with 1 -- shift should be -2"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 1, endLine: 3, newText: 'only-one']],
                expectedHash: f.hash,
                verbose     : true
            ]
        ], 't-d2')

        then:
        r.error == null
        def result = parseResult(r)
        result.lines_shifted == -2
        result.result_lines  == 3
    }

    // -----------------------------------------------------------------------
    // Fix E -- tail_content on boundary patch responses
    // -----------------------------------------------------------------------

    def "boundary patch response includes tail_content"() {
        given:
        def f = writeFile('fix-e1.txt', "a\nb\nc\nd\nold-last\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 5, endLine: 5, newText: 'new-last']],
                expectedHash: f.hash,
                verbose     : true
            ]
        ], 't-e1')

        then:
        r.error == null
        def result = parseResult(r)
        result.tail_content != null
        (result.tail_content as String).contains('new-last')
    }

    def "non-boundary patch response does NOT include tail_content"() {
        given:
        def f = writeFile('fix-e2.txt', "a\nb\nc\nd\ne\n")

        when: "patch middle lines -- NOT boundary"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 2, endLine: 3, newText: 'x']],
                expectedHash: f.hash,
                verbose     : true
            ]
        ], 't-e2')

        then:
        r.error == null
        def result = parseResult(r)
        !result.containsKey('tail_content')
    }

    // -----------------------------------------------------------------------
    // Fix F -- pre-apply brace check rejects unbalanced Groovy boundary patch
    // -----------------------------------------------------------------------

    def "boundary patch on Groovy file with unbalanced braces is rejected before write"() {
        given: "minimal Groovy class"
        String groovyContent = 'class Foo {\n    void bar() {\n        println "hello"\n    }\n}\n'
        File gf = tempDir.resolve('fix-f1.groovy').toFile()
        gf.text = groovyContent
        String hash = fileReadService.handleToolCall('file_read',
            [action: 'info', path: gf.absolutePath], 'setup-hash').with {
            new groovy.json.JsonSlurper().parseText(it.result.content[0].text as String).file_content_hash as String
        }

        when: "boundary patch that opens a brace but forgets to close it"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : gf.absolutePath,
            options: [
                replacements: [[startLine: 4, endLine: 5, newText: '    void extra() {\n        // missing close brace']],
                expectedHash: hash
            ]
        ], 't-f1')

        then: "rejected before write, file unchanged, error visible to Claude (RCA-1 fix)"
        assertToolError(r, 'brace')
        gf.text == groovyContent
    }

    // -----------------------------------------------------------------------
    // CT-NEW-A -- replace oldText ending at EOF without trailing newline
    //             newText must preserve ALL content that was after the match
    // -----------------------------------------------------------------------

    /**
     * Documents the operator contract for replace when oldText ends at EOF.
     * The tool performs an exact string substitution -- if oldText ends with a
     * structural delimiter (e.g. closing triple-quote in a Modelfile SYSTEM block)
     * and newText omits it, the delimiter is silently lost.
     *
     * This test asserts the CURRENT behaviour (no implicit suffix preservation)
     * and serves as a regression anchor if behaviour changes.
     */
    def "replace succeeds but drops trailing delimiter when newText does not include it"() {
        given: "file whose oldText ends at the very end of file (no trailing newline)"
        def f = writeFile('ct-new-a.txt', 'PREFIX\nSOME CONTENT\nEND_DELIMITER')

        when: "replace SOME CONTENT but forget to include END_DELIMITER in newText"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: 'SOME CONTENT\nEND_DELIMITER', newText: 'REPLACED CONTENT', expectedHash: f.hash]
        ], 'ct-new-a')

        then: "replace succeeds -- tool cannot know semantic intent"
        r.error == null
        def result = parseResult(r)
        result.success == true

        and: "END_DELIMITER is gone -- operator must include it in newText explicitly"
        String out = new File(f.path as String).text
        out == 'PREFIX\nREPLACED CONTENT'
        !out.contains('END_DELIMITER')
    }

    def "replace preserves trailing delimiter when newText explicitly includes it"() {
        given:
        def f = writeFile('ct-new-b.txt', 'PREFIX\nSOME CONTENT\nEND_DELIMITER')

        when: "replace SOME CONTENT and explicitly include END_DELIMITER in newText"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: 'SOME CONTENT\nEND_DELIMITER', newText: 'REPLACED CONTENT\nEND_DELIMITER', expectedHash: f.hash]
        ], 'ct-new-b')

        then:
        r.error == null
        def result = parseResult(r)
        result.success == true
        new File(f.path as String).text == 'PREFIX\nREPLACED CONTENT\nEND_DELIMITER'
    }

    // -----------------------------------------------------------------------
    // Fix G -- removed_lines snippet in patch response
    // -----------------------------------------------------------------------

    def "patch response includes removed_lines snippet"() {
        given:
        def f = writeFile('fix-g1.txt', "keep-me\nold-content-here\nkeep-too\n")

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 2, endLine: 2, newText: 'new-content']],
                expectedHash: f.hash,
                verbose     : true
            ]
        ], 't-g1')

        then:
        r.error == null
        def result = parseResult(r)
        result.removed_lines != null
        (result.removed_lines as String).contains('old-content-here')
    }
}
