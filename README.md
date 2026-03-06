# mcp-groovy-filesystem-server v0.7.53

A Spring Boot MCP server providing filesystem and developer toolchain operations to Claude Desktop and Claude Code via HTTP/SSE. Also supports STDIO transport for compatibility.

Eight parameterised tools replace what would otherwise be 30+ individual tools, keeping the MCP schema compact and token-efficient.

---

## What's New in v0.7.53

**CRITICAL BUG FIX: UsageTracker exponential data compounding on restart**

- **Root cause:** `UsageTracker.loadTodayFromDb()` used `SUM(call_count)` across ALL sessions for the
  current day when reloading in-memory counters on startup. However, `flushToDb()` writes accumulated
  totals via `INSERT OR REPLACE` keyed by `(recorded_date, tool_name, context_layer, session_id)`.
  On each restart within the same day, the reload summed every prior session's row into the new
  session's in-memory counters, then flushed that inflated total back under the new `session_id`.
  This caused **exponential compounding** — each restart roughly doubled the stored values.
  After several restarts, `response_bytes` reached values in the quintillions (8.2×10¹⁸) and
  `call_count` reached trillions, corrupting the `token_usage` table and causing SQLite integer
  overflow errors that broke the dashboard (`/dashboard/data` and `/dashboard/kpis` returned HTTP 500).

