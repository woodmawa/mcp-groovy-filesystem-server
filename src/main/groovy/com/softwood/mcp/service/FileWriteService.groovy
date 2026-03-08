package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.write.FileChunkWriter
import com.softwood.mcp.service.write.FileContentWriter
import com.softwood.mcp.service.write.FilePatchService
import com.softwood.mcp.service.write.FileReplaceService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * FileWriteService - ToolHandler entry point for the file_write tool.
 *
 * Thin dispatcher: owns getToolDefinitions/canHandle/handleToolCall/promoteTopLevelParams only.
 * All action implementations live in the service/write/ subpackage:
 *   - FileContentWriter : write, append
 *   - FileReplaceService: replace, multi_replace
 *   - FilePatchService  : patch
 *   - FileChunkWriter   : chunk_write, finalise_write, abort_write
 *   - WriteUtils        : atomicWrite, makeBackup, shouldNormaliseLf, computeHash, fileHash, countOccurrences
 *
 * v0.7.44 - refactored to dispatch-only; implementations split to write/ subpackage.
 */
@Service
@Slf4j
@CompileStatic
class FileWriteService extends AbstractFileService implements ToolHandler {

    @Autowired FileContentWriter  contentWriter
    @Autowired FileReplaceService replaceService
    @Autowired FilePatchService   patchService
    @Autowired FileChunkWriter    chunkWriter
    @Autowired StructureCache     structureCache
    @Autowired(required = false) ContextServerClient contextServerClient

    private static final Set<String> MUTATING_ACTIONS =
        ['write', 'append', 'replace', 'patch', 'multi_replace', 'finalise_write'] as Set

