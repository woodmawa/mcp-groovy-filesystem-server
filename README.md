# McpGroovyFileSystemServer v0.7.1

A Spring Boot MCP (Model Context Protocol) server providing filesystem and developer toolchain operations to Claude Desktop via stdio transport.

## What's New in v0.7.1

**Major consolidation rebuild (from v0.0.6):**
- **22 tools → 7 parameterised tools** — ~1,190 token saving per conversation init
- **Chunked read & write** — overcomes ~700 KB stdio transport limit via `ChunkBufferService`
- **Self-contained Promise module** — `Promise`/`PromiseImpl`/`Promises` on `CompletableFuture` + virtual threads (Java 25)
- **Consolidated security** — `SecurityService` replaces `ScriptSecurityService` + `ResourceControlService`
- **`file_list` and `file_search` split** from `file_read` for better inference-time tool selection
- **AbstractFileService** base class — centralised sanitisation, path validation, safe regex compilation
- **Per-executor enable flags** — individually enable/disable bash, powershell, groovy, cmd
- **Command whitelists** — allow/block patterns per shell type
- **UsageTracker** — lightweight per-action call counts, response sizes, bounded vs full read ratio (via `tools stats`)
- **Gradle wrapper fix** — `gradlew.bat` resolved via absolute path for reliable ProcessBuilder execution

## Architecture

```
controller/
  McpController.groovy          — thin JSON-RPC dispatcher, auto-discovers ToolHandlers
service/
  ToolHandler.groovy            — interface: getToolDefinitions(), canHandle(), handleToolCall()
  AbstractFileService.groovy    — shared base: sanitize(), safeCompilePattern(), path validation
  FileLifecycleService.groovy   — create, delete, copy, move, rename, touch
  FileListService.groovy        — children, list, tree, sizes
  FileSearchService.groovy      — content, name, project search
  FileReadService.groovy        — read, head, tail, range, grep, multi, info, exists, diff, checksum, chunked read
  FileWriteService.groovy       — write, append, replace, multi_replace, patch, chunked write
  ExecuteService.groovy         — bash, powershell, groovy, cmd execution
  ToolsService.groovy           — git, gradle, mvn, npm, project_scan, stats
  ChunkBufferService.groovy     — chunked transfer session management (read + write)
  SecurityService.groovy        — script validation, redaction, bounded execution, resource monitoring
  UsageTracker.groovy           — per-action call counts, response sizes, bounded/full read ratio
  PathService.groovy            — cross-platform path normalisation, WSL ↔ Windows conversion
promise/
  Promise.groovy                — lightweight async interface
  PromiseImpl.groovy            — CompletableFuture + virtual thread implementation
  Promises.groovy               — static factory (newPromise, async, all, any)
script/
  SecureMcpScript.groovy        — Groovy script base class with DSL (powershell, bash, git helpers)
config/
  CommandWhitelistConfig.groovy — YAML-driven allow/block lists per shell type
support/
  JsonRpcWriter.groovy          — safe JSON-RPC output to stdout
  Sanitizer.groovy              — control character removal for clean JSON
  LogCleaner.groovy             — session log hygiene
```

## The 7 Tools

| Tool | Actions |
|------|---------|
| `file_lifecycle` | create, delete, copy, move, rename, touch |
| `file_list` | children, list, tree, sizes |
| `file_search` | content, name, project |
| `file_read` | read, head, tail, range, grep, multi, info, summary, exists, project_root, allowed_dirs, normalize, diff, checksum, structure, chunk_read, finalise_read |
| `file_write` | write, append, replace, multi_replace, patch, chunk_write, finalise_write, abort_write |
| `execute` | bash, powershell, groovy, cmd |
| `tools` | git, gradle, mvn, npm, project_scan, stats |

## Chunked Transfer (Large Files)

Files larger than 300 KB are automatically chunked into ≤400 KB segments to stay within the ~700 KB stdio transport limit.

