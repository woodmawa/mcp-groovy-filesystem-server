# mcp-groovy-filesystem-server v0.8.66

Spring Boot / Groovy MCP server providing filesystem, developer toolchain, and server lifecycle operations
to Claude Desktop and Claude Code via STDIO (primary) and Streamable HTTP (HTTP companion mode).

---

## Architecture — FS ↔ Context Server Call Patterns

Understanding how FS and context-server interact is critical for correct diagnosis when things go wrong.

### Transport paths

```
Claude Desktop (DT)
  │
  ├─ stdio ──► FS stdio JVM (McpController)           ← Claude tool calls arrive here
  │              │
  │              ├─ file_read / file_write / execute / server_lifecycle
  │              │
  │              ├─ FilesystemTelemetryService          ← records tool_call_telemetry
  │              │    └─ JDBC ──► best_practices.db     ← shared SQLite, also owned by context-server
  │              │
  │              └─ ContextServerClient                 ← async HTTP to context HTTP companion
  │                   ├─ persistStructureAsync()        ← fire-and-forget, non-blocking
  │                   ├─ upsertFileRegistryAsync()      ← fire-and-forget, non-blocking
  │                   └─ resolveSessionId() ─► readActiveSessionId()
  │                                           └─ JDBC ──► active_session table (best_practices.db)
  │
  └─ stdio ──► context-server stdio JVM (context_lifecycle, context_read, context_write)
                 │
                 └─ HTTP companion on :8082             ← separate process, separate JVM
                      └─ JDBC ──► best_practices.db     ← same DB, different connection

mcp-agentic-workflow stdio JVM
  └─ flow nodes use mcp.tool_call ──► HTTP :8081 (FS)   ← NOT the same as FS stdio
                                  └─► HTTP :8082 (ctx)
```

### Key rules for diagnosis

**1. `context_lifecycle` is a context-server tool, not FS.**
When Claude calls `context_lifecycle start`, it goes directly to the context-server stdio JVM.
`McpController` in FS never sees it. Therefore wiring `setActiveSessionId()` in FS `McpController`
on a lifecycle start response does NOT work — the call never arrives there.

**2. Session ID resolution — single point of truth: `active_session` table.**
`FilesystemTelemetryService.readActiveSessionId()` reads:
```sql
SELECT session_id FROM active_session ORDER BY id DESC LIMIT 1
```
This is called lazily on first `recordToolCall()` and cached in `trackedSessionId` for the session.
The `active_session` table has columns `(id, session_id, updated_at)` — no `status` column.
NEVER use HTTP `/current-session` endpoint — it returns the HTTP companion's own session scope,
not the DT stdio user session.

**3. FS stdio vs FS HTTP companion are completely separate JVMs.**
`file_read` from Claude → FS stdio (single-threaded request/response).
`mcp.tool_call serverPort=8081` from AW flow node → FS HTTP companion.
They share `best_practices.db` via JDBC but have separate in-memory state.

**4. Hot path (McpController → response) must have ZERO blocking I/O.**
`McpController.handleToolsCall()` runs on the stdio request thread. Any blocking I/O here
(JDBC open, HTTP call) will hold the thread and cause MCP timeout (-32001). Use only
volatile field reads and async submit on this path. Session ID is read from `trackedSessionId`
volatile field — never resolved on the hot path.

**5. `execute action=cmd` — NEVER use `2>&1` with Gradle.**
Windows synchronous pipe buffer deadlock: process writes to merged pipe, pipe fills, process
blocks waiting for consumer, consumer waits for process exit. Gradle writes significant stderr.
The tool captures stderr separately in the `stderr` response field automatically.
Correct: `gradlew.bat compileGroovy --no-daemon`
Wrong:   `gradlew.bat compileGroovy --no-daemon 2>&1`

