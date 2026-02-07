# MCP Groovy Filesystem Server

A Model Context Protocol (MCP) server providing 29 filesystem tools and Groovy script execution, built on Spring Boot 4.0.2, Groovy 5.0.4, and Java 25.

Designed for use with Claude Desktop via STDIO transport.

## Architecture

The server uses a **ToolHandler auto-discovery** pattern — each service implements a `ToolHandler` interface and self-registers its tools at startup. Adding a new tool means editing one file only.

```
controller/
  McpController           ← Thin dispatcher: tools/list + handlerMap lookup
service/
  ToolHandler             ← Interface: getToolDefinitions(), canHandle(), handleToolCall()
  AbstractFileService     ← Base class: sanitize(), isPathAllowed(), safeCompilePattern(), config
  FileReadService         ← 7 tools: readFile, readFileRange, headFile, tailFile, grepFile, countLines, readMultipleFiles
  FileWriteService        ← 7 tools: writeFile, replaceInFile, appendToFile, copyFile, moveFile, deleteFile, createDirectory
  FileQueryService        ← 7 tools: listChildrenOnly, listDirectory, searchInProject, searchFiles, findFilesByName, listDirectoryWithSizes, getDirectoryTree
  FileMetadataService     ← 7 tools: fileExists, getFileInfo, getFileSummary, getProjectRoot, normalizePath, getAllowedDirectories, isSymlinksAllowed
  GroovyScriptService     ← Executes Groovy scripts with secure DSL
  PathService             ← Windows ↔ WSL path conversion, relative path resolution
  ScriptExecutor          ← External command execution (PowerShell, Bash, Git, Gradle)
  ScriptSecurityService   ← Dangerous pattern detection, input validation
  AuditService            ← Audit logging for all operations
script/
  SecureMcpScript         ← Groovy DSL base class: file(), readFile(), git(), gradle(), powershell(), etc.
model/
  McpRequest/McpResponse  ← JSON-RPC 2.0 message types
  CommandResult           ← Typed command execution result
  ScriptExecutionResult   ← Typed script execution result
config/
  CommandWhitelistConfig  ← Configurable PowerShell/Bash whitelists (YAML, no rebuild)
```

## Tools (29)

### File Reading (FileReadService)
| Tool | Description |
|------|-------------|
| `readFile` | Read complete file contents with encoding support |
| `readFileRange` | Read specific line range (streaming, bounded) |
| `readMultipleFiles` | Read up to 10 files in one call |
| `grepFile` | Regex search with early termination |
| `headFile` | First N lines (streaming) |
| `tailFile` | Last N lines (circular buffer, streaming) |
| `countLines` | Line count without loading content |

### File Writing (FileWriteService)
| Tool | Description |
|------|-------------|
| `writeFile` | Write with optional backup, auto-creates parent dirs |
| `replaceInFile` | Find unique text and replace — no full read+write round-trip |
| `appendToFile` | Append without reading existing content |
| `copyFile` | Copy with optional overwrite |
| `moveFile` | Move/rename with optional overwrite |
| `deleteFile` | Delete file or directory (optionally recursive) |
| `createDirectory` | Create directory including parents |

### File Queries (FileQueryService)
| Tool | Description |
|------|-------------|
| `listChildrenOnly` | Immediate children only, bounded to max results |
| `listDirectory` | List with optional pattern filter and recursion |
| `searchInProject` | Search active project root (bounded) |
| `searchFiles` | Regex content search across files |
| `findFilesByName` | Find files by name pattern |
| `listDirectoryWithSizes` | List with sizes, sortable by name or size |
| `getDirectoryTree` | Recursive tree structure (max depth 5, max 200 files) |

### File Metadata (FileMetadataService)
| Tool | Description |
|------|-------------|
| `fileExists` | Check existence (no content read) |
| `getFileInfo` | Detailed metadata (size, dates, permissions) |
| `getFileSummary` | Metadata + line count without content |
| `getProjectRoot` | Active project root directory |
| `normalizePath` | Convert between Windows and WSL path formats |
| `getAllowedDirectories` | List allowed directories |
| `isSymlinksAllowed` | Check symlink policy |

