package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.ChunkBufferService
import com.softwood.mcp.service.PathService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * FileChunkWriter - handles chunk_write, finalise_write, abort_write actions.
 *
 * v0.7.44 - extracted from FileWriteService as part of write/ subpackage split.
 */
@Service
@Slf4j
@CompileStatic
class FileChunkWriter extends AbstractFileService {

    @Autowired
    ChunkBufferService chunkBufferService

    FileChunkWriter(PathService pathService) {
        super(pathService)
    }

    McpResponse doChunkWrite(String path, String content, Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        // @CompileStatic guard: (Integer) ?: 0 crashes when value is 0 (falsy) — use explicit null check
        Integer chunkIndexBoxed = options.chunkIndex as Integer
        int chunkIndex = (chunkIndexBoxed != null) ? chunkIndexBoxed.intValue() : 0
        if (!sessionId) return McpResponse.error(requestId, -32602, 'options.sessionId required for chunk_write')
        if (!content)   return McpResponse.error(requestId, -32602, 'content required for chunk_write')

        int received = chunkBufferService.receiveWriteChunk(sessionId, chunkIndex, content)
        log.debug("chunk_write: session={}, index={}, received={}", sessionId, chunkIndex, received)

        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, chunkIndex: chunkIndex, received: received])
        }
        return textResponse(requestId, [
            action    : 'chunk_write',
            sessionId : sessionId,
            chunkIndex: chunkIndex,
            received  : received,
            success   : true
        ])
    }

    McpResponse doFinaliseWrite(String path, Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        // @CompileStatic guard: Integer ?: 0 triggers IntRange resolution — use explicit null check
        Integer totalChunksBoxed = options.totalChunks as Integer
        int totalChunks = (totalChunksBoxed != null) ? totalChunksBoxed.intValue() : 0
        if (!sessionId)      return McpResponse.error(requestId, -32602, 'options.sessionId required for finalise_write')
        if (totalChunks < 1) return McpResponse.error(requestId, -32602, 'options.totalChunks required for finalise_write')

        String normalized = normalizeAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean mkdirs    = options.mkdirs as boolean ?: true

        Collection<String> chunks = chunkBufferService.getWriteChunksAndRelease(sessionId, totalChunks)

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)
        if (backup && Files.exists(target)) WriteUtils.makeBackup(target)

        Path tmp          = target.resolveSibling(target.fileName.toString() + '.tmp')
        long totalWritten = 0L
        try {
            tmp.withWriter(encoding) { BufferedWriter w ->
                chunks.each { String chunk ->
                    w.write(chunk)
                    totalWritten += chunk.length()
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp) } catch (Exception ignored) {}
            throw e
        }
        log.info("finalise_write: streamed {}B to {} from {} chunks (atomic)", totalWritten, normalized, totalChunks)

        String hash = WriteUtils.fileHash(Paths.get(normalized))
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash, file_content_hash: hash])
        }
        return textResponse(requestId, [
            action     : 'finalise_write',
            path       : normalized,
            totalChunks: totalChunks,
            size       : totalWritten,
            success    : true, content_hash: hash, file_content_hash: hash
        ])
    }

    McpResponse doAbortWrite(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        if (!sessionId) return McpResponse.error(requestId, -32602, 'options.sessionId required for abort_write')
        chunkBufferService.abortWriteSession(sessionId)
        return textResponse(requestId, [action: 'abort_write', sessionId: sessionId, success: true])
    }

    /**
     * FS-T8: chunk_status — returns which chunks have been received for a write session
     * and which are still missing. Call before finalise_write to verify all chunks arrived.
     * Returns ready:true only when receivedCount == totalChunks.
     */
    McpResponse doChunkStatus(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        // @CompileStatic guard: Integer ?: 0 triggers IntRange resolution — use explicit null check
        Integer totalChunksBoxed = options.totalChunks as Integer
        int totalChunks = (totalChunksBoxed != null) ? totalChunksBoxed.intValue() : 0
        if (!sessionId) return McpResponse.error(requestId, -32602, 'options.sessionId required for chunk_status')
        if (totalChunks < 1) return McpResponse.error(requestId, -32602, 'options.totalChunks required for chunk_status')

        Map<String, Object> status = chunkBufferService.getWriteChunkStatus(sessionId)
        if (status == null) {
            return McpResponse.error(requestId, -32602,
                "chunk_status: no write session found for sessionId='${sessionId}'. " +
                'Session may have expired (TTL 30 min) or was never started.')
        }

        List<Integer> received = new ArrayList<Integer>(status.receivedChunks as Collection<Integer>)
        List<Integer> expected = new ArrayList<Integer>()
        for (int i = 0; i < totalChunks; i++) expected.add(i)
        List<Integer> missing  = new ArrayList<Integer>()
        for (Integer idx : expected) { if (!received.contains(idx)) missing.add(idx) }

        Map<String, Object> result = [
            action        : 'chunk_status',
            sessionId     : sessionId,
            totalChunks   : totalChunks,
            receivedChunks: received,
            missingChunks : missing,
            ready         : missing.isEmpty()
        ] as Map<String, Object>
        log.debug('chunk_status: session={} received={}/{} missing={}', sessionId, received.size(), totalChunks, missing)
        return textResponse(requestId, result)
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private String normalizeAndCheckPath(String path) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        return normalized
    }
}
