package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.file.*

/**
 * File write/mutation operations - writeFile, replaceInFile, appendToFile, copy, move, delete, createDir
 * PHASE 1: New replaceInFile (60-80% token savings on edits), appendToFile, auto-create parent dirs
 */
@Service
@Slf4j
@CompileStatic
class FileWriteService extends AbstractFileService implements ToolHandler {

    FileWriteService(PathService pathService) {
        super(pathService)
    }

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [
            createMap([
                name: "writeFile",
                description: "Write content to a file with optional backup",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path (Windows or WSL format)"]),
                        content: createMap([type: "string", description: "Content to write"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"]),
                        createBackup: createMap([type: "boolean", description: "Create .backup file before overwriting"])
                    ]),
                    required: ["path", "content"]
                ])
            ]),
            createMap([
                name: "replaceInFile",
                description: "Replace a unique string in a file without full read+write round-trip. oldText must appear exactly once.",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"]),
                        oldText: createMap([type: "string", description: "Text to find (must be unique in file)"]),
                        newText: createMap([type: "string", description: "Replacement text"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"]),
                        createBackup: createMap([type: "boolean", description: "Create .backup file before editing"])
                    ]),
                    required: ["path", "oldText", "newText"]
                ])
            ]),
            createMap([
                name: "appendToFile",
                description: "Append content to end of file without reading existing content. Creates file and parent dirs if needed.",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"]),
                        content: createMap([type: "string", description: "Content to append"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"])
                    ]),
                    required: ["path", "content"]
                ])
            ]),
            createMap([
                name: "copyFile",
                description: "Copy a file to a new location",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        source: createMap([type: "string", description: "Source file path"]),
                        destination: createMap([type: "string", description: "Destination file path"]),
                        overwrite: createMap([type: "boolean", description: "Overwrite if exists (default: false)"])
                    ]),
                    required: ["source", "destination"]
                ])
            ]),
            createMap([
                name: "moveFile",
                description: "Move or rename a file",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        source: createMap([type: "string", description: "Source file path"]),
                        destination: createMap([type: "string", description: "Destination file path"]),
                        overwrite: createMap([type: "boolean", description: "Overwrite if exists (default: false)"])
                    ]),
                    required: ["source", "destination"]
                ])
            ]),
            createMap([
                name: "deleteFile",
                description: "Delete a file or directory",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File or directory path"]),
                        recursive: createMap([type: "boolean", description: "Delete directory recursively"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "createDirectory",
                description: "Create a directory (including parent directories)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "Directory path to create"])
                    ]),
                    required: ["path"]
                ])
            ])
        ]
    }

    @Override
    boolean canHandle(String toolName) {
        toolName in ['writeFile', 'replaceInFile', 'appendToFile', 'copyFile', 'moveFile', 'deleteFile', 'createDirectory']
    }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> args, Object requestId) {
        switch (toolName) {
            case 'writeFile':
                def result = writeFile(args.path as String, args.content as String,
                    (args.encoding as String) ?: 'UTF-8', (args.createBackup as Boolean) ?: false)
                return textResponse(requestId, result)

            case 'replaceInFile':
                def result = replaceInFile(args.path as String, args.oldText as String, args.newText as String,
                    (args.encoding as String) ?: 'UTF-8', (args.createBackup as Boolean) ?: false)
                return textResponse(requestId, result)

            case 'appendToFile':
                def result = appendToFile(args.path as String, args.content as String,
                    (args.encoding as String) ?: 'UTF-8')
                return textResponse(requestId, result)

            case 'copyFile':
                def result = copyFile(args.source as String, args.destination as String,
                    (args.overwrite as Boolean) ?: false)
                return textResponse(requestId, result)

            case 'moveFile':
                def result = moveFile(args.source as String, args.destination as String,
                    (args.overwrite as Boolean) ?: false)
                return textResponse(requestId, result)

            case 'deleteFile':
                def result = deleteFile(args.path as String, (args.recursive as Boolean) ?: false)
                return textResponse(requestId, result)

            case 'createDirectory':
                def result = createDirectory(args.path as String)
                return textResponse(requestId, result)

            default:
                return McpResponse.error(requestId, -32601, "Unknown tool: ${toolName}" as String)
        }
    }

    // ========================================================================
    // IMPLEMENTATIONS
    // ========================================================================

    /**
     * Write file contents - now auto-creates parent directories
     */
    Map<String, Object> writeFile(String path, String content, String encoding = 'UTF-8', boolean createBackup = false) {
        try {
            validateWriteEnabled()
            String normalized = pathService.normalizePath(path)

            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }

            Path filePath = Paths.get(normalized)

            // Auto-create parent directories
            Path parent = filePath.getParent()
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent)
            }

            String backupPath = null
            if (createBackup && Files.exists(filePath)) {
                backupPath = "${normalized}.backup"
                Files.copy(filePath, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING)
            }

            Files.write(filePath, content.getBytes(encoding))

            return createMap([
                path: sanitize(normalized),
                size: Files.size(filePath),
                backup: backupPath ? sanitize(backupPath) : null
            ])
        } catch (Exception e) {
            log.error("Error writing file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     *  NEW: Replace a unique string in file without full read+write round-trip
     * 60-80% token savings for typical edits
     * Fails safely if oldText not found or found multiple times
     */
    Map<String, Object> replaceInFile(String path, String oldText, String newText,
                                       String encoding = 'UTF-8', boolean createBackup = false) {
        try {
            validateWriteEnabled()
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)

            String content = new String(Files.readAllBytes(filePath), encoding)

            // Count occurrences for safety
            int count = 0
            int searchFrom = 0
            while (true) {
                int idx = content.indexOf(oldText, searchFrom)
                if (idx < 0) break
                count++
                searchFrom = idx + oldText.length()
                if (count > 1) break  // No need to count further
            }

            if (count == 0) {
                String preview = oldText.length() > 100 ? oldText.take(100) + '...' : oldText
                throw new IllegalArgumentException(
                    "oldText not found in file. First 100 chars of search text: '${sanitize(preview)}'"
                )
            }

            if (count > 1) {
                throw new IllegalArgumentException(
                    "oldText found ${count} times in file - must be unique. " +
                    "Include more surrounding context to make the match unique."
                )
            }

            // Create backup if requested
            String backupPath = null
            if (createBackup) {
                backupPath = "${normalized}.backup"
                Files.copy(filePath, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING)
            }

            // Perform replacement and write
            String newContent = content.replace(oldText, newText)
            Files.write(filePath, newContent.getBytes(encoding))

            return createMap([
                path: sanitize(normalized),
                replacements: 1,
                oldLength: oldText.length(),
                newLength: newText.length(),
                fileSize: Files.size(filePath),
                backup: backupPath ? sanitize(backupPath) : null
            ])
        } catch (Exception e) {
            log.error("Error replacing in file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     *  NEW: Append content to end of file without reading existing content
     * Auto-creates file and parent directories if needed
     */
    Map<String, Object> appendToFile(String path, String content, String encoding = 'UTF-8') {
        try {
            validateWriteEnabled()
            String normalized = pathService.normalizePath(path)

            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }

            Path filePath = Paths.get(normalized)

            // Auto-create parent directories
            Path parent = filePath.getParent()
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent)
            }

            Files.write(filePath, content.getBytes(encoding),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)

            return createMap([
                path: sanitize(normalized),
                appendedBytes: content.getBytes(encoding).length,
                fileSize: Files.size(filePath)
            ])
        } catch (Exception e) {
            log.error("Error appending to file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Copy file
     */
    Map<String, Object> copyFile(String sourcePath, String destPath, boolean overwrite = false) {
        try {
            validateWriteEnabled()
            String normalizedSource = pathService.normalizePath(sourcePath)
            String normalizedDest = pathService.normalizePath(destPath)

            if (!isPathAllowed(normalizedSource) || !isPathAllowed(normalizedDest)) {
                throw new SecurityException("Path not allowed")
            }

            Path source = Paths.get(normalizedSource)
            Path dest = Paths.get(normalizedDest)

            if (!Files.exists(source)) {
                throw new FileNotFoundException("Source not found: ${sanitize(normalizedSource)}")
            }

            // Auto-create parent directories for destination
            Path parent = dest.getParent()
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent)
            }

            CopyOption[] options = overwrite ?
                    [StandardCopyOption.REPLACE_EXISTING] as CopyOption[] :
                    [] as CopyOption[]

            Files.copy(source, dest, options)

            return createMap([
                source: sanitize(normalizedSource),
                destination: sanitize(normalizedDest),
                size: Files.size(dest)
            ])
        } catch (Exception e) {
            log.error("Error copying file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Move/rename file
     */
    Map<String, Object> moveFile(String sourcePath, String destPath, boolean overwrite = false) {
        try {
            validateWriteEnabled()
            String normalizedSource = pathService.normalizePath(sourcePath)
            String normalizedDest = pathService.normalizePath(destPath)

            if (!isPathAllowed(normalizedSource) || !isPathAllowed(normalizedDest)) {
                throw new SecurityException("Path not allowed")
            }

            Path source = Paths.get(normalizedSource)
            Path dest = Paths.get(normalizedDest)

            if (!Files.exists(source)) {
                throw new FileNotFoundException("Source not found: ${sanitize(normalizedSource)}")
            }

            CopyOption[] options = overwrite ?
                    [StandardCopyOption.REPLACE_EXISTING] as CopyOption[] :
                    [] as CopyOption[]

            Files.move(source, dest, options)

            return createMap([
                source: sanitize(normalizedSource),
                destination: sanitize(normalizedDest)
            ])
        } catch (Exception e) {
            log.error("Error moving file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Delete file or directory
     */
    Map<String, Object> deleteFile(String path, boolean recursive = false) {
        try {
            validateWriteEnabled()
            String normalized = pathService.normalizePath(path)

            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }

            Path filePath = Paths.get(normalized)
            if (!Files.exists(filePath)) {
                throw new FileNotFoundException("File not found: ${sanitize(normalized)}")
            }

            boolean success = false
            if (Files.isDirectory(filePath) && recursive) {
                java.util.stream.Stream<Path> stream = null
                try {
                    stream = Files.walk(filePath)
                    stream.sorted(Comparator.reverseOrder())
                            .forEach { p ->
                                try { Files.delete(p) }
                                catch (IOException e) { log.warn("Failed to delete ${sanitize(p.toString())}") }
                            }
                    success = !Files.exists(filePath)
                } finally {
                    stream?.close()
                }
            } else {
                Files.delete(filePath)
                success = true
            }

            return createMap([path: sanitize(normalized), deleted: success])
        } catch (Exception e) {
            log.error("Error deleting file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Create directory (including parents)
     */
    Map<String, Object> createDirectory(String path) {
        try {
            validateWriteEnabled()
            String normalized = pathService.normalizePath(path)

            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }

            Path dirPath = Paths.get(normalized)
            Files.createDirectories(dirPath)

            return createMap([path: sanitize(normalized), created: true, exists: Files.exists(dirPath)])
        } catch (Exception e) {
            log.error("Error creating directory: ${sanitize(e.message)}")
            throw e
        }
    }
}