### Script Execution
| Tool | Description |
|------|-------------|
| `executeGroovyScript` | Execute Groovy with secure DSL (file ops, git, gradle, powershell, bash) |

## Quick Start

### Prerequisites
- Java 25 (JDK)
- Gradle 9.3+
- Claude Desktop

### Build
```powershell
.\gradlew.bat clean build
```

### Configure Claude Desktop

Edit `%APPDATA%\Claude\claude_desktop_config.json`:

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
        "C:\\path\\to\\mcp-groovy-filesystem-server-0.0.3-SNAPSHOT.jar"
      ]
    }
  }
}
```

Restart Claude Desktop.

## Regex Pattern Best Practices

Many tools accept regex patterns for filtering filenames or searching content. Understanding how patterns work will help you get accurate results:

### Pattern Matching Behavior
- **Filename matching** (`listChildrenOnly`, `listDirectory`, `getDirectoryTree`): Matches against the **filename only**, not the full path
- **Filename finding** (`findFilesByName`): Uses `.find()` for **partial matches** - pattern can appear anywhere in filename
- **Content searching** (`grepFile`, `searchInProject`, `searchFiles`): Matches against file content line-by-line

### Regex Examples

#### ✅ Good Patterns
```groovy
// Simple substring match
"Controller"              // Matches: UserController.groovy, BlogController.java

// File extension
".*\\.groovy"            // Matches: Service.groovy, Controller.groovy
".*\\.(groovy|java)"     // Matches: *.groovy OR *.java

// Prefix/suffix
"^Test.*"                // Matches filenames starting with "Test"
".*Spec$"                // Matches filenames ending with "Spec"

// Combined
"^Test.*Controller"      // Matches: TestUserController, TestBlogController
```

#### ❌ Patterns That Don't Work As Expected
```groovy
// Anchors on full paths (these match filename only)
".*src/main.*"           // ❌ Won't match path, only filename

// Escaped backslashes for anchors (not needed for filename matching)
".*Service\\.groovy$"    // ⚠️  Works but anchor unnecessary for .find()
"Service\\.groovy"       // ✅ Simpler, same result
```

### Safe Regex Fallback
All regex tools use `safeCompilePattern()` which:
- ✅ Validates regex syntax
- ✅ Falls back to **literal match** if regex is invalid
- ✅ Logs warning about fallback behavior

**This means invalid regex won't crash - it just matches literally!**

```groovy
// Invalid regex - falls back to literal match
".*[invalid"             // Treated as literal string ".*[invalid"
```

### Tool-Specific Tips

| Tool | Pattern Behavior | Example |
|------|------------------|---------|
| `findFilesByName` | Uses `.find()` - partial match | `"Controller"` finds `UserController.groovy` |
| `listChildrenOnly` | Uses `.matches()` - full match | `".*\\.groovy"` matches full filename |
| `grepFile` | Uses `.find()` - line search | `"def\\s+\\w+"` finds method definitions |
| `searchInProject` | Uses `.find()` - line search | `"import\\s+.*Service"` finds imports |

### Recommended Approach
1. **Start simple**: Use substring matches first (`"Controller"`)
2. **Add specificity**: Use regex when you need precision (`".*Controller\\.groovy"`)
3. **Test incrementally**: Start broad, narrow down with more specific patterns
4. **Check logs**: If no results, check if pattern fell back to literal match

## Groovy Script DSL

The `executeGroovyScript` tool provides a rich DSL via `SecureMcpScript`:

```groovy
// File operations (relative paths resolve against workingDirectory)
def content = readFile('src/main/groovy/App.groovy')
writeFile('output.txt', 'Hello')
replaceInFile('config.yml', 'old-value', 'new-value')
appendToFile('log.txt', 'New entry\n')
def files = listFiles('src', [pattern: '.*\\.groovy$', recursive: true])

// file() helper for native Groovy File operations
file('src/main/groovy').eachFileRecurse { f ->
    if (f.name.endsWith('.groovy')) println f.name
}

// Git
git('status')
gitCommit('feat: new feature')
gitPush()
def branch = getCurrentBranch()

// Gradle
gradle('clean', 'build')

// PowerShell / Bash (whitelisted)
powershell('Get-ChildItem -Recurse')
bash('find . -name "*.groovy" | wc -l')

