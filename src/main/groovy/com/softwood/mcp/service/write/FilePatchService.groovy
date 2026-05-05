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
 * v0.9.0  - PR 1.1: recentPatches replaced with bounded LRU (DestructiveChangeGuard.boundedLruMap, D6).
 *           PR 1.3: per-replacement brace/paren delta logic delegates to StructuralGuard (D5).
 *           PR 1.4: doPatch uses WriteContext.load() instead of inline load sequence (D1, D3, D4).
 *                   normalizeAndCheckPath removed from FilePatchService.
 *           Phase 2: doPatch calls WriteCommitter.commit() for pre-commit drift check (D11).
 */
@Service
@Slf4j
@CompileStatic
class FilePatchService extends AbstractFileService {

    /** Track recent patches to warn about sequential patching without intervening re-reads. */
    // v0.9.0: bounded LRU (200 entries) replaces unbounded LinkedHashMap (D6)
    private final Map<String, Long> recentPatches = DestructiveChangeGuard.boundedLruMap()

    FilePatchService(PathService pathService) {
        super(pathService)
    }

    McpResponse doPatch(String path, String content, Map<String, Object> options, Object requestId) {
        List<Map<String, Object>> replacements = (options.replacements instanceof List)
            ? options.replacements as List<Map<String, Object>>
            : []

        if (!replacements) {
            return McpResponse.toolError(requestId,
                'patch requires options.replacements: [{startLine,endLine,newText}]. ' +
                'Use action=multi_replace for string-based edits, or supply a replacements array.')
        }

        // PR 1.4 (FS 0.9.0): WriteContext.load() replaces duplicated load sequence (D1/D3/D4/D13).
        WriteContext.LoadResult lrPatch = WriteContext.load(path, options, this, requestId)
        if (lrPatch.error) return lrPatch.error
        WriteContext ctxPatch   = lrPatch.ctx
        String normalized       = ctxPatch.normalized
        String encoding         = ctxPatch.encoding
        boolean backup          = options.backup as boolean ?: false
        String  expectedHash    = options.expectedHash as String

        McpResponse hashErrPatch = ctxPatch.checkHash(expectedHash, requestId)
        if (hashErrPatch) return hashErrPatch

        log.debug('patch: starting on {} with {} replacement(s)', normalized, replacements.size())

        // D10 fix: destructive guard using ctx.content (already decoded by WriteContext)
        boolean forcePatch = options.containsKey('force') ? (options.force as boolean) : false
        List<String> guardLines = ctxPatch.content.split('\n', -1).toList()
        int patchRemovedLen = (replacements.collect { Map rep ->
            int s = rep.startLine ? (rep.startLine as int) : 0
            int e = rep.endLine   ? (rep.endLine   as int) : 0
            int from = Math.max(0, s - 1)
            int to   = Math.min(guardLines.size(), e)
            (from < to) ? guardLines.subList(from, to).join('\n').length() : 0
        } as List<Integer>).sum() as int
        int patchAddedLen = (replacements.collect { Map rep ->
            (rep.newText as String ?: '').length()
        } as List<Integer>).sum() as int
        String drPatchError = DestructiveChangeGuard.check('patch', patchRemovedLen, patchAddedLen, forcePatch, path)
        if (drPatchError) return McpResponse.toolError(requestId, drPatchError)

        boolean hasCrLf            = ctxPatch.hasCrLf
        String  rawContent         = ctxPatch.content  // LF-normalised
        String  normalised         = rawContent         // alias used by downstream logic
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
            // CT-74: explicit missing-key check before any int cast -- null as int = 0
            // produces a confusing NPE or silent [0..0] range error.
            if (!rep.containsKey('startLine') || rep.startLine == null) {
                errors << 'Missing startLine in replacement entry -- patch requires {startLine, endLine, newText}'
                return
            }
            if (!rep.containsKey('endLine') || rep.endLine == null) {
                errors << 'Missing endLine in replacement entry -- patch requires {startLine, endLine, newText}'
                return
            }
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

        // CT-78: expectedRemovedText content guard (FS 0.8.68).
        // When a replacement entry supplies expectedRemovedText, verify the actual lines
        // [startLine..endLine] match before applying. Prevents stale line-number mistakes
        // from silently corrupting non-Groovy/Java files (no brace check on those).
        // The field is optional -- omitting it preserves existing line-range behaviour.
        for (Map<String, Object> rep : sorted) {
            String expectedRemoved = rep.expectedRemovedText as String
            if (expectedRemoved != null && !expectedRemoved.isEmpty()) {
                int s = (rep.startLine as int) - 1
                int e = (rep.endLine   as int) - 1
                String actualRemoved = lines[s..e].join('\n')
                    .replace('\r\n', '\n').replace('\r', '\n').trim()
                String expectedTrimmed = expectedRemoved.replace('\r\n', '\n').replace('\r', '\n').trim()
                if (actualRemoved != expectedTrimmed) {
                    log.warn('patch: CONTENT_MISMATCH on {} lines {}-{}: expected [{...}] got [{...}]',
                        normalized, rep.startLine, rep.endLine)
                    return McpResponse.toolError(requestId,
                        ('CONTENT_MISMATCH: expectedRemovedText for range [' + rep.startLine + '..' + rep.endLine + '] ' +
                         'does not match actual file content. ' +
                         'Actual (trimmed): [' + actualRemoved.take(120) + '] ' +
                         'Expected (trimmed): [' + expectedTrimmed.take(120) + ']. ' +
                         'Re-read lines ' + rep.startLine + '..' + rep.endLine + ' to get current content.'))
                }
            }
        }

        // FS-T2: Detect boundary patches (endLine == last line of file).
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

        // PR 1.3 (FS 0.9.0): delegate structural checks to StructuralGuard (D5 fix).
        // checkBraceDelta + checkParenDelta with conservative string-strip heuristic.
        // All guards are pre-write hard rejects -- no advisory path.
        if (StructuralGuard.isCodeFile(normalized)) {
            for (Map<String, Object> rep : sorted) {
                int s = (rep.startLine as int) - 1
                int e = (rep.endLine   as int) - 1
                String removedContent = lines[s..e].join('\n')
                String newContent     = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
                String braceErr = StructuralGuard.checkBraceDelta(removedContent, newContent, normalized)
                if (braceErr) {
                    log.warn('patch: brace guard REJECTED {} -- {}', normalized, braceErr)
                    return McpResponse.toolError(requestId, 'patch: ' + braceErr)
                }
                String parenErr = StructuralGuard.checkParenDelta(removedContent, newContent, normalized)
                if (parenErr) {
                    log.warn('patch: paren guard REJECTED {} -- {}', normalized, parenErr)
                    return McpResponse.toolError(requestId, 'patch: ' + parenErr)
                }
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
            WriteCommitter.CommitResult crPatch = WriteCommitter.commit(ctxPatch, resultBytes, requestId)
            if (!crPatch.succeeded()) return crPatch.error
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
}
