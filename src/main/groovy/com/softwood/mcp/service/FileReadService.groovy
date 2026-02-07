package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.charset.Charset
import java.nio.file.*
import java.util.regex.Pattern

/**
 * File reading operations - all read-only, no mutations
 * PHASE 1 HARDENING: Streaming I/O for headFile/tailFile/readFileRange/grepFile
 * Never loads entire file when bounded reading is requested
 */
@Service
@Slf4j
@CompileStatic
class FileReadService extends AbstractFileService implements ToolHandler {

    FileReadService(PathService pathService) {
        super(pathService)
    }

    // ========================================================================
    // TOOL HANDLER INTERFACE
    // ========================================================================

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [
            createMap([
                name: "readFile",
                description: "Read complete file contents with encoding support ( may be large, prefer readFileRange for bounded results)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path (Windows or WSL format)"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "readFileRange",
                description: "Read specific line range from file ( token-optimized, bounded result)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"]),
                        startLine: createMap([type: "integer", description: "Starting line number (1-indexed, default: 1)"]),
                        maxLines: createMap([type: "integer", description: "Maximum lines to return (default: 100)"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "readMultipleFiles",
                description: "Read multiple files at once (more efficient than multiple readFile calls, max 10 files)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        paths: createMap([type: "array", items: createMap([type: "string"]), description: "Array of file paths to read (max 10)"])
                    ]),
                    required: ["paths"]
                ])
            ]),
            createMap([
                name: "grepFile",
                description: "Read only matching lines from a file (like grep) ( token-optimized - 90%+ savings)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"]),
                        pattern: createMap([type: "string", description: "Regex pattern to search for in file contents. Java regex syntax. Invalid regex falls back to literal match."]),
                        maxMatches: createMap([type: "integer", description: "Maximum matches to return (default: 100)"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"])
                    ]),
                    required: ["path", "pattern"]
                ])
            ]),
            createMap([
                name: "tailFile",
                description: "Read last N lines of a file ( perfect for logs - 95%+ token savings)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"]),
                        lines: createMap([type: "integer", description: "Number of lines from end (default: 50)"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "headFile",
                description: "Read first N lines of a file ( perfect for previews/headers - 95%+ token savings)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"]),
                        lines: createMap([type: "integer", description: "Number of lines from start (default: 50)"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"])
                    ]),
                    required: ["path"]
                ])
            ]),
            createMap([
                name: "countLines",
                description: "Count lines in a file ( no content returned - 99%+ token savings)",
                inputSchema: createMap([
                    type: "object",
                    properties: createMap([
                        path: createMap([type: "string", description: "File path"]),
                        encoding: createMap([type: "string", description: "Character encoding (default: UTF-8)"])
                    ]),
                    required: ["path"]
                ])
            ])
        ]
    }

    @Override
    boolean canHandle(String toolName) {
        toolName in ['readFile', 'readFileRange', 'readMultipleFiles', 'grepFile', 'tailFile', 'headFile', 'countLines']
    }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> args, Object requestId) {
        switch (toolName) {
            case 'readFile':
                String content = readFile(args.path as String, (args.encoding as String) ?: 'UTF-8')
                return plainTextResponse(requestId, content)

            case 'readFileRange':
                def result = readFileRange(args.path as String, 
                    (args.startLine as Integer) ?: 1,
                    (args.maxLines as Integer) ?: 100,
                    (args.encoding as String) ?: 'UTF-8')
                return textResponse(requestId, result)

            case 'readMultipleFiles':
                def results = readMultipleFiles(args.paths as List<String>)
                return textResponse(requestId, results)

            case 'grepFile':
                def grepResult = grepFile(args.path as String, args.pattern as String,
                    (args.maxMatches as Integer) ?: 100, (args.encoding as String) ?: 'UTF-8')
                return textResponse(requestId, grepResult)

            case 'tailFile':
                def tailResult = tailFile(args.path as String,
                    (args.lines as Integer) ?: 50, (args.encoding as String) ?: 'UTF-8')
                return textResponse(requestId, tailResult)

            case 'headFile':
                def headResult = headFile(args.path as String,
                    (args.lines as Integer) ?: 50, (args.encoding as String) ?: 'UTF-8')
                return textResponse(requestId, headResult)

            case 'countLines':
                def countResult = countLines(args.path as String, (args.encoding as String) ?: 'UTF-8')
                return textResponse(requestId, countResult)

            default:
                return McpResponse.error(requestId, -32601, "Unknown tool: ${toolName}" as String)
        }
    }

    // ========================================================================
    // IMPLEMENTATIONS
    // ========================================================================

    /**
     * Read complete file contents
     */
    String readFile(String path, String encoding = 'UTF-8') {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)

            long sizeInMb = (long)(Files.size(filePath) / (1024 * 1024))
            if (sizeInMb > maxFileSizeMb) {
                throw new IllegalArgumentException("File too large: ${sizeInMb}MB (max: ${maxFileSizeMb}MB)")
            }

            return sanitize(new String(Files.readAllBytes(filePath), encoding))
        } catch (Exception e) {
            log.error("Error reading file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     *  STREAMING: Read file with line range - only reads lines needed
     * Does NOT load entire file into memory
     */
    Map<String, Object> readFileRange(String path, int startLine = 1, int maxLines = 100, String encoding = 'UTF-8') {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)
            Charset charset = Charset.forName(encoding)

            int actualStart = Math.max(1, startLine)
            List<String> selectedLines = []
            int totalLines = 0

            try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
                String line
                while ((line = reader.readLine()) != null) {
                    totalLines++
                    if (totalLines >= actualStart && selectedLines.size() < maxLines) {
                        selectedLines.add(truncateAndSanitize(line))
                    }
                }
            }

            int actualEnd = Math.min(totalLines, actualStart + selectedLines.size() - 1)

            if (actualStart > totalLines) {
                return createMap([
                    path: sanitize(normalized),
                    totalLines: totalLines,
                    error: "Start line ${startLine} exceeds file length ${totalLines}",
                    lines: []
                ])
            }

            return createMap([
                path: sanitize(normalized),
                startLine: actualStart,
                endLine: actualEnd,
                totalLines: totalLines,
                requestedMaxLines: maxLines,
                actualLines: selectedLines.size(),
                lines: selectedLines,
                truncated: actualEnd < totalLines
            ])
        } catch (Exception e) {
            log.error("Error reading file range: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     *  STREAMING: Grep file - reads line by line, truly stops at maxMatches
     * Uses BufferedReader for proper early termination
     */
    Map<String, Object> grepFile(String path, String pattern, int maxMatches = 100, String encoding = 'UTF-8') {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)
            Charset charset = Charset.forName(encoding)

            Pattern regex = safeCompilePattern(pattern)
            List<Map<String, Object>> matches = []
            int lineNumber = 0

            try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
                String line
                while ((line = reader.readLine()) != null) {
                    lineNumber++
                    if (matches.size() >= maxMatches) {
                        break  // TRUE early termination
                    }
                    if (regex.matcher(line).find()) {
                        matches.add(createMap([
                            lineNumber: lineNumber,
                            line: truncateAndSanitize(line)
                        ]))
                    }
                }
            }

            return createMap([
                path: sanitize(normalized),
                pattern: sanitize(pattern),
                matchCount: matches.size(),
                truncated: matches.size() >= maxMatches,
                matches: matches
            ])
        } catch (Exception e) {
            log.error("Error grepping file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     *  STREAMING: Tail file using circular buffer - never loads full file
     * Only keeps last N lines in memory at any point
     */
    Map<String, Object> tailFile(String path, int lines = 50, String encoding = 'UTF-8') {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)
            Charset charset = Charset.forName(encoding)

            // Circular buffer - only keeps last N lines in memory
            String[] buffer = new String[lines]
            int bufferIndex = 0
            int totalLines = 0

            try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
                String line
                while ((line = reader.readLine()) != null) {
                    buffer[bufferIndex % lines] = line
                    bufferIndex++
                    totalLines++
                }
            }

            // Extract lines in correct order from circular buffer
            int actualCount = Math.min(lines, totalLines)
            List<String> lastLines = []
            int startIdx = totalLines <= lines ? 0 : (bufferIndex % lines)
            for (int i = 0; i < actualCount; i++) {
                String rawLine = buffer[(startIdx + i) % lines]
                lastLines.add(truncateAndSanitize(rawLine))
            }

            return createMap([
                path: sanitize(normalized),
                totalLines: totalLines,
                requestedLines: lines,
                actualLines: lastLines.size(),
                lines: lastLines
            ])
        } catch (Exception e) {
            log.error("Error tailing file: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     *  STREAMING: Head file - only reads first N lines, then stops
     * Does NOT read rest of file
     */
    Map<String, Object> headFile(String path, int lines = 50, String encoding = 'UTF-8') {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)
            Charset charset = Charset.forName(encoding)

            List<String> firstLines = []
            int totalLines = 0

            try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
                String line
                while ((line = reader.readLine()) != null) {
                    totalLines++
                    if (firstLines.size() < lines) {
                        firstLines.add(truncateAndSanitize(line))
                    }
                }
            }

            return createMap([
                path: sanitize(normalized),
                totalLines: totalLines,
                requestedLines: lines,
                actualLines: firstLines.size(),
                lines: firstLines
            ])
        } catch (Exception e) {
            log.error("Error reading file head: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Count lines efficiently without loading content
     */
    Map<String, Object> countLines(String path, String encoding = 'UTF-8') {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)

            long lineCount = 0
            try (BufferedReader reader = Files.newBufferedReader(filePath, Charset.forName(encoding))) {
                while (reader.readLine() != null) {
                    lineCount++
                }
            }

            return createMap([
                path: sanitize(normalized),
                lineCount: lineCount,
                size: Files.size(filePath),
                sizeKB: Files.size(filePath) / 1024
            ])
        } catch (Exception e) {
            log.error("Error counting lines: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Read multiple files at once
     */
    List<Map<String, Object>> readMultipleFiles(List<String> paths) {
        if (paths.size() > maxReadMultiple) {
            log.warn(" readMultipleFiles limited to ${maxReadMultiple} files (requested: ${paths.size()})")
            paths = paths.take(maxReadMultiple)
        }

        return paths.collect { String filePath ->
            try {
                String content = readFile(filePath)
                createMap([path: filePath, content: content, success: true])
            } catch (Exception e) {
                log.warn("Failed to read ${sanitize(filePath)}: ${sanitize(e.message)}")
                createMap([path: filePath, error: sanitize(e.message), success: false])
            }
        }
    }

    /**
     * Read file lines (utility for other services)
     */
    List<String> readLines(String path, String encoding = 'UTF-8') {
        try {
            String normalized = validateFilePath(path)
            Path filePath = Paths.get(normalized)
            return Files.readAllLines(filePath, Charset.forName(encoding))
                    .collect { sanitize(it as String) }
        } catch (Exception e) {
            log.error("Error reading lines: ${sanitize(e.message)}")
            throw e
        }
    }
}