    FileWriteService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        String root      = pathService.activeProjectRoot?.replace('\\', '/') ?: ''
        String skillPath = root ? (root + '/skills/SKILL.md') : 'skills/SKILL.md'
        return [[\
            name       : 'file_write',
            description: ("""\
Write, append, or modify file content. Actions:
- write(path, content): overwrite entire file
- append(path, content): append to end
- replace(path, options): replace ONE unique string. REQUIRED params IN options object: options.oldText (string to find, must appear exactly once), options.newText (replacement). Do NOT pass oldText/newText at top level \u2014 they must be inside the options object. CRITICAL: always check response for success:false or error field - not-found returns McpError with nearest_match hint; duplicate returns line numbers. NOTE: prefer patch for multi-line replacements.
- patch(path, options.replacements[]): line-range edits [{startLine,endLine,newText}], 1-indexed, both startLine AND endLine required. ALWAYS read exact lines first.
- multi_replace(path, options.replacements[]): ordered [{oldText,newText}] swaps. Pre-validates ALL before writing.
- chunk_write/finalise_write/abort_write: chunked large-file writes (see options).
All mutating actions return content_hash. Pass options.expectedHash to reject edits if file changed since last read.

SAFE EDITING: always pass expectedHash (hash from last read). For targeted code edits: get_method -> patch. For unique string replacements: grep to confirm uniqueness -> replace. For multiple changes to same file: multi_replace. NEVER sequential replace calls without re-reading.
PATCH SAFETY: get_method/range IMMEDIATELY before every patch. Verify startLine/endLine covers the ENTIRE block including closing braces. After any patch, re-read before the next patch (line numbers shift). Never patch from memory.
CRITICAL: replace failure (oldText not found) returns a JSON-RPC error - STOP and read the error detail before retrying. Do NOT fall through to patch silently.
SKILL: For worked examples read:
  file_read action=read path=${skillPath}""").toString(),
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['write', 'append', 'replace', 'patch', 'multi_replace',
                                     'chunk_write', 'finalise_write', 'abort_write']],
                    path   : [type: 'string', description: 'Target file path (required for all actions except abort_write)'],
                    content: [type: 'string', description: 'Content for write/append/chunk_write'],
                    options: [type: 'object', description: 'Action-specific options',
                              properties: [
                                  encoding    : [type: 'string',  description: 'File encoding (default UTF-8)'],
                                  backup      : [type: 'boolean', description: 'Create .backup file before writing (default false)'],
                                  expectedHash: [type: 'string',  description: 'Optional 12-char SHA-256 prefix from a prior read/write content_hash. Rejects the edit if the file has changed since last read.'],
                                  mkdirs      : [type: 'boolean', description: 'Create parent dirs if needed (default true)'],
                                  sessionId   : [type: 'string',  description: 'Chunk session ID (required for chunk_write, finalise_write, abort_write)'],
                                  chunkIndex  : [type: 'integer', description: 'Chunk index 0-based (required for chunk_write)'],
                                  totalChunks : [type: 'integer', description: 'Total chunks (required for finalise_write)'],
                                  oldText     : [type: 'string',  description: 'Unique string to replace (required for replace - must appear exactly once)'],
                                  newText     : [type: 'string',  description: 'Replacement string (required for replace)'],
                                  replacements: [type: 'array',
                                                 description: 'patch: [{startLine,endLine,newText}] 1-indexed; multi_replace: [{oldText,newText}]',
                                                 items: [type: 'object', properties: [
                                                     oldText  : [type: 'string'],
                                                     newText  : [type: 'string'],
                                                     startLine: [type: 'integer'],
                                                     endLine  : [type: 'integer']
                                                 ]]],
                                  compact     : [type: 'boolean', description: 'Minimal response - returns only success+content_hash (default: true for all write actions)'],
                                  verbose     : [type: 'boolean', description: 'Set verbose:true to get full response with action/path/size/diagnostics (overrides compact default)']
                              ]]
                ],
                required  : ['action', 'path']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == 'file_write' }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            validateWriteEnabled()

            String action               = arguments.action as String
            String path                 = (arguments.path ?: (arguments.options instanceof Map ? (arguments.options as Map).path : null)) as String
            String content              = arguments.content as String
            Map<String, Object> options = normaliseOptions(arguments.options)

            options = promoteTopLevelParams(action, arguments, options)

            // Guard: all actions except abort_write require a valid path
            if (!path && action != 'abort_write') {
                return McpResponse.error(requestId, -32602,
                    "file_write '${action}' requires a 'path' parameter (received null). " +
                    "Ensure 'path' is at the top level of the arguments object, not nested inside options.")
            }

            McpResponse response
            switch (action) {
                case 'write'         : response = contentWriter.doWrite(path, content, options, requestId); break
                case 'append'        : response = contentWriter.doAppend(path, content, options, requestId); break
                case 'replace'       : response = replaceService.doReplace(path, options, requestId); break
                case 'patch'         : response = patchService.doPatch(path, content, options, requestId); break
                case 'multi_replace' : response = replaceService.doMultiReplace(path, options, requestId); break
                case 'chunk_write'   : response = chunkWriter.doChunkWrite(path, content, options, requestId); break
                case 'finalise_write': response = chunkWriter.doFinaliseWrite(path, options, requestId); break
                case 'abort_write'   : return chunkWriter.doAbortWrite(options, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown file_write action: ${action}")
            }
            // Invalidate structure cache after any successful mutating action
            if (path && MUTATING_ACTIONS.contains(action) && response.error == null) {
                try { structureCache.invalidate(pathService.normalizePath(path)) } catch (Exception ignored) {}
                // Fire-and-forget registry upsert so context server tracks the new hash
                try {
                    String hash = extractFileHash(response)
                    if (hash && contextServerClient != null) {
                        String np = pathService.normalizePath(path)
                        contextServerClient.upsertFileRegistryAsync(np, hash, 0, new File(np).lastModified())
                        // Re-index ontology for source files so symbols stay current after writes
                        if (np.endsWith('.groovy') || np.endsWith('.java')) {
                            contextServerClient.reindexFileAsync(np)
                        }
                    }
                } catch (Exception ignored) {}
            }
            return response

        } catch (SecurityException e) {
            log.warn("Security violation in file_write: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (java.nio.file.NoSuchFileException e) {
            String msg = e.reason ?: "Path not found: ${e.file}"
            log.warn("file_write bad path: {}", msg)
            return McpResponse.error(requestId, -32602, msg)
        } catch (Exception e) {
            log.error("file_write error: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Parameter aliasing: promote top-level params into options
    // -----------------------------------------------------------------------

    private static Map<String, Object> promoteTopLevelParams(
            String action, Map<String, Object> arguments, Map<String, Object> options) {
        Map<String, Object> merged = null

        // Promote expectedHash for ALL mutating actions — Claude often sends it at top level
        if (!options.expectedHash && arguments.expectedHash) {
            merged = new HashMap<String, Object>(options)
            merged.expectedHash = arguments.expectedHash
            log.debug('{}: promoted top-level expectedHash into options', action)
        }

        switch (action) {
            case 'replace':
                if (!options.oldText && !options.old_str) {
                    String topOld = (arguments.oldText ?: arguments.old_str) as String
                    if (topOld) {
                        merged = new HashMap<String, Object>(options)
                        merged.oldText = topOld
                        String topNew = (arguments.newText ?: arguments.new_str) as String
                        if (topNew != null) merged.newText = topNew
                        boolean isSnake = arguments.old_str != null
                        log.debug('replace: promoted top-level {}/*{} into options (variant: top-level {})',
                            isSnake ? 'old_str' : 'oldText', isSnake ? 'new_str' : 'newText',
                            isSnake ? 'snake_case' : 'camelCase')
                    }
                } else if (options.oldText) {
                    log.debug('replace: variant=options.oldText (correct nested form)')
                }
                if (!options.oldText && options.old_str) {
                    merged = merged ?: new HashMap<String, Object>(options)
                    merged.oldText = merged.old_str
                    if (!merged.newText && merged.new_str != null) merged.newText = merged.new_str
                    log.debug('replace: normalised options.old_str -> oldText (variant: snake_case nested)')
                }
                break

            case 'patch':
            case 'multi_replace':
                if (!options.replacements && arguments.replacements instanceof List) {
                    merged = new HashMap<String, Object>(options)
                    merged.replacements = arguments.replacements
                    log.debug('{}: promoted top-level replacements into options', action)
                }
                break
        }
        return merged ?: options
    }

    private static String extractFileHash(McpResponse resp) {
        try {
            if (resp.result == null) return null
            List content = resp.result.get('content') as List
            if (!content) return null
            String text = (content[0] as Map)?.get('text') as String
            if (!text) return null
            Map data = (Map) new groovy.json.JsonSlurper().parseText(text)
            return data?.get('content_hash') as String ?: data?.get('file_content_hash') as String
        } catch (Exception ignored) { return null }
    }
}
