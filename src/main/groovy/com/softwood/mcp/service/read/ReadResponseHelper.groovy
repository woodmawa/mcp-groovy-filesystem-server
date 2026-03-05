package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.ChunkBufferService
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.StructureCache
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * ReadResponseHelper - shared utilities for read sub-services.
 *
 * Provides: hash-gated re-read, session token metering, size warnings,
 * chunk_read / finalise_read delegation, and the line-count guard helper.
 *
 * v0.7.44 - extracted from FileReadService as part of read/ subpackage split.
 */
@Service
@Slf4j
@CompileStatic
class ReadResponseHelper extends AbstractFileService {

    @Autowired
    StructureCache structureCache

    @Autowired(required = false)
    FilesystemTelemetryService telemetryService

    @Autowired
    ChunkBufferService chunkBufferService

    @Value('${mcp.filesystem.large-response-warn-chars:15000}')
    int largeResponseWarnChars

    @Value('${mcp.filesystem.partial-read-cap-chars:12000}')
    int partialReadCapChars

    ReadResponseHelper(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // Size warning
    // -----------------------------------------------------------------------

    void maybeAddSizeWarning(Map<String, Object> response, int contentLength) {
        if (contentLength > largeResponseWarnChars) {
            long kb     = Math.round(contentLength / 1024.0f)
            long tokens = Math.round(contentLength / 4.0f)
            response._sizeWarning = ("NOTE: response is ${kb}KB (~${tokens} tokens). " +
                "Consider head/range/grep for targeted reads to preserve context window." as String)
        }
    }

    // -----------------------------------------------------------------------
    // Session token meter (FIX-B)
    // -----------------------------------------------------------------------

    void injectSessionTokenMeter(Map<String, Object> response, int contentLength) {
        if (telemetryService == null) return
        int sessionTokens = telemetryService.accumulateReadTokens(contentLength)
        int sessionCalls  = telemetryService.getSessionReadCalls()
        response._session_read_tokens = sessionTokens
        // Inject ratio health when degraded (silent on OK/UNKNOWN - avoids noise)
        Map<String, Object> health = telemetryService.getSessionHealthSummary()
        String healthStatus = health.healthStatus as String
        if (healthStatus == 'DEGRADED' || healthStatus == 'POOR') {
            response._session_health = ("${healthStatus}: file_read/ctx ratio=${health.fileToContextRatio} today" +
                " — run context_lifecycle start + context_read resume to improve" as String)
        }
        if (sessionTokens > 80000) {
            response._session_budget_warn = ("CRITICAL: ${sessionTokens} tokens burned on file reads this session (${sessionCalls} calls). " +
                "Context window at serious risk. STOP reading files - use only structure/get_method/grep from now on." as String)
        } else if (sessionTokens > 40000) {
            response._session_budget_warn = ("WARNING: ${sessionTokens} tokens burned on file reads this session (${sessionCalls} calls). " +
                "Switch to structure/get_method/range to avoid context overflow." as String)
        }
    }

    // -----------------------------------------------------------------------
    // Hash-gated re-read (FIX-D)
    // -----------------------------------------------------------------------

    McpResponse checkKnownHash(String normalized, Map<String, Object> options, Object requestId) {
        String knownHash = options.knownHash as String
        if (!knownHash) return null
        String currentHash = structureCache.getHash(normalized)
        if (currentHash == knownHash) {
            log.debug('hash-gate HIT: {} unchanged ({})', normalized, knownHash)
            return textResponse(requestId, [
                unchanged        : true,
                file_content_hash: currentHash,
                _note            : 'File unchanged since last read - reuse content from previous response.'
            ] as Map<String, Object>)
        }
        log.debug('hash-gate MISS: {} changed (known={}, current={})', normalized, knownHash, currentHash)
        return null
    }

    // -----------------------------------------------------------------------
    // Chunk read actions
    // -----------------------------------------------------------------------

    McpResponse doChunkRead(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        int chunkIndex   = (options.chunkIndex as Integer) ?: 0
        if (!sessionId) return McpResponse.error(requestId, -32602, 'options.sessionId required for chunk_read')

        String chunk = chunkBufferService.getReadChunk(sessionId, chunkIndex)

        // FIX-C1: cap individual chunk responses to partialReadCapChars
        boolean chunkTruncated = chunk != null && chunk.length() > partialReadCapChars
        if (chunkTruncated) chunk = chunk.substring(0, partialReadCapChars)

        Map<String, Object> resp = [action: 'chunk_read', sessionId: sessionId, chunkIndex: chunkIndex, content: chunk]
        if (chunkTruncated) {
            resp._truncated = true
            resp._truncatedNote = ("Chunk truncated at ${partialReadCapChars} chars (~${partialReadCapChars / 4000 as int}K tokens). " +
                "Increase specificity with head/range/grep rather than chunk_read for large files." as String)
        }
        return textResponse(requestId, resp)
    }

    McpResponse doFinaliseRead(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        if (!sessionId) return McpResponse.error(requestId, -32602, 'options.sessionId required for finalise_read')
        chunkBufferService.finaliseRead(sessionId)
        return textResponse(requestId, [action: 'finalise_read', sessionId: sessionId, success: true])
    }

    // -----------------------------------------------------------------------
    // Line-count guard helper (FIX-A)
    // -----------------------------------------------------------------------

    static int countLinesUpTo(String normalizedPath, int limit, String encoding) {
        int count = 0
        new File(normalizedPath).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            while (count <= limit && br.readLine() != null) count++
        }
        return count
    }
}
