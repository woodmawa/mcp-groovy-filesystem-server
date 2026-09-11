# mcp-groovy-filesystem-server — Claude Code Guide

## Project identity

- **Language:** Groovy 5 / Spring Boot 4 / Java 25
- **Purpose:** MCP filesystem server — file read/write/search/list/execute for Windows
- **Transport:** STDIO (primary, Claude Desktop) + Streamable HTTP companion (:8081)
- **Current version:** `0.9.27` (check `build.gradle` to confirm)
- **Baseline stack:** FS 0.9.27 / CS 1.0.58 / AW 1.30.17 — 2026-09-11
- **Deployed jar:** `C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-<version>.jar`

---

## Session start — do this every time

```
0. CLAIM THIS FS PROCESS (FS 0.9.17). Right after session-bootstrap, from your OWN connection:
     server_lifecycle action=claim_session sessionId=<id> groupId=mcp-servers
   Without it this process is UNBOUND and FS telemetry and range-cache keys resolve to nothing.
   server_lifecycle action=claim_status reports what this process is serving.
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
    FileReadService              dispatch for all file_read actions
    FileWriteService             dispatch for all file_write actions
    FileListService              file_list (children/tree/list/sizes) + dir caching
    FileSearchService            file_search (content/name/project)
    FileLifecycleService         file_lifecycle (create/delete/copy/move)
    ExecuteService               execute (bash/powershell/groovy/cmd/python)
    PathService                  path normalization + allowed dirs
    ContextServerClient          HTTP client → context server port 8082
    StructureCache               in-memory structure cache (@Service singleton)
    FilesystemTelemetryService   session token meter + telemetry recording
    ToolsService                 tools (git/gradle/npm/mvn/project_scan/stats)
    UsageTracker                 telemetry SQL writes
    ServerLifecycleService       HTTP companion process management
    read/
      FileContentReader          doRead, doHead, doTail, doRange, doGrep, doMultiGrep, doMulti
      FileStructureReader        doStructure, doGetMethod
      FileMetaReader             doInfo, doSummary, doExists, doNormalize, doList (+ listing_hash)
      ReadResponseHelper         checkKnownHash, injectSessionTokenMeter
    write/
      FileContentWriter          doWrite, doAppend
      FileReplaceService         doReplace, doMultiReplace
      FilePatchService           doPatch
      FileTransformService       server_transform (replace_method/section/between/etc)
      FileChunkWriter            doChunkWrite, doFinaliseWrite, doAbortWrite
      WriteUtils                 atomicWrite, makeBackup, computeHash
```

---

## Critical call patterns — get these right

### file_read action=list — returns listing_hash, supports knownHash

```
# First call — full listing + listing_hash
file_read action=list path=<dir>
→ {entries:[...], count, listing_hash:"abc123456789"}

# Repeat call — pass prior hash, get ~15-token response if unchanged
file_read action=list path=<dir> options={knownHash:"abc123456789"}
→ {unchanged:true, listing_hash:"abc123456789", count:N}   # directory unchanged
→ {entries:[...], listing_hash:"new_hash"}                  # directory changed
```

### file_read action=multi_grep — grep N files in one call (no path param needed)

```
file_read action=multi_grep
          options={pattern:"import org.softwood",
                   paths:["File1.groovy","File2.groovy","File3.groovy"],
                   maxMatches:5}
→ {fileCount:3, matchingFiles:2, totalMatches:N,
   results:[{path, matchCount, matches:[{line,content}]}]}
```

### file_read action=grep — FILE path only, NOT a directory

```
# CORRECT
file_read action=grep path=<exact_file> options={pattern:"regex"}

# WRONG — hard error
file_read action=grep path=<directory>   ← "Path is not a file"

# For directory-wide grep: use file_search
file_search action=content path=<dir> options={contentPattern:"regex"}
```

### Gradle builds -- use tools action=gradle (NOT execute action=cmd)

```
# CORRECT -- canonical path, works from both Claude and AW flows
mcp-groovy-filesystem-server:tools action=gradle subcommand=compileGroovy
        options={workingDir:"C:/Users/willw/IdeaProjects/<server>"}

mcp-groovy-filesystem-server:tools action=gradle subcommand=packageMcpbThin
        options={workingDir:"C:/Users/willw/IdeaProjects/<server>"}

mcp-groovy-filesystem-server:tools action=gradle subcommand=installMcpbLocal
        options={workingDir:"C:/Users/willw/IdeaProjects/<server>"}

# WRONG -- execute action=cmd for gradle is the old pattern, do not use
execute action=cmd script="gradlew.bat bootJar"   <-- deprecated
```

