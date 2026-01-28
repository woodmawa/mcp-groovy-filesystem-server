package com.softwood.mcp.model

/**
 * Result of Groovy script execution
 * Type-safe wrapper for script execution results with sanitized output
 */
class ScriptExecutionResult {
    
    /** Whether the script executed successfully */
    boolean success
    
    /** The return value from the script (if any) */
    Object result
    
    /** Captured output from println statements */
    List<String> output
    
    /** Working directory where script was executed */
    String workingDir
    
    /** Error message if script failed */
    String error
    
    /** Stack trace if script failed */
    List<String> stackTrace
    
    /** Execution duration in milliseconds */
    long durationMs
    
    /**
     * Sanitize string by removing control characters except newlines and tabs
     */
    private static String sanitize(String text) {
        if (!text) return text
        // Remove control characters except \n (10) and \t (9)
        // Keep printable ASCII (32-126) and basic whitespace
        return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
    }
    
    /**
     * Create a successful result with sanitized output
     */
    static ScriptExecutionResult success(Object result, List<String> output, String workingDir, long durationMs = 0) {
        // Sanitize all output strings
        def sanitizedOutput = output?.collect { sanitize(it as String) } ?: []
        
        new ScriptExecutionResult(
            success: true,
            result: result,
            output: sanitizedOutput,
            workingDir: workingDir,
            error: null,
            stackTrace: null,
            durationMs: durationMs
        )
    }
    
    /**
     * Create a failed result with sanitized error messages
     */
    static ScriptExecutionResult failure(String error, List<String> stackTrace, String workingDir, long durationMs = 0) {
        new ScriptExecutionResult(
            success: false,
            result: null,
            output: [],
            workingDir: workingDir,
            error: sanitize(error),
            stackTrace: stackTrace?.collect { sanitize(it as String) } ?: [],
            durationMs: durationMs
        )
    }
    
    /**
     * Convert to map for backward compatibility with sanitized strings
     */
    Map<String, Object> toMap() {
        Map<String, Object> map = [
            success: success,
            workingDir: sanitize(workingDir),
            durationMs: durationMs
        ]
        
        if (success) {
            map.result = result
            map.output = output  // Already sanitized in success()
        } else {
            map.error = error  // Already sanitized in failure()
            map.stackTrace = stackTrace  // Already sanitized in failure()
        }
        
        return map
    }
    
    /**
     * Check if output contains text
     */
    boolean outputContains(String text) {
        output?.any { it.contains(text) } ?: false
    }
    
    /**
     * Get all output as single string (sanitized)
     */
    String getOutputText() {
        sanitize(output?.join('\n') ?: '')
    }
}
