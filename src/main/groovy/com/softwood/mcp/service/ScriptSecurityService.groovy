package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.util.regex.Pattern

/**
 * Security validation service for script execution
 * Validates and sanitizes script inputs before execution
 */
@Service
@Slf4j
@CompileStatic
class ScriptSecurityService {
    
    private static final int MAX_SCRIPT_LENGTH = 100_000  // 100KB
    private static final int MAX_WORKING_DIR_LENGTH = 4096
    
    // Compiled patterns for sanitization
    private static final Pattern PASSWORD_PATTERN = Pattern.compile('(?i)password[=:]\\s*\\S+')
    private static final Pattern TOKEN_PATTERN = Pattern.compile('(?i)token[=:]\\s*\\S+')
    private static final Pattern API_KEY_PATTERN = Pattern.compile('(?i)api[_-]?key[=:]\\s*\\S+')
    private static final Pattern SECRET_PATTERN = Pattern.compile('(?i)secret[=:]\\s*\\S+')
    
    // Dangerous patterns in scripts
    private static final List<String> DANGEROUS_PATTERNS = [
        'System.exit',
        'Runtime.getRuntime()',
        'ProcessBuilder',
        'Class.forName',
        'GroovyClassLoader',
        'GroovyShell',
        'Eval.me',
        '__FILE__',
        '__LINE__',
        'this.class.classLoader',
    ]
    
    // Dangerous file operations
    private static final List<String> DANGEROUS_FILE_OPS = [
        '/etc/passwd',
        '/etc/shadow',
        'C:\\Windows\\System32',
        'C:\\Windows\\SysWOW64',
        '/bin/',
        '/sbin/',
        '/usr/bin/',
        '/usr/sbin/'
    ]
    
    /**
     * Validate script before execution
     * @throws SecurityException if script is unsafe
     */
    void validateScript(String script, String workingDir) {
        validateScriptLength(script)
        validateWorkingDir(workingDir)
        checkDangerousPatterns(script)
        checkDangerousFileOperations(script)
        
        log.debug("Script validation passed for working dir: {}", workingDir)
    }
    
    /**
     * Validate script length
     */
    private void validateScriptLength(String script) {
        if (!script) {
            throw new IllegalArgumentException("Script cannot be empty")
        }
        
        if (script.length() > MAX_SCRIPT_LENGTH) {
            throw new IllegalArgumentException(
                "Script too large: ${script.length()} bytes (max: ${MAX_SCRIPT_LENGTH})"
            )
        }
    }
    
    /**
     * Validate working directory
     */
    private void validateWorkingDir(String workingDir) {
        if (!workingDir) {
            throw new IllegalArgumentException("Working directory cannot be empty")
        }
        
        if (workingDir.length() > MAX_WORKING_DIR_LENGTH) {
            throw new IllegalArgumentException("Working directory path too long")
        }
        
        // Check for path traversal attempts
        if (workingDir.contains('..')) {
            throw new SecurityException("Path traversal not allowed in working directory")
        }
    }
    
    /**
     * Check for dangerous patterns in script
     */
    private void checkDangerousPatterns(String script) {
        for (String pattern : DANGEROUS_PATTERNS) {
            if (script.contains(pattern)) {
                log.warn("Dangerous pattern detected: {}", pattern)
                throw new SecurityException("Dangerous pattern not allowed: ${pattern}")
            }
        }
    }
    
    /**
     * Check for dangerous file operations
     */
    private void checkDangerousFileOperations(String script) {
        for (String path : DANGEROUS_FILE_OPS) {
            if (script.contains(path)) {
                log.warn("Dangerous file path detected: {}", path)
                throw new SecurityException("Access to system path not allowed: ${path}")
            }
        }
    }
    
    /**
     * Sanitize script output for logging
     * Removes sensitive information before logging
     */
    String sanitizeForLogging(String text, int maxLength = 200) {
        if (!text) return ""
        
        String sanitized = text
        sanitized = PASSWORD_PATTERN.matcher(sanitized).replaceAll('password=***')
        sanitized = TOKEN_PATTERN.matcher(sanitized).replaceAll('token=***')
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll('api_key=***')
        sanitized = SECRET_PATTERN.matcher(sanitized).replaceAll('secret=***')
        
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength) + "... (truncated)"
        }
        
        return sanitized
    }
    
    /**
     * Calculate estimated resource usage
     */
    ResourceEstimate estimateResources(String script) {
        int lines = script.count('\n') + 1
        int complexity = estimateComplexity(script)
        
        return new ResourceEstimate(
            lines: lines,
            complexity: complexity,
            estimatedMemoryMb: Math.max(64, (int)(lines / 10)),
            estimatedTimeoutSeconds: Math.max(10, complexity * 2)
        )
    }
    
    /**
     * Estimate script complexity (simple heuristic)
     */
    private int estimateComplexity(String script) {
        int score = 1
        
        // Count loops
        score += (script =~ /\b(for|while|each)\b/).count
        
        // Count function definitions
        score += (script =~ /\bdef\s+\w+\s*\(/).count
        
        // Count nested blocks
        score += script.count('{')
        
        return score
    }
    
    /**
     * Resource estimate result
     */
    @CompileStatic
    static class ResourceEstimate {
        int lines
        int complexity
        int estimatedMemoryMb
        int estimatedTimeoutSeconds
    }
}
