package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Abstract base class for all file operation services
 * Provides shared utilities: sanitization, path validation, regex compilation, config
 * 
 * PHASE 1 HARDENING: Centralised sanitize(), safeCompilePattern(), relative path resolution
 */
@Slf4j
@CompileStatic
abstract class AbstractFileService {

    protected final PathService pathService

    // Shared config - declared once, inherited by all
    @Value('${mcp.filesystem.allowed-directories}')
    String allowedDirectoriesString

    List<String> allowedDirectories

    @jakarta.annotation.PostConstruct
    void initAllowedDirs() {
        if (!allowedDirectories) {
            allowedDirectories = allowedDirectoriesString?.split(',')?.collect { String s -> s.trim() } ?: []
        }
    }

    @Value('${mcp.filesystem.max-file-size-mb:10}')
    int maxFileSizeMb

    @Value('${mcp.filesystem.enable-write:false}')
    boolean enableWrite

    @Value('${mcp.filesystem.allow-symlinks:false}')
    boolean allowSymlinks

    @Value('${mcp.filesystem.active-project-root:}')
    String activeProjectRoot

    @Value('${mcp.filesystem.max-list-results:100}')
    int maxListResults

    @Value('${mcp.filesystem.max-search-results:50}')
    int maxSearchResults

    @Value('${mcp.filesystem.max-search-matches-per-file:10}')
    int maxSearchMatchesPerFile

    @Value('${mcp.filesystem.max-tree-depth:5}')
    int maxTreeDepth

    @Value('${mcp.filesystem.max-tree-files:200}')
    int maxTreeFiles

    @Value('${mcp.filesystem.max-read-multiple:10}')
    int maxReadMultiple

    @Value('${mcp.filesystem.max-line-length:1000}')
    int maxLineLength

    @Value('${mcp.filesystem.max-response-size-kb:100}')
    int maxResponseSizeKb

    // Windows reserved device names
    protected static final Set<String> WINDOWS_RESERVED_NAMES = [
            'CON', 'PRN', 'AUX', 'NUL', 'COM1', 'COM2', 'COM3', 'COM4', 'COM5',
            'COM6', 'COM7', 'COM8', 'COM9', 'LPT1', 'LPT2', 'LPT3', 'LPT4',
            'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
    ] as Set<String>

    AbstractFileService(PathService pathService) {
        this.pathService = pathService
    }

    // ========================================================================
    // CANONICAL SANITIZATION - Single source of truth
    // ========================================================================

