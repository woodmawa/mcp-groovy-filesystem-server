package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.read.FileContentReader
import com.softwood.mcp.service.read.FileMetaReader
import com.softwood.mcp.service.read.FileStructureReader
import com.softwood.mcp.service.read.ReadResponseHelper
import com.softwood.mcp.service.StructureCache
import com.woodmawa.mcp.toon.ToonEncoder
import com.woodmawa.mcp.toon.ToonOptions
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service

/**
 * FileReadService - ToolHandler entry point for the file_read tool.
 *
 * Thin dispatcher: owns getToolDefinitions/canHandle/handleToolCall only.
 * All action implementations live in the service/read/ subpackage:
 *   - FileContentReader  : read, head, tail, range, grep, multi_grep, get_method
 *   - FileStructureReader: structure
 *   - FileMetaReader     : info, summary, exists, project_root, allowed_dirs, normalize, diff, checksum
 *   - ReadResponseHelper : chunk_read, finalise_read, hash-gate, token meter, _knownhash_hint
 *
 * <h3>knownHash routing rules (FS 0.9.0)</h3>
 * <ul>
 *   <li>action=read (whole-file): pass options.knownHash from prior file_content_hash.
 *       Returns {unchanged:true} with ZERO token cost if file is unchanged.</li>
 *   <li>action=get_method: same as read -- knownHash supported, returns unchanged:true.</li>
 *   <li>action=range: do NOT pass options.knownHash. Range deduplication is handled by
 *       the CS session range cache (recordRangeCacheAsync, fired unconditionally here
 *       even on unchanged:true responses). Passing knownHash to range suppresses content.</li>
 *   <li>action=list: listing_hash supported, returns unchanged:true when directory is unmodified.</li>
 * </ul>
 *
 * v0.7.44 - refactored to dispatch-only; implementations split to read/ subpackage.
 * v0.9.0  - DEFAULT_DESC and tool param description updated to correct knownHash routing.
 *           range dispatch comment confirms recordRangeCacheAsync fires on unchanged:true.
 */
@Service
@Slf4j
@CompileStatic
class FileReadService extends AbstractFileService implements ToolHandler {

    @Autowired FileContentReader   contentReader
    @Autowired FileStructureReader  structureReader
    @Autowired FileMetaReader       metaReader
    @Autowired ReadResponseHelper   responseHelper
    @Autowired(required = false) ContextServerClient  contextServerClient
    // FIX-KH-RANGE-AUTO (FS 0.8.81): in-memory cache of current file hashes.
    // required=false so unit/integration tests without a live StructureCache bean can run.
    @Autowired(required = false) StructureCache structureCache

    /** Setter for test injection without ReflectionTestUtils (field is @CompileStatic). */
    void setContextServerClient(ContextServerClient c) { this.contextServerClient = c }
    /** Setter for test injection of a stub StructureCache. */
    void setStructureCache(StructureCache s)           { this.structureCache = s }
    @Autowired com.softwood.mcp.service.office.OfficeDocumentHandler officeHandler

    // v0.8.70: DB-driven tool description. Loaded from CS help_sections at startup.
    // Falls back to DEFAULT_DESC if CS is unreachable on first boot.
    private String toolDescription

    // FS 0.9.33 W14: the KNOWNHASH OBLIGATION block that opened this description is gone.
    // It declared the metric "tracked per session and FAILING if <30%" and rode on every FS tool
    // definition in every session. Measured 2026-09-11 over 30 days: 66 eligible repeat reads
    // across 34 sessions out of 2,406 file_read calls in 161 sessions -- 2.7% of reads are even
    // ELIGIBLE, and 79% of sessions produce no measurement at all. knownhash_pct is NULL on 125
    // of 159 sessions because NULL is the correct answer: eligibleReads counts repeat reads only,
    // after two deliberate narrowings (0.54.7 RC-8, 0.93.0 WP-D2), and W13 was STRUCK on the
    // finding that this narrowing is right. CS 1.0.2 RC-C had already written "knownhash_pct
    // cannot serve" in a comment and chosen orientation_tok instead.
    //
    // So the text mandated a threshold on a number that is absent by design -- this arc's shape
    // in its third face: not an unreachable guard, but an unmeasurable obligation, repeated to
    // every model on every connect. What remains says what knownHash DOES and how to get one.
    // The narrow signal survives untouched: ContextServerClient.writeMissingKnownHashObservationAsync
    // still fires when a cached file is re-read without a hash, because THAT case is real,
    // reachable, and specific.
    // DEFAULT_DESC kept in sync with tool_desc_file_read section_key in help_sections.
    // Update via: context_lifecycle execute_sql UPDATE help_sections SET content=? WHERE section_key='tool_desc_file_read'
    private static final String DEFAULT_DESC = '''\
Read files/directories.
Actions: read|head|tail|range|grep|multi_grep|multi|info|summary|stat|exists|project_root|allowed_dirs|normalize|diff|checksum|list|structure|get_method|chunk_read|finalise_read|help

REPEAT READS ARE DE-DUPLICATED FOR YOU. Every content action (read, head, tail, range, grep, multi,
multi_grep, structure, get_method) checks what this chat was already sent: same file content, within
the last 45 minutes. Held content answers {unchanged:true}; a partly-held range sends only the new
lines and lists the rest in already_served. An edited file is always sent again. If you no longer
hold the content (context compacted, or a subagent made the read), repeat with options.force=true.

knownHash saves re-sending content you already hold, without the ledger: pass a file_content_hash
as options.knownHash on action=read or action=get_method. Optional -- nothing needs tracking.
Do NOT pass options.knownHash to action=range -- it returns {unchanged:true} instead of content.

Key params: path (absolute), options.lines (head/tail), options.startLine+maxLines (range), options.pattern+contextLines (grep), options.method (get_method), options.knownHash (read|get_method|list ONLY -- NOT range), options.force (re-send content this chat already holds; override >200-line refusal), options.compact (minimal response), options.className (structure filter).
action=list returns listing_hash. Pass as options.knownHash to get {unchanged:true} (~15 tokens) when directory is unmodified.
action=multi_grep: grep one pattern across options.paths[] in one call - returns only files with matches.
All read actions return file_content_hash. MANDATORY: pass as options.expectedHash on file_write replace|patch|multi_replace.

LARGE FILE, NEED ORIENTATION? Do not read it whole. Ask the local model instead (AW, WP-G G5):
  flow_management action=start mode=flow templateName=file-digest params={path:'<abs path>', question:'<what you need>'}
then action=artifact runId=<id> stage=emit. You get outline_exact (pattern-matched, trustworthy), a digest_lossy citing
line ranges, and verify_with range calls. The digest is a map, not the file: confirm with range before relying on it.
For a long file, pass startLine=<next_startLine> to digest the next window.'''

