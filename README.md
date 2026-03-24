# mcp-groovy-filesystem-server v0.8.22

Spring Boot / Groovy MCP server providing filesystem, developer toolchain, and server lifecycle operations
to Claude Desktop and Claude Code via STDIO (primary) and Streamable HTTP (HTTP companion mode).

📖 **Full documentation**: `docs/asciidoc/` (if present) · `USAGE.md`

---

## MCP Tools (4 parameterised tools)

| Tool | Description |
|------|-------------|
| `file_read` | Read files/directories: read, head, tail, range, grep, multi, structure, get_method, info, checksum, diff |
| `file_write` | Write/modify files: write, append, replace, patch, multi_replace, server_transform, chunk_write |
| `file_search` | Search file contents (regex) or filenames across directories |
| `server_lifecycle` | Manage HTTP companion server processes: start_eager, ensure, stop, status, reload |

---

## What's New

### v0.8.22 — `multi_grep` path-guard fix (2026-03-24)

**`multi_grep` no longer requires a `path` param** — added `multi_grep` to the path-exempt action list alongside `multi`, so it can be called with only `options.paths[]` and `options.pattern` as intended.

---

### v0.8.21 — `action=list` listing hash + `multi_grep` (2026-03-24)

**`file_read action=list` now returns `listing_hash`** — a 12-char SHA-256 of the directory contents (sorted name+type+mtime). Pass as `options.knownHash` on repeat calls; if the directory is unchanged, returns `{unchanged:true, listing_hash, count}` (~15 tokens) instead of the full entry payload. Saves 300–1,500 tokens per repeated directory read in typical sessions.

**`file_read action=multi_grep`** — new action: grep one regex pattern across a list of files in a single call. `options.paths[]` (up to 20 files), `options.pattern` (required), `options.maxMatches` (default 5 per file), `options.contextLines`. Returns only files with matches — collapses the common “scan these N files for this import/class/pattern” into one tool call.

```
file_read action=multi_grep options={pattern:"import org.softwood", paths:["File1.groovy","File2.groovy"]}
```

**`file_list action=list` wired to listing cache** — the `FileListService.doList` path (used by `file_list` tool) now checks and populates the in-process directory cache the same way `file_list action=children` already did.

---

### v0.8.20 — `server_transform` relaxed file-type guard (2026-03-24)

**`replace_between` now works on any file type** — previously `server_transform` blocked all non-Groovy/Java files. The guard is now relaxed: `replace_between` is permitted on any file type (YAML, TOML, JSON, Markdown, etc.), while the structural transforms (`replace_method`, `add_method`, `add_import`) remain Groovy/Java-only. Section transforms (`replace_section`, `insert_after_heading`, `append_section`) now also accept `.yml`, `.yaml`, and `.toml` files in addition to Markdown.

---

### v0.8.19 — (intermediate, superseded by 0.8.20)

---

### v0.8.18 — Toon encoding on `file_read action=list` (2026-03-23)

**`toon=true` option on `file_read action=list`** — pass `options.toon=true` to receive directory
entries in compact Toon columnar notation instead of JSON. Reduces listing token cost by ~38% on
typical directories (45-entry test: 731 → 455 tokens). Uses the shared `mcp-toon-service` library
(`com.woodmawa.mcp.toon:mcp-toon-service:1.0-SNAPSHOT`).

Format: `§files` section header, `@cols name,type,size,lastModified` column row, then one
`¬`-separated data row per entry. `toon_encoded:true` flag present in response when encoding fired.

```
file_read action=list path=C:/Users/willw/IdeaProjects options={toon:true}
```

---

### v0.8.17 — `stop`/`ensure` race fix + `mcp-deploy:1.5` (2026-03-23)

**`stopOneServer` post-kill port verification** (CODE-DEFECT-007) — after any kill path (managed-process,
runtime-PID, or actuator), `stopOneServer` now calls `waitForPortFree(5s)` and applies a Windows
`killByPort()` netstat fallback if the port remains occupied. Eliminates the scenario where a killed
process lingers in TIME_WAIT and blocks the subsequent `ensure`.

