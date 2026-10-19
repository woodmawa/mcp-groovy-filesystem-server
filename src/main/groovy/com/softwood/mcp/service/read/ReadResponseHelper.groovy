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


    /**
     * FS 0.9.8 E-5: feature flag for ONTOLOGY-GATE hard enforcement on indexed file reads.
     * When {@code true} (default), {@code doRead}/{@code doRange}/{@code doGetMethod} on
     * an ontology-indexed {@code .groovy} or {@code .java} file is BLOCKED unless
     * {@code locateCalledThisSession} returns {@code true} or {@code allowNoLocate=true}
     * is passed in options. Set {@code false} to revert to warn-only during rollout.
     */
    @Value('${mcp.filesystem.ontology-gate.enforced:true}')
    boolean ontologyGateEnforced
    // FS 0.9.108 (decision 385): the missing-knownHash advisory and its flag are deleted. The served ledger answers
    // repeats without a caller-supplied hash, so a read without knownHash is not a mistake to report.

    ReadResponseHelper(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // Session token meter (FIX-B)
    // -----------------------------------------------------------------------

    /**
     * Counts this read toward the session's read-token total (telemetry). WP2 (decision 384, 2026-10-19): it no longer
     * writes _session_read_tokens, _session_health or _session_budget_warn into the response -- advice on a success,
     * which the token trend directive already covers. NoAdviceOnSuccessSpec NA-5.
     */
    void injectSessionTokenMeter(Map<String, Object> response, int contentLength) {
        if (telemetryService == null) return
        telemetryService.accumulateReadTokens(contentLength)
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
        // FS 0.9.40 K2: force=true is the documented way back to content -- the auto path honours it.
        boolean forced = Boolean.parseBoolean(String.valueOf(options?.get('force') ?: 'false'))
        if (autoLookup && !forced && autoKhLookupEnabled && contextServerClient != null) {
            String cachedHash = contextServerClient.lookupFileHash(normalized)
            if (cachedHash) {
                String currentHash = structureCache.getHash(normalized)
                if (currentHash == cachedHash) {
                    log.debug('hash-gate HIT (auto): {} unchanged ({})', normalized, cachedHash)
                    return textResponse(requestId, [
                        unchanged        : true,
                        file_content_hash: currentHash,
                        _auto_kh         : true,
                        _note            : 'File unchanged (auto-detected) and already sent to this chat. If you no longer hold it (context compacted, or a subagent made the read), repeat with options.force=true.'
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
        // FS 0.9.40 K2: every read action now asks the served ledger, so while CS is reachable the
        // hint is advice the caller no longer needs -- ~350 chars on every partial read, for nothing.
        boolean autoActive = autoKhHintsSuppressed && contextServerClient != null
        if (autoActive) return

        String fileName = new File(normalized).name
        if (autoStore) {
            response._knownhash_hint = ("CAPTURE: file_content_hash=${hash} for path '${fileName}'. " +
                "Pass as options.knownHash on EVERY subsequent file_read action=read of this file. " +
                "Unchanged file = {unchanged:true} (~15 tokens). Not passing = full content cost again." as String)
        } else {
            response._knownhash_hint = ("file_content_hash=${hash} for '${fileName}': pass as options.knownHash " +
                "on a repeat action=read or get_method (the served ledger is unavailable). Not on action=range." as String)
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

    // FS 0.9.1's ontology-first WARNING (maybeAddOntologyGuardHint) was retired by WP2 (decision 384): it repeated, on a
    // successful read, a decision ONTOLOGY-GATE had already made -- the gate refuses an un-located read, and a read
    // that passed did so by a deliberate allowNoLocate.

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
    /** FS 0.9.43 N11: search hits that stand in for a locate. required=false for bare specs. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    LocateEvidenceRegistry locateEvidenceRegistry

    McpResponse checkOntologyGate(String normalized, Map<String, Object> options,
                                   Object requestId, String action) {
        Map<String, Object> entry = ontologyGateEntry(normalized, options, action)
        return entry == null ? null : textResponse(requestId, entry)
    }

    /**
     * FS 0.9.54 -- record that a grep or multi_grep in this session found matches in these files.
     *
     * <p>N11 (0.9.43) exempted grep from the gate on the grounds that its hits are locate evidence.
     * Only file_search ever recorded them; file_read grep and multi_grep recorded nothing, so a grep
     * that named the file and the line was followed by a refused get_method on that same file --
     * live on 2026-09-22, and WP5d-gate-blocks-after-a-search-hit read 14. Recording lives HERE,
     * beside the check, so both read the session from telemetryService.readActiveSessionId().</p>
     */
    void recordLocateEvidence(Collection<String> normalizedPaths) {
        if (locateEvidenceRegistry == null || !normalizedPaths) return
        try {
            locateEvidenceRegistry.recordHits(telemetryService?.readActiveSessionId(), normalizedPaths)
        } catch (Exception e) {
            log.debug('recordLocateEvidence failed (non-fatal): {}', e.message)
        }
    }

    /**
     * FS 0.9.30 -- the gate DECISION, with the response shape lifted off it.
     *
     * <p>Returns the blocked entry map, or {@code null} to allow. {@code checkOntologyGate} wraps it
     * as a whole-response error for the single-path actions; the multi-path actions
     * ({@code multi}, {@code multi_grep}) collect one entry per path and report them together.</p>
     *
     * <p>Why this exists: {@code multi} used to carry its OWN guard -- Fix D, v0.8.54 -- which asked
     * a different question ({@code isOntologyIndexed(stem)}), returned a different error code, and
     * handed back a bare file STEM as the {@code locate_query}. That stem is precisely the defect
     * CS 1.0.62 shipped to remove: 6 files are named CLAUDE.md and 2,012 symbol names are shared by
     * more than one source_file, so following such a hint resolves to a different file and leaves
     * the caller still blocked. A second guard is a second thing to drift; there is now one
     * decision, and every caller gets the node_id CS resolved.</p>
     */
    Map<String, Object> ontologyGateEntry(String normalized, Map<String, Object> options,
                                           String action) {
        if (!ontologyGateEnforced) return null
        if (isGateExemptProse(normalized)) return null
        if (contextServerClient == null || !contextServerClient.isCsReachable()) return null

        // FS 0.9.26 -- ONE QUESTION, ASKED OF THE SERVER THAT OWNS BOTH FACTS.
        //
        // What was here before was two checks that cancelled each other into silence. The file was
        // resolved by its bare STEM through a fuzzy locate, and the method then returned null
        // whenever the resolved source_file differed from the file being read. In a codebase where
        // nearly every Foo.groovy has a FooSpec.groovy the stem resolves to the SPEC, so that guard
        // -- added correctly, to stop a TempDir stem collision producing a spurious block -- turned
        // the gate off for most of the tree. The second check, locateCalledThisSession, read an
        // in-process Set whose only writer had NO CALLERS anywhere in the repository, so it could
        // only ever answer false. Repair the path lookup alone and every read blocks; repair the
        // recorder alone and nothing changes. Measured before the repair: ONE block since
        // 2026-05-29, against 2,269 reads in 30 days and 21 sessions with zero locate calls.
        //
        // The extension filter has gone with them. Whether a file is gated is now decided by
        // whether the ONTOLOGY has it, which is the actual question -- .md, .adoc and anything else
        // an indexer covers is gated on the same terms, and anything unindexed passes.
        String claimedSession = null
        try {
            claimedSession = telemetryService?.readActiveSessionId()
        } catch (Exception ignored) { }

        Map<String, Object> gate
        try {
            gate = contextServerClient.ontologyGateCheck(normalized, claimedSession)
        } catch (Exception e) {
            log.debug('ontology-gate check failed (fail-open) [{}]: {}', normalized, e.message)
            return null
        }
        // null = CS unreachable or errored. allow != false covers not-indexed, no-session and
        // locate-called. Every uncertain path allows: a gate that cannot reach its evidence must
        // not stop work, and it says so in the reason field rather than silently.
        if (gate == null) return null
        if (gate.get('allow') != false) return null

        // FS 0.9.43 N11: a search that already named this file AND its line is locate evidence.
        // The gate asks "do you know where in this file to read?" -- a file_search or grep hit
        // answers it. Refusing anyway sent the caller to locate for a fact they had in hand, twice
        // in one live session on 2026-09-17.
        if (locateEvidenceRegistry?.isSatisfied(claimedSession, normalized)) {
            log.debug('ontology-gate satisfied by a prior search hit [{}]', normalized)
            return null
        }

        String fname = new File(normalized).name
        int dotAt = fname.lastIndexOf((int) 46)
        String fileStem = dotAt > 0 ? fname.substring(0, dotAt) : fname

        // CS returns a query that resolves to THIS path, and FS passes it through untouched --
        // deliberately, because the ontology is CS's and a second opinion here would be a second
        // copy to drift.
        //
        // What that query IS has been wrong twice, each time one layer less obviously. It guessed
        // the file STEM, which for every file with a sibling spec resolved to the spec. CS 1.0.56
        // made it the symbol_name that actually belongs to this path. CS 1.0.62 made it the
        // node_id, because even the correct symbol name is not unique across files: 6 files named
        // CLAUDE.md, 2,012 names shared by more than one source_file, and a session that hit the
        // dead end six times in a row following the hint exactly.
        //
        // The fileStem fallback is for a CS too old to send one. It is the OLD defect, kept only
        // so a version skew degrades rather than breaks -- do not read it as still acceptable.
        String locateQuery = (gate.get('locate_query') as String) ?: fileStem

        // allowNoLocate=true override -> allow, but count it, so overrides stay visible
        boolean override = options?.get('allowNoLocate') as boolean
        if (override) {
            contextServerClient.incrementOntologyGateBlockedToken(fileStem)
            return null
        }

        // Block: write observation async, return error response
        contextServerClient.writeOntologyGateObservationAsync(fileStem, action)

        String hint = "Call context_read scope=ontology action=locate query=${locateQuery} BEFORE file_read to allow this read. " +
                      "locate returns source_line+end_line in <100 tokens. " +
                      "Pass options.allowNoLocate=true to override the block (telemetry still incremented)."
        Map<String, Object> errorMap = [
            error       : 'BLOCKED_ONTOLOGY_GATE',
            locate_query: locateQuery,
            action      : action,
            file        : normalized,
            hint        : hint
        ] as Map<String, Object>
        return errorMap
    }

    /**
     * R4 (FS 0.9.105, decision 381; WS9 slice): arc briefs and CHANGELOGs are not gated. The gate refused them six
     * times in a week and every refusal was answered by an override or a locate of the same lines -- a retry tax that
     * taught the override reflex. Exactly these two prose kinds, nothing else: CLAUDE.md, asciidoc chapters and all
     * code stay gated (ProseGateExemptionSpec PG-3/4).
     */
    static boolean isGateExemptProse(String path) {
        if (!path) return false
        String p = path.replace('\\', '/').toLowerCase()
        boolean prose = p.endsWith('.md') || p.endsWith('.adoc')
        return prose && (p.contains('/claude-sync/briefs/') || p.endsWith('/changelog.md'))
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