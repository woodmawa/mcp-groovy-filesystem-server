# FS-RESTART-SEQUENCE.md
## Deploy Restart Sequence and Architecture Reference — Living Reference

**Version:** 2.6
**Last updated:** 2026-05-22
**Owner:** mcp-groovy-filesystem-server
**Status:** Active — update whenever deploy behaviour or architecture changes

---

## 1. Context and Problem

Claude Desktop (DT) runs each MCPB server as a stdio child JVM process. When a new jar is
deployed, DT must be restarted to reload the extension manifest and pick up the new jar.
This creates a hard boundary in every deploy cycle:

```
Phase 1 (Claude-driven, pre-restart)
    └─► HUMAN GATE: close + reopen DT
            └─► Phase 2 (auto-detected, post-restart)
```

The `deploy-state.json` file (§4) persists deploy context across the restart.
The `mcp-deploy:4.1` template (§5) implements the full two-phase sequence.

---

## 2. FS ↔ Context Server Architecture

Understanding the call paths between FS and context-server is essential for correct diagnosis.

### Process topology

```
Claude Desktop (DT)
  │
  ├─ MCPB Extension ──► FS stdio JVM                  PID: DT child, unique per session
  │    McpController.handleToolsCall()                ← ALL Claude tool calls arrive here
  │    FilesystemTelemetryService                     ← records tool_call_telemetry
  │    ContextServerClient (async HTTP writer)        ← fire-and-forget to :8082
  │
  └─ MCPB Extension ──► context-server stdio JVM      PID: DT child, unique per session
       Handles: context_lifecycle, context_read, context_write
       HTTP companion auto-started on :8082 (autoHttpCompanion=true)

mcp-agentic-workflow (AW)
  │
  ├─ MCPB Extension ──► AW stdio JVM                  ← flow_management from Claude always here
  │
  └─ HTTP companion on :8084                          ← flow nodes use mcp.tool_call to :8081/:8082
       Started by: FS `autoStartHttpCompanions` (@PostConstruct) on DT launch
       **Adopt behaviour (0.9.4+):** if port 8084 is already occupied at eager-start time
       (e.g. DT re-launched with AW process surviving), `startServer` and `doEnsure` call
       `registry.adopt(name, port)` instead of silently skipping. Result: `managedBySession=true`
       in `server_lifecycle status verbose=true`. Pre-0.9.4: always showed `managedBySession=false`.
       **Telemetry outcome accuracy (0.9.5+):** `McpController.extractOutcome` now detects
       tool-level errors (`result.isError==true`) in addition to protocol-level errors
       (`response.error!=null`). Prior to 0.9.5, tool errors were recorded as `outcome='success'`
       in `tool_call_telemetry`. Matching the CS `deriveOutcome` pattern.
       SEPARATE JVM from AW stdio — separate in-memory state

Shared SQLite DB: C:/Users/willw/claude-sync/best_practices.db
  ├─ Written by: context-server (primary owner, creates schema)
  ├─ Written by: FS stdio via FilesystemTelemetryService JDBC
  ├─ Written by: FS async via ContextServerClient HTTP → :8082 → context HTTP companion → JDBC
  └─ Tables relevant to FS: tool_call_telemetry, session_working_files, active_session
```

### Session ID resolution — single point of truth

**The problem (pre-0.8.37):** `ContextServerClient.resolveSessionId()` called `GET /current-session`
on the context HTTP companion (:8082). That endpoint returns the HTTP companion's own session scope —
NOT the DT stdio user session. Result: `activeSessionId` was never set correctly, all FS telemetry
used `session='unknown'`. 49,212 rows accumulated under `'unknown'` before the fix.

**The fix (0.8.37+):** `FilesystemTelemetryService.readActiveSessionId()` reads directly:
```sql
SELECT session_id FROM active_session ORDER BY id DESC LIMIT 1
```
Uses a short-lived read-only JDBC connection. Transport-agnostic — correct in both stdio and HTTP modes.
`active_session` schema: `(id INTEGER PK, session_id TEXT, updated_at TEXT)` — no `status` column.

