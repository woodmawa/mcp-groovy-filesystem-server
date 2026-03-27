package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Paths
import java.util.regex.Matcher

/**
 * replace_section — replaces the body of a markdown/text section delimited by headings.
 *
 * Required options:
 *   heading      — exact heading text (e.g. "Architecture Overview", without # prefix)
 *   newContent   — replacement body text (heading line is preserved, not replaced)
 *
 * Optional options:
 *   headingStyle — 'markdown' (default, matches ## Heading) |
 *                   'plain'    (exact full-line match) |
 *                   'text'     (any line containing the anchor string — works on source files,
 *                               comment blocks, arbitrary text markers)
 *
 * Behaviour:
 *   Finds the heading/anchor line, replaces all lines from anchor+1 up to (but not including)
 *   the next heading of equal or lesser depth (markdown), the next occurrence of any
 *   anchor-style line (plain/text), or EOF.
 *
 * On not-found: hint lists all headings/candidate lines found in the file.
 *
 * v0.8.2 initial. v0.8.33: added headingStyle=text for arbitrary anchor matching.
 */
@Component
@CompileStatic
class ReplaceSectionTransformer implements FileTransformer {

    @Override
    String getName() { 'replace_section' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String heading    = options.heading as String
        String newContent = options.newContent as String
        String style      = (options.headingStyle as String) ?: 'markdown'

        if (!heading) {
            return new TransformResult(success: false,
                error: 'options.heading is required for replace_section')
        }
        if (newContent == null) {
            return new TransformResult(success: false,
                error: 'options.newContent is required for replace_section')
        }

        List<String> lines = new File(normalizedPath).readLines('UTF-8')

        // --- Find the heading/anchor line ---
        int headingIdx   = -1
        int headingDepth = 0
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i]
            if (style == 'markdown') {
                Matcher m = (line =~ /^(#+)\s+(.+)$/)
                if (m.find() && m.group(2).trim() == heading.trim()) {
                    headingIdx   = i
                    headingDepth = m.group(1).length()
                    break
                }
            } else if (style == 'text') {
                // text: any line containing the anchor string (case-sensitive)
                if (line.contains(heading)) {
                    headingIdx = i
                    break
                }
            } else {
                // plain: exact full-line match
                if (line.trim() == heading.trim()) {
                    headingIdx = i
                    break
                }
            }
        }

        if (headingIdx == -1) {
            List<String> found = []
            for (String line : lines) {
                Matcher m = (line =~ /^(#+)\s+(.+)$/)
                if (m.find()) found << "${m.group(1)} ${m.group(2).trim()}".toString()
            }
            String hint = found
                ? "Available headings: ${found.join(' | ')}"
                : 'No markdown headings found. Try headingStyle=text with a unique anchor string present in the file.'
            return new TransformResult(success: false,
                error: "Heading/anchor '${heading}' not found (style=${style})", hint: hint)
        }

        // --- Find end of section ---
        int sectionEnd = lines.size()
        if (style == 'markdown' && headingDepth > 0) {
            // markdown: next heading of equal or lesser depth
            for (int i = headingIdx + 1; i < lines.size(); i++) {
                Matcher m = (lines[i] =~ /^(#+)\s/)
                if (m.find() && m.group(1).length() <= headingDepth) {
                    sectionEnd = i
                    break
                }
            }
        } else if (style == 'text' || style == 'plain') {
            // text/plain: next line that also matches the anchor pattern, or EOF
            // This lets callers use repeated sentinel comments as section delimiters
            for (int i = headingIdx + 1; i < lines.size(); i++) {
                String line = lines[i]
                boolean nextAnchor = (style == 'text')
                    ? line.contains(heading)
                    : (line.trim() == heading.trim())
                if (nextAnchor) {
                    sectionEnd = i
                    break
                }
            }
        }

        int oldBodyLines = sectionEnd - headingIdx - 1

        // --- Build replacement ---
        List<String> result = []
        result.addAll(lines.subList(0, headingIdx + 1))   // preserve heading line
        if (newContent) {
            String[] parts = newContent.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
            result.addAll(Arrays.asList(parts))
        }
        result.addAll(lines.subList(sectionEnd, lines.size()))

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        int newBodyLines = result.size() - lines.size() + oldBodyLines
        return new TransformResult(
            success      : true,
            linesAffected: oldBodyLines,
            message      : "Section '${heading}' replaced (${oldBodyLines} lines \u2192 ${newBodyLines} lines)"
        )
    }
}
