# mcp-groovy-filesystem-server v0.7.32

A Spring Boot MCP server providing filesystem and developer toolchain operations to Claude Desktop and Claude Code via HTTP/SSE. Also supports STDIO transport for compatibility.

Eight parameterised tools replace what would otherwise be 30+ individual tools, keeping the MCP schema compact and token-efficient.

---

## What's New in v0.7.32

### SQLite write-collision fix (v0.7.32)

Fix from Opus 4.6 assessment (FIX-1) — eliminates `SQLITE_BUSY` errors logged every 10 minutes.

**Root cause:** Both servers share `best_practices.db`. `UsageTracker.withConnection()` opened a connection without a `busy_timeout` PRAGMA. When the context server's persistent connection held a write lock, the filesystem server's periodic flush failed immediately with `SQLITE_BUSY`.

**Fixes applied:**
- `UsageTracker.withConnection()` now sets `PRAGMA busy_timeout=10000` (10 seconds patience) and `PRAGMA journal_mode=WAL` — matches context server's settings.
- `ensureSchema()` statement leaks fixed — all `conn.createStatement().execute(...)` calls wrapped in `.withCloseable { }` so statements are closed promptly.

**Impact:** Silent `UsageTracker: periodic flush failed` errors (3× per session at ~10 min intervals) are eliminated. Token usage tracking is now fully reliable.

---

## What's New in v0.7.31

### Context-window protection  response-size discipline (v0.7.31)

Follow-up fixes from a deep performance review (Opus 4.6, March 2026).  The prior round fixed
heap-blow risks; this round addresses **response-size discipline** — situations where
sub-threshold files still consume large chunks of the context window.

**FIX-15 — doRead() soft 60 KB response cap**
- `FileReadService.doRead()` now truncates content at 60 KB (~15K tokens) before shipping,
  adding a `_truncated: true` note with byte count and guidance to use head/range/grep.
- Previously the `_sizeWarning` was advisory only — the full content was still returned.
- Configurable via `mcp.filesystem.read-soft-cap-chars` (default 61440).

**FIX-16 — doMulti() 1 MB aggregate cap**
- `FileReadService.doMulti()` now pre-checks total file sizes before loading.
- If the aggregate exceeds 1 MB, the call is rejected with a helpful error.
- Prevents accidental 700K-token responses from 10 × 290 KB files.

**FIX-17 — FileSearchService genuine stream short-circuit**
- `FileSearchService.doContentSearch()` replaced `stream.each { if (cap) return }` with
  a proper filter-pipeline; `filesScanned` counter made into an `int[]` for lambda access.
- The walk was previously not short-circuited — it continued traversing the full tree
  even after `maxResults` was reached.  Now the per-file skip is immediate.

**Additional hygiene**
- `ChunkBufferService.createReadSession(String, String)` marked `@Deprecated`.
- Tool description for `multi` now mentions the 1 MB aggregate cap.
- `execute` tool description now prominently shows `maxStdout`/`maxStderr` defaults with
  estimated token cost (~12K / ~1.2K tokens respectively).

---

## What's New in v0.7.23 - v0.7.30

### CRLF drift fix — permanent normalisation (v0.7.23 - v0.7.29)

A long-standing source of subtle bugs was Windows writing CRLF line endings into source files
that were later read back on Linux (Syncthing sync), causing spurious diffs, grep misses, and
patch failures.  From v0.7.23 onwards **all mutating write paths normalise to LF on output**:
`doWrite`, `doAppend`, `doReplace`, `doPatch`, `doMultiReplace`, `doChunkWrite/finalise` all
call `shouldNormaliseLf()` and rewrite through a `\r\n → \n` pass before the atomic rename.
The original line-ending style is preserved only for the diagnostic `endings:` field in log
messages so that regressions are visible, but written bytes are always LF.  This eliminates
the CRLF/LF split permanently regardless of which OS built or edited a file.

Intermediate versions in this range also added: safe-restart helper scripts, improved
StructureCache hash-only path, and minor token-economy tweaks to tool descriptions.

### Performance hardening — all 14 fixes from cd-performance-assessment.md (v0.7.30)

A comprehensive code-review pass identified and fixed every performance and robustness issue
in the codebase.  All fixes are tagged with `// FIX-N` comments in source for traceability.

**HIGH — heap-load guards (FIX-1, FIX-2, FIX-3)**
- `FileWriteService.doReplace` and `doMultiReplace` now check file size against
  `replaceChunkThresholdKb` before calling `Files.readAllBytes()`.  Files over the threshold
  return a helpful error directing callers to use `patch` instead.
