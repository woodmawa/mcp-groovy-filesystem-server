package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.write.FileChunkWriter
import com.softwood.mcp.service.write.FileContentWriter
import com.softwood.mcp.service.write.FilePatchService
import com.softwood.mcp.service.transform.FileTransformService
import com.softwood.mcp.service.write.FileReplaceService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * FileWriteService - ToolHandler entry point for the file_write tool.
 *
 * Thin dispatcher: owns getToolDefinitions/canHandle/handleToolCall/promoteTopLevelParams only.
 * All action implementations live in the service/write/ subpackage:
 *   - FileContentWriter    : write, append
 *   - FileReplaceService   : replace, multi_replace  (uses WriteContext + WriteCommitter + TextMatcher)
 *   - FilePatchService     : patch                   (uses WriteContext + WriteCommitter + StructuralGuard)
 *   - FileChunkWriter      : chunk_write, finalise_write, abort_write
 *   - FileTransformService : server_transform
 *   - WriteContext         : per-call file load (size cap, encoding guard, CRLF detect, hash)
 *   - WriteCommitter       : pre-commit drift re-check + atomic write delegation
 *   - TextMatcher          : Unicode-safe oldText resolution with original-span mapping
 *   - StructuralGuard      : brace/paren delta pre-write hard rejects for code files
 *   - DestructiveChangeGuard: ratio guard across all mutating actions
 *   - MultiReplaceValidator : multi_replace pre-validation (uniqueness, overlap, simulation)
 *   - WriteUtils           : atomicWrite, makeBackup, shouldNormaliseLf, computeHash
 *
 * v0.7.44 - refactored to dispatch-only; implementations split to write/ subpackage.
 * v0.9.0  - dispatcher catches InvalidOptionsException from normaliseOptions.
 */
@Service
@Slf4j
@CompileStatic
class FileWriteService extends AbstractFileService implements ToolHandler {

    @Autowired FileContentWriter  contentWriter
    @Autowired FileReplaceService replaceService
    @Autowired FilePatchService   patchService
    @Autowired FileChunkWriter    chunkWriter
    @Autowired FileTransformService fileTransformService
    @Autowired StructureCache     structureCache
    @Autowired(required = false) ContextServerClient contextServerClient
    @Autowired com.softwood.mcp.service.office.OfficeDocumentHandler officeHandler

    private static final Set<String> MUTATING_ACTIONS =
        ['write', 'append', 'replace', 'patch', 'multi_replace', 'finalise_write', 'server_transform', 'write_office'] as Set

    // v0.8.74: DB-driven tool description -- loaded from CS help_sections at startup.
    // section_key='tool_desc_file_write' for compact, 'tool_desc_file_write_verbose' for verbose.
    // Falls back to DEFAULT_DESC_* if CS unreachable. Update without rebuild via:
    //   context_write scope=help type=section action=update section_key=tool_desc_file_write content=<new>
    private String toolDescriptionCompact
    private String toolDescriptionVerbose

    private static final String DEFAULT_DESC_COMPACT = '''\
Write/modify files.
Actions: write|append|replace|patch|multi_replace|server_transform|chunk_write|finalise_write|abort_write|chunk_status
Key params: path (top-level, not in options), content (write/append), options.oldText+newText (replace), options.replacements (patch/multi_replace), options.transform+expectedHash (server_transform), options.expectedHash (all mutating — required).
server_transform transforms: replace_section|replace_method|replace_between|insert_before_match|insert_after_heading|append_section|add_method|add_import'''

    private static final String DEFAULT_DESC_VERBOSE = '''\
Write/modify files.
Actions: write|append|replace|patch|multi_replace|server_transform|chunk_write|finalise_write|abort_write|chunk_status
- write(path, content): overwrite entire file
- append(path, content): append to end
- replace: ONE unique string swap. options.oldText+newText (inside options). Fails if not found or duplicated — check error detail.
- patch: line-range edits. options.replacements=[{startLine,endLine,newText}] 1-indexed. ALWAYS re-read target lines immediately before each patch (line numbers shift after every edit). For multi-section changes pass ALL replacements in a single patch call -- never sequential patches across turns. After any structural Groovy edit (new method/brace changes) run compileGroovy before continuing. On compile failure: git checkout HEAD -- <file> and start over with one clean patch.
- multi_replace: ordered [{oldText,newText}]. Pre-validates all before writing. Preferred for multiple text swaps in one file. Does NOT shift line numbers. Use instead of sequential patch calls where possible.
- server_transform: server-side transform — file never crosses context boundary. REQUIRED: options.expectedHash. options.transform: replace_section|replace_method|replace_between|insert_before_match|insert_after_heading|append_section|add_method|add_import
- chunk_write/finalise_write/abort_write: large-file chunked writes. chunk_status: verify received chunks before finalise.
All mutating actions return content_hash. options.expectedHash is MANDATORY for replace|patch|multi_replace -- read the file first and pass the returned file_content_hash. Missing expectedHash is a hard error (CT-EH-1, FS 0.8.73).
SAFE EDITING: always read before write, always pass expectedHash. get_method -> patch for code. grep -> replace for unique strings. multi_replace for multiple changes.
CRITICAL: replace failure returns JSON-RPC error with nearest_match hint -- read it before retrying. Do NOT fall through to patch.'''

