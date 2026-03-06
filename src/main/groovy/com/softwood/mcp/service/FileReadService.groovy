package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.read.FileContentReader
import com.softwood.mcp.service.read.FileMetaReader
import com.softwood.mcp.service.read.FileStructureReader
import com.softwood.mcp.service.read.ReadResponseHelper
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
    @Autowired ContextServerClient  contextServerClient

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
            description: '''\
SESSION START SEQUENCE (every conversation, in order):
  0. context_lifecycle action=start  (ALWAYS FIRST - auto-generates session ID)
  1. context_read scope=project action=context groupId=<group>  (stable tier, prompt cache target)
  2. context_read scope=session action=resume  (dynamic delta <150 tokens)

Read files and query filesystem metadata. Actions:
- read(path): full file content. FILES >60KB AUTO-CHUNK. REFUSED if file >200 lines (use structure/get_method/range instead). options.force=true overrides refusal. options.knownHash=<hash> returns {unchanged:true} instantly if file unchanged - USE THIS on any re-read within same session.
- head(path, options.lines=50): first N lines
- tail(path, options.lines=50): last N lines
- range(path, options.startLine, options.maxLines=100): line slice, 1-indexed
- grep(path, options.pattern, options.maxMatches=10, options.contextLines=0): regex matches; FILE path only, NOT directory. set contextLines>0 for before/after context
- multi(options.paths[]): read up to 10 files in parallel.
  CONTEXT-EFFICIENCY: pass options.knownHashes {"path"->"12-char-hash"} from prior reads.
  Server returns {unchanged:true, file_content_hash} for any file whose hash still matches.
  NOTE: aggregate content capped at 24000 chars (~6K tokens) across all files. Use only for small files.
  NOTE: Prefer get_method/range/grep over multi for targeted lookups within files already read.
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
CRITICAL CONTEXT EFFICIENCY - follow these rules to avoid session resets:
  1. NEVER use action=read on files you have already read this session unless you know they changed.
  2. For editing: use structure -> get_method (NOT read of whole file).
  3. For searching: use grep or file_search (NOT read then scan).
  4. Use summary first on unknown files to check size before reading.
  5. Use knownHashes on multi to skip unchanged files entirely.
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
                                  knownHashes : [type: 'object', description: 'Map of {path -> 12-char hash} from prior reads. Files whose hash still matches return {unchanged:true, file_content_hash} with no content - saves tokens on re-reads.'],
                                  compareTo   : [type: 'string',  description: 'Second file for diff (required for diff)'],
                                  algorithm   : [type: 'string',  description: 'Checksum: MD5|SHA-256 (default SHA-256)'],
                                  sessionId   : [type: 'string',  description: 'Session ID (required for chunk_read, finalise_read)'],
                                  chunkIndex  : [type: 'integer', description: 'Chunk index 0-based (required for chunk_read)'],
                                  compact     : [type: 'boolean', description: 'Minimal response - omits action/path echo, returns content+hash only. Supported by read, head, tail, range, grep, structure (structure: methods only, no endLine)'],
                                  knownHash   : [type: 'string',  description: 'Pass file_content_hash from a previous read of this file. If file unchanged, returns {unchanged:true, file_content_hash} with NO content - saves all tokens. Use on every re-read.'],
                                  force       : [type: 'boolean', description: 'Pass force=true to override the >200 line refusal on action=read. Only use when you genuinely need the full file content.']
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
                    McpResponse r = contentReader.doRange(path, options, requestId)
                    if (r.error == null) fireRegistryUpsert(path, r)
                    return r
                }
                case 'grep'         : return contentReader.doGrep(path, options, requestId)
                case 'multi'        : return contentReader.doMulti(options, requestId)
                case 'info'         : return metaReader.doInfo(path, requestId)
                case 'summary'      : return metaReader.doSummary(path, requestId)
                case 'exists'       : return metaReader.doExists(path, requestId)
                case 'project_root' : return metaReader.doProjectRoot(requestId)
                case 'allowed_dirs' : return metaReader.doAllowedDirs(requestId)
                case 'normalize'    : return metaReader.doNormalize(path, requestId)
                case 'diff'         : return metaReader.doDiff(path, options, requestId)
                case 'checksum'     : return metaReader.doChecksum(path, options, requestId)
                case 'structure'    : return structureReader.doStructure(path, options, requestId)
                case 'get_method'   : return structureReader.doGetMethod(path, options, requestId)
                case 'chunk_read'   : return responseHelper.doChunkRead(options, requestId)
                case 'finalise_read': return responseHelper.doFinaliseRead(options, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown file_read action: ${action}")
            }
        } catch (SecurityException e) {
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (FileNotFoundException e) {
            return McpResponse.error(requestId, -32602, sanitize(e.message))
        } catch (Exception e) {
            log.error('file_read error: {}', sanitize(e.message), e)
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    private void fireRegistryUpsert(String path, McpResponse resp) {
        if (!path || resp.result == null) return
        try {
            String hash = extractFileHash(resp)
            if (!hash) return
            String np = pathService.normalizePath(path)
            contextServerClient.upsertFileRegistryAsync(np, hash, 0, new File(np).lastModified())
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
}
