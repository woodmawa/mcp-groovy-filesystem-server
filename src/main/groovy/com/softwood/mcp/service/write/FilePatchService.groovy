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
            log.warn('doPatch called without expectedHash for {} -- drift guard disabled. Caller should pass expectedHash from last read.', path)
        }

        List<Map<String, Object>> replacements = (options.replacements instanceof List)
            ? options.replacements as List<Map<String, Object>>
            : []

        if (!replacements) {
            return McpResponse.toolError(requestId,
                'patch requires options.replacements: [{startLine,endLine,newText}]. ' +
                'Use action=multi_replace for string-based edits, or supply a replacements array.')
        }

        log.debug('patch: starting on {} with {} replacement(s)', normalized, replacements.size())

        // ---- Read file + detect line endings ----
        byte[] rawBytes
        try {
            rawBytes = Files.readAllBytes(Paths.get(normalized))
        } catch (Exception e) {
            log.error('patch: failed to read {}: {}', normalized, sanitize(e.message))
            return McpResponse.toolError(requestId, 'patch: could not read file: ' + sanitize(e.message))
        }

        // ---- Optional drift guard ----
        if (expectedHash) {
            def md = java.security.MessageDigest.getInstance('SHA-256')
            String actualHash = md.digest(rawBytes).encodeHex().toString()[0..11]
            if (actualHash != expectedHash) {
                log.warn('patch: drift guard rejected {}: expected hash {} but file is now {}', normalized, expectedHash, actualHash)
                return McpResponse.toolError(requestId,
                    'expectedHash mismatch: file has changed since your last read (expected ' + expectedHash + ', got ' + actualHash + '). ' +
                    'Re-read the target lines with action=range or action=get_method to get the current content_hash, then retry.')
            }
            log.debug('patch: drift guard OK for {} (hash {})', normalized, actualHash)
        }

        String rawContent          = new String(rawBytes, encoding)
        boolean hasCrLf            = rawContent.contains('\r\n')
        String normalised          = rawContent.replace('\r\n', '\n').replace('\r', '\n')
        boolean hadTrailingNewline = normalised.endsWith('\n')
        String toSplit             = hadTrailingNewline ? normalised[0..-2] : normalised
        List<String> lines         = toSplit ? new ArrayList<String>(Arrays.asList(toSplit.split('\n', -1))) : [] as List<String>
        int originalLineCount      = lines.size()

        log.debug('patch: read {} content lines from {} (endings: {}, trailingNewline: {})',
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
                errors << ('Invalid range [' + start + '..' + end + '] - file has ' + originalLineCount + ' lines')
            }
            if (!rep.containsKey('newText')) {
                errors << ('Missing newText for range [' + start + '..' + end + ']')
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
                    errors << ('Overlapping ranges: [' + ascSorted[i].startLine + '..' + endI + '] and [' + startJ + '..' + ascSorted[i + 1].endLine + ']')
                }
            }
        }

        if (errors) {
            log.warn('patch: validation failed on {}: {}', normalized, errors.join('; '))
            return McpResponse.toolError(requestId,
                ('patch validation failed (file NOT modified): ' + errors.join('; ')))
        }

        // FS-T2: Detect boundary patches (endLine == last line of file).
        // Fix C: if same file was patched within 60s AND this is a boundary patch, BLOCK it.
        // Boundary patches on recently-patched files are the highest-risk corruption path.
        // RCA-3: boundary = endLine touches last line (file-end corruption risk) OR startLine==1 (file-start boundary)
        boolean hasBoundaryPatch = sorted.any { Map<String, Object> rep ->
            (rep.endLine as int) == originalLineCount || (rep.startLine as int) == 1
        }
        long nowPre = System.currentTimeMillis()
        boolean isSequential = recentPatches.containsKey(normalized) && (nowPre - recentPatches.get(normalized)) < 60_000L
        if (hasBoundaryPatch && isSequential) {
            long secsAgo = (nowPre - recentPatches.get(normalized)).intdiv(1000)
            log.warn('patch: BLOCKED sequential boundary patch on {} ({}s ago)', normalized, secsAgo)
            return McpResponse.toolError(requestId,
                ('Sequential boundary patch rejected: file was patched ' + secsAgo + 's ago. ' +
                 'Re-read with action=range or get_method and use the returned content_hash as expectedHash. ' +
                 'Boundary patches (endLine == last line) require a re-read to avoid class-brace corruption. ' +
                 'For multiple changes, include all replacements[] in a SINGLE patch call.'))
        }

        List<String> boundaryWarnings = []
        sorted.each { Map<String, Object> rep ->
            if ((rep.endLine as int) == originalLineCount) {
                boundaryWarnings << ('replacement [' + rep.startLine + '..' + rep.endLine + '] touches the last line of the file ' +
                    '(boundary patch). Verify newText does NOT duplicate the class closing brace or EOF line. ' +
                    'Re-read lines ' + Math.max(1, (rep.startLine as int) - 2) + '..' + originalLineCount + ' to confirm brace ownership.')
                log.warn('patch: boundary patch detected on {} at line {}', normalized, rep.endLine)
            }
        }

        // Fix F: pre-apply brace balance check for Groovy/Java boundary patches.
        // Simulate the full post-patch result and count braces before writing.
        if (hasBoundaryPatch && (normalized.endsWith('.groovy') || normalized.endsWith('.java'))) {
            // Build a simulated result by applying all replacements to a copy of lines
            List<String> simLines = new ArrayList<String>(lines)
            List<Map<String, Object>> simSorted = replacements.sort(false) { Map a, Map b ->
                (b.startLine as int) <=> (a.startLine as int)
            }
            simSorted.each { Map<String, Object> rep ->
                int s = (rep.startLine as int) - 1
                int e = (rep.endLine   as int) - 1
                String nt = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
                List<String> nl = nt ? new ArrayList<String>(Arrays.asList(nt.split('\n', -1))) : [] as List<String>
                simLines[s..e] = nl
            }
            String simContent = simLines.join('\n')
            int openCount  = simContent.count('{')
            int closeCount = simContent.count('}')
            int imbalance  = Math.abs(openCount - closeCount)
            if (imbalance > 1) {
                log.warn('patch: pre-apply brace check REJECTED {} -- open={} close={} imbalance={}', normalized, openCount, closeCount, imbalance)
                return McpResponse.toolError(requestId,
                    ('patch: pre-apply brace check rejected boundary patch on ' + normalized.tokenize('/').last() + ': ' +
                     'simulated result has ' + openCount + ' open braces and ' + closeCount + ' close braces (imbalance=' + imbalance + '). ' +
                     'Verify newText includes all required closing braces. File NOT modified.'))
            }
        }

        // ---- Phase 2: Apply bottom-up ----
        int applied       = 0
        int expectedDelta = 0
        // Fix G: capture removed_lines content for response
        List<String> removedSnippets = new ArrayList<String>()
        sorted.each { Map<String, Object> rep ->
            int start    = (rep.startLine as int) - 1
            int end      = (rep.endLine   as int) - 1
            String newText       = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
            List<String> newLines = newText ? new ArrayList<String>(Arrays.asList(newText.split('\n', -1))) : [] as List<String>
            int removed  = end - start + 1
            int added    = newLines.size()
            // Capture first 80 chars of removed block for Fix G
            String removedChunk = lines[start..end].join('\n').take(80)
            if (removedChunk) removedSnippets << (removedChunk + (removed > 1 ? ('...[' + removed + ' lines]') : ''))
            lines[start..end] = newLines
            expectedDelta += (added - removed)
            applied++
            log.debug('patch: applied [{}..{}] -> {} lines (net delta {})', start + 1, end + 1, added, added - removed)
        }

        int expectedResultLines = originalLineCount + expectedDelta
        // Fix D: lines_shifted -- how much all subsequent line numbers have moved
        int linesShifted = expectedResultLines - originalLineCount

        // ---- Phase 3: Atomic write ----
        Path targetPath   = Paths.get(normalized)
        String lineEnding = (hasCrLf && !WriteUtils.shouldNormaliseLf(targetPath)) ? '\r\n' : '\n'
        String assembled  = lines.join(lineEnding) + (hadTrailingNewline ? lineEnding : '')
        byte[] resultBytes = assembled.getBytes(encoding)
        try {
            if (backup) WriteUtils.makeBackup(targetPath)
            WriteUtils.atomicWrite(targetPath, resultBytes)
            log.debug('patch: atomic write succeeded for {}', normalized)
        } catch (Exception e) {
            log.error('patch: write failed for {}: {}', normalized, sanitize(e.message))
            return McpResponse.toolError(requestId,
                'patch: write failed (original file untouched): ' + sanitize(e.message))
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
                verifyError = 'line count mismatch: expected ' + expectedResultLines + ', file has ' + verifiedLines
                log.error('patch: post-write verification FAILED on {}: {}', normalized, verifyError)
            } else {
                log.info('patch: verified OK - {} content lines in {}', verifiedLines, normalized)
            }
        } catch (Exception e) {
            verifyError = 'could not verify: ' + sanitize(e.message)
            log.warn('patch: post-write verification skipped for {}: {}', normalized, sanitize(e.message))
        }

        log.info('patch: {} replacement(s) on {} ({} -> {} lines, endings: {})',
            applied, normalized, originalLineCount, expectedResultLines, hasCrLf ? 'CRLF' : 'LF')

        String resultHash = null
        try { resultHash = WriteUtils.computeHash(resultBytes) } catch (Exception ignored) {}

        // Fix E: include tail_content (last 5 lines) on boundary patches so caller can
        // verify closing braces without a separate read call.
        String tailContent = null
        if (hasBoundaryPatch) {
            List<String> tailLines = lines.size() > 5 ? lines[-5..-1] : lines
            tailContent = tailLines.join('\n')
        }

        Map<String, Object> result = [
            action           : 'patch',
            path             : normalized,
            success          : (verifyError == null),
            applied          : applied,
            original_lines   : originalLineCount,
            result_lines     : expectedResultLines,
            lines_shifted    : linesShifted,
            content_hash     : resultHash,
            file_content_hash: resultHash
        ] as Map<String, Object>
        if (verifyError) result.put('verify_warning', verifyError)
        if (boundaryWarnings) result.put('boundary_warning', boundaryWarnings.join(' | '))
        if (hasBoundaryPatch) result.put('requires_reread', true)
        if (tailContent != null) result.put('tail_content', tailContent)
        if (removedSnippets) result.put('removed_lines', removedSnippets.join(' | ').take(300))

        // RCA-3: only update recentPatches after confirmed successful write
        long now = System.currentTimeMillis()
        boolean shouldHint = !hasBoundaryPatch && recentPatches.containsKey(normalized) && (now - recentPatches.get(normalized)) < 60_000L
        if (verifyError == null) {
            recentPatches.put(normalized, now)
        }
        if (shouldHint) {
            String hint = 'CAUTION: this file was patched recently -- line numbers have shifted by ' + linesShifted + '. ' +
                'Re-read with action=range or get_method and use the returned content_hash as expectedHash before next patch. ' +
                'For multiple changes, pass all replacements[] in a SINGLE patch call -- never sequential patches across turns.'
            result.put('hint', hint)
            log.warn('patch: sequential patch detected on {} -- caller should re-read between patches', normalized)
        }

        if (isWriteCompact(options)) {
            Map<String, Object> compact = [
                success: result.success, applied: applied,
                lines_shifted: linesShifted,
                content_hash: resultHash, file_content_hash: resultHash
            ] as Map<String, Object>
            if (verifyError) compact.put('verify_warning', verifyError)
            if (shouldHint) compact.put('hint', result.get('hint'))
            if (hasBoundaryPatch) compact.put('requires_reread', true)
            if (boundaryWarnings) compact.put('boundary_warning', result.get('boundary_warning'))
            if (tailContent != null) compact.put('tail_content', tailContent)
            if (removedSnippets) compact.put('removed_lines', result.get('removed_lines'))
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
