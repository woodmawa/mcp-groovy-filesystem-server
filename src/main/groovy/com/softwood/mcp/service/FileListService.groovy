package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern

/**
 * FileListService — handles the file_list tool.
 *
 * actions: children | list | tree | sizes
 *
 * v0.0.7 — Phase 2 Core File Tools
 */
@Service
@Slf4j
@CompileStatic
class FileListService extends AbstractFileService implements ToolHandler {

    FileListService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : 'file_list',
            description: 'List directory contents or generate a directory tree. Actions: children (immediate children only)|list (filtered listing)|tree (recursive JSON tree)|sizes (directory with file sizes).',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string', enum: ['children', 'list', 'tree', 'sizes'],
                              description: 'Listing mode'],
                    path   : [type: 'string', description: 'Directory path to list'],
                    options: [type: 'object', description: 'Optional: pattern (regex filename filter), recursive (bool), maxResults (int), maxDepth (int), sortBy (name|size), excludePatterns (list of regex)',
                              properties: [
                                  pattern        : [type: 'string'],
                                  recursive      : [type: 'boolean'],
                                  maxResults     : [type: 'integer'],
                                  maxDepth       : [type: 'integer'],
                                  sortBy         : [type: 'string', enum: ['name', 'size']],
                                  excludePatterns: [type: 'array', items: [type: 'string']]
                              ]]
                ],
                required  : ['action', 'path']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == 'file_list' }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action  = arguments.action as String
            String path    = arguments.path as String
            Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>

            String normalized = validateDirectoryPath(path)

            switch (action) {
                case 'children': return doChildren(normalized, options, requestId)
                case 'list'    : return doList(normalized, options, requestId)
                case 'tree'    : return doTree(normalized, options, requestId)
                case 'sizes'   : return doSizes(normalized, options, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown file_list action: ${action}")
            }
        } catch (SecurityException e) {
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (FileNotFoundException e) {
            return McpResponse.error(requestId, -32602, sanitize(e.message))
        } catch (Exception e) {
            log.error("file_list error: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private McpResponse doChildren(String path, Map<String, Object> options, Object requestId) {
        int    max     = (options.maxResults as Integer) ?: maxListResults
        String pattern = options.pattern as String
        Pattern compiled = pattern ? safeCompilePattern(pattern) : null

        List<Map<String, Object>> results = []
        Files.list(Paths.get(path)).each { Path p ->
            if (results.size() >= max) return
            String name = p.fileName.toString()
            if (compiled && !(name =~ compiled)) return
            results << pathToMap(p)
        }

        log.debug("file_list children: {} entries from {}", results.size(), path)
        return textResponse(requestId, [action: 'children', path: path, count: results.size(), entries: results])
    }

    private McpResponse doList(String path, Map<String, Object> options, Object requestId) {
        int    max       = (options.maxResults as Integer) ?: maxListResults
        String pattern   = options.pattern as String
        boolean recursive = options.recursive as boolean ?: false
        String sortBy    = options.sortBy as String ?: 'name'
        Pattern compiled = pattern ? safeCompilePattern(pattern) : null

        List<Map<String, Object>> results = []

        def stream = recursive ? Files.walk(Paths.get(path)) : Files.list(Paths.get(path))
        stream.each { Path p ->
            if (results.size() >= max) return
            String name = p.fileName.toString()
            if (compiled && !(name =~ compiled)) return
            results << pathToMap(p)
        }

        // Sort
        if (sortBy == 'size') {
            results = results.sort { Map<String, Object> a, Map<String, Object> b ->
                ((b.size as Long) ?: 0L) <=> ((a.size as Long) ?: 0L)
            }
        } else {
            results = results.sort { Map<String, Object> a, Map<String, Object> b ->
                ((a.name as String) ?: '') <=> ((b.name as String) ?: '')
            }
        }

        log.debug("file_list list: {} entries from {}", results.size(), path)
        return textResponse(requestId, [action: 'list', path: path, count: results.size(), entries: results])
    }

    private McpResponse doTree(String path, Map<String, Object> options, Object requestId) {
        int maxDepth   = (options.maxDepth as Integer) ?: maxTreeDepth
        int maxFiles   = (options.maxResults as Integer) ?: maxTreeFiles
        List<String> excludePatterns = (options.excludePatterns as List<String>) ?: []
        List<Pattern> excludeCompiled = excludePatterns.collect { safeCompilePattern(it) }.findAll { it } as List<Pattern>

        int[] count = [0]
        Map<String, Object> tree = buildTree(Paths.get(path), 0, maxDepth, maxFiles, excludeCompiled, count)

        log.debug("file_list tree: {} nodes from {}", count[0], path)
        return textResponse(requestId, [action: 'tree', path: path, nodeCount: count[0], tree: tree])
    }

    private McpResponse doSizes(String path, Map<String, Object> options, Object requestId) {
        int    max    = (options.maxResults as Integer) ?: maxListResults
        String sortBy = options.sortBy as String ?: 'size'

        List<Map<String, Object>> results = []
        Files.list(Paths.get(path)).each { Path p ->
            if (results.size() >= max) return
            results << pathToMap(p)
        }

        results = results.sort { Map<String, Object> a, Map<String, Object> b ->
            sortBy == 'size'
                ? (((b.size as Long) ?: 0L) <=> ((a.size as Long) ?: 0L))
                : (((a.name as String) ?: '') <=> ((b.name as String) ?: ''))
        }

        return textResponse(requestId, [action: 'sizes', path: path, count: results.size(), entries: results])
    }

    // -----------------------------------------------------------------------
    // Tree builder
    // -----------------------------------------------------------------------

    private Map<String, Object> buildTree(Path dir, int depth, int maxDepth, int maxFiles,
                                          List<Pattern> excludePatterns, int[] count) {
        String name = dir.fileName?.toString() ?: dir.toString()
        Map<String, Object> node = [
            name: sanitize(name),
            type: 'directory',
            path: sanitize(dir.toAbsolutePath().toString().replace('\\', '/'))
        ] as Map<String, Object>

        if (depth >= maxDepth || count[0] >= maxFiles) {
            node.truncated = true
            return node
        }

        List<Map<String, Object>> children = []
        try {
            Files.list(dir).sorted().each { Path child ->
                if (count[0] >= maxFiles) return
                String childName = child.fileName.toString()

                // Apply exclude patterns
                if (excludePatterns.any { Pattern p -> childName =~ p }) return

                count[0]++
                if (Files.isDirectory(child)) {
                    children << buildTree(child, depth + 1, maxDepth, maxFiles, excludePatterns, count)
                } else {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class)
                        children << ([
                            name: sanitize(childName),
                            type: 'file',
                            path: sanitize(child.toAbsolutePath().toString().replace('\\', '/')),
                            size: attrs.size()
                        ] as Map<String, Object>)
                    } catch (Exception e) {
                        children << ([name: sanitize(childName), type: 'file', error: 'unreadable'] as Map<String, Object>)
                    }
                }
            }
        } catch (Exception e) {
            node.error = sanitize("Cannot list: ${e.message}")
        }

        node.children = children
        return node
    }
}
