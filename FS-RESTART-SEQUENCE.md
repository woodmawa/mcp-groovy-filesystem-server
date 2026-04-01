# FS-RESTART-SEQUENCE.md
## Deploy Restart Sequence — Living Reference

**Version:** 1.1
**Last updated:** 2026-04-01
**Owner:** mcp-groovy-filesystem-server / ServerLifecycleService
**Status:** Active — update this file whenever deploy behaviour changes

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

The historical problem was that Claude had no reliable way to:
1. Know which phase of a deploy it was in after a DT restart
2. Know whether the correct new jar had actually loaded
3. Pick up the deploy cleanly without repeating or skipping steps

The `deploy-state.json` file (§4) solves this by persisting deploy context across the restart.
The `mcp-deploy:3.4` template (§5) implements the full two-phase sequence.

---

## 2. Runtime PID File — v2 Format

**Location:** `C:/Users/willw/claude-sync/mcp-http-servers-runtime.json`
**Written by:** `ServerLifecycleService.writeRuntimeState()` (FS 0.8.34+)
**Triggered on:** every `start_eager`, `ensure`, companion start/stop event

### v2 Schema

```json
{
  "updatedAt":      "Wed Apr 01 15:39:57 BST 2026",
  "format":         "v2",
  "stdioPid":       11312,
  "stdioJvmPids":   { "context": 11312, "agentic-workflow": 11312, "filesystem": 11312 },
  "managedServers": [
    {
      "name":      "context",
      "pid":       45996,
      "alive":     true,
      "stdioPid":  11312,
      "startedAt": "2026-04-01T13:22:20.091Z",
      "jar":       "mcp-groovy-context-server-0.18.3.jar",
      "port":      8082
    }
  ]
}
```

### Field semantics

| Field | Meaning | Kill? |
|---|---|---|
| `stdioPid` | Top-level stdio JVM PID (legacy v1 compat field) | **NEVER** |
| `stdioJvmPids` | Map of name→stdioPid for all managed entries | **NEVER** |
| `managedServers[].pid` | HTTP companion process PID | SAFE TO KILL |
| `managedServers[].stdioPid` | Per-entry copy of stdio JVM PID | **NEVER** |

### v1 regression risk

Prior to FS 0.8.34, `writeRuntimeState()` only emitted v1 format (no `format` field, no
`stdioJvmPids` map). Fixed in 0.8.34. If the runtime file shows no `format` field after a
DT restart, the wrong FS jar is running — redeploy.

---

## 3. killHttpCompanions — How It Works

Present in: all five server `build.gradle` files (FS, context-server, agentic-workflow,
mcp-llm-orchestrator, mcp-ms-graph). Wired as `doFirst` in `installMcpbLocal` in all five.

### Kill decision logic

```
1. Read mcp-http-servers-runtime.json
2. Build protectedPids set from ALL of:
     state.stdioPid              (v1 top-level)
     state.stdioJvmPids.*        (v2 map values)
     each srv.stdioPid           (per-entry)
3. For each managedServer:
     pid <= 0            → skip
     pid in protectedPids → REFUSE (log ERROR)
     process alive       → destroyForcibly()
     process dead        → log "already dead"
```

### What it kills vs protects

- **Kills:** HTTP companion JVMs started by `autoStartHttpCompanions()` (ports 8081–8085)
- **Protects:** The DT-launched stdio parent JVM and all stdio children
- **Orchestrator / ms-graph:** Lazy — no HTTP companions at normal deploy time.
  Logs "no managed servers" and exits cleanly. Correct.

### Version-bump install without closing DT

With a version bump (e.g. 1.4.37 → 1.4.38):
- `killHttpCompanions` stops HTTP companions (releases their jar handles)
- New jar has a different filename → installs alongside locked old jar
- Old jar delete fails silently (still locked by stdio JVM) → WARN logged, build continues
- DT restart: loads new jar from extension manifest, cleans old jar

Without a version bump: old jar cannot be overwritten while DT is open.
**Always bump the version before deploying.**

---

## 4. deploy-state.json — Cross-Restart Deploy State

**Location:** `C:/Users/willw/claude-sync/deploy-state.json`
**Written by:** mcp-deploy:3.4 `write-deploy-state` node (phase=PRE_BUILD) and
               `update-deploy-state-awaiting` node (phase=AWAITING_RESTART)
