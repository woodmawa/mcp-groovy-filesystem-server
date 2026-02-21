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
 * FileWriteService  handles the file_write tool.
 *
 * actions: write | append | replace | patch | multi_replace |
 *          chunk_write | finalise_write | abort_write
 *
 * Chunked write flow:
 *   1. Call chunk_write with sessionId, chunkIndex, totalChunks, content for each chunk
 *   2. Call finalise_write with sessionId, totalChunks, path to assemble and write to disk
 *   (abort_write discards a session without writing)
 *
 * v0.0.7  Phase 2 Core File Tools
 * v0.7.2p  doPatch hardened: overlap detection, atomic temp-file write, post-write verification
 * v0.7.3   doFinaliseWrite hardened: atomic temp-file + rename (P1 from Opus assessment)
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
- write(path, content): overwrite entire file
- append(path, content): append to end of file
- replace(path, options.oldText, options.newText): replace ONE unique string
- patch(path, options.replacements[]): line-range edits [{startLine,endLine,newText}], 1-indexed.
  CRITICAL: ALL line numbers from current file state; ranges validated atomically before any write;
  send all patches for one file in a SINGLE call ordered top-to-bottom. Preserves CRLF/LF.
- multi_replace(path, options.replacements[]): ordered [{oldText,newText}] string replacements
- chunk_write(path, content, options.sessionId, options.chunkIndex): buffer one large-content chunk
- finalise_write(path, options.sessionId, options.totalChunks): assemble chunks and write to disk
- abort_write(options.sessionId): discard buffered chunks without writing. NOTE: path is ignored for this action - pass any dummy value.
USE write for full-file replacement, patch for targeted line edits, replace for unique-string swaps.''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['write','append','replace','patch','multi_replace',
                                     'chunk_write','finalise_write','abort_write']],
                    path   : [type: 'string', description: 'Target file path (required for all except abort_write)'],
                    content: [type: 'string', description: 'Content for write/append/chunk_write'],
                    options: [type: 'object', description: 'Action-specific options',
                              properties: [
                                  encoding    : [type: 'string',  description: 'File encoding (default UTF-8)'],
                                  backup      : [type: 'boolean', description: 'Create .backup file before writing (default false)'],
                                  mkdirs      : [type: 'boolean', description: 'Create parent dirs if needed (default true)'],
                                  sessionId   : [type: 'string',  description: 'Chunk session ID (required for chunk_write, finalise_write, abort_write)'],
                                  chunkIndex  : [type: 'integer', description: 'Chunk index 0-based (required for chunk_write)'],
                                  totalChunks : [type: 'integer', description: 'Total chunks (required for finalise_write)'],
                                  oldText     : [type: 'string',  description: 'Unique string to replace (required for replace - must appear exactly once)'],
                                  newText     : [type: 'string',  description: 'Replacement string (required for replace)'],
                                  replacements: [type: 'array',
                                                 description: 'patch: [{startLine,endLine,newText}] 1-indexed; multi_replace: [{oldText,newText}]',
                                                 items: [type: 'object', properties: [
                                                     oldText  : [type: 'string'],
                                                     newText  : [type: 'string'],
                                                     startLine: [type: 'integer'],
                                                     endLine  : [type: 'integer']
                                                 ]]]
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
        // Fix: strip incoming \r so newText doesn't introduce mixed endings into the LF-normalised content
        String newText = (options.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
        if (!oldText) return McpResponse.error(requestId, -32602, "options.oldText required for replace")

        String normalized = normalizAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        // Read raw bytes to detect line-ending style, then decode to String
        byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))
        String rawContent = new String(rawBytes, encoding)
        boolean hasCrLf   = rawContent.contains('\r\n')
        // Normalise file content to LF for matching
        String current    = rawContent.replace('\r\n', '\n').replace('\r', '\n')

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
        // Restore original line endings before writing
        if (hasCrLf) updated = updated.replace('\n', '\r\n')
        Files.write(Paths.get(normalized), updated.getBytes(encoding))

        log.debug("replace: 1 occurrence in {} (line endings: {})", normalized, hasCrLf ? 'CRLF' : 'LF')
        return textResponse(requestId, [action: 'replace', path: normalized, replacements: 1, success: true])
    }

    private McpResponse doMultiReplace(String path, Map<String, Object> options, Object requestId) {
        List<Map<String, Object>> replacements = (options.replacements as List<Map<String, Object>>) ?: []
        if (!replacements) return McpResponse.error(requestId, -32602, "options.replacements required for multi_replace")

        String normalized = normalizAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        if (backup) makeBackup(Paths.get(normalized))

        // Read raw bytes to detect line-ending style, then decode to String
        byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))
        String rawContent = new String(rawBytes, encoding)
        boolean hasCrLf   = rawContent.contains('\r\n')
        // Normalise file content to LF for matching
        String current    = rawContent.replace('\r\n', '\n').replace('\r', '\n')

        int applied = 0
        List<String> errors = []

        replacements.each { Map<String, Object> rep ->
            String oldText = rep.oldText as String
            // Fix: strip incoming \r so newText doesn't introduce mixed endings into LF-normalised content
            String newText = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
            if (!oldText) { errors << "Skipped entry with missing oldText".toString(); return }

            int count = countOccurrences(current, oldText)
            if (count == 0) { errors << "oldText not found: '${sanitize(oldText.take(50))}'".toString(); return }
            if (count > 1)  { errors << "oldText not unique (${count} occurrences): '${sanitize(oldText.take(50))}'".toString(); return }

            current = current.replace(oldText, newText)
            applied++
        }

        // Restore original line endings before writing
        if (hasCrLf) current = current.replace('\n', '\r\n')
        Files.write(Paths.get(normalized), current.getBytes(encoding))
        log.info("multi_replace: {} applied, {} errors in {} (line endings: {})",
            applied, errors.size(), normalized, hasCrLf ? 'CRLF' : 'LF')

        return textResponse(requestId, [
            action : 'multi_replace', path: normalized,
            applied: applied, errors: errors,
            success: errors.isEmpty()
        ])
    }

    private McpResponse doPatch(String path, String content, Map<String, Object> options, Object requestId) {
        // HARDENED patch (v0.7.2q):
        //   Phase 1  : validate all ranges (bounds + newText present)
        //              Line count is the count of REAL content lines (trailing newline stripped
        //              before split so a 10-line file with trailing \n gives 10, not 11).
        //              hadTrailingNewline is preserved and restored on reassembly.
        //   Phase 1b : detect overlapping ranges (would silently corrupt without this)
        //   Phase 2  : apply bottom-up on in-memory list (original untouched until Phase 3)
        //              incoming \r stripped from newText before splitting into lines
        //   Phase 3  : atomic write via Files.write() to .patch_tmp + Files.move/rename
        //              -> original file is never touched if write or rename fails
        //   Phase 4  : post-write verification (re-read, confirm expected line count using
        //              same trailing-newline-aware counting)
        //              -> logs ERROR + sets success=false + adds verify_warning in response
        //   CRLF/LF detected on read and restored on write
        String normalized = normalizAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean backup    = options.backup as boolean ?: false

        List<Map<String, Object>> replacements = (options.replacements instanceof List)
            ? options.replacements as List<Map<String, Object>>
            : []

        if (!replacements) {
            return McpResponse.error(requestId, -32602,
                'patch requires options.replacements: [{startLine,endLine,newText}]. ' +
                'Use write for full-file replacement, multi_replace for string-based edits.')
        }

        log.debug("patch: starting on '{}' with {} replacement(s)", normalized, replacements.size())

        // ---- Read file + detect line endings ----
        byte[] rawBytes
        try {
            rawBytes = Files.readAllBytes(Paths.get(normalized))
        } catch (Exception e) {
            log.error("patch: failed to read '{}': {}", normalized, sanitize(e.message))
            return McpResponse.error(requestId, -32603, "patch: could not read file: ${sanitize(e.message)}")
        }

        String rawContent    = new String(rawBytes, encoding)
        boolean hasCrLf      = rawContent.contains('\r\n')
        // Normalise to LF; also strip lone \r (old Mac)
        String normalised    = rawContent.replace('\r\n', '\n').replace('\r', '\n')

        // Fix Bug 1: track whether file had a trailing newline and strip the phantom empty
        // element it would produce from split('\n', -1), so line numbers are 1-based over
        // real content lines only.
        boolean hadTrailingNewline = normalised.endsWith('\n')
        String toSplit             = hadTrailingNewline ? normalised[0..-2] : normalised
        List<String> lines         = toSplit ? toSplit.split('\n', -1).toList() : [] as List<String>
        int originalLineCount      = lines.size()

        log.debug("patch: read {} content lines from '{}' (endings: {}, trailingNewline: {})",
            originalLineCount, normalized, hasCrLf ? 'CRLF' : 'LF', hadTrailingNewline)

        // Sort descending by startLine for bottom-up application
        List<Map<String, Object>> sorted = replacements.sort(false) { Map a, Map b ->
            (b.startLine as int) <=> (a.startLine as int)
        }

        // ---- Phase 1: Validate all ranges ----
        List<String> errors = []
        sorted.each { Map<String, Object> rep ->
            int start = rep.startLine as int
            int end   = rep.endLine   as int
            if (start < 1 || end < start || start > originalLineCount || end > originalLineCount) {
                errors << ("Invalid range [${start}..${end}] - file has ${originalLineCount} lines" as String)
            }
            if (!rep.containsKey('newText')) {
                errors << ("Missing newText for range [${start}..${end}]" as String)
            }
        }

        // ---- Phase 1b: Detect overlapping ranges ----
        if (!errors) {
            List<Map<String, Object>> ascSorted = replacements.sort(false) { Map a, Map b ->
                (a.startLine as int) <=> (b.startLine as int)
            }
            for (int i = 0; i < ascSorted.size() - 1; i++) {
                int endI   = ascSorted[i].endLine as int
                int startJ = ascSorted[i + 1].startLine as int
                if (endI >= startJ) {
                    errors << ("Overlapping ranges: [${ascSorted[i].startLine}..${endI}] and [${startJ}..${ascSorted[i + 1].endLine}]" as String)
                }
            }
        }

        if (errors) {
            log.warn("patch: validation failed on '{}': {}", normalized, errors.join('; '))
            return McpResponse.error(requestId, -32602,
                "patch validation failed (file NOT modified): ${errors.join('; ')}" as String)
        }

        // ---- Phase 2: Apply bottom-up (sorted descending already) ----
        int applied       = 0
        int expectedDelta = 0
        sorted.each { Map<String, Object> rep ->
            int start     = (rep.startLine as int) - 1   // convert to 0-indexed
            int end       = (rep.endLine   as int) - 1
            // Strip \r from incoming newText before splitting — prevents mixed endings
            String newText        = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
            List<String> newLines = newText ? newText.split('\n', -1).toList() : [] as List<String>
            int removed   = end - start + 1
            int added     = newLines.size()
            lines[start..end] = newLines
            expectedDelta += (added - removed)
            applied++
            log.debug("patch: applied [{}..{}] -> {} lines (net delta {})", start + 1, end + 1, added, added - removed)
        }

        // expectedResultLines counts real content lines, same basis as originalLineCount
        int expectedResultLines = originalLineCount + expectedDelta

        // ---- Phase 3: Atomic write via temp file + rename ----
        // Reassemble: join content lines, then restore trailing newline if file had one
        String lineEnding = hasCrLf ? '\r\n' : '\n'
        String assembled  = lines.join(lineEnding) + (hadTrailingNewline ? lineEnding : '')
        Path targetPath   = Paths.get(normalized)
        Path tempPath     = Paths.get("${normalized}.patch_tmp")

        try {
            if (backup) makeBackup(targetPath)
            // Fix Bug 3: use Files.write (bytes) instead of File.setText for consistency
            Files.write(tempPath, assembled.getBytes(encoding))
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            log.debug("patch: atomic rename succeeded for '{}'", normalized)
        } catch (Exception e) {
            try { Files.deleteIfExists(tempPath) } catch (Exception ignored) {}
            log.error("patch: write/rename failed for '{}': {}", normalized, sanitize(e.message))
            return McpResponse.error(requestId, -32603,
                "patch: write failed (original file untouched): ${sanitize(e.message)}")
        }

        // ---- Phase 4: Post-write verification ----
        // Verify using same trailing-newline-aware line count
        String verifyError = null
        int verifiedLines  = -1
        try {
            String written     = new String(Files.readAllBytes(targetPath), encoding)
            String normWritten = written.replace('\r\n', '\n').replace('\r', '\n')
            boolean writtenHasTrailing = normWritten.endsWith('\n')
            String writtenToCount      = writtenHasTrailing ? normWritten[0..-2] : normWritten
            verifiedLines              = writtenToCount ? writtenToCount.split('\n', -1).length : 0
            if (verifiedLines != expectedResultLines) {
                verifyError = "line count mismatch: expected ${expectedResultLines}, file has ${verifiedLines}"
                log.error("patch: post-write verification FAILED on '{}': {}", normalized, verifyError)
            } else {
                log.info("patch: verified OK - {} content lines in '{}'", verifiedLines, normalized)
            }
        } catch (Exception e) {
            verifyError = "could not verify: ${sanitize(e.message)}"
            log.warn("patch: post-write verification skipped for '{}': {}", normalized, sanitize(e.message))
        }

        log.info("patch: {} replacement(s) on '{}' ({} -> {} lines, endings: {})",
            applied, normalized, originalLineCount, expectedResultLines, hasCrLf ? 'CRLF' : 'LF')

        Map<String, Object> result = [
            action        : 'patch',
            path          : normalized,
            success       : (verifyError == null),
            applied       : applied,
            original_lines: originalLineCount,
            result_lines  : expectedResultLines
        ] as Map<String, Object>
        if (verifyError) {
            result.put('verify_warning', verifyError)
        }
        return textResponse(requestId, result)
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
        if (!sessionId)      return McpResponse.error(requestId, -32602, "options.sessionId required for finalise_write")
        if (totalChunks < 1) return McpResponse.error(requestId, -32602, "options.totalChunks required for finalise_write")

        String normalized = normalizAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean mkdirs    = options.mkdirs as boolean ?: true

        String assembled = chunkBufferService.finaliseWrite(sessionId, totalChunks)

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)
        if (backup && Files.exists(target)) makeBackup(target)

        // Atomic write: temp file + rename so partial failures never zero the target
        Path tempPath = Paths.get("${normalized}.finalize_tmp")
        try {
            Files.write(tempPath, assembled.getBytes(encoding))
            Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (Exception e) {
            try { Files.deleteIfExists(tempPath) } catch (Exception ignored) {}
            throw e
        }
        log.info("finalise_write: wrote {}B to {} from {} chunks (atomic)", assembled.length(), normalized, totalChunks)

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
