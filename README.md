# MCP Groovy Filesystem Server

A powerful Model Context Protocol (MCP) server providing filesystem operations and Groovy script execution with comprehensive security features and **configurable command whitelists**.

## Features

### 🗂️ File Operations
- Read, write, copy, move, delete files
- List and search directories
- Create directories with nested paths
- Cross-platform path conversion (Windows ↔ WSL)

### 🔧 Groovy Script Execution
- Execute Groovy scripts with full DSL support
- **Automatic relative path resolution** - use relative paths naturally! 🆕
- Access to PowerShell (configurable whitelist)
- Access to Bash/WSL (configurable whitelist)
- Git operations integration
- Gradle build automation

### 🔒 Security Features
- Input validation (script length, dangerous patterns)
- **Configurable command whitelisting (PowerShell, Bash)** - no rebuild needed!
- Path traversal prevention
- Dangerous pattern detection (System.exit, Runtime.getRuntime, etc.)
- System path protection (/etc, /bin, C:\Windows)

### 📊 Audit Logging
- All script executions logged
- All command executions logged
- Security violations logged
- Sanitizes sensitive data (passwords, tokens, API keys)

### ⚡ Resource Control (Java 25+)
- Virtual Threads for lightweight concurrency
- Memory limits (256MB default, configurable)
- Thread limits (10 threads default, configurable)
- Execution timeouts (60s default, configurable)
- Real-time resource monitoring

---

## Quick Start

### Prerequisites
- Java 25 (JDK)
- Gradle 9.3+
- Claude Desktop

### Build
```powershell
.\gradlew.bat clean build
```

### Test
```powershell
.\gradlew.bat test
```

### Run Standalone (HTTP Mode)
```powershell
.\gradlew.bat bootRun
```

### Run with Claude Desktop (STDIO Mode)

1. **Build the JAR:**
   ```powershell
   .\gradlew.bat clean build
   ```

2. **Configure Claude Desktop:**
   
   Edit `C:\Users\[username]\AppData\Roaming\Claude\claude_desktop_config.json`:
   
   ```json
   {
     "mcpServers": {
       "groovy-filesystem": {
         "command": "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe",
         "args": [
           "--enable-native-access=ALL-UNNAMED",
           "-Dspring.profiles.active=stdio",
           "-Dmcp.mode=stdio",
           "-jar",
           "C:\\Users\\willw\\IdeaProjects\\mcp-groovy-filesystem-server\\build\\libs\\mcp-groovy-filesystem-server-0.0.2-SNAPSHOT.jar"
         ]
       }
     }
   }
   ```

3. **Restart Claude Desktop**

---

## Available Tools

### 1. `readFile`
Read file contents with encoding support.

**Parameters:**
- `path` (string): File path to read
- `encoding` (string, optional): File encoding (default: UTF-8)

### 2. `writeFile`
Write content to a file with optional backup.

**Parameters:**
- `path` (string): File path to write
- `content` (string): Content to write
- `encoding` (string, optional): File encoding (default: UTF-8)
- `createBackup` (boolean, optional): Create .backup file (default: false)

### 3. `listDirectory`
List files and directories with optional pattern filtering.

**Parameters:**
- `path` (string): Directory path
- `pattern` (string, optional): Regex pattern to filter files
- `recursive` (boolean, optional): Recursive listing (default: false)

### 4. `searchFiles`
Search for files containing specific content.

**Parameters:**
- `directory` (string): Directory to search
- `contentPattern` (string): Regex pattern to search in file contents
- `filePattern` (string, optional): Regex pattern to filter files

### 5. `copyFile`
Copy a file to a new location.

**Parameters:**
- `source` (string): Source file path
- `destination` (string): Destination file path
- `overwrite` (boolean, optional): Overwrite if exists (default: false)

### 6. `moveFile`
Move/rename a file.

**Parameters:**
- `source` (string): Source file path
- `destination` (string): Destination file path
- `overwrite` (boolean, optional): Overwrite if exists (default: false)

### 7. `deleteFile`
Delete a file or directory.

**Parameters:**
- `path` (string): File/directory path
- `recursive` (boolean, optional): Delete recursively (default: false)

