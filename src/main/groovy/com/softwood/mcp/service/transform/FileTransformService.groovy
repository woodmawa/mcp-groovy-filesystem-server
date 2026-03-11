package com.softwood.mcp.service.transform

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.write.WriteUtils
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.nio.file.Paths

/**
 * FileTransformService — orchestrator for server-side named transformations.
 *
 * Wired into FileWriteService as the handler for action=server_transform.
 * File content never crosses the MCP context boundary; only status + new hash is returned.
 *
 * Lifecycle per call:
 *   1. validateWriteEnabled() — honours mcp.filesystem.enable-write flag
 *   2. Validate path via AbstractFileService.validateFilePath()
 *   3. Enforce expectedHash (mandatory — file not read by Claude, drift undetectable)
 *   4. Dispatch to registered FileTransformer implementation
 *   5. Return {success, content_hash, lines_affected, message} or JSON-RPC error with hint
 *
 * Cache invalidation and context-server notification are handled by
 * FileWriteService.handleToolCall() after this method returns (server_transform is
 * included in MUTATING_ACTIONS).
 *
 * v0.8.2
 */
@Service
@Slf4j
@CompileStatic
class FileTransformService extends AbstractFileService {

    @Autowired
    List<FileTransformer> transformers

    private Map<String, FileTransformer> registry = new LinkedHashMap<String, FileTransformer>()

    FileTransformService(PathService pathService) {
        super(pathService)
    }

    @PostConstruct
    void initRegistry() {
        for (FileTransformer t : transformers) {
            registry[t.name] = t
        }
        log.info('FileTransformService: registered {} transforms: {}',
            registry.size(), (registry.keySet() as List<String>).sort().join(', '))
    }

    McpResponse applyTransform(String path, Map<String, Object> options, Object requestId) {
        try {
            validateWriteEnabled()

            String transformName = options.transform as String
            String expectedHash  = options.expectedHash as String

            if (!expectedHash) {
                return McpResponse.error(requestId, -32602,
                    'expectedHash is required for server_transform — Claude has not read the file ' +
                    'content, so drift cannot be detected without it. ' +
                    'Read the file first (file_read action=structure or action=read) to obtain its content_hash.')
            }

            String normalized
            try {
                normalized = validateFilePath(path)
            } catch (SecurityException e) {
                return McpResponse.error(requestId, -32603, "Path not allowed: ${sanitize(e.message)}")
            } catch (FileNotFoundException e) {
                return McpResponse.error(requestId, -32602, sanitize(e.message))
            }

            String actualHash = WriteUtils.fileHash(Paths.get(normalized))
            if (actualHash != expectedHash) {
                return McpResponse.error(requestId, -32602,
                    "Hash mismatch — file changed since last read. " +
                    "Re-read the file to get the current content_hash, then retry. " +
                    "(expected=${expectedHash}, actual=${actualHash})")
            }

            if (!transformName) {
                String available = (registry.keySet() as List<String>).sort().join(', ')
                return McpResponse.error(requestId, -32602,
                    "options.transform is required. Available transforms: ${available}")
            }

            FileTransformer transformer = registry[transformName]
            if (!transformer) {
                String available = (registry.keySet() as List<String>).sort().join(', ')
                return McpResponse.error(requestId, -32602,
                    "Unknown transform '${sanitize(transformName)}'. Available: ${available}")
            }

            TransformResult result = transformer.apply(normalized, options)

            if (!result.success) {
                String msg = result.hint
                    ? "${result.error}\nHint: ${result.hint}"
                    : result.error
                return McpResponse.error(requestId, -32602, msg)
            }

            String newHash = WriteUtils.fileHash(Paths.get(normalized))
            return McpResponse.success(requestId, ([
                success       : true,
                content_hash  : newHash,
                lines_affected: result.linesAffected,
                message       : result.message
            ] as Map<String, Object>))

        } catch (Exception e) {
            log.error('server_transform error: {}', e.message)
            return McpResponse.error(requestId, -32603, sanitize(e.message ?: e.class.simpleName))
        }
    }
}
