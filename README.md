# McpGroovyFileSystemServer v0.7.3

A Spring Boot MCP (Model Context Protocol) server providing filesystem and developer toolchain operations to Claude Desktop via stdio transport.

## What's New in v0.7.3 — Hardening Release

Based on a full Opus-4 deep assessment of the v0.7.2 codebase, this release addresses all P1 and P2 findings plus selected P3 items:

**P1 — Critical fixes:**
- **`doFinaliseWrite` atomic write** — chunked write assembly now uses temp-file + atomic rename, matching `doPatch`. Previously a JVM crash mid-write would zero the target file after chunks were already discarded from the buffer.
- **`CommandWhitelistConfig` wired up** — `doBash` and `doPowershell` in `ExecuteService` now actually consult the YAML allow/block pattern lists. Previously the config was loaded but never consulted (dead code).
- **Version string fixed** — `tools stats` now returns the correct `serverVersion` from a single `ServerVersion.VERSION` constant shared between `McpController` and `ToolsService`.

**P2 — Robustness:**
- **`git commit` message enforcement** — missing `options.message` on a commit now returns a clear error immediately instead of hanging the process waiting for an editor.
- **`.execute()` and `Runtime.exec` blocked** — added to `SecurityService.DANGEROUS_SCRIPT_PATTERNS` to prevent Groovy scripts using the `String.execute()` shell bypass.
- **`abort_write` schema clarified** — tool description now notes that `path` is ignored for `abort_write`.

**P3 — Efficiency:**
- **`doTail` ring buffer** — `file_read tail` now streams using an `ArrayDeque` ring buffer instead of loading the entire file into memory.

**Previously in v0.7.2:**
- `doPatch` hardened: overlap detection, atomic temp-file write, post-write verification, CRLF preservation

**Previously in v0.7.1:**
- 22 tools → 7 parameterised tools (~1,190 token saving per conversation init)
- Chunked read & write (overcomes ~700 KB stdio limit via `ChunkBufferService`)
- Self-contained Promise module on `CompletableFuture` + virtual threads (Java 25)
- Consolidated security (`SecurityService`), `UsageTracker`, `AbstractFileService` base class

## Architecture

```
controller/
  McpController.groovy           thin JSON-RPC dispatcher, auto-discovers ToolHandlers
service/
  ToolHandler.groovy             interface: getToolDefinitions(), canHandle(), handleToolCall()
  AbstractFileService.groovy     shared base: sanitize(), safeCompilePattern(), path validation
  FileLifecycleService.groovy    create, delete, copy, move, rename, touch
  FileListService.groovy         children, list, tree, sizes
  FileSearchService.groovy       content, name, project search
  FileReadService.groovy         read, head, tail, range, grep, multi, info, exists, diff, checksum, chunked read
  FileWriteService.groovy        write, append, replace, multi_replace, patch, chunked write
  ExecuteService.groovy          bash, powershell, groovy, cmd execution
  ToolsService.groovy            git, gradle, mvn, npm, project_scan, stats
  ChunkBufferService.groovy      chunked transfer session management (read + write)
  SecurityService.groovy         script validation, redaction, bounded execution, resource monitoring
  UsageTracker.groovy            per-action call counts, response sizes, bounded/full read ratio
  PathService.groovy             cross-platform path normalisation, WSL ↔ Windows conversion
promise/
  Promise.groovy                 lightweight async interface
  PromiseImpl.groovy             CompletableFuture + virtual thread implementation
  Promises.groovy                static factory (newPromise, async, all, any)
script/
  SecureMcpScript.groovy         Groovy script base class with DSL (powershell, bash, git helpers)
config/
  CommandWhitelistConfig.groovy  YAML-driven allow/block lists per shell type (now wired in ExecuteService)
support/
  JsonRpcWriter.groovy           safe JSON-RPC output to stdout
  Sanitizer.groovy               control character removal for clean JSON
  LogCleaner.groovy              session log hygiene
ServerVersion.groovy             single source of truth for server version constant
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

Files larger than 300 KB are automatically chunked into 400 KB segments to stay within the ~700 KB stdio transport limit.

**Reading large files:**
```
file_read action=read path=<large-file>
  → returns sessionId + totalChunks (when file exceeds threshold)

file_read action=chunk_read options={sessionId, chunkIndex: 0}
file_read action=chunk_read options={sessionId, chunkIndex: 1}
...
file_read action=finalise_read options={sessionId}
```

**Writing large files (atomic assembly since v0.7.3):**
```
file_write action=chunk_write content=<chunk0> options={sessionId, chunkIndex: 0}
file_write action=chunk_write content=<chunk1> options={sessionId, chunkIndex: 1}
...
file_write action=finalise_write path=<target> options={sessionId, totalChunks: N}
```
The finalise step assembles chunks and writes via temp-file + atomic rename. Sessions auto-expire after 30 minutes.

## Security Model

This is a **developer tool** running with process-level permissions. Security is defence-in-depth, not a sandbox:

- **Script validation** — length limits, dangerous pattern detection (`System.exit`, `ProcessBuilder`, `.execute()` shell bypass, `Runtime.exec`, etc.), restricted system path checks
- **Command whitelists** — YAML-configured allow/block regex lists per shell (powershell, bash), now enforced at execution time
- **Bounded execution** — configurable timeouts via virtual threads
- **Path security** — all operations validated against allowed-directories; symlink access controlled; path traversal blocked
- **Sanitisation** — control characters stripped from all output; recursive sanitisation for nested Maps/Lists
- **Sensitive data redaction** — passwords, tokens, API keys scrubbed from logs
- **Windows reserved names** — NUL, CON, PRN etc. detected and handled (Java NIO used throughout, not Groovy GDK)
- **SecureMcpScript** — Groovy scripts run with controlled base class; note file helpers in SecureMcpScript bypass path validation by design (they run with process-level access)

## Usage Tracking

The `tools stats` action returns live session metrics:

```json
{
  "serverVersion": "0.7.3",
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
        "C:/path/to/mcp-groovy-filesystem-server-0.7.3.jar",
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

The bootJar output goes to `build/libs/mcp-groovy-filesystem-server-0.7.3.jar`.

## Version History

| Version | Highlights |
|---------|-----------|
| 0.7.3 | Hardening: atomic finalise_write, CommandWhitelistConfig wired, git commit message enforced, .execute() blocked, doTail ring buffer, version constant unified |
| 0.7.2 | doPatch hardened: overlap detection, atomic write, post-write verification, CRLF preservation |
| 0.7.1 | 7 consolidated tools, chunked I/O, Promise module, SecurityService, UsageTracker, gradle absolutePath fix |
| 0.0.6 | 22 individual tools, token efficiency tracker |
| 0.0.3 | ToolHandler refactoring, 85 tests, McpController auto-discovery |
