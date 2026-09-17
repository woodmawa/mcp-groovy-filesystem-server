# Test Suite Documentation

## Overview

Comprehensive test suite for McpGroovyFileSystemServer using Spock Framework with **408 tests** (FS 0.9.39, 2026-09-17). The per-spec breakdown below dates from v0.0.1 and lists only the original specs.

## Test Structure

```
src/test/groovy/com/softwood/mcp/
├── controller/
│   └── McpControllerSpec.groovy         (Integration tests - 12 tests)
└── service/
    ├── AuditServiceSpec.groovy          (Audit logging - 7 tests) 🆕
    ├── FileSystemServiceSpec.groovy     (File operations - 12 tests)
    ├── GroovyScriptServiceSpec.groovy   (Script execution - 10 tests)
    ├── PathServiceSpec.groovy           (Path conversion - 8 tests)
    ├── ScriptExecutorSpec.groovy        (Command execution - 10 tests)
    └── ScriptSecurityServiceSpec.groovy (Security validation - 8 tests) 🆕
```

## Running Tests

### All Tests
```powershell
.\gradlew.bat test
```

### Specific Test Class
```powershell
.\gradlew.bat test --tests FileSystemServiceSpec
.\gradlew.bat test --tests GroovyScriptServiceSpec
.\gradlew.bat test --tests ScriptSecurityServiceSpec
.\gradlew.bat test --tests AuditServiceSpec
```

### With Output
```powershell
.\gradlew.bat test --info
```

### Continuous Testing
```powershell
.\gradlew.bat test --continuous
```

## Test Coverage

### FileSystemServiceSpec (12 tests)
✅ Read file contents
✅ Write file contents
✅ Create backup when writing
✅ List directory contents
✅ Filter files by pattern
✅ Search file contents
✅ Copy file
✅ Move file
✅ Delete file
✅ Create directory
✅ Reject access outside allowed directories
✅ Check if path is allowed

### GroovyScriptServiceSpec (10 tests)
✅ Execute simple println script
✅ Execute script with return value
✅ Execute script with file operations
✅ Execute script with list operations
✅ Handle script errors gracefully
✅ Inject services correctly
✅ Have access to workingDir variable
✅ Handle multi-line scripts
✅ Reject scripts for disallowed directories
✅ Support script with closures

### PathServiceSpec (8 tests)
✅ Convert Windows paths to WSL
✅ Convert WSL paths to Windows
✅ Normalize Windows paths
✅ Normalize WSL paths (converts to Windows)
✅ Get path representations
✅ Handle relative paths
✅ Handle paths with spaces
✅ Handle paths with special characters

### ScriptExecutorSpec (10 tests)
✅ Execute PowerShell command
✅ Capture PowerShell errors
✅ Execute PowerShell with file operations
✅ Execute Bash command via WSL (conditional)
✅ Capture Bash errors (conditional)
✅ Execute generic command
✅ Handle command with multiple arguments
✅ Execute command in specified working directory
✅ Capture both stdout and stderr
✅ Return proper result structure (CommandResult)

### McpControllerSpec (12 tests)
✅ Handle initialize request
✅ Handle tools/list request
✅ Handle readFile tool call
✅ Handle writeFile tool call
✅ Handle listDirectory tool call
✅ Handle executeGroovyScript tool call
✅ Handle searchFiles tool call
✅ Handle normalizePath tool call
✅ Handle copyFile tool call
✅ Handle errors gracefully
✅ Handle notifications (no response)

### ScriptSecurityServiceSpec (8 tests) 🆕
✅ Validate normal scripts
✅ Reject scripts that are too large (>100KB)
✅ Reject dangerous patterns (System.exit)
✅ Reject Runtime.getRuntime()
✅ Reject dangerous file paths (/etc/passwd)
✅ Reject path traversal in working directory (..)
✅ Sanitize passwords in logging
✅ Estimate script complexity

### AuditServiceSpec (7 tests) 🆕
✅ Log script execution
✅ Log failed script execution
✅ Log command execution
✅ Log security violation
✅ Log file operation
✅ Log unauthorized access
✅ Get audit statistics

**Total: 67 tests**

## Test Reports

After running tests, view reports at:
```
build/reports/tests/test/index.html
```

## Test Configuration

Tests use:
- **Spock Framework 2.4** for behavior-driven testing
- **JUnit Platform** for test execution
- **@TempDir** for isolated file system testing
- **Spring Boot Test** for integration testing
- **CommandResult** and **ScriptExecutionResult** for type-safe results

## Key Testing Patterns

### 1. Service Dependency Injection
```groovy
def setup() {
    pathService = new PathService()
    auditService = new AuditService()
    scriptExecutor = new ScriptExecutor(auditService)
    securityService = new ScriptSecurityService()
    
    fileSystemService = new FileSystemService(pathService)
    fileSystemService.allowedDirectoriesString = tempDir.toString()
    fileSystemService.init()
    fileSystemService.enableWrite = true
    
    groovyScriptService = new GroovyScriptService(
        fileSystemService,
        pathService,
        scriptExecutor,
        securityService,
        auditService
    )
}
```

