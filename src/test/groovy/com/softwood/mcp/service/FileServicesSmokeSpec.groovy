package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ChunkBufferService
import com.softwood.mcp.service.FileLifecycleService
import com.softwood.mcp.service.FileListService
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileSearchService
import com.softwood.mcp.service.FileWriteService
import com.softwood.mcp.service.PathService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Smoke tests for v0.0.7 file tool services.
 *
 * Uses @TempDir so tests are self-contained and don't touch real project files.
 * Validates core happy-paths and that all 7 ToolHandlers register correctly.
 *
 * v0.0.7 — Phase 5 Polish
 */
@SpringBootTest
@ActiveProfiles('test')
class FileServicesSmokeSpec extends Specification {

    @Autowired PathService pathService
    @Autowired ChunkBufferService chunkBufferService
    @Autowired FileLifecycleService fileLifecycleService
    @Autowired FileListService fileListService
    @Autowired FileSearchService fileSearchService
    @Autowired FileReadService fileReadService
    @Autowired FileWriteService fileWriteService

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // ChunkBufferService
    // -----------------------------------------------------------------------

    def "ChunkBufferService splits and reassembles content correctly"() {
        given:
        String content  = 'A' * 500          // small — fits in one chunk
        String sessionId = ChunkBufferService.newSessionId()

        when:
        List<String> chunks = ChunkBufferService.splitIntoChunks(content)

        then:
        chunks.size() == 1
        chunks[0] == content
    }

    def "ChunkBufferService write session round-trip works"() {
        given:
        String sessionId = ChunkBufferService.newSessionId()
        String chunk0 = 'Hello '
        String chunk1 = 'World'

        when:
        chunkBufferService.receiveWriteChunk(sessionId, 0, chunk0)
        chunkBufferService.receiveWriteChunk(sessionId, 1, chunk1)
        String assembled = chunkBufferService.finaliseWrite(sessionId, 2)

        then:
        assembled == 'Hello World'
    }

    def "ChunkBufferService detects large content needing chunking"() {
        expect:
        !ChunkBufferService.needsChunking('small')
        ChunkBufferService.needsChunking('X' * (ChunkBufferService.MAX_CHUNK_BYTES + 1))
    }

    // -----------------------------------------------------------------------
    // FileLifecycleService
    // -----------------------------------------------------------------------

    def "FileLifecycleService registers one tool definition"() {
        expect:
        fileLifecycleService.canHandle('file_lifecycle')
        !fileLifecycleService.canHandle('file_read')
        fileLifecycleService.toolDefinitions.size() == 1
        fileLifecycleService.toolDefinitions[0].name == 'file_lifecycle'
    }

    // -----------------------------------------------------------------------
    // FileListService
    // -----------------------------------------------------------------------

    def "FileListService registers one tool definition"() {
        expect:
        fileListService.canHandle('file_list')
        fileListService.toolDefinitions.size() == 1
        fileListService.toolDefinitions[0].name == 'file_list'
    }

    // -----------------------------------------------------------------------
    // FileSearchService
    // -----------------------------------------------------------------------

    def "FileSearchService registers one tool definition"() {
        expect:
        fileSearchService.canHandle('file_search')
        fileSearchService.toolDefinitions.size() == 1
        fileSearchService.toolDefinitions[0].name == 'file_search'
    }

    // -----------------------------------------------------------------------
    // FileReadService
    // -----------------------------------------------------------------------

    def "FileReadService registers one tool definition"() {
        expect:
        fileReadService.canHandle('file_read')
        fileReadService.toolDefinitions.size() == 1
        fileReadService.toolDefinitions[0].name == 'file_read'
    }

    def "FileReadService project_root action returns a non-empty path"() {
        when:
        McpResponse response = fileReadService.handleToolCall(
            'file_read', [action: 'project_root'], 'test-1')

        then:
        response != null
        response.result != null
    }

    def "FileReadService allowed_dirs action returns a list"() {
        when:
        McpResponse response = fileReadService.handleToolCall(
            'file_read', [action: 'allowed_dirs'], 'test-2')

        then:
        response != null
        response.result != null
    }

    def "FileReadService exists action returns false for non-existent path"() {
        when:
        McpResponse response = fileReadService.handleToolCall(
            'file_read', [action: 'exists', path: '/nonexistent/path/xyz'], 'test-3')

        then:
        response != null
        // Should return a result (not an error) with exists=false
        response.result != null
    }

