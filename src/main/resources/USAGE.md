# Filesystem Server — Detailed Usage Guide
_Retrieve this with: `file_read action=help topic=<tool>` or `topic=all`_

---

## file_read

### Session start (every conversation)
```
0. CLAIM THIS FS PROCESS (FS 0.9.17) -- from your own connection, right after session-bootstrap:
     server_lifecycle action=claim_session sessionId=<id> groupId=<group>
   Without it this process is UNBOUND: FS telemetry and range-cache keys resolve to nothing.
1. context_lifecycle action=start
2. context_read scope=project action=context groupId=<group>  (pass knownHash if available)
3. context_read scope=session action=resume
```

### Actions

**read** — full file content. Auto-chunked if >60KB. Refused if >200 lines; use structure/get_method/range instead. Pass `options.force=true` to override.

**head** — first N lines (default 30). Best for seeing imports + class declaration.

**tail** — last N lines (default 30).

**range** — line slice: `options.startLine` (1-indexed), `options.maxLines` (default 100).

**grep** — regex search within a **FILE** path only (hard error on directory path). `options.pattern` required. `options.contextLines=N` for surrounding context. For directory-wide search use `file_search action=content`.

**multi_grep** — grep one pattern across multiple files in one call. No `path` param. `options.paths[]` (up to 20, required), `options.pattern` (required), `options.maxMatches` (default 5 per file). Returns only files with matches. Use instead of repeated `grep` calls.
```
file_read action=multi_grep options={pattern:"import org.softwood",paths:["A.groovy","B.groovy"]}
→ {fileCount, matchingFiles, totalMatches, results:[{path,matchCount,matches:[{line,content}]}]}
```

**multi** — read up to 10 files in parallel. Pass `options.paths[]`. Pass `options.knownHashes {path->hash}` to skip unchanged files — zero cost.

**summary** / **stat** — file metadata without content. `stat` includes language detection.

**structure** — code outline: classes, methods, fields with startLine/endLine. `options.compact=true` returns methods only. `options.className=Foo` filters to one class subtree.

**get_method** — returns complete named method body. Always call immediately before patching. Preferred over structure+range for editing.

**list** — directory listing: name, type, size, lastModified. Always returns `listing_hash`. Pass as `options.knownHash` on repeat calls — returns `{unchanged:true, listing_hash, count}` (~15 tokens) if directory unchanged. Replaces PowerShell Get-ChildItem.
```
file_read action=list path=<dir>                          → {entries:[...], listing_hash:"abc123"}
file_read action=list path=<dir> options={knownHash:"abc123"}  → {unchanged:true, count:N}
```

**diff** — line-by-line diff of two files. `options.compareTo` required.

**help** — this document. `options.topic=<tool|all>`.

### Context efficiency rules
1. Never re-read a file or directory listing unless it changed. Pass `options.knownHash` (files use `file_content_hash`; directories use `listing_hash`).
2. For editing: structure → get_method (never read whole file).
3. For searching: grep or file_search (never read-then-scan).
4. Use stat/summary first on unknown files to check size.
5. All read actions return `file_content_hash`. Pass as `options.expectedHash` on writes.

---

## file_write

### Actions

**write** — overwrite entire file. `path` and `content` required.

**append** — append to end of file.

**replace** — replace ONE unique string. Params inside `options`: `oldText`, `newText`. Fails if not found or found multiple times (returns nearest_match or line numbers). Always grep first to confirm uniqueness.

**patch** — line-range edits: `options.replacements[]` = `[{startLine, endLine, newText}]` (1-indexed, inclusive). ALWAYS call get_method immediately before to get current line numbers — they shift after every patch. Response includes `requires_reread:true` when patch touches line 1 or the last line (boundary patch) — re-read before next edit on that file.

**multi_replace** — ordered list of `[{oldText, newText}]` swaps. Pre-validates ALL before writing. Preferred for multiple changes to same file. Safety rules (v0.8.48):
- Entries that overlap (one contains the other, or they share a boundary line) are **rejected** with a clear error — merge into one entry or use separate calls.
- A simulation pass runs before write: if entry N makes entry M unfindable, the whole batch fails and the file is unchanged.
- Brace balance is checked on the simulated result **before** writing — returns error if unbalanced, file not modified.

