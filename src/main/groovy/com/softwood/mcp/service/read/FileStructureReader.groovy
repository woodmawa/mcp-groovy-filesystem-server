package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.StructureCache
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * FileStructureReader - handles structure and get_method actions.
 *
 * After each structure scan, asynchronously persists the result to the context
 * server so it can be loaded on future sessions without re-scanning.
 * @see ContextServerClient
 *
 * v0.7.44 - extracted from FileReadService as part of read/ subpackage split.
 *           WI5: automated structure persistence via ContextServerClient.
 */
@Service
@Slf4j
@CompileStatic
class FileStructureReader extends AbstractFileService {

    @Autowired
    StructureCache structureCache

    @Autowired
    ReadResponseHelper helper

    /** Optional - silently no-ops if context server is unavailable. */
    @Autowired(required = false)
    ContextServerClient contextServerClient

    @Value('${mcp.filesystem.structure-max-entries:100}')
    int structureMaxEntries

    @Value('${mcp.filesystem.partial-read-cap-chars:12000}')
    int partialReadCapChars

    FileStructureReader(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // structure
    // -----------------------------------------------------------------------

    McpResponse doStructure(String path, Map<String, Object> options, Object requestId) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) {
            return McpResponse.error(requestId, -32603, "Path not allowed: ${sanitize(normalized)}")
        }

        // FIX-D: hash gate - if file unchanged, skip re-scan entirely
        McpResponse unchanged = helper.checkKnownHash(normalized, options, requestId)
        if (unchanged != null) return unchanged

        Path filePath = Paths.get(normalized)
        if (Files.isDirectory(filePath)) {
            return McpResponse.error(requestId, -32602,
                "structure requires a FILE path, not a directory. " +
                "Use file_list action=tree for directory outlines. Path: ${sanitize(normalized)}")
        }
        if (!Files.exists(filePath)) {
            return McpResponse.error(requestId, -32602, "File not found: ${sanitize(normalized)}")
        }
        if (!Files.isRegularFile(filePath)) {
            return McpResponse.error(requestId, -32602, "Path is not a regular file: ${sanitize(normalized)}")
        }
        if (Files.size(filePath) > 512 * 1024L) {
            return McpResponse.error(requestId, -32602,
                "File too large for structure scan (>512KB). Use head/range/grep for large files: ${sanitize(normalized)}")
        }

        Map<String, Object> result  = structureCache.getStructure(normalized)
        List<Map<String, Object>> entries = result.structure as List<Map<String, Object>>
        int totalEntries = entries.size()
        String fileHash  = structureCache.getHash(normalized)

        // WI5: async persist to context server after every scan (no-op if unavailable or cached)
        boolean wasCached = result.cached as boolean ?: false
        if (!wasCached && contextServerClient != null) {
            contextServerClient.persistStructureAsync(normalized, fileHash, entries)
        }

        // v0.8.1 Change 3: className filter — return only the named class subtree
        String filterClass = options['className'] as String
        if (filterClass) {
            List<Map<String, Object>> classTypeEntries = entries.findAll { Map<String, Object> e ->
                String t = e.type as String
                t == 'class' || t == 'interface' || t == 'enum' || t == 'trait'
            }
            // Match class entries whose content contains filterClass as a distinct word
            List<Map<String, Object>> matched = classTypeEntries.findAll { Map<String, Object> e ->
                String c = (e.content as String) ?: ''
                c.contains(' ' + filterClass + ' ') || c.contains(' ' + filterClass + '<') ||
                c.contains(' ' + filterClass + '{') || c.endsWith(' ' + filterClass)
            }
            if (matched.isEmpty()) {
                List<String> available = classTypeEntries.collect { Map<String, Object> e ->
                    String c = (e.content as String) ?: ''
                    List<String> words = c.tokenize(' ')
                    int idx = words.findIndexOf { String w ->
                        w == 'class' || w == 'interface' || w == 'enum' || w == 'trait'
                    }
                    idx >= 0 && idx + 1 < words.size()
                        ? words[idx + 1].replaceAll('[<{(].*', '')
                        : c.take(40)
                }
                return textResponse(requestId, ([error: ("Class not found: ${filterClass}" as String),
                                                 availableClasses: available,
                                                 file_content_hash: fileHash] as Map<String, Object>))
            }
            entries = entries.findAll { Map<String, Object> e ->
                String t = e.type as String
                if (t == 'class' || t == 'interface' || t == 'enum' || t == 'trait') return matched.contains(e)
                return (e.owner as String) == filterClass
            }
            totalEntries = entries.size()
        }