**6. Unicode on stdio — UTF-8 enforced from v0.8.40.**
`StdioMcpServer` uses `InputStreamReader(System.in, StandardCharsets.UTF_8)`.
`McpGroovyFileSystemServerApplication.main()` sets `System.setOut(new PrintStream(System.out, true, "UTF-8"))`.
Prior to v0.8.40, Windows JVM default charset (Cp1252) corrupted `→` (U+2192), `—` (U+2014) and
other non-Latin-1 chars in tool parameters, causing `file_write action=replace` to silently fail
with `oldText not found` even when the text was visually identical.

---

## MCP Tools (5 parameterised tools)

| Tool | Description |
|------|-------------|
| `file_read` | Read files/directories: read, head, tail, range, grep, multi_grep, multi, structure, get_method, info, checksum, diff, list (listing_hash + knownHash short-circuit) |
| `file_write` | Write/modify files: write, append, replace, patch, multi_replace, server_transform, chunk_write |
| `file_search` | Search file contents (regex) or filenames across directories |
| `file_lifecycle` | File/directory create, delete, copy, move, rename, touch |
| `server_lifecycle` | Manage HTTP companion server processes: start_eager, ensure, stop, status, reload |
| `execute` | Run scripts: cmd, powershell, python, groovy, bash |

---

## What's New

### v0.8.48 — TDD hardening: error surfacing, multi_replace safety, brace pre-write (2026-04-13)

**Root cause analysis (8 RCAs) and full TDD contract spec delivered.** All tool handler errors now visible to Claude Desktop.

- **RCA-1 / `McpResponse.toolError()`** — `McpResponse.error()` produced a JSON-RPC protocol error object that Claude Desktop silently swallowed for `tools/call`. New `toolError()` factory returns `isError:true` in the content array — the format DT actually renders. All tool handlers (`FileReplaceService`, `FilePatchService`, `FileTransformService`, `McpController.handleToolsCall`) migrated.
- **RCA-2 / `multi_replace` overlap detection** — Added suffix/prefix partial overlap check (entries sharing a boundary line now rejected with actionable fix guidance). Added simulation pass: if applying entry N makes entry M unfindable, whole batch fails, file untouched.
- **RCA-3 / `requires_reread`** — Boundary patches (`startLine==1` or `endLine==last line`) now include `requires_reread:true` in success response. `recentPatches` map updated only after confirmed successful `atomicWrite`.
- **RCA-5 / position-order apply** — `doMultiReplace` now locates all positions first, applies in reverse position order (highest offset first). Prevents earlier replacements shifting offsets for later ones.
- **RCA-6 / `FileTransformService`** — All 9 `McpResponse.error()` call sites replaced with `toolError()`.
- **RCA-7 / brace check pre-write** — `checkBraceBalance` in `doMultiReplace` now runs on simulated result **before** `atomicWrite`. Returns `toolError`, file not modified if unbalanced. Previously fired post-write as a warning on an already-corrupted file.
- **RCA-8 / legacy tests** — `FileReplaceAndPatchSpec`, `FileServicesSmokeSpec`, `McpControllerSmokeSpec` updated to new `isError:true` contract.
- **TDD gate** — `FileContractSpec` (CT-1..CT-13) written first, confirmed failing against 0.8.47, all 13 passing on 0.8.48. Full suite: 54 tests, 0 failures.
- **Deploy fix** — `copyToJarsDir` Gradle task now auto-updates `mcp-http-servers.json` jar reference on every deploy. Eliminates stale HTTP companion jar problem.

### v0.8.47 — TDD fixes: unicode replace, multi_replace normalisation, boundary patch safety (2026-04-12)

`FilePatchService.doPatch`: when `options.replacements` is missing or empty, now
returns `textResponse([error:..., hint:...])` instead of `McpResponse.error(-32602)`.
Claude Desktop was rendering `-32602` protocol errors as the opaque `"Tool execution
failed"` string rather than showing the error message. `textResponse` wraps the error
as a content block that DT displays as readable JSON. FS-T6 closed. All accuracy
fixes from the 0.8.43–0.8.46 sprint are now complete.

### v0.8.45 — FS-T9: brace-balance warning on replace / multi_replace (2026-04-10)