### execute -- multi-line, long-running, and native commands (FS 0.9.11 / 0.9.12 / 0.9.15 / 0.9.16)

**An unread stream is not an empty stream (0.9.16).** `success` now requires that FS actually READ
the child's output: `exitCode == 0 && streamsOk`. If a reader thread died or was abandoned at the
join, the response carries `stream_error` -- in the compact shape as well as the verbose one -- and
`success` is withheld. Before this, a reader that threw left an empty buffer and the call returned
`exitCode 0` with empty stdout, indistinguishable from a command that printed nothing. Treat
`stream_error` as "I could not measure it", never as "there was no output".

**Native executables run under powershell regardless of inherited PATHEXT (0.9.15).** FS repairs
`PATHEXT` for every child it spawns when the inherited value cannot run executables. It had been
inheriting `PATHEXT=.CPL`, and with `.EXE` absent PowerShell classifies `git.exe` as a *document*
rather than an application: it is never run, `$LASTEXITCODE` is never set, `$?` stays `True`,
`$Error` is empty, both streams are empty and the process exits 0. `cmd` was immune because it
normalises PATHEXT itself. An empty `git status --porcelain` reads as a clean tree, and the
`git rev-parse HEAD` vs `origin/<branch>` push check compares two empty strings and passes -- so
this presented as success everywhere it mattered. A caller-supplied PATHEXT that already works is
left alone. `ExecuteServiceNativeCommandSpec` PATHEXT-2 pins the differential: cmd and powershell
must agree on the same native command.

**Multi-line scripts run every line.** Lines execute in order and the LAST command's exit code is
returned; a mid-script failure does NOT abort the rest (same contract as `bash -c`, no `set -e`).
When a script mutates state, verify the state -- a `git add` + `git commit` script returning 0 has
not necessarily committed. Before 0.9.11 `action=cmd` silently ran only the first line and returned
`exitCode 0`, which is indistinguishable from success; that is how a real commit was lost
(observation 9881).

**Anything that may take over ~60s must be submitted, not awaited.** The ~60s deadline is imposed by
the MCP *client*, not by FS: `options.timeout` cannot extend it, and a blocked call also serialises
every call behind it (observation 9821, chain `ef8cae5c`).

```
# CORRECT -- submit, then poll
execute action=cmd script="gradlew.bat test" options={async:true, workingDir:"<dir>"}
   -> {jobId, status:"running"}
execute action=job_status jobId=<id>
execute action=job_output jobId=<id> sinceOffset=<nextOffset from last read>
execute action=job_cancel jobId=<id>
execute action=job_list

# WRONG -- raising options.timeout does nothing; the client has already given up
execute action=cmd script="gradlew.bat test" options={timeout:600}
```

Detaching via `Start-Process` and polling a redirected log file was the pre-0.9.12 workaround. It
still works, but `async` is the supported path: it reports exit code and status, and `job_cancel`
kills the process rather than leaving it orphaned.

### server_transform — correct param names

```
# replace_method: body in options.newBody (NOT content)
file_write action=server_transform path=<file>
           options={transform:"replace_method", method:"doThing",
                    newBody:"    ReturnType doThing(...) {\n        ...\n    }",
                    expectedHash:"<hash>"}

# replace_between: new text in options.newContent
# replace_section: new text in options.newContent
# insert_before_match: any file, substring in options.match, new lines in options.content, optional options.occurrence
# insert_after_heading / append_section: new text in options.content
# add_import: import string in options.importStatement
```

---

## Overflow protection (always active)

| Fix | What it does |
|-----|-------------|
| FIX-A | `doRead` refuses files >200 lines — use `structure`/`get_method`/`range`. `force=true` overrides. |
| FIX-B | `_session_read_tokens` injected into every read response. Warns at 40K/80K. |
| FIX-C | McpController hard cap: 64K chars max on any single tool response. |
| FIX-D | `knownHash` on read/range/get_method/list: returns `{unchanged:true}` if unchanged — ZERO tokens consumed. |
| FIX-KH-AUTO | Server-side auto-lookup (FS 0.8.77+): `doRead` auto-checks CS session hash cache. No hash needed from caller — `{unchanged:true, _auto_kh:true}` fires automatically on repeat whole-file reads. |