**Where it fires:** `FilesystemTelemetryService.recordToolCall()` — called on every tool response.
Lazy-resolves on first call, caches in `trackedSessionId` volatile field. Zero I/O on subsequent calls.

### Hot path rule — McpController

`McpController.handleToolsCall()` runs synchronously on the stdio request thread. Any blocking
operation here causes MCP timeout (-32001) and breaks all FS tools. Rules:

- **ZERO blocking I/O** between request receipt and response send
- **ZERO HTTP calls** — HTTP is async fire-and-forget only (via asyncWriter executor)
- **ZERO JDBC opens** — JDBC is only in `recordToolCall()` which runs on asyncWriter thread
- Session ID: read from `trackedSessionId` volatile field (nanoseconds, no I/O)

### Why `context_lifecycle` cannot prime the session ID in McpController

`context_lifecycle` is a **context-server tool**, not an FS tool. When Claude calls
`context_lifecycle start`, the call goes directly to the context-server stdio JVM via DT's
tool routing. The FS `McpController.handleToolsCall()` never sees it. Therefore any attempt
to intercept the lifecycle start response in FS McpController to call `setActiveSessionId()`
is futile — the correct approach is lazy JDBC resolution in `recordToolCall()`.

### AW stdio vs HTTP companion — tool routing

```
flow_management from Claude ──► AW stdio JVM (DT MCPB extension)
                                ALWAYS routes here. HTTP companion state irrelevant.

mcp.tool_call serverPort=8084 ──► AW HTTP companion (separate JVM)
from flow node                   Has separate WorkingMemory, FlowExecutionService, etc.
                                 Shares agentic-workflow.db via JDBC only.

If flow_management start fails with -32602:
  → Check AW stdio log: mcp-server-mcp-agentic-workflow.log
  → Look for: "bad argument: FlowBuilder: missing required params for flow 'X': [param]"
  --> NOT a transport issue -- it's a missing param (e.g. jarPrefix for mcp-deploy:4.1)
```

---

## 3. Runtime PID File — v2 Format

**Location:** `C:/Users/willw/claude-sync/mcp-http-servers-runtime.json`
**Written by:** `ServerLifecycleService.writeRuntimeState()` (FS 0.8.34+)
**Triggered on:** every `start_eager`, `ensure`, companion start/stop event

### v2 Schema

```json
{
  "updatedAt":      "Wed Apr 04 12:59:10 BST 2026",
  "format":         "v2",
  "stdioJvmPids":   { "context": 12345, "agentic-workflow": 12345, "filesystem": 12345 },
  "managedServers": [
    {
      "name":      "context",
      "pid":       45996,
      "alive":     true,
      "stdioPid":  12345,
      "startedAt": "2026-04-04T12:00:00.000Z",
      "jar":       "mcp-groovy-context-server-0.18.15.jar",
      "port":      8082
    }
  ]
}
```

### Field semantics

| Field | Meaning | Kill? |
|-------|---------|-------|
| `stdioJvmPids` | Map of server name → DT stdio child JVM PIDs | **NEVER** |
| `managedServers[].pid` | HTTP companion process PID | Yes (killHttpCompanions) |
| `managedServers[].stdioPid` | The DT stdio JVM that owns this companion | **NEVER** |

### v1 regression risk

If `writeRuntimeState()` emits v1 format (missing `format='v2'` and `stdioJvmPids`), the
`killHttpCompanions` task falls back to the top-level `stdioPid` field for protection. If that
field is also missing, there is NO protection and the task may kill stdio JVMs. Ensure FS is
0.8.34+ before relying on killHttpCompanions.

---

## 4. killHttpCompanions — How It Works

**Gradle task** wired as `doFirst` in `installMcpbLocal` for: filesystem, context-server,
agentic-workflow build.gradle files. Also present in orchestrator and ms-graph (as no-op).

### Kill decision logic

```
Read mcp-http-servers-runtime.json
│
├─ v2 format:
│    stdioJvmPids = { name: pid }     ← PROTECT these always
│    managedServers[].pid             ← KILL these (HTTP companions only)
│    managedServers[].stdioPid        ← PROTECT (cross-check)
│
└─ v1 format (legacy):
     stdioPid                         ← PROTECT
     managedServers[].pid             ← KILL if pid != stdioPid
```

