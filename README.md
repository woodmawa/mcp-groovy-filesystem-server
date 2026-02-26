# mcp-groovy-filesystem-server v0.7.16

A Spring Boot MCP server providing filesystem and developer toolchain operations to Claude Desktop via STDIO, plus HTTP transport for local LLM agentic loops.

Eight parameterised tools replace what would otherwise be 30+ individual tools, keeping the MCP schema compact and token-efficient.

---

## What's New in v0.7.9 – v0.7.16

### Token optimisation round 2 (v0.7.13 – v0.7.16)

**Write compact-by-default (v0.7.13)**
All `file_write` actions now return minimal `{success, content_hash}` by default. Pass `options.verbose:true` for full response with action/path/size. Covers all 7 write actions: write, append, replace, multi_replace, patch, chunk_write, finalise_write. `patch` always surfaces `verify_warning` even in compact mode.

**ServerLifecycleService (v0.7.14)**
- `loadConfig()` now cached in memory — reads `mcp-http-servers.json` once per session, not on every status/ensure/start call. Invalidated only by `reload`.
- `status` compact-by-default: `{name, port, state}` per server (3 fields vs 8). Pass `verbose:true` for full diagnostics.

**ExecuteService (v0.7.15)**
- All 4 actions (bash/powershell/cmd/groovy) compact-by-default: `{success, exitCode, stdout, stderr}` — no action echo, no durationMs.
- `stderr` default cap halved: 10,000 → 5,000 chars.
- Both caps now configurable: `options.maxStdout` (default 50,000), `options.maxStderr` (default 5,000).
- Pass `options.verbose:true` for full response with action + durationMs.

**FileLifecycleService (v0.7.16)**
- All 6 lifecycle actions compact-by-default. `create` returns `{success, type}`, all others return `{success}`.
- Pass `options.verbose:true` for full action/path echo.

### File read optimisations (v0.7.9 – v0.7.12)

**fileHash caching (v0.7.9)**
`file_content_hash` is now cached in `StructureCache` alongside the structure. No longer recomputed on every read/grep/checksum call.

**Compact flag extended (v0.7.10)**
`options.compact=true` now works on `head`, `tail`, `range`, and `grep` — not just `read`. Strips action/path echo, returns content + hash only.

**grep circular buffer (v0.7.11)**
`grep` with `contextLines > 0` no longer loads the whole file via `readLines()`. Uses a streaming circular buffer: O(contextLines) working memory regardless of file size.

**@CompileStatic fixes (v0.7.12)**
Fixed 3 type inference errors in `FileWriteService` where `String[].toList()` lost generic type under `@CompileStatic`.

---

## Token-Efficient Usage Patterns

### Compact / verbose flags

All write, execute, and lifecycle tools default to compact responses. Use `verbose:true` when you need diagnostics:

```
# file_write - compact by default
file_write action=write    path=...  content=...               -> {success, content_hash}
file_write action=write    path=...  options.verbose=true       -> {action, path, size, success, content_hash}

# file_read - verbose by default, compact opt-in
file_read  action=read     path=...  options.compact=true       -> {content, lines, file_content_hash}
file_read  action=grep     path=...  options.compact=true       -> {matchCount, matches, file_content_hash}

# execute - compact by default
execute    action=bash     script=...                          -> {success, exitCode, stdout, stderr}
execute    action=bash     script=... options.verbose=true     -> {action, success, exitCode, stdout, stderr, durationMs}
execute    action=bash     script=... options.maxStderr=500    -> truncate stderr at 500 chars

# server_lifecycle - compact by default
server_lifecycle action=status                                 -> [{name, port, state}, ...]
server_lifecycle action=status verbose=true                    -> [{name, port, state, jar, startupPolicy, managedBySession, processAlive}, ...]

# file_lifecycle - compact by default
file_lifecycle action=create path=... options.type=file        -> {success, type}
file_lifecycle action=delete path=...                          -> {success}
file_lifecycle action=copy   path=... dst=...                  -> {success}
```

### Cheap existence / size check before reading
```
file_read action=summary path=...  -> {lines, size}  (no content loaded)
file_read action=exists  path=...  -> {exists, type}
```

### Single method read (cheaper than structure + range)
```
file_read action=get_method path=MyService.groovy options.method=doRead
```

### Bulk parallel read (up to 10 files)
```
file_read action=multi options.paths=[path1, path2, path3]
```

