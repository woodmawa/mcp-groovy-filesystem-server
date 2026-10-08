package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path

/**
 * Found by the C5 review-sample blind graders (local-model-reliability, 2026-10-08; reviewer task e575ccca missed it),
 * checked against the file: promoteTopLevelParams promotes a top-level expectedHash into `merged`, then the
 * patch/multi_replace branch re-seeds `merged` from `options` when it promotes top-level replacements -- so the hash is
 * dropped and the call is refused "expectedHash required" although the caller sent one. replace got this exact fix as
 * CT-EH-1 (seed from `merged ?: options`); patch and multi_replace never did.
 */
@Title('file_write -- top-level expectedHash survives top-level replacements (patch, multi_replace)')
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class TopLevelReplacementsHashSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService fileReadService

    @TempDir Path tempDir

    private Map writeFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [action: 'write', path: f.absolutePath, content: text], 'setup')
        assert r.result != null
        def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: parsed.file_content_hash as String]
    }

    private String content(String path) {
        McpResponse r = fileReadService.handleToolCall('file_read', [action: 'read', path: path, options: [force: true]], 'verify')
        (new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map).content as String
    }

    private static boolean ok(McpResponse r) { r.result != null && !(r.result.isError == true) }

    private static String text(McpResponse r) { r.result ? ((r.result.content[0] as Map).text as String) : r.error?.message }

    def 'TLH-1: multi_replace with expectedHash AND replacements both at top level applies the edit'() {
        given:
        def f = writeFile('tlh-1.txt', 'alpha\nbeta\ngamma\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action      : 'multi_replace',
            path        : f.path,
            expectedHash: f.hash,
            replacements: [[oldText: 'beta', newText: 'BETA']]
        ], 'test')

        then:
        assert ok(r), text(r)
        content(f.path) == 'alpha\nBETA\ngamma\n'
    }

    def 'TLH-2: patch with expectedHash AND replacements both at top level applies the edit'() {
        given:
        def f = writeFile('tlh-2.txt', 'one\ntwo\nthree\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action      : 'patch',
            path        : f.path,
            expectedHash: f.hash,
            replacements: [[startLine: 2, endLine: 2, newText: 'TWO']]
        ], 'test')

        then:
        assert ok(r), text(r)
        content(f.path) == 'one\nTWO\nthree\n'
    }

    def 'TLH-3: the promoted hash is still checked -- a stale top-level hash is refused and the file is unchanged'() {
        given:
        def f = writeFile('tlh-3.txt', 'keep\n')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action      : 'multi_replace',
            path        : f.path,
            expectedHash: 'deadbeef0000',
            replacements: [[oldText: 'keep', newText: 'lost']]
        ], 'test')

        then:
        !ok(r)
        content(f.path) == 'keep\n'
    }
}
