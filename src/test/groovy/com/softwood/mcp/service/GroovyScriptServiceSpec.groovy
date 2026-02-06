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

    // ========================================================================
    // NEW TESTS: file() helper for relative path resolution
    // ========================================================================
    
    def "file() helper should resolve relative paths against workingDir"() {
        given: "a nested directory structure"
        def subdir = tempDir.resolve("src/main/groovy").toFile()
        subdir.mkdirs()
        def testFile = new File(subdir, "Test.groovy")
        testFile.text = "class Test {}"
        
        and: "a script using file() helper with relative path"
        def script = """
            // Use relative path with file() helper
            def srcDir = file('src/main/groovy')
            
            println "Resolved path: \${srcDir.absolutePath}"
            println "Exists: \${srcDir.exists()}"
            println "Is directory: \${srcDir.isDirectory()}"
            
            // List files
            def files = []
            srcDir.eachFile { f ->
                files << f.name
            }
            
            println "Files found: \${files}"
            return files
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "relative path is resolved correctly against workingDir"
        result.success == true
        result.output.any { it.contains("Resolved path:") && it.contains("src") }
        result.output.any { it.contains("Exists: true") }
        result.output.any { it.contains("Is directory: true") }
        result.output.any { it.contains("Files found: [Test.groovy]") }
        result.result == ['Test.groovy']
    }
    
    def "file() helper should handle absolute paths unchanged"() {
        given: "an absolute path"
        def absolutePath = tempDir.toString().replace('\\', '/')
        
        and: "a script using file() helper with absolute path"
        def script = """
            def absDir = file('${absolutePath}')
            println "Absolute path: \${absDir.absolutePath}"
            println "Exists: \${absDir.exists()}"
            return absDir.absolutePath
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "absolute path is preserved"
        result.success == true
        result.output.any { it.contains("Exists: true") }
        result.result.replace('\\', '/').contains(tempDir.toString().replace('\\', '/'))
    }
    
    def "file() helper should work with eachFileRecurse for nested directories"() {
        given: "a nested directory structure with multiple files"
        def structure = [
            'src/main/groovy/Model.groovy': 'class Model {}',
            'src/main/groovy/Controller.groovy': 'class Controller {}',
            'src/test/groovy/ModelSpec.groovy': 'class ModelSpec {}',
            'README.md': '# Test Project'
        ]
        
        structure.each { path, content ->
            def file = tempDir.resolve(path).toFile()
            file.parentFile.mkdirs()
            file.text = content
        }
        
        and: "a script using file() with eachFileRecurse"
        def script = """
            def results = []
            file('src').eachFileRecurse { f ->
                if (f.name.endsWith('.groovy')) {
                    results << f.name
                }
            }
            
            println "Found \${results.size()} Groovy files"
            results.each { println "  - \${it}" }
            return results.sort()
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "all nested files are found"
        result.success == true
        result.result == ['Controller.groovy', 'Model.groovy', 'ModelSpec.groovy']
        result.output.any { it.contains("Found 3 Groovy files") }
    }
    
    def "file() helper should work with File text property"() {
        given: "a text file"
        def testFile = tempDir.resolve("config.txt").toFile()
        testFile.text = "key=value\nfoo=bar"
        
        and: "a script reading file with file() helper"
        def script = """
            def content = file('config.txt').text
            println "Content length: \${content.length()}"
            
            def lines = file('config.txt').readLines()
            println "Lines: \${lines.size()}"
            
            return lines
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "file content is read correctly"
        result.success == true
        result.result == ['key=value', 'foo=bar']
        result.output.any { it.contains("Lines: 2") }
    }
    
    def "file() helper should work with mkdirs"() {
        given: "a script creating nested directories"
        def script = """
            def targetDir = file('build/output/reports')
            def created = targetDir.mkdirs()
            
            println "Created: \${created}"
            println "Exists: \${targetDir.exists()}"
            println "Path: \${targetDir.absolutePath}"
            
            return targetDir.exists()
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "directories are created relative to workingDir"
        result.success == true
        result.result == true
        result.output.any { it.contains("Created: true") }
        tempDir.resolve("build/output/reports").toFile().exists()
    }
    
    def "file() helper should return null for null path"() {
        given: "a script testing null handling"
        def script = """
            def result = file(null)
            println "Result: \${result}"
            return result
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "null is returned"
        result.success == true
        result.result == null
        result.output.any { it.contains("Result: null") }
    }
    
    def "file() helper should work with exists() check"() {
        given: "a file and non-existent path"
        tempDir.resolve("exists.txt").toFile().text = "content"
        
        and: "a script checking existence"
        def script = """
            def existing = file('exists.txt').exists()
            def missing = file('missing.txt').exists()
            
            println "Existing file: \${existing}"
            println "Missing file: \${missing}"
            
            return [existing: existing, missing: missing]
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "existence checks work correctly"
        result.success == true
        result.result.existing == true
        result.result.missing == false
    }

    def "file() helper prevents FileNotFoundException with relative paths"() {
        given: "a nested directory structure"
        def srcDir = tempDir.resolve("src/main/groovy").toFile()
        srcDir.mkdirs()
        (1..3).each { i ->
            new File(srcDir, "File${i}.groovy").text = "class File${i} {}"
        }
        
        and: "a script that would fail without file() helper"
        def script = """
            // This would fail with: new File('src/main/groovy')
            // Because it resolves against JVM's working dir (Claude Desktop app dir)
            // But file() helper resolves against workingDir parameter
            
            def results = []
            file('src/main/groovy').eachFile { f ->
                results << f.name
            }
            
            println "Successfully listed \${results.size()} files using file() helper"
            return results.sort()
        """
        
        when: "executing the script"
        def result = groovyScriptService.executeScript(script, tempDir.toString())
        
        then: "no FileNotFoundException occurs"
        result.success == true
        result.result == ['File1.groovy', 'File2.groovy', 'File3.groovy']
        result.output.any { it.contains("Successfully listed 3 files") }
    }
}
