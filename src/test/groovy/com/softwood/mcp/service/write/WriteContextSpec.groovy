package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * WriteContextSpec -- Phase 0 (RED) contract tests for WriteContext helper.
 * Target version: FS 0.9.0
 *
 * These tests MUST FAIL against 0.8.83 (WriteContext does not exist yet).
 * They define the contracts that WriteContext.load() must satisfy:
 *
 *   CT-SIZE-1  : file over max write size is rejected before full read
 *   CT-ENC-1   : unsupported encoding returns structured invalid_encoding error
 *   CT-ENC-2   : malformed UTF-8 bytes are rejected, file left unchanged
 *   CT-BIN-1   : replace/patch on binary file rejected by default
 *   CT-HASH-1  : hash mismatch returns consistent error shape across all three write actions
 *   CT-HASH-2  : absent expectedHash returns consistent error shape across all three write actions
 *
 * Spock rules (practice #407):
 *   - @CompileDynamic on spec class
 *   - @SpringBootTest wires real beans
 *   - No @CompileStatic — Spock stubs extending @CompileStatic classes require @CompileDynamic
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class WriteContextSpec extends Specification {

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
    // CT-SIZE-1: file over max write size is rejected before full read
    // -----------------------------------------------------------------------
    def "CT-SIZE-1: replace on file exceeding max write size returns file_too_large error"() {
        given: "a file that is artificially large (simulate via a property override approach)"
        // We write a normal small file and attempt a replace with maxWriteSizeBytes overridden
        // via system property so WriteContext.load() rejects before readAllBytes.
        // Phase 0: this test will fail because WriteContext doesn't exist yet — the replace
        // will succeed (no size guard in 0.8.83), which is incorrect.
        def f = writeFile('ct-size-1.groovy', 'class Foo { def bar = 1 }\n')

        // To exercise the guard at a testable size, we write a file that exceeds the
        // configured threshold. The threshold is mcp.filesystem.max-write-size-mb (default 10MB).
        // We use a 1-byte-over approach by writing a file and passing an artificially low
        // maxWriteSizeBytes. Since we can't inject that per-call yet (WriteContext is new),
        // we instead create a file > 10MB to trigger the real guard.
        // For Phase 0 simplicity: just assert that the error SHAPE is correct when it fires.
        // We'll use a 1MB test file approach plus a tiny threshold via application-test.yml.
        // For now this test documents the expected contract and will go RED due to no size guard.
        File bigFile = tempDir.resolve('ct-size-1-big.groovy').toFile()
        // Write ~11MB of content (exceeds 10MB default)
        StringBuilder sb = new StringBuilder()
        String line = '// padding line to make the file large enough to trigger the size guard\n'
        int targetBytes = 11 * 1024 * 1024
        while (sb.length() < targetBytes) { sb.append(line) }
        bigFile.text = sb.toString()
        // Get current hash so we can attempt a replace
        McpResponse readR = fileReadService.handleToolCall('file_read', [
            action: 'range', path: bigFile.absolutePath,
            options: [startLine: 1, maxLines: 1]
        ], 'hash-probe')
        String currentHash = readR.result ?
            (new groovy.json.JsonSlurper().parseText(readR.result.content[0].text as String) as Map).file_content_hash as String
            : 'probe-failed'

        when: "we attempt a replace on the oversized file"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : bigFile.absolutePath,
            options: [
                oldText     : '// padding line to make the file large enough to trigger the size guard',
                newText     : '// replaced',
                expectedHash: currentHash
            ]
        ], 'test')

        then: "file_too_large error returned, file unchanged"
        assertToolError(r, 'file_too_large', 'max write size')
        // File should be unchanged
        bigFile.text.startsWith('// padding')
    }

    // -----------------------------------------------------------------------
    // CT-ENC-1: unsupported encoding returns structured invalid_encoding error
    // -----------------------------------------------------------------------
    def "CT-ENC-1: replace with unsupported encoding option returns invalid_encoding error"() {
        given:
        def f = writeFile('ct-enc-1.groovy', 'class Foo { String x = "hello" }\n')

        when: "we attempt a replace specifying a non-existent encoding"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '"hello"',
                newText     : '"world"',
                expectedHash: f.hash,
                encoding    : 'MARTIAN-7'
            ]
        ], 'test')

        then: "invalid_encoding error, file unchanged"
        assertToolError(r, 'invalid_encoding')
        readFileContent(f.path).contains('"hello"')
        !readFileContent(f.path).contains('"world"')
    }

    // -----------------------------------------------------------------------
    // CT-ENC-2: malformed UTF-8 bytes are rejected, file left unchanged
    // -----------------------------------------------------------------------
    def "CT-ENC-2: replace on file containing invalid UTF-8 bytes returns invalid_encoding error"() {
        given: "a file written with invalid UTF-8 byte sequences"
        File rawFile = tempDir.resolve('ct-enc-2.groovy').toFile()
        // Write valid UTF-8 content then inject invalid bytes via raw byte array
        byte[] validContent = 'class Foo { String x = "hello" }\n'.getBytes('UTF-8')
        // Inject 0xFF 0xFE (invalid in UTF-8 context) in the middle
        byte[] corrupt = new byte[validContent.length + 2]
        System.arraycopy(validContent, 0, corrupt, 0, 10)
        corrupt[10] = (byte) 0xFF
        corrupt[11] = (byte) 0xFE
        System.arraycopy(validContent, 10, corrupt, 12, validContent.length - 10)
        rawFile.bytes = corrupt

        // Get hash of the corrupt file so we pass a valid expectedHash
        McpResponse readR = fileReadService.handleToolCall('file_read', [
            action: 'range', path: rawFile.absolutePath,
            options: [startLine: 1, maxLines: 1]
        ], 'hash-probe')
        // Note: hash-probe may itself fail due to invalid UTF-8 — that's expected.
        // We still need a hash string for the test; use dummy if probe failed.
        String currentHash = 'probe-hash-ct-enc-2'
        if (readR.result) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(readR.result.content[0].text as String) as Map
                if (parsed.file_content_hash) currentHash = parsed.file_content_hash as String
            } catch (Exception ignored) {}
        }

        when: "we attempt a replace on the file with invalid UTF-8"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : rawFile.absolutePath,
            options: [
                oldText     : '"hello"',
                newText     : '"world"',
                expectedHash: currentHash
            ]
        ], 'test')

        then: "invalid_encoding error returned, file bytes unchanged"
        assertToolError(r, 'invalid_encoding')
        rawFile.bytes == corrupt
    }

    // -----------------------------------------------------------------------
    // CT-BIN-1: replace/patch on binary file rejected by default
    // -----------------------------------------------------------------------
    def "CT-BIN-1: replace on a binary file is rejected without forceBinary:true"() {
        given: "a binary file (simulated with high density of non-printable bytes)"
        File binFile = tempDir.resolve('ct-bin-1.bin').toFile()
        // Create a byte array with >5% non-printable content
        byte[] bytes = new byte[200]
        // First 100 bytes: printable ASCII
        for (int i = 0; i < 100; i++) bytes[i] = (byte)(65 + (i % 26))   // 'A'..'Z' cycling
        // Next 100 bytes: non-printable (0x01..0x08 range, excluding tab/LF/CR)
        for (int i = 100; i < 200; i++) bytes[i] = (byte)(1 + (i % 7))    // 0x01..0x07
        binFile.bytes = bytes

        McpResponse readR = fileReadService.handleToolCall('file_read', [
            action: 'range', path: binFile.absolutePath,
            options: [startLine: 1, maxLines: 1]
        ], 'hash-probe')
        String currentHash = 'bin-hash-ct-bin-1'
        if (readR.result) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(readR.result.content[0].text as String) as Map
                if (parsed.file_content_hash) currentHash = parsed.file_content_hash as String
            } catch (Exception ignored) {}
        }

        when: "we attempt a replace without forceBinary:true"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : binFile.absolutePath,
            options: [
                oldText     : 'ABC',
                newText     : 'XYZ',
                expectedHash: currentHash
            ]
        ], 'test')

        then: "binary_file error returned"
        assertToolError(r, 'binary_file')
        binFile.bytes == bytes
    }

    // -----------------------------------------------------------------------
    // CT-HASH-1: hash mismatch returns consistent error shape across all three write actions
    // -----------------------------------------------------------------------
    def "CT-HASH-1: stale expectedHash on replace returns expectedHash mismatch error"() {
        given:
        def f = writeFile('ct-hash-1-replace.groovy', 'class A { def x = 1 }\n')
        String staleHash = 'aabbccddeeff'  // deliberately wrong

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: 'x = 1', newText: 'x = 2', expectedHash: staleHash]
        ], 'test')

        then:
        assertToolError(r, 'expectedHash', 'mismatch')
        readFileContent(f.path).contains('x = 1')
    }

    def "CT-HASH-1b: stale expectedHash on multi_replace returns expectedHash mismatch error"() {
        given:
        def f = writeFile('ct-hash-1-mr.groovy', 'class B { def y = 1 }\n')
        String staleHash = 'aabbccddeeff'

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                replacements: [[oldText: 'y = 1', newText: 'y = 2']],
                expectedHash: staleHash
            ]
        ], 'test')

        then:
        assertToolError(r, 'expectedHash', 'mismatch')
        readFileContent(f.path).contains('y = 1')
    }

    def "CT-HASH-1c: stale expectedHash on patch returns expectedHash mismatch error"() {
        given:
        def f = writeFile('ct-hash-1-patch.groovy', 'class C {\n    def z = 1\n}\n')
        String staleHash = 'aabbccddeeff'

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [
                replacements: [[startLine: 2, endLine: 2, newText: '    def z = 2\n']],
                expectedHash: staleHash
            ]
        ], 'test')

        then:
        assertToolError(r, 'expectedHash', 'mismatch')
        readFileContent(f.path).contains('z = 1')
    }

    // -----------------------------------------------------------------------
    // CT-HASH-2: absent expectedHash returns consistent error shape
    // (these already pass in 0.8.83 per CT-EH-1 — included here for suite completeness
    //  and to confirm the error text shape is consistent with what WriteContext will produce)
    // -----------------------------------------------------------------------
    def "CT-HASH-2: absent expectedHash on replace returns mandatory-hash error"() {
        given:
        def f = writeFile('ct-hash-2-replace.groovy', 'class D { def a = 1 }\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [oldText: 'a = 1', newText: 'a = 2']
            // no expectedHash
        ], 'test')

        then:
        assertToolError(r, 'expectedHash')
        readFileContent(f.path).contains('a = 1')
    }

    def "CT-HASH-2b: absent expectedHash on multi_replace returns mandatory-hash error"() {
        given:
        def f = writeFile('ct-hash-2-mr.groovy', 'class E { def b = 1 }\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [replacements: [[oldText: 'b = 1', newText: 'b = 2']]]
            // no expectedHash
        ], 'test')

        then:
        assertToolError(r, 'expectedHash')
        readFileContent(f.path).contains('b = 1')
    }

    def "CT-HASH-2c: absent expectedHash on patch returns mandatory-hash error"() {
        given:
        def f = writeFile('ct-hash-2-patch.groovy', 'class F {\n    def c = 1\n}\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : f.path,
            options: [replacements: [[startLine: 2, endLine: 2, newText: '    def c = 2\n']]]
            // no expectedHash
        ], 'test')

        then:
        assertToolError(r, 'expectedHash')
        readFileContent(f.path).contains('c = 1')
    }
}
