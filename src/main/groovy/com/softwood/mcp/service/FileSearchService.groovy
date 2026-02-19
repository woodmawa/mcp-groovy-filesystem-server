package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * FileSearchService — handles the file_search tool.
 *
 * actions: content | name | project
 *
 * v0.0.7 — Phase 2 Core File Tools
 */
@Service
@Slf4j
@CompileStatic
class FileSearchService extends AbstractFileService implements ToolHandler {

    @Value('${mcp.filesystem.default-file-pattern:.*\\.(groovy|java|gradle|yml|yaml|properties|xml|json|md|txt|kt|kts)$}')
    String defaultFilePattern

    FileSearchService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : 'file_search',
            description: 'Search file contents or find files by name. Actions: content (grep-style content search)|name (filename pattern search)|project (search within active project root using default code file filter).',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string', enum: ['content', 'name', 'project'],
                              description: 'Search mode'],
                    path   : [type: 'string', description: 'Root directory to search from'],
                    options: [type: 'object', description: 'contentPattern (regex), filePattern (regex filename filter), maxResults (int), maxDepth (int), recursive (bool, default true)',
                              properties: [
                                  contentPattern: [type: 'string', description: 'Regex to search inside file content'],
                                  filePattern   : [type: 'string', description: 'Regex to filter filenames'],
                                  maxResults    : [type: 'integer'],
                                  maxDepth      : [type: 'integer'],
                                  recursive     : [type: 'boolean']
                              ]]
                ],
                required  : ['action', 'path']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == 'file_search' }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action  = arguments.action as String
            String path    = arguments.path as String
            Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>

            String normalized = validateDirectoryPath(path)

            switch (action) {
                case 'content': return doContentSearch(normalized, options, requestId)
                case 'name'   : return doNameSearch(normalized, options, requestId)
                case 'project': return doProjectSearch(normalized, options, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown file_search action: ${action}")
            }
        } catch (SecurityException e) {
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (FileNotFoundException e) {
            return McpResponse.error(requestId, -32602, sanitize(e.message))
        } catch (Exception e) {
            log.error("file_search error: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private McpResponse doContentSearch(String path, Map<String, Object> options, Object requestId) {
        String contentPatternStr = options.contentPattern as String
        if (!contentPatternStr) {
            return McpResponse.error(requestId, -32602, "options.contentPattern is required for content search")
        }

        String filePatternStr = options.filePattern as String ?: defaultFilePattern
        int maxResults        = (options.maxResults as Integer) ?: maxSearchResults
        int maxMatchesPerFile = maxSearchMatchesPerFile

        Pattern contentPattern = safeCompilePattern(contentPatternStr)
        Pattern filePattern    = safeCompilePattern(filePatternStr)

        List<Map<String, Object>> results = []
        int filesScanned = 0

        Files.walk(Paths.get(path)).each { Path p ->
            if (results.size() >= maxResults) return
            if (Files.isDirectory(p)) return
            if (filePattern && !(p.fileName.toString() =~ filePattern)) return

            filesScanned++
            List<Map<String, Object>> matches = searchFileContent(p, contentPattern, maxMatchesPerFile)
            if (matches) {
                results << ([
                    file   : sanitize(p.toAbsolutePath().toString().replace('\\', '/')),
                    matches: matches,
                    count  : matches.size()
                ] as Map<String, Object>)
            }
        }

        log.debug("file_search content: {} files matched across {} scanned", results.size(), filesScanned)
        return textResponse(requestId, [
            action      : 'content',
            path        : path,
            pattern     : sanitize(contentPatternStr),
            filesScanned: filesScanned,
            filesMatched: results.size(),
            results     : results
        ])
    }

    private McpResponse doNameSearch(String path, Map<String, Object> options, Object requestId) {
        String filePatternStr = options.filePattern as String
        if (!filePatternStr) {
            return McpResponse.error(requestId, -32602, "options.filePattern is required for name search")
        }

        int maxResults = (options.maxResults as Integer) ?: maxSearchResults
        int maxDepth   = (options.maxDepth as Integer) ?: 10
        Pattern compiled = safeCompilePattern(filePatternStr)

        List<Map<String, Object>> results = []

        Files.walk(Paths.get(path), maxDepth).each { Path p ->
            if (results.size() >= maxResults) return
            String name = p.fileName?.toString() ?: ''
            if (name && compiled && (name =~ compiled || name.find(compiled))) {
                results << pathToMap(p)
            }
        }

        log.debug("file_search name: {} matches for pattern '{}' in {}", results.size(), filePatternStr, path)
        return textResponse(requestId, [
            action : 'name',
            path   : path,
            pattern: sanitize(filePatternStr),
            count  : results.size(),
            results: results
        ])
    }

    private McpResponse doProjectSearch(String path, Map<String, Object> options, Object requestId) {
        // Project search: content + name filter, bounded to maxSearchResults, uses default code file pattern
        String contentPatternStr = options.contentPattern as String
        String filePatternStr    = options.filePattern as String ?: defaultFilePattern
        int maxResults           = (options.maxResults as Integer) ?: maxSearchResults

        if (contentPatternStr) {
            // Content search within project
            Map<String, Object> contentOptions = [
                contentPattern: contentPatternStr,
                filePattern   : filePatternStr,
                maxResults    : maxResults
            ] as Map<String, Object>
            return doContentSearch(path, contentOptions, requestId)
        } else if (options.filePattern) {
            // Name search within project
            return doNameSearch(path, options, requestId)
        } else {
            return McpResponse.error(requestId, -32602,
                "project search requires options.contentPattern or options.filePattern")
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> searchFileContent(Path file, Pattern pattern, int maxMatches) {
        List<Map<String, Object>> matches = []
        try {
            long sizeBytes = Files.size(file)
            if (sizeBytes > (long)(maxFileSizeMb) * 1024 * 1024) return matches

            int lineNum = 0
            file.eachLine('UTF-8') { String line ->
                if (matches.size() >= maxMatches) return
                lineNum++
                Matcher m = pattern.matcher(line)
                if (m.find()) {
                    matches << ([
                        line   : lineNum,
                        content: truncateAndSanitize(line.trim())
                    ] as Map<String, Object>)
                }
            }
        } catch (Exception e) {
            log.debug("Could not search file {}: {}", sanitize(file.toString()), sanitize(e.message))
        }
        return matches
    }
}
