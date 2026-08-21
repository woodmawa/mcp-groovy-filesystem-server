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
import java.util.regex.Pattern

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

    @Autowired
    ExecuteJobRegistry jobRegistry

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

    @Value('${mcp.script.enable-python:false}')
    boolean enablePython

    @Value('${mcp.script.python-home:}')
    String pythonHome

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
            description: 'Execute scripts or shell commands. Actions: bash|powershell|groovy|cmd|python.\nScripts validated against dangerous patterns. Working directory must be in allowed directories.\nMULTI-LINE: all actions run every line of a multi-line script. Lines execute in order and the LAST command\'s exit code is returned -- a mid-script failure does not abort the rest (same contract as bash -c, which has no set -e). Check state explicitly rather than trusting a single exitCode when a script mutates something. (cmd silently ran only the first line before FS 0.9.11.)\nASYNC: set options.async=true for work that may exceed the ~60s MCP client deadline (gradle builds, full test suites). It returns a jobId immediately instead of blocking -- the deadline is imposed by the client, not by FS, so options.timeout cannot extend it and a blocked call also serialises the calls behind it. Poll with action=job_status jobId=<id>; tail incrementally with action=job_output jobId=<id> sinceOffset=<nextOffset from the previous read>; stop with action=job_cancel; enumerate with action=job_list. Jobs are retained for 30 minutes after finishing.',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string', enum: ['bash', 'powershell', 'groovy', 'cmd', 'python',
                                                     'job_status', 'job_output', 'job_cancel', 'job_list'],
                              description: 'Execution environment, or a job_* action for background jobs (FS-EXEC-2)'],
                    script : [type: 'string', description: 'Script or command to execute'],
                    options: [type: 'object', description: 'workingDir (string), timeout (int seconds), args (list), env (map), verbose (bool). IMPORTANT: maxStdout (int chars, default 50000 ~12K tokens): cap stdout in response - set lower to save context window. maxStdout (int chars, default 50000 ~12K tokens), maxStderr (int chars, default 5000 ~1.2K tokens)',
                              properties: [
                                  workingDir: [type: 'string'],
                                  timeout   : [type: 'integer'],
                                  args      : [type: 'array', items: [type: 'string']],
                                  env       : [type: 'object'],
                                  verbose   : [type: 'boolean', description: 'Set true for full response with action/durationMs. Default: compact (success/exitCode/stdout/stderr only)'],
                                  maxStdout   : [type: 'integer', description: 'Max chars of stdout to return (default 50000)'],
                                  maxStderr   : [type: 'integer', description: 'Max chars of stderr to return (default 5000)'],
                                  grepPattern : [type: 'string', description: 'Java regex applied to stdout lines after execution. Only matching lines returned. Supports full Java regex including | alternation, e.g. "foo|bar", "RequestBuilder\\.class$".'],
                                  async       : [type: 'boolean', description: 'FS-EXEC-2: run in the background and return a jobId immediately. Use for anything that may exceed the ~60s client deadline.'],
                                  jobId       : [type: 'string', description: 'Job id, for job_status / job_output / job_cancel.'],
                                  sinceOffset : [type: 'integer', description: 'job_output: resume reading stdout from this character offset. Pass the nextOffset returned by the previous job_output call to tail without re-sending output you already have.']
                              ]]
                ],
                // script is required for the executor actions but meaningless for job_* ones,
                // which are rejected before validation if it is demanded here.
                required  : ['action']
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

            // FS-EXEC-2: job actions carry no script and no working directory, so they must be
            // handled before script/path validation rejects them for lacking both.
            if (action in ['job_status', 'job_output', 'job_cancel', 'job_list']) {
                return handleJobAction(action, options, requestId)
            }

            String workingDir = options.workingDir as String ?: activeProjectRoot ?: allowedDirectories[0]
            int timeout       = (options.timeout as Integer) ?: maxExecutionTimeSeconds

            // Normalize and validate working dir
            workingDir = pathService.normalizePath(workingDir)
            // Normalize and validate working dir
            workingDir = pathService.normalizePath(workingDir)
            if (!isPathAllowed(workingDir)) {
                String allowed = allowedDirectories.join(', ')
                throw new SecurityException("Working directory '${sanitize(workingDir)}' is not in the allowed list. Allowed directories: ${allowed}")
            }

            // Security validation -- pass executor type for per-executor pattern rules
            securityService.validateScript(script, workingDir, action)

            // Extract env overrides from options (was previously silently ignored)
            Map<String, String> envOverrides = options.env ? (options.env as Map<String, String>) : null

            switch (action) {
                case 'bash'      : return doBash(script, workingDir, timeout, envOverrides, options, requestId)
                case 'powershell': return doPowershell(script, workingDir, timeout, envOverrides, options, requestId)
                case 'groovy'    : return doGroovy(script, workingDir, timeout, options, requestId)
                case 'cmd'       : return doCmd(script, workingDir, timeout, envOverrides, options, requestId)
                case 'python'    : return doPython(script, workingDir, timeout, envOverrides, options, requestId)
                default:
                    return McpResponse.toolError(requestId, "Unknown execute action: ${action}")
            }
        } catch (SecurityException e) {
            log.warn("execute security violation: {}", sanitize(e.message))
            return McpResponse.toolError(requestId, "Security error: ${sanitize(e.message)}")
        } catch (Exception e) {
            log.error("execute error: {}", sanitize(e.message))
            return McpResponse.toolError(requestId, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    protected McpResponse doBash(String script, String workingDir, int timeout,
                               Map<String, String> envOverrides, Map<String, Object> options, Object requestId) {
        if (!enableBash) return McpResponse.toolError(requestId, "Bash execution is disabled")
        if (!whitelistConfig.isBashAllowed(script)) {
            log.warn("Bash script rejected by whitelist/blacklist config")
            return McpResponse.toolError(requestId, "Bash command not permitted by whitelist configuration")
        }
        List<String> cmd = ['bash', '-c', script]
        return runProcess(cmd, workingDir, timeout, 'bash', requestId, envOverrides, options)
    }

    private McpResponse doPowershell(String script, String workingDir, int timeout,
                                    Map<String, String> envOverrides, Map<String, Object> options, Object requestId) {
        if (!enablePowershell) return McpResponse.toolError(requestId, "PowerShell execution is disabled")
        if (!whitelistConfig.isPowershellAllowed(script)) {
            log.warn("PowerShell script rejected by whitelist/blacklist config")
            return McpResponse.toolError(requestId, "PowerShell command not permitted by whitelist configuration")
        }
        // Always write script to a temp .ps1 file and invoke via -File.
        // Passing scripts via -Command mangles multi-line scripts, backtick escapes,
        // and regex string literals during MCP JSON serialisation -> ProcessBuilder arg passing.
        // -File bypasses all of that: PowerShell reads the file directly, no quoting issues.
        // FS-EXEC-2: runProcess owns the temp file's lifetime from here. An async job outlives
        // this method, so deleting the script in a finally block here destroyed it before the
        // background job could run it.
        File tempScript = File.createTempFile('mcp-ps-', '.ps1')
        tempScript.text = script
        List<String> cmd = ['powershell', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', tempScript.absolutePath]
        return runProcess(cmd, workingDir, timeout, 'powershell', requestId, envOverrides, options, tempScript)
    }

    // protected, not private: @CompileStatic private methods are unreachable from a
    // @CompileDynamic Spock spec even in the same package (practice #1166 seam 2).
    protected McpResponse doCmd(String script, String workingDir, int timeout,
                             Map<String, String> envOverrides, Map<String, Object> options, Object requestId) {
        if (!enableCmd) return McpResponse.toolError(requestId, "CMD execution is disabled")
        if (!whitelistConfig.isCmdAllowed(script)) {
            log.warn("CMD script rejected by whitelist/blacklist config")
            return McpResponse.toolError(requestId, "CMD command not permitted by whitelist configuration")
        }
        // FS-EXEC-1: `cmd /c <script>` accepts a SINGLE command. Every line after the first was
        // discarded SILENTLY -- exitCode 0 plus the first command's stdout, indistinguishable
        // from full success. It cost a real `git commit` (observation 9881): the preceding
        // `git add` ran, the commit vanished, and the follow-up push said "Everything
        // up-to-date", which was true and read like success.
        //
        // doPowershell and doPython already write the script to a temp file for exactly this
        // class of problem, each with a comment saying so. cmd never got the same treatment.
        //
        // Semantics: all lines run in order and the LAST command's exit code is returned --
        // the same contract as `bash -c` here, which has no `set -e`. A mid-script failure does
        // not abort the remaining lines.
        // FS-EXEC-2: see doPowershell -- runProcess owns the temp file.
        File tempScript = File.createTempFile('mcp-cmd-', '.cmd')
        // @echo off, or the interpreter echoes each command into stdout beside its output.
        // CRLF: batch files are line-oriented and LF-only files misparse on some shells.
        String body = script.replaceAll('\r?\n', '\r\n')
        tempScript.text = '@echo off\r\n' + body
        List<String> cmd = ['cmd', '/c', tempScript.absolutePath]
        return runProcess(cmd, workingDir, timeout, 'cmd', requestId, envOverrides, options, tempScript)
    }

    private McpResponse doPython(String script, String workingDir, int timeout,
                                  Map<String, String> envOverrides, Map<String, Object> options,
                                  Object requestId) {
        if (!enablePython) {
            return McpResponse.toolError(requestId, 'Python execution is disabled. Set mcp.script.enable-python=true and ensure PYTHON_HOME is configured.')
        }

        // Resolve interpreter from PYTHON_HOME (env var, set at Machine scope on Windows)
        String interpreter
        if (pythonHome) {
            // Normalise separators — Spring may deliver with backslashes or forward slashes
            String home = pythonHome.replace('\\', '/')
            interpreter = "${home}/python.exe"
            File exe = new File(interpreter)
            if (!exe.exists()) {
                return McpResponse.toolError(requestId, "Python interpreter not found at PYTHON_HOME: ${sanitize(pythonHome)}. " +
                    "Expected: ${sanitize(interpreter)}")
            }
        } else {
            // No PYTHON_HOME — warn but attempt PATH fallback
            log.warn('PYTHON_HOME not set; attempting PATH fallback for python. ' +
                     'Set PYTHON_HOME for reliable resolution.')
            interpreter = 'python'
        }

        // Always write script to a temp .py file and invoke via the file path.
        // Passing scripts via '-c' mangles curly braces and other shell-special characters
        // during ProcessBuilder argument passing on Windows (Groovy GString / shell escaping).
        // Writing to a temp file bypasses all quoting issues: Python reads the file directly.
        // FS-EXEC-2: see doPowershell -- runProcess owns the temp file.
        File tempScript = File.createTempFile('mcp-py-', '.py')
        tempScript.text = script
        List<String> cmd = [interpreter, tempScript.absolutePath]
        return runProcess(cmd, workingDir, timeout, 'python', requestId, envOverrides, options, tempScript)
    }

    private McpResponse doGroovy(String script, String workingDir, int timeout,
                                 Map<String, Object> options, Object requestId) {
        if (!enableGroovy) return McpResponse.toolError(requestId, "Groovy execution is disabled")

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
                                   Map<String, Object> options = null,
                                   File tempScript = null) {
        int maxStdout        = (options?.maxStdout as Integer) ?: 50000
        int maxStderr        = (options?.maxStderr as Integer) ?: 5000
        String grepPattern   = options?.grepPattern as String
        boolean compact      = isWriteCompact(options ?: ([:] as Map<String, Object>))

        // FS-EXEC-2: async submit. The ~60s MCP-client deadline is not ours to raise, so for
        // long work we stop blocking underneath it -- return a job id now, poll cheaply later.
        if (options?.async) {
            return submitAsyncJob(cmd, workingDir, timeout, action, requestId, envOverrides, options, tempScript)
        }

        try {
            Map<String, Object> cap = runAndCapture(cmd, workingDir, timeout, action, envOverrides,
                                                    maxStdout, maxStderr, grepPattern, null)
            if (cap.timedOut) {
                return textResponse(requestId, [
                    success: false,
                    error  : "Process timed out after ${timeout}s"
                ])
            }

            int exitCode     = cap.exitCode as Integer
            long durationMs  = cap.durationMs as Long
            int capturedErr  = cap.capturedErr as Integer
            log.info("execute {}: exitCode={}, duration={}ms, workingDir={}", action, exitCode, durationMs, workingDir)

            String stdoutStr = cap.stdout as String
            if (grepPattern) {
                Pattern gp = Pattern.compile(grepPattern)
                stdoutStr = stdoutStr.readLines().findAll { gp.matcher(it).find() }.join('\n')
                if (stdoutStr) stdoutStr += '\n'
            }
            boolean stdoutTruncated = stdoutStr.length() > maxStdout
            if (stdoutTruncated) stdoutStr = stdoutStr.take(maxStdout)
            String stderrStr = cap.stderr as String
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
            log.error("execute {} failed: {}", action, sanitize(e.message))
            return textResponse(requestId, [
                success   : false,
                error     : sanitize(e.message),
                durationMs: 0
            ])
        } finally {
            tempScript?.delete()
        }
    }

    /**
     * Run the process and stream both streams into buffers.
     *
     * Shared by the synchronous path and by background jobs so there is exactly one copy of the
     * draining logic -- the child blocks if a pipe fills, so this must never be duplicated
     * carelessly. When {@code job} is non-null, output is mirrored into it as it arrives so a
     * caller can tail a running build, and the Process is registered so it can be cancelled.
     */
    private Map<String, Object> runAndCapture(List<String> cmd, String workingDir, int timeout,
                                              String action, Map<String, String> envOverrides,
                                              int maxStdout, int maxStderr, String grepPattern,
                                              ExecuteJob job) {
        long start = System.currentTimeMillis()
        Process process = null
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
            pb.directory(new File(workingDir))
            pb.redirectErrorStream(false)
            if (envOverrides) pb.environment().putAll(envOverrides)

            process = pb.start()
            if (job) job.process = process

            StringBuilder stdout = new StringBuilder()
            StringBuilder stderr = new StringBuilder()
            int capturedOut = 0
            int capturedErr = 0

            // A job tails live, so it is never capped during collection -- job_output applies
            // caps at read time instead. The sync path keeps the original cap-in-loop guard.
            boolean uncapped = (grepPattern != null) || (job != null)

            Process p = process
            Thread stdoutThread = Thread.ofVirtual().start({
                p.inputStream.eachLine { String line ->
                    if (uncapped || capturedOut < maxStdout) {
                        String s = sanitize(line)
                        stdout.append(s).append('\n')
                        capturedOut += s.length() + 1
                        if (job) job.appendStdout(s + '\n')
                    }
                }
            })
            Thread stderrThread = Thread.ofVirtual().start({
                p.errorStream.eachLine { String line ->
                    if (uncapped || capturedErr < maxStderr) {
                        String s = sanitize(line)
                        stderr.append(s).append('\n')
                        capturedErr += s.length() + 1
                        if (job) job.appendStderr(s + '\n')
                    }
                }
            })

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS)
            long elapsedMs = System.currentTimeMillis() - start
            long remainingMs = Math.max(500L, (timeout * 1000L) - elapsedMs)
            stdoutThread.join(remainingMs)
            stderrThread.join(remainingMs)

            if (!finished) {
                process.destroyForcibly()
                return [timedOut: true, durationMs: System.currentTimeMillis() - start,
                        stdout: stdout.toString(), stderr: stderr.toString(),
                        capturedOut: capturedOut, capturedErr: capturedErr] as Map<String, Object>
            }

            return [timedOut    : false,
                    exitCode    : process.exitValue(),
                    success     : process.exitValue() == 0,
                    durationMs  : System.currentTimeMillis() - start,
                    stdout      : stdout.toString(),
                    stderr      : stderr.toString(),
                    capturedOut : capturedOut,
                    capturedErr : capturedErr] as Map<String, Object>
        } catch (Exception e) {
            process?.destroyForcibly()
            throw e
        }
    }

    /** FS-EXEC-2: register the work as a background job and return its id immediately. */
    private McpResponse submitAsyncJob(List<String> cmd, String workingDir, int timeout,
                                       String action, Object requestId,
                                       Map<String, String> envOverrides, Map<String, Object> options,
                                       File tempScript = null) {
        int maxStdout      = (options?.maxStdout as Integer) ?: 50000
        int maxStderr      = (options?.maxStderr as Integer) ?: 5000
        String summary     = sanitize(cmd.join(' ')).take(200)
        ExecuteJob job = jobRegistry.submit(action, summary, workingDir, { ExecuteJob j ->
            runAndCapture(cmd, workingDir, timeout, action, envOverrides,
                          maxStdout, maxStderr, null, j)
        } as Closure<Map<String, Object>>)
        // The job outlives this call, so it owns the script file and deletes it on completion.
        job.tempScript = tempScript

        return textResponse(requestId, [
            async     : true,
            jobId     : job.jobId,
            status    : job.status,
            action    : action,
            command   : summary,
            workingDir: workingDir,
            timeoutSec: timeout,
            hint      : 'Poll with action=job_status jobId=<id>; tail with action=job_output ' +
                        'jobId=<id> sinceOffset=<n>; stop with action=job_cancel.'
        ] as Map<String, Object>)
    }

    /** FS-EXEC-2: status / incremental output / cancel / list for background jobs. */
    private McpResponse handleJobAction(String action, Map<String, Object> options, Object requestId) {
        String jobId = options?.jobId as String

        if (action == 'job_list') {
            List<Map<String, Object>> rows = jobRegistry.list().collect { ExecuteJob j -> j.statusMap() }
            return textResponse(requestId, [jobs: rows, count: rows.size()] as Map<String, Object>)
        }

        if (!jobId) return McpResponse.toolError(requestId, "${action} requires options.jobId" as String)
        ExecuteJob job = jobRegistry.get(jobId)
        if (!job) {
            return McpResponse.toolError(requestId,
                "Unknown jobId '${jobId}'. Jobs are retained for 30 minutes after finishing; use action=job_list." as String)
        }

        switch (action) {
            case 'job_status':
                return textResponse(requestId, job.statusMap())

            case 'job_cancel':
                boolean cancelled = jobRegistry.cancel(jobId)
                Map<String, Object> cm = job.statusMap()
                cm.cancelled = cancelled
                if (!cancelled) cm.note = 'Job had already finished; nothing to cancel.'
                return textResponse(requestId, cm)

            case 'job_output':
                int sinceOffset = (options?.sinceOffset as Integer) ?: 0
                int maxStdout   = (options?.maxStdout as Integer) ?: 50000
                int maxStderr   = (options?.maxStderr as Integer) ?: 5000
                String out = job.stdoutFrom(sinceOffset)
                String err = job.stderrFrom(0)
                boolean outTrunc = out.length() > maxStdout
                boolean errTrunc = err.length() > maxStderr
                if (outTrunc) out = out.take(maxStdout)
                if (errTrunc) err = err.take(maxStderr)
                Map<String, Object> om = job.statusMap()
                om.stdout = out
                om.stderr = err
                // nextOffset lets the caller resume exactly where this read stopped.
                om.nextOffset = sinceOffset + out.length()
                if (outTrunc) om.stdout_truncated = true
                if (errTrunc) om.stderr_truncated = true
                return textResponse(requestId, om)

            default:
                return McpResponse.toolError(requestId, "Unknown job action: ${action}" as String)
        }
    }
}