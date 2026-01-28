package com.softwood.mcp.model

import groovy.transform.CompileStatic
import groovy.transform.Immutable

/**
 * Immutable result of command execution
 * Provides type-safe access to process execution results
 */
@Immutable
@CompileStatic
class CommandResult {
    
    /** Exit code from the process (0 = success) */
    int exitCode
    
    /** Standard output from the process */
    String stdout
    
    /** Standard error from the process */
    String stderr
    
    /** Whether the command succeeded (exitCode == 0) */
    boolean success
    
    /** Duration of execution in milliseconds */
    long durationMs
    
    /**
     * Create a successful result
     */
    static CommandResult success(String stdout, long durationMs = 0) {
        new CommandResult(
            exitCode: 0,
            stdout: stdout,
            stderr: '',
            success: true,
            durationMs: durationMs
        )
    }
    
    /**
     * Create a failed result
     */
    static CommandResult failure(int exitCode, String stdout, String stderr, long durationMs = 0) {
        new CommandResult(
            exitCode: exitCode,
            stdout: stdout,
            stderr: stderr,
            success: false,
            durationMs: durationMs
        )
    }
    
    /**
     * Create from exit code and outputs
     */
    static CommandResult of(int exitCode, String stdout, String stderr, long durationMs = 0) {
        new CommandResult(
            exitCode: exitCode,
            stdout: stdout,
            stderr: stderr,
            success: exitCode == 0,
            durationMs: durationMs
        )
    }
    
    /**
     * Convert to map for backward compatibility
     */
    Map<String, Object> toMap() {
        [
            exitCode: exitCode,
            stdout: stdout,
            stderr: stderr,
            success: success,
            durationMs: durationMs
        ]
    }
    
    /**
     * Get stdout lines
     */
    List<String> getStdoutLines() {
        stdout ? stdout.readLines() : []
    }
    
    /**
     * Get stderr lines
     */
    List<String> getStderrLines() {
        stderr ? stderr.readLines() : []
    }
    
    /**
     * Check if stdout contains text
     */
    boolean stdoutContains(String text) {
        stdout?.contains(text) ?: false
    }
    
    /**
     * Check if stderr contains text
     */
    boolean stderrContains(String text) {
        stderr?.contains(text) ?: false
    }
}