### knownHash — how it works (FS 0.8.77+)

**Server-side auto-lookup is now active for whole-file reads (`action=read`).**
After every content-returning `doRead`, FS stores the hash in the CS session cache (`/fileHashCache`). On the next `doRead` of the same file without a `knownHash`, FS auto-looks up the cached hash and returns `{unchanged:true, _auto_kh:true}` if the file hasn’t changed. You get the token savings without tracking or passing anything.

`knownhash_pct` is tracked per session in `mid-session-audit`. Target: >40%. Both auto-hits and explicit hits count.

**For `action=range`, `get_method`, `head`, `tail` — pass `knownHash` explicitly** (auto-lookup does not apply to partial reads — returning `unchanged:true` for a range you haven’t seen would be a correctness bug):

**Hash sources (check in this order):**
1. `bootstrap globals` — `working_file_hashes["<path>"].hash` loaded by session-bootstrap for all prior-session working files
2. `file_content_hash` — in every `file_read` response that returns content; capture and pass on next read
3. `listing_hash` — returned by `action=list`; pass back for directory re-checks

```
# Whole-file read — auto-lookup handles repeat reads, no hash needed
file_read action=read path=Foo.groovy options={force:true}
→ {content:"...", file_content_hash:"abc123"}   ← hash stored server-side automatically

file_read action=read path=Foo.groovy options={force:true}   # repeat, no knownHash
→ {unchanged:true, _auto_kh:true}   # 33 tokens — auto-hit

# Range/get_method — still pass knownHash explicitly
file_read action=range path=Foo.groovy options={startLine:1,maxLines:50}
→ {content:"...", file_content_hash:"abc123"}   ← CAPTURE THIS

file_read action=range path=Foo.groovy options={startLine:51,maxLines:50,knownHash:"abc123"}
→ {unchanged:true}   # ~15 tokens
```

**Feature flags** (can disable without redeployment via `application.properties`):
- `mcp.filesystem.auto-kh-lookup.enabled=true` — master switch for auto-lookup
- `mcp.filesystem.auto-kh-hints-suppressed.enabled=true` — suppresses `_knownhash_hint` noise when auto is active

---

## Editing rules

- **Always** pass `options.expectedHash` on every mutating action — **MANDATORY for `replace`/`patch`/`multi_replace`** (absent = hard `toolError`, FS 0.8.73). Get it from the prior read's `file_content_hash`.
- **Preferred for method edits:** `get_method` → `patch` (line-addressed, always unique)
- **For small unique insertions:** `grep` to confirm one match → `replace` with hash
- **For multiple changes to one file:** `multi_replace` in one call (pre-validates all before writing)
- **For method-level rewrites:** `server_transform transform=replace_method options.method=X options.newBody=Y`
- **Never** call `grep` with a directory path — hard error
- **Never** sequential `replace` calls without re-reading between them — use `multi_replace`
- **For files >200 lines:** `structure` → `get_method`, never `read` without `force=true`
- **After any patch:** use returned `content_hash` as `expectedHash` for the next edit
- **multi_replace overlap rule (v0.8.48):** entries sharing a boundary line are rejected — merge into one entry or use separate calls
- **Boundary patch (v0.8.48):** response includes `requires_reread:true` when `startLine==1` or `endLine==last` — re-read before next edit

### Structural guard bypass (FS 0.9.6)

When `action=append` leaves an orphaned closing brace that `StructuralGuard` blocks every
subsequent targeted repair on, pass `options.allowStructuralEdit=true` to `replace`, `patch`,
or `multi_replace` to bypass the brace/paren delta check for that one repair call.

```
# Repair an orphaned brace left by a bad append
file_write action=patch path=Foo.groovy options={
  replacements: [{startLine:N, endLine:N, newText:''}],
  expectedHash: '<hash>',
  allowStructuralEdit: true        # bypasses brace/paren delta only
}
```

- `checkBareBoxDrawing` is **never** bypassed — only brace and paren delta.
- The bypass is logged as WARN in CS for observability.
- `action=append` on `.groovy`/`.java`/`.kt`/`.kts` returns a `code_append_warning` field
  in the response (FS 0.9.6). Suppress with `options.suppressCodeAppendWarning=true`.
  Prefer `action=replace` or `server_transform transform=add_method` for code files.