### Version-bump install without closing DT

With a version bump (e.g. 0.8.39 → 0.8.40), `installMcpbLocal` runs while DT is open:
- Old jar delete fails silently (DT holds file lock) — warning logged but build continues
- New versioned jar copies successfully alongside old jar
- DT loads the new jar on next restart, old jar cleaned then
- **This is the standard deploy path** — no need to close DT before building

---

## 5. deploy-state.json — Cross-Restart State

**Location:** `C:/Users/willw/claude-sync/deploy-state.json`
**Written by:** `write-deploy-state` node in mcp-deploy:3.5
**Deleted by:** post-restart session after verification

### Schema

```json
{
  "inProgress": true,
  "phase": "AWAITING_RESTART",
  "preRestartSessionId": "2026-04-04-13-41",
  "reconciliationKey": "deploy-2026-04-04-13-41-context-server-0.18.15",
  "serverShortName": "context-server",
  "jarPrefix": "mcp-groovy-context-server",
  "oldVersion": "0.18.14",
  "newVersion": "0.18.15",
  "jarName": "mcp-groovy-context-server-0.18.15.jar",
  "dbUpdateSql": "UPDATE server_versions SET version='0.18.15', updated_at=datetime('now') WHERE server_name='context-server'",
  "configFilesUpdated": ["mcp-http-servers.json updated pre-build to 0.18.15"],
  "verifySteps": ["..."],
  "writtenAt": "2026-04-04T13:34:34Z"
}
```

### Phase lifecycle

| Phase | Meaning | Action |
|-------|---------|--------|
| `PRE_BUILD` | Flow started but build not yet run | Warn — previous build may have failed mid-way |
| `AWAITING_RESTART` | Build complete, waiting for DT restart | Run verify steps, update DB, delete file |
| `POST_RESTART_SYNC` | (legacy 3.4) DB update needed | Run `dbUpdateSql`, delete file |

### Startup detection (mandatory every session)

After `context_lifecycle start` and `session_init`, ALWAYS check:
```
file_read action=exists path=C:/Users/willw/claude-sync/deploy-state.json
```
If exists and `phase=AWAITING_RESTART`:
1. `server_lifecycle status verbose=true` — confirm new jar in `jar` field
2. `sqlite3 best_practices.db "UPDATE server_versions SET version='X'..."`
3. `file_lifecycle action=delete path=deploy-state.json`

---

## 6. mcp-deploy:4.1 — Canonical Deploy Flow

**Template:** `mcp-deploy:4.1` in `agentic-workflow.db`
**Ontology:** `context_read scope=ontology action=search query=mcp-deploy symbolType=flow-template`

### Required params (NO defaults — omitting any causes -32602 error)

| Param | Example |
|-------|---------|
| `serverName` | `filesystem` |
| `projectDir` | `C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server` |
| `newVersion` | `0.8.40` |
| `jarPrefix` | `mcp-groovy-filesystem-server` |

Optional: `claudeSyncDir` (default `C:/Users/willw/claude-sync`), `runRegression` (default `false`).

### jarPrefix values

| Server | jarPrefix |
|--------|-----------|
| filesystem | `mcp-groovy-filesystem-server` |
| context-server | `mcp-groovy-context-server` |
| agentic-workflow | `mcp-agentic-workflow` |
| orchestrator | `mcp-llm-orchestrator` |

### Node graph

```
derive-old-version
    ├─► update-http-servers    [mcp-http-servers.json — PRE-BUILD]
    └─► update-cc-config       [claude_code_mcp_config.json — PRE-BUILD]
            └─► bump-version   [build.gradle version string]
                    └─► notify-build  [tells Claude to run gradlew manually]
                            └─► build-verify  [confirms new jar exists in build/libs]
                                    └─► sync-server-versions  [MCPB manifests → server_versions DB]
                                            └─► write-deploy-state  [deploy-state.json AWAITING_RESTART]
                                                    └─► update-docs
                                                            └─► restart-reminder
                                                                    └─► regression-gate
```