**server_transform** — server-side named transformation. File content never crosses context boundary. REQUIRED: `options.expectedHash`. `options.transform`:
- `replace_method` — Groovy/Java only. Params: `options.method` (name), `options.newBody` (full method text)
- `add_method` — Groovy/Java only. Params: `options.method` (name), `options.newBody` (full method text)
- `add_import` — Groovy/Java only. Params: `options.import` (full import line e.g. `'import com.example.Foo'` or just `'com.example.Foo'` — `import ` prefix added automatically if absent)
- `replace_section` — Markdown/yml/yaml/toml only. Params: `options.heading`, `options.newContent`
- `insert_before_match` — **any file type**. Params: `options.match` (substring), `options.content`, `options.occurrence` (1=first/default, -1=last, N=Nth)
- `insert_after_heading` — Markdown/yml/yaml/toml. Params: `options.heading`, `options.content`
- `append_section` — Markdown/yml/yaml/toml. Params: `options.heading`, `options.content`
- `replace_between` — **any file type**. Params: `options.startAnchor`, `options.endAnchor`, `options.newContent`

**Param name summary:** body → `newBody`; section/between content → `newContent`; insert/append content → `content`; import → `options.import` (NOT `importStatement`). NEVER use top-level `content=` for server_transform.

### Safe editing workflow
```
1. file_read action=stat                    → check lines count
2. file_read action=get_method              → read method body + get file_content_hash
3. file_write action=patch                  → use startLine/endLine from step 2, pass expectedHash
4. file_read action=range (same lines)      → verify brace balance before next edit
```

### Rules
- Always pass `expectedHash` on every mutating action
- `path` must be at TOP LEVEL of arguments, not inside options
- Never use sequential replace calls without re-reading between them → use multi_replace
- After any patch, re-read before next patch (line numbers shift)
- **Error responses (v0.8.48+):** all tool errors return `isError:true` in content — `content[0].text` contains the error message. The old JSON-RPC `{error:{code,message}}` format is no longer used for tool-level errors.

---

## file_list

### Actions

**children** — immediate children only. Cheapest. Returns name/type/size/lastModified.

**list** — filtered listing. `options.recursive=true` for full walk. `options.pattern` for filename filter. `options.compact=true` for minimal output.

**tree** — recursive JSON tree. `options.maxDepth` (default 2). `options.maxResults` (default 200). `options.excludePatterns` to skip dirs (e.g. `[".git", "build", "node_modules"]`).

**sizes** — children sorted by size descending. Useful for finding large files.

---

## file_search

### Actions

**content** — grep-style regex search in file contents. `options.contentPattern` required. Returns matches with file path and line numbers.

**name** — filename regex search. `options.filePattern` required.

**project** — search within project root using default code file filter (groovy/java/gradle/yml/json/md/txt etc).

Common options: `maxResults` (default 50), `maxDepth`, `recursive` (default true).

---

## file_lifecycle

### Actions

**create** — create file or directory. `options.type=file|directory`. `options.mkdirs=true` creates parent dirs.

**delete** — delete file; directory requires `options.recursive=true`.

**copy / move / rename** — `dst` parameter required. `options.overwrite=false` by default. `options.mkdirs=true` creates parent dirs.

**touch** — update mtime, or create empty file if missing.

---

## execute

### Actions: bash | powershell | groovy | cmd | python

Scripts validated against dangerous patterns. Working directory must be in allowed directories.

Key options:
- `options.workingDir` — working directory
- `options.timeout` — seconds (default 60)
- `options.maxStdout` — chars to return (default 50000 ~12K tokens). Set lower to save context window.
- `options.maxStderr` — chars to return (default 5000)
- `options.grepPattern` — Java regex applied to stdout lines after execution. Only matching lines returned. Supports full Java regex including `|` alternation (unlike Windows `findstr`). Example: `"MessagesRequestBuilder\\.class$|EventsRequestBuilder\\.class$"`. Use this instead of piping to findstr for any output filtering.

