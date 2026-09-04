# mcp-groovy-filesystem-server — Claude Code Guide

## Project identity

- **Language:** Groovy 5 / Spring Boot 4 / Java 25
- **Purpose:** MCP filesystem server — file read/write/search/list/execute for Windows
- **Transport:** STDIO (primary, Claude Desktop) + Streamable HTTP companion (:8081)
- **Current version:** `0.9.17` (check `build.gradle` to confirm)
- **Baseline stack:** FS 0.9.17 / CS 1.0.26 / AW 1.30.8 — 2026-09-04
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

FS's only permitted direct JDBC access to `best_practices.db` (CS's database) is through
`FilesystemTelemetryService` for:
- Reading **and upserting its own `session_claims` row** (own `owner_key` only — never another
  process's row; FS 0.9.17. Was `active_session.session_id`, read-only, before that)
- Writing `tool_call_telemetry` rows
- Reading/writing `pending_reindex` queue

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