**FS-T9 — `multi_replace` / `replace` brace-balance warning.** After each replacement,
`FileReplaceService.checkBraceBalance()` compares `{`/`}` counts in `newText` vs `oldText`.
If `newText` is internally unbalanced (net open ≠ net close), a `brace_warning` field is added
to the response — surfacing silent method-boundary corruption (e.g. omitted closing braces that
were present in `oldText`) before a compile cycle is needed. Not a hard error; the write always
proceeds. Applies to both compact and full response paths.

_Confirmed present from 0.8.44 baseline (no further changes needed):_
FS-T1 (`insert_before_match` newline error), FS-T4 (`.md` file 600-line limit),
FS-T6 (`patch` empty-options guard), FS-T7 (`get_method` regex fallback flag),
FS-T8 (`chunk_status` action).

### v0.8.44 — FS accuracy fixes: chunk_status + get_method fallback flag (2026-04-10)

**FS-T7 — `get_method` fallback flag.** When the AST parser fails on a file with a compile error,
`FileStructureReader.doGetMethod()` now returns `fallback:true` and a `fallback_note` in the response
so callers know the method boundaries were derived from the regex scanner and may be imprecise.

**FS-T8 — `chunk_status` action.** New `file_write action=chunk_status` lets callers verify which
chunks have been received before calling `finalise_write`. Required options: `sessionId`, `totalChunks`.
Returns `receivedChunks[]`, `missingChunks[]`, `ready:bool`. Prevents corrupt files from missed chunks.

