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
 */
@Service
@Slf4j
@CompileStatic
class FileReplaceService extends AbstractFileService {

    @Value('${mcp.filesystem.read-chunk-threshold-kb:300}')
    int replaceChunkThresholdKb

    FileReplaceService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // replace
    // -----------------------------------------------------------------------

    McpResponse doReplace(String path, Map<String, Object> options, Object requestId) {
        String oldText      = options.oldText as String
        String newText      = (options.newText as String ?: '').replace('\r\n', '\n').replace('\r', '\n')
        String expectedHash = options.expectedHash as String
        if (!oldText) return McpResponse.error(requestId, -32602, 'options.oldText required for replace')

        String normalized = normalizeAndCheckPath(path)
        boolean backup    = options.backup as boolean ?: false
        String encoding   = options.encoding as String ?: 'UTF-8'

        long fileSizeKb = Files.size(Paths.get(normalized)).intdiv(1024)
        if (fileSizeKb > replaceChunkThresholdKb) {
            return McpResponse.error(requestId, -32602,
                ("replace: file is ${fileSizeKb}KB which exceeds threshold ${replaceChunkThresholdKb}KB. Use patch (line-range edit) for large files." as String))
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

        int count = WriteUtils.countOccurrences(current, oldText)
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
                error: 'oldText not found in file. Check exact whitespace/newlines. NOTE: replace matches exact bytes \u2014 for strings containing non-ASCII characters (em-dashes, smart quotes, etc.) use patch with explicit startLine/endLine instead.',
                line_endings: hasCrLf ? 'CRLF' : 'LF',
                oldText_first_line: firstLine.take(120)
            ] as Map<String, Object>
            if (nearestLine > 0) err.nearest_match = [line: nearestLine, content: nearestContent?.take(120)]
            List<Integer> nonAsciiPositions = []
            oldText.eachWithIndex { char c, int i -> if (c < 32 || c > 126) nonAsciiPositions << i }
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

        log.debug("replace: 1 occurrence in {} (line endings: {})", normalized,
            WriteUtils.shouldNormaliseLf(target) ? 'LF (normalised)' : (hasCrLf ? 'CRLF (preserved)' : 'LF'))
        String hash = WriteUtils.fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash, file_content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'replace', path: normalized,
            replacements: 1, success: true,
            content_hash: hash, file_content_hash: hash
        ])
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
        if (fileSizeKb > replaceChunkThresholdKb) {
            return McpResponse.error(requestId, -32602,
                ("multi_replace: file is ${fileSizeKb}KB which exceeds threshold ${replaceChunkThresholdKb}KB. Use patch (line-range edit) for large files." as String))
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
        List<String> validationErrors = []
        replacements.eachWithIndex { Map<String, Object> rep, int i ->
            String oldText = rep.oldText as String
            if (!oldText) { validationErrors << ("Entry ${i}: missing oldText" as String); return }
            int count = WriteUtils.countOccurrences(snapshot, oldText)
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