**Note:** `notify-build` tells Claude to run the build manually before continuing.
In DT/MCPB stdio mode the flow cannot run the build (port 8081 not bound in stdio mode).
Claude must run: `gradlew.bat packageMcpbThin installMcpbLocal` via `execute action=cmd`
**before** starting the flow. The flow's `build-verify` node confirms the jar exists.

### Canonical invocation

```
flow_management action=start mode=flow templateName=mcp-deploy version=3.5
  params={
    serverName: "filesystem",
    projectDir: "C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server",
    newVersion:  "0.8.40",
    jarPrefix:   "mcp-groovy-filesystem-server"
  }
```

### Full deploy procedure (no exceptions)

```
1. Edit code
2. gradlew.bat compileGroovy --no-daemon        ← compile check, NO 2>&1
3. gradlew.bat packageMcpbThin installMcpbLocal ← build + install + auto-updates mcp-http-servers.json
4. flow_management start mcp-deploy:4.1         ← config sync, deploy-state, docs
5. HUMAN GATE: close DT, reopen DT
6. New session: detect deploy-state.json → verify → update DB → delete file
```

**`mcp-http-servers.json` is now auto-updated by `copyToJarsDir`** (step 3) — no manual patching needed.
**NEVER restart DT without step 4 completing.** The flow updates `cc-config`, `server_versions`, and writes `deploy-state.json`.

---

## 7. AW flow_management Startup Rules

### Tool routing

`flow_management` from Claude **always** routes to the AW stdio JVM (DT MCPB extension).
The AW HTTP companion on :8084 is irrelevant to Claude's tool calls.
When `flow_management start` fails with `Tool execution failed`:
- Check the AW stdio log: `%APPDATA%/Roaming/Claude/logs/mcp-server-mcp-agentic-workflow.log`
- Look for: `WARN FlowToolHandler: bad argument: FlowBuilder: missing required params for flow 'X': [param]`
- This is NOT a transport issue — it is a missing param validation failure

### Startup procedure

```
1. session_init (or explicit context_lifecycle start + session_init)
2. flow_management action=list_templates toon=true   ← confirms AW stdio is alive
3. context_read scope=ontology action=search query=<templateName> symbolType=flow-template
   ← get exact params before calling start
4. flow_management action=start mode=flow ...
```

---

## 8. Config File Reference

| File | Purpose | Updated by |
|------|---------|-----------|
| `AppData/.../Claude Extensions/.../manifest.json` | DT extension manifest | `generateMcpbManifest` |
| `AppData/.../extensions-installations.json` | DT metadata cache — version+hash | `installMcpbLocal` doLast |
| `AppData/.../claude_desktop_config.json` | DT config — mcpServers (empty in MCPB era) | Not touched |
| `claude-sync/mcp-http-servers.json` | HTTP companion config — jar names, ports | **`copyToJarsDir` Gradle task (v0.8.48, auto)** — was `update-http-servers` flow node |
| `claude-sync/mcp-http-servers-runtime.json` | Live PID state — v2 format | `ServerLifecycleService.writeRuntimeState()` |
| `claude-sync/deploy-state.json` | Cross-restart deploy state | mcp-deploy:4.1 + post-restart session |
| `claude-sync/best_practices.db server_versions` | Canonical version record | `sync-server-versions` node |
| `claude-sync/claude_code_mcp_config.json` | CC fallback config | `update-cc-config` node (PRE-BUILD) |
| `~/.claude.json` | Claude Code settings | `update-claude-json` node |

---

## 9. Compile Check and Build — Windows Rules

```powershell
# CORRECT — compile check
gradlew.bat compileGroovy --no-daemon

# WRONG — pipe deadlock on Windows
gradlew.bat compileGroovy --no-daemon 2>&1

# CORRECT — full build (daemon OK for build, not needed for compile-only)
gradlew.bat packageMcpbThin installMcpbLocal
```

**Why `2>&1` deadlocks:** Windows synchronous pipe buffer (~64KB). Gradle writes significant
stderr (daemon startup messages, deprecation warnings). With `2>&1`, the merged pipe fills,
Gradle blocks waiting for the consumer to drain it, but the `execute` tool is waiting for
process exit. Deadlock. The `execute` tool captures stderr separately in the `stderr` response
field — `2>&1` is never needed.

