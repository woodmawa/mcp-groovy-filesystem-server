package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.model.ScriptExecutionResult
import com.softwood.mcp.script.SecureMcpScript
import groovy.util.logging.Slf4j
import org.codehaus.groovy.control.CompilerConfiguration
import org.springframework.stereotype.Service

/**
 * Service for executing Groovy scripts with secure base script
 * Enhanced with security validation and audit logging
 */
@Service
@Slf4j
class GroovyScriptService {
    
    private final FileSystemService fileSystemService
    private final PathService pathService
    private final ScriptExecutor scriptExecutor
    private final ScriptSecurityService securityService
    private final AuditService auditService
    private final CommandWhitelistConfig whitelistConfig
    private final GitHubService githubService
    
    GroovyScriptService(
        FileSystemService fileSystemService,
        PathService pathService,
        ScriptExecutor scriptExecutor,
        ScriptSecurityService securityService,
        AuditService auditService,
        CommandWhitelistConfig whitelistConfig,
        GitHubService githubService
    ) {
        this.fileSystemService = fileSystemService
        this.pathService = pathService
        this.scriptExecutor = scriptExecutor
        this.securityService = securityService
        this.auditService = auditService
        this.whitelistConfig = whitelistConfig
        this.githubService = githubService
    }
    
    /**
     * Execute a Groovy script with security validation
     */
    ScriptExecutionResult executeScript(String scriptText, String workingDirectory) {
        long startTime = System.currentTimeMillis()
        
        try {
            // Validate script security
            securityService.validateScript(scriptText, workingDirectory)
            
            // Validate working directory
            String normalized = pathService.normalizePath(workingDirectory)
            if (!fileSystemService.isPathAllowed(normalized)) {
                auditService.logUnauthorizedAccess(normalized, "Working directory not in allowed list")
                throw new SecurityException("Working directory not allowed: ${normalized}")
            }
            
            log.info("Executing script in: ${normalized}")
            
            // Execute the script
            ScriptExecutionResult result = doExecuteScript(scriptText, normalized, startTime)
            
            // Audit log
            long duration = System.currentTimeMillis() - startTime
            auditService.logScriptExecution(
                normalized,
                scriptText.length(),
                result.success,
                duration,
                result.error
            )
            
            return result
            
        } catch (SecurityException e) {
            long duration = System.currentTimeMillis() - startTime
            log.error("Security violation during script execution", e)
            auditService.logSecurityViolation("Script Execution", e.message, workingDirectory)
            return ScriptExecutionResult.failure(e.message, [], workingDirectory, duration)
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            log.error("Script execution failed", e)
            return ScriptExecutionResult.failure(
                e.message,
                e.stackTrace.take(5).collect { it.toString() },
                workingDirectory,
                duration
            )
        }
    }
    
    /**
     * Internal script execution method
     */
    private ScriptExecutionResult doExecuteScript(String scriptText, String workingDir, long startTime) {
        // Create binding with services and output capture
        def scriptOutput = []
        def binding = new Binding([
            workingDir: workingDir,
            scriptOutput: scriptOutput
        ])
        
        // Configure compiler with secure base script
        def config = new CompilerConfiguration()
        config.scriptBaseClass = SecureMcpScript.name
        
        // Create shell and execute
        def shell = new GroovyShell(binding, config)
        def script = shell.parse(scriptText)
        
        // Inject services into the script instance
        if (script instanceof SecureMcpScript) {
            script.fileSystemService = fileSystemService
            script.pathService = pathService
            script.scriptExecutor = scriptExecutor
            script.whitelistConfig = whitelistConfig
            script.githubService = githubService
        }
        
        // Now run the script
        def result = script.run()
        
        long duration = System.currentTimeMillis() - startTime
        
        return ScriptExecutionResult.success(
            result,
            scriptOutput,
            workingDir,
            duration
        )
    }
    
    /**
     * Execute script with custom timeout
     */
    ScriptExecutionResult executeScriptWithTimeout(
        String scriptText,
        String workingDirectory,
        int timeoutSeconds
    ) {
        // Create a future for timeout
        def executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        def future = executor.submit({
            executeScript(scriptText, workingDirectory)
        } as java.util.concurrent.Callable<ScriptExecutionResult>)
        
        try {
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true)
            auditService.logSecurityViolation(
                "Script Timeout",
                "Script exceeded timeout of ${timeoutSeconds}s",
                workingDirectory
            )
            throw new RuntimeException("Script execution timed out after ${timeoutSeconds} seconds")
        } finally {
            executor.shutdown()
        }
    }
}