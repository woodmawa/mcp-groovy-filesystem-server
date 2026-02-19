package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

/**
 * FileWriteService — handles the file_write tool.
 *
 * actions: write | append | replace | patch | multi_replace |
 *          chunk_write | finalise_write | abort_write
 *
 * Chunked write flow:
 *   1. Call chunk_write with sessionId, chunkIndex, totalChunks, content for each chunk
 *   2. Call finalise_write with sessionId, totalChunks, path to assemble and write to disk
 *   (abort_write discards a session without writing)
 *
 * v0.0.7 — Phase 2 Core File Tools
 */
@Service
@Slf4j
@CompileStatic
class FileWriteService extends AbstractFileService implements ToolHandler {

    @Autowired
    ChunkBufferService chunkBufferService

    FileWriteService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : 'file_write',
            description: '''\
Write, append, or modify file content. Actions:
- write: write (or overwrite) entire file content
- append: append content to end of file
- replace: replace a unique string in a file (must appear exactly once)
- patch: apply multiple line-based patches
- multi_replace: apply a list of {oldText, newText} replacements in sequence
- chunk_write: send one chunk of a large write session
- finalise_write: assemble all chunks and write to disk
- abort_write: discard a chunk_write session without writing''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['write','append','replace','patch','multi_replace',
                                     'chunk_write','finalise_write','abort_write']],
                    path   : [type: 'string', description: 'Target file path'],
                    content: [type: 'string', description: 'Content for write/append/chunk_write'],
                    options: [type: 'object', description: 'Action-specific options',
                              properties: [
                                  encoding    : [type: 'string', description: 'File encoding (default UTF-8)'],
                                  backup      : [type: 'boolean', description: 'Create .backup file before writing'],
                                  mkdirs      : [type: 'boolean', description: 'Create parent directories if needed'],
                                  sessionId   : [type: 'string',  description: 'Chunk session identifier'],
                                  chunkIndex  : [type: 'integer', description: 'Chunk index (0-based)'],
                                  totalChunks : [type: 'integer', description: 'Total chunk count for finalise_write'],
                                  oldText     : [type: 'string',  description: 'Text to replace (must be unique) for replace action'],
                                  newText     : [type: 'string',  description: 'Replacement text for replace action'],
                                  replacements: [type: 'array',   description: 'List of {oldText, newText} for multi_replace',
                                                 items: [type: 'object', properties: [oldText: [type: 'string'], newText: [type: 'string']]]]
                              ]]
                ],
                required  : ['action', 'path']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == 'file_write' }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            validateWriteEnabled()

            String action  = arguments.action as String
            String path    = arguments.path as String
            String content = arguments.content as String
            Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>

            switch (action) {
                case 'write'         : return doWrite(path, content, options, requestId)
                case 'append'        : return doAppend(path, content, options, requestId)
                case 'replace'       : return doReplace(path, options, requestId)
                case 'patch'         : return doPatch(path, content, options, requestId)
                case 'multi_replace' : return doMultiReplace(path, options, requestId)
                case 'chunk_write'   : return doChunkWrite(path, content, options, requestId)
                case 'finalise_write': return doFinaliseWrite(path, options, requestId)
                case 'abort_write'   : return doAbortWrite(options, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown file_write action: ${action}")
            }
        } catch (SecurityException e) {
            log.warn("Security violation in file_write: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (Exception e) {
            log.error("file_write error: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Write actions
    // -----------------------------------------------------------------------

    private McpResponse doWrite(String path, String content, Map<String, Object> options, Object requestId) {
        String normalized = normalizAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean backup    = options.backup as boolean ?: false
        boolean mkdirs    = options.mkdirs as boolean ?: true

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)
        if (backup && Files.exists(target)) makeBackup(target)

        new File(normalized).setText(content ?: '', encoding)
        log.info("Wrote {} bytes to {}", content?.length() ?: 0, normalized)

        return textResponse(requestId, [action: 'write', path: normalized, size: content?.length() ?: 0, success: true])
    }

    private McpResponse doAppend(String path, String content, Map<String, Object> options, Object requestId) {
        String normalized = normalizAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean mkdirs    = options.mkdirs as boolean ?: true

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)

        new File(normalized).append(content ?: '', encoding)
        log.debug("Appended {} bytes to {}", content?.length() ?: 0, normalized)

        return textResponse(requestId, [action: 'append', path: normalized, appended: content?.length() ?: 0, success: true])
    }

    private McpResponse doReplace(String path, Map<String, Object> options, Object requestId) {
        String oldText = options.oldText as String
        String newText = options.newText as String ?: ''
        if (!oldText) return McpResponse.error(requestId, -32602, "options.oldText required for replace")

        String normalized = normalizAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        File file = new File(normalized)
        String current = file.getText(encoding)

        // Count occurrences to enforce uniqueness
        int count = countOccurrences(current, oldText)
        if (count == 0) {
            return McpResponse.error(requestId, -32602,
                "replace: oldText not found in file. Check exact whitespace/newlines.")
        }
        if (count > 1) {
            return McpResponse.error(requestId, -32602,
                "replace: oldText appears ${count} times (must be unique). Provide more context.")
        }

        if (backup) makeBackup(Paths.get(normalized))
        String updated = current.replace(oldText, newText)
        file.setText(updated, encoding)

        log.debug("Replaced 1 occurrence in {}", normalized)
        return textResponse(requestId, [action: 'replace', path: normalized, replacements: 1, success: true])
    }

    private McpResponse doMultiReplace(String path, Map<String, Object> options, Object requestId) {
        List<Map<String, Object>> replacements = (options.replacements as List<Map<String, Object>>) ?: []
        if (!replacements) return McpResponse.error(requestId, -32602, "options.replacements required for multi_replace")

        String normalized = normalizAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        File file = new File(normalized)
        if (backup) makeBackup(Paths.get(normalized))

        String current = file.getText(encoding)
        int applied = 0
        List<String> errors = []

        replacements.each { Map<String, Object> rep ->
            String oldText = rep.oldText as String
            String newText = rep.newText as String ?: ''
            if (!oldText) { errors << "Skipped entry with missing oldText".toString(); return }

            int count = countOccurrences(current, oldText)
            if (count == 0) { errors << "oldText not found: '${sanitize(oldText.take(50))}'".toString(); return }
            if (count > 1)  { errors << "oldText not unique (${count} occurrences): '${sanitize(oldText.take(50))}'".toString(); return }

            current = current.replace(oldText, newText)
            applied++
        }

        file.setText(current, encoding)
        log.info("multi_replace: {} applied, {} errors in {}", applied, errors.size(), normalized)

        return textResponse(requestId, [
            action    : 'multi_replace', path: normalized,
            applied   : applied, errors: errors,
            success   : errors.isEmpty()
        ])
    }

    private McpResponse doPatch(String path, String content, Map<String, Object> options, Object requestId) {
        // Simple line-based patch: content is a unified-diff-style description handled as direct replacement blocks
        // For v0.0.7 this is a thin wrapper around multi_replace accepting inline content
        // Full unified diff parsing deferred to a later enhancement
        String normalized = normalizAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean backup    = options.backup as boolean ?: false

        if (backup) makeBackup(Paths.get(normalized))
        new File(normalized).setText(content ?: '', encoding)

        log.info("Patch (full replace) applied to {}", normalized)
        return textResponse(requestId, [action: 'patch', path: normalized, success: true,
                                        note: 'v0.0.7 patch = full content replace; unified diff parsing planned'])
    }

    // -----------------------------------------------------------------------
    // Chunked write
    // -----------------------------------------------------------------------

    private McpResponse doChunkWrite(String path, String content, Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        int chunkIndex   = (options.chunkIndex as Integer) ?: 0
        if (!sessionId) return McpResponse.error(requestId, -32602, "options.sessionId required for chunk_write")
        if (!content)   return McpResponse.error(requestId, -32602, "content required for chunk_write")

        int received = chunkBufferService.receiveWriteChunk(sessionId, chunkIndex, content)
        log.debug("chunk_write: session={}, index={}, received={}", sessionId, chunkIndex, received)

        return textResponse(requestId, [
            action    : 'chunk_write',
            sessionId : sessionId,
            chunkIndex: chunkIndex,
            received  : received,
            success   : true
        ])
    }

    private McpResponse doFinaliseWrite(String path, Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        int totalChunks  = (options.totalChunks as Integer) ?: 0
        if (!sessionId)    return McpResponse.error(requestId, -32602, "options.sessionId required for finalise_write")
        if (totalChunks < 1) return McpResponse.error(requestId, -32602, "options.totalChunks required for finalise_write")

        String normalized = normalizAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean mkdirs    = options.mkdirs as boolean ?: true

        String assembled = chunkBufferService.finaliseWrite(sessionId, totalChunks)

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)
        if (backup && Files.exists(target)) makeBackup(target)

        new File(normalized).setText(assembled, encoding)
        log.info("finalise_write: wrote {}B to {} from {} chunks", assembled.length(), normalized, totalChunks)

        return textResponse(requestId, [
            action     : 'finalise_write',
            path       : normalized,
            totalChunks: totalChunks,
            size       : assembled.length(),
            success    : true
        ])
    }

    private McpResponse doAbortWrite(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        if (!sessionId) return McpResponse.error(requestId, -32602, "options.sessionId required for abort_write")
        chunkBufferService.abortWriteSession(sessionId)
        return textResponse(requestId, [action: 'abort_write', sessionId: sessionId, success: true])
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String normalizAndCheckPath(String path) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        return normalized
    }

    private static void makeBackup(Path path) {
        if (Files.exists(path)) {
            Path backup = Paths.get("${path}.backup")
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static int countOccurrences(String text, String target) {
        int count = 0
        int idx   = 0
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++
            idx += target.length()
        }
        return count
    }
}
