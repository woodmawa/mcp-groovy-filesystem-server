# mcp-groovy-filesystem-server — Claude Code Guide

## Project identity

- **Language:** Groovy 5 / Spring Boot 4 / Java 25
- **Purpose:** MCP filesystem server — file read/write/search/list/execute for Windows
- **Transport:** STDIO only (Claude Desktop)
- **Current version:** check `build.gradle` line starting `version =`
- **Deployed jar:** `C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-<version>.jar`

---

## Session start — do this every time

```
1. context_lifecycle action=start
2. context_read scope=project action=context groupId=mcp-servers  (pass last_stable_hash)
3. context_read scope=session action=resume
```

---

## Package layout

```
src/main/groovy/com/softwood/mcp/
  controller/     McpController (dispatch + FIX-C backstop)
  service/
    AbstractFileService          base helpers (validateFilePath, sanitize, isCompact)
    FileReadService              dispatch for all read actions
    FileWriteService             dispatch for all write actions
    FileListService              file_list (children/tree/list/sizes) + dir caching
    FileSearchService            file_search (content/name/project)
    FileLifecycleService         file_lifecycle (create/delete/copy/move)
    ExecuteService               execute (bash/powershell/groovy/cmd)
    PathService                  path normalization + allowed dirs
    ContextServerClient          HTTP client → context server port 8082
    StructureCache               in-memory structure cache (@Service singleton)
    FilesystemTelemetryService   session token meter + telemetry recording
    ToolsService                 tools (git/gradle/npm/mvn/project_scan/stats)
    UsageTracker                 telemetry SQL writes
    read/
      FileContentReader          doRead, doHead, doTail, doRange, doGrep, doMulti
      FileStructureReader        doStructure, doGetMethod
      FileMetaReader             doInfo, doSummary, doExists, doNormalize, ...
      ReadResponseHelper         checkKnownHash, injectSessionTokenMeter
    write/
      FileContentWriter          doWrite, doAppend
      FileReplaceService         doReplace, doMultiReplace
      FilePatchService           doPatch
      FileChunkWriter            doChunkWrite, doFinaliseWrite, doAbortWrite
      WriteUtils                 atomicWrite, makeBackup, computeHash
```

---

## Critical overflow protection fixes (always active)

| Fix | What it does |
|-----|-------------|
| FIX-A | `doRead` refuses files >200 lines — use `structure`/`get_method`/`range` instead. `force=true` overrides. |
| FIX-B | `_session_read_tokens` injected into every read response. Warns at 40K/80K. |
| FIX-C | McpController hard cap: 64K chars max on any single tool response. |
| FIX-D | `knownHash` option on read/structure/get_method: returns `{unchanged:true}` if file unchanged — zero content cost. |

**Always pass `knownHash` on re-reads.** Get the hash from `file_content_hash` in the
previous response and pass it back. This is the single most effective token saving.

---

## Editing rules

- **Never** `file_write action=patch` with line numbers — they drift across edits
- **Always** `file_write action=replace` with unique oldText = complete method body
- **Always** `file_read action=grep` first to confirm oldText appears exactly once
- For multiple changes to one file: `file_write action=multi_replace` in one call
- For files >200 lines: `file_read action=structure` → `get_method`, never `read`

---

## ContextServerClient — fire-and-forget HTTP to port 8082

After every `structure` scan (cache miss), `FileStructureReader` calls
`contextServerClient.persistStructureAsync()` asynchronously. This posts to
`http://localhost:8082/` — the context server's HTTP endpoint.

If the context server is down, the call fails silently at DEBUG level.
Never block file operations waiting for the context server.

The client also caches directory listings in-memory and persists them to the context
server after `file_list` calls.

---

## Build and deploy

```groovy
// Compile check
gradle compileGroovy

// Full build  
gradle bootJar
```

Copy jar + update four configs (see global CLAUDE.md for full list).
Including mcp-http-servers.json — this controls the running jar and is NOT auto-synced.
Restart filesystem server after deploy (stdio process managed by Claude Desktop).

---

## Verification after deploy

```
file_read action=read path=<any 300+ line file>
→ should be REFUSED with guidance

file_read action=structure path=<any groovy file>
→ note file_content_hash in response

file_read action=structure path=<same file> options.knownHash=<hash from above>
→ should return {unchanged:true}

Any read response should contain _session_read_tokens field
```
