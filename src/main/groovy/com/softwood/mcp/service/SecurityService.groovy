package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

/**
 * SecurityService — consolidated script security and resource control.
 *
 * Replaces ScriptSecurityService + ResourceControlService with a single,
 * cleaner service. Uses virtual threads (Java 25) for task execution.
 *
 * Responsibilities:
 * - Script validation (length, dangerous patterns, dangerous file paths)
 * - Sensitive data redaction for logging
 * - Bounded execution with timeout via virtual threads
 * - JVM memory / resource monitoring
 *
 * v0.0.7 — Phase 1 Foundation
 */
@Service
@Slf4j
@CompileStatic
class SecurityService {

    // -----------------------------------------------------------------------
    // Config
    // -----------------------------------------------------------------------

    @Value('${mcp.script.max-script-length:100000}')
    int maxScriptLength

    @Value('${mcp.script.max-working-dir-length:4096}')
    int maxWorkingDirLength

    @Value('${mcp.script.max-execution-time-seconds:60}')
    int maxExecutionTimeSeconds

    @Value('${mcp.script.max-memory-mb:256}')
    int maxMemoryMb

    // -----------------------------------------------------------------------
    // Compiled patterns for sensitive-data redaction
    // -----------------------------------------------------------------------

    private static final Pattern PASSWORD_PATTERN = Pattern.compile('(?i)password[=:]\\s*\\S+')
    private static final Pattern TOKEN_PATTERN    = Pattern.compile('(?i)token[=:]\\s*\\S+')
    private static final Pattern API_KEY_PATTERN  = Pattern.compile('(?i)api[_-]?key[=:]\\s*\\S+')
    private static final Pattern SECRET_PATTERN   = Pattern.compile('(?i)secret[=:]\\s*\\S+')

    // -----------------------------------------------------------------------
    // Dangerous Groovy/script patterns
    // -----------------------------------------------------------------------

    private static final List<String> DANGEROUS_SCRIPT_PATTERNS = [
        'System.exit',
        'Runtime.getRuntime()',
        'ProcessBuilder',
        'Class.forName',
        'GroovyClassLoader',
        'GroovyShell',
        'Eval.me',
        'this.class.classLoader',
    ]

    // System paths that scripts should not touch
    private static final List<String> RESTRICTED_PATHS = [
        '/etc/passwd',
        '/etc/shadow',
        'C:\\Windows\\System32',
        'C:\\Windows\\SysWOW64',
        '/bin/',
        '/sbin/',
        '/usr/bin/',
        '/usr/sbin/',
    ]

    // -----------------------------------------------------------------------
    // Execution infrastructure
    // -----------------------------------------------------------------------

    private ExecutorService virtualExecutor
    private final ConcurrentHashMap<String, Long> activeTasks = new ConcurrentHashMap<>()

    @PostConstruct
    void init() {
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()
        log.info("SecurityService initialised — max execution time: {}s, max memory: {}MB",
            maxExecutionTimeSeconds, maxMemoryMb)
    }

    @PreDestroy
    void shutdown() {
        virtualExecutor?.shutdown()
        try {
            if (!virtualExecutor?.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualExecutor?.shutdownNow()
            }
        } catch (InterruptedException ignored) {
            virtualExecutor?.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    // -----------------------------------------------------------------------
    // Script validation
    // -----------------------------------------------------------------------

    /**
     * Full validation pass for a script before execution.
     * Throws SecurityException or IllegalArgumentException on failure.
     */
    void validateScript(String script, String workingDir) {
        validateScriptLength(script)
        validateWorkingDir(workingDir)
        checkDangerousPatterns(script)
        checkRestrictedPaths(script)
        log.debug("Script validation passed (workingDir={})", workingDir)
    }

    private void validateScriptLength(String script) {
        if (!script?.trim()) {
            throw new IllegalArgumentException("Script cannot be empty")
        }
        if (script.length() > maxScriptLength) {
            throw new IllegalArgumentException(
                "Script too large: ${script.length()} bytes (max: ${maxScriptLength})"
            )
        }
    }

    private void validateWorkingDir(String workingDir) {
        if (!workingDir?.trim()) {
            throw new IllegalArgumentException("Working directory cannot be empty")
        }
        if (workingDir.length() > maxWorkingDirLength) {
            throw new IllegalArgumentException("Working directory path too long (max ${maxWorkingDirLength} chars)")
        }
        if (workingDir.contains('..')) {
            throw new SecurityException("Path traversal not allowed in working directory")
        }
    }

    private void checkDangerousPatterns(String script) {
        for (String pattern : DANGEROUS_SCRIPT_PATTERNS) {
            if (script.contains(pattern)) {
                log.warn("Dangerous pattern detected in script: {}", pattern)
                throw new SecurityException("Dangerous pattern not allowed in script: ${pattern}")
            }
        }
    }

    private void checkRestrictedPaths(String script) {
        for (String path : RESTRICTED_PATHS) {
            if (script.contains(path)) {
                log.warn("Restricted path reference detected in script: {}", path)
                throw new SecurityException("Access to restricted path not allowed: ${path}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Sensitive-data redaction
    // -----------------------------------------------------------------------

    /**
     * Redact passwords, tokens, and API keys from text before logging.
     */
    String redactForLogging(String text, int maxLength = 200) {
        if (!text) return ''

        String s = text
        s = PASSWORD_PATTERN.matcher(s).replaceAll('password=***')
        s = TOKEN_PATTERN.matcher(s).replaceAll('token=***')
        s = API_KEY_PATTERN.matcher(s).replaceAll('api_key=***')
        s = SECRET_PATTERN.matcher(s).replaceAll('secret=***')

        return s.length() > maxLength ? s.substring(0, maxLength) + '... (truncated)' : s
    }

    // -----------------------------------------------------------------------
    // Bounded execution with timeout
    // -----------------------------------------------------------------------

    /**
     * Execute a task on a virtual thread with a configurable timeout.
     * Throws RuntimeException on timeout or execution failure.
     */
    <T> T executeWithTimeout(String taskId, Callable<T> task, int timeoutSeconds = maxExecutionTimeSeconds) {
        activeTasks.put(taskId, System.currentTimeMillis())
        try {
            Future<T> future = virtualExecutor.submit(task)
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (TimeoutException e) {
            log.error("Task '{}' timed out after {}s", taskId, timeoutSeconds)
            throw new RuntimeException("Execution timed out after ${timeoutSeconds} seconds", e)
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() ?: e
            log.error("Task '{}' failed: {}", taskId, cause.message)
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause)
        } finally {
            activeTasks.remove(taskId)
        }
    }

    // -----------------------------------------------------------------------
    // Resource monitoring
    // -----------------------------------------------------------------------

    /** Returns current JVM memory and active task stats */
    Map<String, Object> getResourceUsage() {
        long total = Runtime.runtime.totalMemory()
        long free  = Runtime.runtime.freeMemory()
        long used  = total - free
        long max   = Runtime.runtime.maxMemory()

        return [
            usedMemoryMb : used.intdiv(1024 * 1024),
            totalMemoryMb: total.intdiv(1024 * 1024),
            maxMemoryMb  : max.intdiv(1024 * 1024),
            activeTasks  : activeTasks.size(),
            nearLimit    : isNearMemoryLimit()
        ] as Map<String, Object>
    }

    boolean isNearMemoryLimit() {
        long used = (Runtime.runtime.totalMemory() - Runtime.runtime.freeMemory()).intdiv(1024 * 1024)
        return used > (maxMemoryMb * 0.8)
    }
}