**Reading large files:**
```
file_read action=read path=<large-file>
→ returns sessionId + totalChunks (when file exceeds threshold)

file_read action=chunk_read options={sessionId, chunkIndex: 0}
file_read action=chunk_read options={sessionId, chunkIndex: 1}
...
file_read action=finalise_read options={sessionId}
```

**Writing large files:**
```
file_write action=chunk_write content=<chunk0> options={sessionId, chunkIndex: 0}
file_write action=chunk_write content=<chunk1> options={sessionId, chunkIndex: 1}
...
file_write action=finalise_write path=<target> options={sessionId, totalChunks: N}
```

Sessions auto-expire after 30 minutes if not finalised.

## Usage Tracking

The `tools stats` action returns live session metrics including per-action call counts, response sizes, and bounded vs full read ratio — no separate tool needed:

```json
{
  "serverVersion": "0.7.1",
  "jvm": { "usedMemoryMb": 124, "availableProc": 32 },
  "chunkBuffer": { "activeWriteSessions": 0, "activeReadSessions": 0 },
  "usage": {
    "totalCalls": 15,
    "boundedReads": 12,
    "fullReads": 1,
    "boundedRatio": 92,
    "perAction": [
      { "key": "file_read:head", "calls": 5, "responseKB": 2 },
      { "key": "file_search:content", "calls": 3, "responseKB": 4 }
    ]
  }
}
```

## Security

- **Script validation** — length limits, dangerous pattern detection (System.exit, ProcessBuilder, etc.), restricted system path checks
- **Command whitelists** — YAML-configured allow/block regex lists per shell (powershell, bash)
- **Bounded execution** — configurable timeouts via virtual threads
- **Path security** — all operations validated against allowed-directories; symlink access controlled; path traversal blocked
- **Sanitisation** — control characters stripped from all output; recursive sanitisation for nested Maps/Lists
- **Sensitive data redaction** — passwords, tokens, API keys scrubbed from logs
- **Windows reserved names** — NUL, CON, PRN etc. detected and handled
- **SecureMcpScript** — Groovy scripts run with controlled base class

## Tech Stack

- Spring Boot 4.0.2
- Groovy 5.0.4
- Java 25 (virtual threads)
- Spock 2.4 for testing

## Configuration

Key settings in `application.yml`:

```yaml
mcp:
  filesystem:
    allowed-directories: C:/Users/willw/IdeaProjects,C:/Users/willw/claude
    active-project-root: C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server
    claude-workspace-root: C:/Users/willw/claude
    enable-write: true
    read-chunk-threshold-kb: 300
    max-file-size-mb: 10
    max-list-results: 100
    max-search-results: 50
    max-tree-depth: 5
    max-tree-files: 200
    max-line-length: 1000
    max-response-size-kb: 100

  script:
    max-execution-time-seconds: 60
    enable-bash: true
    enable-powershell: true
    enable-groovy: true
    enable-cmd: true
    whitelist:
      powershell-allowed: ['.*']
      powershell-blocked: ['.*Remove-Item.*', '.*Stop-Computer.*', '.*Format-Volume.*']
      bash-allowed: ['.*']
      bash-blocked: ['.*rm .*', '.*sudo.*', '.*shutdown.*']
```

## Claude Desktop Config

```json
{
  "mcpServers": {
    "groovy-filesystem": {
      "command": "java",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-jar",
        "C:/path/to/mcp-groovy-filesystem-server-0.7.1.jar",
        "--spring.profiles.active=stdio"
      ]
    }
  }
}
```

## Build

```bash
./gradlew compileGroovy        # compile check
./gradlew test                 # run Spock tests
./gradlew bootJar              # build deployable jar
```

The bootJar output goes to `build/libs/mcp-groovy-filesystem-server-0.7.1.jar`.

## Version History

| Version | Highlights |
|---------|-----------|
| 0.7.1 | 7 consolidated tools, chunked I/O, Promise module, SecurityService, UsageTracker, gradle absolutePath fix |
| 0.0.6 | 22 individual tools, token efficiency tracker |
| 0.0.3 | ToolHandler refactoring, 85 tests, McpController auto-discovery |
