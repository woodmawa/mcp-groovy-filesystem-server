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
import java.text.Normalizer

/**
 * FileReplaceService - handles replace and multi_replace actions.
 *
 * v0.7.44 - extracted from FileWriteService as part of write/ subpackage split.
 *           Includes WI2 fixes: non-ASCII diagnostic hint, improved hash-mismatch messages.
 * v0.8.45 - FS-T9: brace-balance warning on replace and multi_replace. After each apply,
 *           counts '{'/'}' in the changed region + 5-line context. Emits brace_warning if
 *           unbalanced -- surfacing silent method-boundary corruption before compile.
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

    // v0.8.1 Change 4: track recent writes to nudge multi_replace batching
    private final Map<String, Long> recentWrites = Collections.synchronizedMap(new LinkedHashMap<String, Long>())

    FileReplaceService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // Unicode normalisation helper
    // -----------------------------------------------------------------------

    /**
     * Replaces box-drawing characters (U+2500-U+257F) with ASCII equivalents.
     * These appear in Groovy/Gradle files as decorative comment separators.
     * U+2500 (horizontal) -> '-', U+2502 (vertical) -> '|', others -> '-'.
     * Purely cosmetic normalisation -- safe to apply before string matching.
     */
    private static String normalizeBoxDrawing(String s) {
        if (!s) return s
        StringBuilder sb = new StringBuilder(s.length())
        s.codePoints().forEach { int cp ->
            if (cp >= 0x2500 && cp <= 0x257F) {
                sb.append(cp == 0x2502 || cp == 0x2503 || cp == 0x2551 ? '|' : '-')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // replace
    // -----------------------------------------------------------------------


    // -----------------------------------------------------------------------
    // Unicode-safe positional replace helper (Fix A'')
    // -----------------------------------------------------------------------

    /**
     * Resolves a normalised match position back to the original string and performs
     * the replacement there -- avoiding NFKC normalisation of the entire file.
     *
     * Strategy:
     *   1. Normalise both sides to the same form to find the match START position.
     *   2. In the ORIGINAL string, find the exact byte sequence at that position by
     *      scanning forward to identify the original characters that correspond to
     *      the normalised oldText length.
     *   3. Replace only that region with newText; everything else is untouched.
     *
     * This is safe because Java String.indexOf() on normalised views returns a char-offset
     * that is valid in the original string IF no multi-char normalisation sequences exist
     * (NFC/NFKC may change char count for certain compatibility sequences, but in practice
     * for the characters we encounter -- em-dashes, smart quotes -- the char count is 1:1).
     * For safety we fall back to full-normalised replace if the position-based approach
     * would exceed string bounds.
     *
     * @param original  LF-normalised original file content (not normalised)
     * @param normOrig  normalised version of original (NFC, NFKC, or box-drawing)
     * @param normOld   normalised version of oldText (same form as normOrig)
     * @param newText   replacement text (written as-is to the output)
     * @return          updated string with only the matched region replaced
     */
    private static String positionalReplace(String original, String normOrig, String normOld, String newText) {
        int pos = normOrig.indexOf(normOld)
        if (pos < 0) return original  // safety: should not happen, caller already confirmed count==1
        int endPos = pos + normOld.length()
        // Bounds check: if char offsets are valid in original, use positional replace
        if (pos <= original.length() && endPos <= original.length()) {
            return original.substring(0, pos) + newText + original.substring(endPos)
        }
        // Fallback: full-normalised replace (last resort -- should be rare for NFC)
        return normOrig.replace(normOld, newText)
    }

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
        // CT-FW-REPLACE-1: newText absent (key missing entirely) is an error -- caller likely forgot the param.
        // Explicit empty string (newText: '') is allowed and means deletion.
        if (!options.containsKey('newText')) return McpResponse.toolError(requestId,
            'options.newText missing for replace -- pass newText:\'\' explicitly if deletion is intended')
        String newText      = (options.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
        String expectedHash = options.expectedHash as String
        if (!expectedHash) {
            log.warn('doReplace called without expectedHash for {} -- drift guard disabled. Caller should pass expectedHash from last read.', path)
        }
        if (!oldText) return McpResponse.toolError(requestId, 'options.oldText required for replace')

        // CT-DR-1/CT-DR-2: Destructive-replace ratio guard (FS 0.8.67).
        // Fires when oldText is large (>500 chars) AND newText is <20% of oldText length.
        // This pattern (oldText=entire file, newText=small fragment) silently destroys content.
        // Use action=write for full-file rewrites. Reduce oldText scope for legitimate shrinks.
        int oldLen = (oldText as String).length()
        int newLen = (newText as String).length()
        if (oldLen > 500 && newLen < (int)(oldLen * 0.20d)) {
            return McpResponse.toolError(requestId,
                ("DESTRUCTIVE_REPLACE: newText (${newLen} chars) is less than 20% of oldText (${oldLen} chars). " +
                 'This typically means a full-file replace with truncated newText, which destroys content. ' +
                 'To rewrite the file use action=write with the full content. ' +
                 'For a legitimate shrinking replace, reduce oldText scope to just the target block.'))
        }

        String normalized = normalizeAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        if (!Files.exists(Paths.get(normalized))) {
            return McpResponse.toolError(requestId,
                ("replace: file not found: ${sanitize(normalized)}. " +
                 'Use file_write action=write to create a new file.'))
        }

        long fileSizeKb = Files.size(Paths.get(normalized)).intdiv(1024)
        if (fileSizeKb > replaceFileSizeThresholdKb) {
            return McpResponse.toolError(requestId,
                ("replace: file is ${fileSizeKb}KB which exceeds threshold ${replaceFileSizeThresholdKb}KB. " +
                 'Use multi_replace for up to ' + replaceFileSizeThresholdKb + 'KB, or patch (line-range) for larger files.'))
        }

        byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))
        String rawContent = new String(rawBytes, encoding)
        boolean hasCrLf   = rawContent.contains('\r\n')
        String current    = rawContent.replace('\r\n', '\n').replace('\r', '\n')

        // Drift guard
        if (expectedHash) {
            String actualHash = WriteUtils.computeHash(rawBytes)
            if (actualHash != expectedHash) {
                return McpResponse.toolError(requestId,
                    ('expectedHash mismatch: file has changed since your last read (expected ' + expectedHash + ', got ' + actualHash + '). ' +
                     'Re-read the target lines with action=range or action=get_method to get the current content_hash, then retry.'))
            }
        }

        // Normalize oldText line endings (same as file content)
        oldText = oldText.replace('\r\n', '\n').replace('\r', '\n')

        int count = WriteUtils.countOccurrences(current, oldText)

        // If no match, try progressive Unicode normalisation: NFC -> NFKC -> box-drawing chars.
        // Fix A'': use positionalReplace so only the matched region is written using the
        // normalised form -- the rest of the file stays on the original bytes.
        String normForm = null       // which normalisation resolved the match
        String normContent = null    // normalised file content (used for position lookup only)
        String normOldText = null    // normalised oldText (used for position lookup only)
        if (count == 0) {
            // Pass 1: NFC
            String nc = Normalizer.normalize(current, Normalizer.Form.NFC)
            String no = Normalizer.normalize(oldText, Normalizer.Form.NFC)
            if (WriteUtils.countOccurrences(nc, no) == 1) {
                normForm = 'NFC'; normContent = nc; normOldText = no
                count = 1
                log.debug('replace: NFC normalisation resolved match for {}', normalized)
            }
        }
        if (count == 0) {
            // Pass 2: NFKC
            String nc = Normalizer.normalize(current, Normalizer.Form.NFKC)
            String no = Normalizer.normalize(oldText, Normalizer.Form.NFKC)
            if (WriteUtils.countOccurrences(nc, no) == 1) {
                normForm = 'NFKC'; normContent = nc; normOldText = no
                count = 1
                log.debug('replace: NFKC normalisation resolved match for {}', normalized)
            }
        }
        if (count == 0) {
            // Pass 3: box-drawing chars
            String nc = normalizeBoxDrawing(current)
            String no = normalizeBoxDrawing(oldText)
            if (WriteUtils.countOccurrences(nc, no) == 1) {
                normForm = 'box-drawing'; normContent = nc; normOldText = no
                count = 1
                log.debug('replace: box-drawing normalisation resolved match for {}', normalized)
            }
        }

        if (count == 0) {
            String firstLine = oldText.trim().tokenize('\n').first()?.trim() ?: ''
            String nearestContent = null
            int nearestLine = -1
            if (firstLine) {
                int bestScore = Integer.MAX_VALUE
                List<String> fileLines = new ArrayList<String>(Arrays.asList(current.split('\n', -1))).take(500) as List<String>
                fileLines.eachWithIndex { String fl, int idx ->
                    String trimFl = fl.trim()
                    if (trimFl.toLowerCase().contains(firstLine.toLowerCase()) || firstLine.toLowerCase().contains(trimFl.toLowerCase())) {
                        int score = Math.abs(trimFl.length() - firstLine.length())
                        if (score < bestScore) { bestScore = score; nearestContent = fl; nearestLine = idx + 1 }
                    }
                }
            }
            Map<String, Object> err = [
                action: 'replace', success: false,
                error: 'oldText not found in file (NFC+NFKC+box-drawing normalisation tried). RECOVERY: (1) Re-read target lines with file_read action=range or action=get_method to get EXACT current text+hash. (2) If oldText contains non-ASCII (em-dashes, box-drawing etc) use action=patch with startLine/endLine -- immune to encoding issues. (3) Retry replace with exact current text + current expectedHash.',

                line_endings: hasCrLf ? 'CRLF' : 'LF',
                oldText_first_line: firstLine.take(120)
            ] as Map<String, Object>
            if (nearestLine > 0) err.nearest_match = [line: nearestLine, content: nearestContent?.take(120)]
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

        // Fix A'': if normalisation was needed, use positionalReplace to avoid touching
        // the rest of the file. Falls back to normContent.replace() only if bounds exceeded.
        String updated
        if (normForm != null) {
            updated = positionalReplace(current, normContent, normOldText, newText)
            log.debug('replace: positional {} replace applied for {}', normForm, normalized)
        } else {
            updated = current.replace(oldText, newText)
        }
        Path target = Paths.get(normalized)
        if (hasCrLf && !WriteUtils.shouldNormaliseLf(target)) updated = updated.replace('\n', '\r\n')

        if (backup) WriteUtils.makeBackup(target)
        // FS-T10: bare-box-drawing check -- blocks writes that would produce lines
        // starting with raw U+2500..U+257F in code files (.groovy/.java/.kt).
        // Root cause (G1 session): multi_replace oldText ending mid-divider left
        // trailing \u2500\u2500\u2500 chars without // prefix -> Groovy compile error.
        String bareBoxError = checkBareBoxDrawing(updated, normalized)
        if (bareBoxError) {
            log.warn('replace: bare-box-drawing check REJECTED {} -- {}', normalized, bareBoxError)
            return McpResponse.toolError(requestId,
                'replace bare_box_drawing check failed (file NOT modified): ' + bareBoxError)
        }
        WriteUtils.atomicWrite(target, updated.getBytes(encoding))

        log.debug('replace: 1 occurrence in {} (line endings: {}, norm: {})', normalized,
            WriteUtils.shouldNormaliseLf(target) ? 'LF (normalised)' : (hasCrLf ? 'CRLF (preserved)' : 'LF'),
            normForm != null ? normForm : 'none')
        String hash = WriteUtils.fileHash(target)

        // v0.8.1 Change 4: hint to use multi_replace when editing same file repeatedly within 60s
        long now = System.currentTimeMillis()
        Long lastWrite = recentWrites.get(normalized)
        boolean shouldHint = lastWrite != null && (now - lastWrite) < 60_000L
        recentWrites.put(normalized, now)

        // FS-T9: brace-balance check on changed region
        String braceWarning = checkBraceBalance(updated, oldText, newText, 'replace')

        if (isWriteCompact(options)) {
            Map<String, Object> compact = [success: true, content_hash: hash, file_content_hash: hash] as Map<String, Object>
            if (braceWarning) compact.brace_warning = braceWarning
            if (normForm != null) compact.norm_applied = normForm
            return textResponse(requestId, compact)
        }
        Map<String, Object> resp = ([action: 'replace', path: normalized,
            replacements: 1, success: true,
            content_hash: hash, file_content_hash: hash] as Map<String, Object>)
        if (shouldHint) resp.hint = 'More edits to this file? Prefer multi_replace to batch them in one call.'
        if (braceWarning) resp.brace_warning = braceWarning
        if (normForm != null) resp.norm_applied = normForm
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

        String normalized   = normalizeAndCheckPath(path)
        boolean backup      = options.backup as boolean ?: false
        String encoding     = options.encoding as String ?: 'UTF-8'
        String expectedHash = options.expectedHash as String

        long fileSizeKb = Files.size(Paths.get(normalized)).intdiv(1024)
        if (fileSizeKb > replaceFileSizeThresholdKb) {
            return McpResponse.toolError(requestId,
                ("multi_replace: file is ${fileSizeKb}KB which exceeds threshold ${replaceFileSizeThresholdKb}KB. " +
                 "Use patch (line-range edit) for files larger than ${replaceFileSizeThresholdKb}KB." as String))
        }

        byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))
        String rawContent = new String(rawBytes, encoding)
        boolean hasCrLf   = rawContent.contains('\r\n')
        String snapshot   = rawContent.replace('\r\n', '\n').replace('\r', '\n')

        if (expectedHash) {
            String actualHash = WriteUtils.computeHash(rawBytes)
            if (actualHash != expectedHash) {
                return McpResponse.toolError(requestId,
                    ("multi_replace rejected: file has changed since last read (expected ${expectedHash}, got ${actualHash}). Re-read before retrying." as String))
            }
        }

        // Phase 1: pre-validate all replacements before touching the file.
        // Fix B: per-entry normalisation tracking -- record which pass resolved each entry
        // so phase 2 can apply targeted positional replace rather than normalising the whole file.
        // normForms[i] is null (raw match), 'NFC', 'NFKC', or 'box-drawing'
        List<String> normForms = new ArrayList<String>(replacements.size())
        for (int k = 0; k < replacements.size(); k++) { normForms.add(null as String) }
        String nfcSnapshot = null
        String nfkcSnapshot = null
        String bdSnapshot = null
        List<String> validationErrors = []
        replacements.eachWithIndex { Map<String, Object> rep, int i ->
            String oldText = (rep.oldText as String)?.replace('\r\n', '\n')?.replace('\r', '\n')
            if (!oldText) { validationErrors << ('Entry ' + i + ': missing oldText'); return }
            // CT-FW-REPLACE-2: newText key absent entirely is an error; explicit '' is allowed (deletion).
            if (!rep.containsKey('newText')) { validationErrors << ('Entry ' + i + ': missing newText -- pass newText:\'\' explicitly if deletion is intended'); return }
            int count = WriteUtils.countOccurrences(snapshot, oldText)
            if (count == 0) {
                // Pass 1: NFC
                if (nfcSnapshot == null) nfcSnapshot = Normalizer.normalize(snapshot, Normalizer.Form.NFC)
                String nfcOld = Normalizer.normalize(oldText, Normalizer.Form.NFC)
                int nfcCount = WriteUtils.countOccurrences(nfcSnapshot, nfcOld)
                if (nfcCount == 1) { normForms.set(i, 'NFC'); count = 1 }
                else if (nfcCount > 1) { count = nfcCount }
            }
            if (count == 0) {
                // Pass 2: NFKC
                if (nfkcSnapshot == null) nfkcSnapshot = Normalizer.normalize(snapshot, Normalizer.Form.NFKC)
                String nfkcOld = Normalizer.normalize(oldText, Normalizer.Form.NFKC)
                int nfkcCount = WriteUtils.countOccurrences(nfkcSnapshot, nfkcOld)
                if (nfkcCount == 1) { normForms.set(i, 'NFKC'); count = 1 }
                else if (nfkcCount > 1) { count = nfkcCount }
            }
            if (count == 0) {
                // Pass 3: box-drawing chars
                if (bdSnapshot == null) bdSnapshot = normalizeBoxDrawing(snapshot)
                String bdOld = normalizeBoxDrawing(oldText)
                int bdCount = WriteUtils.countOccurrences(bdSnapshot, bdOld)
                if (bdCount == 1) { normForms.set(i, 'box-drawing'); count = 1 }
                else if (bdCount > 1) { count = bdCount }
            }
            if (count == 0) validationErrors << ('Entry ' + i + ": oldText not found: '" + sanitize(oldText.take(60)) + "'")
            if (count > 1)  validationErrors << ('Entry ' + i + ': oldText not unique (' + count + " occurrences): '" + sanitize(oldText.take(60)) + "'")
        }
        if (!validationErrors) {
            // RCA-2a: containment overlap check (original)
            List<String> oldTexts = replacements.collect { ((it.oldText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n') }.findAll { it }
            for (int i = 0; i < oldTexts.size() - 1; i++) {
                for (int j = i + 1; j < oldTexts.size(); j++) {
                    String ti = oldTexts[i]
                    String tj = oldTexts[j]
                    if (ti.contains(tj)) {
                        validationErrors << ("Entry ${j}: oldText is a substring of entry ${i} \u2014 replacements overlap. " +
                            "Fix: (1) Merge into one entry, (2) Use separate file_write calls, or (3) Make entries non-overlapping." as String)
                    } else if (tj.contains(ti)) {
                        validationErrors << ("Entry ${i}: oldText is a substring of entry ${j} \u2014 replacements overlap. " +
                            "Fix: (1) Merge into one entry, (2) Use separate file_write calls, or (3) Make entries non-overlapping." as String)
                    } else {
                        // RCA-2b: suffix/prefix partial overlap check
                        // Check if any suffix of ti is a prefix of tj (or vice-versa)
                        // Use a minimum overlap length of 2 to avoid false positives on single chars
                        int minOverlap = 2
                        boolean foundOverlap = false
                        for (int olen = minOverlap; olen <= Math.min(ti.length(), tj.length()) && !foundOverlap; olen++) {
                            if (ti.endsWith(tj.substring(0, olen)) && olen == tj.length() || ti.endsWith(tj.substring(0, olen)) && tj.startsWith(ti.substring(ti.length() - olen))) {
                                // ti ends with a prefix of tj
                                validationErrors << ("Entry ${i} ends with text that starts entry ${j} \u2014 boundary overlap detected (shared: '" +
                                    sanitize(ti.substring(ti.length() - olen).take(40)) + "'). " +
                                    "Fix: (1) Merge into one entry, (2) Use separate file_write calls." as String)
                                foundOverlap = true
                            } else if (tj.endsWith(ti.substring(0, olen)) && ti.startsWith(tj.substring(tj.length() - olen))) {
                                // tj ends with a prefix of ti
                                validationErrors << ("Entry ${j} ends with text that starts entry ${i} \u2014 boundary overlap detected (shared: '" +
                                    sanitize(tj.substring(tj.length() - olen).take(40)) + "'). " +
                                    "Fix: (1) Merge into one entry, (2) Use separate file_write calls." as String)
                                foundOverlap = true
                            }
                        }
                    }
                }
            }
        }
        // RCA-2c: simulation pass -- apply all replacements to a copy and verify each entry
        // is still findable at the point it would be applied (catches entry-makes-entry-unfindable).
        if (!validationErrors) {
            String sim = snapshot
            replacements.eachWithIndex { Map<String, Object> rep, int i ->
                String oldText = ((rep.oldText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
                String newText = ((rep.newText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
                if (!sim.contains(oldText)) {
                    validationErrors << ("multi_replace aborted: applying prior entries makes entry ${i} unfindable " +
                        "\u2014 they overlap via shared boundary text. Restructure or use separate file_write calls." as String)
                    return  // stop simulation at first failure
                }
                sim = sim.replace(oldText, newText)
            }
        }
        if (validationErrors) {
            return McpResponse.toolError(requestId,
                ("multi_replace validation failed (file NOT modified): ${validationErrors.join('; ')}" as String))
        }

        // Phase 2: RCA-5 -- locate all positions first, apply in REVERSE position order.
        // Prevents earlier replacements shifting offsets for later ones (same approach as doPatch).
        // Build (startPos, resolvedOldText, newText, normForm) work items.
        List<Map<String, Object>> workItems = []
        replacements.eachWithIndex { Map<String, Object> rep, int i ->
            String oldText = ((rep.oldText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            String newText = ((rep.newText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            String entryNorm = normForms.get(i)
            String effectiveOld = oldText
            String workingSnapshot = snapshot
            if (entryNorm == 'NFC') {
                if (nfcSnapshot == null) nfcSnapshot = Normalizer.normalize(snapshot, Normalizer.Form.NFC)
                effectiveOld = Normalizer.normalize(oldText, Normalizer.Form.NFC)
                workingSnapshot = nfcSnapshot
            } else if (entryNorm == 'NFKC') {
                if (nfkcSnapshot == null) nfkcSnapshot = Normalizer.normalize(snapshot, Normalizer.Form.NFKC)
                effectiveOld = Normalizer.normalize(oldText, Normalizer.Form.NFKC)
                workingSnapshot = nfkcSnapshot
            } else if (entryNorm == 'box-drawing') {
                if (bdSnapshot == null) bdSnapshot = normalizeBoxDrawing(snapshot)
                effectiveOld = normalizeBoxDrawing(oldText)
                workingSnapshot = bdSnapshot
            }
            int startPos = workingSnapshot.indexOf(effectiveOld)
            workItems << ([startPos: startPos, oldText: oldText, newText: newText, norm: entryNorm] as Map<String, Object>)
        }
        // Sort descending by startPos -- highest offset first (RCA-5)
        workItems.sort { Map<String, Object> a, Map<String, Object> b ->
            (b.startPos as int) <=> (a.startPos as int)
        }

        // Apply in reverse position order
        String current = snapshot
        int applied = 0
        List<String> normsApplied = []
        workItems.each { Map<String, Object> item ->
            String oldText = item.oldText as String
            String newText = item.newText as String
            String entryNorm = item.norm as String
            if (entryNorm == null) {
                current = current.replace(oldText, newText)
            } else {
                String normCurrent
                String normOld
                if (entryNorm == 'NFC') {
                    normCurrent = Normalizer.normalize(current, Normalizer.Form.NFC)
                    normOld = Normalizer.normalize(oldText, Normalizer.Form.NFC)
                } else if (entryNorm == 'NFKC') {
                    normCurrent = Normalizer.normalize(current, Normalizer.Form.NFKC)
                    normOld = Normalizer.normalize(oldText, Normalizer.Form.NFKC)
                } else {
                    normCurrent = normalizeBoxDrawing(current)
                    normOld = normalizeBoxDrawing(oldText)
                }
                current = positionalReplace(current, normCurrent, normOld, newText)
                normsApplied << entryNorm
            }
            applied++
        }
        if (normsApplied) log.debug('multi_replace: per-entry normalisation applied {} for {}', normsApplied.join(', '), normalized)

        // RCA-7: brace check on SIMULATED result BEFORE write -- file NOT modified if unbalanced
        String simulated = snapshot
        replacements.each { Map<String, Object> rep ->
            String ot = ((rep.oldText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            String nt = ((rep.newText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            simulated = simulated.replace(ot, nt)
        }
        String braceError = null
        replacements.each { Map<String, Object> rep ->
            String ot = ((rep.oldText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            String nt = ((rep.newText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            String w  = checkBraceBalance(simulated, ot, nt, 'multi_replace')
            if (w && !braceError) braceError = w
        }
        if (braceError) {
            log.warn('multi_replace: brace check REJECTED {} -- {}', normalized, braceError)
            return McpResponse.toolError(requestId,
                'multi_replace brace check failed (file NOT modified): ' + braceError)
        }

        Path target = Paths.get(normalized)
        if (backup) WriteUtils.makeBackup(target)
        if (hasCrLf) current = current.replace('\n', '\r\n')
        // FS-T10: bare-box-drawing check (same guard as doReplace)
        // Use LF-normalised 'current' before CRLF re-insertion for consistent detection.
        String bareBoxError2 = checkBareBoxDrawing(current, normalized)
        if (bareBoxError2) {
            log.warn('multi_replace: bare-box-drawing check REJECTED {} -- {}', normalized, bareBoxError2)
            return McpResponse.toolError(requestId,
                'multi_replace bare_box_drawing check failed (file NOT modified): ' + bareBoxError2)
        }
        WriteUtils.atomicWrite(target, current.getBytes(encoding))
        log.info('multi_replace: {} applied in {} (line endings: {})', applied, normalized, hasCrLf ? 'CRLF' : 'LF')

        String hash = WriteUtils.fileHash(target)
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
    // Helper
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // FS-T10: bare-box-drawing checker
    // -----------------------------------------------------------------------

    /**
     * Checks whether any line in the simulated post-replacement content starts
     * (after leading whitespace) with a Unicode box-drawing character (U+2500..U+257F)
     * that is NOT prefixed by a // comment marker on that line.
     *
     * Only enforced for code files: .groovy, .java, .kt
     * Safe for .txt, .md, .adoc, .yml etc.
     *
     * @param content     LF-normalised simulated file content after all replacements
     * @param filePath    absolute path (used to determine file type)
     * @return            error string if bare box-drawing detected; null if clean
     */
    @CompileStatic
    private static String checkBareBoxDrawing(String content, String filePath) {
        if (!filePath) return null
        String lc = filePath.toLowerCase(Locale.ROOT)
        if (!lc.endsWith('.groovy') && !lc.endsWith('.java') && !lc.endsWith('.kt')) return null
        String[] lines = content.split('\n', -1)
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i]
            // Find first non-whitespace char
            int firstNonWs = -1
            for (int j = 0; j < line.length(); j++) {
                if (line.charAt(j) != ' ' && line.charAt(j) != '\t') {
                    firstNonWs = j
                    break
                }
            }
            if (firstNonWs < 0) continue  // blank/whitespace-only line
            int cp = line.codePointAt(firstNonWs)
            if (cp >= 0x2500 && cp <= 0x257F) {
                return ('Line ' + (i + 1) + ' starts with bare box-drawing character U+' +
                    Integer.toHexString(cp).toUpperCase(Locale.ROOT).padLeft(4, '0') +
                    '. Section dividers must be inside // comments (e.g. "// \u2500\u2500 Section ").' +
                    ' RECOVERY: ensure newText includes "// " prefix before \u2500 characters.' +
                    ' [bare_box_drawing_hint]')
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // FS-T9: brace-balance checker
    // -----------------------------------------------------------------------

    /**
     * Counts '{' and '}' in the newText and the immediately surrounding context
     * (5 lines before the replacement start, 5 lines after the replacement end).
     * If the newText alone is unbalanced, returns a diagnostic warning string;
     * otherwise returns null (no warning).
     *
     * Not a hard error -- the replacement is always applied. The warning surfaces
     * the most common silent corruption: forgetting to include closing braces that
     * were present in oldText.
     */
    private static String checkBraceBalance(String fullContent, String oldText, String newText, String action) {
        if (!oldText || !newText) return null
        // Only check Groovy/Java-like files based on content heuristics
        // (skip if no braces at all in the replacement context)
        int newOpen  = newText.count('{')
        int newClose = newText.count('}')
        int oldOpen  = oldText.count('{')
        int oldClose = oldText.count('}')
        // Delta: how many net braces does the replacement ADD compared to what was removed
        int deltaOpen  = newOpen  - oldOpen
        int deltaClose = newClose - oldClose
        if (deltaOpen == deltaClose) return null  // balanced replacement -- no warning

        // newText itself is unbalanced internally
        int newInternal = newOpen - newClose
        if (newInternal == 0) return null  // newText is self-contained, net delta is structural -- OK

        // Find approximate line number of the replacement
        int replIdx    = fullContent.indexOf(newText)
        int lineNumber = replIdx >= 0 ? (fullContent.substring(0, replIdx).count('\n') + 1) : -1
        String lineRef = lineNumber > 0 ? " near line ${lineNumber}" : ''

        return ("${action}: replacement${lineRef} may have unbalanced braces " +
            "(newText has ${newOpen} open, ${newClose} close; oldText had ${oldOpen} open, ${oldClose} close). " +
            "Verify closing braces were included in newText — silent method-boundary corruption if not." as String)
    }

    private String normalizeAndCheckPath(String path) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        return normalized
    }
}
