package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.script.SecureMcpScript
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.codehaus.groovy.control.CompilerConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.TimeUnit

/**
 * ExecuteService — handles the execute tool.
 *
 * actions: bash | powershell | groovy | cmd
 *
 * Security: all scripts validated by SecurityService before execution.
 * Timeouts enforced via Process.waitFor with configurable limit.
 *
 * v0.0.7 — Phase 3 Execution + Tools
 */
@Service
@Slf4j
@CompileStatic
class ExecuteService extends AbstractFileService implements ToolHandler {

    @Autowired
    SecurityService securityService

    @Autowired
    CommandWhitelistConfig whitelistConfig

    @Value('${mcp.script.max-execution-time-seconds:60}')
    int maxExecutionTimeSeconds

    @Value('${mcp.script.enable-bash:true}')
    boolean enableBash

    @Value('${mcp.script.enable-powershell:true}')
    boolean enablePowershell

    @Value('${mcp.script.enable-groovy:true}')
    boolean enableGroovy

    @Value('${mcp.script.enable-cmd:true}')
    boolean enableCmd

    ExecuteService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : 'execute',
            description: 'Execute scripts or shell commands. Actions: bash|powershell|groovy|cmd. Scripts are validated for dangerous patterns before execution. Working directory must be within allowed directories.',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string', enum: ['bash', 'powershell', 'groovy', 'cmd'],
                              description: 'Execution environment'],
                    script : [type: 'string', description: 'Script or command to execute'],
                    options: [type: 'object', description: 'workingDir (string), timeout (int seconds), args (list), env (map)',
                              properties: [
                                  workingDir: [type: 'string'],
                                  timeout   : [type: 'integer'],
                                  args      : [type: 'array', items: [type: 'string']],
                                  env       : [type: 'object']
                              ]]
                ],
                required  : ['action', 'script']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == 'execute' }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action  = arguments.action as String
            String script  = arguments.script as String
            Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>

            String workingDir = options.workingDir as String ?: activeProjectRoot ?: allowedDirectories[0]
            int timeout       = (options.timeout as Integer) ?: maxExecutionTimeSeconds

            // Normalize and validate working dir
            workingDir = pathService.normalizePath(workingDir)
            if (!isPathAllowed(workingDir)) {
                throw new SecurityException("Working directory not in allowed list: ${sanitize(workingDir)}")
            }

            // Security validation
            securityService.validateScript(script, workingDir)

            switch (action) {
                case 'bash'      : return doBash(script, workingDir, timeout, requestId)
                case 'powershell': return doPowershell(script, workingDir, timeout, requestId)
                case 'groovy'    : return doGroovy(script, workingDir, timeout, options, requestId)
                case 'cmd'       : return doCmd(script, workingDir, timeout, requestId)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown execute action: ${action}")
            }
        } catch (SecurityException e) {
            log.warn("execute security violation: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (Exception e) {
            log.error("execute error: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private McpResponse doBash(String script, String workingDir, int timeout, Object requestId) {
        if (!enableBash) return McpResponse.error(requestId, -32603, "Bash execution is disabled")
        if (!whitelistConfig.isBashAllowed(script)) {
            log.warn("Bash script rejected by whitelist/blacklist config")
            return McpResponse.error(requestId, -32603, "Bash command not permitted by whitelist configuration")
        }
        List<String> cmd = ['bash', '-c', script]
        return runProcess(cmd, workingDir, timeout, 'bash', requestId)
    }

    private McpResponse doPowershell(String script, String workingDir, int timeout, Object requestId) {
        if (!enablePowershell) return McpResponse.error(requestId, -32603, "PowerShell execution is disabled")
        if (!whitelistConfig.isPowershellAllowed(script)) {
            log.warn("PowerShell script rejected by whitelist/blacklist config")
            return McpResponse.error(requestId, -32603, "PowerShell command not permitted by whitelist configuration")
        }
        List<String> cmd = ['powershell', '-NoProfile', '-NonInteractive', '-Command', script]
        return runProcess(cmd, workingDir, timeout, 'powershell', requestId)
    }

    private McpResponse doCmd(String script, String workingDir, int timeout, Object requestId) {
        if (!enableCmd) return McpResponse.error(requestId, -32603, "CMD execution is disabled")
        List<String> cmd = ['cmd', '/c', script]
        return runProcess(cmd, workingDir, timeout, 'cmd', requestId)
    }

    private McpResponse doGroovy(String script, String workingDir, int timeout,
                                 Map<String, Object> options, Object requestId) {
        if (!enableGroovy) return McpResponse.error(requestId, -32603, "Groovy execution is disabled")

        long start = System.currentTimeMillis()
        try {
            CompilerConfiguration config = new CompilerConfiguration()
            config.scriptBaseClass = SecureMcpScript.name

            Binding binding = new Binding()
            binding.setVariable('workingDir', workingDir)
            binding.setVariable('args', (options.args as List<String>) ?: [])

            GroovyShell shell = new GroovyShell(this.class.classLoader, binding, config)
            Object result = shell.evaluate(script)

            long durationMs = System.currentTimeMillis() - start
            String output   = result != null ? sanitize(result.toString()) : ''

            log.info("Groovy script executed in {}ms, workingDir={}", durationMs, workingDir)
            return textResponse(requestId, [
                action    : 'groovy',
                success   : true,
                output    : output,
                durationMs: durationMs
            ])
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start
            log.warn("Groovy script failed: {}", sanitize(e.message))
            return textResponse(requestId, [
                action    : 'groovy',
                success   : false,
                error     : sanitize(e.message),
                durationMs: durationMs
            ])
        }
    }

    // -----------------------------------------------------------------------
    // Process runner
    // -----------------------------------------------------------------------

    private McpResponse runProcess(List<String> cmd, String workingDir, int timeout,
                                   String action, Object requestId) {
        long start = System.currentTimeMillis()
        Process process = null
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
            pb.directory(new File(workingDir))
            pb.redirectErrorStream(false)

            process = pb.start()

            // Capture stdout + stderr concurrently to avoid blocking
            StringBuilder stdout = new StringBuilder()
            StringBuilder stderr = new StringBuilder()

            Thread stdoutThread = Thread.ofVirtual().start({
                process.inputStream.eachLine { String line -> stdout.append(sanitize(line)).append('\n') }
            })
            Thread stderrThread = Thread.ofVirtual().start({
                process.errorStream.eachLine { String line -> stderr.append(sanitize(line)).append('\n') }
            })

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS)
            stdoutThread.join(2000)
            stderrThread.join(2000)

            long durationMs = System.currentTimeMillis() - start

            if (!finished) {
                process.destroyForcibly()
                return textResponse(requestId, [
                    action    : action,
                    success   : false,
                    error     : "Process timed out after ${timeout}s",
                    durationMs: durationMs
                ])
            }

            int exitCode = process.exitValue()
            log.info("execute {}: exitCode={}, duration={}ms, workingDir={}", action, exitCode, durationMs, workingDir)

            return textResponse(requestId, [
                action    : action,
                success   : exitCode == 0,
                exitCode  : exitCode,
                stdout    : stdout.toString().take(50000),
                stderr    : stderr.toString().take(10000),
                durationMs: durationMs
            ])
        } catch (Exception e) {
            process?.destroyForcibly()
            long durationMs = System.currentTimeMillis() - start
            log.error("execute {} failed: {}", action, sanitize(e.message))
            return textResponse(requestId, [
                action    : action,
                success   : false,
                error     : sanitize(e.message),
                durationMs: durationMs
            ])
        }
    }
}