**`killStalePidIfPresent` logic corrected** — previously only killed when port was NOT listening
(inverted). Now kills when port IS listening and PID is alive (correct orphan-eviction semantics).
Adds `killByPort()` netstat fallback when runtime PID is unknown.

**New helpers** — `killByPort(int port)` (netstat PID extraction + `ProcessHandle.destroyForcibly()`)
and `waitForPortFree(int port, int timeoutSeconds)` (polls until port free).

**`mcp-deploy:1.5` flow template** — adds `wait-for-stop` node (polls port free up to 8s)
between `stop-server` and `ensure-server`, eliminating the stop/ensure race in the deploy flow.

---

### v0.8.15–0.8.16 — pptx `write_office` / `read_office` fix (2026-03-23)

**`OfficeDocumentHandler` pptx** (CODE-DEFECT was `XSLFAutoShape MissingPropertyException`) —
`slide.shapes` / `slide.placeholders` can return `XSLFAutoShape` objects mixed with `XSLFTextShape`.
`XSLFAutoShape` has no `placeholderDetails` property; Groovy generic type annotations do not enforce
this at runtime. Fix: `.findAll { it instanceof XSLFTextShape }` filter applied before any placeholder
lookup. Uses `Placeholder` enum (`TITLE`, `CENTERED_TITLE`, `BODY`) from
`org.apache.poi.sl.usermodel.Placeholder` for type-safe classification. Applied to both
`writePptx` and `readPptx` (notes placeholder). Smoke-tested: 3-slide write + read round-trip passing.

---

### v0.8.13–0.8.14 — Port-conflict race fix + log noise (2026-03-23)

**`ServerLifecycleService` port-conflict race** (CODE-DEFECT-005) — `killStalePidIfPresent` now
guards the kill on `!isPortListening(port)`. A stale PID that is alive but whose port is
already listening (i.e., a legitimate companion) is left untouched. `isPortListening` gains
a retry loop (3 attempts, 300ms delay) to survive transient socket timing gaps.

Previously: second stdio instance killed the live HTTP companion, then raced to restart it,
hitting "Port 8081 already in use" (TIME_WAIT). Now: companion is only killed if it is alive
but its port is not responding.

**`HttpMcpController`** session-ID poll noise already at DEBUG (confirmed pre-existing).

---

### v0.8.12 — OfficeDocumentHandler DSL bridge (2026-03-23)

**GCU adapter bridge** — `OfficeDocumentHandler` now delegates to `XlsxAdapter`, `DocxAdapter`,
and `PptxAdapter` from GroovyConcurrentUtils when the caller passes a `workbookPlan`,
`documentPlan`, or `presentationPlan` option. Legacy flat-map paths (`headers`/`rows`,
`content`, `slides`) are fully preserved for backward compatibility.

| New option | Method | Adapter action |
|-----------|--------|----------------|
| `options.queryPlan` | `read_office` xlsx | `QUERY` |
| `options.workbookPlan` | `write_office` xlsx | `GENERATE` (or `options.action`) |
| `options.documentPlan` | `write_office` docx | `GENERATE` |
| `options.presentationPlan` | `write_office` pptx | `GENERATE` |

---

### v0.8.11 — OfficeDocumentHandler (2026-03-22)

Initial `read_office` / `write_office` tool actions for `.xlsx`, `.docx`, `.pptx` via Apache POI 5.3.0.
Flat-map API: `headers`/`rows` for xlsx, `content` map for docx, `slides` list for pptx.

---

### v0.8.10 — Auto HTTP companion startup (2026-03-22)

**`@PostConstruct autoStartHttpCompanions`** — when the filesystem server starts in stdio mode,
servers with `"autoHttpCompanion": true` in `mcp-http-servers.json` are automatically started as
HTTP child processes. This gives `mcp-agentic-workflow` flow nodes access to filesystem (:8081)
and context (:8082) via `mcp.tool_call` without needing `start-mcp-services.ps1`.