### Multi-line scripts (FS 0.9.11)

Every action runs **every line** of a multi-line script. Lines execute in order and the **last**
command's exit code is returned — a mid-script failure does **not** abort the rest, the same
contract as `bash -c`, which has no `set -e`.

> When a script mutates something, check the resulting state rather than trusting a single
> `exitCode`. A `git add` + `git commit` script that returns 0 has not necessarily committed.

Before 0.9.11 `action=cmd` silently ran **only the first line**, returning `exitCode 0` and the
first command's stdout — indistinguishable from full success. `bash`, `powershell` and `python`
were always correct.

### Long-running work — `options.async` (FS 0.9.12)

`execute` is bounded by a hard **~60s deadline imposed by the MCP client**. `options.timeout` does
**not** extend it: FS honours that value, but the caller has already given up — and the blocked call
**serialises every call behind it**.

For anything that might exceed 60s (gradle builds, full test suites), submit it:

```
execute action=cmd script="gradlew.bat test" options={async:true, workingDir:"<dir>"}
  -> {jobId:"...", status:"running"}

execute action=job_status jobId="..."        # status, exitCode, elapsedMs, byte counts
execute action=job_output jobId="..." sinceOffset=0
  -> {stdout:"...", nextOffset:1234}         # pass nextOffset next time to tail cheaply
execute action=job_cancel jobId="..."        # kills the OS process, not just the promise
execute action=job_list                      # newest first
```

Jobs are retained for 30 minutes after finishing (max 100). An unknown `jobId` is an explicit
error, never a blank status.

### Windows builds
Use `tools action=gradle` for all builds — canonical path from both Claude and AW flows:
```
mcp-groovy-filesystem-server:tools action=gradle subcommand=compileGroovy options={workingDir:"<projectDir>"}
mcp-groovy-filesystem-server:tools action=gradle subcommand=packageMcpbThin options={workingDir:"<projectDir>"}
mcp-groovy-filesystem-server:tools action=gradle subcommand=installMcpbLocal options={workingDir:"<projectDir>"}
```
Do NOT use `execute action=cmd script='gradlew.bat ...'` — that is the deprecated path.

---

## server_lifecycle

**v0.8.29 fix:** The filesystem stdio process no longer self-spawns as an HTTP companion.
Previously `autoStartHttpCompanions` would launch `filesystem` on port 8081, then Spring Boot
tried to bind the same port and the stdio process crashed silently. Now uses `ownPort` guard to skip self.

### Actions: start_eager | ensure | stop | status | reload | claim_session | release_claim | claim_status

Manages HTTP MCP server processes via `claude-sync/mcp-http-servers.json`.

- `start_eager` — start all servers with `startupPolicy=eager`
- `ensure name=<server>` — start named server if not running (lazy startup)
- `stop name=<server>` — stop named server (omit name to stop all)
- `status` — list all servers with state. `verbose=true` for full detail.
- `reload` — re-read config without restarting

### Session claim (FS 0.9.17)

- `claim_session sessionId=<id> groupId=<group>` — bind THIS FS process to your chat
- `release_claim` — drop the binding
- `claim_status` — report what this process is serving

**Why you have to issue this rather than FS working it out.** Session identity used to come from
`active_session`, a table declared `CHECK (id = 1)` — one row per machine — while the MCP stdio
contract is one JVM per client connection. With two chats open, the second chat's bootstrap
overwrote the row the first was resolving through, and FS attributed one chat's telemetry to the
other, silently. **The process is the chat**, so identity is now per-process, held in
`session_claims` and keyed on this JVM's own `owner_key`.

A server cannot tell from the inside which chat it is serving — an HTTP companion is a different
process from the stdio JVM talking to you — so the claim is issued by the caller, not inferred.
An unclaimed process resolves to **UNBOUND** (null), never to whichever session started most
recently.

Server names: `filesystem` | `context` | `orchestrator` | `agentic-workflow`
