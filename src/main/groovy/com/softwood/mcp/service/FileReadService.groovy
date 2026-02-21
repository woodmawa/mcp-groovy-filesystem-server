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
 * FileReadService  handles the file_read tool.
 *
 * actions: read | head | tail | range | grep | multi | info | summary |
 *          exists | project_root | allowed_dirs | normalize |
 *          diff | checksum | structure
 *
 * Large files are automatically chunked via ChunkBufferService.
 *
 * v0.0.7  Phase 2 Core File Tools
 * v0.7.2p  P2 fixes: structure parser broadened, double-normalise removed, summary line count efficient
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
- read(path): full file content (auto-chunks if >300KB - follow sessionId/totalChunks in response)
- head(path, options.lines=50): first N lines
- tail(path, options.lines=50): last N lines
- range(path, options.startLine, options.maxLines=100): line slice, 1-indexed
- grep(path, options.pattern, options.maxMatches=10): regex matches in ONE file
- multi(options.paths[]): read up to 10 files in parallel - cheapest multi-file read
- info(path): file/dir metadata
- summary(path): line count + size only - NO content, cheapest existence check
- exists(path): boolean exists + type
- project_root: active project root path
- allowed_dirs: list of permitted directories
- normalize(path): Windows/WSL path conversion
- diff(path, options.compareTo): line-by-line diff of two files
- checksum(path, options.algorithm=SHA-256): file hash
- structure(path): code/markdown outline - FILE path only, NOT directory (use file_list tree for dirs)
- chunk_read(options.sessionId, options.chunkIndex): retrieve one chunk from a paged read
- finalise_read(options.sessionId): free chunk session when all chunks consumed
NOTE: Use summary before read on unknown files to check size. Batch reads with multi.''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['read','head','tail','range','grep','multi','info','summary',
                                     'exists','project_root','allowed_dirs','normalize',
                                     'diff','checksum','structure','chunk_read','finalise_read']],
                    path   : [type: 'string', description: 'File or dir path (not required for project_root/allowed_dirs/multi/chunk_read/finalise_read)'],
                    options: [type: 'object', description: 'Action-specific options',
                              properties: [
                                  lines     : [type: 'integer', description: 'Lines for head/tail (default 50)'],
                                  startLine : [type: 'integer', description: 'Start line for range, 1-indexed (required for range)'],
                                  maxLines  : [type: 'integer', description: 'Max lines for range (default 100)'],
                                  pattern   : [type: 'string',  description: 'Regex for grep (required for grep)'],
                                  maxMatches: [type: 'integer', description: 'Max grep matches (default 10)'],
                                  encoding  : [type: 'string',  description: 'File encoding (default UTF-8)'],
                                  paths     : [type: 'array', items: [type: 'string'], description: 'File paths for multi (required for multi, max 10)'],
                                  compareTo : [type: 'string',  description: 'Second file for diff (required for diff)'],
                                  algorithm : [type: 'string',  description: 'Checksum: MD5|SHA-256 (default SHA-256)'],
                                  sessionId : [type: 'string',  description: 'Session ID (required for chunk_read, finalise_read)'],
                                  chunkIndex: [type: 'integer', description: 'Chunk index 0-based (required for chunk_read)']
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
            log.info("file_read auto-chunked '{}'  {} chunks", normalized, sessionInfo.totalChunks)
            return textResponse(requestId, [
                action    : 'read',
                path      : normalized,
                chunked   : true,
                sessionId : sessionInfo.sessionId,
                totalChunks: sessionInfo.totalChunks,
                chunkSize : sessionInfo.chunkSize,
                message   : ("File is large - use action=chunk_read with sessionId and chunkIndex 0..${(sessionInfo.totalChunks as int) - 1}, then action=finalise_read when done" as String)
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

        // Ring-buffer: stream the file without loading it all into memory
        // ArrayDeque acts as a fixed-size ring buffer holding the last N lines
        ArrayDeque<String> ring = new ArrayDeque<String>(lines + 1)
        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String line
            while ((line = br.readLine()) != null) {
                ring.addLast(line)
                if (ring.size() > lines) ring.pollFirst()
            }
        }
        List<String> result = ring.collect { truncateAndSanitize(it) }

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
                if (current > startLine + maxLines - 1) break
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
        long sizeCapBytes = (long) readChunkThresholdKb * 1024

        // Parallel file reads via Promises  each on its own virtual thread
        // P2 fix: guard each file against oversized reads before loading
        List<Promise<Map<String, Object>>> promises = paths.collect { String p ->
            Promises.async({ ->
                try {
                    String normalized = validateFilePath(p)
                    long fileSize = Files.size(Paths.get(normalized))
                    if (fileSize > sizeCapBytes) {
                        return [path: normalized, error: "File too large for multi (${fileSize} bytes > ${sizeCapBytes} cap). Use read with chunking.", success: false] as Map<String, Object>
                    }
                    String content = new File(normalized).getText(encoding)
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
        // P2 fix: streaming line count - no full file load into memory
        long lineCount = 0L
        try {
            java.util.stream.Stream<String> ls = Files.lines(p)
            ls.withCloseable { lineCount = it.count() }
        } catch (Exception ignored) {}
        return textResponse(requestId, [
            action: 'summary', path: normalized,
            size: attrs.size(), lines: (int) lineCount,
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

        // Parallel reads  both files loaded concurrently on virtual threads
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
        // P2 fix: single normalise - reuse preCheck, no second call to validateFilePath
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) {
            return McpResponse.error(requestId, -32603, "Path not allowed: ${sanitize(normalized)}")
        }
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

        String ext = normalized.contains('.') ? normalized.tokenize('.').last().toLowerCase() : ''

        List<Map<String, Object>> structure = []
        int lineNum = 0

        new File(normalized).eachLine('UTF-8') { String line ->
            lineNum++
            String trimmed = line.trim()

            if (ext in ['groovy', 'java', 'kt', 'kts']) {
                // Class-level: class, interface, enum, record, trait, annotation
                if (trimmed =~ /^(public\s+|protected\s+|private\s+|static\s+|abstract\s+|final\s+)*(class|interface|enum|record|trait|@interface)\s+\w+/) {
                    structure << ([line: lineNum, type: 'class', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                // Method: any access modifier or return type (including custom types) followed by methodName(
                // Excludes control flow keywords and variable declarations
                } else if (trimmed =~ /^(public\s+|protected\s+|private\s+|static\s+|final\s+|synchronized\s+|override\s+)*[\w<\[\]>]+\s+\w+\s*\(/ &&
                           trimmed =~ /\)/ &&
                           !(trimmed =~ /^(if|else|for|while|switch|catch|try|return|throw|new|import|package|def\s+\w+\s*=|assert)\b/)) {
                    structure << ([line: lineNum, type: 'method', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                }
            } else if (ext == 'md') {
                java.util.regex.Matcher m = trimmed =~ /^(#{1,6})\s+(.+)/
                if (m.find()) {
                    String hashes  = m.group(1)
                    String heading = m.group(2)
                    structure << ([line: lineNum, type: "h${hashes.length()}", content: sanitize(heading)] as Map<String, Object>)
                }
            } else {
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