### 2. Typed Result Objects
```groovy
// CommandResult (for external commands)
CommandResult result = scriptExecutor.executePowerShell(command, workingDir)
assert result.success
assert result.exitCode == 0
assert result.durationMs > 0

// ScriptExecutionResult (for Groovy scripts)
ScriptExecutionResult result = groovyScriptService.executeScript(script, workingDir)
assert result.success
assert result.output.contains("expected text")
```

### 3. Path Escaping for Groovy Scripts
```groovy
// Convert backslashes to forward slashes for Groovy string literals
def testFile = tempDir.resolve("test.txt").toString().replace('\\', '/')
def script = "writeFile('${testFile}', 'content')"
```

### 4. Security Testing
```groovy
def "should reject dangerous patterns"() {
    given: "a script with System.exit"
    def script = "System.exit(0)"
    
    when: "validating"
    securityService.validateScript(script, workingDir)
    
    then: "security exception is thrown"
    def e = thrown(SecurityException)
    e.message.contains("System.exit")
}
```

## Writing New Tests

### Example Test Structure

```groovy
package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir
import java.nio.file.Path

class MyServiceSpec extends Specification {
    
    @TempDir
    Path tempDir
    
    MyService service
    
    def setup() {
        service = new MyService()
    }
    
    def "should do something useful"() {
        given: "some precondition"
        // Setup
        
        when: "performing an action"
        // Execute
        
        then: "expected outcome occurs"
        // Verify
    }
}
```

## Mocking External Commands

Some tests (Bash via WSL) are conditionally skipped if WSL is not available:

```groovy
@IgnoreIf({ !new File("C:\\Windows\\System32\\wsl.exe").exists() })
def "should execute Bash command via WSL"() {
    // Test code
}
```

## Continuous Integration

To run tests in CI environments:

```yaml
# GitHub Actions example
- name: Run tests
  run: ./gradlew.bat test --no-daemon
  
- name: Upload test reports
  if: always()
  uses: actions/upload-artifact@v3
  with:
    name: test-reports
    path: build/reports/tests/
```

## Common Issues

### Issue: Tests fail with "Path not allowed"
**Solution:** Tests use `@TempDir` which is automatically allowed. If you need to test with specific paths, configure `allowedDirectoriesString` in setup.

### Issue: PowerShell tests fail
**Solution:** Ensure PowerShell is available on PATH. Tests expect PowerShell 5.1+ or PowerShell Core.

### Issue: Bash tests are skipped
**Solution:** WSL must be installed. These tests are automatically skipped on systems without WSL.

### Issue: "Cannot find matching constructor"
**Solution:** Ensure all services are properly initialized in setup with correct dependencies (especially new AuditService and ScriptSecurityService).

## Test Data

Tests automatically create temporary files and directories using Spock's `@TempDir`. All test data is cleaned up automatically after tests complete.

## Performance

Test execution times (approximate):
- **FileSystemServiceSpec**: ~2 seconds
- **GroovyScriptServiceSpec**: ~3 seconds  
- **PathServiceSpec**: ~0.5 seconds
- **ScriptExecutorSpec**: ~4 seconds (includes external commands)
- **ScriptSecurityServiceSpec**: ~1 second 🆕
- **AuditServiceSpec**: ~0.5 seconds 🆕
- **McpControllerSpec**: ~3 seconds

**Total**: ~14-17 seconds

## Debugging Tests

### Run single test with debug output
```powershell
.\gradlew.bat test --tests "FileSystemServiceSpec.should read file contents" --debug
```

### Run with verbose logging
```powershell
.\gradlew.bat test --info --stacktrace
```

## New Test Features (v0.0.1)

### Security Validation Tests
- ✅ Pattern detection (System.exit, Runtime.getRuntime)
- ✅ Path validation (dangerous system paths)
- ✅ Script size limits
- ✅ Path traversal prevention

### Audit Logging Tests
- ✅ Script execution logging
- ✅ Command execution logging
- ✅ Security violation logging
- ✅ File operation logging

### Type Safety Tests
- ✅ CommandResult validation
- ✅ ScriptExecutionResult validation
- ✅ Proper field types and values

## Contributing

When adding new features:
1. Write tests first (TDD approach)
2. Ensure all existing tests pass
3. Maintain test coverage above 80%
4. Follow existing test naming conventions
5. Use meaningful test descriptions
6. Add security tests for new capabilities

---

**Last Updated:** January 28, 2026  
**Test Framework:** Spock 2.4 with Groovy 5.0  
**Total Tests:** 67  
**Status:** ✅ All Passing
