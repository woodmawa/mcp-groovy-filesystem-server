package com.softwood.mcp.service

import groovy.io.FileType
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.*

/**
 * Groovy-powered filesystem operations
 * Provides cross-platform file operations with powerful Groovy APIs
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
        allowedDirectories = allowedDirectoriesString.split(',').collect { it.trim() }
    }
    
    @Value('${mcp.filesystem.max-file-size-mb:10}')
    int maxFileSizeMb
    
    @Value('${mcp.filesystem.enable-write:false}')
    boolean enableWrite
    
    FileSystemService(PathService pathService) {
        this.pathService = pathService
    }
    
    /**
     * Check if a path is within allowed directories
     */
    boolean isPathAllowed(String path) {
        String normalized = pathService.normalizePath(path)
        Path resolvedPath = Paths.get(normalized).toAbsolutePath().normalize()
        
        return allowedDirectories.any { allowedDir ->
            String normalizedAllowed = pathService.normalizePath(allowedDir)
            Path allowedPath = Paths.get(normalizedAllowed).toAbsolutePath().normalize()
            resolvedPath.startsWith(allowedPath)
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
        String normalized = pathService.normalizePath(path)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        File file = new File(normalized)
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: ${normalized}")
        }
        
        if (!file.isFile()) {
            throw new IllegalArgumentException("Path is not a file: ${normalized}")
        }
        
        long sizeInMb = (long)(file.length() / (1024 * 1024))
        if (sizeInMb > maxFileSizeMb) {
            throw new IllegalArgumentException("File too large: ${sizeInMb}MB (max: ${maxFileSizeMb}MB)")
        }
        
        return file.getText(encoding)
    }
    
    /**
     * Read file lines
     */
    List<String> readLines(String path, String encoding = 'UTF-8') {
        String normalized = pathService.normalizePath(path)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        File file = new File(normalized)
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: ${normalized}")
        }
        
        return file.readLines(encoding)
    }
    
    /**
     * Write file contents
     */
    Map<String, Object> writeFile(String path, String content, String encoding = 'UTF-8', boolean createBackup = false) {
        validateWriteEnabled()
        
        String normalized = pathService.normalizePath(path)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        File file = new File(normalized)
        String backupPath = null
        
        // Create backup if requested and file exists
        if (createBackup && file.exists()) {
            backupPath = "${normalized}.backup"
            Files.copy(file.toPath(), Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING)
        }
        
        file.setText(content, encoding)
        
        return [
            path: normalized,
            size: file.length(),
            backup: backupPath
        ]
    }
    
    /**
     * List directory contents
     */
    List<Map<String, Object>> listDirectory(String path, String pattern = null, boolean recursive = false) {
        String normalized = pathService.normalizePath(path)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        File dir = new File(normalized)
        if (!dir.exists()) {
            throw new FileNotFoundException("Directory not found: ${normalized}")
        }
        
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Path is not a directory: ${normalized}")
        }
        
        List<Map<String, Object>> results = []
        
        if (recursive) {
            dir.eachFileRecurse(FileType.FILES) { File file ->
                if (!pattern || file.name.matches(pattern)) {
                    results << fileToMap(file)
                }
            }
        } else {
            dir.eachFile { File file ->
                if (!pattern || file.name.matches(pattern)) {
                    results << fileToMap(file)
                }
            }
        }
        
        return results
    }
    
    /**
     * Search files by content using regex
     */
    List<Map<String, Object>> searchFiles(String directory, String contentPattern, String filePattern = '.*\\.groovy$') {
        String normalized = pathService.normalizePath(directory)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        File dir = new File(normalized)
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Invalid directory: ${normalized}")
        }
        
        List<Map<String, Object>> results = []
        
        dir.eachFileRecurse(FileType.FILES) { File file ->
            if (file.name.matches(filePattern)) {
                try {
                    def lines = file.readLines()
                    def matches = []
                    
                    lines.eachWithIndex { String line, int index ->
                        if (line =~ contentPattern) {
                            matches << [lineNumber: index + 1, line: line]
                        }
                    }
                    
                    if (matches) {
                        results.add([
                            path: file.absolutePath.replace('\\', '/'),
                            name: file.name,
                            matches: matches
                        ] as Map<String, Object>)
                    }
                } catch (Exception e) {
                    log.warn("Error reading file ${file.name}: ${e.message}")
                }
            }
        }
        
        return results
    }
    
    /**
     * Convert File to Map
     */
    private Map<String, Object> fileToMap(File file) {
        return [
            path: file.absolutePath.replace('\\', '/'),
            name: file.name,
            type: file.isDirectory() ? 'directory' : 'file',
            size: file.length(),
            lastModified: file.lastModified(),
            readable: file.canRead(),
            writable: file.canWrite(),
            executable: file.canExecute()
        ]
    }
    
    /**
     * Delete file or directory
     */
    Map<String, Object> deleteFile(String path, boolean recursive = false) {
        validateWriteEnabled()
        
        String normalized = pathService.normalizePath(path)
        
        if (!isPathAllowed(normalized)) {
            throw new SecurityException("Path not allowed: ${normalized}")
        }
        
        File file = new File(normalized)
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: ${normalized}")
        }
        
        boolean success
        if (file.isDirectory() && recursive) {
            success = file.deleteDir()
        } else {
            success = file.delete()
        }
        
        return [
            path: normalized,
            deleted: success
        ]
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
        
        return [
            source: normalizedSource,
            destination: normalizedDest,
            size: Files.size(dest)
        ]
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
        
        return [
            source: normalizedSource,
            destination: normalizedDest
        ]
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
        
        File dir = new File(normalized)
        boolean created = dir.mkdirs()
        
        return [
            path: normalized,
            created: created,
            exists: dir.exists()
        ]
    }
}
