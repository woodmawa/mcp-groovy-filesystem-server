package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.promise.Promise
import com.softwood.mcp.promise.Promises
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.charset.Charset
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

    @Autowired
    StructureCache structureCache

    @Value('${mcp.filesystem.read-chunk-threshold-kb:300}')
    int readChunkThresholdKb

    @Value('${mcp.filesystem.large-response-warn-chars:15000}')
    int largeResponseWarnChars

    /** Soft response-size cap for sub-threshold reads (chars). Responses larger than this are
     *  truncated with an explanatory note. Default 40000 = ~10K tokens (aligned with partial-read cap). */
    @Value('${mcp.filesystem.read-soft-cap-chars:40000}')
    int readSoftCapChars

    /** Hard cap for head/tail/range/grep content (chars). Prevents context overflow from
     *  large line counts. Default 40000 = ~10K tokens. Truncates with explanatory note. */
    @Value('${mcp.filesystem.partial-read-cap-chars:40000}')
    int partialReadCapChars

    /** Hard cap for multi aggregate output (chars across all files). Default 80000 = ~20K tokens. */
    @Value('${mcp.filesystem.multi-read-cap-chars:80000}')
    int multiReadCapChars

    /** Max entries returned by structure action. Prevents huge JSON arrays for files with many methods.
     *  Default 100. Total entries count always returned separately. */
    @Value('${mcp.filesystem.structure-max-entries:100}')
    int structureMaxEntries
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
- grep(path, options.pattern, options.maxMatches=10, options.contextLines=0): regex matches; FILE path only, NOT directory. set contextLines>0 for before/after context
- multi(options.paths[]): read up to 10 files in parallel - cheapest multi-file read.
  NOTE: aggregate content capped at ~1MB total. For large files, use individual read with chunking.