The companion processes are tracked in `managedProcesses` and killed cleanly by the existing
`@PreDestroy stopAllOnShutdown()` when DT or CC exits. **No manual lifecycle management needed.**

To enable: add `"autoHttpCompanion": true` to a server entry in `mcp-http-servers.json`.

### v0.8.9 — Code-defect fixes (2026-03-22)

- **`ContextServerClient` liveness guard** (`CODE-DEFECT-004`): added `volatile boolean contextServerReachable`
  flag. On first `ConnectException` (e.g. in stdio-only sessions where port 8082 is closed), logs once at INFO
  then goes completely silent. Early-exit guard prevents thread-pool load for subsequent calls.
- **`server_transform` file-type validation** (`SKILL-UPDATE-004`): `FileTransformService.applyTransform`
  now checks file extension before dispatching. Non-.groovy/.java files receive a clear descriptive error
  pointing to `multi_replace` instead. Markdown files are permitted for `append_section`,
  `insert_after_heading`, `replace_section` only.

### v0.8.8 — mkdirs cast fix (2026-03-16)

Fixed `@CompileStatic` boolean cast issue in `WriteUtils` — `mkdirs` option was silently ignored.

### v0.8.7 — atomicWrite race fix (2026-03-16)

Fixed race condition in `WriteUtils.atomicWrite`: redundant `!Files.exists()` guard raced with
`createDirectories()`, causing intermittent failures writing to new subdirectories.

### v0.8.6 — StdioMcpServer 1MB buffer (2026-03-16)

Fixed `BufferedReader` hard limit of 8KB per line — large MCP messages (file_write with large content)
were silently truncated. Buffer increased to 1MB.

### v0.8.5 — Streamable HTTP transport (2026-03-12)

`HttpMcpController`: `POST /mcp` (primary) + `GET /mcp` (SSE, spec compliance). `Mcp-Session-Id`
session management, origin guard (403 for non-localhost), 30-second SSE heartbeat.

---

## Transport Modes

### STDIO (primary — Claude Desktop / Claude Code)

The filesystem server runs as a stdio subprocess. All Claude tool calls go through this channel.

**Claude Desktop config** (`AppData/Roaming/Claude/claude_desktop_config.json`):
```json
{
  "groovy-filesystem": {
    "command": "java",
    "args": [
      "--enable-native-access=ALL-UNNAMED",
      "-XX:+IgnoreUnrecognizedVMOptions",
      "-Dspring.profiles.active=stdio",
      "-Dmcp.filesystem.allowed-directories=C:/Users/willw/IdeaProjects, ...",
      "-Dmcp.script.enable-python=true",
      "-jar", "C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-0.8.10.jar"
    ]
  }
}
```

### HTTP Companion Mode (auto-started for agentic-workflow)

When the stdio server starts, it auto-starts HTTP companion processes for servers configured with
`"autoHttpCompanion": true` in `mcp-http-servers.json`. These run on their configured ports (:8081,
:8082 etc.) and are killed when the stdio server exits.

This allows `mcp-agentic-workflow` flow nodes to call:
```json
{ "taskType": "mcp.tool_call", "params": { "serverPort": 8081, "toolName": "file_read", "args": {...} } }
```

---

## server_transform — .groovy/.java source only

`file_write action=server_transform` operates on Groovy and Java source files **only**. Available transforms:

| Transform | Description |
|-----------|-------------|
| `replace_method` | Replace a named method body |
| `replace_section` | Replace a `##` markdown section |
| `replace_between` | Replace content between two exact marker strings |
| `insert_after_heading` | Insert content after a markdown heading |
| `append_section` | Append a new `##` section |
| `add_method` | Add a new method to a class |
| `add_import` | Add an import statement |

For YAML, JSON, Python, or any other file type: use `file_write action=multi_replace`.

---

## mcp-http-servers.json — Server Config

Located at `C:/Users/willw/claude-sync/mcp-http-servers.json`.

