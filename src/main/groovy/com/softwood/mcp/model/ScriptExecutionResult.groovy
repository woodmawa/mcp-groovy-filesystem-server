package com.softwood.mcp.model

/**
 * Result of Groovy script execution
 * Type-safe wrapper for script execution results
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
     * Create a successful result
     */
    static ScriptExecutionResult success(Object result, List<String> output, String workingDir, long durationMs = 0) {
        new ScriptExecutionResult(
            success: true,
            result: result,
            output: output ?: [],
            workingDir: workingDir,
            error: null,
            stackTrace: null,
            durationMs: durationMs
        )
    }
    
    /**
     * Create a failed result
     */
    static ScriptExecutionResult failure(String error, List<String> stackTrace, String workingDir, long durationMs = 0) {
        new ScriptExecutionResult(
            success: false,
            result: null,
            output: [],
            workingDir: workingDir,
            error: error,
            stackTrace: stackTrace ?: [],
            durationMs: durationMs
        )
    }
    
    /**
     * Convert to map for backward compatibility
     */
    Map<String, Object> toMap() {
        Map<String, Object> map = [
            success: success,
            workingDir: workingDir,
            durationMs: durationMs
        ]
        
        if (success) {
            map.result = result
            map.output = output
        } else {
            map.error = error
            map.stackTrace = stackTrace
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
     * Get all output as single string
     */
    String getOutputText() {
        output?.join('\n') ?: ''
    }
}
