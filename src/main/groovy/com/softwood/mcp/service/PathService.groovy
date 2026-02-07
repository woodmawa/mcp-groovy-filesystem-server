package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Path normalization and conversion service
 * Handles Windows <-> WSL <-> Linux path translation
 * 
 * v0.0.4 ENHANCEMENT: Cross-platform path handling
 * - Detects and maps Linux absolute paths (e.g., /home/claude/file.md)
 * - Maps to configured workspace directory (claude-workspace-root)
 * - Supports Windows, WSL, and native Linux environments
 * 
 * Path Resolution Priority:
 * 1. WSL mount paths (/mnt/c/...) → Native Windows paths (C:/...)
 * 2. Linux absolute paths (/home/claude/..., /tmp/...) → Workspace mapping
 * 3. Relative paths → Resolved against active-project-root
 * 4. Windows paths → Normalized as-is
 */
@Service
@Slf4j
@CompileStatic
class PathService {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows")
    
    // Common Linux path prefixes that should be mapped to workspace
    private static final List<String> LINUX_ABSOLUTE_PREFIXES = [
        '/home/', '/tmp/', '/var/', '/opt/', '/usr/local/', '/workspace/', '/data/'
    ]

    @Value('${mcp.filesystem.active-project-root:}')
    String activeProjectRoot
    
    @Value('${mcp.filesystem.claude-workspace-root:}')
    String claudeWorkspaceRoot

    /**
     * Normalize a path to use forward slashes and handle Windows, WSL, and Linux formats
     * 
     * v0.0.4 ENHANCEMENT: Handles Linux absolute paths from Claude.ai
     * 
     * Examples:
     * - /mnt/c/Users/will/file.md → C:/Users/will/file.md (WSL to Windows)
     * - /home/claude/file.md → <workspace-root>/file.md (Linux to workspace)
     * - C:\Users\will\file.md → C:/Users/will/file.md (Windows normalized)
     * - relative/file.md → <project-root>/relative/file.md (relative resolved)
     */
    String normalizePath(String path) {
        if (!path) return path

        // Convert backslashes to forward slashes
        String normalized = path.replace('\\', '/')

        // Priority 1: Handle WSL mount paths (/mnt/c/...) → Windows (C:/...)
        if (normalized.startsWith('/mnt/')) {
            normalized = convertWslToWindows(normalized)
            log.debug("Converted WSL path '${path}' to Windows: '${normalized}'")
            return normalized
        }

        // Priority 2: Handle Linux absolute paths → Workspace mapping
        if (isLinuxAbsolutePath(normalized)) {
            normalized = mapLinuxPathToWorkspace(normalized)
            log.debug("Mapped Linux path '${path}' to workspace: '${normalized}'")
            return normalized
        }

        // Priority 3: Resolve relative paths against project root
        Path p = Paths.get(normalized)
        if (!p.isAbsolute() && activeProjectRoot) {
            String resolvedRoot = activeProjectRoot.replace('\\', '/')
            normalized = Paths.get(resolvedRoot, normalized)
                .toAbsolutePath().normalize().toString().replace('\\', '/')
            log.debug("Resolved relative path '${path}' to '${normalized}'")
            return normalized
        }

        // Priority 4: Windows absolute paths - just normalize slashes
        return normalized
    }

    /**
     * Detect if a path is a Linux absolute path (not WSL mount)
     * Examples: /home/claude/file.md, /tmp/data.txt, /var/log/app.log
     */
    private boolean isLinuxAbsolutePath(String path) {
        if (!path.startsWith('/')) return false
        if (path.startsWith('/mnt/')) return false  // WSL mounts handled separately
        
        // Check if it matches common Linux absolute path patterns
        return LINUX_ABSOLUTE_PREFIXES.any { path.startsWith(it) } || 
               path ==~ /^\/[a-zA-Z0-9_-]+\/.*/ // Generic /something/... pattern
    }

    /**
     * Map a Linux absolute path to the configured workspace directory
     * 
     * v0.0.4 NEW: Enables cross-platform operation
     * 
     * Strategy:
     * - If claude-workspace-root is configured, map to that
     * - Otherwise, fall back to active-project-root
     * - Strip leading slash and common prefixes (home/claude → claude)
     * 
     * Examples:
     * - /home/claude/file.md → <workspace>/claude/file.md
     * - /tmp/data.txt → <workspace>/tmp/data.txt
     * - /workspace/project/file.md → <workspace>/project/file.md
     */
    private String mapLinuxPathToWorkspace(String linuxPath) {
        // Determine workspace root (prefer claude-workspace-root, fall back to project root)
        String workspaceRoot = claudeWorkspaceRoot ?: activeProjectRoot
        
        if (!workspaceRoot) {
            log.warn("No workspace root configured - Linux path '${linuxPath}' cannot be mapped safely")
            throw new IllegalStateException(
                "Cannot map Linux path '${linuxPath}': no workspace root configured. " +
                "Set mcp.filesystem.claude-workspace-root or mcp.filesystem.active-project-root"
            )
        }
        
        // Normalize workspace root
        workspaceRoot = workspaceRoot.replace('\\', '/')
        
        // Strip leading slash from Linux path
        String relativePath = linuxPath.substring(1)
        
        // Strip common prefixes to create cleaner workspace structure
        // /home/claude/file.md → claude/file.md
        // /tmp/data.txt → tmp/data.txt
        if (relativePath.startsWith('home/claude/')) {
            relativePath = relativePath.substring('home/claude/'.length())
        } else if (relativePath.startsWith('home/')) {
            relativePath = relativePath.substring('home/'.length())
        } else if (relativePath.startsWith('workspace/')) {
            relativePath = relativePath.substring('workspace/'.length())
        }
        
        // Join workspace root with relative path
        Path result = Paths.get(workspaceRoot, relativePath)
            .toAbsolutePath().normalize()
        
        return result.toString().replace('\\', '/')
    }

    /**
     * Convert Windows path to WSL path
     * C:/Users/will → /mnt/c/Users/will
     */
    String convertWindowsToWsl(String windowsPath) {
        if (!windowsPath) return windowsPath

        String normalized = windowsPath.replace('\\', '/')

        if (normalized.startsWith('/mnt/')) {
            return normalized
        }

        if (normalized.matches('^[A-Za-z]:/.*')) {
            String driveLetter = normalized.substring(0, 1).toLowerCase()
            String pathWithoutDrive = normalized.substring(2)
            return "/mnt/${driveLetter}${pathWithoutDrive}"
        }

        return normalized
    }

    /**
     * Convert WSL path to Windows path
     * /mnt/c/Users/will → C:/Users/will
     */
    String convertWslToWindows(String wslPath) {
        if (!wslPath) return wslPath

        if (wslPath.startsWith('/mnt/')) {
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
     * Check if a path is absolute (after normalization)
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
