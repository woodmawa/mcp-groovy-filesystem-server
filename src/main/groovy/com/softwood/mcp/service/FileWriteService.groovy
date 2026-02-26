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

    @Autowired
    StructureCache structureCache

    private static final Set<String> MUTATING_ACTIONS = ['write','append','replace','patch','multi_replace','finalise_write'] as Set

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
- append(path, content): append to end
- replace(path, options.oldText, options.newText): replace ONE unique string. Fails with nearest_match hint if not found.
- patch(path, options.replacements[]): line-range edits [{startLine,endLine,newText}], 1-indexed. ALWAYS read exact lines first.
- multi_replace(path, options.replacements[]): ordered [{oldText,newText}] swaps. Pre-validates ALL before writing.
- chunk_write/finalise_write/abort_write: chunked large-file writes (see options).
All mutating actions return content_hash. Pass options.expectedHash to reject edits if file changed since last read.''',
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
                                  expectedHash: [type: 'string',  description: 'Optional 12-char SHA-256 prefix from a prior read/write content_hash. Supported by patch, replace, and multi_replace. Rejects the edit if the file has changed since last read - prevents silent corruption in multi-step flows.'],

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
                                                 ]]],
                                  compact     : [type: 'boolean', description: 'Minimal response - returns only success+content_hash (default: true for all write actions)'],
                                  verbose     : [type: 'boolean', description: 'Set verbose:true to get full response with action/path/size/diagnostics (overrides compact default)']
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

            McpResponse response
            switch (action) {
                case 'write'         : response = doWrite(path, content, options, requestId); break
                case 'append'        : response = doAppend(path, content, options, requestId); break
                case 'replace'       : response = doReplace(path, options, requestId); break
                case 'patch'         : response = doPatch(path, content, options, requestId); break
                case 'multi_replace' : response = doMultiReplace(path, options, requestId); break
                case 'chunk_write'   : response = doChunkWrite(path, content, options, requestId); break
                case 'finalise_write': response = doFinaliseWrite(path, options, requestId); break
                case 'abort_write'   : return doAbortWrite(options, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown file_write action: ${action}")
            }
            // Invalidate structure cache for any mutating action that succeeded on a specific path
            if (path && MUTATING_ACTIONS.contains(action) && response.error == null) {
                try {
                    structureCache.invalidate(pathService.normalizePath(path))
                } catch (Exception ignored) {}
            }
            return response

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

    /** Compute a 12-char SHA-256 of the file at path after writing — mirrors FileReadService.fileHash(). */
    private static String fileHash(Path p) {
        try {
            def md = java.security.MessageDigest.getInstance('SHA-256')
            p.toFile().withInputStream { InputStream is ->
                byte[] buf = new byte[8192]; int read
                while ((read = is.read(buf)) != -1) md.update(buf, 0, read)
            }
            return (md.digest().encodeHex().toString() as String)[0..11]
        } catch (Exception ignored) { return null }
    }

    private McpResponse doWrite(String path, String content, Map<String, Object> options, Object requestId) {
        String normalized = normalizAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean backup    = options.backup as boolean ?: false
        boolean mkdirs    = options.mkdirs as boolean ?: true
        String body       = content ?: ''

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)
        if (backup && Files.exists(target)) makeBackup(target)

        atomicWrite(target, body.getBytes(encoding))
        log.info("write: {} bytes -> {} (atomic)", body.length(), normalized)

        String hash = fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'write', path: normalized,
            size: body.length(), success: true,
            content_hash: hash
        ])
    }

    private McpResponse doAppend(String path, String content, Map<String, Object> options, Object requestId) {
        String normalized = normalizAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean mkdirs    = options.mkdirs as boolean ?: true
        byte[] bytes      = (content ?: '').getBytes(encoding)

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)

        // Hardened: FileChannel + FileLock so concurrent agents writing to a shared
        // log/output file never interleave or corrupt each other's output.
        new java.io.RandomAccessFile(normalized, 'rw').withCloseable { java.io.RandomAccessFile raf ->
            raf.channel.lock().withCloseable {
                raf.seek(raf.length())
                raf.write(bytes)
            }
        }
        log.debug("append: {} bytes -> {} (locked)", bytes.length, normalized)

        String hash = fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'append', path: normalized,
            appended: bytes.length, success: true,
            content_hash: hash
        ])
    }

    private McpResponse doReplace(String path, Map<String, Object> options, Object requestId) {
        String oldText      = options.oldText as String
        String newText      = (options.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
        String expectedHash = options.expectedHash as String
        if (!oldText) return McpResponse.error(requestId, -32602, "options.oldText required for replace")

        String normalized = normalizAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))
        String rawContent = new String(rawBytes, encoding)
        boolean hasCrLf   = rawContent.contains('\r\n')
        String current    = rawContent.replace('\r\n', '\n').replace('\r', '\n')

        // Drift guard
        if (expectedHash) {
            String actualHash = computeHash(rawBytes)
            if (actualHash != expectedHash) {
                return McpResponse.error(requestId, -32602,
                    ("replace rejected: file has changed since last read (expected ${expectedHash}, got ${actualHash}). Re-read before retrying." as String))
            }
        }

        int count = countOccurrences(current, oldText)
        if (count == 0) {
            String firstLine = oldText.trim().tokenize('\n').first()?.trim() ?: ''
            String nearestContent = null
            int nearestLine = -1
            if (firstLine) {
                int bestScore = Integer.MAX_VALUE
                List<String> fileLines = new ArrayList<String>(Arrays.asList(current.split('\n', -1)))
                fileLines.eachWithIndex { String fl, int idx ->
                    String trimFl = fl.trim()
                    if (trimFl.contains(firstLine) || firstLine.contains(trimFl)) {
                        int score = Math.abs(trimFl.length() - firstLine.length())
                        if (score < bestScore) { bestScore = score; nearestContent = fl; nearestLine = idx + 1 }
                    }
                }
                if (nearestLine < 0 && firstLine.length() <= 80) {
                    fileLines.eachWithIndex { String fl, int idx ->
                        int common = fl.trim().toSet().intersect(firstLine.toSet()).size()
                        int score  = firstLine.length() - common
                        if (score < bestScore) { bestScore = score; nearestContent = fl; nearestLine = idx + 1 }
                    }
                }
            }
            Map<String, Object> err = [
                action: 'replace', success: false,
                error: 'oldText not found in file. Check exact whitespace/newlines.',
                line_endings: hasCrLf ? 'CRLF' : 'LF',
                oldText_first_line: firstLine.take(120)
            ] as Map<String, Object>
            if (nearestLine > 0) err.nearest_match = [line: nearestLine, content: nearestContent?.take(120)]
            return McpResponse.error(requestId, -32602, new groovy.json.JsonBuilder(err).toString())
        }
        if (count > 1) {
            return McpResponse.error(requestId, -32602,
                "replace: oldText appears ${count} times (must be unique). Provide more context.")
        }

        String updated = current.replace(oldText, newText)
        if (hasCrLf) updated = updated.replace('\n', '\r\n')

        Path target = Paths.get(normalized)
        if (backup) makeBackup(target)
        atomicWrite(target, updated.getBytes(encoding))

        log.debug("replace: 1 occurrence in {} (line endings: {})", normalized, hasCrLf ? 'CRLF' : 'LF')
        String hash = fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'replace', path: normalized,
            replacements: 1, success: true,
            content_hash: hash
        ])
    }

    private McpResponse doMultiReplace(String path, Map<String, Object> options, Object requestId) {
        List<Map<String, Object>> replacements = (options.replacements as List<Map<String, Object>>) ?: []
        if (!replacements) return McpResponse.error(requestId, -32602, "options.replacements required for multi_replace")

        String normalized   = normalizAndCheckPath(path)
        boolean backup      = options.backup as boolean ?: false
        String encoding     = options.encoding as String ?: 'UTF-8'
        String expectedHash = options.expectedHash as String

        byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))
        String rawContent = new String(rawBytes, encoding)
        boolean hasCrLf   = rawContent.contains('\r\n')
        String snapshot   = rawContent.replace('\r\n', '\n').replace('\r', '\n')

        // Drift guard
        if (expectedHash) {
            String actualHash = computeHash(rawBytes)
            if (actualHash != expectedHash) {
                return McpResponse.error(requestId, -32602,
                    ("multi_replace rejected: file has changed since last read (expected ${expectedHash}, got ${actualHash}). Re-read before retrying." as String))
            }
        }

        // Phase 1: pre-validate ALL replacements before touching the file
        List<String> validationErrors = []
        replacements.eachWithIndex { Map<String, Object> rep, int i ->
            String oldText = rep.oldText as String
            if (!oldText) { validationErrors << ("Entry ${i}: missing oldText" as String); return }
            int count = countOccurrences(snapshot, oldText)
            if (count == 0) validationErrors << ("Entry ${i}: oldText not found: '${sanitize(oldText.take(60))}'" as String)
            if (count > 1)  validationErrors << ("Entry ${i}: oldText not unique (${count} occurrences): '${sanitize(oldText.take(60))}'" as String)
        }
        if (validationErrors) {
            return McpResponse.error(requestId, -32602,
                ("multi_replace validation failed (file NOT modified): ${validationErrors.join('; ')}" as String))
        }

        // Phase 2: apply in order
        String current = snapshot
        int applied = 0
        replacements.each { Map<String, Object> rep ->
            String oldText = rep.oldText as String
            String newText = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
            current = current.replace(oldText, newText)
            applied++
        }

        Path target = Paths.get(normalized)
        if (backup) makeBackup(target)
        if (hasCrLf) current = current.replace('\n', '\r\n')
        atomicWrite(target, current.getBytes(encoding))
        log.info("multi_replace: {} applied in {} (line endings: {})", applied, normalized, hasCrLf ? 'CRLF' : 'LF')

        String hash = fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, applied: applied, content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'multi_replace', path: normalized,
            applied: applied, success: true,
            content_hash: hash
        ])
    }

    private McpResponse doPatch(String path, String content, Map<String, Object> options, Object requestId) {
        // HARDENED patch (v0.7.5+):
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
        //   expectedHash: optional 12-char SHA-256 prefix; rejects if file has drifted since last read
        String normalized   = normalizAndCheckPath(path)
        String encoding     = options.encoding as String ?: 'UTF-8'
        boolean backup      = options.backup as boolean ?: false
        String expectedHash = options.expectedHash as String  // optional drift guard

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

        // ---- Optional drift guard: reject if file has changed since caller last read it ----
        if (expectedHash) {
            def md = java.security.MessageDigest.getInstance('SHA-256')
            String actualHash = md.digest(rawBytes).encodeHex().toString()[0..11]
            if (actualHash != expectedHash) {
                log.warn("patch: drift guard rejected '{}': expected hash {} but file is now {}", normalized, expectedHash, actualHash)
                return McpResponse.error(requestId, -32602,
                    "patch rejected: file has changed since last read (expected hash ${expectedHash}, got ${actualHash}). Re-read the file before patching.")
            }
            log.debug("patch: drift guard OK for '{}' (hash {})", normalized, actualHash)
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
        List<String> lines         = toSplit ? new ArrayList<String>(Arrays.asList(toSplit.split('\n', -1))) : [] as List<String>
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
            List<String> newLines = newText ? new ArrayList<String>(Arrays.asList(newText.split('\n', -1))) : [] as List<String>
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
        Path targetPath = Paths.get(normalized)
        try {
            if (backup) makeBackup(targetPath)
            atomicWrite(targetPath, assembled.getBytes(encoding))
            log.debug("patch: atomic write succeeded for '{}'", normalized)
        } catch (Exception e) {
            log.error("patch: write failed for '{}': {}", normalized, sanitize(e.message))
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

        // Include content hash of result so callers can detect file drift before a subsequent patch
        String resultHash = null
        try {
            def md = java.security.MessageDigest.getInstance('SHA-256')
            resultHash = md.digest(Files.readAllBytes(targetPath)).encodeHex().toString()[0..11]
        } catch (Exception ignored) {}

        Map<String, Object> result = [
            action        : 'patch',
            path          : normalized,
            success       : (verifyError == null),
            applied       : applied,
            original_lines: originalLineCount,
            result_lines  : expectedResultLines,
            content_hash  : resultHash
        ] as Map<String, Object>
        if (verifyError) result.put('verify_warning', verifyError)

        if (isWriteCompact(options)) {
            Map<String, Object> compact = [success: result.success, applied: applied, content_hash: resultHash] as Map<String, Object>
            if (verifyError) compact.put('verify_warning', verifyError)  // always surface safety warnings
            return textResponse(requestId, compact)
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

        atomicWrite(target, assembled.getBytes(encoding))
        log.info("finalise_write: wrote {}B to {} from {} chunks (atomic)", assembled.length(), normalized, totalChunks)

        String hash = fileHash(Paths.get(normalized))
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash])
        }
        return textResponse(requestId, [
            action     : 'finalise_write',
            path       : normalized,
            totalChunks: totalChunks,
            size       : assembled.length(),
            success    : true, content_hash: hash
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

    /**
     * Atomic write helper - single source of truth for all write operations.
     * Writes bytes to a sibling temp file then renames atomically over the target.
     * Tries ATOMIC_MOVE first (guaranteed atomic on same filesystem); falls back to
     * REPLACE_EXISTING on cross-filesystem or unsupported cases.
     * Cleans up the temp file if anything goes wrong so we never leave stray .tmp files.
     */
    private static void atomicWrite(Path target, byte[] bytes) {
        Path tmp = target.resolveSibling(target.fileName.toString() + '.tmp')
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.ATOMIC_MOVE)
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp) } catch (Exception ignored) {}
            throw e
        }
    }

    /**
     * Compute a 12-char SHA-256 prefix of the given raw bytes.
     * Used for drift-guard validation - single implementation used by all write actions.
     */
    private static String computeHash(byte[] bytes) {
        (java.security.MessageDigest.getInstance('SHA-256')
            .digest(bytes).encodeHex().toString() as String)[0..11]
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
