package com.softwood.mcp.service

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ContextServerClient - HTTP client for persisting filesystem knowledge to
 * the context server's project knowledge layer, and reading it back.
 *
 * DESIGN PRINCIPLE: the context server is an ACCELERATION LAYER, never a dependency.
 *   - Writes: fire-and-forget via single daemon thread. Never block tool responses.
 *   - Reads:  500ms timeout with immediate fallback to filesystem. If down: transparent.
 *
 * Persists:
 *   - File structure results after every doStructure() cache miss
 *   - Directory listings after every children/tree call
 *
 * v0.7.44 - created; moved to service/ package from service/read/ to share with FileListService.
 */
@Service
@Slf4j
@CompileStatic
class ContextServerClient {

    @Value('${mcp.context-server.url:http://localhost:8082}')
    String contextServerUrl

    @Value('${mcp.context-server.structure-group-id:mcp-servers}')
    String structureGroupId

    @Value('${mcp.context-server.structure-persist-enabled:true}')
    boolean structurePersistEnabled

    @Value('${mcp.filesystem.directory-cache-enabled:true}')
    boolean directoryCacheEnabled

    /** Read timeout for cache lookups. 500ms is non-negotiable - filesystem must not block. */
    @Value('${mcp.context-server.read-timeout-ms:500}')
    int readTimeoutMs

    private static final int MAX_PERSIST_ENTRIES = 150

    private final ExecutorService asyncWriter = Executors.newSingleThreadExecutor { Runnable r ->
        Thread t = new Thread(r, 'ctx-client-writer')
        t.daemon = true
        t
    }

    // Active session ID — lazily resolved from context server, cached for session duration
    private volatile String activeSessionId = null

    /**
     * Liveness flag for context server HTTP endpoint.
     * Tripped to false on first ConnectException (e.g. stdio-only sessions where port 8082 is not open).
     * Logged once at INFO then all subsequent failures silently dropped at DEBUG.
     * Reset to true only on server restart.
     */
    private volatile boolean contextServerReachable = true

    // -----------------------------------------------------------------------
    // In-memory directory listing cache (session-scoped, zero I/O)
    // -----------------------------------------------------------------------

    static class CachedListing {
        String listingHash
        long   dirMtime
        List<Map<String, Object>> entries
    }

    private final ConcurrentHashMap<String, CachedListing> dirListingCache = new ConcurrentHashMap<>()

    // -----------------------------------------------------------------------
    // Structure persistence (WI5)
    // -----------------------------------------------------------------------

    /**
     * Persist a file's structure to the context server asynchronously.
     * Only fires when the StructureCache had a miss (wasCached=false).
     */
    void persistStructureAsync(String filePath, String fileHash, List<Map<String, Object>> entries) {
        if (!structurePersistEnabled || !contextServerReachable) return
        asyncWriter.submit {
            try { doPersistStructure(filePath, fileHash, entries) }
            catch (ConnectException e) {
                if (contextServerReachable) {
                    contextServerReachable = false
                    log.info('ContextServerClient: context server unreachable at {} — structure persistence disabled for this session', contextServerUrl)
                }
            }
            catch (Exception e) { log.debug('ContextServerClient: structure persist failed (non-fatal): {}', e.message) }
        }
    }

    private void doPersistStructure(String filePath, String fileHash, List<Map<String, Object>> entries) {
        String filename = new File(filePath).name
        String stem     = filename.contains('.') ? filename.tokenize('.').first() : filename

        List<Map<String, Object>> compact = entries.take(MAX_PERSIST_ENTRIES).collect { Map<String, Object> e ->
            Map<String, Object> m = [line: e.line, type: e.type] as Map<String, Object>
            if (e.endLine) m.endLine = e.endLine
            String c = e.content as String
            if (c) m.content = c.length() > 120 ? c.substring(0, 120) : c
            m
        }

        String description = JsonOutput.toJson([hash: fileHash, path: filePath, count: entries.size(), entries: compact])
        if (description.length() > 4000) description = description.substring(0, 4000) + '...(truncated)'

        postToContextServer('practice', 'add', structureGroupId, 'file-structure',
            "${stem} [${fileHash}]" as String, description, ['file-structure', stem])
    }

    // -----------------------------------------------------------------------
    // Directory listing cache (Addendum A)
    // -----------------------------------------------------------------------