**`@CompileStatic` hardening (practices #311–314).** Three runtime patterns fixed that caused silent
crashes under Groovy `@CompileStatic`:
- `(Integer) ?: 0` Elvis on falsy zero — replaced with explicit null check throughout
- `x in [list]` list-literal `in` operator — replaced with `== a || == b` chains
- `list.sort(null)` null Comparator — replaced with `Collections.sort()` or removed where collection already ordered (e.g. `ConcurrentSkipListMap.keySet()`)

All three trigger `IntRange.subListBorders NPE` at runtime with no compile warning.

### v0.8.43 — FS accuracy fixes: patch order, insert_before_match, boundary warning (2026-04-10)

**FS-T1** — `InsertBeforeMatchTransformer`: explicit error when `options.match` contains newlines (was silent no-op).
**FS-T2** — `FilePatchService`: `boundary_warning` emitted when `endLine` == last line of file (high risk of duplicate closing brace).
**FS-T3** — `FilePatchService`: confirmed server already applies patch replacements bottom-to-top; class doc updated.
**FS-T4** — `FileContentReader`: doc/config files (`.md`/`.txt`/`.yml`) under 100 KB use 600-line limit instead of 200.

### v0.8.40 — UTF-8 stdio fix (2026-04-04)

**Root cause of silent `replace` failures on Windows:** `StdioMcpServer` used `InputStreamReader(System.in)`
without specifying charset. On Windows the JVM default is Cp1252 which cannot represent `→` (U+2192),
`—` (U+2014) or any other non-Latin-1 Unicode. These chars were silently corrupted when arriving as
tool parameters, so `oldText` containing them never matched the file content (read correctly via UTF-8
`Files.readAllBytes`).

**Fix 1 — stdin:** `StdioMcpServer`:
```groovy
new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1024 * 1024)
```

**Fix 2 — stdout:** `McpGroovyFileSystemServerApplication.main()`:
```groovy
System.setOut(new PrintStream(System.out, true, 'UTF-8'))
```

Both fixes together ensure the full stdio round-trip (incoming params and outgoing responses)
is correctly UTF-8 encoded, regardless of JVM platform default.

---

### v0.8.39 — McpController session ID + ContextServerClient package-scope (2026-04-04)

`ContextServerClient.activeSessionId` changed from `private` to package-scoped to allow
`@CompileStatic` cross-class access from `McpController`. Session ID resolution moved entirely
into `FilesystemTelemetryService.recordToolCall()` via the `readActiveSessionId()` JDBC method —
zero I/O on the hot path.

---

### v0.8.38 — `FilesystemTelemetryService.readActiveSessionId()` SQL fix (2026-04-04)

`readActiveSessionId()` query was `WHERE status = 'active'` but `active_session` has no `status`
column (`id, session_id, updated_at` only). Exception was silently caught, returned null, FS still
wrote `unknown` as session ID. Fixed: `SELECT session_id FROM active_session ORDER BY id DESC LIMIT 1`.

---

### v0.8.37 — Single-point-of-control: JDBC session ID resolution (2026-04-04)

**Root cause of 49K `tool_call_telemetry` rows under `session='unknown'`:**
`ContextServerClient.resolveSessionId()` was calling `GET /current-session` on the context HTTP
companion (:8082). That endpoint returns the HTTP companion's own session scope — not the DT stdio
user session. Result: `activeSessionId` was never set, all telemetry used `'unknown'`.

**Fix — `FilesystemTelemetryService.readActiveSessionId()`:** reads `active_session` table directly
via a short-lived read-only JDBC connection. Transport-agnostic — works in both stdio and HTTP modes.
`ContextServerClient.resolveSessionId()` now calls this method first, HTTP `/current-session` as
fallback only.

Also: **`session_working_files` now populates correctly** — prior to this fix, all
`upsertFileRegistryAsync()` calls used `sessionId='unknown'`, so working file hashes were never
tracked per session.

---

### v0.8.36 — `mcp-http-servers.json` v2 runtime format + killHttpCompanions (2026-03-31)

`ServerLifecycleService.writeRuntimeState()` now emits v2 format with `stdioJvmPids` map.
`killHttpCompanions` Gradle task reads this file and kills HTTP companion PIDs only, protecting
stdio JVM children from being killed during `installMcpbLocal`. See FS-RESTART-SEQUENCE.md §3.

---

### v0.8.35 — `FileReplaceService` three-pass Unicode normalisation (2026-03-30)

`doReplace` and `doMultiReplace` now attempt NFC → NFKC → box-drawing char normalisation before
reporting `oldText not found`. Handles em-dash/en-dash variants, smart quotes, and Gradle
decorative comment separators (U+2500–U+257F). See practice #214, #215, #216.

---

### v0.8.34 — MCPB packaging infrastructure (2026-03-30)

Full MCPB Desktop Extension packaging: `generateMcpbManifest`, `packageMcpbThin`,
`installMcpbLocal` Gradle tasks. `extensions-installations.json` updated on every install.
`killHttpCompanions` wired as `doFirst` in `installMcpbLocal`. `copyToJarsDir` copies jar to
`claude-sync/jars/` for HTTP companion mode after DT restart (practice #248).

---

### v0.8.33 — `replace_section headingStyle=text` (2026-03-27)

**Arbitrary anchor matching** — `headingStyle=text` matches any line containing the anchor string.
Enables `replace_section` in Groovy/Java source, comment blocks, non-markdown files.

---

## Transport Modes

### STDIO (primary — Claude Desktop MCPB extension)

All Claude tool calls arrive here. The FS JVM is started by DT as a child process via the
MCPB extension manifest. This is the **only** path for tool calls from Claude.

**Tool prefix (MCPB):** `mcp-groovy-filesystem-server`

### HTTP Companion Mode

When the stdio server starts, servers with `autoHttpCompanion: true` in `mcp-http-servers.json`
are auto-started as HTTP child processes. Used by `mcp-agentic-workflow` flow nodes via
`mcp.tool_call serverPort=8081`.

**Key distinction:** HTTP companion calls arrive at a **separate JVM** with separate in-memory
state. `ContextServerClient`, `FilesystemTelemetryService`, and `trackedSessionId` are all
independent instances. They share `best_practices.db` via JDBC but nothing else.

---

## Deploy Procedure (mandatory — see FS-RESTART-SEQUENCE.md for full detail)

1. **Code edit** — targeted file changes only
2. **Compile check** — `gradlew.bat compileGroovy --no-daemon` (no `2>&1`)
3. **Build** — `gradlew.bat packageMcpbThin installMcpbLocal`
4. **Flow** -- `flow_management start mode=flow templateName=mcp-deploy version=3.6`
   with params: `serverName`, `projectDir`, `newVersion`, `jarPrefix`
   (`jarPrefix=mcp-groovy-filesystem-server` for this server)
5. **Human gate** — close DT, reopen DT
6. **Verify** — new session auto-detects `deploy-state.json`, confirms jar, deletes state file

**`mcp-http-servers.json` is now auto-updated** by `copyToJarsDir` (v0.8.48) — no manual patch needed after deploy.

**NEVER restart DT without step 4 completing.** The flow updates `mcp-http-servers.json`,
`cc-config`, `server_versions`, and writes `deploy-state.json`. Skipping it requires manual
patching of these files every time.

---

## server_transform — file-type rules (v0.8.20+)

| Transform | File types | Key option |
|-----------|-----------|------------|
| `replace_method` | `.groovy`, `.java` only | `options.method`, `options.newBody` |
| `add_method` | `.groovy`, `.java` only | `options.method`, `options.newBody` |
| `add_import` | `.groovy`, `.java` only | `options.import` |
| `replace_section` | `.md`, `.yml`, `.yaml`, `.toml` | `options.heading`, `options.newContent` |
| `insert_before_match` | any file type | `options.match` (substring), `options.content`, `options.occurrence` (1/−1/N) |
| `insert_after_heading` | `.md`, `.yml`, `.yaml`, `.toml` | `options.heading`, `options.content` |
| `append_section` | `.md`, `.yml`, `.yaml`, `.toml` | `options.heading`, `options.content` |
| `replace_between` | **any file type** | `options.startAnchor`, `options.endAnchor`, `options.newContent` |

All transforms require `options.expectedHash`. For arbitrary text swaps: use `file_write action=multi_replace`.

---

## mcp-http-servers.json — Server Config

Located at `C:/Users/willw/claude-sync/mcp-http-servers.json`.

| Field | Description |
|-------|-------------|
| `name` | Server identifier |
| `jar` | Jar filename (in `jarsDir`) — **must be updated on every deploy even for MCPB servers** |
| `port` | HTTP port |
| `startupPolicy` | `eager` \| `lazy` |
| `mcpb` | `true` = deployed as MCPB extension (DT reads from Claude Extensions dir) |
| `dtOwned` | `true` = DT manages stdio lifecycle |
| `autoHttpCompanion` | `true` = start as HTTP child on FS stdio startup |
| `jvmArgs` | Extra JVM args |

The `jar` field drives HTTP companion startup even for `mcpb:true` servers. Wrong version = companion
starts on wrong jar. Always updated by `mcp-deploy:3.6 update-http-servers` node (runs pre-build).

---

## Build

```powershell
# Compile check (no artifact)
cd C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server
gradlew.bat compileGroovy --no-daemon        # NO 2>&1 -- pipe deadlock on Windows

# Full build + install
gradlew.bat packageMcpbThin installMcpbLocal
```

Installs to:
```
%APPDATA%/Claude/Claude Extensions/local.mcpb.will-woodman.mcp-groovy-filesystem-server/
  manifest.json
  server/mcp-groovy-filesystem-server-<version>.jar
```

Also copies jar to `claude-sync/jars/` via `copyToJarsDir` task (for HTTP companion mode).

---

## Version History

| Version | Highlights |
|---------|-----------|
| **0.8.48** | `McpResponse.toolError()` — all tool errors now `isError:true` content (DT-visible). `multi_replace`: suffix/prefix overlap detection + simulation pass (entry-makes-entry-unfindable aborts batch). Brace check runs on simulated result **before** write. `requires_reread:true` on boundary patches. Position-order apply in `doMultiReplace`. `FileTransformService` errors surfaced. `copyToJarsDir` auto-updates `mcp-http-servers.json`. TDD: `FileContractSpec` CT-1..CT-13. 54 tests, 0 failures. |
| **0.8.47** | Fix A'' — per-position unicode replace in `doReplace`/`doMultiReplace` (NFC/NFKC). Fix B — per-entry normalisation tracking. Fix C — sequential boundary patch blocked. Fix D — `lines_shifted`. Fix E — `tail_content`. Fix F — pre-apply brace check (.groovy/.java). Fix G — `removed_lines` snippet. |
| **0.8.45** | `FileReplaceService.checkBraceBalance()` — brace-balance warning on `replace` and `multi_replace`. Emits `brace_warning` when `newText` net brace count differs from `oldText`. Prevents silent method-boundary corruption. |
| **0.8.44** | `chunk_status` action + `get_method` fallback flag (AST failure → regex scanner with `fallback:true`). `@CompileStatic` Elvis/`in`/sort hardening (practices #311–314). |
| **0.8.43** | `insert_before_match` newline error, `patch` boundary_warning, doc-file 600-line read limit (FS-T1/T2/T4). |
| **0.8.42** | `tools` tool: `action=gradle` subcommands (`compileGroovy`, `packageMcpbThin`, `installMcpbLocal`) -- canonical build path from both Claude and AW flows. Replaces `execute action=cmd gradlew.bat`. |
| **0.8.41** | `mcp.usage.db-path` JVM arg wired into MCPB manifest `jvmArgs`. Required for `FilesystemTelemetryService` JDBC writes in DT stdio mode. Missing arg caused silent telemetry loss. |
| **0.8.40** | UTF-8 stdio fix — `InputStreamReader(System.in, UTF_8)` + `System.setOut(UTF-8)`. Fixes silent `replace` failures for `→`/`—` chars on Windows. Practice #268: never use `2>&1` with Gradle. |
| **0.8.39** | `McpController` session ID via `trackedSessionId` volatile field. `ContextServerClient.activeSessionId` package-scoped. |
| **0.8.38** | `readActiveSessionId()` SQL fix — `active_session` has no `status` column. Query: `ORDER BY id DESC LIMIT 1`. |
| **0.8.37** | Single-point-of-control: `FilesystemTelemetryService.readActiveSessionId()` JDBC — replaces broken HTTP `/current-session`. Fixes 49K rows under `session='unknown'`. `session_working_files` now populates. |
| **0.8.36** | `mcp-http-servers-runtime.json` v2 format. `killHttpCompanions` Gradle task. |
| **0.8.35** | `FileReplaceService` three-pass Unicode normalisation (NFC→NFKC→box-drawing). |
| **0.8.34** | MCPB packaging: `generateMcpbManifest`, `packageMcpbThin`, `installMcpbLocal`, `copyToJarsDir`. |
| **0.8.33** | `replace_section headingStyle=text` — arbitrary anchor matching. |
| **0.8.32** | GCU dep uplift 1.1.0 → 1.1.1. |
| **0.8.31** | `startServer()` passes `-Dspring.profiles.active=http` to HTTP companion ProcessBuilder. |
| **0.8.29** | CRITICAL: self-companion spawn fix — stdio no longer crashes on port 8081 conflict. |
| **0.8.28** | `grepPattern` stdout cap fix. |
| **0.8.27** | `execute options.grepPattern` — Java regex on stdout. |
| **0.8.26** | `server_transform` file-type guard fix; `add_import` param corrected. |
| **0.8.21** | `file_read action=list` listing hash + `knownHash`; `multi_grep` action. |
| **0.8.18** | Toon encoding on `file_read action=list`. |
| **0.8.17** | `stopOneServer` post-kill `waitForPortFree` + `killByPort` netstat fallback. |
| **0.8.10** | `autoStartHttpCompanions` — HTTP companion auto-start on stdio startup. |
| **0.8.6** | `StdioMcpServer` 1MB buffer (was 8KB). |
| **0.8.5** | Streamable HTTP transport (`HttpMcpController`). |