### 8. `createDirectory`
Create a directory (including parent directories).

**Parameters:**
- `path` (string): Directory path to create

### 9. `normalizePath`
Convert paths between Windows and WSL formats.

**Parameters:**
- `path` (string): Path to normalize

**Returns:**
- `original`: Original path
- `normalized`: Normalized path (forward slashes)
- `windows`: Windows format (C:/...)
- `wsl`: WSL format (/mnt/c/...)

### 10. `executeGroovyScript` 🆕
Execute a Groovy script with full DSL support.

**Parameters:**
- `script` (string): Groovy script to execute
- `workingDirectory` (string): Working directory for script execution

**Available in Scripts:**
- File operations: `readFile()`, `writeFile()`, `listFiles()`, etc.
- PowerShell: `powershell('Get-ChildItem')`
- Bash: `bash('ls -la')`
- Git: `git('status')`
- Gradle: `gradle('build')`, `gradlew('clean', 'test')`
- Path operations: `toWslPath()`, `toWindowsPath()`

**Example:**
```groovy
def result = git('status', '--short')
if (result.exitCode == 0) {
    println "Clean working directory"
} else {
    println "Uncommitted changes:"
    println result.stdout
}
```

### 🆕 Automatic Relative Path Resolution

**NEW:** All file operations automatically resolve relative paths against the `workingDirectory`!

**Before (absolute paths required):**
```groovy
def content = readFile("C:\\Users\\willw\\project\\src\\main\\groovy\\App.groovy")
def files = searchFiles("C:\\Users\\willw\\project\\src", "class.*")
```

**After (relative paths work!):**
```groovy
def content = readFile("src/main/groovy/App.groovy")  // Much better!
def files = searchFiles("src", "class.*")             // Cleaner!
```

**How it works:**
- Relative paths (no drive letter, no leading `/`) are resolved against `workingDirectory`
- Absolute paths continue to work unchanged
- Security validation still applies after resolution
- Works with all file operations: `readFile()`, `writeFile()`, `listFiles()`, `searchFiles()`, `copyFile()`, `moveFile()`, `deleteFile()`, `createDirectory()`

**Example:**
```groovy
// Working directory: C:\Users\willw\IdeaProjects\MyProject

// These are equivalent:
readFile("src/main/groovy/App.groovy")
readFile("C:\\Users\\willw\\IdeaProjects\\MyProject\\src\\main\\groovy\\App.groovy")

// Relative paths make scripts portable:
listFiles("build/libs")              // Works anywhere!
copyFile("config.yml", "config.bak") // Clean and readable
```

---

## Security Features

### Input Validation
- **Script length limit:** 100KB max
- **Path validation:** No `..` traversal
- **Working directory:** Must be in allowed list

### Dangerous Pattern Detection
Blocks scripts containing:
- `System.exit`
- `Runtime.getRuntime()`
- `ProcessBuilder`
- `Class.forName`
- `GroovyClassLoader`
- `GroovyShell`
- `Eval.me`

### Dangerous Path Detection
Blocks access to:
- `/etc/passwd`, `/etc/shadow`
- `/bin/`, `/sbin/`, `/usr/bin/`
- `C:\Windows\System32`, `C:\Windows\SysWOW64`

### 🆕 Configurable Command Whitelisting

**NEW:** Command whitelists are now configured in `application.yml` - modify patterns without rebuilding!

**PowerShell Allowed (default patterns):**
- `Get-*`, `Select-*`, `Where-*`, `Measure-*`
- `Format-*`, `Out-*`, `Write-*`
- `.\gradlew.bat` commands
- `cd path; command` chaining
- Piping with `|`

**PowerShell Blocked (always takes precedence):**
- `Remove-*`, `Invoke-*`, `Set-ExecutionPolicy`
- `Stop-Computer`, `Restart-Computer`

**Bash Allowed (default patterns):**
- `ls`, `cat`, `grep`, `find`, `wc`, `head`, `tail`
- `echo`, `pwd`, `ps`, `awk`, `sed`, `sort`
- `./gradlew` commands
- Piping with `|`

