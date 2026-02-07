package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Stream

/**
 * File metadata operations - exists, info, summary, paths, config queries
 * Lightweight operations that return metadata without file content
 */
@Service
@Slf4j
@CompileStatic
class FileMetadataService extends AbstractFileService implements ToolHandler {

    FileMetadataService(PathService pathService) {
        super(pathService)
    }

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [
            createMap([
                name: "fileExists",
                description: "Check if file/directory exists ( no content read - 100% token savings)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File or directory path"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "getFileInfo",
                description: "Get detailed file/directory metadata (size, dates, permissions)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File or directory path"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "getFileSummary",
                description: "Get file metadata without content ( lines, size, type, dates - 99%+ token savings)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "getProjectRoot",
                description: "Get the active project root directory (preferred scope for operations)",
                inputSchema: createMap([
                    type: "object",
                    properties: [:],
                    required: []
                ])
            ]),
            createMap([
                name: "normalizePath",
                description: "Convert between Windows and WSL path formats",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "Path to normalize"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "getAllowedDirectories",
                description: "Get list of allowed directories accessible for file operations",
                inputSchema: createMap([
                    type: "object",
                    properties: [:],
                    required: []
                ])
            ]),
            createMap([
                name: "isSymlinksAllowed",
                description: "Check if symbolic links are allowed",
                inputSchema: createMap([
                    type: "object",
                    properties: [:],
                    required: []
                ])
            ])
        ]
    }

    @Override
    boolean canHandle(String toolName) {
        toolName in ['fileExists', 'getFileInfo', 'getFileSummary', 'getProjectRoot',
                      'normalizePath', 'getAllowedDirectories', 'isSymlinksAllowed']
    }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> args, Object requestId) {
        switch (toolName) {
            case 'fileExists':
                return textResponse(requestId, fileExists(args.path as String))

            case 'getFileInfo':
                return textResponse(requestId, getFileInfo(args.path as String))

            case 'getFileSummary':
                return textResponse(requestId, getFileSummary(args.path as String))

            case 'getProjectRoot':
                return textResponse(requestId, [projectRoot: getProjectRoot(),
                    message: "This is the preferred scope for file operations"])

            case 'normalizePath':
                return textResponse(requestId, pathService.getPathRepresentations(args.path as String))

            case 'getAllowedDirectories':
                return textResponse(requestId, getAllowedDirectories())

            case 'isSymlinksAllowed':
                return textResponse(requestId, [allowSymlinks: isSymlinksAllowed()])

            default:
                return McpResponse.error(requestId, -32601, "Unknown tool: ${toolName}" as String)
        }
    }

    // ========================================================================
    // IMPLEMENTATIONS
    // ========================================================================

    Map<String, Object> fileExists(String path) {
        try {
            String normalized = pathService.normalizePath(path)
            boolean allowed = isPathAllowed(normalized)
            Path filePath = Paths.get(normalized)
            boolean exists = Files.exists(filePath)

            return createMap([
                path: sanitize(normalized),
                exists: exists,
                allowed: allowed,
                isFile: exists ? Files.isRegularFile(filePath) : false,
                isDirectory: exists ? Files.isDirectory(filePath) : false
            ])
        } catch (Exception e) {
            log.error("Error checking file exists: ${sanitize(e.message)}")
            throw e
        }
    }

    Map<String, Object> getFileInfo(String path) {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class)

            return createMap([
                path: sanitize(normalized),
                name: filePath.fileName.toString(),
                type: attrs.isDirectory() ? 'directory' : 'file',
                size: attrs.size(),
                creationTime: attrs.creationTime().toString(),
                lastModified: attrs.lastModifiedTime().toString(),
                lastAccess: attrs.lastAccessTime().toString(),
                readable: Files.isReadable(filePath),
                writable: Files.isWritable(filePath),
                executable: Files.isExecutable(filePath),
                hidden: Files.isHidden(filePath)
            ])
        } catch (Exception e) {
            log.error("Error getting file info: ${sanitize(e.message)}")
            throw e
        }
    }

    Map<String, Object> getFileSummary(String path) {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class)

            Map<String, Object> summary = createMap([
                path: sanitize(normalized),
                name: filePath.fileName.toString(),
                type: attrs.isDirectory() ? 'directory' : 'file',
                size: attrs.size(),
                sizeKB: attrs.size() / 1024,
                sizeMB: attrs.size() / (1024 * 1024),
                created: attrs.creationTime().toString(),
                modified: attrs.lastModifiedTime().toString(),
                accessed: attrs.lastAccessTime().toString(),
                readable: Files.isReadable(filePath),
                writable: Files.isWritable(filePath),
                executable: Files.isExecutable(filePath),
                hidden: Files.isHidden(filePath)
            ])

            if (attrs.isRegularFile() && attrs.size() < (maxFileSizeMb * 1024 * 1024)) {
                try {
                    long lineCount = 0
                    try (Stream<String> lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
                        lineCount = lines.count()
                    }
                    summary.lineCount = lineCount
                    String name = filePath.fileName.toString()
                    String extension = name.contains('.') ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : ''
                    summary.extension = extension
                } catch (Exception e) {
                    summary.lineCount = null
                    summary.extension = 'binary'
                }
            }

            return summary
        } catch (Exception e) {
            log.error("Error getting file summary: ${sanitize(e.message)}")
            throw e
        }
    }

    List<String> getAllowedDirectories() {
        return allowedDirectories.collect { sanitize(it) }
    }

    boolean isSymlinksAllowed() {
        return allowSymlinks
    }
}
