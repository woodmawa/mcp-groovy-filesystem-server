package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.promise.Promises
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.ChunkBufferService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.StructureCache
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * FileContentReader - handles read, head, tail, range, grep, multi_grep, get_method,
 * structure, chunk_read, and finalise_read actions.
 *
 * <p>All content-returning methods delegate hash hint injection and token metering to
 * {@link ReadResponseHelper}. The helper decides whether to emit a {@code _knownhash_hint}
 * and whether to return {@code {unchanged:true}} on a matching knownHash.</p>
 *
 * <h3>Range cache contract</h3>
 * <p>{@code doRange} does NOT fire auto-lookup (FIX-KH-AUTO is whole-file only). If a
 * caller passes {@code options.knownHash} to {@code doRange} and the file is unchanged,
 * the response is {@code {unchanged:true}} -- i.e. the range content is suppressed.
 * Callers MUST NOT pass knownHash to action=range. The session range cache in CS handles
 * range deduplication automatically via {@code recordRangeCacheAsync} in FileReadService.</p>
 *
 * <h3>grep single-pass contract (v0.9.0)</h3>
 * <p>{@code doGrep} uses a single rotating-window pass for both the contextLines=0 and
 * contextLines>0 cases. The before-window deque and pending-after collector list are
 * initialised unconditionally; both are empty/no-op when contextLines=0. This eliminates
 * the two-branch duplication (D8) and ensures grep behaviour is identical regardless of
 * whether context lines are requested.</p>
 *
 * v0.7.44 - extracted from FileReadService as part of read/ subpackage split.
 * v0.9.0  - PR 3.1: doGrep unified to single rotating-window pass (D8).
 */
@Service
@Slf4j
@CompileStatic
class FileContentReader extends AbstractFileService {

    @Autowired
    ChunkBufferService chunkBufferService

    @Autowired
    StructureCache structureCache

    @Autowired
    ReadResponseHelper helper

    @Value('${mcp.filesystem.read-chunk-threshold-kb:60}')
    int readChunkThresholdKb

    @Value('${mcp.filesystem.read-soft-cap-chars:8000}')
    int readSoftCapChars

    @Value('${mcp.filesystem.partial-read-cap-chars:12000}')
    int partialReadCapChars

    @Value('${mcp.filesystem.multi-read-cap-chars:24000}')
    int multiReadCapChars

    @Value('${mcp.filesystem.read-max-lines-before-refuse:200}')
    int readMaxLinesBeforeRefuse

    /** Non-code doc/config files below this size (bytes) get a higher line limit to avoid
     *  spurious refusals on briefs, YAML configs, etc. Default 100KB. (FS-T4) */
    @Value('${mcp.filesystem.read-max-lines-docs-size-bytes:102400}')
    long readMaxLinesDocsSizeBytes

    /** Higher line limit for doc/config files under the size threshold. Default 600. (FS-T4) */
    @Value('${mcp.filesystem.read-max-lines-docs:600}')
    int readMaxLinesDocs

    private static final Set<String> DOC_EXTENSIONS =
        ['.md', '.txt', '.yml', '.yaml', '.json', '.toml', '.ini', '.properties', '.xml'] as Set