        if (isCompact(options)) {
            List<Map<String, Object>> methods = entries
                .findAll { Map<String, Object> e -> e.type == 'method' }
                .collect { Map<String, Object> e ->
                    // v0.8.1 Change 2: include lineCount so callers can gauge method size without get_method
                    int lc = (e.endLine as int) - (e.line as int) + 1
                    [line: e.line, type: e.type, content: e.content, lineCount: lc] as Map<String, Object>
                }
            boolean compactCapped = methods.size() > structureMaxEntries
            if (compactCapped) methods = methods.take(structureMaxEntries)
            Map<String, Object> compactResp = [structure: methods, count: methods.size(), total_entries: totalEntries,
                                               file_content_hash: fileHash] as Map<String, Object>
            if (compactCapped) compactResp.entries_capped = true
            return textResponse(requestId, compactResp)
        }

        boolean entriesCapped = totalEntries > structureMaxEntries
        List<Map<String, Object>> cappedEntries = (entriesCapped ? entries.take(structureMaxEntries) : entries)
            .collect { Map<String, Object> e ->
                String c = e.content as String
                // v0.8.1 Change 2: add lineCount to every entry
                Map<String, Object> entry = (c && c.length() > 200)
                    ? (new LinkedHashMap<>(e) + [content: c.substring(0, 200) + '...']) as Map<String, Object>
                    : new LinkedHashMap<>(e) as Map<String, Object>
                entry.lineCount = (e.endLine as int) - (e.line as int) + 1
                return entry
            }
        Map<String, Object> structResp = [action: 'structure', path: normalized, ext: result.ext,
                                          count: cappedEntries.size(), total_entries: totalEntries,
                                          structure: cappedEntries, scanner: result.scanner, cached: result.cached] as Map<String, Object>
        if (entriesCapped) structResp.entries_capped = true
        return textResponse(requestId, structResp)
    }

    // -----------------------------------------------------------------------
    // get_method
    // -----------------------------------------------------------------------

    McpResponse doGetMethod(String path, Map<String, Object> options, Object requestId) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) {
            return McpResponse.error(requestId, -32603, "Path not allowed: ${sanitize(normalized)}")
        }
        if (!new File(normalized).exists()) {
            return McpResponse.error(requestId, -32602, "File not found: ${sanitize(normalized)}")
        }

        McpResponse unchanged = helper.checkKnownHash(normalized, options, requestId)
        if (unchanged != null) return unchanged

        String methodName = options.method as String
        boolean fuzzy     = options.fuzzy as Boolean ?: false
        if (!methodName) return McpResponse.error(requestId, -32602, 'options.method is required for get_method')

        Map<String, Object> scanResult = structureCache.getStructure(normalized)
        List<Map> entries = scanResult.structure as List<Map>
        String scanner    = scanResult.scanner as String

        Map found = fuzzy
            ? entries.find { it.type == 'method' && (it.content as String).contains(methodName) }
            : entries.find { it.type == 'method' && (it.content as String) =~ /\b${java.util.regex.Pattern.quote(methodName)}\s*\(/ }

        if (!found) return McpResponse.error(requestId, -32602,
            "Method '${sanitize(methodName)}' not found in ${sanitize(normalized)}")

        int startLine   = found.line as int
        Integer endLine = found.endLine as Integer
        int maxLines    = endLine ? (endLine - startLine + 1) : 150

        List<String> lines = []
        int current = 0
        new File(normalized).withReader('UTF-8') { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String ln
            while ((ln = br.readLine()) != null) {
                current++
                if (current < startLine) continue
                if (current > startLine + maxLines - 1) break
                lines << truncateAndSanitize(ln)
            }
        }

        String content        = lines.join('\n')
        boolean methodTruncated = content.length() > partialReadCapChars
        if (methodTruncated) content = content.substring(0, partialReadCapChars)

        Map<String, Object> gmResp = [
            action: 'get_method', path: normalized,
            method: methodName,
            startLine: startLine, endLine: startLine + lines.size() - 1,
            lines: lines.size(), content: content,
            file_content_hash: structureCache.getHash(normalized)
        ] as Map<String, Object>
        // FS-T7: surface fallback flag when AST failed and regex scanner was used.
        // Callers can detect compile-error files and know the boundary may be imprecise.
        if (scanner == 'regex') {
            gmResp.fallback = true
            gmResp.fallback_note = 'AST unavailable (possible compile error) — method boundaries derived from regex scan. Result may be imprecise for overloaded methods or complex signatures.'
        }
        if (methodTruncated) {
            gmResp._truncated = true
            gmResp._truncatedNote = ("Method body truncated at ${partialReadCapChars} chars (~${partialReadCapChars / 4000 as int}K tokens). Use action=range with startLine=${startLine} and maxLines to read the rest." as String)
        }
        helper.injectSessionTokenMeter(gmResp, content.length())
        return textResponse(requestId, gmResp)
    }
}