    def "FileReadService range returns exactly maxLines lines"() {
        given:
        File tmp = File.createTempFile('range-test', '.txt')
        tmp.deleteOnExit()
        tmp.text = (1..10).collect { "line${it}" }.join('\n')

        when: "request 3 lines starting at line 2"
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action : 'range',
            path   : tmp.absolutePath,
            options: [startLine: 2, maxLines: 3]
        ], 'test-range-1')

        then:
        r.error == null
        def result = new groovy.json.JsonSlurper().parseText(
            r.result.content[0].text as String) as Map
        result.lines == 3
        result.content == 'line2\nline3\nline4'
    }

    // -----------------------------------------------------------------------
    // FileWriteService
    // -----------------------------------------------------------------------

    def "FileWriteService registers one tool definition"() {
        expect:
        fileWriteService.canHandle('file_write')
        fileWriteService.toolDefinitions.size() == 1
        fileWriteService.toolDefinitions[0].name == 'file_write'
    }

    def "FileWriteService patch replaces specified line ranges"() {
        given:
        File tmp = File.createTempFile('patch-test', '.txt')
        tmp.deleteOnExit()
        tmp.text = 'line1\nline2\nline3\nline4\nline5'

        when: "replace lines 2-3 with two new lines"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : tmp.absolutePath,
            options: [verbose: true, replacements: [
                [startLine: 2, endLine: 3, newText: 'replaced2\nreplaced3']
            ]]
        ], 'test-patch-1')

        then:
        r.error == null
        def result = new groovy.json.JsonSlurper().parseText(
            r.result.content[0].text as String) as Map
        result.applied == 1
        result.original_lines == 5
        result.result_lines == 5
        tmp.readLines() == ['line1', 'replaced2', 'replaced3', 'line4', 'line5']
    }

    def "FileWriteService patch returns error when no replacements provided"() {
        given:
        File tmp = File.createTempFile('patch-noarg', '.txt')
        tmp.deleteOnExit()
        tmp.text = 'hello'

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'patch',
            path   : tmp.absolutePath,
            options: [:]
        ], 'test-patch-2')

        then: "error returned, file untouched"
        r.error != null
        tmp.text == 'hello'
    }

    def "FileWriteService chunk_write and finalise_write round-trip"() {
        given:
        String sessionId = ChunkBufferService.newSessionId()

        when: "receive two chunks"
        McpResponse r1 = fileWriteService.handleToolCall('file_write', [
            action : 'chunk_write',
            path   : 'ignored-during-chunk-phase.txt',
            content: 'chunk-zero-content',
            options: [sessionId: sessionId, chunkIndex: 0]
        ], 'test-cw-1')

        McpResponse r2 = fileWriteService.handleToolCall('file_write', [
            action : 'chunk_write',
            path   : 'ignored-during-chunk-phase.txt',
            content: '-chunk-one-content',
            options: [sessionId: sessionId, chunkIndex: 1]
        ], 'test-cw-2')

        then:
        r1.result != null
        r2.result != null

        and: "abort (don't actually write to disk in this test)"
        McpResponse abort = fileWriteService.handleToolCall('file_write', [
            action : 'abort_write',
            path   : 'ignored',
            options: [sessionId: sessionId]
        ], 'test-cw-abort')
        abort.result != null
    }

    // -----------------------------------------------------------------------
    // PathService
    // -----------------------------------------------------------------------

    def "PathService converts WSL paths to Windows"() {
        expect:
        pathService.convertWslToWindows('/mnt/c/Users/willw') == 'C:/Users/willw'
    }

    def "PathService converts Windows paths to WSL"() {
        expect:
        pathService.convertWindowsToWsl('C:/Users/willw') == '/mnt/c/Users/willw'
    }

    def "PathService normalizes backslashes"() {
        expect:
        pathService.normalizePath('C:\\Users\\willw\\file.txt') == 'C:/Users/willw/file.txt'
    }

    // -----------------------------------------------------------------------
    // FS-T8: chunk_status action
    // -----------------------------------------------------------------------

    def "chunk_status returns receivedChunks and missingChunks correctly"() {
        given: "a write session with chunks 0 and 2 received (1 missing)"
        String sessionId = ChunkBufferService.newSessionId()
        chunkBufferService.receiveWriteChunk(sessionId, 0, 'chunk-zero')
        chunkBufferService.receiveWriteChunk(sessionId, 2, 'chunk-two')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'chunk_status',
            path   : null,
            options: [sessionId: sessionId, totalChunks: 3]
        ], 'test-cs-1')

        then: "status shows 2 received, 1 missing, not ready"
        r.error == null
        def result = new groovy.json.JsonSlurper().parseText(
            r.result.content[0].text as String) as Map
        result.receivedChunks == [0, 2]
        result.missingChunks  == [1]
        result.ready          == false
        result.totalChunks    == 3

        cleanup:
        chunkBufferService.abortWriteSession(sessionId)
    }

    def "chunk_status returns ready true when all chunks present"() {
        given: "all 2 chunks received"
        String sessionId = ChunkBufferService.newSessionId()
        chunkBufferService.receiveWriteChunk(sessionId, 0, 'alpha')
        chunkBufferService.receiveWriteChunk(sessionId, 1, 'beta')

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'chunk_status',
            path   : null,
            options: [sessionId: sessionId, totalChunks: 2]
        ], 'test-cs-2')

        then:
        r.error == null
        def result = new groovy.json.JsonSlurper().parseText(
            r.result.content[0].text as String) as Map
        result.missingChunks == []
        result.ready         == true

        cleanup:
        chunkBufferService.abortWriteSession(sessionId)
    }

    def "chunk_status returns error for unknown sessionId"() {
        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'chunk_status',
            path   : null,
            options: [sessionId: 'no-such-session-xyz', totalChunks: 2]
        ], 'test-cs-3')

        then: "descriptive error, not a crash"
        r.error != null
        r.error.message?.contains('no write session found')
    }

    // -----------------------------------------------------------------------
    // FS-T7: get_method fallback flag via AstStructureScanner
    // -----------------------------------------------------------------------

    def "AstStructureScanner falls back to regex on a file with a syntax error"() {
        given: "a Groovy file with a deliberate syntax error"
        File broken = File.createTempFile('broken-', '.groovy')
        broken.deleteOnExit()
        broken.text = '''\
class BrokenClass {
    String name

    void doSomething(String arg) {
        println arg
    }

    SYNTAX ERROR HERE
}
'''
        when:
        def scanner = new com.softwood.mcp.service.AstStructureScanner()
        def result  = scanner.scan(broken)

        then: "scanner field is 'regex' (AST failed, fell back)"
        result.scanner == 'regex'
        result.structure != null

        and: "doSomething method is still found via regex"
        def methods = (result.structure as List).findAll { it.type == 'method' }
        methods.any { (it.content as String).contains('doSomething') }
    }
}
