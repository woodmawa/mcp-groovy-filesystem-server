package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.ChunkBufferService
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.StructureCache
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * ReadResponseHelper - shared utilities for read sub-services.
 *
 * <p>Provides: hash-gated re-read (explicit + server-side auto-lookup), session token
 * metering, response size warnings, chunk_read / finalise_read delegation, and the
 * line-count guard helper.</p>
 *
 * <h3>FIX-KH-AUTO (v0.8.77+) — Server-Side Automatic knownHash</h3>
 * <p>After every content-returning {@code doRead()} (whole-file), FS asynchronously
 * stores the file hash in the CS session cache via
 * {@code ContextServerClient.storeFileHashAsync()}. On subsequent {@code doRead()}
 * calls without an explicit {@code options.knownHash}, FS auto-looks up the cached
 * hash and returns {@code {unchanged:true, _auto_kh:true}} if the file is unchanged.
 * This eliminates the caller discipline requirement that kept knownHash compliance
 * near 2% across 14 days of telemetry.</p>
 *
 * <p>Auto-lookup is restricted to whole-file {@code doRead()} only. Partial-read
 * actions ({@code doRange}, {@code doHead}, {@code doTail}, {@code doGetMethod})
 * do NOT use auto-lookup — returning {@code unchanged:true} for a range the caller
 * has not seen is a correctness bug. See brief §18.3 Option A.</p>
 *
 * <p>Feature flags (application.properties):</p>
 * <ul>
 *   <li>{@code mcp.filesystem.auto-kh-lookup.enabled} (default true) — master switch</li>
 *   <li>{@code mcp.filesystem.auto-kh-hints-suppressed.enabled} (default true) —
 *       suppress {@code _knownhash_hint} when auto is active</li>
 * </ul>
 *
 * v0.7.44 - extracted from FileReadService as part of read/ subpackage split.
 * v0.8.77 - FIX-KH-AUTO: auto-lookup, storeAndHintKnownHash, autoKhLookupEnabled flag.
 * v0.8.78 - FIX-KH-AUTO hardening: autoKhHintsSuppressed flag.
 * v0.9.0  - PR 3.1: storeAndHintKnownHash now emits conditional hint text. For whole-file
 *           reads (autoStore=true) the hint instructs callers to pass knownHash on action=read.
 *           For partial reads (autoStore=false: range, get_method) the hint explicitly warns
 *           NOT to pass knownHash to action=range (returns unchanged:true instead of content).
 *           The session range cache handles range deduplication automatically.
 */
@Service
@Slf4j
@CompileStatic
class ReadResponseHelper extends AbstractFileService {

    @Autowired
    StructureCache structureCache

    @Autowired(required = false)
    FilesystemTelemetryService telemetryService

    /** Injected only when the CS HTTP companion is reachable (optional). */
    @Autowired(required = false)
    ContextServerClient contextServerClient

    @Autowired
    ChunkBufferService chunkBufferService

    @Value('${mcp.filesystem.large-response-warn-chars:15000}')
    int largeResponseWarnChars

    @Value('${mcp.filesystem.partial-read-cap-chars:12000}')
    int partialReadCapChars

    /** Feature flag: disable auto-lookup quickly without redeploying if issues observed. */
    @Value('${mcp.filesystem.auto-kh-lookup.enabled:true}')
    boolean autoKhLookupEnabled

    /**
     * FIX-6 (FS 0.8.80): shadow mode for range/get_method auto-KH.
     * When true, range and get_method reads perform a CS hash lookup after read completion.
     * If the cached hash matches the current disk hash, logs a 'shadow HIT' and annotates
     * the response with _shadow_kh:true -- but does NOT return unchanged:true.
     * This lets us validate accuracy and measure coverage without changing read semantics.
     * Flip to false to disable shadow probing without redeploying.
     * Graduate to active mode (return unchanged:true) once accuracy is confirmed.
     */
    @Value('${mcp.filesystem.auto-kh-shadow.enabled:true}')
    boolean autoKhShadowEnabled

