package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.StructureCache
import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.nio.file.Paths
import java.util.Arrays
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * add_method — insert a new method into a Groovy/Java class without reading file content.
 *
 * Required options:
 *   method — name of the new method (used only as a check that it doesn't already exist)
 *   body   — complete method text (signature + body + closing brace)
 *
 * Optional options:
 *   after  — insert after this named method (method name, exact)
 *   before — insert before this named method (method name, exact)
 *   (default: insert before the last closing brace in the file)
 *
 * Uses StructureCache to find insertion point — no file read required.
 * Token saving: eliminates the read round-trip to find the insertion line.
 *
 * v0.8.3
 */
@Component
@CompileStatic
class AddMethodTransformer implements FileTransformer {

    @Autowired
    StructureCache structureCache

    @Override
    String getName() { 'add_method' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String methodName = options.method as String
        String body       = options.body as String
        String after      = options.after as String
        String before     = options.before as String

        if (!body) {
            return new TransformResult(success: false,
                error: 'options.body is required for add_method')
        }

        Map structureResult = structureCache.getStructure(normalizedPath)
        List<Map> entries   = (structureResult.structure as List<Map>) ?: []

        // Check for duplicate
        if (methodName) {
            Pattern exactPat = Pattern.compile('\\b' + Pattern.quote(methodName) + '\\s*\\(')
            for (Map entry : entries) {
                if ((entry.type as String) == 'method') {
                    Matcher m = exactPat.matcher(entry.content as String)
                    if (m.find()) {
                        return new TransformResult(success: false,
                            error: "Method '${methodName}' already exists in ${normalizedPath}. Use server_transform action=replace_method to replace it.")
                    }
                }
            }
        }

        List<String> fileLines = new File(normalizedPath).readLines('UTF-8')
        int totalLines = fileLines.size()
        int insertAfterLine  // insert AFTER this line (1-indexed), i.e. before line insertAfterLine+1

        if (after) {
            // Insert after the named method's endLine
            Map found = findMethod(entries, after)
            if (!found) {
                return new TransformResult(success: false,
                    error: "Method '${after}' not found (options.after). Cannot determine insertion point.",
                    hint: availableMethodNames(entries))
            }
            insertAfterLine = found.get('endLine') != null ? (found.endLine as int) : (found.line as int)
        } else if (before) {
            // Insert before the named method's startLine — i.e. after startLine-1
            Map found = findMethod(entries, before)
            if (!found) {
                return new TransformResult(success: false,
                    error: "Method '${before}' not found (options.before). Cannot determine insertion point.",
                    hint: availableMethodNames(entries))
            }
            int startLine = found.line as int
            insertAfterLine = startLine - 1
        } else {
            // Default: insert before the last closing brace in the file
            int lastBrace = totalLines
            for (int i = totalLines - 1; i >= 0; i--) {
                if (fileLines[i].trim() == '}') {
                    lastBrace = i + 1  // 1-indexed
                    break
                }
            }
            insertAfterLine = lastBrace - 1
        }

        // Build new file content: insert blank line + body after insertAfterLine
        List<String> result = []
        result.addAll(fileLines.subList(0, insertAfterLine))
        result.add('')  // blank separator
        String[] bodyLines = body.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1)
        result.addAll(Arrays.asList(bodyLines))
        result.addAll(fileLines.subList(insertAfterLine, totalLines))

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        String displayName = methodName ?: '<new method>'
        return new TransformResult(
            success      : true,
            linesAffected: bodyLines.length,
            message      : "Method '${displayName}' inserted after line ${insertAfterLine}"
        )
    }

    private static Map findMethod(List<Map> entries, String name) {
        Pattern pat = Pattern.compile('\\b' + Pattern.quote(name) + '\\s*\\(')
        for (Map entry : entries) {
            if ((entry.type as String) == 'method') {
                Matcher m = pat.matcher(entry.content as String)
                if (m.find()) return entry
            }
        }
        return null
    }

    private static String availableMethodNames(List<Map> entries) {
        List<String> names = []
        for (Map e : entries) {
            if ((e.type as String) == 'method') {
                Matcher m = Pattern.compile('(\\w+)\\s*\\(').matcher(e.content as String)
                if (m.find()) names << m.group(1)
            }
        }
        return names ? "Available methods: ${names.join(', ')}" : 'No methods found in file'
    }
}