    /**
     * Persist a directory listing asynchronously (fire-and-forget).
     * Also updates the in-memory cache immediately so the same-session read path
     * hits the cache without any HTTP round-trip.
     */
    void persistDirectoryListingAsync(String normalizedPath, List<Map<String, Object>> entries,
                                       String listingHash, long dirMtime) {
        if (!directoryCacheEnabled) return

        // Update in-memory cache immediately (synchronous - O(1))
        dirListingCache.put(normalizedPath, new CachedListing(
            listingHash: listingHash, dirMtime: dirMtime, entries: entries))

        // Async persist to context server for cross-session recovery
        asyncWriter.submit {
            try { doPersistDirectoryListing(normalizedPath, entries, listingHash, dirMtime) }
            catch (Exception e) { log.debug('ContextServerClient: directory listing persist failed (non-fatal): {}', e.message) }
        }
    }

    /**
     * Get a cached directory listing.
     * Fast path: in-memory cache (zero I/O).
     * Slow path: context server with 500ms timeout.
     * Returns null if not cached or stale — caller must list the filesystem.
     *
     * Staleness: dirMtime mismatch means a file was added/removed — invalidate.
     */
    CachedListing getDirectoryListing(String normalizedPath) {
        if (!directoryCacheEnabled) return null

        // Fast path: in-memory cache
        long currentDirMtime = new File(normalizedPath).lastModified()
        CachedListing cached = dirListingCache.get(normalizedPath)
        if (cached != null) {
            if (cached.dirMtime == currentDirMtime) {
                log.debug('DirCache in-memory HIT: {}', normalizedPath)
                return cached
            }
            dirListingCache.remove(normalizedPath)
            log.debug('DirCache in-memory STALE (mtime changed): {}', normalizedPath)
            return null
        }

        // Slow path: try context server with read timeout
        try {
            CachedListing fromServer = fetchDirectoryListingFromServer(normalizedPath, currentDirMtime)
            if (fromServer != null) {
                dirListingCache.put(normalizedPath, fromServer)  // warm in-memory cache
                return fromServer
            }
        } catch (Exception e) {
            log.debug('DirCache context server fetch failed (non-fatal): {}', e.message)
        }
        return null
    }

    private CachedListing fetchDirectoryListingFromServer(String normalizedPath, long currentDirMtime) {
        // Search context server practices for a directory-listing entry matching this path
        Map<String, Object> requestBody = [
            jsonrpc: '2.0', method: 'tools/call', id: 1,
            params : [
                name     : 'context_read',
                arguments: [scope: 'project', action: 'practices', groupId: structureGroupId]
            ]
        ] as Map<String, Object>

        String responseText = postWithTimeout(JsonOutput.toJson(requestBody), readTimeoutMs)
        if (!responseText) return null

        // Parse response and find matching directory-listing entry
        Map parsed = (Map) new JsonSlurper().parseText(responseText)
        List practices = extractPracticesFromResponse(parsed)
        Map match = practices.find { Object p ->
            (p as Map).category == 'directory-listing' && (p as Map).title == normalizedPath
        } as Map
        if (!match) return null

        Map desc = (Map) new JsonSlurper().parseText(match.description as String ?: '{}')
        long storedMtime = (desc.dirMtime as Long) ?: 0L
        if (storedMtime != currentDirMtime) {
            log.debug('DirCache context server entry stale for {}: stored mtime={} current={}',
                normalizedPath, storedMtime, currentDirMtime)
            return null
        }

        List<Map<String, Object>> entries = (desc.entries as List<Map<String, Object>>) ?: []
        return new CachedListing(
            listingHash: desc.hash as String,
            dirMtime   : storedMtime,
            entries    : entries
        )
    }

    private void doPersistDirectoryListing(String normalizedPath, List<Map<String, Object>> entries,
                                            String listingHash, long dirMtime) {
        String lastSegments = normalizedPath.replace('\\', '/').tokenize('/').takeRight(2).join('/')

        // Compact entries: keep name, type, size only
        List<Map<String, Object>> compact = entries.take(500).collect { Map<String, Object> e ->
            [name: e.name, type: e.type, size: e.size] as Map<String, Object>
        }

        String description = JsonOutput.toJson([
            hash    : listingHash,
            dirMtime: dirMtime,
            path    : normalizedPath,
            count   : entries.size(),
            entries : compact
        ])
        if (description.length() > 8000) description = description.substring(0, 8000) + '...(truncated)'

        postToContextServer('practice', 'add', structureGroupId, 'directory-listing',
            normalizedPath, description, ['directory-listing', lastSegments])
    }

