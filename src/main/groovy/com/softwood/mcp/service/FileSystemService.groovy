package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * Groovy-powered filesystem operations using Java NIO
 * Avoids Groovy GDK File methods that can trigger Windows phantom 'nul' file creation
 */
@Service
@Slf4j
@CompileStatic
class FileSystemService {

    private final PathService pathService

    @Value('${mcp.filesystem.allowed-directories}')
    String allowedDirectoriesString

    List<String> allowedDirectories

    @jakarta.annotation.PostConstruct
    void init() {
        allowedDirectories = allowedDirectoriesString.split(',').collect {String s -> s.trim() }
    }

    @Value('${mcp.filesystem.max-file-size-mb:10}')
    int maxFileSizeMb

    @Value('${mcp.filesystem.enable-write:false}')
    boolean enableWrite

    @Value('${mcp.filesystem.allow-symlinks:false}')
    boolean allowSymlinks

    // Windows reserved device names that should be filtered
    private static final Set<String> WINDOWS_RESERVED_NAMES = [
            'CON', 'PRN', 'AUX', 'NUL', 'COM1', 'COM2', 'COM3', 'COM4', 'COM5',
            'COM6', 'COM7', 'COM8', 'COM9', 'LPT1', 'LPT2', 'LPT3', 'LPT4',
            'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
    ] as Set<String>

    FileSystemService(PathService pathService) {
        this.pathService = pathService
    }

    /**
     * Helper to create properly typed Map for CompileStatic
     */
    private static Map<String, Object> createMap(Map raw) {
        return new HashMap<String, Object>(raw)
    }

    /**
     * Sanitize string by removing control characters (except newlines and tabs)
     * Ensures clean JSON serialization
     * CRITICAL: This prevents "Exceeded max compaction" errors in Claude client
     */
    private static String sanitize(String text) {
        if (!text) return text
        try {
            // Remove control characters except \n (10) and \t (9)
            // Also remove any UTF-8 invalid sequences and other problematic characters
            String cleaned = text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F-\x9F]/, '')
            
            // Additional safety: replace any remaining non-printable characters
            cleaned = cleaned.replaceAll(/[^\p{Print}\p{Space}]/, '')
            
