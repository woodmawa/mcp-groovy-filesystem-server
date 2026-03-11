package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.StructureCache
import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * replace_method — replaces a named method body in a Groovy/Java source file.
 * Driven by method name, not line numbers — stable across edits.
 *
 * Required options:
 *   method   — method name to find (exact word-boundary match, or substring if fuzzy=true)
 *   newBody  — complete replacement text (signature + body + closing brace)
 *
 * Optional options:
 *   fuzzy    — true: substring match on method name; false (default): word-boundary match
 *
 * Uses StructureCache to locate the method's startLine/endLine without reading file content.
 * On not-found: hint lists all method names in the file.
 *
 * v0.8.2
 */
@Component
@CompileStatic
class ReplaceMethodTransformer implements FileTransformer {

    @Autowired
    StructureCache structureCache

    @Override
    String getName() { 'replace_method' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String methodName = options.method as String
        String newBody    = options.newBody as String
        boolean fuzzy     = options.fuzzy as Boolean ?: false

        if (!methodName) {
            return new TransformResult(success: false,
                error: 'options.method is required for replace_method')
        }
        if (!newBody) {
            return new TransformResult(success: false,
                error: 'options.newBody is required for replace_method')
        }

        Map structureResult = structureCache.getStructure(normalizedPath)
        List<Map> entries   = (structureResult.structure as List<Map>) ?: []

        // --- Find the method entry ---
        Map found        = null
        Pattern exactPat = Pattern.compile('\\b' + Pattern.quote(methodName) + '\\s*\\(')
        for (Map entry : entries) {
            if ((entry.type as String) != 'method') continue
            String sig = entry.content as String
            if (fuzzy) {
                if (sig.contains(methodName)) { found = entry; break }
            } else {
                Matcher m = exactPat.matcher(sig)
                if (m.find()) { found = entry; break }
            }
        }

        if (!found) {
            List<String> methodNames = []
            for (Map e : entries) {
                if ((e.type as String) == 'method') {
                    Matcher m = Pattern.compile('(\\w+)\\s*\\(').matcher(e.content as String)
                    if (m.find()) methodNames << m.group(1)
                }
            }
            String hint = methodNames
                ? "Available methods: ${methodNames.join(', ')}"
                : 'No methods found in file (structure cache may be stale — re-read the file first)'
            return new TransformResult(success: false,
                error: "Method '${methodName}' not found in ${normalizedPath}", hint: hint)
        }

        int startLine     = found.line as int
        int endLine       = found.get('endLine') != null ? (found.endLine as int) : (startLine + 150)
        int linesAffected = endLine - startLine + 1

        List<String> lines = new File(normalizedPath).readLines('UTF-8')

        List<String> result = []
        result.addAll(lines.subList(0, startLine - 1))                    // lines before method
        String[] parts = newBody.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
        result.addAll(Arrays.asList(parts))                               // new method body
        result.addAll(lines.subList(endLine, lines.size()))               // lines after method

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        return new TransformResult(
            success      : true,
            linesAffected: linesAffected,
            message      : "Method '${methodName}' replaced (${linesAffected} lines)"
        )
    }
}