    FileContentReader(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // read
    // -----------------------------------------------------------------------

    McpResponse doRead(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'

        // FIX-KH-AUTO: doRead is a whole-file action -- pass autoLookup=true
        McpResponse unchanged = helper.checkKnownHash(normalized, options, requestId, true)
        if (unchanged != null) return unchanged

        long fileSize    = Files.size(Paths.get(normalized))
        long threshBytes = (long) readChunkThresholdKb * 1024

        if (!(options.force as boolean)) {
            // FS-T4: doc/config files under the size threshold use a higher line limit
            // to avoid spurious refusals on briefs, YAML, and other non-code files.
            boolean isDocFile = DOC_EXTENSIONS.any { normalized.toLowerCase().endsWith(it) }
            int effectiveLimit = (isDocFile && fileSize <= readMaxLinesDocsSizeBytes)
                ? readMaxLinesDocs
                : readMaxLinesBeforeRefuse
            int lineCount = ReadResponseHelper.countLinesUpTo(normalized, effectiveLimit, encoding)
            if (lineCount > effectiveLimit) {
                return McpResponse.toolError(requestId, ("read refused: file has more than ${effectiveLimit} lines" +
                     (isDocFile ? " (doc/config file limit=${effectiveLimit})" : '') + ". " +
                     "Use targeted actions instead:\n" +
                     "  1. structure(path, compact=true) - get method/section outline\n" +
                     "  2. get_method(path, method) - read one method body\n" +
                     "  3. range(path, startLine, maxLines) - read specific lines\n" +
                     "  4. grep(path, pattern) - find specific content\n" +
                     "Pass options.force=true only if you genuinely need the full file." as String))
            }
        }

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
        String hash    = structureCache.getHash(normalized)

        boolean truncated = false
        if (content.length() > readSoftCapChars) {
            content   = content.substring(0, readSoftCapChars)
            truncated = true
        }

        Map<String, Object> resp
        if (isCompact(options)) {
            resp = [content: sanitize(content), lines: content.count('\n') + 1, file_content_hash: hash] as Map<String, Object>
        } else {
            resp = [action: 'read', path: normalized, content: sanitize(content), size: content.length(), file_content_hash: hash] as Map<String, Object>
        }
        if (truncated) {
            long totalChars = Files.size(Paths.get(normalized))
            resp._truncated = true
            resp._truncatedNote = ("Response truncated at ${readSoftCapChars} chars (~${readSoftCapChars / 4000 as int}K tokens). " +
                "File has ~${totalChars} bytes total. Use head/range/grep for targeted reads, or use read with chunking for full content." as String)
        } else {
            helper.maybeAddSizeWarning(resp, content.length())
        }
        helper.injectSessionTokenMeter(resp, content.length())
        // FIX-KH-AUTO: autoStore=true for whole-file read
        helper.storeAndHintKnownHash(resp, normalized, options, true)
        // FS 0.9.1: ontology-first guard -- warns when reading indexed .groovy/.java without prior locate
        helper.maybeAddOntologyGuardHint(resp, normalized)
        return textResponse(requestId, resp)
    }

    private int streamFileToChunks(String normalized, String sessionId, String encoding) {
        Charset charset  = Charset.forName(encoding)
        int chunkChars   = ChunkBufferService.MAX_CHUNK_BYTES
        char[] buf       = new char[8192]
        StringBuilder sb = new StringBuilder(chunkChars)
        int chunkIdx     = 0

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
        chunkBufferService.registerStreamedReadSession(sessionId, chunkIdx)
        return chunkIdx
    }

    // -----------------------------------------------------------------------
    // head
    // -----------------------------------------------------------------------

    McpResponse doHead(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        McpResponse unchanged = helper.checkKnownHash(normalized, options, requestId)
        if (unchanged != null) return unchanged
        int lines       = (options.lines as Integer) ?: 50
        String encoding = options.encoding as String ?: 'UTF-8'

        List<String> result = []
        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String line
            while (result.size() < lines && (line = br.readLine()) != null) {
                result << truncateAndSanitize(line)
            }
        }

        String joined    = result.join('\n')
        boolean truncated = false
        if (joined.length() > partialReadCapChars) {
            joined    = joined.substring(0, partialReadCapChars)
            truncated = true
        }
        String hash = structureCache.getHash(normalized)
        Map<String, Object> resp
        if (isCompact(options)) {
            resp = [content: joined, lines: result.size(), file_content_hash: hash] as Map<String, Object>
        } else {
            resp = [action: 'head', path: normalized, lines: result.size(), content: joined, file_content_hash: hash] as Map<String, Object>
        }
        if (truncated) {
            resp._truncated = true
            resp._truncatedNote = ("head output truncated at ${partialReadCapChars} chars (~${partialReadCapChars / 4000 as int}K tokens). Reduce lines= or use range for targeted reads." as String)
        } else {
            helper.maybeAddSizeWarning(resp, joined.length())
        }
        helper.injectSessionTokenMeter(resp, joined.length())
        return textResponse(requestId, resp)
    }

    // -----------------------------------------------------------------------
    // tail
    // -----------------------------------------------------------------------