**Read by:** Claude session startup check (mandatory per practice #212)
**Cleared by:** Post-restart session — delete after successful Phase 2 verification

### Schema

```json
{
  "schemaVersion":       1,
  "inProgress":          true,
  "phase":               "AWAITING_RESTART",
  "serverName":          "mcp-agentic-workflow",
  "oldVersion":          "1.4.37",
  "newVersion":          "1.4.38",
  "jarPrefix":           "mcp-agentic-workflow",
  "projectDir":          "C:/Users/willw/IdeaProjects/mcp-agentic-workflow",
  "buildComplete":       true,
  "configSyncComplete":  true,
  "killHttpRan":         true,
  "preRestartSessionId": "2026-04-01-14-36",
  "startedAt":           "2026-04-01T15:39:57Z",
  "updatedAt":           "2026-04-01T16:30:00Z",
  "notes":               "Human-readable description of what was done and why.",
  "verifySteps": [
    "server_lifecycle status -- confirm new jar in managedServers",
    "tool_search -- confirm affected tool surfaces correctly",
    "If verified: reload config, delete this file, write completion practice"
  ],
  "reconciliationKey":   "deploy-2026-04-01-14-36-aw-1.4.38",
  "expectedRuntimeJar":  "mcp-agentic-workflow-1.4.38.jar",
  "expectedExtensionDir": "C:/Users/willw/AppData/Roaming/Claude/Claude Extensions/local.mcpb.will-woodman.mcp-agentic-workflow"
}
```

### Phase lifecycle

| Phase | Set by | Meaning | Action on detection |
|---|---|---|---|
| `PRE_BUILD` | `write-deploy-state` node | Build started, not yet complete | Warn — build may have failed mid-way, ask Will |
| `AWAITING_RESTART` | `update-deploy-state-awaiting` node | Phase 1 complete, waiting for DT restart | Run Phase 2 verification sequence |
| `COMPLETE` | Post-restart session (manual) | Deploy finished | Delete file |

### reconciliationKey format

`deploy-{preRestartSessionId}-{serverShortName}-{newVersion}`

Example: `deploy-2026-04-01-14-36-aw-1.4.38`

Used to link the post-restart session back to the session that initiated the deploy,
enabling diagnosis if something went wrong between phases.

### Session startup check (MANDATORY — practice #212)

At every session start, after `context_lifecycle start` + `session_init`:

```
1. Check deploy-state.json exists at C:/Users/willw/claude-sync/deploy-state.json
2. If exists and inProgress=true:
   a. Read phase and reconciliationKey
   b. Log: "Resuming deploy {reconciliationKey} from pre-restart session {preRestartSessionId}"
   c. If phase=AWAITING_RESTART:
        - server_lifecycle status -- verify expectedRuntimeJar in managedServers
        - Check AW log (or relevant server log) confirms new version loaded
        - tool_search for affected tool -- confirm it surfaces correctly
        - If all verified: server_lifecycle reload, write completion practice, delete file
        - If not verified: report to Will with details, do NOT delete file
   d. If phase=PRE_BUILD:
        - Warn Will: "Previous deploy may have failed mid-build"
        - Ask how to proceed before touching anything
3. If file absent: normal session
```

---

## 5. Full Deploy Sequence — mcp-deploy:3.4

### Config files updated during Phase 1

The flow updates these files during Phase 1 (before restart):

| File | Node | What changes |
|---|---|---|
| `build.gradle` (project) | `bump-version` | version string old→new |
| `claude-sync/deploy-state.json` | `write-deploy-state`, `update-deploy-state-awaiting` | phase tracking |
| DT extension dir jar | `build` (via Gradle) | new versioned jar installed |
| `AppData/.../extensions-installations.json` | `build` (via Gradle `installMcpbLocal`) | DT metadata cache |
| `claude-sync/best_practices.db server_versions` | `sync-server-versions` | canonical version record |
| `claude-sync/mcp-http-servers.json` | `update-http-servers` | jar version for HTTP companion mode |
| `claude-sync/claude_code_mcp_config.json` | `update-cc-config` | jar version for CC fallback |
| `~/.claude.json` | `update-claude-json` | jar version if present |

### Phase 1 — Pre-restart node graph

```
bump-version
    └─► write-deploy-state          [phase=PRE_BUILD]
            └─► build               [gradlew packageMcpbThin installMcpbLocal]
                    │                (killHttpCompanions fires as doFirst)
                    │                (new versioned jar installs alongside locked old)
                    └─► sync-server-versions   [MCPB manifests → server_versions DB]
                            ├─► update-http-servers    [mcp-http-servers.json]
                            ├─► update-cc-config       [claude_code_mcp_config.json]
                            └─► update-claude-json     [~/.claude.json]
                                    └─► verify-config-sync   [all files reference newVersion]
                                            └─► update-deploy-state-awaiting  [phase=AWAITING_RESTART]
                                                    └─► restart-reminder
```

**restart-reminder output:**
```
=======================================================
  DEPLOY PHASE 1 COMPLETE
=======================================================
  Server:  mcp-agentic-workflow
  Version: 1.4.37 -> 1.4.38
  State:   AWAITING_RESTART (saved to deploy-state.json)

  ACTION REQUIRED:
    1. Close Claude Desktop
    2. Reopen Claude Desktop
    3. Start new conversation -- auto-detects and completes Phase 2
=======================================================
```

### Phase 2 — Post-restart (auto-detected at session startup)

```
detect deploy-state.json (phase=AWAITING_RESTART)
    └─► server_lifecycle status     [confirm new jar in managedServers]
            └─► tail server log     [confirm version string in initialize response]
                    └─► tool_search [confirm affected tool surfaces]
                            └─► server_lifecycle reload   [re-read mcp-http-servers.json]
                                    └─► write completion practice
                                            └─► delete deploy-state.json
```

### How to invoke Phase 1

```
flow_management action=start mode=flow templateName=mcp-deploy version=3.4
  params={
    serverName:    "mcp-agentic-workflow",
    projectDir:    "C:/Users/willw/IdeaProjects/mcp-agentic-workflow",
    oldVersion:    "1.4.37",
    newVersion:    "1.4.38",
    jarPrefix:     "mcp-agentic-workflow"
  }
```

### Human gate

The **only** step requiring human action is closing and reopening DT between Phase 1 and
Phase 2. Everything else is Claude-driven via the flow and the session startup check.

---

## 6. AW flow_management Tool — Session Startup Rules

### Tool surfacing issue (fixed in AW 1.4.38)

**Problem (pre-1.4.38):** `tool_search` did not surface `mcp-agentic-workflow:flow_management`
even when AW stdio was connected. Root cause: `FlowToolHandler.getToolDefinitions()` set the
`description` field to a Groovy GString. Jackson serialised GString as `{"values":[...]}` not
a plain String. DT received a malformed tool schema and could not surface the tool.

**Fix:** Extracted GString to a local `String toolDesc` variable with explicit `.toString()`
before the map literal. Deployed in AW 1.4.38. See practice #211.

**Guard:** Before any session using `flow_management`, run `tool_search query=flow_management`.
If it returns `mcp-agentic-workflow:flow_management`, proceed. If not, check the AW log:
the `tools/list` response `description` field must be a plain string, not `{"values":[...]}`.

### Correct startup procedure

```
1. server_lifecycle status (verbose=true)
2. If AW state=DOWN: server_lifecycle ensure name=agentic-workflow
3. tool_search query=flow_management
4. If surfaces: use flow_management directly
5. If does not surface despite AW being UP:
     - tail AW log, check description field in tools/list response
     - if {values:[...]}: GString bug has recurred -- check FlowToolHandler, rebuild
6. NEVER skip AW and go direct to sqlite3 on agentic-workflow.db
   UNLESS AW HTTP companion is confirmed DOWN and cannot be started
```

---

## 7. Config File Reference

| File | Purpose | Updated by |
|---|---|---|
| `AppData/Roaming/Claude/Claude Extensions/local.mcpb.will-woodman.{server}/manifest.json` | DT extension manifest — command/args/version | `generateMcpbManifest` Gradle task |
| `AppData/Roaming/Claude/extensions-installations.json` | DT metadata cache — version+hash DT reads at startup | `installMcpbLocal` Gradle task doLast |
| `AppData/Roaming/Claude/claude_desktop_config.json` | DT config — mcpServers block (empty in MCPB era) | Not touched by deploy |
| `claude-sync/mcp-http-servers.json` | HTTP companion config — jar names, ports, startup policy | `update-http-servers` node in mcp-deploy:3.4 |
| `claude-sync/mcp-http-servers-runtime.json` | Live PID state — v2 format, written on every start_eager | `ServerLifecycleService.writeRuntimeState()` |
| `claude-sync/deploy-state.json` | Cross-restart deploy state — created Phase 1, deleted Phase 2 | mcp-deploy:3.4 nodes + post-restart session |
| `claude-sync/best_practices.db server_versions` | Canonical server version record | `sync-server-versions` node |
| `claude-sync/claude_code_mcp_config.json` | CC fallback config | `update-cc-config` node |
| `~/.claude.json` | Claude Code settings | `update-claude-json` node |

---

## 8. Change Log

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-04-01 | Initial — v2 runtime format, killHttpCompanions, deploy-state.json design |
| 1.1 | 2026-04-01 | Full rewrite: mcp-deploy:3.4 validated end-to-end. Added Phase 1 node graph, config file table, AW 1.4.38 GString fix, Phase 2 auto-detect sequence, reconciliationKey format, all config files updated during flow documented. Cross-restart mechanism validated in production (practice #213). |
