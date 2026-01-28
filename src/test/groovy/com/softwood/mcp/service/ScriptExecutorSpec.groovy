package com.softwood.mcp.service

import com.softwood.mcp.model.CommandResult
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.IgnoreIf

import java.nio.file.Path

/**
 * Tests for ScriptExecutor - external command execution
 */
class ScriptExecutorSpec extends Specification {
    
    @TempDir
    Path tempDir
    
    ScriptExecutor scriptExecutor
    AuditService auditService
    
    def setup() {
        auditService = new AuditService()
        scriptExecutor = new ScriptExecutor(auditService)
    }
    
    def "should execute PowerShell command"() {
        given: "a simple PowerShell command"
        def command = "Write-Output 'Hello PowerShell'"
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executePowerShell(command, tempDir.toString())
        
        then: "command executes successfully"
        result.exitCode == 0
        result.success == true
        result.stdout.contains("Hello PowerShell")
        result.durationMs >= 0
    }
    
    def "should capture PowerShell errors"() {
        given: "a command that produces an error"
        def command = "Get-Item 'C:/NonExistent/Path'"
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executePowerShell(command, tempDir.toString())
        
        then: "error is captured"
        result.exitCode != 0
        result.success == false
        result.stderr.length() > 0
    }
    
    def "should execute PowerShell with file operations"() {
        given: "a command that creates a file"
        def testFile = tempDir.resolve("ps-test.txt")
        def command = "Set-Content -Path '${testFile}' -Value 'PowerShell content'"
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executePowerShell(command, tempDir.toString())
        
        then: "file is created"
        result.exitCode == 0
        testFile.toFile().exists()
        testFile.toFile().text.trim() == "PowerShell content"
    }
    
    @IgnoreIf({ !System.getenv("WSL_DISTRO_NAME") && !new File("C:\\Windows\\System32\\wsl.exe").exists() })
    def "should execute Bash command via WSL"() {
        given: "a simple bash command"
        def command = "echo 'Hello Bash'"
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executeBash(command, tempDir.toString())
        
        then: "command executes successfully"
        result.exitCode == 0
        result.success == true
        result.stdout.contains("Hello Bash")
    }
    
    @IgnoreIf({ !System.getenv("WSL_DISTRO_NAME") && !new File("C:\\Windows\\System32\\wsl.exe").exists() })
    def "should capture Bash errors"() {
        given: "a command that produces an error"
        def command = "cat /nonexistent/file"
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executeBash(command, tempDir.toString())
        
        then: "error is captured"
        result.exitCode != 0
        result.success == false
        result.stderr.length() > 0
    }
    
    def "should execute generic command"() {
        given: "a generic command (echo)"
        def executable = "cmd"
        def args = ["/c", "echo", "Hello Command"]
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executeCommand(executable, args, tempDir.toString())
        
        then: "command executes successfully"
        result.exitCode == 0
        result.success == true
        result.stdout.contains("Hello Command")
    }
    
    def "should handle command with multiple arguments"() {
        given: "a command with multiple args"
        def executable = "cmd"
        def args = ["/c", "dir", tempDir.toString()]
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executeCommand(executable, args, tempDir.toString())
        
        then: "command executes successfully"
        result.exitCode == 0
        result.success == true
    }
    
    def "should execute command in specified working directory"() {
        given: "a test file in temp directory"
        tempDir.resolve("marker.txt").toFile().text = "test"
        def command = "Test-Path 'marker.txt'"
        
        when: "executing in temp directory"
        CommandResult result = scriptExecutor.executePowerShell(command, tempDir.toString())
        
        then: "file is found"
        result.exitCode == 0
        result.stdout.trim() == "True"
    }
    
    def "should capture both stdout and stderr"() {
        given: "a command that writes to both streams"
        def command = "Write-Output 'stdout message'; Write-Error 'stderr message'"
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executePowerShell(command, tempDir.toString())
        
        then: "both outputs are captured"
        result.stdout.contains("stdout message")
        result.stderr.contains("stderr message")
    }
    
    def "should return proper result structure"() {
        given: "any command"
        def command = "Write-Output 'test'"
        
        when: "executing the command"
        CommandResult result = scriptExecutor.executePowerShell(command, tempDir.toString())
        
        then: "result has all required fields"
        result.exitCode != null
        result.stdout != null
        result.stderr != null
        result.success == (result.exitCode == 0)
        result.durationMs >= 0
    }
}