    McpResponse doTail(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        McpResponse unchanged = helper.checkKnownHash(normalized, options, requestId)
        if (unchanged != null) return unchanged
        int lines       = (options.lines as Integer) ?: 50
        String encoding = options.encoding as String ?: 'UTF-8'

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
        String joined        = result.join('\n')

        boolean truncated = false
        if (joined.length() > partialReadCapChars) {
            joined    = joined.substring(0, partialReadCapChars)
            truncated = true
        }
        Map<String, Object> resp
        if (isCompact(options)) {
            resp = [content: joined, lines: result.size(), file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
        } else {
            resp = [action: 'tail', path: normalized, lines: result.size(), content: joined, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
        }
        if (truncated) {
            resp._truncated = true
            resp._truncatedNote = ("tail output truncated at ${partialReadCapChars} chars (~${partialReadCapChars / 4000 as int}K tokens). Reduce lines= or use range for targeted reads." as String)
        } else {
            helper.maybeAddSizeWarning(resp, joined.length())
        }
        helper.injectSessionTokenMeter(resp, joined.length())
        return textResponse(requestId, resp)
    }

    // -----------------------------------------------------------------------
    // range
    // -----------------------------------------------------------------------

    McpResponse doRange(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        McpResponse unchanged = helper.checkKnownHash(normalized, options, requestId)
        if (unchanged != null) return unchanged
        int startLine   = (options.startLine as Integer) ?: 1
        int maxLines    = (options.maxLines as Integer) ?: 100
        String encoding = options.encoding as String ?: 'UTF-8'

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
        String joined    = result.join('\n')
        boolean truncated = false
        if (joined.length() > partialReadCapChars) {
            joined    = joined.substring(0, partialReadCapChars)
            truncated = true
        }
        String hash = structureCache.getHash(normalized)
        Map<String, Object> resp
        if (isCompact(options)) {
            resp = [content: joined, lines: result.size(), startLine: startLine,
                    endLine: startLine + result.size() - 1, file_content_hash: hash] as Map<String, Object>
        } else {
            resp = [action: 'range', path: normalized, startLine: startLine,
                    endLine: startLine + result.size() - 1, lines: result.size(),
                    content: joined, file_content_hash: hash] as Map<String, Object>
        }
        if (truncated) {
            resp._truncated = true
            resp._truncatedNote = ("range output truncated at ${partialReadCapChars} chars (~${partialReadCapChars / 4000 as int}K tokens). Use smaller maxLines or target a narrower startLine." as String)
        } else {
            helper.maybeAddSizeWarning(resp, joined.length())
        }
        helper.injectSessionTokenMeter(resp, joined.length())
        // range is partial-content -- no autoStore (Option A, brief §18.3)
        helper.storeAndHintKnownHash(resp, normalized, options)
        // FIX-6 (FS 0.8.80): shadow probe -- validates hash accuracy without changing semantics
        helper.shadowAutoKhProbe(resp, normalized, 'range')
        return textResponse(requestId, resp)
    }

    // -----------------------------------------------------------------------
    // grep
    // -----------------------------------------------------------------------

    McpResponse doGrep(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        String patternStr = options.pattern as String
        if (!patternStr) return McpResponse.toolError(requestId, 'options.pattern required for grep')

        int maxMatches   = (options.maxMatches as Integer) ?: maxSearchMatchesPerFile
        int contextLines = (options.contextLines as Integer) ?: 0
        String encoding  = options.encoding as String ?: 'UTF-8'
        Pattern compiled = safeCompilePattern(patternStr)

        // Single-pass rotating-window: handles contextLines=0 as degenerate case (empty deque, no pending).
        List<Map<String, Object>> matches = []
        int lineNum           = 0
        int totalContentChars = 0
        boolean sizeCapped    = false
        ArrayDeque<String> beforeBuf  = new ArrayDeque<>(contextLines > 0 ? contextLines + 1 : 1)
        List<Map<String, Object>> pendingAfter = []   // [{matchEntry, remaining, afterList}]

        new File(normalized).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            String line
            while ((line = br.readLine()) != null) {
                lineNum++
                String sanitized = truncateAndSanitize(line)

                // Advance any pending after-collectors
                if (!pendingAfter.isEmpty()) {
                    Iterator<Map<String, Object>> pit = pendingAfter.iterator()
                    while (pit.hasNext()) {
                        Map<String, Object> p = pit.next()
                        (p.afterList as List<String>) << sanitized
                        p.remaining = (p.remaining as int) - 1
                        if ((p.remaining as int) <= 0) pit.remove()
                    }
                }

                // Check match cap before the pattern test to avoid over-reading
                if (matches.size() < maxMatches && !sizeCapped && compiled.matcher(line).find()) {
                    totalContentChars += sanitized.length()
                    if (totalContentChars > partialReadCapChars) {
                        sizeCapped = true
                    } else {
                        List<String> before    = contextLines > 0 ? new ArrayList<>(beforeBuf as Collection<String>) : []
                        List<String> afterList = contextLines > 0 ? [] : null
                        Map<String, Object> entry = contextLines > 0
                            ? ([line: lineNum, content: sanitized, before: before, after: afterList] as Map<String, Object>)
                            : ([line: lineNum, content: sanitized] as Map<String, Object>)
                        matches << entry
                        if (contextLines > 0) pendingAfter << ([remaining: contextLines, afterList: afterList] as Map<String, Object>)
                    }
                }

                // Maintain the before-window (no-op when contextLines=0)
                if (contextLines > 0) {
                    beforeBuf.addLast(sanitized)
                    if (beforeBuf.size() > contextLines) beforeBuf.pollFirst()
                }
            }
        }

        Map<String, Object> resp = isCompact(options)
            ? [matchCount: matches.size(), matches: matches, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
            : [action: 'grep', path: normalized, pattern: sanitize(patternStr),
               matchCount: matches.size(), matches: matches, file_content_hash: structureCache.getHash(normalized)] as Map<String, Object>
        if (contextLines > 0) resp.contextLines = contextLines
        if (sizeCapped) {
            resp._sizeCapped = true
            resp._sizeCappedNote = ("grep results capped at ~${partialReadCapChars} chars (~${partialReadCapChars / 4000 as int}K tokens). Reduce maxMatches or narrow pattern." as String)
        }
        return textResponse(requestId, resp)
    }

    // -----------------------------------------------------------------------
    // multi-path grep
    // -----------------------------------------------------------------------

    /**
     * doMultiGrep — run one pattern across a list of files in a single call.
     *
     * options.paths    : List<String>  — file paths to grep (max 20)
     * options.pattern  : String        — regex pattern (required)
     * options.maxMatches : int         — per-file match cap (default 5)
     * options.contextLines : int       — before/after lines per match (default 0)
     *
     * Returns: {action:'multi_grep', pattern, fileCount, matchingFiles, results:[{path, matchCount, matches:[...]}]}
     * Only files with at least one match are included in results.
     * Files with no match contribute to fileCount but not matchingFiles or results.
     */
    McpResponse doMultiGrep(Map<String, Object> options, Object requestId) {
        List<String> paths = (options.paths as List<String>) ?: []
        if (!paths) return McpResponse.toolError(requestId, 'options.paths required for multi_grep')
        if (paths.size() > 20) paths = paths.take(20)

        String patternStr = options.pattern as String
        if (!patternStr) return McpResponse.toolError(requestId, 'options.pattern required for multi_grep')

        int maxMatchesPerFile = (options.maxMatches as Integer) ?: 5
        int contextLines      = (options.contextLines as Integer) ?: 0
        String encoding       = options.encoding as String ?: 'UTF-8'
        Pattern compiled      = safeCompilePattern(patternStr)

        List<Map<String, Object>> results = []
        int totalMatchingFiles = 0
        int totalMatches = 0

        for (String rawPath : paths) {
            try {
                String normalized = validateFilePath(rawPath)
                List<Map<String, Object>> matches = []
                int lineNum = 0
                ArrayDeque<String> beforeBuf = contextLines > 0 ? new ArrayDeque<>(contextLines + 1) : null

                new File(normalized).withReader(encoding) { Reader r ->
                    BufferedReader br = new BufferedReader(r)
                    String line
                    while ((line = br.readLine()) != null && matches.size() < maxMatchesPerFile) {
                        lineNum++
                        if (compiled.matcher(line).find()) {
                            String sanitized = truncateAndSanitize(line)
                            Map<String, Object> entry = [line: lineNum, content: sanitized] as Map<String, Object>
                            if (contextLines > 0 && beforeBuf != null) {
                                entry.before = new ArrayList<>(beforeBuf as Collection<String>)
                            }
                            matches << entry
                            totalMatches++
                        }
                        if (contextLines > 0 && beforeBuf != null) {
                            beforeBuf.addLast(truncateAndSanitize(line))
                            if (beforeBuf.size() > contextLines) beforeBuf.pollFirst()
                        }
                    }
                }
                if (matches) {
                    totalMatchingFiles++
                    results << ([path: normalized, matchCount: matches.size(), matches: matches] as Map<String, Object>)
                }
            } catch (SecurityException ignored) {
                results << ([path: rawPath, error: 'path not allowed'] as Map<String, Object>)
            } catch (FileNotFoundException ignored) {
                results << ([path: rawPath, error: 'file not found'] as Map<String, Object>)
            } catch (Exception e) {
                results << ([path: rawPath, error: sanitize(e.message)] as Map<String, Object>)
            }
        }

        return textResponse(requestId, [
            action        : 'multi_grep',
            pattern       : sanitize(patternStr),
            fileCount     : paths.size(),
            matchingFiles : totalMatchingFiles,
            totalMatches  : totalMatches,
            results       : results
        ] as Map<String, Object>)
    }

    // -----------------------------------------------------------------------
    // multi
    // -----------------------------------------------------------------------

    McpResponse doMulti(Map<String, Object> options, Object requestId) {
        List<String> paths = (options.paths as List<String>) ?: []
        if (!paths) return McpResponse.toolError(requestId, 'options.paths required for multi read')
        if (paths.size() > maxReadMultiple) paths = paths.take(maxReadMultiple)

        String encoding   = options.encoding as String ?: 'UTF-8'
        long sizeCapBytes = (long) readChunkThresholdKb * 1024

        Map<String, String> rawKnown = (options.knownHashes instanceof Map)
            ? (options.knownHashes as Map<String, String>)
            : ([:] as Map<String, String>)
        Map<String, String> knownHashes = [:] as Map<String, String>
        rawKnown.each { String k, String v ->
            try { knownHashes[pathService.normalizePath(k) as String] = v as String }
            catch (Exception ignored) {}
        }

        long totalBytes       = 0L
        long aggregateCapBytes = 256L * 1024L
        for (String p : paths) {
            try {
                String norm = pathService.normalizePath(p)
                if (!isPathAllowed(norm)) continue
                String currentHash = structureCache.getHash(norm)
                if (knownHashes[norm] && knownHashes[norm] == currentHash) continue
                totalBytes += Files.size(Paths.get(norm))
            } catch (Exception ignored) {}
        }
        if (totalBytes > aggregateCapBytes) {
            return McpResponse.toolError(requestId, ("multi: aggregate file sizes (~${totalBytes / 1024}KB) exceed 1MB cap. " +
                 "Use individual reads with chunking for large files." as String))
        }

        List<com.softwood.mcp.promise.Promise<Map<String, Object>>> promises = paths.collect { String p ->
            Promises.async(({ ->
                try {
                    String normalized  = validateFilePath(p)
                    String currentHash = structureCache.getHash(normalized)

                    if (knownHashes[normalized] && knownHashes[normalized] == currentHash) {
                        return [path: normalized, unchanged: true,
                                file_content_hash: currentHash, success: true] as Map<String, Object>
                    }

                    long fileSize = Files.size(Paths.get(normalized))
                    if (fileSize > sizeCapBytes) {
                        return [path: normalized, error: "File too large for multi (${fileSize} bytes > ${sizeCapBytes} cap). Use read with chunking.", success: false] as Map<String, Object>
                    }
                    String content = new File(normalized).getText(encoding)
                    return [path: normalized, content: sanitize(content),
                            size: content.length(), file_content_hash: currentHash,
                            success: true] as Map<String, Object>
                } catch (Exception e) {
                    return [path: sanitize(p), error: sanitize(e.message), success: false] as Map<String, Object>
                }
            } as Callable<Map<String, Object>>))
        }

        List<Map<String, Object>> results = Promises.all(promises).get(30, TimeUnit.SECONDS)

        int unchangedCount = results.count { Map<String, Object> r -> r.unchanged == true } as int

        int aggChars  = 0
        boolean aggCapped = false
        for (Map<String, Object> r : results) {
            if (r.success && !r.unchanged && r.content) {
                String c = r.content as String
                aggChars += c.length()
                if (aggChars > multiReadCapChars) {
                    int allowed = Math.max(0, multiReadCapChars - (aggChars - c.length()))
                    r.content   = allowed > 0 ? c.substring(0, allowed) : ''
                    r._truncated = true
                    aggCapped    = true
                }
            }
        }
        Map<String, Object> resp = [
            action         : 'multi',
            count          : results.size(),
            unchanged_count: unchangedCount,
            files          : results
        ] as Map<String, Object>
        if (aggCapped) {
            resp._sizeCapped = true
            resp._sizeCappedNote = ("multi aggregate output capped at ${multiReadCapChars} chars (~${multiReadCapChars / 4000 as int}K tokens). Use fewer files or head/range for targeted reads." as String)
        }
        return textResponse(requestId, resp)
    }
}