    // -----------------------------------------------------------------------
    // Shared HTTP helpers
    // -----------------------------------------------------------------------

    /**
     * Fire-and-forget: upsert a file's hash into the context server's file-registry
     * AND track it in session_working_files for the active Claude Code session.
     * Never blocks — failures are silently logged at DEBUG.
     */
    void upsertFileRegistryAsync(String normalizedPath, String contentHash, int lineCount, long lastModified) {
        if (!structurePersistEnabled || !contentHash || !contextServerReachable) return
        String path = normalizedPath
        String hash = contentHash
        int lc = lineCount
        long lm = lastModified
        String sid = resolveSessionId()
        asyncWriter.submit({
            try {
                String pathHash = sha256Short(path)
                String pathTail = shortPathTail(path)
                Map<String, Object> body = [
                    jsonrpc: '2.0', method: 'tools/call', id: 1,
                    params : [
                        name     : 'context_write',
                        arguments: [scope: 'knowledge', type: 'file-registry', action: 'upsert',
                                    pathHash: pathHash, pathTail: pathTail,
                                    contentHash: hash, lineCount: lc, lastModified: lm,
                                    filePath: path, sessionId: sid ?: '']
                    ]
                ] as Map<String, Object>
                URL url = new URL("${contextServerUrl}/mcp")
                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                try {
                    conn.requestMethod = 'POST'
                    conn.doOutput     = true
                    conn.connectTimeout = 2000
                    conn.readTimeout    = 3000
                    conn.setRequestProperty('Content-Type', 'application/json')
                    conn.outputStream.withWriter('UTF-8') { Writer w -> w.write(JsonOutput.toJson(body)) }
                    int status = conn.responseCode
                    if (status != 200) {
                        log.debug('upsertFileRegistry: context server returned {} for {}', status, pathTail)
                    }
                } finally { conn.disconnect() }
            } catch (ConnectException e) {
                if (contextServerReachable) {
                    contextServerReachable = false
                    log.info('ContextServerClient: context server unreachable at {} — file registry updates disabled for this session (stdio-only mode?)', contextServerUrl)
                }
                // Subsequent failures are silent — flag already tripped
            } catch (Exception e) {
                log.debug('upsertFileRegistry async failed for {}: {}', path, e.message)
            }
        } as Runnable)
    }

    /** Allow external callers (e.g. McpController on lifecycle start) to prime the session ID cache. */
    void setActiveSessionId(String sessionId) {
        this.activeSessionId = sessionId
        log.debug('ContextServerClient: activeSessionId set to {}', sessionId)
    }

    /**
     * Resolve the active session ID. Returns cached value if available; otherwise
     * makes one lightweight GET to the context server and caches the result.
     * Returns null if context server is unreachable or no session is active.
     */
    private String resolveSessionId() {
        if (activeSessionId) return activeSessionId
        if (!structurePersistEnabled) return null
        try {
            URL url = new URL("${contextServerUrl}/current-session")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            try {
                conn.requestMethod  = 'GET'
                conn.connectTimeout = 1000
                conn.readTimeout    = 1000
                if (conn.responseCode == 200) {
                    String body2 = conn.inputStream.getText('UTF-8')
                    Map parsed = (Map) new groovy.json.JsonSlurper().parseText(body2)
                    String sid = parsed?.get('session_id') as String
                    if (sid) {
                        activeSessionId = sid
                        log.debug('ContextServerClient: resolved session_id={}', sid)
                    }
                    return sid
                }
            } finally { conn.disconnect() }
        } catch (Exception e) {
            log.debug('resolveSessionId failed: {}', e.message)
        }
        return null
    }