**Bash Blocked (always takes precedence):**
- `rm`, `chmod`, `sudo`, `shutdown`, `kill`
- `eval`, `exec`, `source`

#### Adding New Commands

Edit `src/main/resources/application.yml`:

```yaml
mcp:
  script:
    whitelist:
      # Add new PowerShell patterns
      powershell-allowed:
        - '^npm .*'           # Allow npm commands
        - '^mvn clean.*'      # Allow Maven clean
        
      # Add new Bash patterns
      bash-allowed:
        - '^npm .*'           # Allow npm commands
        - '^docker ps.*'      # Allow docker ps
```

**Then just restart Claude Desktop** - no rebuild needed!

### Resource Limits
- **Memory:** 256MB max (configurable)
- **Threads:** 10 max (configurable)
- **Execution Time:** 60s max (configurable)
- **Virtual Threads:** Lightweight concurrency (Java 25+)

---

## Configuration

### `application.yml`

```yaml
mcp:
  filesystem:
    allowed-directories: C:/Users/willw/IdeaProjects,C:/Users/willw/claude
    max-file-size-mb: 10
    enable-write: true
  
  script:
    max-memory-mb: 256
    max-threads: 10
    max-execution-time-seconds: 60
    max-script-length: 100000
    enable-dangerous-pattern-check: true
    enable-file-path-validation: true
    enable-audit-logging: true
    
    # 🆕 Configurable command whitelists (no rebuild needed!)
    whitelist:
      powershell-allowed:
        - '^Get-ChildItem.*'
        - '^\\.\\\\gradlew\\.bat.*'
        - '^cd .+;.*'
        # Add your patterns here!
        
      powershell-blocked:
        - '.*Remove-Item.*'
        - '.*Invoke-Expression.*'
        # Add blocked patterns here
        
      bash-allowed:
        - '^ls.*'
        - '^\\.\/gradlew.*'
        # Add your patterns here!
        
      bash-blocked:
        - '.*rm .*'
        - '.*sudo.*'
        # Add blocked patterns here
```

---

## Testing

### Run All Tests
```powershell
.\gradlew.bat test
```

### Run Specific Test
```powershell
.\gradlew.bat test --tests ScriptSecurityServiceSpec
```

### View Test Report
```
build/reports/tests/test/index.html
```

**Test Coverage:**
- 71 comprehensive tests ✅
- FileSystem operations (12 tests)
- Script execution (10 tests)
- Security validation (8 tests)
- Audit logging (7 tests)
- Path conversion (8 tests)
- Command execution (10 tests)
- Integration tests (16 tests)

See `src/test/README.md` for detailed testing documentation.

---

## Architecture

### Components

```
mcp-groovy-filesystem-server
├── config/                    🆕
│   └── CommandWhitelistConfig (Configurable whitelists)
├── model/
│   ├── CommandResult          (Typed command results)
│   └── ScriptExecutionResult  (Typed script results)
├── service/
│   ├── AuditService          (Audit logging)
│   ├── FileSystemService     (File operations)
│   ├── GroovyScriptService   (Script execution)
│   ├── PathService           (Path conversion)
│   ├── ResourceControlService (Resource limits)
│   ├── ScriptExecutor        (External commands)
│   └── ScriptSecurityService (Security validation)
├── script/
│   └── SecureMcpScript       (Groovy DSL base class)
└── controller/
    └── McpController         (MCP protocol handler)
```

### Key Technologies
- **Spring Boot 4.0.2** - Application framework
- **Groovy 5.0.4** - Scripting engine
- **Spring AI MCP 1.1.2** - MCP protocol
- **Spock 2.4** - Testing framework
- **Java 25** - Virtual threads, structured concurrency

---

## Example Usage

### Simple File Operations
```
Read the README.md file from C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server
```

### Execute Groovy Script
```
Execute a Groovy script that:
1. Lists all .groovy files in src/main/groovy
2. Counts how many service files there are
3. Prints the result

Working directory: C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server
```

### Git Integration
```
Execute a Groovy script that checks git status and tells me if there are uncommitted changes.

Working directory: C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server
```

### Gradle Build Integration 🆕
```
Execute a Groovy script that runs gradlew clean test and reports the results.

Working directory: C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server
```

