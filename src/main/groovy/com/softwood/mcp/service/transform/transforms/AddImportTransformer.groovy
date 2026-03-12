package com.softwood.mcp.service.transform.transforms

import com.softwood.mcp.service.transform.FileTransformer
import com.softwood.mcp.service.transform.TransformResult
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.nio.file.Paths

/**
 * add_import — add an import statement to a Groovy/Java file if not already present.
 *
 * Required options:
 *   import — the import statement to add (with or without leading 'import ' keyword)
 *             e.g. 'com.example.Foo' or 'import com.example.Foo'
 *
 * Behaviour:
 *   - If the import is already present: returns success with a no-op note (idempotent).
 *   - Inserts after the last existing import line, maintaining relative order.
 *   - If no imports exist: inserts after the package declaration line.
 *   - If no package either: inserts at line 1.
 *
 * Token saving: eliminates the read round-trip to find the import block.
 *
 * v0.8.3
 */
@Component
@CompileStatic
class AddImportTransformer implements FileTransformer {

    @Override
    String getName() { 'add_import' }

    @Override
    TransformResult apply(String normalizedPath, Map<String, Object> options) {
        String importValue = (options['import'] as String)?.trim()
        if (!importValue) {
            return new TransformResult(success: false,
                error: 'options.import is required for add_import')
        }

        // Normalise: ensure it starts with 'import '
        String importStatement = importValue.startsWith('import ') ? importValue : "import ${importValue}"
        // Strip trailing semicolon if present (Groovy doesn't need it, Java has it)
        importStatement = importStatement.replaceAll(';$', '').trim()

        List<String> fileLines = new File(normalizedPath).readLines('UTF-8')

        // Check idempotent: already present?
        String importClass = importStatement.replaceFirst('^import\\s+', '').trim()
        for (String line : fileLines) {
            String trimmed = line.trim().replaceAll(';$', '')
            if (trimmed == importStatement || trimmed == "import ${importClass}") {
                return new TransformResult(
                    success      : true,
                    linesAffected: 0,
                    message      : "Import '${importStatement}' already present \u2014 no change made"
                )
            }
        }

        // Find insertion point: after last 'import' line, else after 'package' line, else line 0
        int lastImportLine   = -1  // 0-indexed
        int packageLine      = -1  // 0-indexed

        for (int i = 0; i < fileLines.size(); i++) {
            String trimmed = fileLines[i].trim()
            if (trimmed.startsWith('import '))  lastImportLine = i
            if (trimmed.startsWith('package ')) packageLine   = i
        }

        int insertAfter  // insert after this 0-indexed line (i.e. before index insertAfter+1)
        if (lastImportLine >= 0) {
            insertAfter = lastImportLine
        } else if (packageLine >= 0) {
            // Insert with a blank line after the package declaration
            insertAfter = packageLine
        } else {
            insertAfter = -1  // prepend
        }

        List<String> result = []
        if (insertAfter < 0) {
            result.add(importStatement)
            result.addAll(fileLines)
        } else {
            result.addAll(fileLines.subList(0, insertAfter + 1))
            result.add(importStatement)
            result.addAll(fileLines.subList(insertAfter + 1, fileLines.size()))
        }

        WriteUtils.atomicWrite(Paths.get(normalizedPath), result.join('\n').getBytes('UTF-8'))

        return new TransformResult(
            success      : true,
            linesAffected: 1,
            message      : "Added: ${importStatement}"
        )
    }
}
