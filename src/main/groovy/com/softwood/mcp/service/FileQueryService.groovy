package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * File query operations - listing, searching, tree views
 * PHASE 1: Safe regex compilation via safeCompilePattern()
 * 
 * REGEX PATTERN GUIDANCE:
 * - Simple substring: "Controller" matches any filename containing "Controller"
 * - Java regex: ".*\\.groovy" matches filenames ending in .groovy
 * - Anchors work on filename only (not full path): "^Test.*" matches filenames starting with "Test"
 * - Invalid regex gracefully falls back to literal match via Pattern.quote()
 * - Examples: "Service" | ".*Controller\\.groovy" | "^Test.*Spec"
 */
@Service
@Slf4j
@CompileStatic
class FileQueryService extends AbstractFileService implements ToolHandler {

    FileQueryService(PathService pathService) {
        super(pathService)
    }

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [
            createMap([
                name: "listChildrenOnly",
                description: "List immediate children only ( no recursion, bounded to 100 results, token-optimized)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "Directory path"]),
                        pattern: createMap([type: "string", description: "Filename regex pattern (optional). Simple substring (Controller) or Java regex (.*\\.groovy). Matches filename only, not full path. Invalid regex falls back to literal match."]),
                        maxResults: createMap([type: "integer", description: "Max results (default: 100)"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "listDirectory",
                description: "List files and directories with optional filtering ( max 100 results, may truncate. Prefer listChildrenOnly)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "Directory path"]),
                        pattern: createMap([type: "string", description: "Filename regex pattern (optional). Simple substring (Controller) or Java regex (.*\\.groovy). Matches filename only, not full path. Invalid regex falls back to literal match."]),
                        recursive: createMap([type: "boolean", description: "List recursively ( may hit limits)"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "searchInProject",
                description: "Search in active project root only ( bounded to 50 results, token-optimized)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        contentPattern: createMap([type: "string", description: "Regex pattern to search for in file contents. Java regex syntax (e.g., 'def\\s+\\w+' for method definitions). Invalid regex falls back to literal match."]),
                        filePattern: createMap([type: "string", description: "Regex pattern for filenames (default: .(groovy|java|gradle)\$). Matches filename only. Examples: .*\\.groovy | .*Controller\\. | Test.*"]),
                        maxResults: createMap([type: "integer", description: "Max results (default: 50)"])
                    ]),
                    required: ["contentPattern"]
                ])
            ]),
            createMap([
                name: "searchFiles",
                description: "Search file contents using regex patterns ( max 50 results, prefer searchInProject for project scope)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        directory: createMap([type: "string", description: "Directory to search in"]),
                        contentPattern: createMap([type: "string", description: "Regex pattern to search for in file contents. Java regex syntax (e.g., 'def\\s+\\w+' for method definitions). Invalid regex falls back to literal match."]),
                        filePattern: createMap([type: "string", description: "Regex pattern for filenames (default: groovy files). Matches filename only. Examples: .*\\.groovy | .*Controller\\. | Test.*"])
                    ]),
                    required: ["directory", "contentPattern"]
                ])
            ]),
            createMap([
                name: "findFilesByName",
                description: "Find files by name pattern ( faster than content search - 80%+ token savings)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        pattern: createMap([type: "string", description: "Filename pattern. Simple substring (Controller) or Java regex (.*\\.groovy). Uses .find() not .matches() - so partial matches work. Examples: Controller | .*\\.groovy | Test.*Spec. Invalid regex falls back to literal match."]),
                        directory: createMap([type: "string", description: "Directory to search (default: project root)"]),
                        maxDepth: createMap([type: "integer", description: "Maximum directory depth (default: 10)"]),
                        maxResults: createMap([type: "integer", description: "Maximum results (default: 100)"])
                    ]),
                    required: ["pattern"]
                ])
            ]),
            createMap([
                name: "listDirectoryWithSizes",
                description: "List directory with file sizes and optional sorting",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "Directory path"]),
                        sortBy: createMap([type: "string", enum: ["name", "size"], description: "Sort by name or size (default: name)"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "getDirectoryTree",
                description: "Get recursive directory tree structure as JSON ( max depth 5, max 200 files)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "Directory path"]),
                        excludePatterns: createMap([type: "array", items: createMap([type: "string"]), description: "Regex patterns to exclude (e.g., ['node_modules', '\\.git']). Matches against directory/file names. Invalid regex falls back to literal match."])
                    ]),
                    required: ["path"]
                ])
            ])
        ]
    }

    @Override
    boolean canHandle(String toolName) {
        toolName in ['listChildrenOnly', 'listDirectory', 'searchInProject', 'searchFiles',
                      'findFilesByName', 'listDirectoryWithSizes', 'getDirectoryTree']
    }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> args, Object requestId) {
        switch (toolName) {
            case 'listChildrenOnly':
                def result = listChildrenOnly(args.path as String, args.pattern as String,
                    (args.maxResults as Integer) ?: -1)
                return textResponse(requestId, result)

            case 'listDirectory':
                def result = listDirectory(args.path as String, args.pattern as String,
                    (args.recursive as Boolean) ?: false)
                return textResponse(requestId, result)

            case 'searchInProject':
                def result = searchInProject(args.contentPattern as String,
                    (args.filePattern as String) ?: '.*\\.(groovy|java|gradle)$',
                    (args.maxResults as Integer) ?: -1)
                return textResponse(requestId, result)

            case 'searchFiles':
                def result = searchFiles(args.directory as String, args.contentPattern as String,
                    (args.filePattern as String) ?: '.*\\.groovy$')
                return textResponse(requestId, result)

            case 'findFilesByName':
                def result = findFilesByName(args.pattern as String, args.directory as String,
                    (args.maxDepth as Integer) ?: 5, (args.maxResults as Integer) ?: 100)
                return textResponse(requestId, result)

            case 'listDirectoryWithSizes':
                def result = listDirectoryWithSizes(args.path as String, (args.sortBy as String) ?: 'name')
                return textResponse(requestId, result)

            case 'getDirectoryTree':
                def result = getDirectoryTree(args.path as String,
                    (args.excludePatterns as List<String>) ?: [])
                return textResponse(requestId, result)

            default:
                return McpResponse.error(requestId, -32601, "Unknown tool: ${toolName}" as String)
        }
    }

    // ========================================================================
    // IMPLEMENTATIONS
    // ========================================================================

    List<Map<String, Object>> listChildrenOnly(String path, String pattern = null, int maxResults = -1) {
        try {
            String normalized = validateDirectoryPath(path)
            Path dirPath = Paths.get(normalized)
            int effectiveMax = maxResults > 0 ? Math.min(maxResults, maxListResults) : maxListResults
            List<Map<String, Object>> results = []
            Pattern regexPattern = pattern ? safeCompilePattern(pattern) : null

            Stream<Path> stream = null
            try {
                stream = Files.list(dirPath)
                stream.filter { p -> !isReservedName(p.fileName.toString()) }
                      .filter { p -> !regexPattern || regexPattern.matcher(p.fileName.toString()).matches() }
                      .forEach { p ->
                          if (results.size() >= effectiveMax) return
                          try {
                              Map<String, Object> entry = pathToMap(p)
                              if (entry != null) results.add(entry)
                          } catch (Exception e) {
                              log.warn("Error: ${sanitize(e.message)}")
                          }
                      }
            } finally {
                stream?.close()
            }

            return results.collect { sanitizeObject(it) as Map<String, Object> }
        } catch (Exception e) {
            log.error("Error listing children: ${sanitize(e.message)}")
            throw e
        }
    }

    List<Map<String, Object>> listDirectory(String path, String pattern = null, boolean recursive = false) {
        try {
            String normalized = validateDirectoryPath(path)
            Path dirPath = Paths.get(normalized)
            List<Map<String, Object>> results = []
            Pattern regexPattern = pattern ? safeCompilePattern(pattern) : null

            Stream<Path> stream = null
            try {
                stream = recursive ? Files.walk(dirPath) : Files.list(dirPath)
                stream.filter { p ->
                          recursive ? Files.isRegularFile(p) : true
                      }
                      .filter { p -> !isReservedName(p.fileName.toString()) }
                      .filter { p -> !regexPattern || regexPattern.matcher(p.fileName.toString()).matches() }
                      .forEach { p ->
                          if (results.size() >= maxListResults) return
                          try {
                              Map<String, Object> entry = pathToMap(p)
                              if (entry != null) results.add(entry)
                          } catch (Exception e) {
                              log.warn("Error: ${sanitize(e.message)}")
                          }
                      }
            } finally {
                stream?.close()
            }

            List<Map<String, Object>> sanitized = results.collect { sanitizeObject(it) as Map<String, Object> }
            validateResponseSize(sanitized, "listDirectory")
            return sanitized
        } catch (Exception e) {
            log.error("Error listing directory: ${sanitize(e.message)}")
            throw e
        }
    }

    List<Map<String, Object>> searchInProject(String contentPattern, String filePattern = '.*\\.(groovy|java|gradle)$', int maxResults = -1) {
        try {
            String projectRoot = getProjectRoot()
            int effectiveMax = maxResults > 0 ? Math.min(maxResults, maxSearchResults) : maxSearchResults
            return searchFilesWithLimit(projectRoot, contentPattern, filePattern, effectiveMax)
        } catch (Exception e) {
            log.error("Error searching in project: ${sanitize(e.message)}")
            throw e
        }
    }

    List<Map<String, Object>> searchFiles(String directory, String contentPattern, String filePattern = '.*\\.groovy$') {
        return searchFilesWithLimit(directory, contentPattern, filePattern, maxSearchResults)
    }

    private List<Map<String, Object>> searchFilesWithLimit(String directory, String contentPattern, String filePattern, int maxResults) {
        try {
            String normalized = validateDirectoryPath(directory)
            Path dirPath = Paths.get(normalized)
            List<Map<String, Object>> results = []
            Pattern fileRegex = safeCompilePattern(filePattern)
            Pattern contentRegex = safeCompilePattern(contentPattern)

            Stream<Path> stream = null
            try {
                stream = Files.walk(dirPath)
                stream.filter { p -> Files.isRegularFile(p) }
                      .filter { p -> !isReservedName(p.fileName.toString()) }
                      .filter { p -> fileRegex.matcher(p.fileName.toString()).matches() }
                      .forEach { p ->
                          if (results.size() >= maxResults) return
                          try {
                              List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8)
                              List<Map<String, Object>> matches = []
                              lines.eachWithIndex { String line, int index ->
                                  if (matches.size() >= maxSearchMatchesPerFile) return
                                  try {
                                      if (contentRegex.matcher(line).find()) {
                                          matches.add(createMap([lineNumber: index + 1, line: truncateAndSanitize(line)]))
                                      }
                                  } catch (Exception e) { /* skip line */ }
                              }
                              if (matches) {
                                  results.add(createMap([
                                      path: sanitize(p.toAbsolutePath().toString().replace('\\', '/')),
                                      name: sanitize(p.fileName.toString()),
                                      matchCount: matches.size(),
                                      truncatedMatches: matches.size() >= maxSearchMatchesPerFile,
                                      matches: matches
                                  ]))
                              }
                          } catch (Exception e) {
                              log.warn("Error reading ${sanitize(p.toString())}: ${sanitize(e.message)}")
                          }
                      }
            } finally {
                stream?.close()
            }

            return results.collect { sanitizeObject(it) as Map<String, Object> }
        } catch (Exception e) {
            log.error("Error searching files: ${sanitize(e.message)}")
            throw e
        }
    }

    List<Map<String, Object>> findFilesByName(String pattern, String directory = null, int maxDepth = 10, int maxResults = 100) {
        try {
            String searchDir = directory ? pathService.normalizePath(directory) : getProjectRoot()
            if (!isPathAllowed(searchDir)) {
                throw new SecurityException("Path not allowed: ${sanitize(searchDir)}")
            }

            Path dirPath = Paths.get(searchDir)
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                throw new IllegalArgumentException("Invalid directory: ${sanitize(searchDir)}")
            }

            Pattern namePattern = safeCompilePattern(pattern)
            List<Map<String, Object>> results = []

            Stream<Path> stream = null
            try {
                stream = Files.walk(dirPath, maxDepth)
                stream.filter { p -> Files.isRegularFile(p) && !isReservedName(p.fileName.toString()) }
                      .filter { p -> namePattern.matcher(p.fileName.toString()).find() }
                      .forEach { p ->
                          if (results.size() >= maxResults) return
                          try { results.add(pathToMap(p)) }
                          catch (Exception e) { log.warn("Error: ${sanitize(e.message)}") }
                      }
            } finally {
                stream?.close()
            }

            return results.collect { sanitizeObject(it) as Map<String, Object> }
        } catch (Exception e) {
            log.error("Error finding files by name: ${sanitize(e.message)}")
            throw e
        }
    }

    List<Map<String, Object>> listDirectoryWithSizes(String path, String sortBy = 'name') {
        try {
            String normalized = validateDirectoryPath(path)
            Path dirPath = Paths.get(normalized)
            List<Map<String, Object>> entries = []

            Files.newDirectoryStream(dirPath).each { Path entry ->
                String entryName = entry.fileName.toString()
                if (WINDOWS_RESERVED_NAMES.contains(entryName.toUpperCase())) return

                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class)
                    entries.add(createMap([
                        name: entryName,
                        path: sanitize(entry.toString()),
                        type: attrs.isDirectory() ? 'directory' : 'file',
                        size: attrs.size(),
                        lastModified: attrs.lastModifiedTime().toMillis(),
                        readable: Files.isReadable(entry),
                        writable: Files.isWritable(entry),
                        executable: Files.isExecutable(entry)
                    ]))
                } catch (Exception e) {
                    log.warn("Error reading attrs for ${sanitize(entryName)}: ${sanitize(e.message)}")
                }
            }

            if (sortBy == 'size') {
                entries.sort { a, b -> (b.size as Long) <=> (a.size as Long) }
            } else {
                entries.sort { a, b -> (a.name as String) <=> (b.name as String) }
            }
            return entries
        } catch (Exception e) {
            log.error("Error listing directory with sizes: ${sanitize(e.message)}")
            throw e
        }
    }

    Map<String, Object> getDirectoryTree(String path, List<String> excludePatterns = []) {
        try {
            String normalized = validateDirectoryPath(path)
            Path dirPath = Paths.get(normalized)
            List<Pattern> excludeRegexes = excludePatterns.collect { safeCompilePattern(it) }
            Map<String, Integer> limits = [currentDepth: 0, fileCount: 0]

            Map<String, Object> tree = buildTreeNode(dirPath, excludeRegexes, 0, limits)
            validateResponseSize(tree, "getDirectoryTree")
            return tree
        } catch (Exception e) {
            log.error("Error getting directory tree: ${sanitize(e.message)}")
            throw e
        }
    }

    private Map<String, Object> buildTreeNode(Path path, List<Pattern> excludePatterns, int depth, Map<String, Integer> limits) {
        if (depth >= maxTreeDepth) {
            return createMap([name: path.fileName?.toString() ?: path.toString(), type: 'truncated',
                             message: "Max depth (${maxTreeDepth}) reached"])
        }
        if (limits.fileCount >= maxTreeFiles) return null

        String name = path.fileName?.toString() ?: path.toString()
        for (Pattern pattern : excludePatterns) {
            if (pattern.matcher(name).matches()) return null
        }

        boolean isDir = Files.isDirectory(path)
        Map<String, Object> node = createMap([name: name, type: isDir ? 'directory' : 'file'])
        limits.fileCount++

        if (isDir) {
            List<Map<String, Object>> children = []
            try {
                Files.newDirectoryStream(path).each { Path child ->
                    if (limits.fileCount >= maxTreeFiles) {
                        children.add(createMap([name: "... (truncated)", type: "truncated",
                                               message: "Max files (${maxTreeFiles}) reached"]))
                        return
                    }
                    if (WINDOWS_RESERVED_NAMES.contains(child.fileName.toString().toUpperCase())) return
                    Map<String, Object> childNode = buildTreeNode(child, excludePatterns, depth + 1, limits)
                    if (childNode != null) children.add(childNode)
                }
                node.children = children
            } catch (Exception e) {
                log.warn("Error reading directory ${sanitize(name)}: ${sanitize(e.message)}")
                node.children = []
            }
        }
        return node
    }
}
