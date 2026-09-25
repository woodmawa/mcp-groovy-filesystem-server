package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * FileLifecycleService — handles the file_lifecycle tool.
 *
 * actions: create | delete | copy | move | rename | touch
 *
 * v0.0.7 — Phase 2 Core File Tools
 */
@Service
@Slf4j
@CompileStatic
class FileLifecycleService extends AbstractFileService implements ToolHandler {

    FileLifecycleService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : 'file_lifecycle',
            description: '''\
File/directory operations. Actions: create|delete|copy|move|rename|touch.
- create(path, options.type=file|directory, options.mkdirs=true): create file or directory
- delete(path, options.recursive=true): delete file; directory requires recursive=true
- copy/move/rename(path, dst, options.overwrite=false, options.mkdirs=true): copy or move/rename
- touch(path): update mtime or create if missing
dst required for copy/move/rename. All actions: options.verbose=true for full response (default: compact success-only).''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string', enum: ['create', 'delete', 'copy', 'move', 'rename', 'touch'],
                              description: 'Operation to perform'],
                    path   : [type: 'string', description: 'Source path (or target for create/touch)'],
                    dst    : [type: 'string', description: 'Destination path (required for copy/move/rename)'],
                    options: [type: 'object', description: 'Optional flags: recursive (bool), overwrite (bool), mkdirs (bool), type (file|directory), verbose (bool)',
                              properties: [
                                  recursive: [type: 'boolean'],
                                  overwrite: [type: 'boolean'],
                                  mkdirs   : [type: 'boolean'],
                                  type     : [type: 'string', enum: ['file', 'directory']],
                                  verbose  : [type: 'boolean', description: 'Set true for full response with action/path echo. Default: compact (success only).']
                              ]]
                ],
                required  : ['action', 'path']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) {
        return toolName == 'file_lifecycle'
    }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action  = arguments.action as String
            String path    = arguments.path as String
            String dst     = arguments.dst as String
            Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>

            switch (action) {
                case 'create' : return doCreate(path, options, requestId)
                case 'delete' : return doDelete(path, options, requestId)
                case 'copy'   : return doCopy(path, dst, options, requestId)
                case 'move'   : return doMove(path, dst, options, requestId)
                case 'rename' : return doMove(path, dst, options, requestId)  // rename = move within same dir
                case 'touch'  : return doTouch(path, options, requestId)
                default:
                    return McpResponse.toolError(requestId, "Unknown file_lifecycle action: ${action}")
            }
        } catch (SecurityException e) {
            log.warn("Security violation in file_lifecycle: {}", sanitize(e.message))
            return McpResponse.toolError(requestId, "Security error: ${sanitize(e.message)}")
        } catch (Exception e) {
            log.error("file_lifecycle error: {}", sanitize(e.message))
            return McpResponse.toolError(requestId, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    /**
     * FS 0.9.50: read an optional boolean flag with a real default.
     *
     * `options.mkdirs as boolean ?: true` looks like a default and is not one: `false as
     * boolean` is false, and `false ?: true` is true, so mkdirs:false could never be honoured
     * and `overwrite as boolean ?: false` was the only variant that behaved. Absent, present
     * and explicitly-false are three different answers and this helper returns them.
     */
    private static boolean flag(Map<String, Object> options, String key, boolean dflt) {
        Object v = options?.get(key)
        if (v == null) return dflt
        if (v instanceof Boolean) return (boolean) v
        return Boolean.parseBoolean(v.toString())
    }

    /**
     * FS 0.9.50: create the destination's parent when asked, and say so when refused.
     * Compared with `!= null` on purpose -- Groovy truth on a Path is Files.exists.
     */
    private static void ensureParent(Path target, boolean mkdirs) {
        Path parent = target.parent
        if (parent == null) return
        if (Files.isDirectory(parent)) return
        if (mkdirs) {
            Files.createDirectories(parent)
        } else {
            throw new FileNotFoundException("Destination parent does not exist and options.mkdirs=false: ${parent}")
        }
    }

    private McpResponse doCreate(String path, Map<String, Object> options, Object requestId) {
        validateWriteEnabled()
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")

        String type   = options.type as String ?: 'file'
        // Default true, as the tool description on the schema has always said ("options.mkdirs=true").
        boolean mkdirs = flag(options, 'mkdirs', true)
        Path target   = Paths.get(normalized)

        if (mkdirs || type == 'directory') {
            Files.createDirectories(type == 'directory' ? target : target.parent)
        }

        if (type == 'directory') {
            Files.createDirectories(target)
            log.info("Created directory: {}", normalized)
            if (isWriteCompact(options)) return textResponse(requestId, [success: true, type: 'directory'])
            return textResponse(requestId, [action: 'create', type: 'directory', path: normalized, success: true])
        } else {
            // Audit 2026-09-25 F1: this ran unconditionally, so mkdirs=false was ignored on create.
            ensureParent(target, mkdirs)
            if (!Files.exists(target)) Files.createFile(target)
            log.info("Created file: {}", normalized)
            if (isWriteCompact(options)) return textResponse(requestId, [success: true, type: 'file'])
            return textResponse(requestId, [action: 'create', type: 'file', path: normalized, success: true])
        }
    }

    private McpResponse doDelete(String path, Map<String, Object> options, Object requestId) {
        validateWriteEnabled()
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")

        Path target       = Paths.get(normalized)
        boolean recursive = options.recursive as boolean ?: false

        if (!Files.exists(target)) {
            return McpResponse.toolError(requestId, "Path does not exist: ${sanitize(normalized)}")
        }

        if (Files.isDirectory(target)) {
            if (!recursive) throw new IllegalArgumentException("Cannot delete directory without recursive=true: ${normalized}")
            deleteRecursive(target)
        } else {
            Files.delete(target)
        }

        log.info("Deleted: {}", normalized)
        if (isWriteCompact(options)) return textResponse(requestId, [success: true])
        return textResponse(requestId, [action: 'delete', path: normalized, success: true])
    }

    private McpResponse doCopy(String src, String dst, Map<String, Object> options, Object requestId) {
        validateWriteEnabled()
        if (!dst) throw new IllegalArgumentException("dst is required for copy")

        String normSrc  = pathService.normalizePath(src)
        String normDst  = pathService.normalizePath(dst)
        if (!isPathAllowed(normSrc)) throw new SecurityException("Source path not allowed: ${sanitize(normSrc)}")
        if (!isPathAllowed(normDst)) throw new SecurityException("Destination path not allowed: ${sanitize(normDst)}")

        Path srcPath      = Paths.get(normSrc)
        Path dstPath      = Paths.get(normDst)
        boolean overwrite = flag(options, 'overwrite', false)
        boolean mkdirs    = flag(options, 'mkdirs', true)

        if (!Files.exists(srcPath)) throw new FileNotFoundException("Source not found: ${normSrc}")
        // FS 0.9.50: `dstPath.parent && mkdirs` was the defect. Groovy truth on a java.nio.file.Path
        // is Files.exists(path) (NioExtensions.asBoolean), so the condition read "create the
        // parent only if it already exists" and copy into a missing folder failed with a bare
        // `src -> dst` NoSuchFileException. Found 2026-09-22 (FileLifecycleMkdirsSpec).
        ensureParent(dstPath, mkdirs)

        if (overwrite) {
            Files.copy(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.copy(srcPath, dstPath)
        }

        log.info("Copied {} -> {}", normSrc, normDst)
        if (isWriteCompact(options)) return textResponse(requestId, [success: true])
        return textResponse(requestId, [action: 'copy', src: normSrc, dst: normDst, success: true])
    }

    private McpResponse doMove(String src, String dst, Map<String, Object> options, Object requestId) {
        validateWriteEnabled()
        if (!dst) throw new IllegalArgumentException("dst is required for move/rename")

        String normSrc  = pathService.normalizePath(src)
        String normDst  = pathService.normalizePath(dst)
        if (!isPathAllowed(normSrc)) throw new SecurityException("Source path not allowed: ${sanitize(normSrc)}")
        if (!isPathAllowed(normDst)) throw new SecurityException("Destination path not allowed: ${sanitize(normDst)}")

        Path srcPath      = Paths.get(normSrc)
        Path dstPath      = Paths.get(normDst)
        boolean overwrite = flag(options, 'overwrite', false)
        boolean mkdirs    = flag(options, 'mkdirs', true)

        if (!Files.exists(srcPath)) throw new FileNotFoundException("Source not found: ${normSrc}")
        ensureParent(dstPath, mkdirs)   // FS 0.9.50, see doCopy

        if (overwrite) {
            Files.move(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(srcPath, dstPath)
        }

        log.info("Moved {} -> {}", normSrc, normDst)
        if (isWriteCompact(options)) return textResponse(requestId, [success: true])
        return textResponse(requestId, [action: 'move', src: normSrc, dst: normDst, success: true])
    }

    private McpResponse doTouch(String path, Map<String, Object> options, Object requestId) {
        validateWriteEnabled()
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")

        Path target = Paths.get(normalized)
        boolean mkdirs = flag(options, 'mkdirs', true)

        ensureParent(target, mkdirs)   // FS 0.9.50, see doCopy

        if (Files.exists(target)) {
            // Update last-modified time
            target.toFile().setLastModified(System.currentTimeMillis())
        } else {
            Files.createFile(target)
        }

        log.debug("Touched: {}", normalized)
        if (isWriteCompact(options)) return textResponse(requestId, [success: true])
        return textResponse(requestId, [action: 'touch', path: normalized, success: true])
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void deleteRecursive(Path dir) {
        // Fix: wrap in withCloseable to ensure stream is closed after iteration
        (Files.walk(dir) as java.util.stream.Stream<Path>)
            .withCloseable { java.util.stream.Stream<Path> stream ->
                stream.sorted(Comparator.reverseOrder()).each { Path p -> Files.delete(p) }
            }
    }
}
