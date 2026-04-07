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


    McpResponse doReplace(String path, Map<String, Object> options, Object requestId) {
        String oldText      = options.oldText as String
        String newText      = (options.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
        String expectedHash = options.expectedHash as String
        if (!expectedHash) {
            log.warn('doReplace called without expectedHash for {} — drift guard disabled. Caller should pass expectedHash from last read.', path)
        }
        if (!oldText) return McpResponse.error(requestId, -32602, 'options.oldText required for replace')

        String normalized = normalizeAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        long fileSizeKb = Files.size(Paths.get(normalized)).intdiv(1024)
        if (fileSizeKb > replaceFileSizeThresholdKb) {
            return McpResponse.error(requestId, -32602,
                ("replace: file is ${fileSizeKb}KB which exceeds threshold ${replaceFileSizeThresholdKb}KB. " +
                 "Use multi_replace for up to ${replaceFileSizeThresholdKb}KB, or patch (line-range) for larger files." as String))
        }

        byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))
        String rawContent = new String(rawBytes, encoding)
        boolean hasCrLf   = rawContent.contains('\r\n')
        String current    = rawContent.replace('\r\n', '\n').replace('\r', '\n')

        // Drift guard
        if (expectedHash) {
            String actualHash = WriteUtils.computeHash(rawBytes)
            if (actualHash != expectedHash) {
                return McpResponse.error(requestId, -32602,
                    ("expectedHash mismatch: file has changed since your last read (expected ${expectedHash}, got ${actualHash}). " +
                     "Re-read the target lines with action=range or action=get_method to get the current content_hash, then retry." as String))
            }
        }

        // Normalize oldText line endings (same as file content)
        oldText = oldText.replace('\r\n', '\n').replace('\r', '\n')

        int count = WriteUtils.countOccurrences(current, oldText)

        // If no match, try progressive Unicode normalisation: NFC -> NFKC -> box-drawing chars
        boolean usedNormalized = false
        if (count == 0) {
            // Pass 1: NFC - canonical decomposition + canonical composition
            String nfcContent = Normalizer.normalize(current, Normalizer.Form.NFC)
            String nfcOldText = Normalizer.normalize(oldText, Normalizer.Form.NFC)
            if (WriteUtils.countOccurrences(nfcContent, nfcOldText) == 1) {
                current = nfcContent; oldText = nfcOldText
                newText = Normalizer.normalize(newText, Normalizer.Form.NFC)
                count = 1; usedNormalized = true
                log.debug('replace: NFC normalisation resolved match for {}', normalized)
            }
        }
        if (count == 0) {
            // Pass 2: NFKC - compatibility decomposition (en/em dash variants, smart quotes)
            String nfkcContent = Normalizer.normalize(current, Normalizer.Form.NFKC)
            String nfkcOldText = Normalizer.normalize(oldText, Normalizer.Form.NFKC)
            if (WriteUtils.countOccurrences(nfkcContent, nfkcOldText) == 1) {
                current = nfkcContent; oldText = nfkcOldText
                newText = Normalizer.normalize(newText, Normalizer.Form.NFKC)
                count = 1; usedNormalized = true
                log.debug('replace: NFKC normalisation resolved match for {}', normalized)
            }
        }
        if (count == 0) {
            // Pass 3: box-drawing chars (U+2500-U+257F -> ASCII dashes/pipes)
            String bdContent = normalizeBoxDrawing(current)
            String bdOldText = normalizeBoxDrawing(oldText)
            if (WriteUtils.countOccurrences(bdContent, bdOldText) == 1) {
                current = bdContent; oldText = bdOldText
                newText = normalizeBoxDrawing(newText)
                count = 1; usedNormalized = true
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
                    if (trimFl.containsIgnoreCase(firstLine) || firstLine.containsIgnoreCase(trimFl)) {
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
            return McpResponse.error(requestId, -32602, new groovy.json.JsonBuilder(err).toString())
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
            return McpResponse.error(requestId, -32602,
                ('replace: oldText appears ' + count + ' times' + lineInfo + ' (must be unique). Provide more context.'))
        }

        String updated = current.replace(oldText, newText)
        Path target = Paths.get(normalized)
        if (hasCrLf && !WriteUtils.shouldNormaliseLf(target)) updated = updated.replace('\n', '\r\n')

        if (backup) WriteUtils.makeBackup(target)
        WriteUtils.atomicWrite(target, updated.getBytes(encoding))

        log.debug("replace: 1 occurrence in {} (line endings: {}{})", normalized,
            WriteUtils.shouldNormaliseLf(target) ? 'LF (normalised)' : (hasCrLf ? 'CRLF (preserved)' : 'LF'),
            usedNormalized ? ', NFC-normalised' : '')
        String hash = WriteUtils.fileHash(target)

        // v0.8.1 Change 4: hint to use multi_replace when editing same file repeatedly within 60s
        long now = System.currentTimeMillis()
        Long lastWrite = recentWrites.get(normalized)
        boolean shouldHint = lastWrite != null && (now - lastWrite) < 60_000L
        recentWrites.put(normalized, now)

        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash, file_content_hash: hash])
        }
        Map<String, Object> resp = ([action: 'replace', path: normalized,
            replacements: 1, success: true,
            content_hash: hash, file_content_hash: hash] as Map<String, Object>)
        if (shouldHint) resp.hint = 'More edits to this file? Prefer multi_replace to batch them in one call.'
        return textResponse(requestId, resp)
    }

    // -----------------------------------------------------------------------
    // multi_replace
    // -----------------------------------------------------------------------

    McpResponse doMultiReplace(String path, Map<String, Object> options, Object requestId) {
        List<Map<String, Object>> replacements = (options.replacements as List<Map<String, Object>>) ?: []
        if (!replacements) return McpResponse.error(requestId, -32602, 'options.replacements required for multi_replace')

        String normalized   = normalizeAndCheckPath(path)
        boolean backup      = options.backup as boolean ?: false
        String encoding     = options.encoding as String ?: 'UTF-8'
        String expectedHash = options.expectedHash as String

        long fileSizeKb = Files.size(Paths.get(normalized)).intdiv(1024)
        if (fileSizeKb > replaceFileSizeThresholdKb) {
            return McpResponse.error(requestId, -32602,
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
                return McpResponse.error(requestId, -32602,
                    ("multi_replace rejected: file has changed since last read (expected ${expectedHash}, got ${actualHash}). Re-read before retrying." as String))
            }
        }

        // Phase 1: pre-validate all replacements before touching the file
        // Try raw match first, then NFC-normalized match as fallback
        boolean usedNormalized = false
        String nfcSnapshot = null
        List<String> validationErrors = []
        replacements.eachWithIndex { Map<String, Object> rep, int i ->
            String oldText = (rep.oldText as String)?.replace('\r\n', '\n')?.replace('\r', '\n')
            if (!oldText) { validationErrors << ("Entry ${i}: missing oldText" as String); return }
            int count = WriteUtils.countOccurrences(snapshot, oldText)
            if (count == 0) {
                // Pass 1: NFC
                if (nfcSnapshot == null) nfcSnapshot = Normalizer.normalize(snapshot, Normalizer.Form.NFC)
                String nfcOld = Normalizer.normalize(oldText, Normalizer.Form.NFC)
                int nfcCount = WriteUtils.countOccurrences(nfcSnapshot, nfcOld)
                if (nfcCount == 1) { usedNormalized = true; count = 1 }
                else if (nfcCount > 1) { count = nfcCount }
            }
            if (count == 0) {
                // Pass 2: NFKC (en/em dash variants, smart quotes)
                String nfkcSnap = Normalizer.normalize(snapshot, Normalizer.Form.NFKC)
                String nfkcOld = Normalizer.normalize(oldText, Normalizer.Form.NFKC)
                int nfkcCount = WriteUtils.countOccurrences(nfkcSnap, nfkcOld)
                if (nfkcCount == 1) { usedNormalized = true; count = 1 }
                else if (nfkcCount > 1) { count = nfkcCount }
            }
            if (count == 0) {
                // Pass 3: box-drawing chars (U+2500-U+257F -> ASCII)
                String bdSnap = normalizeBoxDrawing(snapshot)
                String bdOld  = normalizeBoxDrawing(oldText)
                int bdCount = WriteUtils.countOccurrences(bdSnap, bdOld)
                if (bdCount == 1) { usedNormalized = true; count = 1 }
                else if (bdCount > 1) { count = bdCount }
            }
            if (count == 0) validationErrors << ("Entry ${i}: oldText not found: '${sanitize(oldText.take(60))}'" as String)
            if (count > 1)  validationErrors << ("Entry ${i}: oldText not unique (${count} occurrences): '${sanitize(oldText.take(60))}'" as String)
        }
        if (!validationErrors) {
            List<String> oldTexts = replacements.collect { (it.oldText as String) ?: '' }.findAll { it }
            for (int i = 0; i < oldTexts.size() - 1; i++) {
                for (int j = i + 1; j < oldTexts.size(); j++) {
                    if (oldTexts[i].contains(oldTexts[j])) {
                        validationErrors << ("Entry ${j}: oldText is a substring of entry ${i} \u2014 replacements overlap and will interact unexpectedly" as String)
                    } else if (oldTexts[j].contains(oldTexts[i])) {
                        validationErrors << ("Entry ${i}: oldText is a substring of entry ${j} \u2014 replacements overlap and will interact unexpectedly" as String)
                    }
                }
            }
        }
        if (validationErrors) {
            return McpResponse.error(requestId, -32602,
                ("multi_replace validation failed (file NOT modified): ${validationErrors.join('; ')}" as String))
        }

        // Phase 2: apply using the same normalisation that resolved the match (all three passes are idempotent)
        String current = usedNormalized
            ? normalizeBoxDrawing(Normalizer.normalize(snapshot, Normalizer.Form.NFKC))
            : snapshot
        int applied = 0
        replacements.each { Map<String, Object> rep ->
            String oldText = (rep.oldText as String)?.replace('\r\n', '\n')?.replace('\r', '\n')
            String newText = (rep.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
            if (usedNormalized) {
                oldText = normalizeBoxDrawing(Normalizer.normalize(oldText, Normalizer.Form.NFKC))
                newText = normalizeBoxDrawing(Normalizer.normalize(newText, Normalizer.Form.NFKC))
            }
            current = current.replace(oldText, newText)
            applied++
        }
        if (usedNormalized) log.debug('multi_replace: unicode normalisation resolved matches for {}', normalized)


        Path target = Paths.get(normalized)
        if (backup) WriteUtils.makeBackup(target)
        if (hasCrLf) current = current.replace('\n', '\r\n')
        WriteUtils.atomicWrite(target, current.getBytes(encoding))
        log.info("multi_replace: {} applied in {} (line endings: {})", applied, normalized, hasCrLf ? 'CRLF' : 'LF')

        String hash = WriteUtils.fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, applied: applied, content_hash: hash, file_content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'multi_replace', path: normalized,
            applied: applied, success: true,
            content_hash: hash, file_content_hash: hash
        ])
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
