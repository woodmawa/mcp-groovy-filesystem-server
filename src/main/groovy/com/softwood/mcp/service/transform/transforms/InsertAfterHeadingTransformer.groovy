package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Paths
import java.util.regex.Matcher

/**
 * insert_after_heading — inserts lines immediately after a heading line.
 * Does not replace any existing content — purely additive.
 *
 * Required options:
 *   heading  — heading text to find (without # prefix for markdown style)
 *   content  — text to insert as new lines after the heading
 *
 * Optional options:
 *   headingStyle — 'markdown' (default, matches ## Heading) or 'plain' (exact line match)
 *
 * On not-found: hint lists all headings found in the file.
 *
 * v0.8.2
 */
@Component
@CompileStatic
class InsertAfterHeadingTransformer implements FileTransformer {

    @Override
    String getName() { 'insert_after_heading' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String heading = options.heading as String
        String content = options.content as String
        String style   = (options.headingStyle as String) ?: 'markdown'

        if (!heading) {
            return new TransformResult(success: false,
                error: 'options.heading is required for insert_after_heading')
        }
        if (!content) {
            return new TransformResult(success: false,
                error: 'options.content is required for insert_after_heading')
        }

        List<String> lines = new File(normalizedPath).readLines('UTF-8')

        // --- Find the heading line ---
        int headingIdx = -1
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i]
            if (style == 'markdown') {
                Matcher m = (line =~ /^(#+)\s+(.+)$/)
                if (m.find() && m.group(2).trim() == heading.trim()) {
                    headingIdx = i
                    break
                }
            } else {
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
                if (m.find()) {
                    found << "${m.group(1)} ${m.group(2).trim()}".toString()
                }
            }
            String hint = found
                ? "Available headings: ${found.join(' | ')}"
                : 'No headings found in file'
            return new TransformResult(success: false,
                error: "Heading '${heading}' not found", hint: hint)
        }

        String[] parts = content.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
        List<String> insertLines = Arrays.asList(parts)

        List<String> result = []
        result.addAll(lines.subList(0, headingIdx + 1))
        result.addAll(insertLines)
        result.addAll(lines.subList(headingIdx + 1, lines.size()))

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        return new TransformResult(
            success      : true,
            linesAffected: insertLines.size(),
            message      : "Inserted ${insertLines.size()} lines after heading '${heading}'"
        )
    }
}
