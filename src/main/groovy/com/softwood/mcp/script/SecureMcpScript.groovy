package com.softwood.mcp.script

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
 * Enhanced with typed CommandResult return values
 */
abstract class SecureMcpScript extends Script {
    
    // Injected services (set by GroovyScriptService) - properly typed
    FileSystemService fileSystemService
    PathService pathService
    ScriptExecutor scriptExecutor
    
    // Working directory for script execution
    String getWorkingDir() {
        binding.getVariable('workingDir') as String
    }
    
    // ========================================================================
    // File Operations (delegated to FileSystemService)
    // ========================================================================
    
    def readFile(String path, String encoding = 'UTF-8') {
        fileSystemService.readFile(path, encoding)
    }
    
    def writeFile(String path, String content, Map options = [:]) {
        String encoding = options.encoding ?: 'UTF-8'
        boolean backup = options.backup ?: false
        fileSystemService.writeFile(path, content, encoding, backup)
    }
    
    def listFiles(String path, Map options = [:]) {
        String pattern = options.pattern
        boolean recursive = options.recursive ?: false
        fileSystemService.listDirectory(path, pattern, recursive)
    }
    
    def searchFiles(String directory, String contentPattern, String filePattern = null) {
        fileSystemService.searchFiles(directory, contentPattern, filePattern ?: '.*')
    }
    
    def copyFile(String source, String dest, boolean overwrite = false) {
        fileSystemService.copyFile(source, dest, overwrite)
    }
    
    def moveFile(String source, String dest, boolean overwrite = false) {
        fileSystemService.moveFile(source, dest, overwrite)
    }
    
    def deleteFile(String path, boolean recursive = false) {
        fileSystemService.deleteFile(path, recursive)
    }
    
    def createDirectory(String path) {
        fileSystemService.createDirectory(path)
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
        // Use gradlew.bat on Windows
        executeCommand('.\\gradlew.bat', args.toList())
    }
    
    CommandResult gradlew(String... args) {
        executeCommand('.\\gradlew.bat', args.toList())
    }
    
    // ========================================================================
    // PowerShell Operations (Whitelisted) - Returns CommandResult
    // ========================================================================
    
    CommandResult powershell(String command) {
        if (!isAllowedPowerShell(command)) {
            throw new SecurityException("PowerShell command not whitelisted: ${command.take(50)}")
        }
        executePowerShell(command)
    }
    
    CommandResult ps(String command) {
        powershell(command)
    }
    
    // Whitelist of safe PowerShell commands
    private static final List ALLOWED_POWERSHELL_PATTERNS = [
        ~/^Get-ChildItem.*/,
        ~/^Get-Content.*/,
        ~/^Get-Item.*/,
        ~/^Test-Path.*/,
        ~/^Get-Location.*/,
        ~/^Get-Process.*/,
        ~/^Select-Object.*/,
        ~/^Where-Object.*/,
        ~/^Measure-Object.*/,
        ~/^Sort-Object.*/,
        ~/^Group-Object.*/,
        ~/^Format-.*/,
        ~/^Out-.*/,
        ~/^Write-Host.*/,
        ~/^Write-Output.*/,
        // Explicitly allow piping
        ~/.*\|.*/
    ]
    
    // Blacklist of dangerous PowerShell commands
    private static final List BLOCKED_POWERSHELL_PATTERNS = [
        ~/.*Remove-Item.*/,
        ~/.*Clear-.*Content.*/,
        ~/.*Stop-Computer.*/,
        ~/.*Restart-Computer.*/,
        ~/.*Format-Volume.*/,
        ~/.*Clear-Disk.*/,
        ~/.*Remove-.*Disk.*/,
        ~/.*Invoke-Expression.*/,
        ~/.*Invoke-Command.*/,
        ~/.*Start-Process.*/,
        ~/.*New-Service.*/,
        ~/.*Set-ExecutionPolicy.*/
    ]
    
    private boolean isAllowedPowerShell(String command) {
        String normalized = command.trim()
        
        // Check blacklist first
        if (BLOCKED_POWERSHELL_PATTERNS.any { pattern -> normalized ==~ pattern }) {
            return false
        }
        
        // Check whitelist
        return ALLOWED_POWERSHELL_PATTERNS.any { pattern -> normalized ==~ pattern }
    }
    
    private CommandResult executePowerShell(String command) {
        scriptExecutor.executePowerShell(command, workingDir)
    }
    
    // ========================================================================
    // Bash Operations (Whitelisted) - Returns CommandResult
    // ========================================================================
    
    CommandResult bash(String command) {
        if (!isAllowedBash(command)) {
            throw new SecurityException("Bash command not whitelisted: ${command.take(50)}")
        }
        executeBash(command)
    }
    
    // Whitelist of safe bash commands
    private static final List ALLOWED_BASH_PATTERNS = [
        ~/^ls.*/,
        ~/^cat.*/,
        ~/^grep.*/,
        ~/^find.*/,
        ~/^wc.*/,
        ~/^head.*/,
        ~/^tail.*/,
        ~/^echo.*/,
        ~/^pwd.*/,
        ~/^which.*/,
        ~/^whoami.*/,
        ~/^date.*/,
        ~/^file.*/,
        ~/^stat.*/,
        ~/^du.*/,
        ~/^df.*/,
        ~/^ps.*/,
        ~/^top.*/,
        ~/^awk.*/,
        ~/^sed.*/,
        ~/^sort.*/,
        ~/^uniq.*/,
        ~/^tr.*/,
        ~/^cut.*/,
        // Allow piping
        ~/.*\|.*/
    ]
    
    // Blacklist of dangerous bash commands
    private static final List BLOCKED_BASH_PATTERNS = [
        ~/.*rm .*/,
        ~/.*rm-.*/,
        ~/.*delete.*/,
        ~/.*chmod.*/,
        ~/.*chown.*/,
        ~/.*mkfs.*/,
        ~/.*dd.*/,
        ~/.*sudo.*/,
        ~/.*su .*/,
        ~/.*shutdown.*/,
        ~/.*reboot.*/,
        ~/.*kill.*/,
        ~/.*pkill.*/,
        ~/.*eval.*/,
        ~/.*exec.*/,
        ~/.*source.*/,
        ~/.*\\.\\/.*/  // No executing files
    ]
    
    private boolean isAllowedBash(String command) {
        String normalized = command.trim()
        
        // Check blacklist first
        if (BLOCKED_BASH_PATTERNS.any { pattern -> normalized ==~ pattern }) {
            return false
        }
        
        // Check whitelist
        return ALLOWED_BASH_PATTERNS.any { pattern -> normalized ==~ pattern }
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
