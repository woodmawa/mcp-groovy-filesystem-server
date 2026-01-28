package com.softwood.mcp.service

import spock.lang.Specification

/**
 * Tests for ScriptSecurityService
 */
class ScriptSecurityServiceSpec extends Specification {
    
    ScriptSecurityService securityService
    
    def setup() {
        securityService = new ScriptSecurityService()
    }
    
    def "should validate normal scripts"() {
        given: "a safe script"
        def script = "println 'Hello World'"
        def workingDir = "C:/Users/test"
        
        when: "validating"
        securityService.validateScript(script, workingDir)
        
        then: "no exception is thrown"
        noExceptionThrown()
    }
    
    def "should reject scripts that are too large"() {
        given: "a very large script"
        def script = "x" * 200_000  // 200KB
        def workingDir = "C:/Users/test"
        
        when: "validating"
        securityService.validateScript(script, workingDir)
        
        then: "exception is thrown"
        thrown(IllegalArgumentException)
    }
    
    def "should reject dangerous patterns"() {
        given: "a script with dangerous pattern"
        def script = "System.exit(0)"
        def workingDir = "C:/Users/test"
        
        when: "validating"
        securityService.validateScript(script, workingDir)
        
        then: "security exception is thrown"
        def e = thrown(SecurityException)
        e.message.contains("System.exit")
    }
    
    def "should reject Runtime.getRuntime()"() {
        given: "a script trying to use Runtime"
        def script = "Runtime.getRuntime().exec('cmd')"
        def workingDir = "C:/Users/test"
        
        when: "validating"
        securityService.validateScript(script, workingDir)
        
        then: "security exception is thrown"
        def e = thrown(SecurityException)
        e.message.contains("Runtime.getRuntime()")
    }
    
    def "should reject dangerous file paths"() {
        given: "a script accessing system directories"
        def script = "readFile('/etc/passwd')"
        def workingDir = "C:/Users/test"
        
        when: "validating"
        securityService.validateScript(script, workingDir)
        
        then: "security exception is thrown"
        def e = thrown(SecurityException)
        e.message.contains("/etc/passwd")
    }
    
    def "should reject path traversal in working directory"() {
        given: "a working directory with path traversal"
        def script = "println 'test'"
        def workingDir = "C:/Users/../Windows/System32"
        
        when: "validating"
        securityService.validateScript(script, workingDir)
        
        then: "security exception is thrown"
        def e = thrown(SecurityException)
        e.message.contains("Path traversal")
    }
    
    def "should sanitize passwords in logging"() {
        given: "text with password"
        def text = "username=admin password=secret123 token=abc123"
        
        when: "sanitizing"
        def sanitized = securityService.sanitizeForLogging(text)
        
        then: "sensitive data is masked"
        sanitized.contains("password=***")
        !sanitized.contains("secret123")
    }
    
    def "should estimate script complexity"() {
        given: "a complex script"
        def script = """
            for (i in 1..10) {
                def foo() { }
                while (true) { break }
            }
        """
        
        when: "estimating resources"
        def estimate = securityService.estimateResources(script)
        
        then: "complexity is calculated"
        estimate.lines > 0
        estimate.complexity > 1
        estimate.estimatedMemoryMb > 0
        estimate.estimatedTimeoutSeconds > 0
    }
}
