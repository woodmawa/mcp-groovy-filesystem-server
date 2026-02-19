package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.promise.Promise
import com.softwood.mcp.promise.Promises
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * FileReadService — handles the file_read tool.
 *
 * actions: read | head | tail | range | grep | multi | info | summary |
 *          exists | project_root | allowed_dirs | normalize |
 *          diff | checksum | structure
 *
 * Large files are automatically chunked via ChunkBufferService.
 *
 * v0.0.7 — Phase 2 Core File Tools
 */
@Service
@Slf4j
@CompileStatic
class FileReadService extends AbstractFileService implements ToolHandler {

    @Autowired
    ChunkBufferService chunkBufferService

    @Value('${mcp.filesystem.read-chunk-threshold-kb:300}')
    int readChunkThresholdKb

    FileReadService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : 'file_read',
            description: '''\
Read files and query filesystem metadata. Actions:
- read: full file content (auto-chunks if >threshold)
- head: first N lines
- tail: last N lines
- range: lines startLine..startLine+maxLines
- grep: lines matching regex pattern in a SINGLE file (use file_search for directory-wide search)
- multi: read up to 10 files at once (paths array in options)
- info: detailed file/dir metadata
- summary: line count, size, type only (no content)
- exists: check if path exists
- project_root: return active project root
- allowed_dirs: return allowed directory list
- normalize: convert path between Windows/WSL formats
- diff: compare two files (path vs options.compareTo)
- checksum: MD5/SHA-256 of file (options.algorithm)
- structure: outline of code/markdown structure
- chunk_read: retrieve chunk N of a paged read session
- finalise_read: discard a completed read session''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['read','head','tail','range','grep','multi','info','summary',
                                     'exists','project_root','allowed_dirs','normalize',
                                     'diff','checksum','structure','chunk_read','finalise_read']],
                    path   : [type: 'string', description: 'File or directory path (not required for project_root/allowed_dirs)'],
                    options: [type: 'object', description: 'Action-specific options',
                              properties: [
                                  lines     : [type: 'integer', description: 'Lines for head/tail'],
                                  startLine : [type: 'integer', description: 'Start line for range (1-indexed)'],
                                  maxLines  : [type: 'integer', description: 'Max lines for range'],
                                  pattern   : [type: 'string',  description: 'Regex for grep'],
                                  maxMatches: [type: 'integer', description: 'Max grep matches'],
                                  encoding  : [type: 'string',  description: 'File encoding (default UTF-8)'],
                                  paths     : [type: 'array', items: [type: 'string'], description: 'Paths for multi read'],
                                  compareTo : [type: 'string',  description: 'Second file path for diff'],
                                  algorithm : [type: 'string',  description: 'Checksum algorithm: MD5|SHA-256'],
                                  sessionId : [type: 'string',  description: 'Chunk session ID for chunk_read/finalise_read'],
                                  chunkIndex: [type: 'integer', description: 'Chunk index (0-based) for chunk_read']
                              ]]
                ],
                required  : ['action']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == 'file_read' }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action  = arguments.action as String
            String path    = arguments.path as String
            Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>

            switch (action) {
                case 'read'        : return doRead(path, options, requestId)
                case 'head'        : return doHead(path, options, requestId)
                case 'tail'        : return doTail(path, options, requestId)
                case 'range'       : return doRange(path, options, requestId)
                case 'grep'        : return doGrep(path, options, requestId)
                case 'multi'       : return doMulti(options, requestId)
                case 'info'        : return doInfo(path, requestId)
                case 'summary'     : return doSummary(path, requestId)
                case 'exists'      : return doExists(path, requestId)
                case 'project_root': return doProjectRoot(requestId)
                case 'allowed_dirs': return doAllowedDirs(requestId)
                case 'normalize'   : return doNormalize(path, requestId)
                case 'diff'        : return doDiff(path, options, requestId)
                case 'checksum'    : return doChecksum(path, options, requestId)
                case 'structure'   : return doStructure(path, requestId)
                case 'chunk_read'  : return doChunkRead(options, requestId)
                case 'finalise_read': return doFinaliseRead(options, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown file_read action: ${action}")
            }
        } catch (SecurityException e) {
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (FileNotFoundException e) {
            return McpResponse.error(requestId, -32602, sanitize(e.message))
        } catch (Exception e) {
            log.error("file_read error: {}", sanitize(e.message), e)
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Read actions
    // -----------------------------------------------------------------------

    private McpResponse doRead(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        String content    = new File(normalized).getText(encoding)

        // Auto-chunk if large
        if (ChunkBufferService.needsChunking(content)) {
            String sessionId = ChunkBufferService.newSessionId()
            Map<String, Object> sessionInfo = chunkBufferService.createReadSession(sessionId, content)
            log.info("file_read auto-chunked '{}' — {} chunks", normalized, sessionInfo.totalChunks)
            return textResponse(requestId, [
                action    : 'read',
                path      : normalized,
                chunked   : true,
                sessionId : sessionInfo.sessionId,
                totalChunks: sessionInfo.totalChunks,
                chunkSize : sessionInfo.chunkSize,
                message   : "File is large — use action=chunk_read with sessionId and chunkIndex 0..${(sessionInfo.totalChunks as int) - 1}, then action=finalise_read when done"
            ])
        }

        return textResponse(requestId, [action: 'read', path: normalized, content: sanitize(content), size: content.length()])
    }

    private McpResponse doHead(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        int lines         = (options.lines as Integer) ?: 50
        String encoding   = options.encoding as String ?: 'UTF-8'

        List<String> result = []
        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String line
            while (result.size() < lines && (line = br.readLine()) != null) {
                result << truncateAndSanitize(line)
            }
        }

        return textResponse(requestId, [action: 'head', path: normalized, lines: result.size(), content: result.join('\n')])
    }

    private McpResponse doTail(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        int lines         = (options.lines as Integer) ?: 50
        String encoding   = options.encoding as String ?: 'UTF-8'

        List<String> all = new File(normalized).readLines(encoding)
        int from = Math.max(0, all.size() - lines)
        List<String> result = all.subList(from, all.size()).collect { truncateAndSanitize(it) }

        return textResponse(requestId, [action: 'tail', path: normalized, lines: result.size(), content: result.join('\n')])
    }

    private McpResponse doRange(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        int startLine     = (options.startLine as Integer) ?: 1
        int maxLines      = (options.maxLines as Integer) ?: 100
        String encoding   = options.encoding as String ?: 'UTF-8'

        List<String> result = []
        int current = 0
        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String line
            while ((line = br.readLine()) != null) {
                current++
                if (current < startLine) continue
                if (current >= startLine + maxLines) break
                result << truncateAndSanitize(line)
            }
        }

        return textResponse(requestId, [
            action: 'range', path: normalized,
            startLine: startLine, endLine: startLine + result.size() - 1,
            lines: result.size(), content: result.join('\n')
        ])
    }

    private McpResponse doGrep(String path, Map<String, Object> options, Object requestId) {
        String normalized  = validateFilePath(path)
        String patternStr  = options.pattern as String
        if (!patternStr) return McpResponse.error(requestId, -32602, "options.pattern required for grep")

        int maxMatches = (options.maxMatches as Integer) ?: maxSearchMatchesPerFile
        String encoding = options.encoding as String ?: 'UTF-8'
        Pattern compiled = safeCompilePattern(patternStr)

        List<Map<String, Object>> matches = []
        int lineNum = 0
        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String line
            while ((line = br.readLine()) != null && matches.size() < maxMatches) {
                lineNum++
                if (compiled.matcher(line).find()) {
                    matches << ([line: lineNum, content: truncateAndSanitize(line)] as Map<String, Object>)
                }
            }
        }

        return textResponse(requestId, [action: 'grep', path: normalized, pattern: sanitize(patternStr), matchCount: matches.size(), matches: matches])
    }

    private McpResponse doMulti(Map<String, Object> options, Object requestId) {
        List<String> paths = (options.paths as List<String>) ?: []
        if (!paths) return McpResponse.error(requestId, -32602, "options.paths required for multi read")
        if (paths.size() > maxReadMultiple) paths = paths.take(maxReadMultiple)

        String encoding = options.encoding as String ?: 'UTF-8'

        // Parallel file reads via Promises — each on its own virtual thread
        List<Promise<Map<String, Object>>> promises = paths.collect { String p ->
            Promises.async({ ->
                try {
                    String normalized = validateFilePath(p)
                    String content    = new File(normalized).getText(encoding)
                    return [path: normalized, content: sanitize(content), size: content.length(), success: true] as Map<String, Object>
                } catch (Exception e) {
                    return [path: sanitize(p), error: sanitize(e.message), success: false] as Map<String, Object>
                }
            } as Callable<Map<String, Object>>)
        }

        List<Map<String, Object>> results = Promises.all(promises).get(30, TimeUnit.SECONDS)
        return textResponse(requestId, [action: 'multi', count: results.size(), files: results])
    }

    // -----------------------------------------------------------------------
    // Metadata actions
    // -----------------------------------------------------------------------

    private McpResponse doInfo(String path, Object requestId) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        Path p = Paths.get(normalized)
        Map<String, Object> infoResult = pathToMap(p)
        infoResult.put('action', 'info')
        return textResponse(requestId, infoResult)
    }

    private McpResponse doSummary(String path, Object requestId) {
        String normalized = validateFilePath(path)
        Path p = Paths.get(normalized)
        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class)
        int lineCount = 0
        try { lineCount = new File(normalized).readLines().size() } catch (Exception ignored) {}
        return textResponse(requestId, [
            action: 'summary', path: normalized,
            size: attrs.size(), lines: lineCount,
            lastModified: attrs.lastModifiedTime().toMillis()
        ])
    }

    private McpResponse doExists(String path, Object requestId) {
        String normalized = pathService.normalizePath(path)
        boolean allowed   = isPathAllowed(normalized)
        boolean exists    = allowed && Files.exists(Paths.get(normalized))
        String type       = exists ? (Files.isDirectory(Paths.get(normalized)) ? 'directory' : 'file') : null
        return textResponse(requestId, [action: 'exists', path: normalized, exists: exists, type: type])
    }

    private McpResponse doProjectRoot(Object requestId) {
        return textResponse(requestId, [action: 'project_root', projectRoot: getProjectRoot()])
    }

    private McpResponse doAllowedDirs(Object requestId) {
        return textResponse(requestId, [action: 'allowed_dirs', allowedDirectories: allowedDirectories])
    }

    private McpResponse doNormalize(String path, Object requestId) {
        Map<String, String> representations = pathService.getPathRepresentations(path)
        return textResponse(requestId, [action: 'normalize'] + representations)
    }

    // -----------------------------------------------------------------------
    // Advanced actions
    // -----------------------------------------------------------------------

    private McpResponse doDiff(String path, Map<String, Object> options, Object requestId) {
        String compareTo = options.compareTo as String
        if (!compareTo) return McpResponse.error(requestId, -32602, "options.compareTo required for diff")

        String normA = validateFilePath(path)
        String normB = validateFilePath(compareTo)

        // Parallel reads — both files loaded concurrently on virtual threads
        Promise<List<String>> readA = Promises.async({ -> new File(normA).readLines('UTF-8') } as Callable<List<String>>)
        Promise<List<String>> readB = Promises.async({ -> new File(normB).readLines('UTF-8') } as Callable<List<String>>)
        List<List<String>> both = Promises.all([readA, readB]).get(30, TimeUnit.SECONDS)
        List<String> linesA = both[0]
        List<String> linesB = both[1]

        List<Map<String, Object>> diffs = []
        int maxA = linesA.size(), maxB = linesB.size()
        int maxLines = Math.max(maxA, maxB)

        for (int i = 0; i < maxLines && diffs.size() < 200; i++) {
            String a = i < maxA ? linesA[i] : null
            String b = i < maxB ? linesB[i] : null
            if (a != b) {
                diffs << ([
                    line: i + 1,
                    a   : a != null ? truncateAndSanitize(a) : '(no line)',
                    b   : b != null ? truncateAndSanitize(b) : '(no line)'
                ] as Map<String, Object>)
            }
        }

        return textResponse(requestId, [
            action    : 'diff',
            pathA     : normA, linesA: maxA,
            pathB     : normB, linesB: maxB,
            diffCount : diffs.size(),
            identical : diffs.isEmpty(),
            diffs     : diffs
        ])
    }

    private McpResponse doChecksum(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        String algorithm  = options.algorithm as String ?: 'SHA-256'

        MessageDigest digest = MessageDigest.getInstance(algorithm)
        new File(normalized).withInputStream { InputStream is ->
            byte[] buf = new byte[8192]
            int read
            while ((read = is.read(buf)) != -1) {
                digest.update(buf, 0, read)
            }
        }
        String hex = digest.digest().encodeHex().toString()

        return textResponse(requestId, [action: 'checksum', path: normalized, algorithm: algorithm, checksum: hex])
    }

    private McpResponse doStructure(String path, Object requestId) {
        String normalized = validateFilePath(path)
        String ext = normalized.contains('.') ? normalized.tokenize('.').last().toLowerCase() : ''

        List<Map<String, Object>> structure = []
        int lineNum = 0

        new File(normalized).eachLine('UTF-8') { String line ->
            lineNum++
            String trimmed = line.trim()

            // Groovy/Java: class, interface, enum, def method, void/type method
            if (ext in ['groovy', 'java', 'kt', 'kts']) {
                if (trimmed =~ /^(public|protected|private|static|abstract|final|\s)*(class|interface|enum|record)\s+\w+/) {
                    structure << ([line: lineNum, type: 'class', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                } else if (trimmed =~ /^\s*(def|void|String|int|long|boolean|List|Map|Object)\s+\w+\s*\(/) {
                    structure << ([line: lineNum, type: 'method', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                }
            }
            // Markdown: headings
            else if (ext == 'md') {
                java.util.regex.Matcher m = trimmed =~ /^(#{1,6})\s+(.+)/
                if (m.find()) {
                    String hashes  = m.group(1)
                    String heading = m.group(2)
                    structure << ([line: lineNum, type: "h${hashes.length()}", content: sanitize(heading)] as Map<String, Object>)
                }
            }
            // Generic: comments that look like section headers
            else {
                if (trimmed =~ /^\/\/ ={4,}/ || trimmed =~ /^# ={4,}/) {
                    structure << ([line: lineNum, type: 'section', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                }
            }
        }

        return textResponse(requestId, [action: 'structure', path: normalized, ext: ext, count: structure.size(), structure: structure])
    }

    // -----------------------------------------------------------------------
    // Chunk read actions (delegated to ChunkBufferService)
    // -----------------------------------------------------------------------

    private McpResponse doChunkRead(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        int chunkIndex   = (options.chunkIndex as Integer) ?: 0
        if (!sessionId) return McpResponse.error(requestId, -32602, "options.sessionId required for chunk_read")

        String chunk = chunkBufferService.getReadChunk(sessionId, chunkIndex)
        return textResponse(requestId, [action: 'chunk_read', sessionId: sessionId, chunkIndex: chunkIndex, content: chunk])
    }

    private McpResponse doFinaliseRead(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        if (!sessionId) return McpResponse.error(requestId, -32602, "options.sessionId required for finalise_read")

        chunkBufferService.finaliseRead(sessionId)
        return textResponse(requestId, [action: 'finalise_read', sessionId: sessionId, success: true])
    }
}
