package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * FileListService  handles the file_list tool.
 *
 * actions: children | list | tree | sizes
 *
 * v0.7.3  Stream leak fixes: all Files.list()/Files.walk() wrapped in withCloseable{}
 */
@Service
@Slf4j
@CompileStatic
class FileListService extends AbstractFileService implements ToolHandler {

    @Autowired(required = false)
    ContextServerClient contextServerClient

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
            description: '''\
List directory contents.
Actions: children|list|tree|sizes
Key params: path (dir, required), options.maxDepth (tree, default 2), options.pattern (filename filter), options.recursive (list), options.compact (minimal output).''',
            inputSchema: [
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
                                  excludePatterns: [type: 'array', items: [type: 'string']],
                                  compact        : [type: 'boolean', description: 'Minimal response - omits action/path echo, trims metadata (children action only)']
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
        boolean fromCache = false

        // Cache check: only when no pattern filter (cache stores raw unfiltered listing)
        if (compiled == null && contextServerClient != null) {
            ContextServerClient.CachedListing cached = contextServerClient.getDirectoryListing(path)
            if (cached != null) {
                results = (cached.entries.size() > max ? cached.entries.take(max) : cached.entries) as List<Map<String, Object>>
                fromCache = true
                log.debug('file_list children: cache HIT ({} entries) for {}', results.size(), path)
            }
        }

        if (!fromCache) {
            // Fix: wrap in withCloseable to ensure stream is closed after iteration
            // FIX-10: use filter+limit so stream genuinely short-circuits at max
            (Files.list(Paths.get(path)) as Stream<Path>).withCloseable { Stream<Path> stream ->
                stream.filter { Path p ->
                    compiled == null || (p.fileName.toString() =~ compiled)
                }.limit(max).each { Path p ->
                    results << pathToMap(p)
                }
            }
            // Persist unfiltered results to cache (only when no pattern filter)
            if (compiled == null && contextServerClient != null) {
                long dirMtime = new File(path).lastModified()
                String hash = ContextServerClient.computeListingHash(results)
                contextServerClient.persistDirectoryListingAsync(path, results, hash, dirMtime)
            }
        }

        log.debug('file_list children: {} entries from {} (cache={})' , results.size(), path, fromCache)
        // FIX-D: cap response size to prevent large directory listings overflowing context window
        int totalChildren = results.size()
        boolean childrenCapped = false
        if (isCompact(options)) {
            List<Map<String, Object>> slim = results.collect { compactPathEntry(it) }
            // estimate size and truncate if needed
            int estChars = slim.sum { Map m -> (m.values().sum { Object v -> (v?.toString()?.length() ?: 0) } as int) + 10 } as int
            if (estChars > listResponseCapChars) {
                slim = []; int acc = 0
                for (Map<String, Object> e : results.collect { compactPathEntry(it) }) {
                    int sz = (e.values().sum { Object v -> (v?.toString()?.length() ?: 0) } as int) + 10
                    if (acc + sz > listResponseCapChars) { childrenCapped = true; break }
                    slim << e; acc += sz
                }
            }
            Map<String, Object> r = [count: slim.size(), total_available: totalChildren, entries: slim] as Map<String, Object>
            if (childrenCapped) r._sizeCapped = true
            return textResponse(requestId, r)
        }
        int estChars = results.sum { Map<String,Object> m -> (m.values().sum { Object v -> (v?.toString()?.length() ?: 0) } as int) + 10 } as int
        if (estChars > listResponseCapChars) {
            List<Map<String, Object>> trimmed = []; int acc = 0
            for (Map<String, Object> e : results) {
                int sz = (e.values().sum { Object v -> (v?.toString()?.length() ?: 0) } as int) + 10
                if (acc + sz > listResponseCapChars) { childrenCapped = true; break }
                trimmed << e; acc += sz
            }
            results = trimmed
        }
        Map<String, Object> childResp = [action: 'children', path: path, count: results.size(), total_available: totalChildren, entries: results] as Map<String, Object>
        if (childrenCapped) childResp._sizeCapped = true
        return textResponse(requestId, childResp)
    }

