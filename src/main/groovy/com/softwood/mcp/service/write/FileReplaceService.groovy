package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.PathService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * FileReplaceService - handles replace and multi_replace actions.
 *
 * v0.7.44 - extracted from FileWriteService as part of write/ subpackage split.
 *           Includes WI2 fixes: non-ASCII diagnostic hint, improved hash-mismatch messages.
 * v0.8.45 - FS-T9: brace-balance warning on replace and multi_replace.
 * v0.9.0  - PR 1.1: recentWrites replaced with bounded LRU (DestructiveChangeGuard.boundedLruMap, D6).
 *           PR 1.2: doReplace delegates Unicode normalisation + replacement to TextMatcher (D2).
 *                   doMultiReplace delegates per-entry resolution to TextMatcher.findAll().
 *           PR 1.3: dead post-write brace_warning removed from doReplace (D5).
 *                   StructuralGuard.checkBraceDelta/checkParenDelta used for pre-write hard reject.
 *                   No brace_warning field in any response from this service.
 *           PR 1.4: doReplace + doMultiReplace use WriteContext.load() instead of inline
 *                   7-line load sequence (D1, D3). normalizeAndCheckPath removed.
 *           PR 1.5: doMultiReplace delegates Phases A-D to MultiReplaceValidator (D9).
 *           Phase 2: all write paths call WriteCommitter.commit() for pre-commit drift check (D11).
 */
@Service
@Slf4j
@CompileStatic
class FileReplaceService extends AbstractFileService {

    @Value('${mcp.filesystem.read-chunk-threshold-kb:300}')
    int replaceChunkThresholdKb

    // Separate threshold for replace/multi_replace -- higher than read-chunk to allow large handler files
    @Value('${mcp.filesystem.replace-threshold-kb:150}')
    int replaceFileSizeThresholdKb

    // v0.9.0: bounded LRU (200 entries) replaces unbounded LinkedHashMap (D6)
    private final Map<String, Long> recentWrites = DestructiveChangeGuard.boundedLruMap()

    FileReplaceService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // Unicode normalisation helper
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // replace
    // -----------------------------------------------------------------------


    // -----------------------------------------------------------------------
    // Unicode-safe positional replace helper (Fix A'')
    // -----------------------------------------------------------------------

