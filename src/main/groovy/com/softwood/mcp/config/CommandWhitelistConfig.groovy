package com.softwood.mcp.config

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

import java.util.regex.Pattern

/**
 * Configuration for command whitelists and blacklists
 * Loaded from application.yml - allows runtime configuration without rebuild
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.script.whitelist")
@Slf4j
@CompileStatic
class CommandWhitelistConfig {
    
    List<String> powershellAllowed = []
    List<String> powershellBlocked = []
    List<String> bashAllowed = []
    List<String> bashBlocked = []
    
    // Compiled patterns for better performance
    private List<Pattern> powershellAllowedPatterns = null
    private List<Pattern> powershellBlockedPatterns = null
    private List<Pattern> bashAllowedPatterns = null
    private List<Pattern> bashBlockedPatterns = null
    
    /**
     * Get compiled PowerShell allowed patterns (lazy initialization)
     */
    List<Pattern> getPowershellAllowedPatterns() {
        if (powershellAllowedPatterns == null) {
            powershellAllowedPatterns = powershellAllowed.collect { Pattern.compile(it) }
            log.info("Loaded ${powershellAllowedPatterns.size()} PowerShell allowed patterns")
        }
        return powershellAllowedPatterns
    }
    
    /**
     * Get compiled PowerShell blocked patterns (lazy initialization)
     */
    List<Pattern> getPowershellBlockedPatterns() {
        if (powershellBlockedPatterns == null) {
            powershellBlockedPatterns = powershellBlocked.collect { Pattern.compile(it) }
            log.info("Loaded ${powershellBlockedPatterns.size()} PowerShell blocked patterns")
        }
        return powershellBlockedPatterns
    }
    
    /**
     * Get compiled Bash allowed patterns (lazy initialization)
     */
    List<Pattern> getBashAllowedPatterns() {
        if (bashAllowedPatterns == null) {
            bashAllowedPatterns = bashAllowed.collect { Pattern.compile(it) }
            log.info("Loaded ${bashAllowedPatterns.size()} Bash allowed patterns")
        }
        return bashAllowedPatterns
    }
    
    /**
     * Get compiled Bash blocked patterns (lazy initialization)
     */
    List<Pattern> getBashBlockedPatterns() {
        if (bashBlockedPatterns == null) {
            bashBlockedPatterns = bashBlocked.collect { Pattern.compile(it) }
            log.info("Loaded ${bashBlockedPatterns.size()} Bash blocked patterns")
        }
        return bashBlockedPatterns
    }
    
    /**
     * Check if a PowerShell command is allowed
     */
    boolean isPowershellAllowed(String command) {
        String normalized = command.trim()
        
        // Check blacklist first
        if (powershellBlockedPatterns.any { pattern -> normalized ==~ pattern }) {
            log.debug("PowerShell command blocked by blacklist: {}", normalized.take(50))
            return false
        }
        
        // Check whitelist
        boolean allowed = powershellAllowedPatterns.any { pattern -> normalized ==~ pattern }
        if (!allowed) {
            log.debug("PowerShell command not in whitelist: {}", normalized.take(50))
        }
        return allowed
    }
    
    /**
     * Check if a Bash command is allowed
     */
    boolean isBashAllowed(String command) {
        String normalized = command.trim()
        
        // Check blacklist first
        if (bashBlockedPatterns.any { pattern -> normalized ==~ pattern }) {
            log.debug("Bash command blocked by blacklist: {}", normalized.take(50))
            return false
        }
        
        // Check whitelist
        boolean allowed = bashAllowedPatterns.any { pattern -> normalized ==~ pattern }
        if (!allowed) {
            log.debug("Bash command not in whitelist: {}", normalized.take(50))
        }
        return allowed
    }
}
