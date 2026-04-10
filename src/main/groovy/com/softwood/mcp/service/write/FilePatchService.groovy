package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.PathService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * FilePatchService - handles the patch action.
 *
 * Hardened patch (v0.7.5+): range validation, overlap detection, bottom-up apply,
 * atomic write, post-write verification, CRLF/LF preservation, drift guard.
 *
 * v0.7.44 - extracted from FileWriteService as part of write/ subpackage split.
 *           Includes WI2 fix: improved hash-mismatch error message.
 * v0.8.36 - recentPatches hint: warns when same file patched twice in <60s without
 *           a re-read between patches. Line numbers shift after every patch -- callers
 *           MUST re-read between sequential patches and use the returned content_hash
 *           as expectedHash. Best practice: pass ALL replacements in a single patch
 *           call rather than sequential calls.
 * v0.8.43 - FS-T2: boundary_warning emitted when endLine == last line of file.
 *           FS-T3 NOTE: server ALREADY applies replacements bottom-to-top regardless
 *           of supplied order. Caller line-number drift between turns is the real hazard
 *           -- always re-read (range/get_method) immediately before patching.
 */
@Service
@Slf4j
@CompileStatic
class FilePatchService extends AbstractFileService {

    /** Track recent patches to warn about sequential patching without intervening re-reads. */
    private final Map<String, Long> recentPatches = Collections.synchronizedMap(new LinkedHashMap<String, Long>())

    FilePatchService(PathService pathService) {
        super(pathService)
    }

