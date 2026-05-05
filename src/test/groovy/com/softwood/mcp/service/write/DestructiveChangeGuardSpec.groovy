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
 * DestructiveChangeGuardSpec -- contract tests for PR 1.1.
 * Target version: FS 0.9.0
 *
 * Verifies that the destructive-change ratio guard is applied uniformly across
 * all three write actions (replace, multi_replace, patch), not just replace.
 *
 *   CT-DR-5 : multi_replace combined deletion over threshold blocked without force
 *   CT-DR-6 : large patch deletion blocked without force
 *   CT-DR-7 : multi_replace destructive deletion succeeds with force:true
 *
 * Existing CT-DR-1..4 in FileReplaceAndPatchSpec continue to test replace.
 * These new tests extend coverage to multi_replace and patch (D10 fix).
 *
 * Spock rules (practice #407): @CompileDynamic, @SpringBootTest, TempDir.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class DestructiveChangeGuardSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map writeFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'write',
            path   : f.absolutePath,
            content: text
        ], 'setup')
        assert r.result != null : "Setup write failed: ${r.error?.message}"
        def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: parsed.file_content_hash as String]
    }

    private void assertToolError(McpResponse r, String... keywords) {
        assert r.result != null :
            "Expected isError content response, but r.result is null — r.error=${r.error?.message}"
        assert r.result.isError == true :
            "Expected isError:true, got: ${r.result}"
        String text = (r.result.content[0] as Map).text as String
        keywords.each { kw ->
            assert text.toLowerCase().contains(kw.toLowerCase()) :
                "Error text missing keyword '${kw}'.\nFull text: ${text}"
        }
    }

    private String readFileContent(String path) {
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action : 'read',
            path   : path,
            options: [force: true]
        ], 'verify')
        assert r.result != null : "Read failed for ${path}: ${r.error?.message}"
        def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        parsed.content as String
    }

    // Build a string of N repetitions of a line
    private static String repeat(String line, int times) {
        (1..times).collect { line }.join('\n') + '\n'
    }

    // -----------------------------------------------------------------------
    // CT-DR-5: multi_replace combined deletion over threshold blocked without force
    // -----------------------------------------------------------------------
    def "CT-DR-5: multi_replace with combined removal over threshold is blocked without force:true"() {
        given: "a file with large distinct blocks to remove"
        // Build two blocks of >300 chars each (total >600 > threshold 500)
        String blockA = repeat('// blockA padding line content to get above the ratio threshold', 6)
        String blockB = repeat('// blockB padding line content to get above the ratio threshold', 6)
        String fileContent = "class CT_DR5 {\n${blockA}\n${blockB}\n    def keep = 'safe'\n}\n"
        def f = writeFile('ct-dr5.groovy', fileContent)

        when: "we attempt a multi_replace that removes both large blocks and adds tiny replacements"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: blockA, newText: '// a\n'],
                    [oldText: blockB, newText: '// b\n'],
                ]
            ]
        ], 'test')

        then: "DESTRUCTIVE_REPLACE guard fires"
        assertToolError(r, 'DESTRUCTIVE_REPLACE')
        // File must be unchanged
        readFileContent(f.path).contains('blockA padding')
        readFileContent(f.path).contains('blockB padding')
    }

    // -----------------------------------------------------------------------
    // CT-DR-6: large patch deletion blocked without force
    // -----------------------------------------------------------------------
    def "CT-DR-6: patch replacing a large range with tiny content is blocked without force:true"() {
        given: "a file with a large block occupying many lines"
        StringBuilder sb = new StringBuilder('class CT_DR6 {\n')
        // 40 lines of content — each ~30 chars, total ~1200 chars > threshold 500
        (1..40).each { int i -> sb.append("    def line${i} = 'padding content ${i}'\n") }
        sb.append("    def keep = 'safe'\n}\n")
        def f = writeFile('ct-dr6.groovy', sb.toString())

        when: "we attempt to patch-replace all 40 content lines (2..41) with a single comment"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [[startLine: 2, endLine: 41, newText: '    // all removed\n']]
            ]
        ], 'test')

        then: "DESTRUCTIVE_REPLACE guard fires"
        assertToolError(r, 'DESTRUCTIVE_REPLACE')
        readFileContent(f.path).contains('line1')
        readFileContent(f.path).contains('line40')
    }

    // -----------------------------------------------------------------------
    // CT-DR-7: multi_replace destructive deletion succeeds with force:true
    // -----------------------------------------------------------------------
    def "CT-DR-7: multi_replace destructive deletion succeeds when force:true is passed"() {
        given: "a file with a large block to legitimately remove"
        String bigBlock = repeat('// remove this block entirely with force', 8)
        String fileContent = "class CT_DR7 {\n${bigBlock}\n    def keep = 'this stays'\n}\n"
        def f = writeFile('ct-dr7.groovy', fileContent)

        when: "we pass force:true to bypass the guard"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                force       : true,
                replacements: [
                    [oldText: bigBlock, newText: '']
                ]
            ]
        ], 'test')

        then: "succeeds — large block removed, keep line intact"
        r.result != null
        r.result.isError == null || r.result.isError == false
        !readFileContent(f.path).contains('remove this block')
        readFileContent(f.path).contains("this stays")
    }
}