// GitHub API
def repos = githubListRepos()
def pr = githubCreatePR('owner/repo', 'Title', 'Body', 'feature-branch')
```

## Security

- **Path validation**: All paths checked against allowed directories
- **Symlink control**: Configurable symlink policy
- **Script validation**: Size limits, dangerous pattern detection (System.exit, Runtime.getRuntime, ProcessBuilder, etc.)
- **Command whitelisting**: PowerShell and Bash commands filtered by configurable regex patterns in `application.yml`
- **Audit logging**: All script executions, commands, and security violations logged
- **Safe regex**: Invalid patterns fall back to literal match via `Pattern.quote()`

## Configuration

Key settings in `application.yml`:

```yaml
mcp:
  filesystem:
    allowed-directories: C:/Users/willw/IdeaProjects,C:/Users/willw/claude
    active-project-root: C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server
    enable-write: true
    max-file-size-mb: 10
    max-list-results: 100
    max-search-results: 50
    max-search-matches-per-file: 10
    max-tree-depth: 5
    max-tree-files: 200
    max-read-multiple: 10
    max-line-length: 1000
    max-response-size-kb: 100
  script:
    whitelist:
      powershell-allowed: ['^Get-ChildItem.*', '^\\.\\gradlew\\.bat.*']
      powershell-blocked: ['.*Remove-Item.*', '.*Invoke-Expression.*']
      bash-allowed: ['^ls.*', '^\\.\/gradlew.*']
      bash-blocked: ['.*rm .*', '.*sudo.*']
```

## Testing

```powershell
.\gradlew.bat test                                    # All 85 tests
.\gradlew.bat test --tests FileServicesSpec            # File service tests
.\gradlew.bat test --tests McpControllerSpec           # Integration tests
.\gradlew.bat test --tests GroovyScriptServiceSpec     # Script execution tests
```

### Test Coverage (85 tests)
| Spec | Tests | Covers |
|------|-------|--------|
| FileServicesSpec | 23 | FileReadService, FileWriteService, FileQueryService, FileMetadataService |
| GroovyScriptServiceSpec | 18 | Script execution, DSL, file() helper, service injection |
| McpControllerSpec | 11 | MCP protocol, tool dispatch, ToolHandler wiring |
| ScriptExecutorSpec | 10 | PowerShell, Bash, generic command execution |
| PathServiceSpec | 8 | Windows ↔ WSL conversion, normalization |
| ScriptSecurityServiceSpec | 8 | Dangerous patterns, path traversal, input validation |
| AuditServiceSpec | 7 | Audit logging operations |

## Stack
- **Spring Boot 4.0.2** — Application framework
- **Groovy 5.0.4** — Language and scripting engine
- **Java 25** — Virtual threads, native access
- **Spring AI MCP 1.1.2** — MCP protocol support
- **Spock 2.4** — Testing framework

## Version History

### v0.0.4 (Current)
- **Cross-platform path handling**: Linux absolute paths (`/home/claude/...`) now map to configurable workspace
- **New config**: `claude-workspace-root` for mapping Claude.ai Linux paths to Windows/WSL
- **Path priority**: WSL mounts → Linux paths → Relative → Windows (4-level intelligent resolution)
- **Tests**: 27 PathService tests (up from 12), comprehensive Linux path coverage
- **Zero breaking changes**: Fully backward compatible with v0.0.3

### v0.0.3
- **Architecture**: Decomposed monolithic FileSystemService (930+ lines) into 4 focused services with ToolHandler auto-discovery
- **New tools**: `replaceInFile`, `appendToFile` — 60-80% token savings on file edits
- **Streaming I/O**: `headFile`, `tailFile`, `readFileRange`, `grepFile` use BufferedReader, never load full file
- **Safe regex**: `safeCompilePattern()` with graceful fallback to literal match
- **Removed**: Non-functional `watchDirectory`/`pollDirectoryWatch` tools
- **Tests**: 85 tests (up from 71), all rewritten for new architecture

### v0.0.2
- Automatic relative path resolution
- Configurable command whitelists (YAML, no rebuild)
- Token-optimized bounded results on all query tools
- 71 tests

### v0.0.1
- Initial release: 10 MCP tools, Groovy script DSL, security, audit logging
- 67 tests