            return cleaned
        } catch (Exception e) {
            log.warn("Error sanitizing text: ${e.message}")
            // Return empty string if sanitization fails to prevent errors
            return ""
        }
    }

    /**
     * Sanitize any object for safe JSON serialization
     * Recursively sanitizes maps and lists
     */
    private static Object sanitizeObject(Object obj) {
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

    /**
     * Check if a path is within allowed directories
     * Also handles symbolic link validation
     */
    boolean isPathAllowed(String path) {
        try {
            String normalized = pathService.normalizePath(path)
            Path resolvedPath = Paths.get(normalized).toAbsolutePath().normalize()
            
            // Check if path is a symbolic link
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
    private static boolean isReservedName(String filename) {
        if (!filename) return false
        try {
            String upper = filename.toUpperCase()
            // Check exact match or with extension (e.g., "nul.txt")
            return WINDOWS_RESERVED_NAMES.contains(upper) ||
                    WINDOWS_RESERVED_NAMES.any { upper.startsWith("${it}.") }
        } catch (Exception e) {
            // If we can't check, assume it's reserved to be safe
            return true
        }
    }

    /**
     * Validate write operations are enabled
     */
    private void validateWriteEnabled() {
        if (!enableWrite) {
            throw new SecurityException("Write operations are disabled. Set mcp.filesystem.enable-write=true")
        }
    }

    /**
     * Read file contents with encoding detection
     */
    String readFile(String path, String encoding = 'UTF-8') {
        try {
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
     * Read file lines using Java NIO
     */
    List<String> readLines(String path, String encoding = 'UTF-8') {
        try {
            String normalized = pathService.normalizePath(path)

            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }

            Path filePath = Paths.get(normalized)
            if (!Files.exists(filePath)) {
                throw new FileNotFoundException("File not found: ${sanitize(normalized)}")
            }

            return Files.readAllLines(filePath, java.nio.charset.Charset.forName(encoding))
                    .collect { sanitize(it as String) }
        } catch (Exception e) {
            log.error("Error reading lines: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Write file contents using Java NIO
     */
    Map<String, Object> writeFile(String path, String content, String encoding = 'UTF-8', boolean createBackup = false) {
        try {
            validateWriteEnabled()

            String normalized = pathService.normalizePath(path)

            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }

            Path filePath = Paths.get(normalized)
            String backupPath = null

            // Create backup if requested and file exists
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
     * List directory contents using Java NIO (avoids Groovy GDK eachFile)
     * CRITICAL: Enhanced error handling and sanitization to prevent client errors
     */
    List<Map<String, Object>> listDirectory(String path, String pattern = null, boolean recursive = false) {
        try {
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

            List<Map<String, Object>> results = []
            Pattern regexPattern = pattern ? Pattern.compile(pattern) : null

            if (recursive) {
                // Use Files.walk for recursive listing with enhanced error handling
                Stream<Path> stream = null
                try {
                    stream = Files.walk(dirPath)
                    stream.filter { p ->
                        try {
                            return Files.isRegularFile(p)
                        } catch (Exception e) {
                            log.warn("Error checking if regular file: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                            return false
                        }
                    }
                    .filter { p ->
                        try {
                            return !isReservedName(p.fileName.toString())
                        } catch (Exception e) {
                            log.warn("Error checking reserved name: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                            return false
                        }
                    }
                    .filter { p ->
                        try {
                            return !regexPattern || regexPattern.matcher(p.fileName.toString()).matches()
                        } catch (Exception e) {
                            log.warn("Error matching pattern: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                            return false
                        }
                    }
                    .forEach { p ->
                        try {
                            Map<String, Object> entry = pathToMap(p)
                            if (entry != null) {
                                results.add(entry)
                            }
                        } catch (Exception e) {
                            log.warn("Error adding path to results: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                        }
                    }
                } finally {
                    if (stream != null) {
                        try {
                            stream.close()
                        } catch (Exception e) {
                            log.warn("Error closing stream: ${sanitize(e.message)}")
                        }
                    }
                }
            } else {
                // Use Files.list for non-recursive listing with enhanced error handling
                Stream<Path> stream = null
                try {
                    stream = Files.list(dirPath)
                    stream.filter { p ->
                        try {
                            return !isReservedName(p.fileName.toString())
                        } catch (Exception e) {
                            log.warn("Error checking reserved name: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                            return false
                        }
                    }
                    .filter { p ->
                        try {
                            return !regexPattern || regexPattern.matcher(p.fileName.toString()).matches()
                        } catch (Exception e) {
                            log.warn("Error matching pattern: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                            return false
                        }
                    }
                    .forEach { p ->
                        try {
                            Map<String, Object> entry = pathToMap(p)
                            if (entry != null) {
                                results.add(entry)
                            }
                        } catch (Exception e) {
                            log.warn("Error adding path to results: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                        }
                    }
                } finally {
                    if (stream != null) {
                        try {
                            stream.close()
                        } catch (Exception e) {
                            log.warn("Error closing stream: ${sanitize(e.message)}")
                        }
                    }
                }
            }

            // Sanitize entire result set before returning
            return results.collect { result ->
                sanitizeObject(result) as Map<String, Object>
            }
        } catch (Exception e) {
            log.error("Error listing directory: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Search files by content using Java NIO and regex
     * CRITICAL: Enhanced error handling and sanitization
     */
    List<Map<String, Object>> searchFiles(String directory, String contentPattern, String filePattern = '.*\\.groovy$') {
        try {
            String normalized = pathService.normalizePath(directory)

            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }

            Path dirPath = Paths.get(normalized)
            if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                throw new IllegalArgumentException("Invalid directory: ${sanitize(normalized)}")
            }

            List<Map<String, Object>> results = []
            Pattern fileRegex = Pattern.compile(filePattern)
            Pattern contentRegex = Pattern.compile(contentPattern)

            Stream<Path> stream = null
            try {
                stream = Files.walk(dirPath)
                stream.filter { p ->
                    try {
                        return Files.isRegularFile(p)
                    } catch (Exception e) {
                        log.warn("Error checking if regular file: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                        return false
                    }
                }
                .filter { p ->
                    try {
                        return !isReservedName(p.fileName.toString())
                    } catch (Exception e) {
                        log.warn("Error checking reserved name: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                        return false
                    }
                }
                .filter { p ->
                    try {
                        return fileRegex.matcher(p.fileName.toString()).matches()
                    } catch (Exception e) {
                        log.warn("Error matching file pattern: ${sanitize(p.toString())}: ${sanitize(e.message)}")
                        return false
                    }
                }
                .forEach { p ->
                    try {
                        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8)
                        List<Map<String, Object>> matches = []

                        lines.eachWithIndex { String line, int index ->
                            try {
                                if (contentRegex.matcher(line).find()) {
                                    matches.add(createMap([lineNumber: index + 1, line: sanitize(line)]))
                                }
                            } catch (Exception e) {
                                log.warn("Error matching content pattern in line: ${sanitize(e.message)}")
                            }
                        }

                        if (matches) {
                            results.add(createMap([
                                    path: sanitize(p.toAbsolutePath().toString().replace('\\', '/')),
                                    name: sanitize(p.fileName.toString()),
                                    matches: matches
                            ]))
                        }
                    } catch (Exception e) {
                        log.warn("Error reading file ${sanitize(p.toString())}: ${sanitize(e.message)}")
                    }
                }
            } finally {
                if (stream != null) {
                    try {
                        stream.close()
                    } catch (Exception e) {
                        log.warn("Error closing stream: ${sanitize(e.message)}")
                    }
                }
            }

            // Sanitize entire result set before returning
            return results.collect { result ->
                sanitizeObject(result) as Map<String, Object>
            }
        } catch (Exception e) {
            log.error("Error searching files: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Convert Path to Map using Java NIO attributes with sanitized strings
     * CRITICAL: All strings must be sanitized to prevent client errors
     */
    private Map<String, Object> pathToMap(Path path) {
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
            // Return a minimal safe entry instead of null
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
     * Delete file or directory using Java NIO
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
                // Delete directory recursively using Files.walk
                Stream<Path> stream = null
                try {
                    stream = Files.walk(filePath)
                    stream.sorted(Comparator.reverseOrder())
                            .forEach { p ->
                                try {
                                    Files.delete(p)
                                } catch (IOException e) {
                                    log.warn("Failed to delete ${sanitize(p.toString())}: ${sanitize(e.message)}")
                                }
                            }
                    success = !Files.exists(filePath)
                } finally {
                    if (stream != null) {
                        try {
                            stream.close()
                        } catch (Exception e) {
                            log.warn("Error closing stream: ${sanitize(e.message)}")
                        }
                    }
                }
            } else {
                Files.delete(filePath)
                success = true
            }

            return createMap([
                    path: sanitize(normalized),
                    deleted: success
            ])
        } catch (Exception e) {
            log.error("Error deleting file: ${sanitize(e.message)}")
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
     * Create directory
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

            return createMap([
                    path: sanitize(normalized),
                    created: true,
                    exists: Files.exists(dirPath)
            ])
        } catch (Exception e) {
            log.error("Error creating directory: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Get list of allowed directories
     * Returns the directories that are accessible for file operations
     */
    List<String> getAllowedDirectories() {
        return allowedDirectories.collect { sanitize(it) }
    }

    /**
     * Check if symbolic links are allowed
     */
    boolean isSymlinksAllowed() {
        return allowSymlinks
    }

    /**
     * Watch a directory for changes
     * Returns a map with watch details
     * Note: This creates a one-time watch that reports changes since the last check
     */
    Map<String, Object> watchDirectory(String path, List<String> eventTypes = ['CREATE', 'MODIFY', 'DELETE']) {
        try {
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
            
            // Create a watch service
            WatchService watchService = FileSystems.getDefault().newWatchService()
            
            // Register the directory with the watch service
            Set<WatchEvent.Kind<?>> kinds = [] as Set
            if (eventTypes.contains('CREATE')) kinds.add(StandardWatchEventKinds.ENTRY_CREATE)
            if (eventTypes.contains('MODIFY')) kinds.add(StandardWatchEventKinds.ENTRY_MODIFY)
            if (eventTypes.contains('DELETE')) kinds.add(StandardWatchEventKinds.ENTRY_DELETE)
            
            WatchKey key = dirPath.register(watchService, kinds.toArray(new WatchEvent.Kind<?>[0]) as WatchEvent.Kind<?>[])
            
            return createMap([
                path: sanitize(normalized),
                watching: true,
                eventTypes: eventTypes,
                message: "Directory watch registered. Use pollDirectoryWatch() to check for events."
            ])
        } catch (Exception e) {
            log.error("Error watching directory: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Poll for directory watch events
     * This is a simplified version - in production you'd want a more sophisticated approach
     */
    Map<String, Object> pollDirectoryWatch(String path) {
        try {
            String normalized = pathService.normalizePath(path)
            
            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }
            
            return createMap([
                path: sanitize(normalized),
                events: [],
                message: "File watching is available but requires active watch service management. Use watchDirectory() first."
            ])
        } catch (Exception e) {
            log.error("Error polling directory watch: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Read multiple files at once (more efficient than multiple readFile calls)
     */
    List<Map<String, Object>> readMultipleFiles(List<String> paths) {
        return paths.collect { path ->
            try {
                String content = readFile(path)
                createMap([
                    path: path,
                    content: content,
                    success: true
                ])
            } catch (Exception e) {
                log.warn("Failed to read ${sanitize(path)}: ${sanitize(e.message)}")
                createMap([
                    path: path,
                    error: sanitize(e.message),
                    success: false
                ])
            }
        }
    }

    /**
     * Get detailed file/directory metadata
     */
    Map<String, Object> getFileInfo(String path) {
        try {
            String normalized = pathService.normalizePath(path)
            
            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }
            
            Path filePath = Paths.get(normalized)
            
            if (!Files.exists(filePath)) {
                throw new FileNotFoundException("File not found: ${sanitize(normalized)}")
            }
            
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

    /**
     * List directory with file sizes and optional sorting
     */
    List<Map<String, Object>> listDirectoryWithSizes(String path, String sortBy = 'name') {
        try {
            String normalized = pathService.normalizePath(path)
            
            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }
            
            Path dirPath = Paths.get(normalized)
            
            if (!Files.isDirectory(dirPath)) {
                throw new IllegalArgumentException("Path is not a directory: ${sanitize(normalized)}")
            }
            
            List<Map<String, Object>> entries = []
            
            Files.newDirectoryStream(dirPath).each { Path entry ->
                String entryName = entry.fileName.toString()
                
                // Skip Windows reserved names
                if (WINDOWS_RESERVED_NAMES.contains(entryName.toUpperCase())) {
                    return
                }
                
                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class)
                    
                    entries.add(createMap([
                        name: entryName,
                        path: sanitize(entry.toString()),
                        type: attrs.isDirectory() ? 'directory' : 'file',
                        size: attrs.size(),
                        lastModified: attrs.lastModifiedTime().toMillis(),
                        readable: Files.isReadable(entry),
                        writable: Files.isWritable(entry),
                        executable: Files.isExecutable(entry)
                    ]))
                } catch (Exception e) {
                    log.warn("Error reading attributes for ${sanitize(entryName)}: ${sanitize(e.message)}")
                }
            }
            
            // Sort entries
            if (sortBy == 'size') {
                entries.sort { a, b -> (b.size as Long) <=> (a.size as Long) }
            } else {
                entries.sort { a, b -> (a.name as String) <=> (b.name as String) }
            }
            
            return entries
        } catch (Exception e) {
            log.error("Error listing directory with sizes: ${sanitize(e.message)}")
            throw e
        }
    }

    /**
     * Get recursive directory tree structure
     */
    Map<String, Object> getDirectoryTree(String path, List<String> excludePatterns = []) {
        try {
            String normalized = pathService.normalizePath(path)
            
            if (!isPathAllowed(normalized)) {
                throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
            }
            
            Path dirPath = Paths.get(normalized)
            
            if (!Files.isDirectory(dirPath)) {
                throw new IllegalArgumentException("Path is not a directory: ${sanitize(normalized)}")
            }
            
            List<Pattern> excludeRegexes = excludePatterns.collect { Pattern.compile(it) }
            
            return buildTreeNode(dirPath, excludeRegexes)
        } catch (Exception e) {
            log.error("Error getting directory tree: ${sanitize(e.message)}")
            throw e
        }
    }
    
    private Map<String, Object> buildTreeNode(Path path, List<Pattern> excludePatterns) {
        String name = path.fileName?.toString() ?: path.toString()
        
        // Check exclusions
        for (Pattern pattern : excludePatterns) {
            if (pattern.matcher(name).matches()) {
                return null
            }
        }
        
        boolean isDir = Files.isDirectory(path)
        
        Map<String, Object> node = createMap([
            name: name,
            type: isDir ? 'directory' : 'file'
        ])
        
        if (isDir) {
            List<Map<String, Object>> children = []
            try {
                Files.newDirectoryStream(path).each { Path child ->
                    String childName = child.fileName.toString()
                    
                    // Skip Windows reserved names
                    if (WINDOWS_RESERVED_NAMES.contains(childName.toUpperCase())) {
                        return
                    }
                    
                    Map<String, Object> childNode = buildTreeNode(child, excludePatterns)
                    if (childNode != null) {
                        children.add(childNode)
                    }
                }
                node.children = children
            } catch (Exception e) {
                log.warn("Error reading directory ${sanitize(name)}: ${sanitize(e.message)}")
                node.children = []
            }
        }
        
        return node
    }

}