- The O(n×m) nearest-match fallback scan (which called `.toSet().intersect()` per line,
  allocating a `HashSet<Character>` for every line in the file) is removed entirely.  The
  first-pass `contains` scan is capped at 500 lines.  The hint is informational only —
  accuracy beyond 500 lines was not worth the allocation cost.

**MEDIUM — stream / process I/O fixes (FIX-4, FIX-5, FIX-6, FIX-7, FIX-8, FIX-9)**
- `FileSearchService.searchFileContent`: replaced `file.eachLine('UTF-8')` with explicit
  `withReader { BufferedReader br -> br.eachLine { ... } }` for guaranteed stream close.
- `ToolsService.runToolRaw` and `ExecuteService.runProcess`: stdout/stderr `StringBuilder`
  accumulation is now capped *inside* the virtual-thread reader loop (not downstream with
  `.take()`).  A process generating 200 MB of output no longer fills the heap before
  truncation — lines beyond the cap are drained and discarded so the child process never
  blocks on a full pipe.
- `McpController.estimateResponseSize`: replaced `response?.result?.toString()?.length()`
  (which forced full Map serialisation) with a direct read of the `text` field from the
  content list — zero allocations, zero serialisation.
- Virtual threads enabled for the entire Tomcat thread pool via
  `spring.threads.virtual.enabled=true` in `application.yml`.  Long-running tool calls
  (gradle builds, bash scripts) no longer monopolise platform threads.
- `UsageTracker`: replaced the single shared `volatile Connection dbConn` + `dbLock`
  serialisation bottleneck with per-operation short-lived connections.  Each `withConnection`
  call opens a fresh connection, sets `PRAGMA journal_mode=WAL`, runs the action, then closes.
  Concurrent periodic flush + stats reads no longer block each other.

**LOW — correctness and bounds fixes (FIX-10, FIX-11, FIX-12, FIX-13, FIX-14)**
- `FileListService`: `stream.each { if (results.size() >= max) return }` was using `return`
  as `continue`, iterating the entire directory even after the limit was hit.  Replaced with
  `stream.filter(...).limit(max).each` in `doChildren`, `doList`, and `doSizes`.
- `FileReadService.doDiff`: size pre-check added before loading both files into heap.  Files
  over `readChunkThresholdKb` return an error directing callers to `grep` or `range`.
- `FilesystemTelemetryService.sessionCallCache`: bounded at 1000 entries to prevent
  unbounded growth in long sessions.
- `AstStructureScanner`: `CompilerConfiguration` promoted to `private static final
  SHARED_COMPILER_CONFIG`.  It is read-only after initialisation and safe to share across
  threads.  `CompilationUnit` remains per-call as it is stateful.
- `StructureCache`: `evictIfNeeded()` moved from *inside* `synchronized(pathLock)` to
  *before* acquiring `pathLock` in both `getStructure()` and `getHash()`.  The previous
  ordering created nested lock acquisition (`pathLock → lruLock`) on every cache miss,
  serialising all concurrent hash computations through the LRU eviction lock.

---

## What's New in v0.7.22

### HTTP-first deployment + doStop port-kill fallback (v0.7.22)

The server ecosystem now runs as **persistent HTTP/SSE services** managed by a PowerShell/Groovy launcher script (`start-mcp-services.ps1` / `start-mcp-services.groovy` in `claude-sync/`). Both Claude Desktop and Claude Code connect via `http://localhost:808x/sse` - no STDIO duplication, no cold starts, services survive Claude restarts.

**`doStop` port-kill fallback**: previously `stop` only worked for servers started by the current session's `managedProcesses` map, returning `"not managed by this session"` for externally-launched servers. Now `doStop` has a three-stage fallback:
1. Managed process map (same session)
2. PID from `mcp-http-servers-runtime.json` (killed by `ProcessHandle`)
3. `POST /actuator/shutdown` (graceful self-shutdown)

This means `server_lifecycle action=stop name=context` works correctly whether the server was started by Claude, by the PowerShell launcher, or any other means. Stop-all now also runs in **reverse dependency order** (agentic → orchestrator → context → filesystem).

**Windows startup automation**: `Register-McpStartup.ps1` registers a Task Scheduler job that starts all 4 services at login with a 15s delay. Services are idempotent - already-running ports are skipped, so the script is safe to re-run.

**Claude Code support**: `~/.claude/settings.json` now mirrors Desktop config with HTTP URLs. Both clients can run side-by-side against the same service instances.

---

## What's New in v0.7.17 - v0.7.21

### Safe editing workflow + SKILL.md (v0.7.21)

