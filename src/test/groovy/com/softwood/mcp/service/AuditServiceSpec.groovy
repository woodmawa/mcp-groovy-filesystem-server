package com.softwood.mcp.service

import spock.lang.Specification

/**
 * Tests for AuditService
 */
class AuditServiceSpec extends Specification {
    
    AuditService auditService
    
    def setup() {
        auditService = new AuditService()
    }
    
    def "should log script execution"() {
        when: "logging script execution"
        auditService.logScriptExecution(
            "C:/test",
            100,
            true,
            250L,
            null
        )
        
        then: "no exception is thrown"
        noExceptionThrown()
    }
    
    def "should log failed script execution"() {
        when: "logging failed script execution"
        auditService.logScriptExecution(
            "C:/test",
            100,
            false,
            250L,
            "Test error"
        )
        
        then: "no exception is thrown"
        noExceptionThrown()
    }
    
    def "should log command execution"() {
        when: "logging command execution"
        auditService.logCommandExecution(
            "PowerShell",
            "Get-ChildItem",
            "C:/test",
            true,
            0,
            100L
        )
        
        then: "no exception is thrown"
        noExceptionThrown()
    }
    
    def "should log security violation"() {
        when: "logging security violation"
        auditService.logSecurityViolation(
            "Dangerous Pattern",
            "System.exit detected",
            "C:/test"
        )
        
        then: "no exception is thrown"
        noExceptionThrown()
    }
    
    def "should log file operation"() {
        when: "logging file operation"
        auditService.logFileOperation(
            "read",
            "C:/test/file.txt",
            true,
            null
        )
        
        then: "no exception is thrown"
        noExceptionThrown()
    }
    
    def "should log unauthorized access"() {
        when: "logging unauthorized access"
        auditService.logUnauthorizedAccess(
            "C:/Windows/System32",
            "Path not in allowed list"
        )
        
        then: "no exception is thrown"
        noExceptionThrown()
    }
    
    def "should get audit stats"() {
        when: "getting audit stats"
        def stats = auditService.getAuditStats()
        
        then: "stats are returned"
        stats != null
        stats.message != null
    }
}