    /**
     * WP-1 (0.9.21): the tool description is not worth a single second of startup.
     *
     * DEFAULT_DESC is installed synchronously so the bean is fully usable the moment it
     * is constructed; the refresh from CS then runs on a daemon thread. The old loop slept
     * 0/500/1000/2000/3000ms INLINE, and on a cold start -- where CS :8082 does not exist
     * yet because our own sibling is about to spawn it -- it always spent the whole 6.5s
     * budget and then fell back to DEFAULT_DESC anyway. Measured 2026-09-08 17:23:11-24:
     * 13.5s across this service and FileWriteService, sitting on the critical path between
     * Claude Desktop spawning us and our answering initialize, to arrive at the same string
     * we now start with. Nothing is lost: a cold start served DEFAULT_DESC before this
     * change too, and ServerLifecycleService still calls reloadDescriptionsFromCs() once
     * the companions are confirmed up.
     */
    @PostConstruct
    void init() {
        toolDescription = DEFAULT_DESC

        Thread refresher = new Thread({ ->
            int[] delays = [0, 500, 1000, 2000, 3000]
            for (int i = 0; i < delays.length; i++) {
                if (delays[i] > 0) {
                    try {
                        Thread.sleep(delays[i])
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                try {
                    String loaded = contextServerClient?.getHelpSection('tool_desc_file_read')
                    if (loaded) {
                        toolDescription = loaded
                        log.debug('FileReadService: loaded tool description from CS help_sections (attempt {})', i + 1)
                        return
                    }
                    log.debug('FileReadService: CS section missing on attempt {} -- retrying', i + 1)
                } catch (Exception e) {
                    log.debug('FileReadService.init attempt {} failed (non-fatal): {}', i + 1, e.message)
                }
            }
            log.debug('FileReadService: CS unavailable after retries -- keeping DEFAULT_DESC fallback')
        } as Runnable, 'fs-tooldesc-read')
        refresher.daemon = true
        refresher.start()
    }

    /**
     * Called by ServerLifecycleService after HTTP companions are confirmed up.
     * Gives FileReadService a second chance to load the description from CS
     * (the @PostConstruct retry loop fires before CS HTTP companion exists).
     * @return true if successfully reloaded from CS, false otherwise
     */
    boolean reloadDescriptionsFromCs() {
        try {
            String loaded = contextServerClient?.getHelpSection('tool_desc_file_read')
            if (loaded) {
                toolDescription = loaded
                log.debug('FileReadService: tool description reloaded from CS (post-companion-start)')
                return true
            }
        } catch (Exception e) {
            log.debug('FileReadService.reloadDescriptionsFromCs failed (non-fatal): {}', e.message)
        }
        return false
    }

    FileReadService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[\
            name       : 'file_read',
            description: toolDescription,
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['read','head','tail','range','grep','multi_grep','multi','info','summary','stat',
                                     'exists','project_root','allowed_dirs','normalize',
                                     'diff','checksum','list','structure','get_method','chunk_read','finalise_read','help',
                                     'read_office']],
                    path   : [type: 'string', description: 'File or dir path (not required for project_root/allowed_dirs/multi/chunk_read/finalise_read/help)'],
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
                                  paths       : [type: 'array', items: [type: 'string'], description: 'File paths for multi/multi_grep (required for multi max 10, multi_grep max 20)'],
                                  knownHashes : [type: 'object', description: 'Map of {path->12-char-hash} from prior reads. Files matching hash return {unchanged:true} with no content.'],
                                  compareTo   : [type: 'string',  description: 'Second file for diff (required for diff)'],
                                  algorithm   : [type: 'string',  description: 'Checksum: MD5|SHA-256 (default SHA-256)'],
                                  sessionId   : [type: 'string',  description: 'Session ID (required for chunk_read, finalise_read)'],
                                  chunkIndex  : [type: 'integer', description: 'Chunk index 0-based (required for chunk_read)'],
                                  compact     : [type: 'boolean', description: 'Minimal response - omits action/path echo, returns content+hash only. Supported by read, head, tail, range, grep, structure (methods only, no endLine)'],
                                  knownHash   : [type: 'string',  description: 'Optional. file_content_hash from a prior read, for action=read or get_method: unchanged file = {unchanged:true}. Repeats are de-duplicated without it. Do NOT pass to action=range.'],
                                  force       : [type: 'boolean', description: 'Re-send content this chat was already sent (after compaction, or when a subagent made the read). Also overrides the >200-line refusal on action=read.'],
                                  className   : [type: 'string',  description: 'Filter structure to one class subtree (returns error+availableClasses if not found)'],
                                  topic       : [type: 'string',  description: 'Help topic: tool name or "all" (for help action)'],
                                  toon        : [type: 'boolean', description: 'Encode directory listing entries in compact Toon columnar notation to save context tokens. Only applies to action=list. Default false.']
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
            String action               = arguments.action as String
            String path                 = arguments.path as String
            Map<String, Object> options = normaliseOptions(arguments.options)

            // Guard: most actions require a valid path
            if (!path && action != 'multi' && action != 'multi_grep' && action != 'project_root' && action != 'allowed_dirs' && action != 'chunk_read' && action != 'finalise_read' && action != 'help') {
                return McpResponse.toolError(requestId, "file_read '${action}' requires a 'path' parameter (received null).")
            }

            // FS 0.9.26 -- ONTOLOGY-GATE AT DISPATCH, not from a per-action list.
            //
            // The gate used to be applied inside doRead, doRange and doGetMethod. Everything else
            // that returns file content -- grep, head, tail, structure, summary -- walked straight
            // past it, and those are the CHEAP calls, which is to say the ones actually used. Three
            // gated actions out of eight was gating the exception. This is the same correction CS
            // 1.0.52 made when it moved the knowledge dirty-flag into dispatch, ahead of the
            // handler-registry fast path, for exactly the same reason.
            //
            // The exempt set is named EXPLICITLY rather than left to fall through, so an action
            // added later is gated by default and has to be argued out of the set instead of
            // quietly missing it. Exempt because they return no file content (exists, stat, info,
            // checksum, normalize, project_root, allowed_dirs, list, help) or because they carry
            // no single path (multi, multi_grep, chunk_read, finalise_read). The two multi-path
            // actions are NOT ungated -- FS 0.9.30 gates both per path inside their own cases,
            // through the same ReadResponseHelper decision this guard calls. They are named here
            // because dispatch has no single `path` to ask about, which is a different thing from
            // being exempt; until 0.9.30 multi_grep really was exempt, and that was the last hole.
            if (responseHelper != null && path &&
                // FS 0.9.43 N11: 'grep' joins the exempt set. It is the navigation primitive the
                // gate should be sending people TO -- it returns matching lines with their numbers,
                // which is what locate returns -- and gating it made the cheapest way to find a
                // symbol cost a locate first. Its hits are recorded as locate evidence below.
                !(action in ['exists', 'stat', 'info', 'checksum', 'normalize', 'project_root',
                             'allowed_dirs', 'list', 'help', 'chunk_read', 'finalise_read',
                             'grep', 'multi', 'multi_grep'])) {
                String gateNorm = pathService.normalizePath(path)
                McpResponse gateBlock =
                    responseHelper.checkOntologyGate(gateNorm, options, requestId, action)
                if (gateBlock != null) return gateBlock
            }

            switch (action) {
                // FS 0.9.40 K2 (decision 206): every content action asks the served ledger.
                case 'read' : return servedRead(path, options, requestId)
                case 'head' : return servedHeadTail(path, options, requestId, false)
                case 'tail' : return servedHeadTail(path, options, requestId, true)
                case 'range': return dedupedRange(path, options, requestId)
                case 'grep'         : {
                    // FS 0.9.35 G4c: the same grep on unchanged content is not re-sent.
                    String gh = null
                    String gkey = 'grep:' + options.get('pattern') + '|ctx=' + (options.get('contextLines') ?: 0) +
                                  '|max=' + (options.get('maxMatches') ?: '')
                    boolean gforce = Boolean.parseBoolean(String.valueOf(options.get('force') ?: 'false'))
                    if (contextServerClient != null && options.get('pattern')) {
                        gh = structureCache?.getHash(pathService.normalizePath(path))
                        if (gh && !gforce && contextServerClient.servedKeySeen(path, gkey, gh)) {
                            return cachedResponse(requestId, gh,
                                'This grep on this file content was already returned to this chat. ' + SERVED_FORCE_HINT, null)
                        }
                    }
                    boolean gForcedRepeat = gh && gforce && contextServerClient.servedKeySeen(path, gkey, gh)
                    McpResponse gr = contentReader.doGrep(path, options, requestId)
                    if (gForcedRepeat && gr.error == null) gr = markForced(gr, requestId)
                    if (gr.error == null && contextServerClient != null && options.get('pattern')) {
                        String h2 = gh ?: structureCache?.getHash(pathService.normalizePath(path)) ?: extractFileHash(gr)
                        if (h2) contextServerClient.recordServedKeyAsync(path, gkey, h2)
                    }
                    return gr
                }
                case 'multi_grep'   : {
                    // FS 0.9.30 -- W3: multi_grep was the last file_read action that returned file
                    // content with no gate at all. The dispatch guard cannot cover it -- there is no
                    // single `path` to ask about -- so it is gated here, per path, through the same
                    // decision function dispatch uses. Being named in the exempt list above is about
                    // the shape of the arguments, not about being exempt.
                    Map<String, Object> mg = gateMultiPaths(
                        (options.paths as List<String>) ?: [], options, 'multi_grep', false)
                    List<Map> mgBlocked = mg.get('blocked') as List<Map>
                    if (mgBlocked) {
                        List<String> mgAllowed = mg.get('allowed') as List<String>
                        if (!mgAllowed) return McpResponse.toolError(requestId,
                            groovy.json.JsonOutput.toJson([error  : 'BLOCKED_ONTOLOGY_GATE',
                                blocked: mgBlocked,
                                hint   : 'All requested files are ontology-indexed and unlocated. locate each locate_query below, then retry.']))
                        options = new HashMap<String, Object>(options as Map<String, Object>)
                        options.paths = mgAllowed
                        options._blocked = mgBlocked
                    }
                    return servedMultiGrep(options, requestId)
                }
                case 'multi'        : {
                    // Fix D (v0.8.54) guarded this case with a SECOND, different gate: it asked
                    // isOntologyIndexed(stem), returned BLOCKED_UNRANGED_INDEXED_READ, and handed
                    // back a bare file stem as the locate_query. That stem is the defect CS 1.0.62
                    // shipped to remove -- 6 files named CLAUDE.md, 2,012 names shared across
                    // source_files -- so the hint resolved to a different file and left the caller
                    // blocked. FS 0.9.30 routes this case through the one decision every other
                    // action uses, and the hint is now the node_id CS resolved.
                    //
                    // The hash-only exemptions are kept: a knownHash supplied for the path, or
                    // compact=true, means the caller wants change detection, not content, and no
                    // content tokens are at risk. They are passed for `multi` only -- multi_grep
                    // has no hash-only mode, so there is nothing there to exempt.
                    Map<String, Object> mr = gateMultiPaths(
                        (options.paths as List<String>) ?: [], options, 'multi', true)
                    List<Map> blocked = mr.get('blocked') as List<Map>
                    if (blocked) {
                        List<String> allowed = mr.get('allowed') as List<String>
                        if (!allowed) return McpResponse.toolError(requestId,
                            groovy.json.JsonOutput.toJson([error  : 'BLOCKED_ONTOLOGY_GATE',
                                blocked: blocked,
                                hint   : 'All requested files are ontology-indexed and unlocated. locate each locate_query below, then retry.']))
                        options = new HashMap<String, Object>(options as Map<String, Object>)
                        options.paths = allowed
                        options._blocked = blocked
                    }
                    return servedMulti(options, requestId)
                }
                case 'info'         : return metaReader.doInfo(path, requestId)
                case 'summary'      : return metaReader.doSummary(path, requestId)
                case 'stat'         : return metaReader.doStat(path, requestId)
                case 'exists'       : return metaReader.doExists(path, requestId)
                case 'project_root' : return metaReader.doProjectRoot(requestId)
                case 'allowed_dirs' : return metaReader.doAllowedDirs(requestId)
                case 'normalize'    : return metaReader.doNormalize(path, requestId)
                case 'diff'         : return metaReader.doDiff(path, options, requestId)
                case 'checksum'     : return metaReader.doChecksum(path, options, requestId)
                case 'list'         : {
                    McpResponse listResp = metaReader.doList(path, requestId, options)
                    boolean toon = options.get('toon') as boolean
                    if (toon && listResp.result != null) {
                        // Extract the entries list from the JSON text response and Toon-encode it
                        try {
                            List content = listResp.result.get('content') as List
                            if (content) {
                                String text = (content[0] as Map)?.get('text') as String
                                if (text) {
                                    Map data = (Map) new JsonSlurper().parseText(text)
                                    List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get('entries')
                                    if (entries) {
                                        String toonBlock = ToonEncoder.encodeFileListing(entries, ToonOptions.fileListingOnly())
                                        data.put('entries', toonBlock)
                                        data.put('toon_encoded', true)
                                        return textResponse(requestId, data)
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    return listResp
                }
                case 'structure'    : return servedStructure(path, options, requestId)
                case 'get_method'   : {
                    // FS 0.9.30 -- W2: the second gate call for this read has gone.
                    // Dispatch above already asked, for every action not in the exempt list, and
                    // get_method is not in it. Asking again here cost a CS round trip per read and,
                    // worse, made the gate look covered at two layers when only one of them decides.
                    // The layer that decides is dispatch; assert there. (The third copy lived in
                    // FileContentReader.doGetMethod, a wrapper production never called -- deleted.)
                    // FIX-KH-RANGE-AUTO (FS 0.8.81): auto-lookup range cache before calling doGetMethod.
                    // First read records startLine/endLine in range cache. On repeat call, if
                    // StructureCache has the file hash, we can auto-hit without caller passing knownHash.
                    // FS 0.9.35 G4a: keyed by the METHOD. The (0,0) sentinel this replaces was
                    // refused by CS (/rangeCache requires startLine >= 1), so it never hit; and one
                    // sentinel per file would have answered for every method in it.
                    String gmKey = 'method:' + (options.get('className') ?: '') + '#' + options.get('method')
                    boolean gmForce = Boolean.parseBoolean(String.valueOf(options.get('force') ?: 'false'))
                    if (contextServerClient != null && options.get('method') && !gmForce) {
                        String fileHash = options.get('knownHash') as String
                        if (!fileHash) fileHash = structureCache?.getHash(pathService.normalizePath(path))
                        if (fileHash && contextServerClient.servedKeySeen(path, gmKey, fileHash)) {
                            return cachedResponse(requestId, fileHash,
                                'Method ' + options.get('method') + ' was already sent to this chat. ' + SERVED_FORCE_HINT, null)
                        }
                    }
                    // FS 0.9.30 -- W2: the missing-knownHash advisory moves to the layer that RUNS.
                    // It lived in FileContentReader.doGetMethod, the wrapper this very line
                    // bypasses, so get_method has never once emitted it in production -- while
                    // MKH-9 proved it worked, by calling the wrapper. Same shape as the gate: a
                    // thing that only ever ran in the test. Captured before the read, because the
                    // read populates the cache it is compared against.
                    String gmPreCachedHash = responseHelper?.peekStructureCache(pathService.normalizePath(path))
                    McpResponse r = structureReader.doGetMethod(path, options, requestId)
                    if (r.error == null && contextServerClient != null) {
                        String h = structureCache?.getHash(pathService.normalizePath(path)) ?: extractFileHash(r)
                        if (h) {
                            // Fix C (v0.8.56): record with actual line range from response
                            // so a subsequent range read of same lines returns cached:true.
                            Map<String, Object> payload = parseResponsePayload(r)
                            int rsl = payload?.get('startLine') as Integer ?: 0
                            int rel = payload?.get('endLine')   as Integer ?: 0
                            if (rsl >= 1 && rel >= rsl) contextServerClient.recordRangeCacheAsync(path, rsl, rel, h)
                            if (options.get('method')) contextServerClient.recordServedKeyAsync(path, gmKey, h)
                        }
                    }
                    if (r != null && r.error == null && responseHelper != null) {
                        try {
                            Map<String, Object> gmPayload = parseResponsePayload(r)
                            if (gmPayload != null) {
                                responseHelper.maybeWarnMissingKnownHash(
                                    gmPayload, pathService.normalizePath(path), options,
                                    'get_method', gmPreCachedHash)
                                if (gmPayload.containsKey('_missing_knownhash')) {
                                    return textResponse(requestId, gmPayload)
                                }
                            }
                        } catch (Exception ignored) { } // fail-open
                    }
                    return r
                }
                case 'chunk_read'   : return responseHelper.doChunkRead(options, requestId)
                case 'finalise_read': return responseHelper.doFinaliseRead(options, requestId)
                case 'help'         : return metaReader.doHelp(options, requestId)
                case 'read_office'  : return officeHandler.readOffice(path, options, requestId)
                default:
                    return McpResponse.toolError(requestId, "Unknown file_read action: '${action}'. Valid actions: read|head|tail|range|grep|multi_grep|multi|info|structure|get_method|list|checksum|stat|exists|diff|normalize|chunk_read|finalise_read|help. For script execution use the 'execute' tool.")
            }
        } catch (SecurityException e) {
            return McpResponse.toolError(requestId, "Security error: ${sanitize(e.message)}")
        } catch (FileNotFoundException e) {
            return McpResponse.toolError(requestId, sanitize(e.message))
        } catch (Exception e) {
            log.error('file_read error: {}', sanitize(e.message), e)
            return McpResponse.toolError(requestId, sanitize(e.message))
        }
    }

    /**
     * FS 0.9.30 -- W3: the ontology gate for the two multi-path read actions.
     *
     * <p>Asks {@link ReadResponseHelper#ontologyGateEntry} once per path -- the same decision the
     * dispatch guard makes for single-path actions -- and splits the request into what may proceed
     * and what is blocked. It replaces Fix D (v0.8.54), which asked a different question of a
     * different method and answered with a bare file stem.</p>
     *
     * <p>Fails open, per path: a path that throws is allowed through, exactly as Fix D did. A gate
     * that cannot reach its evidence must not stop work.</p>
     *
     * @param hashOnlyExempt {@code true} for {@code multi}, where a supplied {@code knownHashes}
     *        entry or {@code compact=true} means the caller wants change detection rather than
     *        content. {@code multi_grep} has no such mode, so it passes {@code false} rather than
     *        inheriting an exemption that would mean nothing there.
     * @return {@code [allowed: List<String>, blocked: List<Map>]}
     */
    private Map<String, Object> gateMultiPaths(List<String> rawPaths, Map<String, Object> options,
                                               String action, boolean hashOnlyExempt) {
        List<String> allowed = []
        List<Map> blocked    = []
        if (responseHelper == null || !rawPaths) {
            return [allowed: rawPaths, blocked: blocked] as Map<String, Object>
        }
        Map knownHashMap = (options.knownHashes instanceof Map) ? (options.knownHashes as Map) : [:]
        boolean compactMode = options.compact as boolean ?: false
        rawPaths.each { String p ->
            try {
                String np = pathService.normalizePath(p)
                if (hashOnlyExempt) {
                    boolean hasKnownHash = knownHashMap.containsKey(np) || knownHashMap.containsKey(p)
                    if (hasKnownHash || compactMode) { allowed << p; return }
                }
                Map<String, Object> entry = responseHelper.ontologyGateEntry(np, options, action)
                if (entry == null) { allowed << p; return }
                Map<String, Object> blockedEntry = new LinkedHashMap<String, Object>(entry)
                blockedEntry.put('file', p)
                if (hashOnlyExempt && contextServerClient != null) {
                    // FS 0.8.69 FIX-6A carried forward: hand back the hash so the caller can retry
                    // with options.knownHashes and get the ~15-token unchanged response.
                    String knownHash = contextServerClient.getKnownHashForPath(np)
                    if (knownHash) blockedEntry.put('known_hash', knownHash)
                }
                blocked << blockedEntry
            } catch (Exception ignored) {
                allowed << p
            }
        }
        return [allowed: allowed, blocked: blocked] as Map<String, Object>
    }

    private void fireRegistryUpsert(String path, McpResponse resp) {
        if (!path || resp.result == null) return
        try {
            String hash = extractFileHash(resp)
            if (!hash) return
            String np = pathService.normalizePath(path)
            if (contextServerClient != null) {
                contextServerClient.upsertFileRegistryAsync(np, hash, 0, new File(np).lastModified())
            }
        } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // FS 0.9.40 K2 (decision 206): the served ledger, asked by every content action.
    // The ledger is keyed on the CHAT (CS), windowed (cs.served-window-minutes), and
    // hash-guarded -- an edited file is a different hash and is always sent.
    // -----------------------------------------------------------------------

    static final String SERVED_FORCE_HINT =
        'If you no longer hold it (context compacted, or a subagent made the read), repeat with options.force=true.'

    private static boolean isForce(Map<String, Object> options) {
        return Boolean.parseBoolean(String.valueOf(options?.get('force') ?: 'false'))
    }

    /** The hash the ledger is keyed on, or null when the ledger is unavailable. */
    private String ledgerHash(String path) {
        if (contextServerClient == null || !path) return null
        try { return structureCache?.getHash(pathService.normalizePath(path)) }
        catch (Exception ignored) { return null }
    }

    private int lineCount(String path, Map<String, Object> options) {
        try {
            return com.softwood.mcp.service.read.ReadResponseHelper.countLinesUpTo(
                pathService.normalizePath(path), Integer.MAX_VALUE - 1,
                (options?.get('encoding') as String) ?: 'UTF-8')
        } catch (Exception ignored) { return -1 }
    }

    /** True when this chat already holds every line of the file at this hash. */
    private boolean wholeFileHeld(String path, String hash, Map<String, Object> options) {
        List<List<Integer>> served = contextServerClient.rangeCoverage(path, hash)
        if (served.isEmpty()) return false
        int total = lineCount(path, options)
        return total > 0 && com.softwood.mcp.service.read.RangeCoverage.uncovered(1, total, served) == null
    }

    /** Marks a response as a forced re-send of content the chat already held (wasted_tok). */
    private McpResponse markForced(McpResponse r, Object requestId) {
        Map<String, Object> p = parseResponsePayload(r)
        if (p == null) return r
        p.put('forced_repeat', true)
        return textResponse(requestId, p)
    }

    private McpResponse servedRead(String path, Map<String, Object> options, Object requestId) {
        String h = options.get('knownHash') ? null : ledgerHash(path)
        boolean force = isForce(options)
        boolean held = h && wholeFileHeld(path, h, options)
        if (held && !force) {
            return cachedResponse(requestId, h, 'The whole file was already sent to this chat. ' + SERVED_FORCE_HINT, null)
        }
        McpResponse r = contentReader.doRead(path, options, requestId)
        if (r.error != null) return r
        fireRegistryUpsert(path, r)
        if (h) {
            Map<String, Object> rp = parseResponsePayload(r)
            if (rp != null && rp.get('content') != null && !rp.get('_truncated') && !rp.get('unchanged')) {
                int total = lineCount(path, options)
                if (total > 0) contextServerClient.recordRangeCacheAsync(path, 1, total, h)
            }
        }
        if (held) r = markForced(r, requestId)
        return r
    }

    /** head and tail are ranges: same ledger, same partial answers. */
    private McpResponse servedHeadTail(String path, Map<String, Object> options, Object requestId, boolean tail) {
        int n = (options.get('lines') as Integer) ?: 50
        if (contextServerClient == null || options.get('knownHash') || n > 500) {
            McpResponse r = tail ? contentReader.doTail(path, options, requestId) : contentReader.doHead(path, options, requestId)
            if (r.error == null) fireRegistryUpsert(path, r)
            return r
        }
        int start = 1
        if (tail) {
            int total = lineCount(path, options)
            if (total < 0) return contentReader.doTail(path, options, requestId)   // the reader reports the error
            start = Math.max(1, total - n + 1)
            n = Math.max(1, total - start + 1)
        }
        Map<String, Object> ro = new LinkedHashMap<String, Object>(options)
        ro.remove('lines')
        ro.put('startLine', start)
        ro.put('maxLines', n)
        return dedupedRange(path, ro, requestId)
    }

    private McpResponse servedStructure(String path, Map<String, Object> options, Object requestId) {
        String h = ledgerHash(path)
        String key = 'structure:' + (options.get('className') ?: '*') + '|compact=' + (options.get('compact') ?: false)
        boolean seen = h && contextServerClient.servedKeySeen(path, key, h)
        if (seen && !isForce(options)) {
            return cachedResponse(requestId, h,
                'This structure of this file content was already sent to this chat. ' + SERVED_FORCE_HINT, null)
        }
        McpResponse r = structureReader.doStructure(path, options, requestId)
        if (r.error != null) return r
        if (h) contextServerClient.recordServedKeyAsync(path, key, h)
        return seen ? markForced(r, requestId) : r
    }

    /** multi: files the chat already holds whole are answered unchanged; the rest are read. */
    private McpResponse servedMulti(Map<String, Object> options, Object requestId) {
        List<String> paths = (options.get('paths') as List<String>) ?: []
        if (contextServerClient == null || !paths) return contentReader.doMulti(options, requestId)
        boolean force = isForce(options)
        List<Map<String, Object>> held = []
        List<String> send = []
        Map<String, String> sendHash = [:]
        for (String p : paths) {
            String h = ledgerHash(p)
            if (h && !force && wholeFileHeld(p, h, options)) {
                held << ([path: p, unchanged: true, file_content_hash: h, success: true] as Map<String, Object>)
            } else {
                send << p
                if (h) sendHash.put(p, h)
            }
        }
        if (!held.isEmpty() && send.isEmpty()) {
            Map<String, Object> resp = [action: 'multi', count: held.size(), unchanged_count: held.size(), files: held,
                hint: 'Every file that could be read was already sent to this chat. ' + SERVED_FORCE_HINT] as Map<String, Object>
            if (options.get('_blocked')) resp.put('blocked', options.get('_blocked'))
            return textResponse(requestId, resp)
        }
        Map<String, Object> narrowed = options
        if (!held.isEmpty()) {
            narrowed = new HashMap<String, Object>(options)
            narrowed.put('paths', send)
        }
        McpResponse r = contentReader.doMulti(narrowed, requestId)
        if (r.error != null) return r
        Map<String, Object> payload = parseResponsePayload(r)
        if (payload == null) return r
        List<Map<String, Object>> files = (payload.get('files') as List<Map<String, Object>>) ?: []
        sendHash.each { String p, String h ->
            String np = pathService.normalizePath(p)
            Map<String, Object> f = files.find { Map<String, Object> m -> m.get('path') == np || m.get('path') == p }
            if (f != null && f.get('success') && f.get('content') != null && !f.get('_truncated') && !f.get('unchanged')) {
                int total = lineCount(p, options)
                if (total > 0) contextServerClient.recordRangeCacheAsync(p, 1, total, h)
            }
        }
        if (held.isEmpty()) return r
        files.addAll(held)
        payload.put('files', files)
        payload.put('count', files.size())
        payload.put('unchanged_count', ((payload.get('unchanged_count') as Integer) ?: 0) + held.size())
        payload.put('hint', '' + held.size() + ' file(s) were already sent to this chat and are not repeated. ' + SERVED_FORCE_HINT)
        return textResponse(requestId, payload)
    }

    /** multi_grep: a path this grep already ran on (same content) is not searched again. */
    private McpResponse servedMultiGrep(Map<String, Object> options, Object requestId) {
        List<String> paths = (options.get('paths') as List<String>) ?: []
        if (contextServerClient == null || !paths || !options.get('pattern')) return contentReader.doMultiGrep(options, requestId)
        String key = 'mgrep:' + options.get('pattern') + '|ctx=' + (options.get('contextLines') ?: 0) +
                     '|max=' + (options.get('maxMatches') ?: '')
        boolean force = isForce(options)
        List<String> held = []
        List<String> send = []
        Map<String, String> sendHash = [:]
        for (String p : paths) {
            String h = ledgerHash(p)
            if (h && !force && contextServerClient.servedKeySeen(p, key, h)) held << p
            else { send << p; if (h) sendHash.put(p, h) }
        }
        McpResponse r
        if (send.isEmpty()) {
            Map<String, Object> resp = [action: 'multi_grep', pattern: options.get('pattern'), fileCount: 0,
                matchingFiles: 0, totalMatches: 0, results: []] as Map<String, Object>
            if (options.get('_blocked')) resp.put('blocked', options.get('_blocked'))
            r = textResponse(requestId, resp)
        } else if (!held.isEmpty()) {
            Map<String, Object> narrowed = new HashMap<String, Object>(options)
            narrowed.put('paths', send)
            r = contentReader.doMultiGrep(narrowed, requestId)
        } else {
            r = contentReader.doMultiGrep(options, requestId)
        }
        if (r.error != null) return r
        sendHash.each { String p, String h -> contextServerClient.recordServedKeyAsync(p, key, h) }
        if (held.isEmpty()) return r
        Map<String, Object> payload = parseResponsePayload(r)
        if (payload == null) return r
        payload.put('unchanged_paths', held)
        payload.put('hint', 'This grep was already returned for ' + held.size() +
            ' file(s) in this chat; they are not repeated. ' + SERVED_FORCE_HINT)
        return textResponse(requestId, payload)
    }

    /**
     * FS 0.9.40 K2: the served-ledger range path, shared by range, head and tail.
     */
    private McpResponse dedupedRange(String path, Map<String, Object> options, Object requestId) {
        // Fix C (v0.8.50): session range read cache.
        // knownFileHash lets us validate the cache entry against current file state.
        // Graceful degradation: if CS is down or hash absent, fall through to normal read.
        // FIX-KH-RANGE-AUTO (FS 0.8.81): if caller did not supply a hash, derive it from
        // StructureCache (in-memory, no I/O). This removes the requirement for the caller
        // to pass knownHash in order to benefit from the range cache on repeat reads.
        // Safety: checkRangeCache only hits when (session, path, startLine, endLine, hash)
        // all match -- the hash guards against stale entries if the file changed.
        boolean forcedRepeat = false
        if (contextServerClient != null) {
            String fileHash = options.get('knownFileHash') as String
            if (!fileHash) fileHash = options.get('knownHash') as String
            if (!fileHash) {
                // Auto-derive from StructureCache: pure in-memory, populated on every FS op.
                // Returns null if file has never been seen this session -> safe cache miss.
                fileHash = structureCache?.getHash(pathService.normalizePath(path))
            }
            int csl = (options.get('startLine') as Integer) ?: 1
            int cml = (options.get('maxLines') as Integer) ?: 100
            boolean force = Boolean.parseBoolean(String.valueOf(options.get('force') ?: 'false'))
            if (fileHash && force) {
                // K2: a forced re-read of lines the chat already holds is the waste the ledger measures
                List<List<Integer>> fsv = contextServerClient.rangeCoverage(path, fileHash)
                forcedRepeat = !fsv.isEmpty() &&
                    com.softwood.mcp.service.read.RangeCoverage.uncovered(csl, csl + cml - 1, fsv) == null
            }
            if (fileHash && !force) {
                // FS 0.9.35 G4b: de-duplicate by LINES, not by exact window. Exact-pair
                // matching served 4 of 867 range reads from cache; paging asks for
                // overlapping windows. Fully covered -> unchanged; partly -> only the
                // unserved span, with what was already served named.
                List<List<Integer>> served = contextServerClient.rangeCoverage(path, fileHash)
                if (!served.isEmpty()) {
                    List<Integer> todo = com.softwood.mcp.service.read.RangeCoverage.uncovered(csl, csl + cml - 1, served)
                    if (todo == null) {
                        return cachedResponse(requestId, fileHash,
                            'Lines ' + csl + '-' + (csl + cml - 1) + ' were already sent to this chat. ' + SERVED_FORCE_HINT,
                            com.softwood.mcp.service.read.RangeCoverage.describe(served))
                    }
                    if (todo[0] != csl || todo[1] != csl + cml - 1) {
                        List<String> alreadyServed = com.softwood.mcp.service.read.RangeCoverage.describe(served)
                        Map<String, Object> narrowed = new LinkedHashMap<String, Object>(options)
                        narrowed.put('startLine', todo[0])
                        narrowed.put('maxLines', todo[1] - todo[0] + 1)
                        McpResponse nr = contentReader.doRange(path, narrowed, requestId)
                        if (nr.error == null) {
                            fireRegistryUpsert(path, nr)
                            String nh = fileHash
                            Map<String, Object> np = parseResponsePayload(nr)
                            // K2: record what was SENT -- a capped window ends before todo[1]
                            int nEnd = (np?.get('endLine') as Integer) ?: todo[1]
                            if (nEnd >= todo[0]) contextServerClient.recordRangeCacheAsync(path, todo[0], nEnd, nh)
                            if (np != null) {
                                np.put('already_served', alreadyServed)
                                np.put('requested', csl + '-' + (csl + cml - 1))
                                return textResponse(requestId, np)
                            }
                        }
                        return nr
                    }
                }
            }
        }
        McpResponse r = contentReader.doRange(path, options, requestId)
        if (r.error == null) {
            fireRegistryUpsert(path, r)
            if (contextServerClient != null) {
                // Record under the SAME hash source the check reads, or they never meet.
                String h = structureCache?.getHash(pathService.normalizePath(path)) ?: extractFileHash(r)
                Map<String, Object> rp = parseResponsePayload(r)
                int rsl = (rp?.get('startLine') as Integer) ?: ((options.get('startLine') as Integer) ?: 1)
                int rel = (rp?.get('endLine') as Integer) ?: (rsl + ((options.get('maxLines') as Integer) ?: 100) - 1)
                // FS 0.9.35: record the lines ACTUALLY returned (a window past EOF is
                // shorter than asked), so coverage never claims lines nobody saw.
                if (h && rel >= rsl) contextServerClient.recordRangeCacheAsync(path, rsl, rel, h)
            }
        }
        if (forcedRepeat && r.error == null) r = markForced(r, requestId)
        return r
    }

    /**
     * FS 0.9.35 G4: one shape for every "you already have this" answer. Carries unchanged:true so
     * telemetry classifies it as a cache answer (it recorded 'success' for the old cached:true
     * shape, which is why no range hit ever showed in the ledger).
     */
    private McpResponse cachedResponse(Object requestId, String fileHash, String hint, List<String> alreadyServed) {
        Map<String, Object> hit = new LinkedHashMap<String, Object>()
        hit.put('unchanged', true)
        hit.put('cached', true)
        hit.put('is_repeat_call', true)
        hit.put('file_content_hash', fileHash)
        if (alreadyServed) hit.put('already_served', alreadyServed)
        hit.put('hint', hint)
        return textResponse(requestId, hit)
    }

    private static String extractFileHash(McpResponse resp) {
        try {
            if (resp.result == null) return null
            List content = resp.result.get('content') as List
            if (!content) return null
            String text = (content[0] as Map)?.get('text') as String
            if (!text) return null
            Map data = (Map) new groovy.json.JsonSlurper().parseText(text)
            return data?.get('file_content_hash') as String
        } catch (Exception ignored) { return null }
    }

    /** Parse the full JSON payload map from an McpResponse content envelope. */
    private static Map<String, Object> parseResponsePayload(McpResponse resp) {
        try {
            if (resp.result == null) return null
            List content = resp.result.get('content') as List
            if (!content) return null
            String text = (content[0] as Map)?.get('text') as String
            if (!text) return null
            return (Map<String, Object>) new groovy.json.JsonSlurper().parseText(text)
        } catch (Exception ignored) { return null }
    }
}
