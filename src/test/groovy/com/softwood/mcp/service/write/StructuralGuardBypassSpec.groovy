package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * StructuralGuardBypassSpec -- CT-SG-BYPASS-1..9 contract tests.
 * FS 0.9.6 / fix #142 (Ideation #142 -- FS-CS-FRICTION-FIXES-2026-05-22 brief).
 *
 * Verifies allowStructuralEdit=true bypass across ALL three mutating write
 * paths (replace, patch, multi_replace) and the companion soft warning on
 * action=append targeting code files.
 *
 *   CT-SG-BYPASS-1  replace: orphaned brace removed with allowStructuralEdit=true (FileReplaceService)
 *   CT-SG-BYPASS-2  replace: same edit WITHOUT flag rejected by brace guard
 *   CT-SG-BYPASS-3  replace: allowStructuralEdit=true does NOT suppress checkBareBoxDrawing
 *   CT-SG-BYPASS-4  StructuralGuard.checkAll unit: flag skips brace/paren, not box-drawing
 *   CT-SG-BYPASS-5  append: .groovy file returns code_append_warning in response
 *   CT-SG-BYPASS-6  patch: orphaned brace removed with allowStructuralEdit=true (FilePatchService)
 *   CT-SG-BYPASS-7  patch: same edit WITHOUT flag rejected by brace guard
 *   CT-SG-BYPASS-8  multi_replace: orphaned brace removed with allowStructuralEdit=true
 *   CT-SG-BYPASS-9  multi_replace: same edit WITHOUT flag rejected by brace guard
 *
 * NOTE on oldText uniqueness:
 * A file with two consecutive bare closing-brace lines produces duplicate matches for
 * the naive pattern, causing TextMatcher to reject before StructuralGuard is reached.
 * Tests use a unique MARKER comment before the orphaned brace so oldText is unambiguous.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class StructuralGuardBypassSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // Helpers -- mirror the pattern from StructuralGuardSpec
    // -----------------------------------------------------------------------

    /** Write a file via the tool surface; returns [path, hash]. */
    private Map setupFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'write', path: f.absolutePath, content: text
        ], 'setup')
        assert r.result != null : "Setup write failed: ${r.error?.message}"
        Map parsed = new JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: parsed.file_content_hash as String, file: f]
    }

    /** Read current file content as String via the tool surface. */
    private String readContent(String path) {
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action: 'read', path: path, options: [force: true]
        ], 'verify')
        assert r.result != null
        (new JsonSlurper().parseText(r.result.content[0].text as String) as Map).content as String
    }

    /** Parse a write tool response to a Map. */
    private Map parseResponse(McpResponse r) {
        assert r.result != null : "Null result: ${r.error?.message}"
        new JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    /** Assert the response is a tool-level error containing the given keyword. */
    private void assertGuardFired(McpResponse r, String keyword) {
        assert r.result != null
        assert r.result.isError == true : "Expected guard error, got: ${r.result}"
        String text = (r.result.content[0] as Map).text as String
        assert text.toLowerCase().contains(keyword.toLowerCase()) :
            "Guard error missing '${keyword}'. Full text: ${text}"
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-1  replace path: bypass flag lets repair through
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-1: replace removing orphaned brace succeeds with allowStructuralEdit=true'() {
        given: 'a .groovy file with a uniquely-marked orphaned closing brace'
        def f = setupFile('ReplaceBypass.groovy',
            'class ReplaceBypass {\n    void run() { println "ok" }\n}\n// ORPHANED_BRACE\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'replace', path: f.path,
            options: [oldText: '// ORPHANED_BRACE\n}\n', newText: '',
                      expectedHash: f.hash, allowStructuralEdit: true]
        ], 'test')

        then: 'write succeeds'
        r.result.isError != true

        and: 'orphaned brace removed'
        !readContent(f.path).contains('ORPHANED_BRACE')
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-2  replace path: guard enforced without flag
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-2: replace removing orphaned brace is rejected without allowStructuralEdit'() {
        given:
        def f = setupFile('ReplaceGuarded.groovy',
            'class ReplaceGuarded {\n    void run() { println "ok" }\n}\n// ORPHANED_BRACE\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'replace', path: f.path,
            options: [oldText: '// ORPHANED_BRACE\n}\n', newText: '',
                      expectedHash: f.hash]
        ], 'test')

        then: 'brace guard fires'
        assertGuardFired(r, 'brace')
        (f.file as File).text.contains('ORPHANED_BRACE')
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-3  box-drawing guard never bypassed by allowStructuralEdit
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-3: allowStructuralEdit=true does not suppress checkBareBoxDrawing'() {
        given: 'file with a comment-protected box-drawing divider (valid starting state)'
        // CT-GUARD-3 established: bare box-drawing at line start is always rejected.
        // Verify allowStructuralEdit does NOT disable that protection.
        def f = setupFile('BoxDraw.groovy',
            'class BoxDraw {\n    // \u2500\u2500\u2500 section\n    def x = 1\n}\n')

        when: 'strip the // prefix to make divider bare -- even with bypass flag'
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'replace', path: f.path,
            options: [oldText : '    // \u2500\u2500\u2500 section',
                      newText : '    \u2500\u2500\u2500 section',
                      expectedHash: f.hash, allowStructuralEdit: true]
        ], 'test')

        then: 'box-drawing guard still fires'
        r.result.isError == true
    }
    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-4  StructuralGuard.checkAll unit: flag behaviour
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-4: StructuralGuard.checkAll skips brace/paren with flag, never box-drawing'() {
        given:
        String removed = 'if (x) {'   // +1 open brace, +1 open paren
        String newText = 'x'          // delta 0 -- mismatch without flag
        String updated = 'class C { x }'
        String path    = 'Foo.groovy'

        expect: 'flag=false: brace guard fires'
        StructuralGuard.checkAll(removed, newText, updated, path, false) != null

        and: 'flag=true: brace/paren skipped, no error (no box-drawing in content)'
        StructuralGuard.checkAll(removed, newText, updated, path, true) == null

        and: 'flag=true: box-drawing in updatedContent still fires'
        StructuralGuard.checkAll('x', 'x', '\u2500 bad', path, true) != null
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-5  append soft warning present in response
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-5: append on .groovy file returns code_append_warning'() {
        given:
        def f = setupFile('Appendable.groovy', 'class Appendable { void foo() { } }\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'append', path: f.path, content: '// extra\n', options: [:]
        ], 'test')
        Map parsed = parseResponse(r)

        then: 'write succeeds'
        r.result.isError != true

        and: 'warning field present and descriptive'
        parsed.code_append_warning != null
        (parsed.code_append_warning as String).toLowerCase().contains('append')
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-6  patch path: bypass flag lets repair through (FilePatchService)
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-6: patch removing orphaned brace succeeds with allowStructuralEdit=true'() {
        given: '4-line file; line 4 is the orphaned brace'
        // line 1: class PatchBypass {
        // line 2:     void run() { println "ok" }
        // line 3: }  <- class close
        // line 4: }  <- orphaned
        def f = setupFile('PatchBypass.groovy',
            'class PatchBypass {\n    void run() { println "ok" }\n}\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'patch', path: f.path,
            options: [replacements: [[startLine: 4, endLine: 4, newText: '']],
                      expectedHash: f.hash, allowStructuralEdit: true]
        ], 'test')

        then: 'write succeeds'
        r.result.isError != true

        and: 'orphaned brace gone -- file now 3 lines'
        readContent(f.path).readLines().size() == 3
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-7  patch path: guard enforced without flag (FilePatchService)
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-7: patch removing orphaned brace is rejected without allowStructuralEdit'() {
        given:
        def f = setupFile('PatchGuarded.groovy',
            'class PatchGuarded {\n    void run() { println "ok" }\n}\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'patch', path: f.path,
            options: [replacements: [[startLine: 4, endLine: 4, newText: '']],
                      expectedHash: f.hash]
        ], 'test')

        then: 'brace guard fires'
        assertGuardFired(r, 'brace')

        and: 'file unchanged at 4 lines'
        readContent(f.path).readLines().size() == 4
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-8  multi_replace path: bypass flag succeeds
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-8: multi_replace removing orphaned brace succeeds with allowStructuralEdit=true'() {
        given:
        def f = setupFile('MultiReplaceBypass.groovy',
            'class MultiReplaceBypass {\n    void run() { println "ok" }\n}\n// ORPHANED_MR\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'multi_replace', path: f.path,
            options: [replacements: [[oldText: '// ORPHANED_MR\n}\n', newText: '']],
                      expectedHash: f.hash, allowStructuralEdit: true]
        ], 'test')

        then: 'write succeeds'
        r.result.isError != true
        !readContent(f.path).contains('ORPHANED_MR')
    }

    // -----------------------------------------------------------------------
    // CT-SG-BYPASS-9  multi_replace path: guard enforced without flag
    // -----------------------------------------------------------------------
    def 'CT-SG-BYPASS-9: multi_replace removing orphaned brace is rejected without allowStructuralEdit'() {
        given:
        def f = setupFile('MultiReplaceGuarded.groovy',
            'class MultiReplaceGuarded {\n    void run() { println "ok" }\n}\n// ORPHANED_MRG\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'multi_replace', path: f.path,
            options: [replacements: [[oldText: '// ORPHANED_MRG\n}\n', newText: '']],
                      expectedHash: f.hash]
        ], 'test')

        then: 'brace guard fires'
        assertGuardFired(r, 'brace')
        readContent(f.path).contains('ORPHANED_MRG')
    }
}