### Hash-guarded edits (prevents silent corruption)
```
# Read first - note file_content_hash in response
file_read action=get_method path=... options.method=myMethod
# Edit with hash guard - rejected if file changed since read
file_write action=replace path=... options.oldText=... options.newText=... options.expectedHash=<hash>
```

### Tree with relative paths
```
file_list action=tree path=C:/Users/willw/IdeaProjects/myproject options.maxDepth=3
  rootPath: "C:/Users/willw/IdeaProjects/myproject"
  tree.path: "."
  tree.children[0].path: "src/main/groovy"
```

---

## Architecture

```
controller/
  McpController.groovy           @Component - thin JSON-RPC dispatcher, auto-discovers ToolHandlers
  HttpMcpController.groovy       @RestController - HTTP wrapper, delegates to McpController
service/
  ToolHandler.groovy             interface: getToolDefinitions(), canHandle(), handleToolCall()
  AbstractFileService.groovy     shared base: sanitize(), path validation, isCompact(), isWriteCompact()
  FileLifecycleService.groovy    create, delete, copy, move, rename, touch  [compact-by-default]
  FileListService.groovy         children, list, tree (relative paths), sizes
  FileSearchService.groovy       content, name, project search
  FileReadService.groovy         read, head, tail, range, grep, multi, info, summary, exists,
                                 project_root, allowed_dirs, normalize, diff, checksum,
                                 structure, get_method, chunk_read, finalise_read
  FileWriteService.groovy        write, append, replace, multi_replace, patch,
                                 chunk_write, finalise_write, abort_write  [compact-by-default]
  ExecuteService.groovy          bash, powershell, groovy, cmd  [compact-by-default]
  ToolsService.groovy            git, gradle, mvn, npm, project_scan, stats
  ServerLifecycleService.groovy  start/stop/status HTTP MCP server processes  [compact-by-default, config cached]
  StructureCache.groovy          AST structure + file hash cache (invalidated on write)
  ChunkBufferService.groovy      chunked transfer session management
  SecurityService.groovy         script validation, bounded execution, resource monitoring
  UsageTracker.groovy            per-action call counts, SQLite persistence
  PathService.groovy             cross-platform path normalisation
support/
  LogCleaner.groovy              control-character sanitisation
```

---

## The 8 Tools

| Tool | Actions | Default response |
|------|---------|-----------------|
| `file_lifecycle` | create, delete, copy, move, rename, touch | compact |
| `file_list` | children, list, tree, sizes | verbose |
| `file_search` | content, name, project | verbose |
| `file_read` | read, head, tail, range, grep, multi, info, summary, exists, project_root, allowed_dirs, normalize, diff, checksum, structure, get_method, chunk_read, finalise_read | verbose (`compact=true` opt-in) |
| `file_write` | write, append, replace, multi_replace, patch, chunk_write, finalise_write, abort_write | compact (`verbose=true` opt-in) |
| `execute` | bash, powershell, groovy, cmd | compact (`verbose=true` opt-in) |
| `tools` | git, gradle, mvn, npm, project_scan, stats | verbose |
| `server_lifecycle` | start_eager, ensure, stop, status, reload | compact (`verbose=true` opt-in) |

---

## HTTP Server Lifecycle

`server_lifecycle` manages the other HTTP MCP servers. Config in `claude-sync/mcp-http-servers.json`:

```json
{
  "jarsDir": "C:/Users/willw/claude-sync/jars",
  "javaCmd": "C:/Program Files/Java/jdk-25/bin/java.exe",
  "servers": [
    { "name": "filesystem",       "jar": "mcp-groovy-filesystem-server-0.7.16.jar", "port": 8081, "startupPolicy": "eager" },
    { "name": "context",          "jar": "mcp-groovy-context-server-0.11.0.jar",    "port": 8082, "startupPolicy": "eager" },
    { "name": "orchestrator",     "jar": "mcp-llm-orchestrator-0.4.0.jar",          "port": 8083, "startupPolicy": "lazy"  },
    { "name": "agentic-workflow", "jar": "mcp-agentic-workflow-0.6.0.jar",          "port": 8084, "startupPolicy": "lazy"  }
  ]
}
```

Session pattern:
```
server_lifecycle action=start_eager                  # bring up eager servers at session start
server_lifecycle action=ensure name=orchestrator     # on-demand lazy start
server_lifecycle action=stop                         # stop all at session end
server_lifecycle action=reload                       # re-read config after deploying new jars
```

