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
 * FileReadContractSpec -- Phase 0 (RED) contract tests for read-side KH hint corrections.
 * Target version: FS 0.9.0
 *
 *   CT-KH-2 : range with matching knownHash returns unchanged:true + hash; range-cache still recorded
 *   CT-KH-3 : _knownhash_hint for range says use hash for action=read only, NOT action=range
 *   CT-KH-4 : auto-derived stale hash does not return cached content after external file change
 *
 * CT-KH-3 MUST FAIL against 0.8.83: the hint currently says "pass as options.knownHash on
 * EVERY subsequent file_read" which is misleading — passing it to action=range suppresses
 * content. The hint needs to explicitly say NOT to pass it to range.
 *
 * Spock rules (practice #407): @CompileDynamic, @SpringBootTest, TempDir.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class FileReadContractSpec extends Specification {

    @Autowired FileReadService  fileReadService
    @Autowired FileWriteService fileWriteService

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

    private Map doRange(String path, int startLine, int maxLines, String knownHash = null) {
        def opts = [startLine: startLine, maxLines: maxLines]
        if (knownHash) opts.knownHash = knownHash
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : path,
            options: opts
        ], 'test')
        assert r.result != null : "Range read failed: ${r.error?.message}"
        new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    // -----------------------------------------------------------------------
    // CT-KH-2: range with matching knownHash returns unchanged:true + hash
    // -----------------------------------------------------------------------
    def "CT-KH-2: range with correct knownHash returns unchanged:true and file_content_hash"() {
        given:
        def f = writeFile('ct-kh-2.groovy', 'class KH2 {\n    def a = 1\n    def b = 2\n}\n')

        // First range read — get the file_content_hash
        Map first = doRange(f.path, 1, 3)
        String hash = first.file_content_hash as String
        assert hash != null : "First range read did not return file_content_hash"

        when: "second range read passes the captured hash as knownHash"
        Map second = doRange(f.path, 1, 3, hash)

        then: "returns unchanged:true and still provides file_content_hash"
        second.unchanged == true
        second.file_content_hash != null
    }

    // -----------------------------------------------------------------------
    // CT-KH-3: _knownhash_hint for range must NOT say to pass knownHash for range reads
    // -----------------------------------------------------------------------
    def "CT-KH-3: _knownhash_hint emitted by range read explicitly warns against passing knownHash to range"() {
        given:
        def f = writeFile('ct-kh-3.groovy', 'class KH3 {\n    def x = 1\n}\n')

        when: "we do a range read WITHOUT knownHash (hint should be injected)"
        Map result = doRange(f.path, 1, 3)

        then: "a _knownhash_hint is present in the response"
        result._knownhash_hint != null

        and: "the hint text tells the caller to use the hash for action=read (whole-file), NOT action=range"
        String hint = result._knownhash_hint as String
        // 0.9.0: hint must explicitly say NOT to pass knownHash to action=range
        (hint.toLowerCase().contains('do not') || hint.toLowerCase().contains('not pass')) &&
         hint.toLowerCase().contains('action=range')
    }

    // -----------------------------------------------------------------------
    // CT-KH-4: auto-derived stale hash does not serve cached content after file change
    // -----------------------------------------------------------------------
    def "CT-KH-4: range read after external file change returns fresh content, not stale cached content"() {
        given: "a file we read once to prime any caches"
        def f = writeFile('ct-kh-4.groovy', 'class KH4 { def version = "v1" }\n')
        Map first = doRange(f.path, 1, 2)
        assert (first.content as String)?.contains('v1') : "Initial read should contain v1"

        and: "the file is externally modified"
        new File(f.path).text = 'class KH4 { def version = "v2" }\n'

        when: "we do a fresh range read with no knownHash"
        Map second = doRange(f.path, 1, 2)

        then: "fresh content is returned (v2), not stale cached content (v1)"
        (second.content as String)?.contains('v2')
        !(second.content as String)?.contains('v1')
        // hash must reflect the new file state
        second.file_content_hash != first.file_content_hash
    }
}
