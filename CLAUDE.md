# mcp-groovy-filesystem-server — Claude Code Guide

## Project identity

- **Language:** Groovy 5 / Spring Boot 4 / Java 25
- **Purpose:** MCP filesystem server — file read/write/search/list/execute for Windows
- **Transport:** STDIO (primary, Claude Desktop) + Streamable HTTP companion (:8081)
- **Current version:** `0.9.4` (check `build.gradle` to confirm)
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

## ContextServerClient — session ID contract (FS 0.8.82+)

`ContextServerClient.resolveSessionId()` **always reads the live `active_session` table** via
`FilesystemTelemetryService.readActiveSessionId()` on every invocation. It compares the live
value to the cached `activeSessionId` field and updates the cache if they differ.

This eliminates the stale-cache bug (OW-3) where FS held the previous session's ID after a DT
restart, causing all `recordRangeCacheAsync` writes and `checkRangeCache` lookups to target the
wrong session. Root cause of `real_kh_pct` being stuck at ~15% despite FS 0.8.81 auto-range-cache
being mechanically correct.

**Do not add back a permanent cache-on-first-resolve.** The JDBC read is sub-millisecond.

---

## Cross-server DB isolation

FS's only permitted direct JDBC access to `best_practices.db` (CS's database) is through
`FilesystemTelemetryService` for:
- Reading `active_session.session_id`
- Writing `tool_call_telemetry` rows
- Reading/writing `pending_reindex` queue

All other FS→CS communication goes via `ContextServerClient` HTTP calls to port 8082.
CS's WAL and connection pool are never bypassed. See `FS_CONTEXT_ARCHITECTURE.md §15`.