    /**
     * Sanitize string by removing control characters (except newlines and tabs)
     * Ensures clean JSON serialization
     * CRITICAL: Prevents "Exceeded max compaction" errors in Claude client
     */
    protected static String sanitize(String text) {
        if (!text) return text
        try {
            String cleaned = text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F-\x9F]/, '')
            cleaned = cleaned.replaceAll(/[^\p{Print}\p{Space}]/, '')
            return cleaned
        } catch (Exception e) {
            return ""
        }
    }

    /**
     * Sanitize any object recursively for safe JSON serialization
     */
    protected static Object sanitizeObject(Object obj) {
        if (obj == null) {
            return null
        } else if (obj instanceof String) {
            return sanitize((String) obj)
        } else if (obj instanceof Map) {
            Map result = [:]
            ((Map) obj).each { k, v ->
                result[sanitizeObject(k)] = sanitizeObject(v)
            }
            return result
        } else if (obj instanceof List) {
            return ((List) obj).collect { sanitizeObject(it) }
        } else {
            return obj
        }
    }

    // ========================================================================
    // SAFE REGEX COMPILATION - Handles bad patterns gracefully
    // ========================================================================

    /**
     * Safely compile a regex pattern with graceful fallback
     * If pattern is invalid, falls back to literal match (Pattern.quote)
     * Returns a map with the compiled pattern and whether fallback was used
     */
    protected static Pattern safeCompilePattern(String pattern) {
        if (!pattern) return null
        try {
            return Pattern.compile(pattern)
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex '${sanitize(pattern)}': ${sanitize(e.message)} - falling back to literal match")
            try {
                return Pattern.compile(Pattern.quote(pattern))
            } catch (Exception e2) {
                throw new IllegalArgumentException(
                    "Cannot compile pattern '${sanitize(pattern)}': ${sanitize(e.message)}. " +
                    "Tip: escape special chars like . * + ? with double backslash"
                )
            }
        }
    }

    // ========================================================================
    // PATH VALIDATION
    // ========================================================================

    /**
     * Check if a path is within allowed directories
     * Handles symbolic link validation and relative path resolution
     */
    boolean isPathAllowed(String path) {
        try {
            String normalized = pathService.normalizePath(path)
            Path resolvedPath = Paths.get(normalized).toAbsolutePath().normalize()

            if (Files.isSymbolicLink(resolvedPath) && !allowSymlinks) {
                log.warn("Symbolic link access denied: ${sanitize(normalized)}")
                return false
            }

            return allowedDirectories.any { allowedDir ->
                String normalizedAllowed = pathService.normalizePath(allowedDir)
                Path allowedPath = Paths.get(normalizedAllowed).toAbsolutePath().normalize()
                resolvedPath.startsWith(allowedPath)
            }
        } catch (Exception e) {
            log.error("Error checking path allowed: ${sanitize(e.message)}")
            return false
        }
    }

    /**
     * Check if filename is a Windows reserved device name
     */
    protected static boolean isReservedName(String filename) {
        if (!filename) return false
        try {
            String upper = filename.toUpperCase()
            return WINDOWS_RESERVED_NAMES.contains(upper) ||
                    WINDOWS_RESERVED_NAMES.any { upper.startsWith("${it}.") }
        } catch (Exception e) {
            return true
        }
    }

    /**
     * Validate write operations are enabled
     */
    protected void validateWriteEnabled() {
        if (!enableWrite) {
            throw new SecurityException("Write operations are disabled. Set mcp.filesystem.enable-write=true")
        }
    }

    /**
     * Validate a path exists, is allowed, and is a regular file
     * Returns the normalized path string
     * @throws SecurityException, FileNotFoundException, IllegalArgumentException
     */
    protected String validateFilePath(String path) {
        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        }

        Path filePath = Paths.get(normalized)
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("File not found: ${sanitize(normalized)}")
        }

        if (!Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Path is not a file: ${sanitize(normalized)}")
        }

        return normalized
    }

    /**
     * Validate a path exists, is allowed, and is a directory
     * Returns the normalized path string
     */
    protected String validateDirectoryPath(String path) {
        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        }

        Path dirPath = Paths.get(normalized)
        if (!Files.exists(dirPath)) {
            throw new FileNotFoundException("Directory not found: ${sanitize(normalized)}")
        }

        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Path is not a directory: ${sanitize(normalized)}")
        }

        return normalized
    }

    // ========================================================================
    // SHARED HELPERS
    // ========================================================================

    /**
     * Helper to create properly typed Map for CompileStatic
     */
    protected static Map<String, Object> createMap(Map raw) {
        return new HashMap<String, Object>(raw)
    }

    /**
     * Get active project root
     */
    String getProjectRoot() {
        if (activeProjectRoot) {
            return pathService.normalizePath(activeProjectRoot)
        }
        return allowedDirectories.get(0)
    }

    /**
     * Truncate and sanitize a line for safe output
     */
    protected String truncateAndSanitize(String line) {
        if (!line) return ''
        line.length() > maxLineLength ?
            sanitize(line.take(maxLineLength)) + "... (truncated)" :
            sanitize(line)
    }

    /**
     * Convert Path to Map using Java NIO attributes with sanitized strings
     */
    protected Map<String, Object> pathToMap(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class)
            return createMap([
                    path: sanitize(path.toAbsolutePath().toString().replace('\\', '/')),
                    name: sanitize(path.fileName.toString()),
                    type: attrs.isDirectory() ? 'directory' : 'file',
                    size: attrs.size(),
                    lastModified: attrs.lastModifiedTime().toMillis(),
                    readable: Files.isReadable(path),
                    writable: Files.isWritable(path),
                    executable: Files.isExecutable(path)
            ])
        } catch (Exception e) {
            log.warn("Error reading attributes for ${sanitize(path.toString())}: ${sanitize(e.message)}")
            try {
                return createMap([
                        path: sanitize(path.toAbsolutePath().toString().replace('\\', '/')),
                        name: sanitize(path.fileName.toString()),
                        type: 'unknown',
                        size: 0L,
                        lastModified: 0L,
                        readable: false,
                        writable: false,
                        executable: false,
                        error: sanitize("Could not read attributes: ${e.message}")
                ])
            } catch (Exception e2) {
                log.error("Critical error creating error entry: ${sanitize(e2.message)}")
                return null
            }
        }
    }

    /**
     * Validate and warn about large responses
     */
    protected void validateResponseSize(Object response, String operation) {
        try {
            String json = JsonOutput.toJson(response)
            BigDecimal sizeKb = json.length() / 1024

            if (sizeKb > maxResponseSizeKb) {
                log.warn(" Large response for ${operation}: ${sizeKb}KB (limit: ${maxResponseSizeKb}KB) - consider using bounded tools")
            }
        } catch (Exception e) {
            log.debug("Could not validate response size: ${e.message}")
        }
    }

    /**
     * Build a standard MCP success response wrapping text content
     */
    protected McpResponse textResponse(Object requestId, Object data) {
        McpResponse.success(requestId, [
            content: [[type: "text", text: JsonOutput.toJson(data)]]
        ] as Map<String, Object>)
    }

    /**
     * Build a standard MCP success response wrapping plain text
     */
    protected McpResponse plainTextResponse(Object requestId, String text) {
        McpResponse.success(requestId, [
            content: [[type: "text", text: text]]
        ] as Map<String, Object>)
    }
}
