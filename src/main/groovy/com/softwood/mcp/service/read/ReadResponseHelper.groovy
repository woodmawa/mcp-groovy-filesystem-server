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

    /** FS 0.9.1: feature flag for ontology-first guard on whole-file source reads (warn-only path). */
    @Value('${mcp.filesystem.ontology-guard.enabled:true}')
    boolean ontologyGuardEnabled

    /**
     * FS 0.9.8 E-5: feature flag for ONTOLOGY-GATE hard enforcement on indexed file reads.
     * When {@code true} (default), {@code doRead}/{@code doRange}/{@code doGetMethod} on
     * an ontology-indexed {@code .groovy} or {@code .java} file is BLOCKED unless
     * {@code locateCalledThisSession} returns {@code true} or {@code allowNoLocate=true}
     * is passed in options. Set {@code false} to revert to warn-only during rollout.
     */
    @Value('${mcp.filesystem.ontology-gate.enforced:true}')
    boolean ontologyGateEnforced
    /**
     * FS 0.9.9: feature flag for missing-knownHash advisory detection on whole-file reads.
     * When {@code true} (default), {@code doRead} and {@code doGetMethod} inject
     * {@code _missing_knownhash} into the response and fire a correction observation
     * when the caller omitted {@code options.knownHash} but {@link StructureCache}
     * already holds a hash for the file. Advisory only -- does NOT block the read.
     */
    @Value('${mcp.filesystem.missing-kh-warn.enabled:true}')
    boolean missingKhWarnEnabled

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
    // FS 0.9.1: Ontology-first guard
    // -----------------------------------------------------------------------

    /**
     * FS 0.9.1: Ontology-first guard for whole-file reads.
     * If the target file is a .groovy/.java source file that is indexed in the ontology,
     * injects _ontology_guard_warn to remind Claude to call scope=ontology action=locate
     * before performing expensive whole-file reads. Non-blocking: always returns normally.
     *
     * Controlled by: mcp.filesystem.ontology-guard.enabled (default true).
     * Requires CS reachable; fails silently if CS is down or isOntologyIndexed throws.
     */
    void maybeAddOntologyGuardHint(Map<String, Object> response, String normalized) {
        if (!ontologyGuardEnabled) return
        if (contextServerClient == null || !contextServerClient.isCsReachable()) return
        if (!(normalized?.endsWith('.groovy') || normalized?.endsWith('.java'))) return

        String fileStem = new File(normalized).name.replaceFirst(/\.[^.]+$/, '')
        try {
            // FS 0.9.2: single locate call returns both found flag and source_line/end_line bounds.
            Map<String, Object> range = contextServerClient.getOntologyRange(fileStem)
            if (range?.get('found') == true) {
                response._ontology_guard_warn = (
                    "ONTOLOGY-FIRST: '${fileStem}' is indexed. " +
                    "Call context_read scope=ontology action=locate query=${fileStem} BEFORE whole-file reads. " +
                    'locate returns source_line+end_line in <100 tokens -- use range instead.' as String
                )
                Integer sl = range.get('source_line') as Integer
                Integer el = range.get('end_line') as Integer
                if (sl != null && el != null) {
                    int maxLines = el - sl + 1
                    response._ontology_guard_hint = (
                        "Call range startLine=${sl} maxLines=${maxLines} instead (source: ontology index)" as String
                    )
                }
            }
        } catch (Exception e) {
            log.debug('ontology-guard probe failed (non-fatal) [{}]: {}', fileStem, e.message)
        }
    }

    // -----------------------------------------------------------------------
    // E-5: ONTOLOGY-GATE hard enforcement (FS 0.9.8)
    // -----------------------------------------------------------------------

    /**
     * Gate check for ontology-indexed file reads. Called from
     * {@link com.softwood.mcp.service.read.FileContentReader} at the top of
     * {@code doRead}, {@code doRange}, and {@code doGetMethod} before any content is read.
     *
     * <h3>Decision matrix</h3>
     * <ul>
     *   <li>Gate disabled ({@code ontologyGateEnforced=false}) → {@code null} (proceed)</li>
     *   <li>CS unreachable → {@code null} (fail-open, proceed)</li>
     *   <li>File not indexed → {@code null} (proceed)</li>
     *   <li>Locate was called this session → {@code null} (proceed)</li>
     *   <li>{@code options.allowNoLocate=true} → {@code null} (proceed, but telemetry incremented)</li>
     *   <li>Otherwise → returns a {@code BLOCKED_ONTOLOGY_GATE} error {@link McpResponse}</li>
     * </ul>
     *
     * @param normalized  normalized absolute file path
     * @param options     tool call options map (checks {@code allowNoLocate})
     * @param requestId   MCP request ID for error response construction
     * @param action      the file_read action name ("read", "range", "get_method")
     * @return {@code null} to proceed, or a blocking {@link McpResponse} to return immediately
     */
    McpResponse checkOntologyGate(String normalized, Map<String, Object> options,
                                   Object requestId, String action) {
        if (!ontologyGateEnforced) return null
        if (contextServerClient == null || !contextServerClient.isCsReachable()) return null
        if (!(normalized?.endsWith('.groovy') || normalized?.endsWith('.java'))) return null

        String fileStem = new File(normalized).name.replaceFirst(/\.[^.]+$/, '')

        // Use getOntologyRange (reuses existing locate call) and path-verify the result.
        // CRITICAL: check that CS's source_file for this stem matches our normalized path.
        // Without the path check, a stem like 'ct30' from a TempDir test file can match
        // a residual ontology entry from a prior test run, causing a spurious gate block.
        // The gate only applies when THIS EXACT FILE is indexed in the source ontology.
        Map<String, Object> range
        try {
            range = contextServerClient.getOntologyRange(fileStem)
        } catch (Exception e) {
            log.debug('ontology-gate range check failed (fail-open) [{}]: {}', fileStem, e.message)
            return null  // fail-open
        }
        if (range == null || range.get('found') != true) return null

        // Path-scope guard: only block when CS's indexed path matches this file.
        // Normalise both to forward slashes, lowercase for comparison.
        String csSourceFile = (range.get('source_file') as String)?.replace('\\', '/') ?: ''
        String normFwd      = normalized.replace('\\', '/')
        if (!csSourceFile.equalsIgnoreCase(normFwd)) return null

        // Locate was called this session → allow
        if (contextServerClient.locateCalledThisSession(fileStem)) return null

        // allowNoLocate=true override → allow but increment telemetry
        boolean override = options?.get('allowNoLocate') as boolean
        if (override) {
            contextServerClient.incrementOntologyGateBlockedToken(fileStem)
            return null
        }

        // Block: write observation async, return error response
        contextServerClient.writeOntologyGateObservationAsync(fileStem, action)

        String hint = "Call context_read scope=ontology action=locate query=${fileStem} BEFORE file_read to allow this read. " +
                      "locate returns source_line+end_line in <100 tokens. " +
                      "Pass options.allowNoLocate=true to override the block (telemetry still incremented)."
        Map<String, Object> errorMap = [
            error       : 'BLOCKED_ONTOLOGY_GATE',
            locate_query: fileStem,
            action      : action,
            file        : normalized,
            hint        : hint
        ] as Map<String, Object>
        return textResponse(requestId, errorMap)
    }

    // -----------------------------------------------------------------------
    // FS 0.9.9: missing-knownHash advisory detection
    // -----------------------------------------------------------------------

    /**
     * Advisory check for missing {@code options.knownHash} on whole-file reads.
     * Called from {@link FileContentReader#doRead} and {@link FileContentReader#doGetMethod}
     * AFTER content has been successfully read and the response map assembled.
     *
     * <p>If {@link StructureCache} already has a hash for this file and the caller did NOT
     * supply {@code options.knownHash}, injects {@code _missing_knownhash} into the response,
     * fires a correction observation async, and increments the session counter.
     * Does NOT modify the read result -- purely additive.</p>
     *
     * <p>Skipped when: flag disabled; no CS client; file not in cache; knownHash was supplied;
     * or this is the first time the file has been seen (nothing to cache-hit against).</p>
     *
     * @param response   the assembled response map to enrich (mutated in place)
     * @param normalized normalized absolute file path
     * @param options    tool call options map (checks {@code knownHash})
     * @param action         the file_read action ("read" or "get_method")
     * @param preCachedHash  hash from {@link #peekStructureCache} captured BEFORE the read
     *                       (null means file was not in cache at call entry -- no violation)
     */
    void maybeWarnMissingKnownHash(Map<String, Object> response, String normalized,
                                    Map<String, Object> options, String action,
                                    String preCachedHash) {
        if (!missingKhWarnEnabled) return
        if (options?.containsKey('knownHash')) return           // caller passed it -- no violation
        if (!preCachedHash) return                              // file was not in cache at call entry

        // Violation: file was already known to the cache but knownHash was omitted.
        response.put('_missing_knownhash',
            ("KNOWNHASH DISCIPLINE: pass options.knownHash='${preCachedHash}' on this read " +
             "to get {unchanged:true} (~15 tok) instead of full content. " +
             "Source: StructureCache (session-local). Practice #497." as String))

        // Fire correction observation async (fail-open -- CS may be down)
        if (contextServerClient != null && contextServerClient.isCsReachable()) {
            String stem = new File(normalized).name.replaceFirst(/\.[^.]+$/, '')
            contextServerClient.writeMissingKnownHashObservationAsync(stem, action)
        }

        // Increment session counter for telemetry
        if (telemetryService != null) telemetryService.incrementMissingKhCount()
    }

    /**
     * Convenience wrapper: peek at the StructureCache for this path WITHOUT computing
     * a hash from disk. Returns the cached hash if the file was already seen this session
     * and the entry is still valid, or {@code null} otherwise.
     * Call BEFORE the read so the check reflects pre-read cache state.
     *
     * @param normalized  normalised absolute path
     * @return cached hash or null
     */
    String peekStructureCache(String normalized) {
        if (structureCache == null || !normalized) return null
        return structureCache.peekHash(pathService.normalizePath(normalized))
    }
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