    McpResponse doPatch(String path, String content, Map<String, Object> options, Object requestId) {
        String normalized   = normalizeAndCheckPath(path)
        String encoding     = options.encoding as String ?: 'UTF-8'
        boolean backup      = options.backup as boolean ?: false
        String expectedHash = options.expectedHash as String
        if (!expectedHash) {
            log.warn('doPatch called without expectedHash for {} \u2014 drift guard disabled. Caller should pass expectedHash from last read.', path)
        }

        List<Map<String, Object>> replacements = (options.replacements instanceof List)
            ? options.replacements as List<Map<String, Object>>
            : []

        if (!replacements) {
            // FS-T6: return as textResponse (not McpResponse.error) so DT renders the message
            // rather than showing the opaque "Tool execution failed" for -32602 codes.
            return textResponse(requestId, [
                error : 'patch requires options.replacements: [{startLine,endLine,newText}].',
                hint  : 'Use action=multi_replace for string-based edits, or supply a replacements array.'
            ])
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

        // ---- Optional drift guard ----
        if (expectedHash) {
            def md = java.security.MessageDigest.getInstance('SHA-256')
            String actualHash = md.digest(rawBytes).encodeHex().toString()[0..11]
            if (actualHash != expectedHash) {
                log.warn("patch: drift guard rejected '{}': expected hash {} but file is now {}", normalized, expectedHash, actualHash)
                return McpResponse.error(requestId, -32602,
                    "expectedHash mismatch: file has changed since your last read (expected ${expectedHash}, got ${actualHash}). " +
                    "Re-read the target lines with action=range or action=get_method to get the current content_hash, then retry.")
            }
            log.debug("patch: drift guard OK for '{}' (hash {})", normalized, actualHash)
        }

        String rawContent        = new String(rawBytes, encoding)
        boolean hasCrLf          = rawContent.contains('\r\n')
        String normalised        = rawContent.replace('\r\n', '\n').replace('\r', '\n')
        boolean hadTrailingNewline = normalised.endsWith('\n')
        String toSplit           = hadTrailingNewline ? normalised[0..-2] : normalised
        List<String> lines       = toSplit ? new ArrayList<String>(Arrays.asList(toSplit.split('\n', -1))) : [] as List<String>
        int originalLineCount    = lines.size()

        log.debug("patch: read {} content lines from '{}' (endings: {}, trailingNewline: {})",
            originalLineCount, normalized, hasCrLf ? 'CRLF' : 'LF', hadTrailingNewline)

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

        // FS-T2: Detect boundary patches (endLine == last line of file).
        // These are high-risk: the closing brace at EOF is often already present and
        // callers accidentally duplicate it by including it in newText.
        List<String> boundaryWarnings = []
        sorted.each { Map<String, Object> rep ->
            if ((rep.endLine as int) == originalLineCount) {
                boundaryWarnings << ("replacement [${rep.startLine}..${rep.endLine}] touches the last line of the file " +
                    "(boundary patch). Verify newText does NOT duplicate the class closing brace or EOF line. " +
                    "Re-read lines ${Math.max(1, (rep.startLine as int) - 2)}..${originalLineCount} to confirm brace ownership." as String)
                log.warn("patch: boundary patch detected on '{}' at line {}", normalized, rep.endLine)
            }
        }

        // ---- Phase 2: Apply bottom-up ----
        int applied       = 0
        int expectedDelta = 0
        sorted.each { Map<String, Object> rep ->
            int start    = (rep.startLine as int) - 1
            int end      = (rep.endLine   as int) - 1
            String newText       = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
            List<String> newLines = newText ? new ArrayList<String>(Arrays.asList(newText.split('\n', -1))) : [] as List<String>
            int removed  = end - start + 1
            int added    = newLines.size()
            lines[start..end] = newLines
            expectedDelta += (added - removed)
            applied++
            log.debug("patch: applied [{}..{}] -> {} lines (net delta {})", start + 1, end + 1, added, added - removed)
        }

        int expectedResultLines = originalLineCount + expectedDelta

        // ---- Phase 3: Atomic write ----
        Path targetPath   = Paths.get(normalized)
        String lineEnding = (hasCrLf && !WriteUtils.shouldNormaliseLf(targetPath)) ? '\r\n' : '\n'
        String assembled  = lines.join(lineEnding) + (hadTrailingNewline ? lineEnding : '')
        byte[] resultBytes = assembled.getBytes(encoding)
        try {
            if (backup) WriteUtils.makeBackup(targetPath)
            WriteUtils.atomicWrite(targetPath, resultBytes)
            log.debug("patch: atomic write succeeded for '{}'", normalized)
        } catch (Exception e) {
            log.error("patch: write failed for '{}': {}", normalized, sanitize(e.message))
            return McpResponse.error(requestId, -32603,
                "patch: write failed (original file untouched): ${sanitize(e.message)}")
        }

        // ---- Phase 4: Post-write verification ----
        String verifyError = null
        int verifiedLines  = -1
        try {
            String written     = new String(resultBytes, encoding)
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

        String resultHash = null
        try { resultHash = WriteUtils.computeHash(resultBytes) } catch (Exception ignored) {}

        Map<String, Object> result = [
            action        : 'patch',
            path          : normalized,
            success       : (verifyError == null),
            applied       : applied,
            original_lines: originalLineCount,
            result_lines  : expectedResultLines,
            content_hash  : resultHash,
            file_content_hash: resultHash
        ] as Map<String, Object>
        if (verifyError) result.put('verify_warning', verifyError)
        if (boundaryWarnings) result.put('boundary_warning', boundaryWarnings.join(' | '))

        // Warn if same file patched twice within 60s without a re-read between patches.
        // Line numbers shift after every patch -- sequential patches without re-reading
        // cause exactly the kind of file corruption seen in practice #217.
        long now = System.currentTimeMillis()
        boolean shouldHint = recentPatches.containsKey(normalized) && (now - recentPatches.get(normalized)) < 60_000L
        recentPatches.put(normalized, now)
        if (shouldHint) {
            String hint = 'CAUTION: this file was patched recently -- line numbers have shifted. ' +
                'Re-read with action=range or get_method and use the returned content_hash as expectedHash before next patch. ' +
                'For multiple changes, pass all replacements[] in a SINGLE patch call -- never sequential patches across turns.'
            result.put('hint', hint)
            log.warn('patch: sequential patch detected on {} -- caller should re-read between patches', normalized)
        }

        if (isWriteCompact(options)) {
            Map<String, Object> compact = [success: result.success, applied: applied, content_hash: resultHash, file_content_hash: resultHash] as Map<String, Object>
            if (verifyError) compact.put('verify_warning', verifyError)
            if (shouldHint) compact.put('hint', result.get('hint'))
            if (boundaryWarnings) compact.put('boundary_warning', result.get('boundary_warning'))
            return textResponse(requestId, compact)
        }
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