### PowerShell Integration
```
Execute a Groovy script that uses PowerShell to list all running Java processes.

Working directory: C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server
```

---

## Development

### Project Structure
```
src/
├── main/
│   ├── groovy/com/softwood/mcp/
│   │   ├── config/          🆕 (Configuration classes)
│   │   ├── controller/      (MCP handlers)
│   │   ├── model/           (Data models)
│   │   ├── script/          (Groovy DSL)
│   │   └── service/         (Business logic)
│   └── resources/
│       └── application.yml  (Configuration)
└── test/
    └── groovy/com/softwood/mcp/
        ├── controller/      (Integration tests)
        └── service/         (Unit tests)
```

### Building from Source

1. **Clone repository**
2. **Build:**
   ```powershell
   .\gradlew.bat clean build
   ```
3. **Run tests:**
   ```powershell
   .\gradlew.bat test
   ```
4. **Generate JAR:**
   ```
   build/libs/mcp-groovy-filesystem-server-0.0.2-SNAPSHOT.jar
   ```

---

## Troubleshooting

### Server Won't Start
- Check Java version: `java -version` (must be 25+)
- Check JAR exists: `build/libs/mcp-groovy-filesystem-server-0.0.2-SNAPSHOT.jar`
- Check config path in `claude_desktop_config.json`

### Scripts Failing with Security Errors
- Check audit logs for specific violation
- Review dangerous patterns in `ScriptSecurityService`
- Ensure working directory is in allowed list
- **Check whitelist patterns in `application.yml`** 🆕

### Commands Not Whitelisted 🆕
1. Check the command against patterns in `application.yml`
2. Add appropriate regex pattern to `powershell-allowed` or `bash-allowed`
3. Restart Claude Desktop (no rebuild needed!)

### Commands Timing Out
- Increase timeout in `application.yml`:
  ```yaml
  mcp.script.max-execution-time-seconds: 120
  ```

### High Memory Usage
- Reduce memory limit in `application.yml`:
  ```yaml
  mcp.script.max-memory-mb: 128
  ```

---

## Documentation

- **WHITELIST_CONFIGURATION_UPDATE.md** 🆕 - Configurable whitelist feature
- **SESSION_COMPLETION_SUMMARY.md** 🆕 - Latest changes summary
- **ENHANCEMENTS_SUMMARY.md** - Security & enhancement details
- **BUILD_WITH_ENHANCEMENTS.md** - Build guide
- **DEPLOYMENT_STRATEGY.md** - Deployment guide
- **TEST_PROMPTS.md** - Test scripts
- **src/test/README.md** - Testing documentation

---

## Version History

### v0.0.2-SNAPSHOT (Current) 🆕
- ✅ **Automatic relative path resolution** - use relative paths naturally!
- ✅ **Configurable command whitelists** - edit YAML, no rebuild!
- ✅ Gradle wrapper support (`.\gradlew.bat`, `./gradlew`)
- ✅ Command chaining support (`cd path; command`)
- ✅ 71 comprehensive tests passing
- ✅ Spring Boot @ConfigurationProperties integration
- ✅ Improved security with blacklist precedence

### v0.0.1-SNAPSHOT
- ✅ 10 MCP tools (9 filesystem + 1 script execution)
- ✅ Groovy script execution with DSL
- ✅ PowerShell/Bash/Git/Gradle integration
- ✅ Comprehensive security validation
- ✅ Audit logging
- ✅ Resource control (Virtual Threads)
- ✅ Type-safe result objects
- ✅ 67 comprehensive tests

---

## License

This project is part of the Anthropic MCP ecosystem.

---

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new features
4. Ensure all tests pass
5. Submit pull request

---

## Support

For issues or questions:
1. Check test reports: `build/reports/tests/test/index.html`
2. Review audit logs
3. Check whitelist configuration in `application.yml` 🆕
4. Consult documentation in project root

---

**Status:** ✅ Production Ready  
**Version:** 0.0.2-SNAPSHOT  
**Tests:** 71 passing ✅  
**Build:** Successful ✅  
**Last Updated:** February 4, 2026
