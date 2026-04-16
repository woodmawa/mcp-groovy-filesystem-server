package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Paths

/**
 * insert_before_match -- inserts one or more lines immediately BEFORE the first line
 * that contains the given match string (substring search, case-sensitive).
 * Purely additive -- no existing content is replaced.
 * Format-agnostic: works on any file type (.groovy, .java, .gradle, .md, .yml, etc.)
 *
 * Required options:
 *   match    -- substring to find in target line
 *   content  -- text to insert as new lines before the matched line
 *
 * Optional options:
 *   occurrence   -- which match to use: 1 (default/first), -1 (last), or N (Nth, 1-based)
 *   matchLast    -- boolean; true = insert before LAST occurrence (alias for occurrence=-1)
 *   fromLine     -- integer (1-based); ignore occurrences on lines before this number
 *   matchIsRegex -- boolean (default false); if true, options.match is compiled as a Java
 *                   regex Pattern and matched via find() instead of contains().
 *                   Enables anchored patterns like ^}$ to match closing braces exactly.
 *                   Regex errors return a structured toolError with the parse message.
 *
 * fromLine and matchLast/occurrence compose: fromLine restricts the search window first,
 * then occurrence/-1/matchLast resolves within that window.
 *
 * On not-found: error lists up to 10 sample lines to aid diagnosis.
 *
 * Use-case example -- inserting a new version comment above the previous one in build.gradle:
 *   transform: insert_before_match
 *   match:    '// v0.8.41:'
 *   content:  '// v0.8.42: ...'
 *
 * v0.8.56
 */
@Component
@CompileStatic
class InsertBeforeMatchTransformer implements FileTransformer {

    @Override
    String getName() { 'insert_before_match' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String match   = options.match as String
        String content = options.content as String
        // occurrence: 1=first (default), -1=last, N=Nth. matchLast=true is alias for -1.
        boolean matchLast  = options.matchLast  != null ? (options.matchLast  as boolean) : false
        boolean matchIsRegex = options.matchIsRegex != null ? (options.matchIsRegex as boolean) : false
        int occurrence = matchLast ? -1 : (options.occurrence != null ? (options.occurrence as int) : 1)
        // fromLine: 1-based; ignore matches on lines strictly before this line number
        int fromLine = options.fromLine != null ? (options.fromLine as int) : 1

        // Compile regex pattern if requested -- fail fast with useful error
        java.util.regex.Pattern matchPattern = null
        if (matchIsRegex) {
            try {
                matchPattern = java.util.regex.Pattern.compile(match)
            } catch (java.util.regex.PatternSyntaxException e) {
                return new TransformResult(success: false,
                    error: "options.match is not a valid regex: ${e.message}")
            }
        }

        if (!match) {
            return new TransformResult(success: false,
                error: 'options.match is required for insert_before_match')
        }
        if (!content) {
            return new TransformResult(success: false,
                error: 'options.content is required for insert_before_match')
        }
        // FS-T1: multi-line match strings fail silently due to line-ending variation.
        // Return a clear error instead of a silent no-op.
        if (match.contains('\n') || match.contains('\r')) {
            return new TransformResult(success: false,
                error: 'options.match contains newline characters — insert_before_match uses single-line substring matching only. ' +
                       'Use a unique single-line anchor from the target region (e.g. the first line of the block you want to insert before).')
        }

        List<String> lines = new File(normalizedPath).readLines('UTF-8')

        // Collect all matching line indices
        List<Integer> matchIndices = []
        for (int i = 0; i < lines.size(); i++) {
            // fromLine is 1-based; skip lines before it
            if ((i + 1) < fromLine) continue
            boolean lineMatches = matchIsRegex
                ? matchPattern.matcher(lines[i]).find()
                : lines[i].contains(match)
            if (lineMatches) {
                matchIndices << i
            }
        }

        if (matchIndices.isEmpty()) {
            // Provide a sample of lines to aid diagnosis
            List<String> sample = []
            int limit = Math.min(10, lines.size())
            for (int i = 0; i < limit; i++) {
                String preview = lines[i].size() > 80 ? lines[i].substring(0, 80) : lines[i]
                sample << ('  line ' + (i + 1) + ': ' + preview)
            }
            return new TransformResult(success: false,
                error: "No line containing '" + match + "' found in file",
                hint : 'First ' + limit + ' lines:\n' + sample.join('\n'))
        }

        // Resolve occurrence: 1=first, -1=last, N=Nth (1-based)
        int targetIdx
        if (occurrence == -1) {
            targetIdx = matchIndices.last()
        } else {
            int occ = Math.max(1, occurrence)
            if (occ > matchIndices.size()) {
                return new TransformResult(success: false,
                    error: 'occurrence=' + occ + ' requested but only ' + matchIndices.size() + " match(es) found for '" + match + "'")
            }
            targetIdx = matchIndices[occ - 1]
        }

        // Split content on newlines (normalise CRLF)
        String[] parts = content.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
        List<String> insertLines = Arrays.asList(parts)

        List<String> result = []
        result.addAll(lines.subList(0, targetIdx))
        result.addAll(insertLines)
        result.addAll(lines.subList(targetIdx, lines.size()))

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        return new TransformResult(
            success      : true,
            linesAffected: insertLines.size(),
            message      : 'Inserted ' + insertLines.size() + ' line(s) before line ' + (targetIdx + 1) +
                           (matchIsRegex ? " (regex='" : " (match='") + match + "')"
        )
    }
}
