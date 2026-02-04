package com.softwood.mcp.script

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.model.CommandResult
import com.softwood.mcp.service.FileSystemService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.ScriptExecutor

/**
 * Secure base script for MCP Groovy script execution
 * Provides whitelisted access to:
 * - File operations (via FileSystemService)
 * - PowerShell commands (whitelisted)
 * - Bash commands (whitelisted)
 * - Git operations
 * - Gradle operations
 *
 * Enhanced with typed CommandResult return values and configurable whitelists
 */
abstract class SecureMcpScript extends Script {

    // Injected services (set by GroovyScriptService) - properly typed
    FileSystemService fileSystemService
    PathService pathService
    ScriptExecutor scriptExecutor
    CommandWhitelistConfig whitelistConfig

    // Working directory for script execution
    String getWorkingDir() {
        binding.getVariable('workingDir') as String
    }

    /**
     * Sanitize string by removing control characters (except newlines and tabs)
     * Ensures clean exception messages for JSON serialization
     */
    private static String sanitize(String text) {
        if (!text) return text
        // Remove control characters except \n (10) and \t (9)
        return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
    }

    /**
     * Resolve path against working directory if relative
     * Absolute paths are returned unchanged
     */
    private String resolvePath(String path) {
        if (!path) return path
        
        // Check if already absolute (Windows: C:/ or Linux: /)
        if (path.matches('^[A-Za-z]:[\\\\/].*') || path.startsWith('/')) {
            return path
        }
        
        // Relative path - resolve against workingDir
        return new File(workingDir, path).canonicalPath
    }

    // ========================================================================
    // File Operations (delegated to FileSystemService)
    // ========================================================================

    def readFile(String path, String encoding = 'UTF-8') {
        fileSystemService.readFile(resolvePath(path), encoding)
    }

    def writeFile(String path, String content, Map options = [:]) {
        String encoding = options.encoding ?: 'UTF-8'
        boolean backup = options.backup ?: false
        fileSystemService.writeFile(resolvePath(path), content, encoding, backup)
    }

    def listFiles(String path, Map options = [:]) {
        String pattern = options.pattern
        boolean recursive = options.recursive ?: false
        fileSystemService.listDirectory(resolvePath(path), pattern, recursive)
    }

    def searchFiles(String directory, String contentPattern, String filePattern = null) {
        fileSystemService.searchFiles(resolvePath(directory), contentPattern, filePattern ?: '.*')
    }

    def copyFile(String source, String dest, boolean overwrite = false) {
        fileSystemService.copyFile(resolvePath(source), resolvePath(dest), overwrite)
    }

    def moveFile(String source, String dest, boolean overwrite = false) {
        fileSystemService.moveFile(resolvePath(source), resolvePath(dest), overwrite)
    }

    def deleteFile(String path, boolean recursive = false) {
        fileSystemService.deleteFile(resolvePath(path), recursive)
    }

    def createDirectory(String path) {
        fileSystemService.createDirectory(resolvePath(path))
    }

    def readMultipleFiles(List<String> paths) {
        fileSystemService.readMultipleFiles(paths.collect { resolvePath(it) })
    }

    def getFileInfo(String path) {
        fileSystemService.getFileInfo(resolvePath(path))
    }

    def listFilesWithSizes(String path, String sortBy = 'name') {
        fileSystemService.listDirectoryWithSizes(resolvePath(path), sortBy)
    }

    def getDirectoryTree(String path, List<String> excludePatterns = []) {
        fileSystemService.getDirectoryTree(resolvePath(path), excludePatterns)
    }

    // ========================================================================
    // Path Operations
    // ========================================================================

    String toWslPath(String windowsPath) {
        pathService.convertWindowsToWsl(windowsPath)
    }

    String toWindowsPath(String wslPath) {
        pathService.convertWslToWindows(wslPath)
    }

    String normalizePath(String path) {
        pathService.normalizePath(path)
    }

    Map<String, String> getPathInfo(String path) {
        pathService.getPathRepresentations(path)
    }

    // ========================================================================
    // Git Operations - Returns CommandResult
    // ========================================================================

    CommandResult git(String... args) {
        executeCommand('git', args.toList())
    }

    // ========================================================================
    // Gradle Operations - Returns CommandResult
    // ========================================================================

    CommandResult gradle(String... args) {
        // Use gradlew.bat through cmd.exe on Windows
        List<String> cmdArgs = ['cmd', '/c', 'gradlew.bat'] + args.toList()
        executeCommand(cmdArgs[0], cmdArgs.drop(1))
    }

    CommandResult gradlew(String... args) {
        // Use gradlew.bat through cmd.exe on Windows
        List<String> cmdArgs = ['cmd', '/c', 'gradlew.bat'] + args.toList()
        executeCommand(cmdArgs[0], cmdArgs.drop(1))
    }

    // ========================================================================
    // PowerShell Operations (Whitelisted) - Returns CommandResult
    // ========================================================================

    CommandResult powershell(String command) {
        if (!whitelistConfig.isPowershellAllowed(command)) {
            throw new SecurityException("PowerShell command not whitelisted: ${sanitize(command.take(50))}")
        }
        executePowerShell(command)
    }

    CommandResult ps(String command) {
        powershell(command)
    }

    private CommandResult executePowerShell(String command) {
        scriptExecutor.executePowerShell(command, workingDir)
    }

    // ========================================================================
    // Bash Operations (Whitelisted) - Returns CommandResult
    // ========================================================================

    CommandResult bash(String command) {
        if (!whitelistConfig.isBashAllowed(command)) {
            throw new SecurityException("Bash command not whitelisted: ${sanitize(command.take(50))}")
        }
        executeBash(command)
    }

    private CommandResult executeBash(String command) {
        scriptExecutor.executeBash(command, workingDir)
    }

    // ========================================================================
    // Generic Command Execution (Used by git, gradle, etc.) - Returns CommandResult
    // ========================================================================

    private CommandResult executeCommand(String executable, List args) {
        scriptExecutor.executeCommand(executable, args, workingDir)
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    void println(Object message) {
        // Capture output for return to MCP
        def output = binding.getVariable('scriptOutput') as List
        output.add(message.toString())
    }

    void print(Object message) {
        def output = binding.getVariable('scriptOutput') as List
        if (output.isEmpty()) {
            output.add(message.toString())
        } else {
            // Append to last element
            String lastItem = output[-1] as String
            output[-1] = lastItem + message.toString()
        }
    }
}