### Error contract (v0.8.48+)

All tool errors return `isError:true` in content — Claude Desktop renders `content[0].text` directly. The old JSON-RPC `{error:{code,message}}` is no longer used for tool handlers.
```groovy
// NEW (0.8.48+)
assert r.result.isError == true
assert (r.result.content[0] as Map).text.contains('expected keyword')
// OLD (pre-0.8.48) — no longer applies
assert r.error != null   // always null for tool errors now
```

---

## ContextServerClient — fire-and-forget HTTP to port 8082

After every `structure` scan (cache miss), `FileStructureReader` calls
`contextServerClient.persistStructureAsync()` asynchronously. If the context server is down,
the call fails silently at DEBUG level. Never block file operations waiting for it.

The client also caches directory listings in-memory after `file_list` calls.

---

## Build and deploy

```
# Build (canonical path)
mcp-groovy-filesystem-server:tools action=gradle subcommand=compileGroovy options={workingDir:"C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server"}
mcp-groovy-filesystem-server:tools action=gradle subcommand=packageMcpbThin options={workingDir:"C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server"}
mcp-groovy-filesystem-server:tools action=gradle subcommand=installMcpbLocal options={workingDir:"C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server"}
# Deploy via flow template (handles all 5 config updates + restart)
start_flow mode=flow templateName=mcp-deploy
           params={serverName:"filesystem", projectDir:"...", newVersion:"Y", jarPrefix:"mcp-groovy-filesystem-server"}
```

**`mcp-http-servers.json` is now auto-updated** by `copyToJarsDir` (v0.8.48) — no manual step needed after deploy.

Five-config rule — on every version bump update ALL of:
1. `build.gradle` version string
2. `claude-sync/mcp-http-servers.json`
3. `claude-sync/claude_desktop_config.json` (and sync to AppData)
4. `claude-sync/claude_code_mcp_config.json`
5. `claude-sync/regression-test.py`

---

## Verification after deploy

```
# Confirm version
server_lifecycle action=status verbose=true  → jar should show new version

# Test expectedHash mandatory (v0.8.73) -- must reject
file_write action=replace path=<any file> options={oldText:'anything', newText:'X'}
→ isError:true, text contains 'expectedHash' and 'required'

# Test error surfacing (v0.8.48 contract)
file_write action=replace path=<any file> options={oldText:'NOTEXIST', expectedHash:<hash>}
→ r.result.isError==true, content[0].text contains 'oldText not found'  (NOT r.error!=null)

# Test multi_replace overlap rejection
file_write action=multi_replace path=<file> options={replacements:[{oldText:'a\nb',newText:'X'},{oldText:'b\nc',newText:'Y'}],...}
→ isError:true, text contains 'overlap'

# Test boundary patch requires_reread
file_write action=patch path=<file> options={replacements:[{startLine:1,endLine:1,newText:'X'}],...}
→ success, parseContent(r).requires_reread == true

# Test knownHash on read
file_read action=structure path=<any groovy file>
→ note file_content_hash in response
file_read action=structure path=<same file> options={knownHash:<hash>}
→ should return {unchanged:true}

# Test listing_hash
file_read action=list path=<any dir>
→ note listing_hash in response
file_read action=list path=<same dir> options={knownHash:<listing_hash>}
→ should return {unchanged:true, count:N}

# Test multi_grep (no path param)
file_read action=multi_grep options={pattern:"package com", paths:["File1.groovy","File2.groovy"]}
→ should return matched files without error

# Test session token meter
file_read action=stat path=<any file>
→ response should contain _session_read_tokens field

# Test chunk_status (v0.8.44)
file_write action=chunk_write path=x.txt content="hello" options={sessionId:"test-1", chunkIndex:0}
file_write action=chunk_status options={sessionId:"test-1", totalChunks:2}
→ should return receivedChunks:[0], missingChunks:[1], ready:false
file_write action=abort_write options={sessionId:"test-1"}

# Test get_method fallback flag (v0.8.44)
# (only visible when file has a compile error — look for fallback:true in response)
```


---

## Session ID contract — this process's own claim (FS 0.9.17)

**FS no longer reads `active_session`.** That table is a machine-wide singleton
(`CHECK (id = 1)`), and with two Claude chats open the second chat's bootstrap overwrote the row
the first was resolving through — so FS attributed one chat's telemetry to the other. The MCP
stdio contract is one JVM per client connection: **the process is the chat**, and identity is now
per-process.

