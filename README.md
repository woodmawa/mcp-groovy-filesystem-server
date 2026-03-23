# mcp-groovy-filesystem-server v0.8.12

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
# Output: build/libs/mcp-groovy-filesystem-server-0.8.10.jar

copy build\libs\mcp-groovy-filesystem-server-0.8.10.jar C:\Users\willw\claude-sync\jars\
copy build\libs\mcp-groovy-filesystem-server-0.8.10.jar C:\Users\willw\claude-sync\
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