Config is cached in memory after first read. `reload` forces re-read from disk — call after deploying a new jar. Servers already listening are skipped. PIDs tracked in `mcp-http-servers-runtime.json`. All managed servers stopped via `@PreDestroy` on JVM shutdown.

---

## Dual Transport Pattern

```
Claude Desktop (STDIO)              Local LLM agentic loop (HTTP)
        |                                      |
   stdio profile                         default profile
  web-type=none                         web-type=servlet
  port=0 (disabled)                     port=8081
        |                                      |
        +-----------> McpController <----------+
                       (@Component)
                            |
                     ToolHandler beans
                     (auto-discovered)
```

`McpController` is always `@Component`, never `@RestController`. `HttpMcpController` is the thin `@RestController` wrapper — only active when Tomcat is running. `web-application-type=none` in the stdio Spring profile ensures Tomcat never starts for Claude Desktop.

---

## Claude Desktop Config

```json
{
  "mcpServers": {
    "groovy-filesystem": {
      "command": "C:/Program Files/Java/jdk-25/bin/java.exe",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-Dmcp.filesystem.allowed-directories=C:/Users/willw/IdeaProjects, C:/Users/willw/claude, C:/Users/willw/AppData/Roaming/Claude, C:/Users/willw/claude-sync",
        "-Dspring.profiles.active=stdio",
        "-Dmcp.mode=stdio",
        "-jar",
        "C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-0.7.16.jar"
      ]
    }
  }
}
```

---

## Build & Deploy

```bash
./gradlew bootJar -x test
# Output: build/libs/mcp-groovy-filesystem-server-0.7.16.jar
# Deploy: copy to claude-sync/jars/
#         update mcp-http-servers.json jar name
#         update claude_desktop_config.json jar name
# Restart Claude Desktop to pick up the new STDIO process
```

---

## Security

- **Command whitelisting** - only approved executables allowed in `bash`, `powershell`, `cmd` actions
- **Allowed directories** - all file operations restricted to configured paths (set via `-Dmcp.filesystem.allowed-directories`)
- **Atomic writes** - `finalise_write` and `replace` use temp-file-then-rename for crash safety
- **Hash-guarded edits** - `options.expectedHash` on `patch`/`replace`/`multi_replace` rejects stale edits
- **JSON sanitisation** - multi-layer control-character stripping on all responses
- **Bounded execution** - configurable timeouts on all `execute` and `tools` actions; cancel-on-timeout enforced
- **Windows reserved name guard** - filters `NUL`, `CON`, `PRN`, `AUX`, `COM1-9`, `LPT1-9` from directory listings

---

## Version History

| Version | Highlights |
|---------|-----------|
| **0.7.16** | FileLifecycleService compact-by-default (success/type only) |
| **0.7.15** | ExecuteService compact-by-default; stderr cap 10k→5k; maxStdout/maxStderr configurable |
| **0.7.14** | ServerLifecycleService config cache (disk read once per session); status compact-by-default |
| **0.7.13** | file_write compact-by-default; verbose:true flag added to AbstractFileService |
| **0.7.12** | Fix @CompileStatic toList() errors in FileWriteService |
| **0.7.11** | grep contextLines streaming circular buffer (O(contextLines) memory, not O(file)) |
| **0.7.10** | compact flag extended to head/tail/range/grep |
| **0.7.9** | fileHash cached in StructureCache; no longer recomputed per read/grep/checksum call |
| 0.7.8 | AstStructureScanner, StructureCache, get_method action |
| 0.7.7 | Token optimisation: tighter descriptions, slim pathToMap, relative tree paths, compact mode |
| 0.7.6 | Security hardening: cancel-on-timeout, cmd whitelist, env passthrough, toRealPath, join budget |
| 0.7.5 | HTTP dual-transport (port 8081), HttpMcpController, ServerLifecycleService |
| 0.7.4 | UsageTracker SQLite persistence, period stats |
| 0.7.3 | Atomic finalise_write, CommandWhitelistConfig, doTail ring buffer, ServerVersion constant |
| 0.7.2 | doPatch hardened: overlap detection, atomic write, post-write verification, CRLF preservation |
| 0.7.1 | 7 consolidated tools, chunked I/O, virtual threads, SecurityService |
| 0.7.0 | ToolHandler architecture, 7 parameterised tools replacing 22 individual tools |