`FilesystemTelemetryService.readActiveSessionId()` resolves in three steps and then stops:

. the in-process claim held in memory (no TTL — a process cannot outlive itself)
. this process's own `session_claims` row, keyed on `ProcessIdentity.OWNER_KEY`
. **null — UNBOUND**

There is no fourth step, and its absence is the fix. It returns `null` rather than a sentinel:
callers must handle UNBOUND, because a manufactured `'unknown'` is a value, and a value is not a
refusal — it lands upstream of every guard that checks for null.

`ProcessIdentity` mints `OWNER_KEY` as `fs-<pid>-<jvmStartMillis>-<random>`. The JVM start time is
load-bearing: an OS reuses pids, so pid alone would let a new process inherit a dead one's claim.
CS's reaper decides liveness for all three servers' rows, conservatively — only a positive
determination of death reaps.

Tools: `server_lifecycle action=claim_session | release_claim | claim_status`.

**Do not add back a permanent cache-on-first-resolve, and do not add back a read of
`active_session`.** The earlier stale-cache bug (OW-3, fixed in 0.8.82 by revalidating against the
singleton on every call) is now structurally impossible rather than merely revalidated: a
restarted process is a new process with a new `OWNER_KEY` and no claim, so it reports UNBOUND
instead of holding a stale id.

---

## Cross-server DB isolation

**A database is owned by its server alone. Other servers ask; they do not reach in.**

That rule is absolute for schema: FS must never issue DDL against `best_practices.db`. FS 0.9.28
briefly added `owner_key`/`caller_session` to `tool_call_telemetry` via `ALTER TABLE` from
`FilesystemTelemetryService.init()`, reasoning that a Desktop restart gives no ordering guarantee
between the two JVMs and an un-migrated table would lose every row to a debug-level catch. The
hazard was real and the conclusion was backwards: it is an argument for FS not writing the table at
all, and it disappeared the moment FS stopped. Reverted in 0.9.29.

`tool_call_telemetry` is **no longer written by FS** (0.9.29). `FilesystemTelemetryService` builds
the row — including its own `ProcessIdentity.OWNER_KEY`, which CS cannot resolve because the call
lands in the shared companion rather than this JVM — and hands it to
`ContextServerClient.recordToolCall(sessionId, row)`, which calls
`context_lifecycle action=record_tool_call`. The trade is explicit: the old JDBC path worked when CS
was down, this one does not. `isCsReachable()` short-circuits and the row is dropped, never queued.
Telemetry is evidence about a call, never part of it.