- info(path): file/dir metadata
- summary(path): line count + size only - NO content, cheapest existence check
- exists(path): boolean exists + type
- project_root: active project root path
- allowed_dirs: list of permitted directories
- normalize(path): Windows/WSL path conversion
- diff(path, options.compareTo): line-by-line diff of two files
- checksum(path, options.algorithm=SHA-256): file hash
- structure(path): code/markdown outline with line AND endLine per entry - FILE path only, NOT directory. options.compact=true returns methods only (no endLine, ~50% smaller)
- get_method(path, options.method): returns complete named method body - FILE path only, NOT directory. Preferred over structure+range for editing
- chunk_read(options.sessionId, options.chunkIndex): retrieve one chunk from a paged read
- finalise_read(options.sessionId): free chunk session when all chunks consumed
NOTE: Use summary before read on unknown files to check size. Batch reads with multi.
NOTE: Use get_method to read a single method before editing it - cheaper than structure+range.
NOTE: read/head/tail/range/grep/get_method all return file_content_hash (12-char SHA-256 of whole file).
      Pass this as options.expectedHash on patch/replace/multi_replace to guard against drift.''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['read','head','tail','range','grep','multi','info','summary',
                                     'exists','project_root','allowed_dirs','normalize',
                                     'diff','checksum','structure','get_method','chunk_read','finalise_read']],
                    path   : [type: 'string', description: 'File or dir path (not required for project_root/allowed_dirs/multi/chunk_read/finalise_read)'],
                    options: [type: 'object', description: 'Action-specific options',
                              properties: [
                                  lines       : [type: 'integer', description: 'Lines for head/tail (default 50)'],
                                  startLine   : [type: 'integer', description: 'Start line for range, 1-indexed (required for range)'],
                                  maxLines    : [type: 'integer', description: 'Max lines for range (default 100)'],
                                  pattern     : [type: 'string',  description: 'Regex for grep (required for grep)'],
                                  maxMatches  : [type: 'integer', description: 'Max grep matches (default 10)'],
                                  contextLines: [type: 'integer', description: 'Lines of context before/after each grep match (default 0)'],
                                  method      : [type: 'string',  description: 'Method name for get_method (required for get_method)'],
                                  fuzzy       : [type: 'boolean', description: 'If true, match method name as substring (for get_method)'],
                                  encoding    : [type: 'string',  description: 'File encoding (default UTF-8)'],
                                  paths       : [type: 'array', items: [type: 'string'], description: 'File paths for multi (required for multi, max 10)'],
                                  compareTo   : [type: 'string',  description: 'Second file for diff (required for diff)'],
                                  algorithm   : [type: 'string',  description: 'Checksum: MD5|SHA-256 (default SHA-256)'],
                                  sessionId   : [type: 'string',  description: 'Session ID (required for chunk_read, finalise_read)'],
                                  chunkIndex  : [type: 'integer', description: 'Chunk index 0-based (required for chunk_read)'],
                                  compact     : [type: 'boolean', description: 'Minimal response - omits action/path echo, returns content+hash only. Supported by read, head, tail, range, grep, structure (structure: methods only, no endLine)']
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
            Map<String, Object> options = normaliseOptions(arguments.options)

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
                case 'structure'   : return doStructure(path, options, requestId)
                case 'get_method'  : return doGetMethod(path, options, requestId)
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

    /** Compute a 12-char SHA-256 prefix of the whole file — cheap drift token for callers.
     *  Returned as file_content_hash in all read responses so callers can use expectedHash
     *  on the next patch/replace without a separate checksum call. */
    // fileHash() removed v0.7.9 - delegated to structureCache.getHash()
    // which caches the 12-char SHA-256 keyed on (path, lastModified), invalidated on write.


    private McpResponse doRead(String path, Map<String, Object> options, Object requestId) {
        String normalized   = validateFilePath(path)
        String encoding     = options.encoding as String ?: 'UTF-8'
        long fileSize       = Files.size(Paths.get(normalized))
        long threshBytes    = (long) readChunkThresholdKb * 1024

        // S-I1 FIX: stream large files directly into chunks - never load full file into memory
        if (fileSize > threshBytes) {
            String sessionId = ChunkBufferService.newSessionId()
            int totalChunks  = streamFileToChunks(normalized, sessionId, encoding)
            log.info("file_read auto-chunked '{}' ({} bytes) -> {} chunks (streamed)", normalized, fileSize, totalChunks)
            return textResponse(requestId, [
                action     : 'read',
                path       : normalized,
                chunked    : true,
                sessionId  : sessionId,
                totalChunks: totalChunks,
                chunkSize  : ChunkBufferService.MAX_CHUNK_BYTES,
                message    : ("File is large - use action=chunk_read with sessionId and chunkIndex 0..${totalChunks - 1}, then action=finalise_read when done" as String)
            ])
        }

        String content = new File(normalized).getText(encoding)
        String hash = structureCache.getHash(normalized)

        // FIX-15: soft response-size cap  truncate before shipping to protect context window
        boolean truncated = false
        if (content.length() > readSoftCapChars) {
            content  = content.substring(0, readSoftCapChars)
            truncated = true
        }

        Map<String, Object> resp
        if (isCompact(options)) {
            resp = [content: sanitize(content), lines: content.count('\n') + 1, file_content_hash: hash] as Map<String, Object>
        } else {
            resp = [action: 'read', path: normalized, content: sanitize(content), size: content.length(), file_content_hash: hash] as Map<String, Object>
        }
        if (truncated) {
            long totalChars = Files.size(Paths.get(normalized))  // raw byte count (close enough for message)
            resp._truncated = true
            resp._truncatedNote = ("Response truncated at ${readSoftCapChars} chars (~${readSoftCapChars / 4000 as int}K tokens). " +
                "File has ~${totalChars} bytes total. Use head/range/grep for targeted reads, or use read with chunking for full content." as String)
        } else {
            maybeAddSizeWarning(resp, content.length())
        }
        return textResponse(requestId, resp)
    }

    /**
     * S-I1 FIX: Stream a large file directly into ChunkBufferService in fixed-size slices.
     * Peak heap cost = 1 chunk buffer (MAX_CHUNK_BYTES) rather than 2-3× the full file size.
     * Returns the total number of chunks created.
     */
    private int streamFileToChunks(String normalized, String sessionId, String encoding) {
        Charset charset   = Charset.forName(encoding)
        int chunkChars    = ChunkBufferService.MAX_CHUNK_BYTES  // treat as char budget per chunk
        char[] buf        = new char[8192]
        StringBuilder sb  = new StringBuilder(chunkChars)
        int chunkIdx      = 0

        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r, 65536)
            int read
            while ((read = br.read(buf)) != -1) {
                sb.append(buf, 0, read)
                if (sb.length() >= chunkChars) {
                    chunkBufferService.storeReadChunk(sessionId, chunkIdx++, sb.toString())
                    sb.setLength(0)
                }
            }
        }
        if (sb.length() > 0) {
            chunkBufferService.storeReadChunk(sessionId, chunkIdx++, sb.toString())
        }
        // Register the session expiry (chunks already stored individually)
        chunkBufferService.registerStreamedReadSession(sessionId, chunkIdx)
        return chunkIdx
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

        String joinedHead = result.join('\n')
        // FIX-17: hard cap head output to avoid context overflow
        boolean truncatedHead = false
        if (joinedHead.length() > partialReadCapChars) {
            joinedHead = joinedHead.substring(0, partialReadCapChars)
            truncatedHead = true
        }
        String hashHead = structureCache.getHash(normalized)
        Map<String, Object> respHead
        if (isCompact(options)) {
            respHead = [content: joinedHead, lines: result.size(), file_content_hash: hashHead] as Map<String, Object>
        } else {
            respHead = [action: 'head', path: normalized, lines: result.size(), content: joinedHead, file_content_hash: hashHead] as Map<String, Object>
        }
        if (truncatedHead) {
            respHead._truncated = true
            respHead._truncatedNote = ("head output truncated at ${partialReadCapChars} chars (~${partialReadCapChars/4000 as int}K tokens). Reduce lines= or use range for targeted reads." as String)
        } else {
            maybeAddSizeWarning(respHead, joinedHead.length())
        }
        return textResponse(requestId, respHead)
    }

    private McpResponse doTail(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        int lines         = (options.lines as Integer) ?: 50
        String encoding   = options.encoding as String ?: 'UTF-8'

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
        String joinedTail = result.join('\n')

        // FIX-17: hard cap tail output to avoid context overflow
        boolean truncatedTail = false
        if (joinedTail.length() > partialReadCapChars) {
            joinedTail = joinedTail.substring(0, partialReadCapChars)
            truncatedTail = true
        }
        Map<String, Object> respTail
        if (isCompact(options)) {
            respTail = [content: joinedTail, lines: result.size(), file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
        } else {
            respTail = [action: 'tail', path: normalized, lines: result.size(), content: joinedTail, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
        }
        if (truncatedTail) {
            respTail._truncated = true
            respTail._truncatedNote = ("tail output truncated at ${partialReadCapChars} chars (~${partialReadCapChars/4000 as int}K tokens). Reduce lines= or use range for targeted reads." as String)
        } else {
            maybeAddSizeWarning(respTail, joinedTail.length())
        }
        return textResponse(requestId, respTail)
    }
    private McpResponse doRange(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        int startLine     = (options.startLine as Integer) ?: 1
        int maxLines      = (options.maxLines as Integer) ?: 100
        String encoding   = options.encoding as String ?: 'UTF-8'

        // FIX-17: hard cap maxLines to prevent requesting thousands of lines in one shot
        if (maxLines > 500) maxLines = 500

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
        String joinedRange = result.join('\n')
        // FIX-17: hard cap range char output
        boolean truncated = false
        if (joinedRange.length() > partialReadCapChars) {
            joinedRange = joinedRange.substring(0, partialReadCapChars)
            truncated = true
        }
        String hashRange = structureCache.getHash(normalized)
        Map<String, Object> respRange
        if (isCompact(options)) {
            respRange = [content: joinedRange, lines: result.size(), startLine: startLine,
                         endLine: startLine + result.size() - 1, file_content_hash: hashRange] as Map<String, Object>
        } else {
            respRange = [action: 'range', path: normalized, startLine: startLine,
                         endLine: startLine + result.size() - 1, lines: result.size(),
                         content: joinedRange, file_content_hash: hashRange] as Map<String, Object>
        }
        if (truncated) {
            respRange._truncated = true
            respRange._truncatedNote = ("range output truncated at ${partialReadCapChars} chars (~${partialReadCapChars/4000 as int}K tokens). Use smaller maxLines or target a narrower startLine." as String)
        } else {
            maybeAddSizeWarning(respRange, joinedRange.length())
        }
        return textResponse(requestId, respRange)
    }

    private McpResponse doGrep(String path, Map<String, Object> options, Object requestId) {
        String normalized  = validateFilePath(path)
        String patternStr  = options.pattern as String
        if (!patternStr) return McpResponse.error(requestId, -32602, "options.pattern required for grep")

        int maxMatches   = (options.maxMatches as Integer) ?: maxSearchMatchesPerFile
        int contextLines = (options.contextLines as Integer) ?: 0
        String encoding  = options.encoding as String ?: 'UTF-8'
        Pattern compiled = safeCompilePattern(patternStr)

        List<Map<String, Object>> matches = []
        int lineNum = 0
        // FIX-17: track running content chars to cap grep output and avoid context overflow
        int totalContentChars = 0
        boolean sizeCapped    = false

        if (contextLines > 0) {
            ArrayDeque<String> beforeBuf = new ArrayDeque<>(contextLines + 1)
            List<Map<String, Object>> pendingAfter = []

            new File(normalized).withReader(encoding) { Reader r ->
                BufferedReader br = new BufferedReader(r)
                String line
                while ((line = br.readLine()) != null) {
                    lineNum++
                    String sanitized = truncateAndSanitize(line)

                    Iterator<Map<String, Object>> pit = pendingAfter.iterator()
                    while (pit.hasNext()) {
                        Map<String, Object> p = pit.next()
                        (p.afterList as List<String>) << sanitized
                        p.remaining = (p.remaining as int) - 1
                        if ((p.remaining as int) <= 0) pit.remove()
                    }

                    if (matches.size() < maxMatches && !sizeCapped && compiled.matcher(line).find()) {
                        List<String> before = new ArrayList<>(beforeBuf as Collection<String>)
                        List<String> afterList = []
                        Map<String, Object> entry = [line: lineNum, content: sanitized, before: before, after: afterList] as Map<String, Object>
                        totalContentChars += sanitized.length()
                        if (totalContentChars > partialReadCapChars) {
                            sizeCapped = true
                        } else {
                            matches << entry
                            if (contextLines > 0) pendingAfter << ([matchEntry: entry, remaining: contextLines, afterList: afterList] as Map<String, Object>)
                        }
                    }

                    beforeBuf.addLast(sanitized)
                    if (beforeBuf.size() > contextLines) beforeBuf.pollFirst()
                }
            }

            Map<String, Object> grepResp = isCompact(options)
                ? [matchCount: matches.size(), matches: matches, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
                : [action: 'grep', path: normalized, pattern: sanitize(patternStr), contextLines: contextLines,
                   matchCount: matches.size(), matches: matches, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
            if (sizeCapped) {
                grepResp._sizeCapped = true
                grepResp._sizeCappedNote = ("grep results capped at ~${partialReadCapChars} chars (~${partialReadCapChars/4000 as int}K tokens). Reduce maxMatches or narrow pattern." as String)
            }
            return textResponse(requestId, grepResp)
        }

        // No context - simple streaming path
        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String line
            while ((line = br.readLine()) != null && matches.size() < maxMatches && !sizeCapped) {
                lineNum++
                if (compiled.matcher(line).find()) {
                    String sanitized = truncateAndSanitize(line)
                    totalContentChars += sanitized.length()
                    if (totalContentChars > partialReadCapChars) {
                        sizeCapped = true
                    } else {
                        matches << ([line: lineNum, content: sanitized] as Map<String, Object>)
                    }
                }
            }
        }
        Map<String, Object> grepResp2 = isCompact(options)
            ? [matchCount: matches.size(), matches: matches, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
            : [action: 'grep', path: normalized, pattern: sanitize(patternStr),
               matchCount: matches.size(), matches: matches, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
        if (sizeCapped) {
            grepResp2._sizeCapped = true
            grepResp2._sizeCappedNote = ("grep results capped at ~${partialReadCapChars} chars (~${partialReadCapChars/4000 as int}K tokens). Reduce maxMatches or narrow pattern." as String)
        }
        return textResponse(requestId, grepResp2)
    }

    private McpResponse doMulti(Map<String, Object> options, Object requestId) {
        List<String> paths = (options.paths as List<String>) ?: []
        if (!paths) return McpResponse.error(requestId, -32602, "options.paths required for multi read")
        if (paths.size() > maxReadMultiple) paths = paths.take(maxReadMultiple)

        String encoding = options.encoding as String ?: 'UTF-8'
        long sizeCapBytes = (long) readChunkThresholdKb * 1024

        // FIX-16: aggregate size pre-check - reject before loading if total would exceed 1 MB
        long totalBytes = 0L
        long aggregateCapBytes = 1024L * 1024L  // 1 MB
        for (String p : paths) {
            try {
                String norm = pathService.normalizePath(p)
                if (isPathAllowed(norm)) totalBytes += Files.size(Paths.get(norm))
            } catch (Exception ignored) {}
        }
        if (totalBytes > aggregateCapBytes) {
            return McpResponse.error(requestId, -32602,
                ("multi: aggregate file sizes (~${totalBytes / 1024}KB) exceed 1MB cap. " +
                 "Use individual reads with chunking for large files." as String))
        }

        // Parallel file reads via Promises - each on its own virtual thread
        // P2 fix: guard each file against oversized reads before loading
        List<Promise<Map<String, Object>>> promises = paths.collect { String p ->
            Promises.async(({ ->
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
            } as Callable<Map<String, Object>>))
        }

        List<Map<String, Object>> results = Promises.all(promises).get(30, TimeUnit.SECONDS)

        // FIX-17: cap aggregate content output to avoid context overflow
        int aggChars = 0
        boolean aggCapped = false
        for (Map<String, Object> r : results) {
            if (r.success && r.content) {
                String c = r.content as String
                aggChars += c.length()
                if (aggChars > multiReadCapChars) {
                    int allowed = Math.max(0, multiReadCapChars - (aggChars - c.length()))
                    r.content = allowed > 0 ? c.substring(0, allowed) : ''
                    r._truncated = true
                    aggCapped = true
                }
            }
        }
        Map<String, Object> multiResp = [action: 'multi', count: results.size(), files: results] as Map<String, Object>
        if (aggCapped) {
            multiResp._sizeCapped = true
            multiResp._sizeCappedNote = ("multi aggregate output capped at ${multiReadCapChars} chars (~${multiReadCapChars/4000 as int}K tokens). Use fewer files or head/range for targeted reads." as String)
        }
        return textResponse(requestId, multiResp)
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

        // FIX-11: size pre-check before loading both files into heap
        long sizeAKb = Files.size(Paths.get(normA)).intdiv(1024)
        long sizeBKb = Files.size(Paths.get(normB)).intdiv(1024)
        if (sizeAKb > readChunkThresholdKb || sizeBKb > readChunkThresholdKb) {
            return McpResponse.error(requestId, -32602,
                ("diff: one or both files exceed ${readChunkThresholdKb}KB threshold (${sizeAKb}KB vs ${sizeBKb}KB). Use grep or range for targeted comparison." as String))
        }

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

    /**
     * Core structure scanner — shared by doStructure and doGetMethod.
     * Returns map with keys: ext (String), structure (List<Map>) each entry having line, endLine, type, content.
     */
    private Map<String, Object> computeStructure(String normalized) {
        String ext = normalized.contains('.') ? normalized.tokenize('.').last().toLowerCase() : ''
        List<Map<String, Object>> structure = []
        int lineNum = 0
        int braceDepth = 0
        Deque<int[]> openStack = new ArrayDeque<>()

        new File(normalized).eachLine('UTF-8') { String line ->
            lineNum++
            String trimmed = line.trim()
            String stripped = trimmed.replaceAll(/"[^"]*"|'[^']*'|\/\/.*/, '')
            int open  = stripped.count('{')
            int close = stripped.count('}')

            if (close > 0) {
                braceDepth -= close
                while (!openStack.isEmpty() && openStack.peek()[1] > braceDepth) {
                    int[] top = openStack.pop()
                    structure[top[0]].endLine = lineNum
                }
            }

            if (ext in ['groovy', 'java', 'kt', 'kts']) {
                if (trimmed =~ /^(public\s+|protected\s+|private\s+|static\s+|abstract\s+|final\s+)*(class|interface|enum|record|trait|@interface)\s+\w+/) {
                    int idx = structure.size()
                    structure << ([line: lineNum, type: 'class', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                    if (open > 0) openStack.push([idx, braceDepth + open] as int[])
                } else if (trimmed =~ /^(public\s+|protected\s+|private\s+|static\s+|final\s+|synchronized\s+|override\s+)*[\w<\[\]>]+\s+\w+\s*\(/ &&
                           trimmed =~ /\)/ &&
                           !(trimmed =~ /^(if|else|for|while|switch|catch|try|return|throw|new|import|package|def\s+\w+\s*=|assert)\b/)) {
                    int idx = structure.size()
                    structure << ([line: lineNum, type: 'method', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                    if (open > 0) openStack.push([idx, braceDepth + open] as int[])
                }
            } else if (ext == 'md') {
                java.util.regex.Matcher m = trimmed =~ /^(#{1,6})\s+(.+)/
                if (m.find()) {
                    structure << ([line: lineNum, type: "h${m.group(1).length()}", content: sanitize(m.group(2))] as Map<String, Object>)
                }
            } else {
                if (trimmed =~ /^\/\/ ={4,}/ || trimmed =~ /^# ={4,}/) {
                    structure << ([line: lineNum, type: 'section', content: truncateAndSanitize(trimmed)] as Map<String, Object>)
                }
            }

            if (open > 0) braceDepth += open
        }

        return [ext: ext, structure: structure] as Map<String, Object>
    }

    private McpResponse doStructure(String path, Map<String, Object> options, Object requestId) {
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
        if (Files.size(filePath) > 512 * 1024L) {
            return McpResponse.error(requestId, -32602,
                "File too large for structure scan (>512KB). Use head/range/grep for large files: ${sanitize(normalized)}")
        }

        Map<String, Object> result = structureCache.getStructure(normalized)
        List<Map<String, Object>> entries = result.structure as List<Map<String, Object>>

        int totalEntries = entries.size()

        if (isCompact(options)) {
            // Compact: methods only, no endLine  ~50% smaller for files with many fields/classes
            List<Map<String, Object>> methods = entries
                .findAll { Map<String, Object> e -> e.type == 'method' }
                .collect { Map<String, Object> e ->
                    [line: e.line, type: e.type, content: e.content] as Map<String, Object>
                }
            // FIX-C: cap compact result too
            boolean compactCapped = methods.size() > structureMaxEntries
            if (compactCapped) methods = methods.take(structureMaxEntries)
            Map<String, Object> compactResp = [structure: methods, count: methods.size(), total_entries: totalEntries,
                                               file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
            if (compactCapped) compactResp.entries_capped = true
            return textResponse(requestId, compactResp)
        }

        // FIX-C: cap full list at structureMaxEntries (default 100), truncate content strings at 200 chars
        boolean entriesCapped = totalEntries > structureMaxEntries
        List<Map<String, Object>> cappedEntries = (entriesCapped ? entries.take(structureMaxEntries) : entries)
            .collect { Map<String, Object> e ->
                String c = e.content as String
                (c && c.length() > 200) ? (new LinkedHashMap<>(e) + [content: c.substring(0,200)+'...']) as Map<String,Object> : e
            }
        Map<String, Object> structResp = [action: 'structure', path: normalized, ext: result.ext,
                                          count: cappedEntries.size(), total_entries: totalEntries,
                                          structure: cappedEntries, scanner: result.scanner, cached: result.cached] as Map<String, Object>
        if (entriesCapped) structResp.entries_capped = true
        return textResponse(requestId, structResp)
    }

    // -----------------------------------------------------------------------
    // Get-method action — single call returns one named method's content
    // -----------------------------------------------------------------------

    private McpResponse doGetMethod(String path, Map<String, Object> options, Object requestId) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) {
            return McpResponse.error(requestId, -32603, "Path not allowed: ${sanitize(normalized)}")
        }
        if (!new File(normalized).exists()) {
            return McpResponse.error(requestId, -32602, "File not found: ${sanitize(normalized)}")
        }

        String methodName = options.method as String
        boolean fuzzy     = options.fuzzy as Boolean ?: false
        if (!methodName) return McpResponse.error(requestId, -32602, "options.method is required for get_method")

        // Direct call — no JSON serialise/deserialise round-trip
        List<Map> entries = structureCache.getStructure(normalized).structure as List<Map>

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

        // FIX-B: hard cap method body output to partialReadCapChars to prevent enormous method bodies overflowing context
        String content = lines.join('\n')
        boolean methodTruncated = content.length() > partialReadCapChars
        if (methodTruncated) content = content.substring(0, partialReadCapChars)

        Map<String, Object> gmResp = [
            action: 'get_method', path: normalized,
            method: methodName,
            startLine: startLine, endLine: startLine + lines.size() - 1,
            lines: lines.size(), content: content,
            file_content_hash: structureCache.getHash(normalized)
        ] as Map<String, Object>
        if (methodTruncated) {
            gmResp._truncated = true
            gmResp._truncatedNote = ("Method body truncated at ${partialReadCapChars} chars (~${partialReadCapChars/4000 as int}K tokens). Use action=range with startLine=${startLine} and maxLines to read the rest." as String)
        }
        return textResponse(requestId, gmResp)
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

    // -----------------------------------------------------------------------
    // Size-warning helper
    // -----------------------------------------------------------------------

    /** Appends a soft _sizeWarning to a response map when content is large enough to
     *  meaningfully consume context window budget. Threshold configurable via
     *  mcp.filesystem.large-response-warn-chars (default 15000 chars ≈ 3750 tokens). */
    private void maybeAddSizeWarning(Map<String, Object> response, int contentLength) {
        if (contentLength > largeResponseWarnChars) {
            long kb = Math.round(contentLength / 1024.0f)
            long tokens = Math.round(contentLength / 4.0f)
            response._sizeWarning = ("NOTE: response is ${kb}KB (~${tokens} tokens). " +
                "Consider head/range/grep for targeted reads to preserve context window." as String)
        }
    }
}
