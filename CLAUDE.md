# mcp-groovy-filesystem-server — Claude Code Guide

## Project identity

- **Language:** Groovy 5 / Spring Boot 4 / Java 25
- **Purpose:** MCP filesystem server — file read/write/search/list/execute for Windows
- **Transport:** STDIO (primary, Claude Desktop) + Streamable HTTP companion (:8081)
- **Current version:** `0.8.45` (check `build.gradle` to confirm)
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
| FIX-D | `knownHash` on read/structure/get_method/list: returns `{unchanged:true}` if unchanged — zero cost. |

**Always pass `knownHash` on re-reads.** Get the hash from `file_content_hash` (files) or
`listing_hash` (directories) in the previous response and pass it back as `options.knownHash`.

---

## Editing rules

- **Always** pass `options.expectedHash` on every mutating action — get it from the prior read's `file_content_hash`
- **Preferred for method edits:** `get_method` → `patch` (line-addressed, always unique)
- **For small unique insertions:** `grep` to confirm one match → `replace` with hash
- **For multiple changes to one file:** `multi_replace` in one call (pre-validates all before writing)
- **For method-level rewrites:** `server_transform transform=replace_method options.method=X options.newBody=Y`
- **Never** call `grep` with a directory path — hard error
- **Never** sequential `replace` calls without re-reading between them — use `multi_replace`
- **For files >200 lines:** `structure` → `get_method`, never `read` without `force=true`
- **After any patch:** use returned `content_hash` as `expectedHash` for the next edit

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
