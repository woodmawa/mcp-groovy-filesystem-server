package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Paths

/**
 * append_section — appends a new heading + body at the end of the file.
 *
 * Required options:
 *   heading       — new section heading text (without # prefix)
 *   content       — body text for the new section
 *
 * Optional options:
 *   headingDepth  — integer 1–6; e.g. 2 produces "## Heading" (default 2)
 *
 * A blank separator line is prepended before the heading.
 *
 * v0.8.2
 */
@Component
@CompileStatic
class AppendSectionTransformer implements FileTransformer {

    @Override
    String getName() { 'append_section' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String heading = options.heading as String
        String content = options.content as String
        int depth      = options.headingDepth != null ? (options.headingDepth as int) : 2

        if (!heading) {
            return new TransformResult(success: false,
                error: 'options.heading is required for append_section')
        }
        if (content == null) {
            return new TransformResult(success: false,
                error: 'options.content is required for append_section')
        }
        if (depth < 1 || depth > 6) {
            return new TransformResult(success: false,
                error: "headingDepth must be 1\u20136, got ${depth}")
        }

        List<String> lines = new File(normalizedPath).readLines('UTF-8')

        String hashes      = '#' * depth
        String headingLine = "${hashes} ${heading}"

        List<String> newSection = []
        newSection << ''           // blank separator line
        newSection << headingLine
        if (content) {
            String[] parts = content.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
            newSection.addAll(Arrays.asList(parts))
        }

        List<String> result = []
        result.addAll(lines)
        result.addAll(newSection)

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        return new TransformResult(
            success      : true,
            linesAffected: newSection.size(),
            message      : "Appended section '${headingLine}' (${newSection.size()} lines added)"
        )
    }
}