---

## 9b. Updating Tool Descriptions Without a Rebuild (v0.8.74+)

`file_read` (v0.8.70) and `file_write` (v0.8.74) load their tool descriptions from CS
`help_sections` at FS startup. This means you can correct or improve the descriptions
served to Claude without touching source code or rebuilding the jar.

**Section keys:**

| Tool | Compact key | Verbose key |
|------|-------------|-------------|
| `file_read` | `tool_desc_file_read` | (not split — single section) |
| `file_write` | `tool_desc_file_write` | `tool_desc_file_write_verbose` |

**To update (no rebuild, no jar deploy):**

```
# Update the compact file_write description
context_write scope=help type=section action=update
    section_key=tool_desc_file_write
    content=<new description text>

# Or via execute_sql for longer content
context_lifecycle action=execute_sql allowWrite=true
    sql="UPDATE help_sections SET content='...',updated_at=datetime('now')
         WHERE section_key='tool_desc_file_write'"
```

**Then restart DT** — FS reads the sections in `@PostConstruct init()` at startup, so a DT
restart is required to pick up changes. No build, no jar, no deploy.

Fallback: if CS is unreachable at startup, FS uses the `DEFAULT_DESC_*` static constants
baked into the source. These are kept in sync with the help_sections rows.

---

## 10. Change Log

| Version | Date | Change |
|---------|------|--------|
| 2.6 | 2026-05-22 | 0.9.5 BUILD-16B (partial): `McpController.extractOutcome` now detects tool-level `isError=true`; `outcome='error'` recorded correctly for tool errors (was `outcome='success'`). `TelemetryOutcomeSpec` CT-16B-1..5 green. CS-side `outcome='unchanged'` cache-hit restore is the remaining 16B item (CS-only change). |
| 2.5 | 2026-05-20 | 0.9.4 adopt fix documented: `startServer`+`doEnsure` adopt untracked eager processes when port is occupied. AW (`managedBySession=false`) root cause traced to `pingMcp` returning null for REST endpoints; fix adds adopt-on-detect guard. §2 updated. |
| 2.4 | 2026-04-30 | Race condition fix documented: `@PostConstruct init()` retry-with-backoff (v0.8.75). Both `FileReadService` and `FileWriteService` retry 3x before falling back to `DEFAULT_DESC`. FS-RESTART-SEQUENCE §9b updated: DB-driven descriptions now reliable at DT start. |
| 2.3 | 2026-04-30 | DB-driven tool descriptions: `file_write` now loads description from CS `help_sections` at startup (v0.8.74, idea #109). Both `file_read` (v0.8.70) and `file_write` (v0.8.74) DB-driven. Update without rebuild: `context_write scope=help type=section action=update section_key=tool_desc_file_write content=<new>` then restart DT. Tool description live-edit procedure added to §9. |
| 2.2 | 2026-04-30 | mcp-deploy ref updated to 4.1; `expectedHash` mandatory rule added to §9 compile/build section; FS versions 0.8.66–0.8.73 documented; CT-EH-1 `expectedHash` mandatory enforcement noted |
| 2.1 | 2026-04-13 | mcp-deploy ref updated to 3.7; `mcp-http-servers.json` now auto-updated by `copyToJarsDir` Gradle task (v0.8.48) — removed from manual steps; Config File Reference table updated; `McpResponse.toolError()` and error contract changes documented |
| 2.0 | 2026-04-04 | Full rewrite: FS↔Context architecture section, session ID resolution design, AW transport routing, mcp-deploy:3.5 with jarPrefix, Windows pipe deadlock rule, UTF-8 stdio fix, all changes from v0.8.34–0.8.40 documented |
| 1.1 | 2026-04-01 | mcp-deploy:3.4 validated end-to-end. Phase 1 node graph, config file table, AW 1.4.38 GString fix, Phase 2 auto-detect, cross-restart mechanism validated in production |
| 1.0 | 2026-04-01 | Initial — v2 runtime format, killHttpCompanions, deploy-state.json design |
