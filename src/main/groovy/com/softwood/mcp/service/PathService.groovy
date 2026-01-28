package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Path normalization and conversion service
 * Handles Windows <-> WSL path translation
 */
@Service
@Slf4j
@CompileStatic
class PathService {
    
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows")
    
    /**
     * Normalize a path to use forward slashes and handle both Windows and WSL formats
     */
    String normalizePath(String path) {
        if (!path) return path
        
        // Convert backslashes to forward slashes
        String normalized = path.replace('\\', '/')
        
        // Handle WSL paths (/mnt/c/...) -> convert to Windows (C:/...)
        if (normalized.startsWith('/mnt/')) {
            normalized = convertWslToWindows(normalized)
        }
        
        return normalized
    }
    
    /**
     * Convert Windows path to WSL path
     * C:/Users/will -> /mnt/c/Users/will
     */
    String convertWindowsToWsl(String windowsPath) {
        if (!windowsPath) return windowsPath
        
        String normalized = windowsPath.replace('\\', '/')
        
        // Check if it's already a WSL path
        if (normalized.startsWith('/mnt/')) {
            return normalized
        }
        
        // Extract drive letter
        if (normalized.matches('^[A-Za-z]:/.*')) {
            String driveLetter = normalized[0].toLowerCase()
            String pathWithoutDrive = normalized.substring(2) // Remove "C:"
            return "/mnt/${driveLetter}${pathWithoutDrive}"
        }
        
        return normalized
    }
    
    /**
     * Convert WSL path to Windows path
     * /mnt/c/Users/will -> C:/Users/will
     */
    String convertWslToWindows(String wslPath) {
        if (!wslPath) return wslPath
        
        // Check if it's a WSL path
        if (wslPath.startsWith('/mnt/')) {
            // Extract drive letter and path
            def matcher = wslPath =~ /^\/mnt\/([a-z])(\/.*)?$/
            if (matcher.matches()) {
                String driveLetter = matcher.group(1).toUpperCase()
                String path = matcher.group(2) ?: ""
                return "${driveLetter}:${path}"
            }
        }
        
        return wslPath
    }
    
    /**
     * Get both Windows and WSL representations of a path
     */
    Map<String, String> getPathRepresentations(String path) {
        String normalized = normalizePath(path)
        
        return [
            original: path,
            normalized: normalized,
            windows: convertWslToWindows(normalized),
            wsl: convertWindowsToWsl(normalized)
        ]
    }
    
    /**
     * Resolve a path to a Java Path object
     */
    Path resolvePath(String path) {
        String normalized = normalizePath(path)
        return Paths.get(normalized)
    }
    
    /**
     * Check if a path is absolute
     */
    boolean isAbsolute(String path) {
        String normalized = normalizePath(path)
        return Paths.get(normalized).isAbsolute()
    }
    
    /**
     * Join path components
     */
    String joinPaths(String... components) {
        if (!components) return ""
        
        String result = components[0]
        for (int i = 1; i < components.length; i++) {
            String component = components[i]
            if (!component) continue
            
            // Remove leading slash from component if result doesn't end with slash
            if (result.endsWith('/')) {
                result += component.startsWith('/') ? component.substring(1) : component
            } else {
                result += component.startsWith('/') ? component : '/' + component
            }
        }
        
        return normalizePath(result)
    }
    
    /**
     * Get the parent directory of a path
     */
    String getParentPath(String path) {
        Path p = resolvePath(path)
        Path parent = p.getParent()
        return parent ? parent.toString().replace('\\', '/') : null
    }
    
    /**
     * Get the filename from a path
     */
    String getFileName(String path) {
        Path p = resolvePath(path)
        return p.getFileName()?.toString()
    }
}
