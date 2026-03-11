package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Paths

/**
 * replace_between — replaces all content between two unique anchor strings.
 * The lines containing the anchors are preserved; only the lines between them are replaced.
 *
 * Required options:
 *   startAnchor  — unique string identifying the start boundary line (line is preserved)
 *   endAnchor    — unique string identifying the end boundary line (line is preserved)
 *   newContent   — replacement content to insert between the anchors
 *
 * Error cases:
 *   startAnchor == endAnchor         → error
 *   startAnchor not found            → error
 *   endAnchor not found after start  → error
 *
 * v0.8.2
 */
@Component
@CompileStatic
class ReplaceBetweenTransformer implements FileTransformer {

    @Override
    String getName() { 'replace_between' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String startAnchor = options.startAnchor as String
        String endAnchor   = options.endAnchor as String
        String newContent  = options.newContent as String

        if (!startAnchor) {
            return new TransformResult(success: false,
                error: 'options.startAnchor is required for replace_between')
        }
        if (!endAnchor) {
            return new TransformResult(success: false,
                error: 'options.endAnchor is required for replace_between')
        }
        if (newContent == null) {
            return new TransformResult(success: false,
                error: 'options.newContent is required for replace_between')
        }
        if (startAnchor == endAnchor) {
            return new TransformResult(success: false,
                error: 'startAnchor and endAnchor must be different strings')
        }

        List<String> lines = new File(normalizedPath).readLines('UTF-8')

        int startIdx = -1
        int endIdx   = -1
        for (int i = 0; i < lines.size(); i++) {
            if (startIdx == -1 && lines[i].contains(startAnchor)) {
                startIdx = i
            } else if (startIdx != -1 && lines[i].contains(endAnchor)) {
                endIdx = i
                break
            }
        }

        if (startIdx == -1) {
            return new TransformResult(success: false,
                error: "startAnchor not found: '${startAnchor}'")
        }
        if (endIdx == -1) {
            return new TransformResult(success: false,
                error: "endAnchor not found after startAnchor: '${endAnchor}'")
        }

        int oldBodyLines = endIdx - startIdx - 1

        List<String> result = []
        result.addAll(lines.subList(0, startIdx + 1))      // up to and including startAnchor line
        if (newContent) {
            String[] parts = newContent.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
            result.addAll(Arrays.asList(parts))
        }
        result.addAll(lines.subList(endIdx, lines.size()))  // from endAnchor line onwards

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        int newBodyLines = newContent
            ? newContent.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1).length
            : 0
        return new TransformResult(
            success      : true,
            linesAffected: oldBodyLines,
            message      : "Content between anchors replaced (${oldBodyLines} lines \u2192 ${newBodyLines} lines)"
        )
    }
}
