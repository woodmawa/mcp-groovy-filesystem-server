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
    // Dangerous Groovy/script patterns -- configurable via application.yml
    // -----------------------------------------------------------------------

    /**
     * Comma-separated dangerous patterns applied globally across all executor types.
     * ProcessBuilder and .execute() are NOT in the global list -- they are managed
     * per-executor via executorExtraPatternsConfig so groovy eval (internal JDBC
     * tooling) is not blocked, while python/bash still block shell injection.
     */
    @Value('${mcp.script.dangerous-patterns:System.exit,Runtime.getRuntime(),Runtime.exec,GroovyClassLoader,GroovyShell,Eval.me,this.class.classLoader}')
    String dangerousPatternsConfig

    /**
     * Comma-separated literal strings scrubbed from the script BEFORE pattern
     * checking. Use for known-safe boilerplate that would otherwise false-positive.
     * Default: Class.forName('org.sqlite.JDBC') -- JDBC driver registration.
     */
    @Value("\${mcp.script.allowed-literals:Class.forName('org.sqlite.JDBC')}")
    String allowedLiteralsConfig

    /**
     * Per-executor extra blocked patterns as a single string, format:
     *   "python:.execute(),bash:.execute()"
     * Parsed at check time. Executors not listed get no extras.
     */
    @Value('${mcp.script.executor-extra-patterns:python:.execute(),bash:.execute()}')
    String executorExtraPatternsConfig

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
    void validateScript(String script, String workingDir, String executorType = 'groovy') {
        validateScriptLength(script)
        validateWorkingDir(workingDir)
        checkDangerousPatterns(script, executorType)
        checkRestrictedPaths(script)
        log.debug('Script validation passed (workingDir={} executor={})', workingDir, executorType)
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
        // Resolve to canonical path and check for traversal, rather than substring-matching '..'
        // (substring check false-positives on legitimate folder names like '...-utils')
        try {
            String canonical = new File(workingDir).canonicalPath
            String normalized = new File(workingDir).absolutePath
            // If canonical diverges from absolute path, there is either a symlink or traversal
            if (!canonical.replace('\\', '/').equals(normalized.replace('\\', '/'))) {
                // Allow it - actual boundary enforcement is in isPathAllowed()
                // Just note it for debug visibility
                log.debug("Working dir canonical path differs from absolute - possible symlink: {}", workingDir)
            }
        } catch (IOException e) {
            throw new SecurityException("Cannot resolve working directory: ${workingDir}")
        }
    }

    @groovy.transform.PackageScope
    void checkDangerousPatterns(String script, String executorType) {
        // Step 1: scrub known-safe literals before pattern matching
        // Prevents false positives on e.g. Class.forName('org.sqlite.JDBC') in JDBC boilerplate
        String scrubbed = script
        allowedLiteralsConfig.split(',').each { lit ->
            String trimmed = lit.trim()
            if (trimmed) scrubbed = scrubbed.replace(trimmed, '__ALLOWED__')
        }

        // Step 2: global patterns (all executor types)
        List<String> globalPatterns = dangerousPatternsConfig.split(',').collect { it.trim() }.findAll { it }
        for (String pattern : globalPatterns) {
            if (scrubbed.contains(pattern)) {
                log.warn('Dangerous pattern detected in script (executor={}): {}', executorType, pattern)
                throw new SecurityException("Dangerous pattern not allowed in script: ${pattern}")
            }
        }

        // Step 3: per-executor extra patterns (e.g. python:.execute(),bash:.execute())
        // Format: "executor1:pattern1,executor2:pattern2"
        if (executorExtraPatternsConfig?.trim()) {
            executorExtraPatternsConfig.split(',').each { entry ->
                String[] parts = entry.trim().split(':', 2)
                if (parts.length == 2 && parts[0].trim() == executorType) {
                    String extraPattern = parts[1].trim()
                    if (extraPattern && scrubbed.contains(extraPattern)) {
                        log.warn('Executor-specific dangerous pattern in script (executor={}): {}', executorType, extraPattern)
                        throw new SecurityException("Dangerous pattern not allowed in ${executorType} script: ${extraPattern}")
                    }
                }
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
        Future<T> future = null
        try {
            future = virtualExecutor.submit(task)
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (TimeoutException e) {
            log.error("Task '{}' timed out after {}s - cancelling", taskId, timeoutSeconds)
            future?.cancel(true)  // interrupt the task thread; won't stop tight loops but releases I/O waits
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
