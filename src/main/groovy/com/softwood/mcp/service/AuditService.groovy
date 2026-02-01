package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * Audit logging service for tracking command and script executions
 * Provides security audit trail
 */
@Service
@Slf4j
@CompileStatic
class AuditService {
    
    private static final DateTimeFormatter FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
    
    // Compiled patterns for sanitization
    private static final Pattern PASSWORD_PATTERN = Pattern.compile('(?i)password[=:]\\s*\\S+')
    private static final Pattern TOKEN_PATTERN = Pattern.compile('(?i)token[=:]\\s*\\S+')
    private static final Pattern API_KEY_PATTERN = Pattern.compile('(?i)api[_-]?key[=:]\\s*\\S+')
    private static final Pattern SECRET_PATTERN = Pattern.compile('(?i)secret[=:]\\s*\\S+')
    
    /**
     * Log script execution attempt
     */
    void logScriptExecution(
        String workingDir,
        int scriptLength,
        boolean success,
        long durationMs,
        String error = null
    ) {
        def timestamp = FORMATTER.format(Instant.now())
        def status = success ? "SUCCESS" : "FAILED"
        
        if (success) {
            log.info(
                "[AUDIT] {} | Script Execution | {} | dir={} | length={} | duration={}ms",
                timestamp, status, workingDir, scriptLength, durationMs
            )
        } else {
            log.warn(
                "[AUDIT] {} | Script Execution | {} | dir={} | length={} | duration={}ms | error={}",
                timestamp, status, workingDir, scriptLength, durationMs, error
            )
        }
    }
    
    /**
     * Log command execution attempt
     */
    void logCommandExecution(
        String commandType,  // "PowerShell", "Bash", "Git", "Gradle"
        String command,
        String workingDir,
        boolean success,
        int exitCode,
        long durationMs
    ) {
        def timestamp = FORMATTER.format(Instant.now())
        def status = success ? "SUCCESS" : "FAILED"
        def sanitizedCommand = sanitizeCommand(command, 50)
        
        if (success) {
            log.info(
                "[AUDIT] {} | {} Command | {} | cmd='{}' | dir={} | exit={} | duration={}ms",
                timestamp, commandType, status, sanitizedCommand, workingDir, exitCode, durationMs
            )
        } else {
            log.warn(
                "[AUDIT] {} | {} Command | {} | cmd='{}' | dir={} | exit={} | duration={}ms",
                timestamp, commandType, status, sanitizedCommand, workingDir, exitCode, durationMs
            )
        }
    }
    
    /**
     * Log security violation
     */
    void logSecurityViolation(String violationType, String details, String workingDir = null) {
        def timestamp = FORMATTER.format(Instant.now())
        
        log.error(
            "[AUDIT] {} | SECURITY VIOLATION | type={} | details={} | dir={}",
            timestamp, violationType, details, workingDir ?: "N/A"
        )
    }
    
    /**
     * Log file operation
     */
    void logFileOperation(
        String operation,  // "read", "write", "delete", "copy", "move"
        String path,
        boolean success,
        String error = null
    ) {
        def timestamp = FORMATTER.format(Instant.now())
        def status = success ? "SUCCESS" : "FAILED"
        def sanitizedPath = sanitizePath(path, 100)
        
        if (success) {
            log.info(
                "[AUDIT] {} | File Operation | {} | op={} | path={}",
                timestamp, status, operation, sanitizedPath
            )
        } else {
            log.warn(
                "[AUDIT] {} | File Operation | {} | op={} | path={} | error={}",
                timestamp, status, operation, sanitizedPath, error
            )
        }
    }
    
    /**
     * Log unauthorized access attempt
     */
    void logUnauthorizedAccess(String path, String reason) {
        def timestamp = FORMATTER.format(Instant.now())
        def sanitizedPath = sanitizePath(path, 100)
        
        log.error(
            "[AUDIT] {} | UNAUTHORIZED ACCESS | path={} | reason={}",
            timestamp, sanitizedPath, reason
        )
    }
    
    /**
     * Sanitize command for logging (remove sensitive data)
     */
    private String sanitizeCommand(String command, int maxLength) {
        if (!command) return ""
        
        String sanitized = command
        sanitized = PASSWORD_PATTERN.matcher(sanitized).replaceAll('password=***')
        sanitized = TOKEN_PATTERN.matcher(sanitized).replaceAll('token=***')
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll('api_key=***')
        sanitized = SECRET_PATTERN.matcher(sanitized).replaceAll('secret=***')
        
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength) + "..."
        }
        
        return sanitized
    }
    
    /**
     * Sanitize file path for logging
     */
    private String sanitizePath(String path, int maxLength) {
        if (!path) return ""
        
        if (path.length() > maxLength) {
            // Keep start and end of path
            int half = (int)((maxLength - 3) / 2)
            return path.substring(0, half) + "..." + path.substring(path.length() - half)
        }
        
        return path
    }
    
    /**
     * Get audit statistics
     */
    Map<String, Object> getAuditStats() {
        // In a real implementation, this would query an audit database
        // For now, just return a placeholder
        return [
            message: "Audit stats available in application logs" as Object,
            logLevel: "INFO" as Object,
            auditTag: "[AUDIT]" as Object
        ]
    }
}