FS's remaining direct JDBC access to `best_practices.db`, through `FilesystemTelemetryService`:
- Reading **and upserting its own `session_claims` row** (own `owner_key` only — never another
  process's row; FS 0.9.17. Was `active_session.session_id`, read-only, before that)
- Reading/writing `pending_reindex` queue

Both are on the list to move behind CS actions too. Neither is a licence to add a third: the claim
row is load-bearing for CLAIM-GATE, and `pending_reindex` is explicitly the fallback for when CS
HTTP is unreachable, so it cannot be routed through CS HTTP without being circular. Those are the
two questions to answer before either moves — not reasons to keep reaching in.

All other FS→CS communication goes via `ContextServerClient` HTTP calls to port 8082.
CS's WAL and connection pool are never bypassed. See `FS_CONTEXT_ARCHITECTURE.md §15`.

### `tool_call_telemetry.outcome` accuracy (FS 0.9.5+)

`McpController.extractOutcome` now correctly detects tool-level errors (`result.isError==true`)
in addition to protocol-level errors. Prior to 0.9.5, any response going through
`McpResponse.toolError()` (which wraps `isError:true` inside `result`) was recorded as
`outcome='success'` because the extractor only checked `response.error != null`. Fixed by
adding `result instanceof Map && result.isError == true` branch, matching the CS
`deriveOutcome` logic pattern.

The `outcome='unchanged'` cache-hit write path (CS-side) is tracked separately as
build-16B (CS link still open — FS side done in 0.9.5).


---

## CRITICAL: PowerShell file rewrites can corrupt Unicode characters

**When using PowerShell `[System.IO.File]::WriteAllText()` to rewrite a `.groovy` file,
any Unicode character outside ASCII may be silently re-encoded as CP1252 bytes.**

The ellipsis `…` (U+2026) is a known victim: it becomes three chars `â€¦`, each stored
as a distinct Groovy `char` literal. Any cap/length logic that appends a single `…` will
produce `length == cap + 3` instead of `cap + 1`.

**Fix:** Use `'\u2026'` Unicode escape in Groovy source. After any PowerShell rewrite,
grep the affected file for `\u00e2\u20ac` as a mojibake sentinel.

This was introduced in FS when a block-removal PowerShell script rewrote `FlowTypeRegistry.groovy`
during AW 1.28.10 stabilisation. `FlowTypeRegistryExtractFieldSpec` caps test caught it.

---

## Logging and startup, aligned across FS / CS / AW (2026-09-08, revised 2026-09-09)

FS 0.9.21-0.9.22, CS 1.0.42-1.0.45, AW 1.30.12-1.30.13. All three servers share one stderr rule and one EOF shutdown contract (AW had neither until 1.30.13 -- it logged an EOF message and left the JVM resident, orphaning a process on every restart).

TWO CLAIMS MADE UNDER THIS HEADING ON 2026-09-08 HAVE SINCE BEEN CORRECTED. (1) The stderr fix is NOT the cause of the intermittent tool-list drops; those were Claude Desktop auto-updating and relaunching itself, which closes stdin on every stdio child, proven from Squirrel's own update log on 2026-09-09. (2) The rolling policy described below is declared but does NOT bound these files in practice: logback's size trigger counts bytes written by the current appender instance and every restart resets it, so no instance lives long enough to count 20MB. Exactly one rolled archive existed on the machine when this was measured, and it rolled at a restart rather than on size. Treat file growth as unbounded between date boundaries and check sizes directly.

Full account, including the startup and shutdown sequencing rules and the evidence discipline that came out of it: "The one that would not exit" -- CS docs ch58, AW docs ch22.

### The rule

**In stdio mode the server must not write to stderr at all.** stderr is a pipe with a fixed OS
buffer whose draining is the client's choice; when it fills, the next write blocks the writing
thread, and if that is the thread reading stdin the server stops answering while looking alive.

`logback-spring.xml` in all three defines two complete `<root>` blocks, each inside a **top-level**
`<springProfile>`: `stdio` gets FILE only, `!stdio` gets STDERR + FILE.

Two traps, both real, both hit:

- `springProfile` must be a **direct child of `<configuration>`**. Nested inside `<root>` it fails
  with `Failed to find appender named [STDERR]` and took out 279 of FS's 342 tests.
- **XML comments may not contain `--`.** Logback then fails to parse, Spring Boot falls back to its
  default console appender, and the server blocks on stderr exactly as before. An invalid config
  fails in the shape of the bug it fixes.

Measured with stderr redirected and never drained: FS 0.9.18 no answer in 40s vs 0.9.19 in 2.48s;
AW 1.30.9 no answer in 35s vs 1.30.10 in 3.68s.

### Retention

All three FILE appenders use `SizeAndTimeBasedRollingPolicy`: **20MB per file, `maxHistory=2` days,
`totalSizeCap=100MB`.** Two days is the diagnostic window that matters; the size cap is what stops
one runaway loop filling the disk before the daily roll arrives.

Note: logback's size trigger counts bytes written by the *current* appender instance, not the length
of the file it appended to, so an already-oversized file inherited across a restart rolls at the next
date boundary rather than immediately.

### HEARTBEAT

`McpHeartbeat` in all three, started by `StdioMcpServer` before the read loop, fed by
`recordRequest()` on every accepted request line, 60s interval:

```
HEARTBEAT server=<name> v=<version> pid=<pid> uptime=<n>s requests=<n> idle=<n>s
```

INFO normally, WARN past 120s idle, `idle=never-any-request` before the first request. It separates
two situations that are otherwise indistinguishable from outside:

| observation | meaning |
| --- | --- |
| heartbeats continuing, `idle` climbing | alive, receiving nothing. Fault is upstream. |
| heartbeats stopped | wedged or gone. The last line before the gap is the evidence. |

### What it found on its first restart

2026-09-08 17:22, heartbeat live for the first time:

- **Claude Desktop spawned four stdio instances of every server** (FS pids 53808 / 54536 / 61568 /
  64780; four CS; five AW).
- Each FS stdio instance ran `autoStartHttpCompanions`, so one restart produced **6 FS JVMs, 6 AW
  companions on 8084, 19 CS companion attempts on 8082, 3 FS companions racing for 8081**.
- **FS Spring startup took 49.6 seconds**, then answered `initialize` and `tools/list` correctly
  (18,727 bytes in 19ms) and received nothing further. Two instances sat at `requests=3` with `idle`
  climbing; one AW instance reported `requests=0 idle=never-any-request`.
- FS's tools were absent from the live session during that window and returned unprompted about two
  minutes later.
- The `SQLITE_BUSY_SNAPSHOT` errors blocking AW flow starts are the same storm contending on
  `best_practices.db`.

### A claim withdrawn

FS 0.9.19 / CS 1.0.40 / AW 1.30.10 each called the stderr fix the **root cause** of the intermittent
tool-list drops. Withdrawn in the following release of each. The hazard is real and was measured, but
`jstack` against live stdio PIDs *while the tool list was empty* showed `main` RUNNABLE inside
`System.in.read()`, and the logs show `tools/list` answered in full. A server blocked on a stderr
write does not look like that.

### Open

1. **The companion storm.** `autoStartHttpCompanions` runs inside Spring startup on every stdio
   instance; no single process owns companion startup and the guards are check-then-act across
   processes. Live suspect for the drops: a ~50s cold start is long enough for a client to give up
   while the server carries on looking healthy. Not proven.
2. **We were deleting the evidence.** `LogCleaner` truncates Claude Desktop's own MCP client logs on
   startup; `mcp-server-groovy-filesystem.log` was 154 bytes, a banner written at the exact second of
   the drop.
3. **A logback logger level is a default, not a setting.** Spring Boot applies `logging.level.*`
   after parsing `logback-spring.xml`, so `application.yml` always wins. CS 1.0.41 lowered
   `com.woodmawa.mcp.context` to INFO in logback and the running companion kept logging DEBUG.
   Verify a logging change from the running process's output, never from the config or the jar.

---

## FS 0.9.26 (2026-09-10) — ONTOLOGY-GATE actually blocks now

**Baseline:** FS 0.9.26 · CS 1.0.58 · AW 1.30.17 (2026-09-10)

**Call `context_read scope=ontology action=locate` before any `file_read` on an indexed file.** This
stopped being advice in 0.9.26.

### It had blocked once, ever

`ONTOLOGY-GATE` is a declared hard gate — `on_violation: block_and_observe`, enforced by default
since FS 0.9.8. One block, on **2026-05-29**. Over the trailing 30 days: **111 sessions, 2,269 reads,
average `ontology_pct` 70.9%, and 21 sessions at literally zero locate calls** — none refused. A
review from 2026-05-07 had already written down *"ontology_pct 17–23% = ~80% of file reads bypass
ontology locate"*; the number was known for four months, what it meant was not.

### Two defects that cancelled each other into silence

1. `checkOntologyGate` resolved the file by its bare **stem** through a fuzzy locate, then returned
   `null` whenever the resolved `source_file` differed from the file being read. In a codebase where
   nearly every `Foo.groovy` has a `FooSpec.groovy`, **the stem resolves to the Spec**. The
   path-scope guard responsible was added deliberately and correctly, to stop a TempDir stem
   collision producing a spurious block — and it switched the gate off for most of the tree.
2. `locateCalledThisSession` read `sessionLocatedStems`, an in-memory `Set` **in the FS process**,
   whose only writer `recordLocateCalled` **had no callers anywhere in the repository**. It could
   only ever answer `false`.

Repair the first alone and every read blocks. Repair the second alone and nothing changes. **The gate
read as working because both were broken.** And `OntologyGateEnforcementSpec` OGE-1..11 passed
throughout, because its stubs hand-built the exact conditions production never produces.

### What 0.9.26 does instead

`checkOntologyGate` decides nothing itself. It asks CS **one question about one path** —
`ontologyGateCheck(normalizedPath, claimedSessionId)` — and takes the answer. CS owns the ontology
and serves `locate`, so both facts live there; FS keeps no second copy.

- **The session sent to CS is this process's CLAIMED session** (`readActiveSessionId()`), never a
  singleton. A claim arrives on this process's own pipe, so it is the one thing that identifies the
  chat.
- **The `.groovy`/`.java` filter is gone.** Whether a file is gated is decided by whether the
  **ontology** holds it — `.md` and `.adoc` are gated on the same terms, anything unindexed passes.
- **The gate runs at dispatch**, ahead of the action switch. It used to sit inside `doRead`,
  `doRange` and `doGetMethod`; `grep`, `head`, `tail`, `structure` and `summary` walked straight
  past, and those are the *cheap* calls, which is to say the ones actually used. Same correction
  CS 1.0.52 made by moving its dirty-flag ahead of the handler fast path.
- The exempt set is **named explicitly**, so an action added later is gated by default and has to be
  argued out of the set: `exists`, `stat`, `info`, `checksum`, `normalize`, `project_root`,
  `allowed_dirs`, `list`, `help` return no file content; `multi`, `multi_grep`, `chunk_read`,
  `finalise_read` carry no single path. **`multi_grep` is therefore still ungated — a known gap, not
  a decision.**

Every uncertain path **allows** — CS unreachable, path not indexed, no claimed session, check
errored — and says which in `reason`. A gate that cannot reach its evidence must not stop work, and
must not go quiet about it either.

### Using it

```
context_read scope=ontology action=locate query=<ClassName>     # <100 tokens, returns line range
file_read    action=range path=<file> options.startLine=… maxLines=…
```

`options.allowNoLocate=true` overrides the block and **increments telemetry**, so overrides stay
visible. Use it when locate genuinely cannot name your target — not as a habit.

**When the hint's query lands on the wrong file**, pass the path-qualified `node_id` instead of the
bare name: `locate query=doc:mcp-groovy-context-server/CLAUDE.md`. `locate` matches `node_id`
exactly before it tries any `LIKE`, so it is unambiguous where a symbol name is not. CS 1.0.58 fixed
the `Foo` vs `FooSpec` case (exact name now outranks a substring); files that **share** a name
across directories still need the `node_id`.


---

## FS 0.9.27 (2026-09-11) — a lost claim is now loud

**Baseline:** FS 0.9.27 · CS 1.0.58 · AW 1.30.17 (2026-09-11)

A Claude Desktop restart — including an **auto-update you never saw** — respawns every MCP JVM and
drops this process's session claim. CS has always complained about that; FS did not, and quietly
filed every subsequent call into the `session_id='unknown'` holding pen.

**What you will see now.** While this FS process holds no claim, every tool response carries a
second `content` element:

```json
{"unbound": true,
 "unbound_warning": "This FS process holds no session claim, so this call is filed as unattributed telemetry and does not count toward read_count or ontology_pct. Claim on THIS connection to bind it: server_lifecycle action=claim_session sessionId=<id>",
 "owner_key": "fs-<pid>-<jvmStart>-<rand>"}
```

**Do exactly what it says, immediately**: `server_lifecycle action=claim_session sessionId=<id>
groupId=<group>`. The warning stops on the next call. Everything filed while it was showing stays
in the pen until a sweep repairs it, and `read_count` / `ontology_pct` for the session do not count
those calls.

**Treat `unbound: true` as the signal, not the restart notification.** An auto-update gives you no
other warning; this is the only one.

### For anyone changing this

- `McpController.unboundWarningMap(ownerKey)` is the single definition of the three keys. Specs
  derive their expected key set from it (CT-UBW-4) — add a key there and the spec follows.
- `McpController.withUnboundWarning(response, ownerKey)` returns a **copy**. Do not make it mutate:
  the response is measured for telemetry and the backstop *before* this runs, and not mutating is
  what keeps the warning out of `tool_call_telemetry.response_char_count`.
- It appends a content element rather than injecting a key into `content[0].text`, so the handler's
  JSON payload is never re-parsed and **no response trim can eat the warning**. CS computed its
  unbound warning correctly in 1.0.25 and never delivered it, because `KEEP_KEYS` dropped it.
- The response warning fires on **every** call while unbound. The log line fires **once per unbound
  streak** (`unboundWarned`, reset when a claim is seen) — a flow-node process can make thousands
  of calls it was never meant to claim for.
- `FsUnboundLoudnessSpec` CT-UBW-6 asserts through `handleRequest`, not the helper. A/B'd: remove
  the dispatch wiring and CT-UBW-1..5 stay green while only CT-UBW-6 goes red. Keep it that way —
  a guard proved only in the test is the defect this platform keeps re-finding.

Contract: `fs-telemetry-not-stranded-in-unknown` (code, warn), registered red at 356.
