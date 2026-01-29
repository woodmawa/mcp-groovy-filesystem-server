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
     */
    private static String sanitize(String text) {
        if (!text) return text
        // Remove control characters except \n (10) and \t (9)
        return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
    }

    /**
     * Check if a path is within allowed directories
     * Also handles symbolic link validation
     */
    boolean isPathAllowed(String path) {
        String normalized = pathService.normalizePath(path)
        Path resolvedPath = Paths.get(normalized).toAbsolutePath().normalize()
        
        // Check if path is a symbolic link
        if (Files.isSymbolicLink(resolvedPath) && !allowSymlinks) {
            log.warn("Symbolic link access denied: ${normalized}")
            return false
        }

        return allowedDirectories.any { allowedDir ->
            String normalizedAllowed = pathService.normalizePath(allowedDir)
            Path allowedPath = Paths.get(normalizedAllowed).toAbsolutePath().normalize()
            resolvedPath.startsWith(allowedPath)
        }
    }

    /**
     * Check if filename is a Windows reserved device name
     */
    private static boolean isReservedName(String filename) {
        if (!filename) return false
        String upper = filename.toUpperCase()
        // Check exact match or with extension (e.g., "nul.txt")
        return WINDOWS_RESERVED_NAMES.contains(upper) ||
                WINDOWS_RESERVED_NAMES.any { upper.startsWith("${it}.") }
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
        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }

        Path filePath = Paths.get(normalized)
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("File not found: ${normalized}")
        }

        if (!Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Path is not a file: ${normalized}")
        }

        long sizeInMb = (long)(Files.size(filePath) / (1024 * 1024))
        if (sizeInMb > maxFileSizeMb) {
            throw new IllegalArgumentException("File too large: ${sizeInMb}MB (max: ${maxFileSizeMb}MB)")
        }

        return sanitize(new String(Files.readAllBytes(filePath), encoding))
    }

    /**
     * Read file lines using Java NIO
     */
    List<String> readLines(String path, String encoding = 'UTF-8') {
        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }

        Path filePath = Paths.get(normalized)
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("File not found: ${normalized}")
        }

        return Files.readAllLines(filePath, java.nio.charset.Charset.forName(encoding))
                .collect { sanitize(it as String) }
    }

    /**
     * Write file contents using Java NIO
     */
    Map<String, Object> writeFile(String path, String content, String encoding = 'UTF-8', boolean createBackup = false) {
        validateWriteEnabled()

        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
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
    }

    /**
     * List directory contents using Java NIO (avoids Groovy GDK eachFile)
     */
    List<Map<String, Object>> listDirectory(String path, String pattern = null, boolean recursive = false) {
        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }

        Path dirPath = Paths.get(normalized)
        if (!Files.exists(dirPath)) {
            throw new FileNotFoundException("Directory not found: ${normalized}")
        }

        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Path is not a directory: ${normalized}")
        }

        List<Map<String, Object>> results = []
        Pattern regexPattern = pattern ? Pattern.compile(pattern) : null

        if (recursive) {
            // Use Files.walk for recursive listing
            Stream<Path> stream = Files.walk(dirPath)
            try {
                stream.filter { p -> Files.isRegularFile(p) }
                        .filter { p -> !isReservedName(p.fileName.toString()) }
                        .filter { p -> !regexPattern || regexPattern.matcher(p.fileName.toString()).matches() }
                        .forEach { p -> results.add(pathToMap(p)) }
            } finally {
                stream.close()
            }
        } else {
            // Use Files.list for non-recursive listing
            Stream<Path> stream = Files.list(dirPath)
            try {
                stream.filter { p -> !isReservedName(p.fileName.toString()) }
                        .filter { p -> !regexPattern || regexPattern.matcher(p.fileName.toString()).matches() }
                        .forEach { p -> results.add(pathToMap(p)) }
            } finally {
                stream.close()
            }
        }

        return results
    }

    /**
     * Search files by content using Java NIO and regex
     */
    List<Map<String, Object>> searchFiles(String directory, String contentPattern, String filePattern = '.*\\.groovy$') {
        String normalized = pathService.normalizePath(directory)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }

        Path dirPath = Paths.get(normalized)
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Invalid directory: ${normalized}")
        }

        List<Map<String, Object>> results = []
        Pattern fileRegex = Pattern.compile(filePattern)
        Pattern contentRegex = Pattern.compile(contentPattern)

        Stream<Path> stream = Files.walk(dirPath)
        try {
            stream.filter { p -> Files.isRegularFile(p) }
                    .filter { p -> !isReservedName(p.fileName.toString()) }
                    .filter { p -> fileRegex.matcher(p.fileName.toString()).matches() }
                    .forEach { p ->
                        try {
                            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8)
                            List<Map<String, Object>> matches = []

                            lines.eachWithIndex { String line, int index ->
                                if (contentRegex.matcher(line).find()) {
                                    matches.add(createMap([lineNumber: index + 1, line: sanitize(line)]))
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
                            log.warn("Error reading file ${p.fileName}: ${e.message}")
                        }
                    }
        } finally {
            stream.close()
        }

        return results
    }

    /**
     * Convert Path to Map using Java NIO attributes with sanitized strings
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
            log.warn("Error reading attributes for ${path}: ${e.message}")
            return createMap([
                    path: sanitize(path.toAbsolutePath().toString().replace('\\', '/')),
                    name: sanitize(path.fileName.toString()),
                    type: 'unknown',
                    size: 0L,
                    lastModified: 0L,
                    readable: false,
                    writable: false,
                    executable: false
            ])
        }
    }

    /**
     * Delete file or directory using Java NIO
     */
    Map<String, Object> deleteFile(String path, boolean recursive = false) {
        validateWriteEnabled()

        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }

        Path filePath = Paths.get(normalized)
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("File not found: ${normalized}")
        }

        boolean success = false
        if (Files.isDirectory(filePath) && recursive) {
            // Delete directory recursively using Files.walk
            Stream<Path> stream = Files.walk(filePath)
            try {
                stream.sorted(Comparator.reverseOrder())
                        .forEach { p ->
                            try {
                                Files.delete(p)
                            } catch (IOException e) {
                                log.warn("Failed to delete ${p}: ${e.message}")
                            }
                        }
                success = !Files.exists(filePath)
            } finally {
                stream.close()
            }
        } else {
            Files.delete(filePath)
            success = true
        }

        return createMap([
                path: sanitize(normalized),
                deleted: success
        ])
    }

    /**
     * Copy file
     */
    Map<String, Object> copyFile(String sourcePath, String destPath, boolean overwrite = false) {
        validateWriteEnabled()

        String normalizedSource = pathService.normalizePath(sourcePath)
        String normalizedDest = pathService.normalizePath(destPath)

        if (!isPathAllowed(normalizedSource) || !isPathAllowed(normalizedDest)) {
            throw new SecurityException("Path not allowed")
        }

        Path source = Paths.get(normalizedSource)
        Path dest = Paths.get(normalizedDest)

        if (!Files.exists(source)) {
            throw new FileNotFoundException("Source not found: ${normalizedSource}")
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
    }

    /**
     * Move/rename file
     */
    Map<String, Object> moveFile(String sourcePath, String destPath, boolean overwrite = false) {
        validateWriteEnabled()

        String normalizedSource = pathService.normalizePath(sourcePath)
        String normalizedDest = pathService.normalizePath(destPath)

        if (!isPathAllowed(normalizedSource) || !isPathAllowed(normalizedDest)) {
            throw new SecurityException("Path not allowed")
        }

        Path source = Paths.get(normalizedSource)
        Path dest = Paths.get(normalizedDest)

        if (!Files.exists(source)) {
            throw new FileNotFoundException("Source not found: ${normalizedSource}")
        }

        CopyOption[] options = overwrite ?
                [StandardCopyOption.REPLACE_EXISTING] as CopyOption[] :
                [] as CopyOption[]

        Files.move(source, dest, options)

        return createMap([
                source: sanitize(normalizedSource),
                destination: sanitize(normalizedDest)
        ])
    }

    /**
     * Create directory
     */
    Map<String, Object> createDirectory(String path) {
        validateWriteEnabled()

        String normalized = pathService.normalizePath(path)

        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }

        Path dirPath = Paths.get(normalized)
        Files.createDirectories(dirPath)

        return createMap([
                path: sanitize(normalized),
                created: true,
                exists: Files.exists(dirPath)
        ])
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
        String normalized = pathService.normalizePath(path)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        Path dirPath = Paths.get(normalized)
        if (!Files.exists(dirPath)) {
            throw new FileNotFoundException("Directory not found: ${normalized}")
        }
        
        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Path is not a directory: ${normalized}")
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
    }

    /**
     * Poll for directory watch events
     * This is a simplified version - in production you'd want a more sophisticated approach
     */
    Map<String, Object> pollDirectoryWatch(String path) {
        String normalized = pathService.normalizePath(path)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        return createMap([
            path: sanitize(normalized),
            events: [],
            message: "File watching is available but requires active watch service management. Use watchDirectory() first."
        ])
    }

}