    FileWriteService(PathService pathService) {
        super(pathService)
    }

    @PostConstruct
    void init() {
        // CS HTTP companion may not be ready immediately at DT startup -- the companion
        // is spawned as a child process by ServerLifecycleService.autoStartHttpCompanions()
        // which returns after fork, before :8082 is actually listening.
        // DT cold-start launches all servers in parallel -- CS :8082 typically needs 2-4s.
        // 5 attempts at 0/500/1000/2000/3000ms = max ~6.5s wait before falling back to DEFAULT_DESC.
        // Falls back to DEFAULT_DESC_* if all attempts fail (CS unreachable or missing row).
        int[] delays = [0, 500, 1000, 2000, 3000]
        for (int i = 0; i < delays.length; i++) {
            if (delays[i] > 0) {
                try { Thread.sleep(delays[i]) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
            }
            try {
                String compact = contextServerClient?.getHelpSection('tool_desc_file_write')
                String verbose = contextServerClient?.getHelpSection('tool_desc_file_write_verbose')
                if (compact && verbose) {
                    toolDescriptionCompact = compact
                    toolDescriptionVerbose = verbose
                    log.debug('FileWriteService: loaded tool descriptions from CS help_sections (attempt {})', i + 1)
                    return
                }
                log.debug('FileWriteService: CS section(s) missing on attempt {} -- retrying', i + 1)
            } catch (Exception e) {
                log.debug('FileWriteService.init attempt {} failed (non-fatal): {}', i + 1, e.message)
            }
        }
        // All retries exhausted -- use baked-in defaults
        if (!toolDescriptionCompact) toolDescriptionCompact = DEFAULT_DESC_COMPACT
        if (!toolDescriptionVerbose) toolDescriptionVerbose = DEFAULT_DESC_VERBOSE
        log.debug('FileWriteService: CS unavailable after retries -- using DEFAULT_DESC fallback')
    }

    /**
     * Called by ServerLifecycleService after HTTP companions are confirmed up.
     * Gives FileWriteService a second chance to load descriptions from CS
     * (the @PostConstruct retry loop fires before CS HTTP companion exists).
     * @return true if successfully reloaded from CS, false otherwise
     */
    boolean reloadDescriptionsFromCs() {
        try {
            String compact = contextServerClient?.getHelpSection('tool_desc_file_write')
            String verbose = contextServerClient?.getHelpSection('tool_desc_file_write_verbose')
            if (compact && verbose) {
                toolDescriptionCompact = compact
                toolDescriptionVerbose = verbose
                log.debug('FileWriteService: tool descriptions reloaded from CS (post-companion-start)')
                return true
            } else if (compact) {
                toolDescriptionCompact = compact
                log.debug('FileWriteService: compact description reloaded from CS (verbose missing)')
                return true
            }
        } catch (Exception e) {
            log.debug('FileWriteService.reloadDescriptionsFromCs failed (non-fatal): {}', e.message)
        }
        return false
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[\
            name       : 'file_write',
            description: isDescriptionCompact() ? toolDescriptionCompact : toolDescriptionVerbose,
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string',
                              enum: ['write', 'append', 'replace', 'patch', 'multi_replace',
                                     'chunk_write', 'finalise_write', 'abort_write', 'chunk_status', 'server_transform',
                                     'write_office']],
                    path   : [type: 'string', description: 'Target file path (required for all actions except abort_write and chunk_status)'],
                    content: [type: 'string', description: 'Content for write/append/chunk_write (not used for write_office)'],
                    options: [type: 'object', description: 'Action-specific options. REQUIRED for replace: oldText (the unique string to find) and newText (the replacement; pass newText:\'\' to delete). For patch: replacements. For chunks: sessionId, chunkIndex, totalChunks. expectedHash mandatory for all mutating actions.',
                              properties: [
                                  encoding    : [type: 'string',  description: 'File encoding (default UTF-8)'],
                                  backup      : [type: 'boolean', description: 'Create .backup file before writing (default false)'],
                                  expectedHash: [type: 'string',  description: 'MANDATORY for replace|patch|multi_replace: 12-char SHA-256 prefix from prior read/write. Absent = hard error. Always read the file first and pass the returned file_content_hash.'],
                                  mkdirs      : [type: 'boolean', description: 'Create parent dirs if needed (default true)'],
                                  sessionId   : [type: 'string',  description: 'Chunk session ID (required for chunk_write, finalise_write, abort_write, chunk_status)'],
                                  chunkIndex  : [type: 'integer', description: 'Chunk index 0-based (required for chunk_write)'],
                                  totalChunks : [type: 'integer', description: 'Total chunks (required for finalise_write, chunk_status)'],
                                  oldText     : [type: 'string',  description: 'Unique string to replace (REQUIRED for action=replace -- must appear exactly once in the file). Not used by other actions.'],
                                  newText     : [type: 'string',  description: 'Replacement string (REQUIRED for action=replace -- key must be present; pass empty string to delete matched text). Not used by other actions.'],
                                  replacements: [type: 'array',
                                                 description: 'patch: [{startLine,endLine,newText}] 1-indexed; multi_replace: [{oldText,newText}]',
                                                 items: [type: 'object', properties: [
                                                     oldText  : [type: 'string'],
                                                     newText  : [type: 'string'],
                                                     startLine: [type: 'integer'],
                                                     endLine  : [type: 'integer']
                                                 ]]],
                                  compact     : [type: 'boolean', description: 'Minimal response (default: true for all write actions)'],
                                  verbose     : [type: 'boolean', description: 'Full response with action/path/size/diagnostics']
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
            Map<String, Object> options
            try {
                options = normaliseOptions(arguments.options)
            } catch (InvalidOptionsException e) {
                return McpResponse.toolError(requestId, e.message)
            }

            options = promoteTopLevelParams(action, arguments, options)

            // CT-FW-RG-1..3 (FS 0.9.3 / #107): pre-flight gate for action=replace.
            // Fires BEFORE dispatch so early return bypasses the post-write integrity block.
            // Transport-invariant: both STDIO and HTTP paths converge here via handler.handleToolCall().
            // Defence-in-depth: doReplace still validates, but this gate surfaces actionable errors
            // earlier and is the primary guard against the post-write side-effect leak (Bug B).
            if (action == 'replace') {
                String preOld = (options.oldText ?: options.old_str) as String
                if (!preOld) {
                    return McpResponse.toolError(requestId,
                        'action=replace: options.oldText is required. ' +
                        'Read the target section first (file_read action=range or action=get_method) ' +
                        'to get the exact text and file_content_hash, then retry with both oldText and expectedHash.')
                }
                if (!options.containsKey('newText') && !options.containsKey('new_str')) {
                    return McpResponse.toolError(requestId,
                        'action=replace: options.newText is required. ' +
                        'Pass newText:\'\' (empty string) to explicitly delete the matched text.')
                }
            }

            // Guard: all actions except abort_write require a valid path
            if (!path && action != 'abort_write' && action != 'chunk_status') {
                return McpResponse.toolError(requestId, "file_write '${action}' requires a 'path' parameter (received null). " +
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
                case 'chunk_status'  : return chunkWriter.doChunkStatus(options, requestId)
                case 'server_transform': response = fileTransformService.applyTransform(path, options, requestId); break
                case 'write_office'    : response = officeHandler.writeOffice(path, options, requestId); break
                default:
                    return McpResponse.toolError(requestId, "Unknown file_write action: ${action}")
            }
            // Invalidate structure cache after any successful mutating action.
            // CT-FW-RG-4 (FS 0.9.3): guard against toolError responses -- McpResponse.toolError()
            // is implemented as success() wrapping isError:true, so response.error == null is true
            // even for error paths. Check result.isError to prevent spurious cache/registry updates.
            boolean isToolError = (response.result as Map)?.get('isError') == true
            if (path && MUTATING_ACTIONS.contains(action) && response.error == null && !isToolError) {
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
                            // Fix F (v0.8.54): queue pending_reindex so stale_warning fires
                            // during the brief window before async reindex completes
                            contextServerClient.invalidateFileAsync(np)
                        }
                    }
                } catch (Exception ignored) {}
            }
            return response

        } catch (SecurityException e) {
            log.warn("Security violation in file_write: {}", sanitize(e.message))
            return McpResponse.toolError(requestId, "Security error: ${sanitize(e.message)}")
        } catch (java.nio.file.NoSuchFileException e) {
            String msg = e.reason ?: "Path not found: ${e.file}"
            log.warn("file_write bad path: {}", msg)
            return McpResponse.toolError(requestId, msg)
        } catch (Exception e) {
            log.error("file_write error: {}", sanitize(e.message))
            // Return as JSON-structured toolError so callers can always parseText the response
            String safeMsg = sanitize(e.message ?: e.class.simpleName)
            return McpResponse.toolError(requestId,
                new groovy.json.JsonBuilder([success: false, error: safeMsg, action: (arguments?.action ?: 'unknown') as String]).toString())
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
                        // CT-EH-1 fix: seed from merged (which may already carry promoted expectedHash),
                        // not from options directly -- prevents expectedHash being dropped when
                        // both expectedHash and oldText/newText are all at top level.
                        merged = new HashMap<String, Object>(merged ?: options)
                        merged.oldText = topOld
                        // CT-OPT-2/3 fix (FS 0.9.0): use explicit hasProperty check, not ?:
                        // The ?: operator treats '' (empty string) as falsy, swallowing deliberate
                        // content-deletion requests. arguments.newText may be '' intentionally.
                        Object rawNew = arguments.containsKey('newText') ? arguments.newText
                            : (arguments.containsKey('new_str') ? arguments.new_str : null)
                        if (rawNew != null) merged.newText = rawNew as String
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