- **Fix:** `loadTodayFromDb()` now filters by `AND session_id = ?` (the current session's own ID),
  so it only reloads its own previously-flushed data. Other sessions' rows are never loaded into
  the in-memory counters, eliminating the compounding entirely.

- **Impact:** 1,560+ rows of corrupt data had to be manually purged from `token_usage`
  (`DELETE FROM token_usage WHERE response_bytes > 1000000000`). The corruption was confined to
  `context_layer='filesystem'` rows from 2026-03-05 and 2026-03-06. The context server's own
  `SqliteTelemetryStore` was unaffected as it uses a per-call `INSERT` pattern with no
  reload-on-startup accumulation.

- **Severity:** CRITICAL — silently corrupted telemetry data over multiple days, broke the
  dashboard, and produced misleading token burn analytics. The compounding was invisible until
  aggregation queries overflowed SQLite's integer range.

---

## What's New in v0.7.52


**Session ID pass-through to context server + ontology auto-reindex on write**

- **`reindexFileAsync()`** added to `ContextServerClient`: after every successful write to a `.groovy` or
  `.java` file, `FileWriteService` fires a fire-and-forget `context_write scope=ontology type=node
  action=index` call. The ontology reflects edited files within ~1 second of a write completing.
  No manual re-indexing needed after Claude Code build sessions.

- **Session ID pass-through**: `ContextServerClient` now resolves the active session ID before each
  `upsertFileRegistryAsync` call and injects it as a `sessionId` argument. The context server uses
  this to call `trackWorkingFile()` on the correct session, populating `session_working_files` for
  cross-session hash carry-forward. Resolution uses a lazy `GET /current-session` call to the
  context server HTTP endpoint (cached for the session duration).

---

## What's New in v0.7.51

**Async file-registry upsert to context server (Change B)**

- **`upsertFileRegistryAsync()`** added to `ContextServerClient`: after every `file_read` (read, head,
  tail, range, get_method) and `file_write` completion, fires a fire-and-forget HTTP POST to
  `context_write scope=knowledge type=file-registry action=upsert`. Keeps the context server's
  `file_hash_registry` table live without any extra tool calls from Claude.
- Uses a shared `asyncWriter` single-thread executor — never blocks the read/write response path.
  Failures logged at DEBUG only.
- `structurePersistEnabled` flag (from `application.properties`) gates all async calls — disabled
  when context server URL is not configured.

---

## What's New in v0.7.50

**Unicode preservation fix + NFC normalization fallback (v0.7.50):**

- **`sanitize()` preserving non-ASCII Unicode:** Both `AbstractFileService.sanitize()` and `Sanitizer.sanitize()` used a regex `[^\p{Print}\p{Space}]` to strip non-printable characters. However, `\p{Print}` only matches ASCII printable (0x20-0x7E), which silently stripped all non-ASCII characters including em-dashes (U+2014), smart quotes (U+2018/2019), accented characters, and CJK text. The `\p{Print}` filter has been removed; sanitize now only strips C0/C1 control characters (0x00-0x08, 0x0B-0x0C, 0x0E-0x1F, 0x7F-0x9F), preserving all valid Unicode in `file_read` responses and enabling correct round-trip through `replace`.
- **NFC normalization fallback in `replace`/`multi_replace`:** When exact byte matching fails, both `doReplace()` and `doMultiReplace()` now try Unicode NFC normalization on both file content and `oldText`. If a unique NFC-normalized match is found, the replacement proceeds using normalized content. This handles edge cases where multi-byte characters survive the JSON round-trip but in a different normalization form.
- **`oldText` line-ending normalization in `doReplace`:** `oldText` now gets `\r\n` -> `\n` normalization, matching the treatment already applied to `newText` and file content.

---

## What's New in v0.7.46

**Unicode crash fix in `replace` not-found diagnostic (v0.7.46):**
- **`FileReplaceService` unicode scan fix:** `doReplace()` scans `oldText` for non-ASCII characters when the text is not found, to emit a helpful `non_ascii_hint` in the error response. The scan used `eachWithIndex { char c, int i -> }` on a `String`, which passes `char` for ASCII but falls back to `Integer` codepoints for unicode > 127 (e.g. `←`, `—`, smart quotes). Under `@CompileStatic` the closure signature is locked at compile time to `(char, int)`, causing a Groovy MOP dispatch crash (`No signature of method: doCall for class ... applicable for argument types: (String, Integer)`). Changed to `toCharArray().eachWithIndex` which guarantees `char` elements. One word change, no logic change — the non-ASCII hint now fires correctly instead of crashing with a `-32603` internal error.

---

## What's New in v0.7.45

**Two startup/write bug fixes (v0.7.45):**

- **`UsageTracker` SQLite constraint fix:** `ensureSchema()` now wraps both `CREATE INDEX` calls in `try/catch`. The shared SQLite DB may already have `idx_token_usage_unique` created by the context server (potentially with columns in a different order), which caused `SQLITE_CONSTRAINT_UNIQUE` on every startup. The catch silently skips the index creation so `init()` completes cleanly and token telemetry is persisted to DB rather than running in-memory only.
- **`atomicWrite` missing-parent-dir fix:** `WriteUtils.atomicWrite()` now checks for parent directory existence before attempting `Files.write(tmp, ...)`. Previously, a missing parent caused `NoSuchFileException` whose message was the opaque `.tmp` path. The fix throws a clear `"Parent directory does not exist: <path>"` message. `FileWriteService` catches `NoSuchFileException` specifically and returns `-32602` (invalid params) rather than `-32603` (internal error), correctly signalling to callers that retrying with the same args won't help.

---

## What's New in v0.7.37

**multi hash short-circuit + re-read guidance (v0.7.37):**

- **`multi` hash short-circuit (`knownHashes`):** `file_read action=multi` now accepts `options.knownHashes: {"path" -> "12-char-hash"}`. For each path whose hash matches the server's current `StructureCache.getHash()` result, the server returns `{path, unchanged: true, file_content_hash}` with zero content bytes — no file I/O, no context window cost. Files with a stale or absent hash are fetched normally. The aggregate size pre-check also skips unchanged files from the byte count.
- **`file_content_hash` on all `multi` results:** Previously successful `multi` entries omitted `file_content_hash`, making it impossible to build a `knownHashes` map for subsequent calls. Now every successful result includes the hash, enabling callers to pass it back on the next `multi` call to skip unchanged files.
- **`unchanged_count` in `multi` response summary:** The top-level response now includes `unchanged_count` so callers can confirm how many files were short-circuited.
- **Tool description re-read guidance:** `multi` description updated to document `knownHashes` usage and added a global `NOTE: AVOID re-reading files already in context this session` with guidance to use `knownHashes` for staleness checks. `options.knownHashes` added to `inputSchema`.
- **Motivation:** Telemetry showed `file_read:multi` as the #3 token consumer (6.1 MB total). Worst single session: 85 multi calls / ~2 MB from repeated orientation re-reads. The hash short-circuit makes re-orientation calls near-zero cost when files haven't changed.

---

## What's New in v0.7.36

**Endpoint review + stdio hardening (v0.7.36):**

- **CommandWhitelistConfig refactor:** Whitelist configuration hardened and cleaned up — pattern matching improved for multi-line scripts.
- **ToolsService git status cap:** `doProjectScan` git status output capped at 2,000 chars (FS-4 follow-on) — consistent across both the dedicated git tool and project_scan.
- **UsageTracker WAL per-op connection:** Busy timeout and WAL pragma now set on every operation, not just flush. Eliminates residual `SQLITE_BUSY` edge cases under concurrent access.
- **application.yml stdio profile cleanup:** `web-application-type` now absent from stdio profile stanza (inherits `none` correctly without explicit override).

---

## What's New in v0.7.35

**Residual overflow risk fixes (v0.7.35) - 2 fixes from static analysis review:**

- **FS-2 - Remove duplicate `maxLines > 500` guard:** Dead second identical guard removed from `doRange`. Single cap now makes intent clear.
- **FS-4 - Cap `git status` output in `doProjectScan` to 2,000 chars:** In large repos, `git status` could be substantial. `project_scan` is a lightweight overview; status now truncated at 2K chars with a note to use the `git` tool.

---

## What's New in v0.7.34

**Context window overflow protection (v0.7.34) — 5 fixes from deep-review assessment:**

- **FIX-A  doRead soft cap aligned to 40 K chars (CRITICAL):** `read-soft-cap-chars` default reduced from 61,440 to 40,000. Aligns plain `action=read` with the hard caps on head/tail/range/grep. Responses over this limit emit `_truncated: true` with a note to use chunked read for full content.
- **FIX-B  doGetMethod output cap (HIGH):** Method body output is now capped at `partial-read-cap-chars` (40 K). A very large generated/data-heavy method body could previously return 90 K+ tokens in one call. Truncated responses include `_truncatedNote` with the `startLine` to resume from using `action=range`.
- **FIX-C  doStructure entry count + content truncation (HIGH):** Structure results now capped at `structure-max-entries` (default 100) with a `total_entries` field. Individual entry `content` strings truncated at 200 chars. Both compact and full responses benefit. Applies to files with very many methods/fields.
- **FIX-D  doList/doChildren response-size cap (HIGH):** List and children results now accumulate an estimated char count and stop adding entries once `list-response-cap-chars` (default 30,000) is reached. `_sizeCapped: true` and `total_available` tell the caller to paginate. `doSizes` hard-capped at 50 entries (diagnostic use only).
- **FIX-H  stdout/stderr truncation flags (LOW):** `execute` and `tools` responses now include `stdout_truncated: true` and/or `stderr_truncated: true` when output was silently cut by the capture cap. Previously there was no way for the caller to know output was incomplete.

---

## What's New in v0.7.33

**Hard response caps + PowerShell whitelist fix (v0.7.33):**

- **FIX-17 head/tail/range/grep hard caps:** All partial-read operations capped at 40,000 chars (~10 K tokens). Prevents the primary overflow source (was producing up to 116 K char single responses).
- **FIX-16 PowerShell multiline whitelist:** Changed from `==~` (full-string regex, fails on newlines) to `pattern.matcher(script).find()`. Multiline PowerShell scripts now pass the whitelist correctly.

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

### Bulk parallel read with hash short-circuit (up to 10 files)
```
# First call - no hashes yet, capture file_content_hash from each result
file_read action=multi options.paths=[path1, path2, path3]

# Subsequent calls - pass knownHashes to skip unchanged files entirely
file_read action=multi
          options.paths=[path1, path2, path3]
          options.knownHashes={"path1": "abc123def456", "path2": "fed987654321"}
# Unchanged files return: {path, unchanged: true, file_content_hash} - zero content bytes
# Changed/new files return: full content + updated file_content_hash
# Response includes unchanged_count showing how many were skipped
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
| **0.7.37** | multi hash short-circuit (knownHashes); file_content_hash on all multi results; unchanged_count in response; re-read guidance in description |
| **0.7.36** | CommandWhitelistConfig hardened; ToolsService git-status cap consistent; UsageTracker WAL per-op; stdio profile yml cleanup |
| **0.7.35** | FS-2 duplicate maxLines guard removed; FS-4 git status cap 2K in doProjectScan |
| **0.7.34** | FIX-A/B/C/D/H: doRead 40K cap, doGetMethod char cap, structure entry cap, list/children size cap, stdout/stderr truncation flags |
| **0.7.33** | head/tail/range/grep hard caps 40K; PowerShell multiline whitelist fix |
| **0.7.32** | SQLite busy_timeout + WAL; statement leak fixes in UsageTracker |
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
