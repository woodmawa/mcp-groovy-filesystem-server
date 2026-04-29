package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.read.FileContentReader
import com.softwood.mcp.service.read.FileMetaReader
import com.softwood.mcp.service.read.FileStructureReader
import com.softwood.mcp.service.read.ReadResponseHelper
import com.woodmawa.mcp.toon.ToonEncoder
import com.woodmawa.mcp.toon.ToonOptions
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * FileReadService - ToolHandler entry point for the file_read tool.
 *
 * Thin dispatcher: owns getToolDefinitions/canHandle/handleToolCall only.
 * All action implementations live in the service/read/ subpackage:
 *   - FileContentReader  : read, head, tail, range, grep, multi
 *   - FileStructureReader: structure, get_method
 *   - FileMetaReader     : info, summary, exists, project_root, allowed_dirs, normalize, diff, checksum
 *   - ReadResponseHelper : chunk_read, finalise_read, hash-gate, token meter
 *
 * v0.7.44 - refactored to dispatch-only; implementations split to read/ subpackage.
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
    @Autowired com.softwood.mcp.service.office.OfficeDocumentHandler officeHandler

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
            description: isDescriptionCompact() ? '''\
Read files/directories.
Actions: read|head|tail|range|grep|multi_grep|multi|info|summary|stat|exists|project_root|allowed_dirs|normalize|diff|checksum|list|structure|get_method|chunk_read|finalise_read|help
Key params: path (absolute), options.lines (head/tail), options.startLine+maxLines (range), options.pattern+contextLines (grep), options.method (get_method), options.knownHash (read|range|get_method|list — ZERO tokens if unchanged — always use), options.force (override >200-line refusal), options.compact (minimal response), options.className (structure filter).
action=list returns listing_hash. Pass as options.knownHash to get {unchanged:true} (~15 tokens) when directory is unmodified.
action=multi_grep: grep one pattern across options.paths[] in one call — returns only files with matches.
All read actions return file_content_hash. Use options.expectedHash on writes to guard drift.''' : '''\
Read files/directories.
Actions: read|head|tail|range|grep|multi|info|summary|stat|exists|project_root|allowed_dirs|normalize|diff|checksum|list|structure|get_method|chunk_read|finalise_read|help
- read: full content. >200 lines refused — use structure/get_method/range. force=true overrides. knownHash=<hash> returns {unchanged:true} instantly — ZERO tokens.
- head/tail: first/last N lines (default 50). options.lines=N.
- range: line slice. options.startLine (1-indexed), options.maxLines (default 100). knownHash=<file_content_hash> returns {unchanged:true} if file unchanged — ZERO tokens. Always pass after first read.
- grep: regex in FILE (not dir). options.pattern required. options.contextLines for surrounding lines.
- multi_grep: grep one pattern across multiple files. options.paths[] (max 20), options.pattern required, options.maxMatches (default 5 per file). Returns only files with matches. No path param needed.
- multi: up to 10 files parallel. options.paths[]. options.knownHashes {path->hash} skips unchanged. Cap: 24000 chars.
- stat: metadata only — path, exists, size, lines, lastModified, language. Cheapest existence check.
- summary: line count + size only.
- structure: code outline per entry (line/endLine/lineCount). compact=true = methods only. className=Foo filters.
- get_method: complete named method body. Preferred over structure+range for editing. knownHash=<file_content_hash> returns {unchanged:true} if file unchanged — ZERO tokens. Always pass after first read.
- list: directory listing [{name,type,size,lastModified}]. Dirs first, alpha sorted. Returns listing_hash. Pass as options.knownHash on repeat calls — returns {unchanged:true, listing_hash, count} (~15 tokens) when directory unmodified.
- help: detailed usage guide. options.topic=<tool|all>.
- chunk_read/finalise_read: chunked large-file paging.
All read actions return file_content_hash (12-char SHA-256). Pass as options.expectedHash on writes.
KNOWNHASH PATTERN: (1) bootstrap result has working_file_hashes[path].hash for prior-session files. (2) Every read response returns file_content_hash — capture it. (3) Pass as options.knownHash on ALL subsequent reads of the same file. Unchanged = {unchanged:true} = ZERO tokens.''',
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
                                  knownHash   : [type: 'string',  description: 'Pass file_content_hash from prior read. Supported on read|range|get_method|list. File unchanged = {unchanged:true}, ZERO tokens. Source: (1) bootstrap working_file_hashes[path].hash, (2) file_content_hash field of any read response. Chain within session.'],
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
                    if (contextServerClient != null) {
                        String fileHash = options.get('knownFileHash') as String
                        if (!fileHash) fileHash = options.get('knownHash') as String
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