    private McpResponse doList(String path, Map<String, Object> options, Object requestId) {
        int    max       = (options.maxResults as Integer) ?: maxListResults
        String pattern   = options.pattern as String
        boolean recursive = options.recursive as boolean ?: false
        String sortBy    = options.sortBy as String ?: 'name'
        Pattern compiled = pattern ? safeCompilePattern(pattern) : null

        List<Map<String, Object>> results = []

        // Fix: wrap in withCloseable to ensure stream is closed after iteration
        // FIX-10: use filter+limit so stream genuinely short-circuits at max
        Stream<Path> stream = recursive ? Files.walk(Paths.get(path)) : Files.list(Paths.get(path))
        stream.withCloseable { Stream<Path> s ->
            s.filter { Path p ->
                compiled == null || (p.fileName.toString() =~ compiled)
            }.limit(max).each { Path p ->
                results << pathToMap(p)
            }
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
        // FIX-D: cap response size
        int totalList = results.size()
        boolean listCapped = false
        int estChars = results.sum { Map<String,Object> m -> (m.values().sum { Object v -> (v?.toString()?.length() ?: 0) } as int) + 10 } as int
        if (estChars > listResponseCapChars) {
            List<Map<String, Object>> trimmed = []; int acc = 0
            for (Map<String, Object> e : results) {
                int sz = (e.values().sum { Object v -> (v?.toString()?.length() ?: 0) } as int) + 10
                if (acc + sz > listResponseCapChars) { listCapped = true; break }
                trimmed << e; acc += sz
            }
            results = trimmed
        }
        Map<String, Object> listResp = [action: 'list', path: path, count: results.size(), total_available: totalList, entries: results] as Map<String, Object>
        if (listCapped) listResp._sizeCapped = true
        return textResponse(requestId, listResp)
    }

    private McpResponse doTree(String path, Map<String, Object> options, Object requestId) {
        int maxDepth   = (options.maxDepth as Integer) ?: maxTreeDepth
        int maxFiles   = (options.maxResults as Integer) ?: maxTreeFiles
        List<String> excludePatterns = (options.excludePatterns as List<String>) ?: []
        List<Pattern> excludeCompiled = excludePatterns.collect { safeCompilePattern(it) }.findAll { it } as List<Pattern>

        int[] count = [0]
        Path rootPath = Paths.get(path)
        Map<String, Object> tree = buildTree(rootPath, rootPath, 0, maxDepth, maxFiles, excludeCompiled, count)

        // Async persist tree children to directory cache (no short-circuit path for tree)
        if (contextServerClient != null && tree.children != null) {
            List<Map<String, Object>> topChildren = tree.children as List<Map<String, Object>>
            long dirMtime = new File(path).lastModified()
            String hash = ContextServerClient.computeListingHash(topChildren)
            contextServerClient.persistDirectoryListingAsync(path, topChildren, hash, dirMtime)
        }

        log.debug('file_list tree: {} nodes from {}', count[0], path)
        return textResponse(requestId, [action: 'tree', rootPath: path, nodeCount: count[0], tree: tree])
    }

    private McpResponse doSizes(String path, Map<String, Object> options, Object requestId) {
        int    max    = (options.maxResults as Integer) ?: maxListResults
        String sortBy = options.sortBy as String ?: 'size'

        List<Map<String, Object>> results = []
        // Fix: wrap in withCloseable to ensure stream is closed after iteration
        // FIX-10: use limit so stream genuinely short-circuits at max
        (Files.list(Paths.get(path)) as Stream<Path>).withCloseable { Stream<Path> stream ->
            stream.limit(max).each { Path p ->
                results << pathToMap(p)
            }
        }

        results = results.sort { Map<String, Object> a, Map<String, Object> b ->
            sortBy == 'size'
                ? (((b.size as Long) ?: 0L) <=> ((a.size as Long) ?: 0L))
                : (((a.name as String) ?: '') <=> ((b.name as String) ?: ''))
        }

        // FIX-D: doSizes is diagnostic only - hard cap at 50 entries (top-N is all that matters)
        boolean sizesCapped = results.size() > 50
        if (sizesCapped) results = results.take(50)
        Map<String, Object> sizesResp = [action: 'sizes', path: path, count: results.size(), entries: results] as Map<String, Object>
        if (sizesCapped) sizesResp._capped = '50 entry limit applied (diagnostic use only)'
        return textResponse(requestId, sizesResp)
    }

    // -----------------------------------------------------------------------
    // Tree builder
    // -----------------------------------------------------------------------

    private Map<String, Object> buildTree(Path dir, Path rootPath, int depth, int maxDepth, int maxFiles,
                                          List<Pattern> excludePatterns, int[] count) {
        String name = dir.fileName?.toString() ?: dir.toString()
        String relPath = rootPath.relativize(dir.toAbsolutePath()).toString().replace('\\', '/')
        if (relPath.isEmpty()) relPath = '.'
        Map<String, Object> node = [
            name: sanitize(name),
            type: 'directory',
            path: sanitize(relPath)
        ] as Map<String, Object>

        if (depth >= maxDepth || count[0] >= maxFiles) {
            node.truncated = true
            return node
        }

        List<Map<String, Object>> children = []
        try {
            // Fix: wrap in withCloseable to ensure stream is closed after iteration
            (Files.list(dir) as Stream<Path>).withCloseable { Stream<Path> stream ->
                stream.sorted().each { Path child ->
                    if (count[0] >= maxFiles) return
                    String childName = child.fileName.toString()

                    // Apply exclude patterns
                    if (excludePatterns.any { Pattern p -> childName =~ p }) return

                    count[0]++
                    if (Files.isDirectory(child)) {
                        children << buildTree(child, rootPath, depth + 1, maxDepth, maxFiles, excludePatterns, count)
                    } else {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class)
                            String childRel = rootPath.relativize(child.toAbsolutePath()).toString().replace('\\', '/')
                            children << ([
                                name: sanitize(childName),
                                type: 'file',
                                path: sanitize(childRel),
                                size: attrs.size()
                            ] as Map<String, Object>)
                        } catch (Exception e) {
                            children << ([name: sanitize(childName), type: 'file', error: 'unreadable'] as Map<String, Object>)
                        }
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
