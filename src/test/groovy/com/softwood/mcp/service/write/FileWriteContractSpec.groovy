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
 * FileWriteContractSpec -- Phase 0 (RED) contract tests for option parsing hardening.
 * Target version: FS 0.9.0
 *
 *   CT-OPT-1 : malformed JSON options returns structured invalid_options error (not silent {:})
 *   CT-OPT-2 : top-level newText: '' is treated as deliberate empty-string deletion (not absent)
 *   CT-OPT-3 : top-level new_str: '' (snake_case alias) treated as deliberate deletion
 *
 * CT-OPT-1 MUST FAIL against 0.8.83: normaliseOptions silently returns {} on parse failure,
 * so the caller gets a misleading "oldText required" error rather than "invalid_options".
 *
 * CT-OPT-2 and CT-OPT-3 test that an explicit empty newText is honoured as a deliberate
 * content deletion, not confused with an absent parameter.
 *
 * Spock rules (practice #407): @CompileDynamic, @SpringBootTest, TempDir.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class FileWriteContractSpec extends Specification {

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

    // -----------------------------------------------------------------------
    // CT-OPT-1: malformed JSON in options returns invalid_options, not silent {}
    // -----------------------------------------------------------------------
    def "CT-OPT-1: malformed JSON options string returns structured invalid_options error"() {
        given:
        def f = writeFile('ct-opt-1.groovy', 'class Opt1 { def x = 1 }\n')

        when: "options is passed as an unparseable JSON string (simulating a truncated tool call)"
        // This exercises AbstractFileService.normaliseOptions with bad input.
        // In 0.8.83: silently returns {} -> downstream error 'oldText required' (wrong root cause)
        // In 0.9.0: should throw InvalidOptionsException caught at dispatch -> 'invalid_options'
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            // Pass options as a raw malformed JSON string (not a Map)
            options: '{bad json -- truncated'
        ], 'test')

        then: "invalid_options error surfaced with the raw input snippet"
        // WILL FAIL in 0.8.83: error will say 'oldText required' not 'invalid_options'
        assertToolError(r, 'invalid_options')
    }

    // -----------------------------------------------------------------------
    // CT-OPT-2: top-level newText: '' is deliberate deletion, not absent
    // -----------------------------------------------------------------------
    def "CT-OPT-2: top-level newText as explicit empty string is treated as deliberate content deletion"() {
        given:
        def f = writeFile('ct-opt-2.groovy', 'class Opt2 {\n    def toDelete = "remove me"\n    def keep = "keep this"\n}\n')

        when: "top-level newText is explicitly set to empty string"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            // top-level params (promoteTopLevelParams path)
            oldText     : '    def toDelete = "remove me"\n',
            newText     : '',   // explicit empty string — deliberate deletion
            expectedHash: f.hash
        ], 'test')

        then: "succeeds and the target line is removed"
        r.result != null
        r.result.isError == null || r.result.isError == false
        !readFileContent(f.path).contains('toDelete')
        readFileContent(f.path).contains('keep this')
    }

    // -----------------------------------------------------------------------
    // CT-FW-RG-1..4: replace pre-flight guard contracts (FS 0.9.3 / #107 fix)
    // These four contracts are the RED gate -- all must fail against FS 0.9.2,
    // pass after the pre-flight gate and post-write isToolError guard are applied.
    // -----------------------------------------------------------------------

    def "CT-FW-RG-1: action=replace with empty options returns toolError and leaves file unchanged"() {
        given: "a file with known content"
        def f = writeFile('ct-rg-1.groovy', 'class RG1 { def keep = true }\n')

        when: "replace is called with no options at all (empty map)"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'replace',
            path  : f.path
            // options absent entirely -- simulates Claude forgetting the param
        ], 'test')

        then: "toolError with actionable message, file content unchanged"
        assertToolError(r, 'oldText', 'replace')
        readFileContent(f.path) == 'class RG1 { def keep = true }\n'
    }

    def "CT-FW-RG-2: action=replace with options containing only newText returns toolError and leaves file unchanged"() {
        given:
        def f = writeFile('ct-rg-2.groovy', 'class RG2 { def keep = true }\n')

        when: "replace called with newText but no oldText -- oldText key absent from options"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [newText: 'def keep = false', expectedHash: f.hash]
        ], 'test')

        then: "toolError mentioning oldText, file content unchanged"
        assertToolError(r, 'oldText', 'replace')
        readFileContent(f.path) == 'class RG2 { def keep = true }\n'
    }

    def "CT-FW-RG-3: action=replace with oldText present but newText key entirely absent returns toolError"() {
        given:
        def f = writeFile('ct-rg-3.groovy', 'class RG3 { def keep = true }\n')

        when: "replace called with oldText but newText key absent entirely (not even null -- key missing)"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: 'def keep = true', expectedHash: f.hash]
            // newText key absent -- caller forgot it; empty string deletion must be explicit
        ], 'test')

        then: "toolError mentioning newText, file content unchanged"
        assertToolError(r, 'newText', 'replace')
        readFileContent(f.path) == 'class RG3 { def keep = true }\n'
    }

    def "CT-FW-RG-4: toolError response from replace pre-flight does not trigger post-write side-effects"() {
        given: "a file; we capture its content hash to detect if any write occurred"
        def f = writeFile('ct-rg-4.groovy', 'class RG4 { def x = 1 }\n')
        String hashBefore = f.hash

        when: "empty-options replace fires pre-flight toolError"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'replace',
            path  : f.path
        ], 'test')

        and: "we re-read the file hash to detect any spurious write"
        McpResponse readBack = fileReadService.handleToolCall('file_read', [
            action : 'read',
            path   : f.path,
            options: [force: true]
        ], 'verify')
        def readParsed = new groovy.json.JsonSlurper().parseText(readBack.result.content[0].text as String) as Map
        String hashAfter = readParsed.file_content_hash as String

        then: "toolError returned, file hash unchanged (no spurious post-write side effects)"
        r.result.isError == true
        hashAfter == hashBefore
        readFileContent(f.path) == 'class RG4 { def x = 1 }\n'
    }

    // -----------------------------------------------------------------------
    // CT-OPT-3: top-level new_str: '' (snake_case alias) treated as deliberate deletion
    // -----------------------------------------------------------------------
    def "CT-OPT-3: top-level new_str as explicit empty string is treated as deliberate content deletion"() {
        given:
        def f = writeFile('ct-opt-3.groovy', 'class Opt3 {\n    def toDelete = "remove me too"\n    def keep = "keep this too"\n}\n')

        when: "top-level new_str (snake_case alias) is explicitly set to empty string"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            old_str     : '    def toDelete = "remove me too"\n',
            new_str     : '',   // explicit empty string — deliberate deletion via alias
            expectedHash: f.hash
        ], 'test')

        then: "succeeds and the target line is removed"
        r.result != null
        r.result.isError == null || r.result.isError == false
        !readFileContent(f.path).contains('toDelete')
        readFileContent(f.path).contains('keep this too')
    }

    // -----------------------------------------------------------------------
    // CT-APPEND-1..4: an append that appends nothing must not report success
    //
    // Found 2026-09-22 writing the FS 0.9.47 changelog. content was passed inside
    // options instead of at top level, so doAppend saw null, did (content ?: '')
    // and wrote zero bytes -- and answered success:true. The changelog entry would
    // have been committed missing behind a green write. Chain 8216fbea.
    //
    // These assert the FILE, not the flag. A spec that checked success:false only
    // would pass against a version that refuses and then corrupts the file, and a
    // spec that checked the file only would pass against today's silent no-op.
    // -----------------------------------------------------------------------
    def "CT-APPEND-1: append with no content at all is refused and leaves the file untouched"() {
        given:
        def f = writeFile('ct-append-1.txt', 'original line\n')

        when: "the content argument is absent entirely -- the real shape of the defect"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'append',
            path  : f.path
        ], 'test')

        then: "it refuses, and says which argument it wanted"
        assertToolError(r, 'content')

        and: "and the file is byte-identical -- the half a success flag cannot tell you"
        readFileContent(f.path) == 'original line\n'
    }

    def "CT-APPEND-2: append with an empty string is refused for the same reason"() {
        given:
        def f = writeFile('ct-append-2.txt', 'original line\n')

        when: "an explicitly empty content -- appending nothing is still appending nothing"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'append',
            path   : f.path,
            content: ''
        ], 'test')

        then:
        assertToolError(r, 'content')

        and:
        readFileContent(f.path) == 'original line\n'
    }

    def "CT-APPEND-3: a bare newline IS legitimate content and must still append"() {
        // The boundary. Refusing 'blank' rather than 'empty' would break this, and
        // appending a newline to end a file properly is an ordinary thing to want.
        given:
        def f = writeFile('ct-append-3.txt', 'no trailing newline')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'append',
            path   : f.path,
            content: '\n'
        ], 'test')

        then:
        r.result != null
        r.result.isError == null || r.result.isError == false

        and:
        readFileContent(f.path) == 'no trailing newline\n'
    }

    def "CT-APPEND-4: a normal append reports how many bytes it wrote, compact response included"() {
        // The evidence that was missing. doAppend has always returned `appended`, but
        // ONLY on the verbose path -- the default compact response dropped it, so the
        // one field that would have shown 0 bytes was the one field suppressed.
        given:
        def f = writeFile('ct-append-4.txt', 'first\n')

        when: "the default (compact) response shape"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'append',
            path   : f.path,
            content: 'second\n'
        ], 'test')
        def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map

        then: "the byte count is present and correct"
        parsed.appended == 7

        and:
        readFileContent(f.path) == 'first\nsecond\n'
    }
}
