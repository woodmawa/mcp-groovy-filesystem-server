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
    List<String> cmdAllowed = []
    List<String> cmdBlocked = []
    
    // Compiled patterns for better performance
    private List<Pattern> powershellAllowedPatterns = null
    private List<Pattern> powershellBlockedPatterns = null
    private List<Pattern> bashAllowedPatterns = null
    private List<Pattern> bashBlockedPatterns = null
    private List<Pattern> cmdAllowedPatterns = null
    private List<Pattern> cmdBlockedPatterns = null
    
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
     * Check if a PowerShell command is allowed.
     * Uses pattern.matcher().find() so that '.*' matches multiline scripts
     * (Groovy ==~ is a full-string match and fails on newlines without DOTALL).
     * Blocked patterns are also checked with find() against the first line only,
     * keeping dangerous-command detection robust.
     */
    boolean isPowershellAllowed(String command) {
        String normalized = command.trim()
        String firstLine  = normalized.readLines().first() ?: normalized

        // Check blacklist first - match against full script (find)
        if (getPowershellBlockedPatterns().any { pattern -> pattern.matcher(normalized).find() }) {
            log.debug("PowerShell command blocked by blacklist: {}", firstLine.take(50))
            return false
        }

        // Check whitelist - use find() so '.*' allows multiline scripts
        boolean allowed = getPowershellAllowedPatterns().any { pattern -> pattern.matcher(normalized).find() }
        if (!allowed) {
            log.debug("PowerShell command not in whitelist: {}", firstLine.take(50))
        }
        return allowed
    }
    
    /**
     * Check if a Bash command is allowed.
     * Uses pattern.matcher().find() so '.*' matches multiline scripts.
     */
    boolean isBashAllowed(String command) {
        String normalized = command.trim()
        String firstLine  = normalized.readLines().first() ?: normalized

        // Check blacklist first
        if (getBashBlockedPatterns().any { pattern -> pattern.matcher(normalized).find() }) {
            log.debug("Bash command blocked by blacklist: {}", firstLine.take(50))
            return false
        }

        boolean allowed = getBashAllowedPatterns().any { pattern -> pattern.matcher(normalized).find() }
        if (!allowed) {
            log.debug("Bash command not in whitelist: {}", firstLine.take(50))
        }
        return allowed
    }

    List<Pattern> getCmdAllowedPatterns() {
        if (cmdAllowedPatterns == null) {
            cmdAllowedPatterns = cmdAllowed.collect { Pattern.compile(it) }
            log.info("Loaded ${cmdAllowedPatterns.size()} CMD allowed patterns")
        }
        return cmdAllowedPatterns
    }

    List<Pattern> getCmdBlockedPatterns() {
        if (cmdBlockedPatterns == null) {
            cmdBlockedPatterns = cmdBlocked.collect { Pattern.compile(it) }
            log.info("Loaded ${cmdBlockedPatterns.size()} CMD blocked patterns")
        }
        return cmdBlockedPatterns
    }

    /**
     * Check if a CMD command is allowed.
     * If cmdAllowed list is empty, defaults to ALLOW (open by default - same risk profile as enableCmd=true).
     * If cmdAllowed has patterns, command must match at least one.
     */
    boolean isCmdAllowed(String command) {
        String normalized = command.trim()

        // Check blocklist first
        if (getCmdBlockedPatterns().any { pattern -> pattern.matcher(normalized).find() }) {
            log.debug("CMD command blocked by blocklist: {}", normalized.take(50))
            return false
        }

        // If no allow patterns configured, default to allow (open policy)
        if (getCmdAllowedPatterns().isEmpty()) {
            return true
        }

        boolean allowed = getCmdAllowedPatterns().any { pattern -> pattern.matcher(normalized).find() }
        if (!allowed) {
            log.debug("CMD command not in allowlist: {}", normalized.take(50))
        }
        return allowed
    }
}