| Field | Description |
|-------|-------------|
| `name` | Server identifier (`filesystem`, `context`, `orchestrator`, `agentic-workflow`) |
| `jar` | Jar filename (looked up in `jarsDir`) |
| `port` | HTTP port |
| `startupPolicy` | `eager` (start via `start_eager`) \| `lazy` (start via `ensure`) |
| `dtOwned` | `true` = DT manages this server's stdio lifecycle |
| `autoHttpCompanion` | `true` = start as HTTP child process on filesystem stdio startup |
| `jvmArgs` | Extra JVM args (e.g. allowed-directories) |
| `env` | Environment variables for the child process |

---

## Build & Deploy

```powershell
cd C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server
./gradlew.bat bootJar
# Output: build/libs/mcp-groovy-filesystem-server-0.8.22.jar

# Use the mcp-deploy:1.6 flow template instead (handles all 5 config updates + restart)
```

Update all five configs on every version bump (five-config rule):
- `build.gradle` version string
- `claude-sync/mcp-http-servers.json`
- `AppData/Roaming/Claude/claude_desktop_config.json`
- `claude-sync/claude_code_mcp_config.json`
- `claude-sync/regression-test.py` (DEPLOY-05/07 read from mcp-http-servers.json automatically — no manual update needed)

---

## Version History

| Version | Highlights |
|---------|-----------|
| **0.8.22** | `multi_grep` path-guard fix — no `path` param required |
| **0.8.21** | `file_read action=list` listing hash + `knownHash` short-circuit; `multi_grep` action; `file_list` cache aligned |
| **0.8.20** | `server_transform` file-type guard relaxed — `replace_between` on any file; section transforms accept `.yml`/`.yaml`/`.toml` |
| **0.8.19** | (intermediate) |
| **0.8.18** | Toon encoding on `file_read action=list` (`options.toon=true`) — ~38% token saving on directory listings |
| **0.8.17** | `stopOneServer` post-kill `waitForPortFree` + `killByPort` netstat fallback; `killStalePidIfPresent` inverted-logic fix; `mcp-deploy:1.5` |
| **0.8.16** | `ServerLifecycleService` `killByPort`/`waitForPortFree` helpers added |
| **0.8.15** | `OfficeDocumentHandler` pptx fix — `instanceof XSLFTextShape` filter + `Placeholder` enum; all office smoke tests passing |
| **0.8.14** | pptx intermediate fix (partial — superseded by 0.8.15) |
| **0.8.13** | `ServerLifecycleService` port-conflict race fix — `killStalePidIfPresent` guards kill on `isPortListening` + retry loop |
| **0.8.12** | `OfficeDocumentHandler` DSL bridge — `XlsxAdapter`/`DocxAdapter`/`PptxAdapter` via GCU; legacy paths preserved |
| **0.8.11** | `OfficeDocumentHandler` — `read_office`/`write_office` for `.xlsx`/`.docx`/`.pptx` via Apache POI 5.3.0 |
| **0.8.10** | `@PostConstruct autoStartHttpCompanions` — HTTP companion auto-start on stdio startup; clean shutdown via existing `@PreDestroy` |
| **0.8.9** | `ContextServerClient` liveness guard (CODE-DEFECT-004); `server_transform` file-type validation (SKILL-UPDATE-004) |
| **0.8.8** | `mkdirs` boolean cast fix under `@CompileStatic` |
| **0.8.7** | `atomicWrite` race condition fix — redundant `!Files.exists()` guard removed |
| **0.8.6** | `StdioMcpServer` 1MB buffer (was 8KB, silently truncated large file_write requests) |
| **0.8.5** | Streamable HTTP transport — `HttpMcpController` (`POST /mcp` + `GET /mcp` SSE) |
| **0.8.4** | `file_read action=structure` compact mode; `get_method` targeted read |
| **0.8.2** | `server_transform` capability added |
| **0.7.x** | Context server client integration, SQLite usage tracking, path normalisation |
