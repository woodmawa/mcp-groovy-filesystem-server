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

    // DEFAULT_DESC kept in sync with tool_desc_file_read section_key in help_sections.
    // Update via: context_lifecycle execute_sql UPDATE help_sections SET content=? WHERE section_key='tool_desc_file_read'
    private static final String DEFAULT_DESC = '''\
Read files/directories.
Actions: read|head|tail|range|grep|multi_grep|multi|info|summary|stat|exists|project_root|allowed_dirs|normalize|diff|checksum|list|structure|get_method|chunk_read|finalise_read|help

KNOWNHASH IS MANDATORY ON EVERY REPEAT READ (practice #497).
Sources: (1) bootstrap globals working_file_hashes[path].hash for prior-session files.
         (2) file_content_hash field returned by every read response -- capture immediately.
         (3) _knownhash_hint field -- appears in every content response where knownHash was omitted.
Usage:   Pass as options.knownHash on action=read (whole-file) or action=get_method only.
Result:  Unchanged file returns {unchanged:true} = ZERO tokens consumed.
Metric:  knownhash_pct tracked per session. FAILING in mid-session-audit if <30%.

CRITICAL: Do NOT pass options.knownHash to action=range.
  action=range with a matching knownHash returns {unchanged:true} instead of content.
  Range reads are deduplicated automatically by the session range cache -- no knownHash needed.
  knownHash is for action=read (whole-file) and action=get_method only.

Key params: path (absolute), options.lines (head/tail), options.startLine+maxLines (range), options.pattern+contextLines (grep), options.method (get_method), options.knownHash (read|get_method|list ONLY -- NOT range), options.force (override >200-line refusal), options.compact (minimal response), options.className (structure filter).
action=list returns listing_hash. Pass as options.knownHash to get {unchanged:true} (~15 tokens) when directory is unmodified.
action=multi_grep: grep one pattern across options.paths[] in one call - returns only files with matches.
All read actions return file_content_hash. MANDATORY: pass as options.expectedHash on file_write replace|patch|multi_replace.'''

    @PostConstruct
    void init() {
        // Retry with backoff: CS HTTP companion may not be ready at FS @PostConstruct time.
        // DT cold-start launches all servers in parallel -- CS :8082 typically needs 2-4s.
        // 5 attempts at 0/500/1000/2000/3000ms = max ~6.5s wait before falling back to DEFAULT_DESC.
        int[] delays = [0, 500, 1000, 2000, 3000]
        for (int i = 0; i < delays.length; i++) {
            if (delays[i] > 0) {
                try { Thread.sleep(delays[i]) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
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
        toolDescription = DEFAULT_DESC
        log.debug('FileReadService: CS unavailable after retries -- using DEFAULT_DESC fallback')
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
                                  knownHash   : [type: 'string',  description: 'Pass file_content_hash from prior read. For action=read and action=get_method: file unchanged = {unchanged:true}, ZERO tokens. Do NOT pass to action=range -- range returns unchanged:true instead of content; range cache is automatic. Source: (1) bootstrap working_file_hashes[path].hash, (2) file_content_hash of any prior read response.'],
                                  force       : [type: 'boolean', description: 'Override >200-line refusal on action=read.'],
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

            switch (action) {
                case 'read' : {
                    McpResponse r = contentReader.doRead(path, options, requestId)
                    if (r.error == null) fireRegistryUpsert(path, r)
                    return r
                }
                case 'head' : {
                    McpResponse r = contentReader.doHead(path, options, requestId)
                    if (r.error == null) fireRegistryUpsert(path, r)
                    return r
                }
                case 'tail' : {
                    McpResponse r = contentReader.doTail(path, options, requestId)
                    if (r.error == null) fireRegistryUpsert(path, r)
                    return r
                }
                case 'range': {
                    // Fix C (v0.8.50): session range read cache.
                    // knownFileHash lets us validate the cache entry against current file state.
                    // Graceful degradation: if CS is down or hash absent, fall through to normal read.
                    // FIX-KH-RANGE-AUTO (FS 0.8.81): if caller did not supply a hash, derive it from
                    // StructureCache (in-memory, no I/O). This removes the requirement for the caller
                    // to pass knownHash in order to benefit from the range cache on repeat reads.
                    // Safety: checkRangeCache only hits when (session, path, startLine, endLine, hash)
                    // all match -- the hash guards against stale entries if the file changed.
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
                        if (fileHash) {
                            String readAt = contextServerClient.checkRangeCache(path, csl, csl + cml - 1, fileHash)
                            if (readAt != null) {
                                String hitJson = groovy.json.JsonOutput.toJson([
                                    cached          : true,
                                    already_read_at : readAt,
                                    hint            : 'Content already in context from this session. Do not re-read.',
                                    is_repeat_call  : true
                                ])
                                return McpResponse.success(requestId, [
                                    content: [[type: 'text', text: hitJson]]
                                ] as Map<String, Object>)
                            }
                        }
                    }
                    McpResponse r = contentReader.doRange(path, options, requestId)
                    if (r.error == null) {
                        fireRegistryUpsert(path, r)
                        if (contextServerClient != null) {
                            String h = extractFileHash(r)
                            int rsl = (options.get('startLine') as Integer) ?: 1
                            int rml = (options.get('maxLines') as Integer) ?: 100
                            // NOTE: recordRangeCacheAsync fires unconditionally here -- even when
                            // doRange returned unchanged:true (knownHash matched). This ensures the
                            // range cache entry is always refreshed, so subsequent reads still hit.
                            if (h) contextServerClient.recordRangeCacheAsync(path, rsl, rsl + rml - 1, h)
                        }
                    }
                    return r
                }
                case 'grep'         : return contentReader.doGrep(path, options, requestId)
                case 'multi_grep'   : return contentReader.doMultiGrep(options, requestId)
                case 'multi'        : {
                    // Fix D (v0.8.54): guard unranged reads on ontology-indexed files.
                    // Per-path: if CS has this file indexed and no range is specified, block it.
                    // Fail open: CS unavailable or file not indexed -> allow.
                    if (contextServerClient != null) {
                        List<String> rawPaths = (options.paths as List<String>) ?: []
                        // Exempt paths that have a knownHash supplied -- caller only wants
                        // hash-change detection, not full content. No content tokens at risk.
                        // Also exempt if compact=true (hash-only multi read).
                        Map knownHashMap = (options.knownHashes instanceof Map)
                            ? (options.knownHashes as Map) : [:]
                        boolean compactMode = options.compact as boolean ?: false
                        List<Map> blocked = []
                        rawPaths.each { String p ->
                            try {
                                // Normalise path for knownHashes lookup
                                String np = pathService.normalizePath(p)
                                boolean hasKnownHash = knownHashMap.containsKey(np) || knownHashMap.containsKey(p)
                                if (hasKnownHash || compactMode) return // exempt -- no content risk
                                String stem = new File(p).name.replaceAll('\\.\\w+$', '')
                                if (contextServerClient.isOntologyIndexed(stem)) {
                                    // FS 0.8.69 FIX-6A: include known_hash hint so caller can pass
                                    // options.knownHash on retry to get ~15-token unchanged response.
                                    Map<String, Object> blockedEntry = [error: 'BLOCKED_UNRANGED_INDEXED_READ',
                                                file : p,
                                                hint : 'This file is ontology-indexed. Use: context_read scope=ontology action=locate query="' + stem + '" then file_read action=range startLine/endLine.',
                                                locate_query: stem] as Map<String, Object>
                                    String knownHash = contextServerClient.getKnownHashForPath(np)
                                    if (knownHash) blockedEntry.known_hash = knownHash
                                    blocked << blockedEntry
                                }
                            } catch (Exception ignored) {}
                        }
                        if (blocked) {
                            boolean allBlocked = blocked.size() == rawPaths.size()
                            if (allBlocked) {
                                return McpResponse.toolError(requestId,
                                    groovy.json.JsonOutput.toJson([error: 'BLOCKED_UNRANGED_INDEXED_READ',
                                        blocked: blocked,
                                        hint: 'All requested files are ontology-indexed. Use locate + range instead.']))
                            }
                            // Mixed: remove blocked from options, proceed with remainder
                            List<String> allowed = rawPaths.findAll { String p ->
                                blocked.every { (it as Map).file != p }
                            }
                            options = new HashMap<String, Object>(options as Map<String, Object>)
                            options.paths = allowed
                            options._blocked = blocked
                        }
                    }
                    return contentReader.doMulti(options, requestId)
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
                case 'structure'    : return structureReader.doStructure(path, options, requestId)
                case 'get_method'   : {
                    // E-5: ONTOLOGY-GATE hard enforcement (FS 0.9.8) -- gate before cache-hit check.
                    // Delegates to contentReader.doGetMethod which applies the gate and then
                    // falls through to structureReader.doGetMethod on allow.
                    if (responseHelper != null) {
                        String gmNorm = pathService.normalizePath(path)
                        McpResponse gmGate = responseHelper.checkOntologyGate(gmNorm, options, requestId, 'get_method')
                        if (gmGate != null) return gmGate
                    }
                    // FIX-KH-RANGE-AUTO (FS 0.8.81): auto-lookup range cache before calling doGetMethod.
                    // First read records startLine/endLine in range cache. On repeat call, if
                    // StructureCache has the file hash, we can auto-hit without caller passing knownHash.
                    if (contextServerClient != null) {
                        String fileHash = options.get('knownHash') as String
                        if (!fileHash) fileHash = structureCache?.getHash(pathService.normalizePath(path))
                        if (fileHash) {
                            // Resolve the prior range entry for this method from the range cache.
                            // We don't know startLine/endLine yet (that's what doGetMethod would give us),
                            // so we call checkRangeCache with the whole-file sentinel (0, 0) which CS
                            // uses to record get_method results. If no sentinel, fall through to full read.
                            // NOTE: actual line ranges are stored by the record call below -- so a hit
                            // here means Claude has already seen this method body this session.
                            Map<String, Object> payload = null
                            // Try to recover cached line range from the response helper's last record
                            // by consulting CS with knownHash=fileHash. We use a range probe with
                            // startLine=0 to detect any get_method sentinel entry for this file.
                            // If CS returns a hit for (path, 0, 0, fileHash) we return cached.
                            String readAt = contextServerClient.checkRangeCache(path, 0, 0, fileHash)
                            if (readAt != null) {
                                String hitJson = groovy.json.JsonOutput.toJson([
                                    cached          : true,
                                    already_read_at : readAt,
                                    hint            : 'Method content already in context from this session. Do not re-read.',
                                    is_repeat_call  : true
                                ])
                                return McpResponse.success(requestId, [
                                    content: [[type: 'text', text: hitJson]]
                                ] as Map<String, Object>)
                            }
                        }
                    }
                    McpResponse r = structureReader.doGetMethod(path, options, requestId)
                    if (r.error == null && contextServerClient != null) {
                        String h = extractFileHash(r)
                        if (h) {
                            // Fix C (v0.8.56): record with actual line range from response
                            // so a subsequent range read of same lines returns cached:true.
                            Map<String, Object> payload = parseResponsePayload(r)
                            int rsl = payload?.get('startLine') as Integer ?: 0
                            int rel = payload?.get('endLine')   as Integer ?: 0
                            contextServerClient.recordRangeCacheAsync(path, rsl, rel, h)
                            // FIX-KH-RANGE-AUTO: also record a sentinel (0,0) entry for get_method
                            // so the auto-lookup above can detect repeat calls without knowing line numbers.
                            contextServerClient.recordRangeCacheAsync(path, 0, 0, h)
                        }
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