    /**
     * Fire-and-forget: re-index a source file in the ontology after a write.
     * Only fires for .groovy / .java files. Queued on the same asyncWriter executor.
     */
    void reindexFileAsync(String normalizedPath) {
        if (!structurePersistEnabled) return
        String path = normalizedPath
        asyncWriter.submit({
            try {
                Map<String, Object> body = [
                    jsonrpc: '2.0', method: 'tools/call', id: 1,
                    params : [
                        name     : 'context_write',
                        arguments: [scope: 'ontology', type: 'node', action: 'index',
                                    filePath: path, clearExisting: true]
                    ]
                ] as Map<String, Object>
                URL url = new URL("${contextServerUrl}/mcp")
                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                try {
                    conn.requestMethod = 'POST'
                    conn.doOutput     = true
                    conn.connectTimeout = 2000
                    conn.readTimeout    = 5000
                    conn.setRequestProperty('Content-Type', 'application/json')
                    conn.outputStream.withWriter('UTF-8') { Writer w -> w.write(JsonOutput.toJson(body)) }
                    int status = conn.responseCode
                    log.debug('reindexFile: context server returned {} for {}', status, path)
                } finally { conn.disconnect() }
            } catch (Exception e) {
                log.debug('reindexFile async failed for {}: {}', path, e.message)
            }
        } as Runnable)
    }

    private static String sha256Short(String input) {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance('SHA-256')
        byte[] hash = md.digest(input.getBytes('UTF-8'))
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < 6; i++) sb.append(String.format('%02x', hash[i] & 0xff))
        return sb.toString()
    }

    private static String shortPathTail(String path) {
        String normalised = path.replace('\\', '/')
        int slash = normalised.lastIndexOf('/')
        if (slash < 0) return normalised.take(200)
        int prev = normalised.lastIndexOf('/', slash - 1)
        return (prev >= 0 ? normalised.substring(prev + 1) : normalised.substring(slash + 1)).take(200)
    }

    private void postToContextServer(String type, String action, String groupId,
                                      String category, String title, String description,
                                      List<String> tags) {
        Map<String, Object> body = [
            jsonrpc: '2.0', method: 'tools/call', id: 1,
            params : [
                name     : 'context_write',
                arguments: [scope: 'project', type: type, action: action,
                            groupId: groupId, category: category,
                            title: title, description: description, tags: tags]
            ]
        ] as Map<String, Object>

        URL url = new URL("${contextServerUrl}/mcp")
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        try {
            conn.requestMethod = 'POST'
            conn.doOutput      = true
            conn.connectTimeout = 3000
            conn.readTimeout    = 5000
            conn.setRequestProperty('Content-Type', 'application/json')
            conn.outputStream.withWriter('UTF-8') { Writer w -> w.write(JsonOutput.toJson(body)) }
            int status = conn.responseCode
            if (status == 200) {
                log.debug('ContextServerClient: persisted {} [{}] to context server', category, title.take(60))
            } else {
                log.debug('ContextServerClient: context server returned {} for {}', status, title.take(60))
            }
        } finally {
            conn.disconnect()
        }
    }

    private String postWithTimeout(String jsonBody, int timeoutMs) {
        URL url = new URL("${contextServerUrl}/mcp")
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        try {
            conn.requestMethod  = 'POST'
            conn.doOutput       = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout    = timeoutMs
            conn.setRequestProperty('Content-Type', 'application/json')
            conn.outputStream.withWriter('UTF-8') { Writer w -> w.write(jsonBody) }
            if (conn.responseCode == 200) {
                return conn.inputStream.withReader('UTF-8') { Reader r -> r.text }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private static List extractPracticesFromResponse(Map parsed) {
        try {
            // MCP response: result.content[0].text -> JSON string
            List content = (parsed?.get('result') as Map)?.get('content') as List
            if (!content) return []
            String text = (content[0] as Map)?.get('text') as String
            if (!text) return []
            Map data = (Map) new JsonSlurper().parseText(text)
            return (data.practices as List) ?: []
        } catch (Exception ignored) {
            return []
        }
    }

    /** Compute a hash over sorted entry names+mtimes for staleness detection. */
    static String computeListingHash(List<Map<String, Object>> entries) {
        String key = entries
            .sort(false) { Map a, Map b -> (a.name as String) <=> (b.name as String) }
            .collect { Map e -> "${e.name}:${e.lastModified ?: 0}" }
            .join('|')
        Integer.toHexString(key.hashCode()).padLeft(8, '0')
    }

    @PreDestroy
    void shutdown() {
        asyncWriter.shutdown()
        try {
            if (!asyncWriter.awaitTermination(3, TimeUnit.SECONDS)) asyncWriter.shutdownNow()
        } catch (InterruptedException e) {
            asyncWriter.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
