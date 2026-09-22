package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS-LC-1 -- file_lifecycle copy/move honour the mkdirs default the schema promises.
 *
 * Found 2026-09-22 (session 2026-09-22-13-03) probing file_lifecycle with scratch files:
 * copy into a destination whose parent folder does not exist failed with an error whose
 * whole text was `src -> dst` -- a NoSuchFileException from Files.copy with no reason
 * string -- although the schema says `options.mkdirs=true` is the default and doCopy
 * reads as if it creates the parent. Passing mkdirs:true explicitly made no difference.
 * A flat copy worked, and a move into a parent created by hand worked, so the defect is
 * narrowly the parent-creation step.
 *
 * Asserted at the layer that decides: the file exists afterwards. A success flag is not
 * evidence (the append defect of 0.9.48 returned success:true over an unchanged file).
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FileLifecycleService -- FS-LC-1 mkdirs on copy and move')
class FileLifecycleMkdirsSpec extends Specification {

    @Autowired FileLifecycleService lifecycle

    @TempDir Path tempDir

    private static String payloadOf(McpResponse r) {
        def content = r?.result?.content
        return content ? (content[0].text as String) : ''
    }

    private McpResponse call(String action, String src, String dst, Map options = [:]) {
        return lifecycle.handleToolCall('file_lifecycle', [
            action : action,
            path   : src,
            dst    : dst,
            options: options
        ] as Map<String, Object>, "lc-${action}")
    }

    def 'FS-LC-1: copy into a missing parent creates it by default'() {
        given:
        Path src = tempDir.resolve('source.txt')
        src.text = 'payload'
        Path dst = tempDir.resolve('a/b/copied.txt')

        when:
        String out = payloadOf(call('copy', src.toString(), dst.toString()))

        then: 'the copy landed'
        Files.exists(dst)
        dst.text == 'payload'

        and:
        !out.contains('->')
    }

    def 'FS-LC-1b: move into a missing parent creates it by default'() {
        given:
        Path src = tempDir.resolve('movable.txt')
        src.text = 'moving'
        Path dst = tempDir.resolve('c/d/moved.txt')

        when:
        payloadOf(call('move', src.toString(), dst.toString()))

        then:
        Files.exists(dst)
        !Files.exists(src)
        dst.text == 'moving'
    }

    @Autowired FileWriteService writeService

    def 'FS-LC-1d: file_write append into a missing parent creates it (doAppend had the same trap)'() {
        given:
        Path dst = tempDir.resolve('g/h/appended.txt')

        when:
        String out = payloadOf(writeService.handleToolCall('file_write', [
            action : 'append',
            path   : dst.toString(),
            content: 'first line',
            options: [:]
        ] as Map<String, Object>, 'lc-append'))

        then:
        Files.exists(dst)
        dst.text.contains('first line')
        !out.contains('Failed to create parent')
    }

    def 'FS-LC-1e: chunked finalise_write into a missing parent creates it (FileChunkWriter had the same trap)'() {
        given:
        Path dst = tempDir.resolve('i/j/chunked.txt')
        String sid = 'lc-chunks-' + System.nanoTime()

        when:
        writeService.handleToolCall('file_write', [action: 'chunk_write', path: dst.toString(), content: 'part-0 ',
                                                    options: [sessionId: sid, chunkIndex: 0]] as Map<String, Object>, 'c0')
        writeService.handleToolCall('file_write', [action: 'chunk_write', path: dst.toString(), content: 'part-1',
                                                    options: [sessionId: sid, chunkIndex: 1]] as Map<String, Object>, 'c1')
        String out = payloadOf(writeService.handleToolCall('file_write', [action: 'finalise_write', path: dst.toString(),
                                                    options: [sessionId: sid, totalChunks: 2]] as Map<String, Object>, 'cf'))

        then:
        Files.exists(dst)
        dst.text == 'part-0 part-1'
        !out.contains('->')
    }

    def 'FS-LC-1c: mkdirs:false is honoured and the refusal says why'() {
        given:
        Path src = tempDir.resolve('nomk.txt')
        src.text = 'x'
        Path dst = tempDir.resolve('e/f/nomk.txt')

        when:
        String out = payloadOf(call('copy', src.toString(), dst.toString(), [mkdirs: false]))

        then: 'nothing was created, and the error names the missing parent rather than printing two paths'
        !Files.exists(dst)
        out.toLowerCase().contains('parent')
    }
}
