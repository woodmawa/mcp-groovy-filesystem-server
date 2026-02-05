package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.model.ScriptExecutionResult
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Tests for GroovyScriptService - script execution
 */
class GroovyScriptServiceSpec extends Specification {
    
    @TempDir
    Path tempDir
    
    GroovyScriptService groovyScriptService
    FileSystemService fileSystemService
    PathService pathService
    ScriptExecutor scriptExecutor
    ScriptSecurityService securityService
    AuditService auditService
    CommandWhitelistConfig whitelistConfig
    GitHubService githubService
    
    def setup() {
        pathService = new PathService()
        auditService = new AuditService()
        scriptExecutor = new ScriptExecutor(auditService)
        securityService = new ScriptSecurityService()
        
        // Create mock whitelist config with permissive patterns for testing
        whitelistConfig = new CommandWhitelistConfig()
        whitelistConfig.powershellAllowed = ['.*']  // Allow all in tests
        whitelistConfig.powershellBlocked = []
        whitelistConfig.bashAllowed = ['.*']  // Allow all in tests
        whitelistConfig.bashBlocked = []
        
        // Mock GitHubService
        githubService = Mock(GitHubService)
        
        fileSystemService = new FileSystemService(pathService)
        fileSystemService.allowedDirectoriesString = tempDir.toString()
        fileSystemService.init()
        fileSystemService.enableWrite = true

        //  FIX: SET BOUNDED LIMITS FOR TESTS (prevents 0-limit failures)
        fileSystemService.maxListResults = 100
        fileSystemService.maxSearchResults = 50
        fileSystemService.maxSearchMatchesPerFile = 10
        fileSystemService.maxTreeDepth = 5
        fileSystemService.maxTreeFiles = 200
        fileSystemService.maxReadMultiple = 10
        fileSystemService.maxLineLength = 1000
        fileSystemService.maxResponseSizeKb = 100
        fileSystemService.maxFileSizeMb = 10
        fileSystemService.activeProjectRoot = tempDir.toString()
        
        groovyScriptService = new GroovyScriptService(
            fileSystemService,
            pathService,
            scriptExecutor,
            securityService,
            auditService,
            whitelistConfig,
            githubService
        )
    }
    
    def "should execute simple println script"() {
        given: "a simple script"
        def script = "println 'Hello from Groovy!'"
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "script executes successfully"
        result.success == true
        result.output.contains("Hello from Groovy!")
    }
    
    def "should execute script with return value"() {
        given: "a script that returns a value"
        def script = "def x = 10; def y = 20; x + y"
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "result is captured"
        result.success == true
        result.result == 30
    }
    
    def "should execute script with file operations"() {
        given: "a script that creates and reads a file"
        def testFile = tempDir.resolve("test.txt").toString().replace('\\', '/')
        def script = """
            writeFile('${testFile}', 'Test content')
            def content = readFile('${testFile}')
            println "File content: \${content}"
        """
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "file operations work"
        result.success == true
        result.output.any { it.contains("File content: Test content") }
        new File(testFile).text == "Test content"
    }
    
    def "should execute script with list operations"() {
        given: "a directory with files"
        tempDir.resolve("file1.txt").toFile().text = "content1"
        tempDir.resolve("file2.txt").toFile().text = "content2"
        
        def tempDirStr = tempDir.toString().replace('\\', '/')
        def script = """
            def files = listFiles('${tempDirStr}')
            println "Found \${files.size()} files"
            files.each { f -> println "File: \${f.name}" }
        """
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "files are listed"
        result.success == true
        result.output.any { it.contains("Found 2 files") }
    }
    
    def "should handle script errors gracefully"() {
        given: "a script with an error"
        def script = "throw new RuntimeException('Test error')"
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "error is captured"
        result.success == false
        result.error.contains("Test error")
    }
    
    def "should inject services correctly"() {
        given: "a script that uses services"
        def script = """
            // Test that services are available
            assert fileSystemService != null
            assert pathService != null
            assert scriptExecutor != null
            println 'All services injected'
        """
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "services are available"
        result.success == true
        result.output.contains("All services injected")
    }
    
    def "should have access to workingDir variable"() {
        given: "a script that uses workingDir"
        def script = """
            println "Working directory: \${workingDir}"
        """
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "workingDir is available"
        result.success == true
        result.output.any { it.contains("Working directory:") }
        result.workingDir == pathService.normalizePath(tempDir.toString())
    }
    
    def "should handle multi-line scripts"() {
        given: "a multi-line script"
        def script = """
            def numbers = [1, 2, 3, 4, 5]
            def sum = numbers.sum()
            def avg = sum / numbers.size()
            println "Sum: \${sum}"
            println "Average: \${avg}"
            return avg
        """
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "script executes correctly"
        result.success == true
        result.result == 3
        result.output.contains("Sum: 15")
        result.output.contains("Average: 3")
    }
    
    def "should reject scripts for disallowed directories"() {
        given: "a disallowed directory"
        def disallowedDir = "C:/Windows/System32"
        def script = "println 'test'"
        
        when: "executing script in disallowed directory"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, disallowedDir)
        
        then: "security exception is captured"
        result.success == false
        result.error.contains("not allowed")
    }
    
    def "should support script with closures"() {
        given: "a script with closures"
        def script = """
            def numbers = [1, 2, 3, 4, 5]
            def doubled = numbers.collect { it * 2 }
            println "Doubled: \${doubled}"
        """
        
        when: "executing the script"
        ScriptExecutionResult result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "closures work"
        result.success == true
        result.output.any { it.contains("Doubled: [2, 4, 6, 8, 10]") }
    }

    
    def "should execute script with withOverride for temporary limit changes"() {
        given: "a directory with many files"
        (1..150).each { i ->
            tempDir.resolve("file${i}.txt").toFile().text = "content $i"
        }
        
        def tempDirStr = tempDir.toString().replace('\\', '/')
        def script = """
            // Test default limits
            def normalFiles = listFiles('${tempDirStr}', [recursive: false])
            println "Normal limit returned: \${normalFiles.size()} files"
            
            // Test with override
            def result = withOverride(maxListResults: 200) {
                def manyFiles = listFiles('${tempDirStr}', [recursive: false])
                println "Override limit returned: \${manyFiles.size()} files"
                return manyFiles.size()
            }
            
            // Test limits restored
            def afterFiles = listFiles('${tempDirStr}', [recursive: false])
            println "After override returned: \${afterFiles.size()} files"
            
            return result
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "withOverride works correctly"
        result.success == true
        result.output.any { it.contains("Normal limit returned: 100 files") }
        result.output.any { it.contains("Override limit returned: 150 files") }
        result.output.any { it.contains("After override returned: 100 files") }
        result.result == 150
    }

}