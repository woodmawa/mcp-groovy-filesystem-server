package com.softwood.mcp.service

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import jakarta.annotation.PostConstruct
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

    /** Fallback for MCPB/stdio mode: queues reindex requests to shared SQLite when HTTP is unavailable. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    FilesystemTelemetryService telemetryService

    private static final int MAX_PERSIST_ENTRIES = 150

    private final ExecutorService asyncWriter = Executors.newSingleThreadExecutor { Runnable r ->
        Thread t = new Thread(r, 'ctx-client-writer')
        t.daemon = true
        t
    }

    // Active session ID — lazily resolved from context server, cached for session duration
    // package-scoped for McpController access (no private -- @CompileStatic blocks cross-class private field access)
    volatile String activeSessionId = null

    /**
     * Circuit breaker for context server HTTP endpoint (FIX-5, FS 0.8.79).
     * Replaces permanent boolean latch. Three states:
     *   CLOSED    - CS reachable; auto-KH active.
     *   OPEN      - Recent failure; suppress calls until csRetryAfterMs.
     *   HALF_OPEN - Allow one probe; success -> CLOSED, failure -> OPEN (longer backoff).
     *
     * Only ConnectException opens the circuit aggressively.
     * Recovery detected on any successful CS HTTP call (fileHashCache, fileRegistry, etc.).
     */
    private enum CsCircuitState { CLOSED, OPEN, HALF_OPEN }
    private volatile CsCircuitState csCircuitState = CsCircuitState.CLOSED
    private volatile long csRetryAfterMs = 0L
    private static final long[] CS_BACKOFF_MS = [5_000L, 15_000L, 30_000L, 60_000L] as long[]
    private volatile int csFailureCount = 0

    boolean isCsReachable() {
        switch (csCircuitState) {
            case CsCircuitState.CLOSED:    return true
            case CsCircuitState.OPEN:
                if (System.currentTimeMillis() < csRetryAfterMs) return false
                csCircuitState = CsCircuitState.HALF_OPEN
                return true   // allow probe
            case CsCircuitState.HALF_OPEN: return true
            default: return false
        }
    }

    void onCsSuccess() {
        if (csCircuitState != CsCircuitState.CLOSED) {
            log.info('ContextServerClient: CS circuit CLOSED (recovered after {} failures)', csFailureCount)
            csCircuitState = CsCircuitState.CLOSED
            csFailureCount = 0
        }
    }

    void onCsConnectFailure() {
        long backoff = CS_BACKOFF_MS[Math.min(csFailureCount, CS_BACKOFF_MS.length - 1)]
        csFailureCount = Math.min(csFailureCount + 1, CS_BACKOFF_MS.length)
        csRetryAfterMs = System.currentTimeMillis() + backoff
        if (csCircuitState != CsCircuitState.OPEN) {
            log.info('ContextServerClient: CS circuit OPEN (retry in {}ms, failure #{})', backoff, csFailureCount)
        }
        csCircuitState = CsCircuitState.OPEN
    }

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
        if (!structurePersistEnabled || !isCsReachable()) return
        asyncWriter.submit {
            try { doPersistStructure(filePath, fileHash, entries) }
            catch (ConnectException e) {
                onCsConnectFailure()
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
        if (!structurePersistEnabled || !contentHash || !isCsReachable()) return
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
                onCsConnectFailure()
                // Subsequent failures are silent - circuit breaker handles backoff
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
     * Eagerly resolve + cache the active session ID at startup.
     * Submitted async to asyncWriter so Spring context is fully wired before the JDBC read fires.
     * Eliminates the 'new session unknown' log spam that occurs when the first tool call
     * hits resolveSessionId() before the session ID has been primed.
     */
    @PostConstruct
    void eagerResolveSessionId() {
        asyncWriter.submit({
            try {
                String sid = resolveSessionId()
                if (sid) {
                    log.info('ContextServerClient: eagerly resolved session_id={} at startup', sid)
                } else {
                    log.debug('ContextServerClient: eager session resolve returned null (no active session yet)')
                }
            } catch (Exception e) {
                log.debug('ContextServerClient: eager session resolve failed: {}', e.message)
            }
        } as Runnable)
    }

    /**
     * Resolve the active session ID.
     * D1/D3/D4 fix: reads active_session table directly via JDBC (FilesystemTelemetryService)
     * instead of HTTP GET to /current-session. The HTTP endpoint returns the HTTP companion's
     * own session scope, never the DT stdio user session. JDBC read is transport-agnostic.
     *
     * OW-3 fix (FS 0.8.82): removed permanent cache. The original cache-forever pattern caused
     * stale session IDs after DT restarts -- all range cache writes/reads used the prior session's
     * ID, so checkRangeCache always missed and real_kh_pct stayed ~15%.
     * New behaviour: always re-read JDBC. If the live active_session differs from the cached value,
     * update the cache and return the fresh ID. JDBC read is sub-millisecond on local SQLite;
     * cost is negligible compared to the HTTP call this replaces.
     * Returns null if telemetryService unavailable or no active session.
     */
    private String resolveSessionId() {
        if (!structurePersistEnabled) return null
        if (telemetryService) {
            String liveSid = telemetryService.readActiveSessionId()
            if (liveSid) {
                if (liveSid != activeSessionId) {
                    // Session changed (DT restart or new context_lifecycle start) -- update cache
                    log.info('ContextServerClient: session_id updated {} -> {}', activeSessionId, liveSid)
                    activeSessionId = liveSid
                }
                return liveSid
            }
            log.debug('ContextServerClient: JDBC session resolve returned null (session not yet active)')
            return null
        }
        // No telemetryService -- HTTP fallback for non-MCPB mode only
        if (!isCsReachable()) return null
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
                        log.debug('ContextServerClient: resolved session_id={} via HTTP fallback', sid)
                    }
                    return sid
                }
            } finally { conn.disconnect() }
        } catch (Exception e) {
            log.debug('ContextServerClient: HTTP fallback failed (expected in MCPB mode): {}', e.message)
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
                // MCPB/stdio fallback: HTTP unreachable, queue via shared SQLite instead
                // Context server drains pending_reindex on every context_lifecycle action=start
                if (telemetryService) {
                    telemetryService.queueReindexAsync(path)
                    log.debug('reindexFile: queued {} via pending_reindex (HTTP unavailable)', path)
                }
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

    // -----------------------------------------------------------------------
    // Fix C (v0.8.50) -- session-scoped range read cache
    // checkRangeCache: sync, 500ms hard timeout -- never blocks FS.
    // recordRangeCacheAsync: fire-and-forget -- never blocks FS.
    // Both silently no-op if CS HTTP is unreachable.
    // -----------------------------------------------------------------------

    String checkRangeCache(String filePath, int startLine, int endLine, String fileHash) {
        if (!isCsReachable() || !fileHash) return null
        String sid = resolveSessionId()
        if (!sid) return null
        try {
            Map<String, Object> body = [
                action       : 'check',
                sessionId    : sid,
                sourceFile   : filePath,
                startLine    : startLine,
                endLine      : endLine,
                knownFileHash: fileHash
            ] as Map<String, Object>
            String json = groovy.json.JsonOutput.toJson(body)
            URL url = new URL("${contextServerUrl}/rangeCache")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            try {
                conn.requestMethod = 'POST'
                conn.doOutput      = true
                conn.connectTimeout = 500
                conn.readTimeout    = 500
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.outputStream.withWriter('UTF-8') { it << json }
                if (conn.responseCode == 200) {
                    String resp = conn.inputStream.getText('UTF-8')
                    Map parsed = (Map) new groovy.json.JsonSlurper().parseText(resp)
                    if (parsed?.get('cached') == true) return (parsed.get('read_at') as String) ?: ''
                }
            } finally {
                conn.disconnect()
            }
        } catch (ConnectException e) {
            onCsConnectFailure()
        } catch (Exception e) {
            log.debug('checkRangeCache failed (non-fatal): {}', e.message)
        }
        return null
    }

    void recordRangeCacheAsync(String filePath, int startLine, int endLine, String contentHash) {
        if (!isCsReachable() || !contentHash) return
        String sid = resolveSessionId()
        if (!sid) return
        asyncWriter.submit({
            try {
                Map<String, Object> body = [
                    action     : 'record',
                    sessionId  : sid,
                    sourceFile : filePath,
                    startLine  : startLine,
                    endLine    : endLine,
                    contentHash: contentHash
                ] as Map<String, Object>
                String json = groovy.json.JsonOutput.toJson(body)
                URL url = new URL("${contextServerUrl}/rangeCache")
                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                try {
                    conn.requestMethod = 'POST'
                    conn.doOutput      = true
                    conn.connectTimeout = 2000
                    conn.readTimeout    = 2000
                    conn.setRequestProperty('Content-Type', 'application/json')
                    conn.outputStream.withWriter('UTF-8') { it << json }
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            } catch (ConnectException ignored) {
                onCsConnectFailure()
            } catch (Exception e) {
                log.debug('recordRangeCacheAsync failed (non-fatal): {}', e.message)
            }
        } as Runnable)
    }

    // -----------------------------------------------------------------------
    // Fix D (v0.8.54): check if a file stem is ontology-indexed.
    // Sync, 500ms hard timeout. Returns true if CS has a node for this stem.
    // Used by FileReadService multi guard.
    // -----------------------------------------------------------------------
    boolean isOntologyIndexed(String fileStem) {
        if (!isCsReachable() || !fileStem) return false
        try {
            Map<String, Object> callBody = [
                jsonrpc: '2.0', method: 'tools/call', id: 1,
                params : [name: 'context_read',
                          arguments: [scope: 'ontology', action: 'locate', query: fileStem]]
            ] as Map<String, Object>
            String json = groovy.json.JsonOutput.toJson(callBody)
            URL url = new URL("${contextServerUrl}/mcp")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            try {
                conn.requestMethod = 'POST'
                conn.doOutput      = true
                conn.connectTimeout = 500
                conn.readTimeout    = 500
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.outputStream.withWriter('UTF-8') { it << json }
                if (conn.responseCode == 200) {
                    String resp = conn.inputStream.getText('UTF-8')
                    Map parsed = (Map) new groovy.json.JsonSlurper().parseText(resp)
                    List content = ((parsed?.get('result') as Map)?.get('content') as List)
                    String text = ((content?.find { (it as Map)?.get('type') == 'text' } as Map)?.get('text')) as String
                    if (text) {
                        Map data = (Map) new groovy.json.JsonSlurper().parseText(text)
                        return data?.get('found') == true
                    }
                }
            } finally { conn.disconnect() }
        } catch (ConnectException e) {
            onCsConnectFailure()
        } catch (Exception e) {
            log.debug('isOntologyIndexed failed (non-fatal): {}', e.message)
        }
        return false
    }

    /**
     * FS 0.9.2: look up class bounds (source_line/end_line) from the ontology index.
     * Makes one call to CS (scope=ontology action=locate query=fileStem) -- same call
     * as isOntologyIndexed but also extracts range fields.
     * Returns [found:true, source_line:N, end_line:N] on hit,
     *         [found:false]                            on clean miss,
     *         null                                     on error/timeout/CS-down.
     * Sync, 500ms hard timeout. Fail-silent. Used by ReadResponseHelper.maybeAddOntologyGuardHint.
     */
    Map<String, Object> getOntologyRange(String fileStem) {
        if (!isCsReachable() || !fileStem) return null
        try {
            Map<String, Object> callBody = [
                jsonrpc: '2.0', method: 'tools/call', id: 1,
                params : [name: 'context_read',
                          arguments: [scope: 'ontology', action: 'locate', query: fileStem]]
            ] as Map<String, Object>
            String json = groovy.json.JsonOutput.toJson(callBody)
            URL url = new URL("${contextServerUrl}/mcp")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            try {
                conn.requestMethod = 'POST'
                conn.doOutput      = true
                conn.connectTimeout = 500
                conn.readTimeout    = 500
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.outputStream.withWriter('UTF-8') { it << json }
                if (conn.responseCode == 200) {
                    String resp = conn.inputStream.getText('UTF-8')
                    Map parsed = (Map) new groovy.json.JsonSlurper().parseText(resp)
                    List content = ((parsed?.get('result') as Map)?.get('content') as List)
                    String text = ((content?.find { (it as Map)?.get('type') == 'text' } as Map)?.get('text')) as String
                    if (text) {
                        Map data = (Map) new groovy.json.JsonSlurper().parseText(text)
                        if (data?.get('found') == true) {
                            return [found: true,
                                    source_line: data.get('source_line') as Integer,
                                    end_line   : data.get('end_line')    as Integer] as Map<String, Object>
                        }
                        return [found: false] as Map<String, Object>
                    }
                }
            } finally { conn.disconnect() }
        } catch (ConnectException e) {
            onCsConnectFailure()
        } catch (Exception e) {
            log.debug('getOntologyRange failed (non-fatal) [{}]: {}', fileStem, e.message)
        }
        return null
    }

    // FS 0.8.69 FIX-6A: look up the known content_hash for a path from CS file_hash_registry,
    // via context_read scope=ontology action=file-hash. Used to enrich BLOCKED_UNRANGED_INDEXED_READ
    // errors so Claude can pass options.knownHash on a retry. Sync, 500ms hard timeout. Fail-silent.
    String getKnownHashForPath(String normalizedPath) {
        if (!isCsReachable() || !normalizedPath) return null
        try {
            Map<String, Object> callBody = [
                jsonrpc: '2.0', method: 'tools/call', id: 1,
                params : [name: 'context_read',
                          arguments: [scope: 'ontology', action: 'file-hash', query: normalizedPath]]
            ] as Map<String, Object>
            String json = groovy.json.JsonOutput.toJson(callBody)
            URL url = new URL("${contextServerUrl}/mcp")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            try {
                conn.requestMethod = 'POST'
                conn.doOutput      = true
                conn.connectTimeout = 500
                conn.readTimeout    = 500
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.outputStream.withWriter('UTF-8') { it << json }
                if (conn.responseCode == 200) {
                    String resp = conn.inputStream.getText('UTF-8')
                    Map parsed = (Map) new groovy.json.JsonSlurper().parseText(resp)
                    List content = ((parsed?.get('result') as Map)?.get('content') as List)
                    String text = ((content?.find { (it as Map)?.get('type') == 'text' } as Map)?.get('text')) as String
                    if (text) {
                        Map data = (Map) new groovy.json.JsonSlurper().parseText(text)
                        if (data?.get('found') == true) {
                            return data.get('content_hash') as String
                        }
                    }
                }
            } finally { conn.disconnect() }
        } catch (ConnectException e) {
            onCsConnectFailure()
        } catch (Exception e) {
            log.debug('getKnownHashForPath failed (non-fatal): {}', e.message)
        }
        return null
    }

    // Fix F (v0.8.54): notify CS that a file has been written and needs reindexing.
    // Fire-and-forget via asyncWriter -- never blocks the write path.
    void invalidateFileAsync(String filePath) {
        if (!isCsReachable() || !filePath) return
        asyncWriter.submit({
            try {
                Map<String, Object> body = [filePath: filePath] as Map<String, Object>
                String json = groovy.json.JsonOutput.toJson(body)
                URL url = new URL("${contextServerUrl}/invalidate")
                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                try {
                    conn.requestMethod = 'POST'
                    conn.doOutput      = true
                    conn.connectTimeout = 2000
                    conn.readTimeout    = 3000
                    conn.setRequestProperty('Content-Type', 'application/json')
                    conn.outputStream.withWriter('UTF-8') { it << json }
                    conn.responseCode
                } finally { conn.disconnect() }
            } catch (ConnectException ignored) {
                onCsConnectFailure()
            } catch (Exception e) {
                log.debug('invalidateFileAsync failed (non-fatal): {}', e.message)
            }
        } as Runnable)
    }


    /**
     * v0.8.70: Load a help_sections content string from CS by section key.
     * Used by FileReadService.init() to load tool_desc_file_read at startup.
     * Returns null (fail-silent) if CS unreachable, section missing, or any error.
     */
    String getHelpSection(String sectionKey) {
        if (!isCsReachable() || !sectionKey) return null
        try {
            Map<String, Object> callBody = [
                jsonrpc: '2.0', method: 'tools/call', id: 1,
                params : [name: 'context_read',
                          arguments: [scope: 'help', topic: sectionKey]]
            ] as Map<String, Object>
            String json = groovy.json.JsonOutput.toJson(callBody)
            URL url = new URL("${contextServerUrl}/mcp")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            try {
                conn.requestMethod = 'POST'
                conn.doOutput      = true
                conn.connectTimeout = 2000
                conn.readTimeout    = 2000
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.outputStream.withWriter('UTF-8') { it << json }
                if (conn.responseCode == 200) {
                    String resp = conn.inputStream.getText('UTF-8')
                    Map parsed = (Map) new groovy.json.JsonSlurper().parseText(resp)
                    List content = ((parsed?.get('result') as Map)?.get('content') as List)
                    String text = ((content?.find { (it as Map)?.get('type') == 'text' } as Map)?.get('text')) as String
                    if (text) {
                        Map data = (Map) new groovy.json.JsonSlurper().parseText(text)
                        // context_read scope=help returns {topic, content, section_key, ...}
                        String sectionContent = data?.get('content') as String
                        if (sectionContent) return sectionContent
                    }
                }
            } finally { conn.disconnect() }
        } catch (ConnectException e) {
            log.debug('getHelpSection: CS unreachable -- using DEFAULT_DESC fallback')
        } catch (Exception e) {
            log.debug('getHelpSection failed (non-fatal): {}', e.message)
        }
        return null
    }

    // -----------------------------------------------------------------------
    // fileHashCache: whole-file hash store/lookup (FIX-KH-AUTO, FS 0.8.77)
    // Delegates to CS /fileHashCache HTTP endpoint -- same inline pattern as
    // checkRangeCache (sync lookup) and recordRangeCacheAsync (fire-and-forget).
    // SCOPE: whole-file doRead() ONLY (Option A, brief s18.3 -- not range/get_method).
    // -----------------------------------------------------------------------

    /** Fire-and-forget store of whole-file hash after a content-returning doRead(). */
    void storeFileHashAsync(String normalizedPath, String hash) {
        if (!isCsReachable() || !normalizedPath || !hash) return
        String sid = resolveSessionId()
        if (!sid) return
        asyncWriter.submit({
            try {
                String json = groovy.json.JsonOutput.toJson([
                    action     : 'store',
                    sessionId  : sid,
                    sourceFile : normalizedPath,
                    contentHash: hash
                ] as Map<String, Object>)
                URL url = new URL("${contextServerUrl}/fileHashCache")
                HttpURLConnection conn = (HttpURLConnection) url.openConnection()
                try {
                    conn.requestMethod = 'POST'
                    conn.doOutput      = true
                    conn.connectTimeout = 2000
                    conn.readTimeout    = 2000
                    conn.setRequestProperty('Content-Type', 'application/json')
                    conn.outputStream.withWriter('UTF-8') { it << json }
                    conn.responseCode  // force send
                } finally {
                    conn.disconnect()
                }
            } catch (ConnectException ignored) {
                onCsConnectFailure()
            } catch (Exception e) {
                log.debug('storeFileHashAsync failed (non-fatal): {}', e.message)
            }
        } as Runnable)
    }

    /**
     * Sync lookup of cached whole-file hash. Returns null on miss, CS down,
     * timeout, malformed response, or missing session. Hard 300ms timeout.
     */
    String lookupFileHash(String normalizedPath) {
        if (!isCsReachable() || !normalizedPath) return null
        String sid = resolveSessionId()
        if (!sid) return null
        try {
            String json = groovy.json.JsonOutput.toJson([
                action    : 'lookup',
                sessionId : sid,
                sourceFile: normalizedPath
            ] as Map<String, Object>)
            URL url = new URL("${contextServerUrl}/fileHashCache")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()
            try {
                conn.requestMethod = 'POST'
                conn.doOutput      = true
                conn.connectTimeout = 300
                conn.readTimeout    = 300
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.outputStream.withWriter('UTF-8') { it << json }
                if (conn.responseCode == 200) {
                    String resp = conn.inputStream.getText('UTF-8')
                    Map parsed = (Map) new groovy.json.JsonSlurper().parseText(resp)
                    if (parsed?.get('found') == true) {
                        onCsSuccess()
                        String h = parsed.get('hash') as String
                        return (h && h ==~ /^[a-fA-F0-9]{12,64}$/) ? h : null
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (ConnectException e) {
            onCsConnectFailure()
        } catch (Exception e) {
            log.debug('lookupFileHash failed (non-fatal): {}', e.message)
        }
        return null
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