**`file_write` tool description** now contains a full **SAFE EDITING WORKFLOW** section:
- Preferred pattern: `get_method` -> `patch` + `expectedHash` (line-addressed, always unique)
- Grep-first pattern: confirm uniqueness before any `replace`
- `multi_replace` rule: never make sequential `replace` calls -- batch all edits in one call
- CRITICAL RULES block: explicit NEVER/PREFER list preventing the silent corruption patterns
  that caused 3+ broken builds in mcp-agentic-workflow Phase 6

**`file_read` tool description** now ends with a CRITICAL note: every read returns
`file_content_hash` -- capture it and pass it as `expectedHash` on every write.

**`skills/SKILL.md`** added to project root -- worked examples of all three safe patterns,
an anti-patterns table, and a quick reference. Readable by Claude directly via:
```
file_read action=read path=C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/skills/SKILL.md
```

### v0.7.17 - v0.7.20

**Periodic UsageTracker flush + clean STDIO shutdown (v0.7.17)**

**Kill stale HTTP server PIDs from previous sessions on start (v0.7.18)**

**Filesystem telemetry hook (v0.7.19)**

**Write responses return `file_content_hash` alias (v0.7.20)**
All 5 mutating write actions now return `file_content_hash` alongside `content_hash`
so the hash is consistently named whether you last did a read or a write.

---

## What's New in v0.7.9 - v0.7.16

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

## Claude Desktop + Claude Code Config

Both clients connect via HTTP/SSE. Services must be started first via the launcher.

**`%APPDATA%\Claude\claude_desktop_config.json`** and **`~\.claude\settings.json`**:
```json
{
  "mcpServers": {
    "groovy-filesystem": { "url": "http://localhost:8081/sse" },
    "context-server":    { "url": "http://localhost:8082/sse" },
    "llm-orchestrator":  { "url": "http://localhost:8083/sse" },
    "agentic-workflow":  { "url": "http://localhost:8084/sse" }
  }
}
```

## Starting Services

```powershell
# Windows - start all services (idempotent, safe to re-run)
cd C:\Users\willw\claude-sync
.\start-mcp-services.ps1           # start
.\start-mcp-services.ps1 -Status   # health check
.\start-mcp-services.ps1 -Stop     # graceful stop

# Register auto-start at Windows login (run once)
.\Register-McpStartup.ps1
```

```bash
# Linux
groovy start-mcp-services.groovy           # start
groovy start-mcp-services.groovy --status  # health check
groovy start-mcp-services.groovy --stop    # stop
```

## Dev Loop (rebuild a server without restarting Claude)

```
# 1. Stop the target server (works even if started by launcher)
server_lifecycle action=stop name=filesystem

# 2. Build new jar
tools action=gradle subcommand=bootJar workingDir=C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server

# 3. Copy jar to jars/ and update mcp-http-servers.json if version changed
server_lifecycle action=reload

# 4. Restart
server_lifecycle action=ensure name=filesystem
server_lifecycle action=status
```

---

## Build & Deploy

```bash
./gradlew bootJar -x test
# Output: build/libs/mcp-groovy-filesystem-server-0.7.30.jar
# Deploy: copy to claude-sync/jars/
#         update mcp-http-servers.json jar name
#         server_lifecycle action=reload then action=ensure name=filesystem
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
| **0.7.31** | Response-size discipline: doRead 60KB soft cap (FIX-15), doMulti 1MB aggregate cap (FIX-16), FileSearchService genuine stream short-circuit (FIX-17), @Deprecated createReadSession(String,String), tool description improvements |
| **0.7.30** | 14 performance/robustness fixes: size guards on replace/multi_replace, O(nxm) nearest-match removed, stdout capped in loop, zero-copy estimateResponseSize, virtual threads, UsageTracker WAL per-op connections, stream limit short-circuit, doDiff size guard, sessionCallCache bound, shared CompilerConfig, evict lock order |
| **0.7.29** | LF normalisation on all write paths (doWrite/doReplace/doPatch) - eliminates Windows CRLF/Linux LF split permanently |
| **0.7.23-0.7.28** | Safe-restart helpers, StructureCache hash-only path, tool description token tweaks, intermediate hardening |
| **0.7.22** | doStop port-kill fallback (managed/runtime PID/actuator); reverse-order stop-all; HTTP-first config for Desktop+Code |
| **0.7.21** | Safe editing workflow in tool descriptions; skills/SKILL.md; expectedHash guidance |
| **0.7.20** | Write responses return file_content_hash alias alongside content_hash |
| **0.7.19** | FilesystemTelemetryService hook |
| **0.7.18** | Kill stale HTTP server PIDs from previous sessions on start |
| **0.7.17** | Periodic UsageTracker flush; clean STDIO shutdown |
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
