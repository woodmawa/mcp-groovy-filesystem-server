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
            description: 'Execute scripts or shell commands. Actions: bash|powershell|groovy|cmd.\nScripts validated against dangerous patterns. Working directory must be in allowed directories.',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string', enum: ['bash', 'powershell', 'groovy', 'cmd'],
                              description: 'Execution environment'],
                    script : [type: 'string', description: 'Script or command to execute'],
                    options: [type: 'object', description: 'workingDir (string), timeout (int seconds), args (list), env (map), verbose (bool). IMPORTANT: maxStdout (int chars, default 50000 ~12K tokens): cap stdout in response - set lower to save context window. maxStdout (int chars, default 50000 ~12K tokens), maxStderr (int chars, default 5000 ~1.2K tokens)',
                              properties: [
                                  workingDir: [type: 'string'],
                                  timeout   : [type: 'integer'],
                                  args      : [type: 'array', items: [type: 'string']],
                                  env       : [type: 'object'],
                                  verbose   : [type: 'boolean', description: 'Set true for full response with action/durationMs. Default: compact (success/exitCode/stdout/stderr only)'],
                                  maxStdout : [type: 'integer', description: 'Max chars of stdout to return (default 50000)'],
                                  maxStderr : [type: 'integer', description: 'Max chars of stderr to return (default 5000)']
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

            // Extract env overrides from options (was previously silently ignored)
            Map<String, String> envOverrides = options.env ? (options.env as Map<String, String>) : null

            switch (action) {
                case 'bash'      : return doBash(script, workingDir, timeout, envOverrides, options, requestId)
                case 'powershell': return doPowershell(script, workingDir, timeout, envOverrides, options, requestId)
                case 'groovy'    : return doGroovy(script, workingDir, timeout, options, requestId)
                case 'cmd'       : return doCmd(script, workingDir, timeout, envOverrides, options, requestId)
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

    private McpResponse doBash(String script, String workingDir, int timeout,
                               Map<String, String> envOverrides, Map<String, Object> options, Object requestId) {
        if (!enableBash) return McpResponse.error(requestId, -32603, "Bash execution is disabled")
        if (!whitelistConfig.isBashAllowed(script)) {
            log.warn("Bash script rejected by whitelist/blacklist config")
            return McpResponse.error(requestId, -32603, "Bash command not permitted by whitelist configuration")
        }
        List<String> cmd = ['bash', '-c', script]
        return runProcess(cmd, workingDir, timeout, 'bash', requestId, envOverrides, options)
    }

    private McpResponse doPowershell(String script, String workingDir, int timeout,
                                    Map<String, String> envOverrides, Map<String, Object> options, Object requestId) {
        if (!enablePowershell) return McpResponse.error(requestId, -32603, "PowerShell execution is disabled")
        if (!whitelistConfig.isPowershellAllowed(script)) {
            log.warn("PowerShell script rejected by whitelist/blacklist config")
            return McpResponse.error(requestId, -32603, "PowerShell command not permitted by whitelist configuration")
        }
        // Always write script to a temp .ps1 file and invoke via -File.
        // Passing scripts via -Command mangles multi-line scripts, backtick escapes,
        // and regex string literals during MCP JSON serialisation -> ProcessBuilder arg passing.
        // -File bypasses all of that: PowerShell reads the file directly, no quoting issues.
        File tempScript = null
        try {
            tempScript = File.createTempFile('mcp-ps-', '.ps1')
            tempScript.text = script
            List<String> cmd = ['powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', tempScript.absolutePath]
            return runProcess(cmd, workingDir, timeout, 'powershell', requestId, envOverrides, options)
        } finally {
            tempScript?.delete()
        }
    }

    private McpResponse doCmd(String script, String workingDir, int timeout,
                             Map<String, String> envOverrides, Map<String, Object> options, Object requestId) {
        if (!enableCmd) return McpResponse.error(requestId, -32603, "CMD execution is disabled")
        if (!whitelistConfig.isCmdAllowed(script)) {
            log.warn("CMD script rejected by whitelist/blacklist config")
            return McpResponse.error(requestId, -32603, "CMD command not permitted by whitelist configuration")
        }
        List<String> cmd = ['cmd', '/c', script]
        return runProcess(cmd, workingDir, timeout, 'cmd', requestId, envOverrides, options)
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
            if (isWriteCompact(options)) {
                return textResponse(requestId, [success: true, output: output])
            }
            return textResponse(requestId, [
                action    : 'groovy',
                success   : true,
                output    : output,
                durationMs: durationMs
            ])
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start
            log.warn("Groovy script failed: {}", sanitize(e.message))
            if (isWriteCompact(options)) {
                return textResponse(requestId, [success: false, error: sanitize(e.message)])
            }
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
                                   String action, Object requestId,
                                   Map<String, String> envOverrides = null,
                                   Map<String, Object> options = null) {
        int maxStdout = (options?.maxStdout as Integer) ?: 50000
        int maxStderr = (options?.maxStderr as Integer) ?: 5000
        boolean compact = isWriteCompact(options ?: ([:] as Map<String, Object>))

        long start = System.currentTimeMillis()
        Process process = null
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
            pb.directory(new File(workingDir))
            pb.redirectErrorStream(false)
            if (envOverrides) pb.environment().putAll(envOverrides)

            process = pb.start()

            StringBuilder stdout = new StringBuilder()
            StringBuilder stderr = new StringBuilder()

            // FIX-6: cap inside loop to prevent heap fill; drain remainder to avoid child process blocking
            int capturedOut = 0
            int capturedErr = 0
            Thread stdoutThread = Thread.ofVirtual().start({
                process.inputStream.eachLine { String line ->
                    if (capturedOut < maxStdout) {
                        String s = sanitize(line)
                        stdout.append(s).append('\n')
                        capturedOut += s.length() + 1
                    } // else: drain without storing
                }
            })
            Thread stderrThread = Thread.ofVirtual().start({
                process.errorStream.eachLine { String line ->
                    if (capturedErr < maxStderr) {
                        String s = sanitize(line)
                        stderr.append(s).append('\n')
                        capturedErr += s.length() + 1
                    } // else: drain without storing
                }
            })

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS)
            long elapsedMs = System.currentTimeMillis() - start
            long remainingMs = Math.max(500L, (timeout * 1000L) - elapsedMs)
            stdoutThread.join(remainingMs)
            stderrThread.join(remainingMs)

            long durationMs = System.currentTimeMillis() - start

            if (!finished) {
                process.destroyForcibly()
                return textResponse(requestId, [
                    success: false,
                    error  : "Process timed out after ${timeout}s"
                ])
            }

            int exitCode = process.exitValue()
            log.info("execute {}: exitCode={}, duration={}ms, workingDir={}", action, exitCode, durationMs, workingDir)
            // FIX-6: already capped in loop above - no .take() needed
            // FIX-H: add truncation flags so caller knows output was cut
            String stdoutStr = stdout.toString()
            String stderrStr = stderr.toString()
            boolean stdoutTruncated = capturedOut >= maxStdout
            boolean stderrTruncated = capturedErr >= maxStderr

            if (compact) {
                Map<String, Object> cr = [success: exitCode == 0, exitCode: exitCode,
                                          stdout: stdoutStr, stderr: stderrStr] as Map<String, Object>
                if (stdoutTruncated) cr.stdout_truncated = true
                if (stderrTruncated) cr.stderr_truncated = true
                return textResponse(requestId, cr)
            }
            Map<String, Object> er = [action: action, success: exitCode == 0, exitCode: exitCode,
                                      stdout: stdoutStr, stderr: stderrStr, durationMs: durationMs] as Map<String, Object>
            if (stdoutTruncated) er.stdout_truncated = true
            if (stderrTruncated) er.stderr_truncated = true
            return textResponse(requestId, er)
        } catch (Exception e) {
            process?.destroyForcibly()
            long durationMs = System.currentTimeMillis() - start
            log.error("execute {} failed: {}", action, sanitize(e.message))
            return textResponse(requestId, [
                success: false,
                error  : sanitize(e.message),
                durationMs: durationMs
            ])
        }
    }
}