    /**
     * doReplace -- single-site string replacement.
     *
     * CT-FW-REPLACE-1 (FS 0.8.69): options.newText key absent entirely is a hard error.
     * Callers that genuinely want deletion must pass newText:'' explicitly.
     * CT-FW-REPLACE-2 (FS 0.8.69): options.newText:'' (explicit empty) is allowed -- deliberate deletion.
     * CT-DR-1/CT-DR-2 (FS 0.8.67): large oldText with newText < 20% size is rejected -- destructive ratio guard.
     */
    McpResponse doReplace(String path, Map<String, Object> options, Object requestId) {
        String oldText      = options.oldText as String
        // oldText is the primary required field -- check it first so error messages are actionable.
        if (!oldText) return McpResponse.toolError(requestId, 'options.oldText required for replace')
        // CT-FW-REPLACE-1: newText absent (key missing entirely) is an error -- caller likely forgot the param.
        // Explicit empty string (newText: '') is allowed and means deletion.
        if (!options.containsKey('newText')) return McpResponse.toolError(requestId,
            'options.newText missing for replace -- pass newText:\'\' explicitly if deletion is intended')
        String newText      = (options.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
        String expectedHash = options.expectedHash as String
        // CT-EH-1 (FS 0.8.73): expectedHash is mandatory for all mutating actions.
        // A replace without it means the caller hasn't read the file -- silent corruption risk.
        // Always read first (action=range/get_method), pass the returned file_content_hash here.
        if (!expectedHash) {
            return McpResponse.toolError(requestId,
                ('options.expectedHash required for replace. ' +
                 'Read the target section first (file_read action=range or get_method) and pass ' +
                 'the returned file_content_hash as options.expectedHash.'))
        }

        // CT-DR-1/CT-DR-2: Destructive-replace ratio guard (FS 0.8.67 / centralised 0.9.0).
        // Delegated to DestructiveChangeGuard.check() (D10 fix -- uniform across all write actions).
        boolean forceReplace = options.containsKey('force') ? (options.force as boolean) : false
        String drError = DestructiveChangeGuard.check('replace',
            (oldText as String).length(), (newText as String).length(), forceReplace, path)
        if (drError) return McpResponse.toolError(requestId, drError)

        // PR 1.4 (FS 0.9.0): WriteContext.load() replaces duplicated 7-line load sequence (D1/D3/D4/D13).
        // Handles: write-enabled, path validation, size cap, encoding check, binary guard, CRLF detect.
        WriteContext.LoadResult lr = WriteContext.load(path, options, this, requestId)
        if (lr.error) return lr.error
        WriteContext ctx = lr.ctx
        String normalized = ctx.normalized
        boolean backup    = options.backup as boolean ?: false
        String  encoding  = ctx.encoding

        // Hash check (mandatory -- CT-EH-1)
        McpResponse hashErr = ctx.checkHash(expectedHash, requestId)
        if (hashErr) return hashErr

        boolean hasCrLf = ctx.hasCrLf
        String  current = ctx.content


        // Normalize oldText line endings (same as file content)
        oldText = oldText.replace('\r\n', '\n').replace('\r', '\n')

        // PR 1.2 (FS 0.9.0): delegate Unicode match resolution to TextMatcher (D2 fix).
        // TextMatcher.find() tries raw -> NFC -> NFKC -> box-drawing and returns a
        // MatchResult whose origStart/origEnd are offsets in the ORIGINAL string,
        // not in any normalised form. This eliminates the wrong-offset replacement bug.
        TextMatcher.MatchResult matchResult = TextMatcher.find(current, oldText)
        int count = matchResult.count

        if (count == 0) {
            // Nearest-line hint already computed by TextMatcher.find()
            String firstLine = oldText.trim().tokenize('\n').first()?.trim() ?: ''
            Map<String, Object> err = [
                action: 'replace', success: false,
                error: 'oldText not found in file (NFC+NFKC+box-drawing normalisation tried). RECOVERY: (1) Re-read target lines with file_read action=range or action=get_method to get EXACT current text+hash. (2) If oldText contains non-ASCII (em-dashes, box-drawing etc) use action=patch with startLine/endLine -- immune to encoding issues. (3) Retry replace with exact current text + current expectedHash.',
                line_endings: hasCrLf ? 'CRLF' : 'LF',
                oldText_first_line: firstLine.take(120)
            ] as Map<String, Object>
            if (matchResult.nearestLine > 0) err.nearest_match = [line: matchResult.nearestLine, content: matchResult.nearestContent?.take(120)]
            List<Integer> nonAsciiPositions = []
            oldText.toCharArray().eachWithIndex { char c, int i -> if (c < 32 || c > 126) nonAsciiPositions << i }
            if (nonAsciiPositions) {
                err.non_ascii_hint = "oldText contains non-ASCII chars at positions ${nonAsciiPositions.take(10)} \u2014 use patch with explicit startLine/endLine for strings containing special characters."
            }
            return McpResponse.toolError(requestId, new groovy.json.JsonBuilder(err).toString())
        }
        if (count > 1) {
            List<Integer> matchLines = new ArrayList<Integer>()
            int searchFrom = 0
            while (searchFrom < current.length()) {
                int idx = current.indexOf(oldText, searchFrom)
                if (idx < 0) break
                matchLines.add(current.substring(0, idx).count('\n') + 1)
                searchFrom = idx + 1
            }
            String lineInfo = matchLines.isEmpty() ? '' : (' at lines ' + matchLines.join(', '))
            return McpResponse.toolError(requestId,
                ('replace: oldText appears ' + count + ' times' + lineInfo + ' (must be unique). Provide more context.'))
        }

        // PR 1.2: apply via TextMatcher using original-span offsets (D2 fix).
        // MATCH_REQUIRES_EXACT means NFC/NFKC changed string length before match position --
        // return a toolError so the file is left unchanged.
        String updated
        if (matchResult.normForm != null) {
            updated = TextMatcher.apply(current, newText, matchResult)
            if (updated == TextMatcher.MATCH_REQUIRES_EXACT) {
                return McpResponse.toolError(requestId,
                    ('replace: Unicode normalisation resolved match via ' + matchResult.normForm +
                     ' but the normalised form changes string length before the match position. ' +
                     'Use action=patch with explicit startLine/endLine to replace this content safely.'))
            }
            log.debug('replace: {} span-based replace applied for {}', matchResult.normForm, normalized)
        } else {
            updated = current.replace(oldText, newText)
        }
        Path target = Paths.get(normalized)
        // CRLF restoration is handled by ctx.toBytes() -- do NOT pre-convert updated here.

        if (backup) WriteUtils.makeBackup(target)
        // FS-T10: bare-box-drawing check -- blocks writes that would produce lines
        // starting with raw U+2500..U+257F in code files (.groovy/.java/.kt).
        // Root cause (G1 session): multi_replace oldText ending mid-divider left
        // trailing \u2500\u2500\u2500 chars without // prefix -> Groovy compile error.
        // PR 1.3 (FS 0.9.0): unified StructuralGuard.checkAll() replaces inline checks (D5 fix).
        // checkAll() runs: brace delta, paren delta, bare-box-drawing -- all pre-write hard rejects.
        // No post-write advisory (brace_warning field removed entirely).
        String structErr = StructuralGuard.checkAll(oldText, newText, updated, normalized)
        if (structErr) {
            log.warn('replace: structural guard REJECTED {} -- {}', normalized, structErr)
            return McpResponse.toolError(requestId, 'replace structural check failed (file NOT modified): ' + structErr)
        }
        WriteCommitter.CommitResult cr = WriteCommitter.commit(ctx, ctx.toBytes(updated), requestId)
        if (!cr.succeeded()) return cr.error
        String hash = cr.newHash

        long now = System.currentTimeMillis()
        Long lastWrite = recentWrites.get(normalized)
        boolean shouldHint = lastWrite != null && (now - lastWrite) < 60_000L
        recentWrites.put(normalized, now)

        if (isWriteCompact(options)) {
            Map<String, Object> compact = [success: true, content_hash: hash, file_content_hash: hash] as Map<String, Object>
            if (matchResult.normForm != null) compact.norm_applied = matchResult.normForm
            return textResponse(requestId, compact)
        }
        Map<String, Object> resp = ([action: 'replace', path: normalized,
            replacements: 1, success: true,
            content_hash: hash, file_content_hash: hash] as Map<String, Object>)
        if (shouldHint) resp.hint = 'More edits to this file? Prefer multi_replace to batch them in one call.'
        if (matchResult.normForm != null) resp.norm_applied = matchResult.normForm
        return textResponse(requestId, resp)
    }

    // -----------------------------------------------------------------------
    // multi_replace
    // -----------------------------------------------------------------------

    /**
     * doMultiReplace -- atomic multi-site replacement in one call.
     *
     * CT-FW-REPLACE-3 (FS 0.8.69): per-entry newText key absent is a validation error.
     * CT-FW-REPLACE-4 (FS 0.8.69): per-entry newText:'' (explicit empty) is allowed -- deliberate deletion.
     * All entries are pre-validated before the file is touched (fail-fast, file unchanged on error).
     */
    McpResponse doMultiReplace(String path, Map<String, Object> options, Object requestId) {
        List<Map<String, Object>> replacements = (options.replacements as List<Map<String, Object>>) ?: []
        if (!replacements) return McpResponse.toolError(requestId, 'options.replacements required for multi_replace')

        // PR 1.4 (FS 0.9.0): WriteContext.load() replaces duplicated load sequence (D1/D3/D4/D13).
        WriteContext.LoadResult lrMr = WriteContext.load(path, options, this, requestId)
        if (lrMr.error) return lrMr.error
        WriteContext ctxMr  = lrMr.ctx
        String normalized   = ctxMr.normalized
        boolean backup      = options.backup as boolean ?: false
        String  encoding    = ctxMr.encoding
        String  expectedHash = options.expectedHash as String

        McpResponse hashErrMr = ctxMr.checkHash(expectedHash, requestId)
        if (hashErrMr) return hashErrMr

        boolean hasCrLf = ctxMr.hasCrLf
        String  snapshot = ctxMr.content


        // Phase 1: pre-validate all replacements before touching the file.
        // D10 fix (FS 0.9.0): destructive-change guard now covers multi_replace.
        // Compute combined removed/added lengths across all entries.
        boolean forceMr = options.containsKey('force') ? (options.force as boolean) : false
        int totalOldLen = (replacements.collect { Map<String, Object> rep ->
            (rep.oldText as String)?.length() ?: 0
        } as List<Integer>).sum() as int
        int totalNewLen = (replacements.collect { Map<String, Object> rep ->
            (rep.containsKey('newText') ? (rep.newText as String ?: '') : '').length()
        } as List<Integer>).sum() as int
        String drMrError = DestructiveChangeGuard.check('multi_replace', totalOldLen, totalNewLen, forceMr, path)
        if (drMrError) return McpResponse.toolError(requestId, drMrError)
        // PR 1.2 (FS 0.9.0): delegate Unicode match resolution to TextMatcher.findAll() (D2 fix).
        // findAll() resolves NFC/NFKC/box-drawing for each entry and returns MatchResults
        // whose origStart/origEnd are offsets in the ORIGINAL snapshot -- not in any
        // normalised form.  We sort by origStart descending (RCA-5) and apply in reverse
        // order so earlier replacements don't shift offsets for later ones.
        // PR 1.5 (FS 0.9.0): MultiReplaceValidator.validate() extracts all three validation
        // phases from doMultiReplace (D9 fix). Phases: presence check, TextMatcher resolution,
        // overlap checks (containment + boundary), simulation pass.
        MultiReplaceValidator.ValidationResult vr = MultiReplaceValidator.validate(snapshot, replacements)
        if (!vr.valid()) {
            return McpResponse.toolError(requestId,
                ('multi_replace validation failed (file NOT modified): ' + vr.errors.join('; ') as String))
        }
        List<String> oldTextsForMatch = vr.oldTexts
        List<TextMatcher.MatchResult> matchResults = vr.matchResults

        // Build work items with origStart from TextMatcher results (RCA-5: apply highest offset first)
        List<Map<String, Object>> workItems = new ArrayList<Map<String, Object>>(replacements.size())
        replacements.eachWithIndex { Map<String, Object> rep, int i ->
            String oldText = oldTextsForMatch.get(i)
            String newText = ((rep.newText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            TextMatcher.MatchResult mr = matchResults.get(i)
            workItems.add([startPos: mr.origStart, oldText: oldText, newText: newText, mr: mr] as Map<String, Object>)
        }
        workItems.sort { Map<String, Object> a, Map<String, Object> b ->
            (b.startPos as int) <=> (a.startPos as int)
        }

        // Apply in reverse position order
        String current = snapshot
        int applied = 0
        List<String> normsApplied = []
        for (Map<String, Object> item : workItems) {
            String oldText = item.oldText as String
            String newText = item.newText as String
            TextMatcher.MatchResult mr = item.mr as TextMatcher.MatchResult
            if (mr.normForm == null) {
                current = current.replace(oldText, newText)
            } else {
                // Re-run find() on the progressively modified string
                TextMatcher.MatchResult reCurrent = TextMatcher.find(current, oldText)
                if (reCurrent.count == 1) {
                    String result = TextMatcher.apply(current, newText, reCurrent)
                    if (result == TextMatcher.MATCH_REQUIRES_EXACT) {
                        log.warn('multi_replace: MATCH_REQUIRES_EXACT for {} (normForm={}), falling back', normalized, mr.normForm)
                        current = current.replace(oldText, newText)
                    } else {
                        current = result
                    }
                    normsApplied << (mr.normForm as String)
                } else {
                    log.warn('multi_replace: entry became unfindable after prior replacements for {} -- skipping', normalized)
                }
            }
            applied++
        }
        if (normsApplied) log.debug('multi_replace: per-entry normalisation applied {} for {}', normsApplied.join(', '), normalized)

        // PR 1.3 (FS 0.9.0): StructuralGuard.checkAll() replaces inline per-entry brace check
        // and bare-box check. 'current' is the fully-applied LF-normalised result.
        // We pass snapshot as removedContent and current as newText to check the net delta;
        // for multi-replace we use the overall file delta (snapshot->current).
        String structErrMr = StructuralGuard.checkAll(snapshot, current, current, normalized)
        if (structErrMr) {
            log.warn('multi_replace: structural guard REJECTED {} -- {}', normalized, structErrMr)
            return McpResponse.toolError(requestId,
                'multi_replace structural check failed (file NOT modified): ' + structErrMr)
        }

        Path target = Paths.get(normalized)
        if (backup) WriteUtils.makeBackup(target)
        if (hasCrLf) current = current.replace('\n', '\r\n')
        WriteCommitter.CommitResult crMr = WriteCommitter.commit(ctxMr, current.getBytes(encoding), requestId)
        if (!crMr.succeeded()) return crMr.error
        log.info('multi_replace: {} applied in {} (line endings: {})', applied, normalized, hasCrLf ? 'CRLF' : 'LF')

        String hash = crMr.newHash
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, applied: applied, content_hash: hash, file_content_hash: hash] as Map<String, Object>)
        }
        return textResponse(requestId, [
            action: 'multi_replace', path: normalized,
            applied: applied, success: true,
            content_hash: hash, file_content_hash: hash
        ] as Map<String, Object>)
    }

    // -----------------------------------------------------------------------
}