    /**
     * Feature flag: suppress _knownhash_hint when auto-lookup is active and CS is reachable.
     * Set false to re-enable hints for diagnostic purposes.
     * Defaults true (hints suppressed when auto handles the next read automatically).
     */
    @Value('${mcp.filesystem.auto-kh-hints-suppressed.enabled:true}')
    boolean autoKhHintsSuppressed

    ReadResponseHelper(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // Size warning
    // -----------------------------------------------------------------------

    void maybeAddSizeWarning(Map<String, Object> response, int contentLength) {
        if (contentLength > largeResponseWarnChars) {
            long kb     = Math.round(contentLength / 1024.0f)
            long tokens = Math.round(contentLength / 4.0f)
            response._sizeWarning = ("NOTE: response is ${kb}KB (~${tokens} tokens). " +
                "Consider head/range/grep for targeted reads to preserve context window." as String)
        }
    }

    // -----------------------------------------------------------------------
    // Session token meter (FIX-B)
    // -----------------------------------------------------------------------

    void injectSessionTokenMeter(Map<String, Object> response, int contentLength) {
        if (telemetryService == null) return
        int sessionTokens = telemetryService.accumulateReadTokens(contentLength)
        int sessionCalls  = telemetryService.getSessionReadCalls()
        response._session_read_tokens = sessionTokens
        // Inject ratio health when degraded (silent on OK/UNKNOWN - avoids noise)
        Map<String, Object> health = telemetryService.getSessionHealthSummary()
        String healthStatus = health.healthStatus as String
        if (healthStatus == 'DEGRADED' || healthStatus == 'POOR') {
            response._session_health = ("${healthStatus}: file_read/ctx ratio=${health.fileToContextRatio} today" +
                " — run context_lifecycle start + context_read resume to improve" as String)
        }
        if (sessionTokens > 80000) {
            response._session_budget_warn = ("CRITICAL: ${sessionTokens} tokens burned on file reads this session (${sessionCalls} calls). " +
                "Context window at serious risk. STOP reading files - use only structure/get_method/grep from now on." as String)
        } else if (sessionTokens > 40000) {
            response._session_budget_warn = ("WARNING: ${sessionTokens} tokens burned on file reads this session (${sessionCalls} calls). " +
                "Switch to structure/get_method/range to avoid context overflow." as String)
        }
    }

    // -----------------------------------------------------------------------
    // Hash-gated re-read (FIX-D + FIX-KH-AUTO)
    //
    // autoLookup=true: ONLY pass from doRead() (whole-file actions).
    // NEVER pass autoLookup=true from doRange/doHead/doTail/doGetMethod --
    // those actions serve a subset of file content and returning unchanged:true
    // when the caller hasn't seen the requested portion is a correctness bug.
    // See brief §18.3 Option A.
    // -----------------------------------------------------------------------

    /**
     * Overload for callers that never use auto-lookup (range, head, tail, get_method).
     * Preserves backward compatibility -- all existing call sites work unchanged.
     */
    McpResponse checkKnownHash(String normalized, Map<String, Object> options, Object requestId) {
        return checkKnownHash(normalized, options, requestId, false)
    }

    /**
     * Full form: pass autoLookup=true only from whole-file read actions (doRead).
     *
     * Path 1 (explicit): caller passed options.knownHash -- compare and return.
     * Path 2 (auto):     no hash passed; autoLookup=true; CS lookup available.
     *                    Compares cached hash against current disk hash.
     *                    Fails open on any CS error.
     */
    McpResponse checkKnownHash(String normalized, Map<String, Object> options,
                                Object requestId, boolean autoLookup) {
        String knownHash = options.knownHash as String

        // Path 1: explicit hash passed by caller (unchanged from pre-0.8.77 behaviour)
        if (knownHash) {
            String currentHash = structureCache.getHash(normalized)
            if (currentHash == knownHash) {
                log.debug('hash-gate HIT (explicit): {} unchanged ({})', normalized, knownHash)
                return textResponse(requestId, [
                    unchanged        : true,
                    file_content_hash: currentHash,
                    _note            : 'File unchanged since last read - reuse content from previous response.'
                ] as Map<String, Object>)
            }
            log.debug('hash-gate MISS (explicit): {} changed (known={}, current={})',
                normalized, knownHash, currentHash)
            return null
        }

        // Path 2: auto-lookup -- whole-file reads only, when feature flag is on
        if (autoLookup && autoKhLookupEnabled && contextServerClient != null) {
            String cachedHash = contextServerClient.lookupFileHash(normalized)
            if (cachedHash) {
                String currentHash = structureCache.getHash(normalized)
                if (currentHash == cachedHash) {
                    log.debug('hash-gate HIT (auto): {} unchanged ({})', normalized, cachedHash)
                    return textResponse(requestId, [
                        unchanged        : true,
                        file_content_hash: currentHash,
                        _auto_kh         : true,
                        _note            : 'File unchanged (auto-detected from session hash cache).'
                    ] as Map<String, Object>)
                }
                log.debug('hash-gate AUTO-MISS: {} changed (cached={}, current={})',
                    normalized, cachedHash, currentHash)
                // File changed -- store new hash (done by storeAndHintKnownHash after content is built)
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // storeAndHintKnownHash (renamed from injectKnownHashHint, FIX-KH-AUTO)
    //
    // 1. Stores the hash asynchronously to CS (whole-file reads only, guarded by autoStore param).
    // 2. Injects _knownhash_hint into response when options.knownHash was NOT passed.
    //
    // Call sites that serve partial content (range/head/tail) pass autoStore=false.
    // doRead() passes autoStore=true.
    // Backward-compat overload with autoStore=false keeps range/get_method unchanged.
    // -----------------------------------------------------------------------

    /** Backward-compat overload: no auto-store (used by range, head, tail, get_method). */
    void storeAndHintKnownHash(Map<String, Object> response, String normalized,
                                Map<String, Object> options) {
        storeAndHintKnownHash(response, normalized, options, false)
    }

    /**
     * Full form: pass autoStore=true from doRead() (whole-file reads).
     * autoStore=false for partial reads (range, head, tail, get_method).
     */
    void storeAndHintKnownHash(Map<String, Object> response, String normalized,
                                Map<String, Object> options, boolean autoStore) {
        String hash = response.file_content_hash as String
        if (!hash) return

        // Store async -- whole-file reads only
        if (autoStore && autoKhLookupEnabled && contextServerClient != null) {
            contextServerClient.storeFileHashAsync(normalized, hash)
        }

        // Suppress hint when auto-lookup is active and CS is reachable:
        // the next read will auto-hit without the caller needing to track the hash.
        // Re-enable with mcp.filesystem.auto-kh-hints-suppressed.enabled=false for diagnostics.
        if (options?.knownHash) return   // hash already passed -- no hint needed regardless
        // FIX-KH-RANGE-AUTO (FS 0.8.81): only suppress hint for whole-file reads (autoStore=true).
        // For range/get_method (autoStore=false), auto-lookup does NOT fire, so Claude still
        // needs the hint to know the hash for future explicit knownHash use.
        boolean autoActive = autoKhLookupEnabled && autoKhHintsSuppressed && contextServerClient != null && autoStore
        if (autoActive) return           // whole-file auto will handle next read -- hint is token noise

        String fileName = new File(normalized).name
        if (autoStore) {
            response._knownhash_hint = ("CAPTURE: file_content_hash=${hash} for path '${fileName}'. " +
                "Pass as options.knownHash on EVERY subsequent file_read action=read of this file. " +
                "Unchanged file = {unchanged:true} (~15 tokens). Not passing = full content cost again." as String)
        } else {
            response._knownhash_hint = ("CAPTURE: file_content_hash=${hash} for path '${fileName}'. " +
                "Use as options.knownHash for action=read (whole-file) or action=get_method repeat checks. " +
                "Do NOT pass knownHash to action=range -- it returns unchanged:true instead of content. " +
                "Repeated range reads are handled by the session range cache automatically." as String)
        }
    }

    /**
     * FIX-6 (FS 0.8.80): shadow auto-KH probe for range/get_method reads.
     *
     * Called AFTER the read response is built. Performs a CS hash lookup;
     * if the cached hash matches the current file hash, annotates the response
     * with _shadow_kh:true and logs a shadow HIT for telemetry validation.
     * Does NOT return unchanged:true -- read semantics are unchanged.
     * Also stores the current file hash so future whole-file reads can auto-hit.
     *
     * Graduate to active mode (return unchanged) once shadow accuracy >= 99%
     * over 3+ sessions.
     *
     * @param response  the already-built response map (mutated in place)
     * @param normalized  normalised file path
     * @param action  'range' or 'get_method' (for log labelling)
     */
    void shadowAutoKhProbe(Map<String, Object> response, String normalized, String action) {
        if (!autoKhShadowEnabled) return
        if (!autoKhLookupEnabled) return
        if (contextServerClient == null) return
        if (!contextServerClient.isCsReachable()) return
        if (response.unchanged == true) return   // already an explicit hit -- no shadow needed
        // if caller passed explicit knownHash it was already evaluated -- skip shadow
        if (response.containsKey('_shadow_kh')) return   // already probed

        try {
            String cachedHash  = contextServerClient.lookupFileHash(normalized)
            if (!cachedHash) {
                log.debug('shadow-kh MISS (no cached hash) [{} {}]', action, new File(normalized).name)
                return
            }
            String currentHash = response.file_content_hash as String ?: structureCache.getHash(normalized)
            if (!currentHash) return

            if (currentHash == cachedHash) {
                log.debug('shadow-kh HIT [{} {}] hash={}', action, new File(normalized).name, cachedHash.take(12))
                response._shadow_kh = true
                response._shadow_kh_action = action
            } else {
                log.debug('shadow-kh STALE [{} {}] cached={} current={}',
                    action, new File(normalized).name, cachedHash.take(12), currentHash?.take(12))
                response._shadow_kh = false
                response._shadow_kh_action = action
                // Update stale cache entry with current hash
                contextServerClient.storeFileHashAsync(normalized, currentHash)
            }
        } catch (Exception e) {
            log.debug('shadow-kh probe failed [{} {}]: {}', action, new File(normalized).name, e.message)
        }
    }

    // -----------------------------------------------------------------------
    // Chunk read actions
    // -----------------------------------------------------------------------

    McpResponse doChunkRead(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        int chunkIndex   = (options.chunkIndex as Integer) ?: 0
        if (!sessionId) return McpResponse.toolError(requestId, 'options.sessionId required for chunk_read')

        String chunk = chunkBufferService.getReadChunk(sessionId, chunkIndex)

        // FIX-C1: cap individual chunk responses to partialReadCapChars
        boolean chunkTruncated = chunk != null && chunk.length() > partialReadCapChars
        if (chunkTruncated) chunk = chunk.substring(0, partialReadCapChars)

        Map<String, Object> resp = [action: 'chunk_read', sessionId: sessionId, chunkIndex: chunkIndex, content: chunk]
        if (chunkTruncated) {
            resp._truncated = true
            resp._truncatedNote = ("Chunk truncated at ${partialReadCapChars} chars (~${partialReadCapChars / 4000 as int}K tokens). " +
                "Increase specificity with head/range/grep rather than chunk_read for large files." as String)
        }
        return textResponse(requestId, resp)
    }

    McpResponse doFinaliseRead(Map<String, Object> options, Object requestId) {
        String sessionId = options.sessionId as String
        if (!sessionId) return McpResponse.toolError(requestId, 'options.sessionId required for finalise_read')
        chunkBufferService.finaliseRead(sessionId)
        return textResponse(requestId, [action: 'finalise_read', sessionId: sessionId, success: true])
    }

    // -----------------------------------------------------------------------
    // Line-count guard helper (FIX-A)
    // -----------------------------------------------------------------------

    static int countLinesUpTo(String normalizedPath, int limit, String encoding) {
        int count = 0
        new File(normalizedPath).withReader(encoding) { Reader r ->
            BufferedReader br = new BufferedReader(r)
            while (count <= limit && br.readLine() != null) count++
        }
        return count
    }
}
