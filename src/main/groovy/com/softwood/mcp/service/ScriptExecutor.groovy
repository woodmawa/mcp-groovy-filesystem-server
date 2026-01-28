package com.softwood.mcp.service

import com.softwood.mcp.model.CommandResult
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

/**
 * Executes external commands (PowerShell, Bash, general commands)
 * Now with typed results and audit logging
 */
@Service
@Slf4j
class ScriptExecutor {
    
    private static final int DEFAULT_TIMEOUT_SECONDS = 60
    
    private final AuditService auditService
    
    ScriptExecutor(AuditService auditService) {
        this.auditService = auditService
    }
    
    /**
     * Execute a PowerShell command
     */
    CommandResult executePowerShell(String command, String workingDir) {
        long startTime = System.currentTimeMillis()
        
        try {
            List<String> cmdList = ['powershell', '-NoProfile', '-NonInteractive', '-Command', command]
            Process process = cmdList.execute(null, new File(workingDir))
            
            CommandResult result = captureOutput(process, DEFAULT_TIMEOUT_SECONDS, startTime)
            
            auditService.logCommandExecution(
                "PowerShell",
                command,
                workingDir,
                result.success,
                result.exitCode,
                result.durationMs
            )
            
            return result
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            log.error("PowerShell execution failed", e)
            auditService.logCommandExecution("PowerShell", command, workingDir, false, -1, duration)
            throw e
        }
    }
    
    /**
     * Execute a Bash command (via WSL)
     */
    CommandResult executeBash(String command, String workingDir) {
        long startTime = System.currentTimeMillis()
        
        try {
            // Convert Windows path to WSL path for working directory
            String wslPath = convertToWslPath(workingDir)
            
            String fullCommand = "cd ${wslPath} && ${command}"
            List<String> cmdList = ['wsl', 'bash', '-c', fullCommand]
            Process process = cmdList.execute()
            
            CommandResult result = captureOutput(process, DEFAULT_TIMEOUT_SECONDS, startTime)
            
            auditService.logCommandExecution(
                "Bash",
                command,
                workingDir,
                result.success,
                result.exitCode,
                result.durationMs
            )
            
            return result
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            log.error("Bash execution failed", e)
            auditService.logCommandExecution("Bash", command, workingDir, false, -1, duration)
            throw e
        }
    }
    
    /**
     * Execute a general command
     */
    CommandResult executeCommand(String executable, List args, String workingDir) {
        long startTime = System.currentTimeMillis()
        
        try {
            List<String> cmdList = new ArrayList<String>()
            cmdList.add(executable)
            cmdList.addAll(args)
            
            Process process = cmdList.execute(null, new File(workingDir))
            
            CommandResult result = captureOutput(process, DEFAULT_TIMEOUT_SECONDS, startTime)
            
            String commandType = executable.toLowerCase()
            auditService.logCommandExecution(
                commandType.capitalize(),
                "${executable} ${args.join(' ')}",
                workingDir,
                result.success,
                result.exitCode,
                result.durationMs
            )
            
            return result
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            log.error("Command execution failed: {} {}", executable, args, e)
            auditService.logCommandExecution(executable, args.join(' '), workingDir, false, -1, duration)
            throw e
        }
    }
    
    /**
     * Capture output from a process with timing
     */
    private CommandResult captureOutput(Process process, int timeoutSeconds, long startTime) {
        StringBuilder stdout = new StringBuilder()
        StringBuilder stderr = new StringBuilder()
        
        // Capture output streams
        Thread outThread = Thread.start {
            process.inputStream.eachLine { String line -> 
                stdout.append(line).append('\n') 
            }
        }
        
        Thread errThread = Thread.start {
            process.errorStream.eachLine { String line -> 
                stderr.append(line).append('\n') 
            }
        }
        
        // Wait for completion with timeout
        boolean completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        
        if (!completed) {
            process.destroy()
            long duration = System.currentTimeMillis() - startTime
            throw new RuntimeException("Command timed out after ${timeoutSeconds} seconds")
        }
        
        // Wait for output capture to complete
        outThread.join(1000)
        errThread.join(1000)
        
        int exitCode = process.exitValue()
        long duration = System.currentTimeMillis() - startTime
        
        return CommandResult.of(
            exitCode,
            stdout.toString(),
            stderr.toString(),
            duration
        )
    }
    
    /**
     * Convert Windows path to WSL path
     */
    private String convertToWslPath(String windowsPath) {
        String normalized = windowsPath.replace('\\', '/')
        
        if (normalized.matches('^[A-Za-z]:/.*')) {
            String driveLetter = normalized[0].toLowerCase()
            String pathWithoutDrive = normalized.substring(2)
            return "/mnt/${driveLetter}${pathWithoutDrive}"
        }
        
        return normalized
    }
}
