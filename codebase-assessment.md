# Codebase Assessment  mcp-groovy-filesystem-server v0.7.22 → v0.7.23
_Initial review 2026-03-01; second pass 2026-03-01. Reviewed by Claude Sonnet 4.6._

---

## Executive Summary

The codebase is in good shape architecturally. The safe editing protocol (hash drift guards,
atomic writes, bottom-up patching) is solid and addresses the real-world corruption problems
from Phase 6. The primary improvement opportunities are:

1. **Token tracking is broken** — `estimated_tokens` is always `0` in SQLite, making the
   cross-server token report inaccurate.
2. **Same-day multi-session data is silently overwritten** — the DELETE+INSERT flush strategy
   loses earlier session data.
3. **Tool description bloat** — `file_write` description is 1,190 chars; a hardcoded absolute
   path is baked in; the editing workflow is duplicated in both description and SKILL.md.
4. **Several small patch accuracy gaps** remain — replace failure messages don't show line
   numbers, and there's no validation feedback from the `replace` nearest-match heuristic.
5. **Missing session ID alignment**  filesystem session IDs don't match the context server
   format, breaking cross-server JOIN queries.

**Update (v0.7.23):** All 5 P1 items implemented. P2 items 6, 7, 8, 10 implemented (item 9 still pending). P3 item 17 (estimatedTokens in stats) implemented. Four pre-existing test failures fixed (stale 7→8 tool count, version string, patch verbose flag).
---

## 1. Architecture — What Is Working Well

### 1.1 Tool-as-Service Pattern
Each tool is a Spring `@Service` implementing `ToolHandler`. The controller discovers them
reflectively at startup. This is clean and extensible — adding a new tool is a single class
with no changes elsewhere.

### 1.2 Safe Editing Protocol
The four-phase `doPatch` implementation is thorough:
- Phase 1: range validation + overlap detection
- Phase 2: bottom-up application (preserves original until Phase 3)
- Phase 3: atomic write via temp file + rename
- Phase 4: post-write verification (line count check + hash return)

The `expectedHash` drift guard on `replace`, `patch`, and `multi_replace` is correctly
optional but strongly signposted. The SKILL.md companion document explains the patterns well.

### 1.3 ChunkBuffer for Large Files
The 400KB chunk / 300KB threshold approach is sound. Session TTL (30 min) with scheduled
sweep prevents memory leaks. Chunk ordering via `ConcurrentSkipListMap` is correct.

### 1.4 StructureCache
Hash cached alongside structure, keyed by `(path, lastModified)` — clean invalidation.
Explicit invalidation after every write operation is correctly implemented.

### 1.5 Security Model
Path traversal protection, symlink control, windows reserved name detection, and command
whitelists are all present. The blocked-list-takes-precedence ordering is correct (fail-safe).

---

## 2. Token Usage Tracking — Critical Gaps

### 2.1 BUG: `estimated_tokens` is Always 0

**The problem:** Every row the filesystem server inserts into `token_usage` has
`estimated_tokens = 0`. The column exists but is never populated.

Evidence from the live DB:
```
id=5472  tool_name=server_lifecycle:status  estimated_tokens=0  response_bytes=322
id=5469  tool_name=file_write:replace       estimated_tokens=0  response_bytes=1417
```
Compare to context server rows:
```
id=5473  tool_name=context_lifecycle  estimated_tokens=204  response_bytes=816
```

The context server correctly populates `estimated_tokens` by dividing `response_bytes / 4`.
The filesystem server never does this, so the token-report from `tools action=stats` is
reporting `0` tokens for all filesystem operations.

**Fix:** In `flushToDb`, compute the estimate on insert:
```groovy
// In the INSERT batch loop, replace the literal 0:
long bytes = responseBytes[key]?.get() ?: 0L
int estimatedTokens = (int) Math.round(bytes / 4.0)
ins.setInt(4, count.get())
ins.setLong(5, estimatedTokens)   // was: ins.setLong(5, 0) - hardcoded zero
ins.setLong(6, bytes)
```
This is a one-line fix with high analytics value.

### 2.2 Same-Day Multi-Session Data Overwritten

**The problem:** `flushToDb` does `DELETE WHERE recorded_date=today AND context_layer=filesystem`
then re-inserts. Each session starts with fresh in-memory counters. If two filesystem sessions
run on the same day (restart, Desktop re-launch, etc.), the second session's flush **silently
erases the first session's data**.

Evidence: today's rows only show session `2026-03-01T13:55:50.924...` — any prior session
data from the same day is gone.

**Fix:** Use UPSERT to accumulate rather than replace:
```sql
INSERT INTO token_usage (recorded_date, session_id, tool_name, call_count, estimated_tokens, response_bytes, context_layer)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(recorded_date, tool_name, context_layer, session_id)
DO UPDATE SET
    call_count        = call_count + excluded.call_count,
    estimated_tokens  = estimated_tokens + excluded.estimated_tokens,
    response_bytes    = response_bytes + excluded.response_bytes
```
This requires adding a UNIQUE constraint on `(recorded_date, tool_name, context_layer, session_id)`.
Alternatively, tag each session with a unique sub-key and never DELETE.

### 2.3 Session ID Format Mismatch

**The problem:** Filesystem rows store session_id as `ISO_LOCAL_DATE_TIME` format:
`2026-03-01T13:55:50.9242093`

Context server rows store session_id as date-hour format:
`2026-03-01-13-58`

These two can never JOIN, making cross-server analytics (total tokens including both
filesystem and context server for a single Claude session) impossible.

**Fix:** At startup, look up the current context server session ID and use it. Or adopt
the same format: `LocalDateTime.now().format("yyyy-MM-dd-HH-mm")`.

### 2.4 Input Size Not Tracked

The `payloadSizeBytes` field is correctly captured in `McpRequestEvent` and available in
`onMcpEvent`. But it's never stored. For `file_write:write` with large content payloads,
the input side can be 50-100KB, which adds significantly to the Claude context window burn.

**Fix:** Add an `input_bytes` column to `token_usage` and populate it from `event.payloadSizeBytes`.

### 2.5 No Per-Call Telemetry (Repeat Detection)

The context server's `tool_call_telemetry` table records every individual call with
`is_repeat_call`, `args_hash`, and `called_at`. This enables detection of patterns like
"this file is read 10 times per session" which is a major token burn indicator.

The filesystem server only has daily aggregates. You can't tell whether `file_read:range`
being called 73 times yesterday was 73 different files or one file read 73 times.

**Recommendation:** Add a lightweight `tool_call_telemetry` equivalent — or better, reuse
the context server's existing table by logging filesystem calls into it too (with
`server_name='filesystem'`). This avoids schema duplication.

---

## 3. Tool Descriptions — Context Window Bloat

Every `tools/list` response (which happens on every new Claude context initialization)
includes all tool schemas. The description text alone is:

| Tool | Description chars | Schema chars (approx) |
|------|------------------|-----------------------|
| `file_read` | 876 | 950 |
| `file_write` | **1,192** | 1,050 |
| `file_list` | 230 | 420 |
| `file_search` | 210 | 380 |
| `file_lifecycle` | 160 | 320 |
| `execute` | 220 | 380 |
| `tools` | 420 | 450 |
| `server_lifecycle` | 175 | 250 |
| **Total** | **~3,483** | **~4,200** |

**~7,700 chars in every tools/list response = ~1,925 tokens per context init.**

### 3.1 `file_write` Description is Largest — and Has a Hardcoded Path

The description contains a full safe editing workflow tutorial (~500 chars) and ends with:
```
SKILL: For worked examples read:
  file_read action=read path=C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/skills/SKILL.md
```

This is **machine-specific** — it will break on any other machine, Docker container, or
if the project is cloned to a different directory. It also adds ~90 chars of absolute path
to every tools/list.

**Fix:** Replace the hardcoded path with a dynamic reference using `project_root`:
```
SKILL: For worked examples:
  file_read action=get_method path=<use file_read action=project_root to find SKILL.md>
```
Or better, store the SKILL path in `application.yml` and inject it at startup, so the
description is computed once and is always correct for the current machine.

### 3.2 Safe Editing Workflow is Duplicated

The same 3-pattern workflow appears verbatim in:
- The `file_write` tool description (shown to Claude every context init)
- `skills/SKILL.md` (read on-demand)

The `file_write` description should contain the **rules summary only** and point to
SKILL.md for the full worked examples. The current description repeats the step-by-step
patterns that are already in SKILL.md. This adds ~400 chars that Claude already gets from
SKILL.md when it matters.

**Proposed shorter `file_write` description** (saves ~500 chars):
```
Write, append, or modify file content. Actions:
- write(path, content): overwrite entire file
- append(path, content): append to end
- replace(path, options.oldText, options.newText): replace ONE unique string
- patch(path, options.replacements[]): line-range edits [{startLine,endLine,newText}]
- multi_replace(path, options.replacements[]): multiple ordered string swaps
- chunk_write/finalise_write/abort_write: chunked large-file writes
All mutating actions return content_hash. Pass options.expectedHash to guard against drift.
SAFE EDITING: ALWAYS use expectedHash. NEVER sequential replace calls (use multi_replace).
PREFER patch (line-addressed) over replace for files >100 lines.
Read SKILL.md for worked examples: file_read action=project_root then look for skills/SKILL.md.
```

### 3.3 `file_read` CRITICAL Block Adds Redundancy

The `file_read` description ends with a `CRITICAL FOR EDITING` block that duplicates
the `expectedHash` guidance already in the `file_write` description. Claude reads both
schemas at once. One location is enough.

**Suggestion:** Keep the file_read NOTE about hash return, shorten the CRITICAL block:
```
NOTE: read/head/tail/range/grep/get_method all return file_content_hash.
      Pass this as options.expectedHash on patch/replace/multi_replace to guard against drift.
```
(Remove the 3-line CRITICAL FOR EDITING paragraph.)

### 3.4 `tools` Description Subcommand List Bloat

The `tools` description lists all git subcommands: `status|log|diff|add|commit|push|pull|
branch|stash|clone|fetch|checkout|merge|show|tag|remote|reset|revert`.

Claude already knows git subcommands. Listing them adds ~120 chars with no real benefit.

**Suggestion:**
```
- git(subcommand, args[], options.workingDir, options.message): common git commands.
  IMPORTANT: commit requires options.message or process hangs.
```

### 3.5 `file_read` Has 18 Actions — Consider a Lookup Table Format

18 actions in one description with one line each is clear, but makes for a long description.
The action enum in the schema is the authoritative list. The description could be shorter:

**High-use actions** (describe fully): read, head, tail, range, grep, multi, structure, get_method
**Meta actions** (one line): info, summary, exists, project_root, allowed_dirs, normalize
**Utility actions** (mention): diff, checksum, chunk_read, finalise_read

---

## 4. Patch / Edit Accuracy — Remaining Gaps

### 4.1 `replace` Failure Doesn't Show Match Locations

When `doReplace` finds N > 1 occurrences, the error is:
```
Found 3 occurrences of oldText in '<file>' - text must be unique.
```

This tells Claude "it's not unique" but not **where** the matches are. Claude then has to
do a `grep` call to find them — an extra round-trip that adds latency and tokens.

**Fix:** When count > 1, include the first few line numbers:
```groovy
if (occurrences > 1) {
    List<Integer> lineNums = []
    content.eachLine { String l, int idx -> if (l.contains(oldText)) lineNums << (idx + 1) }
    return McpResponse.error(requestId, -32602,
        "Found ${occurrences} occurrences in '${path}' at lines ${lineNums.take(5).join(', ')}. " +
        "Use grep to find a unique anchor context, then use a larger oldText string.")
}
```

### 4.2 Hash Computed Three Times in `doPatch`

`doPatch` computes SHA-256:
1. For the drift guard (from `rawBytes` before patching)
2. After write for `resultHash` (reads file back from disk: `Files.readAllBytes(targetPath)`)
3. `Phase 4` also reads the file back from disk to check line count

That's **two full file reads after writing** plus one before. The result hash could be
computed from `assembled.getBytes(encoding)` without re-reading from disk, then the Phase 4
verification can also use that same byte array:

```groovy
byte[] resultBytes = assembled.getBytes(encoding)
atomicWrite(targetPath, resultBytes)
String resultHash = computeHash(resultBytes)
// Phase 4: verify using resultBytes directly (no second disk read)
String normWritten = new String(resultBytes, encoding).replace('\r\n', '\n').replace('\r', '\n')
```

This removes one `Files.readAllBytes` per patch call.

### 4.3 `patch` with Empty `newText` Is Documented as "Deletion" But Has Edge Case

When `newText` is `""` (or absent), the replacement removes the target lines. The code:
```groovy
List<String> newLines = newText ? [...split...] : [] as List<String>
lines[start..end] = newLines
```
This is correct for interior deletions. However, if the deleted range is the **entire file**
(start=1, end=N), `lines` becomes empty, `assembled` is `""` + trailing newline if
`hadTrailingNewline`. The result is a file with just a newline or truly empty. The Phase 4
verification handles this correctly (0 content lines) but the response still says
`success: true, applied: 1, result_lines: 0` which could surprise callers.

**Suggestion:** Add a `warn` flag in the response when `result_lines == 0`.

### 4.4 `normalizAndCheckPath` Typo in Method Name

`FileWriteService.groovy:658`: `normalizAndCheckPath` — missing 'e' in "normalize".
Inconsistent with `normalizePath` in `PathService`. Minor but worth fixing for readability
(it's a private method so no external API breakage).

### 4.5 `multi_replace` Doesn't Detect Overlapping String Replacements

`doMultiReplace` pre-validates that all `oldText` strings exist and are unique before
writing. But it doesn't check whether one `oldText` is a **substring of another** `oldText`,
which could cause the second replacement to operate on already-modified text if they overlap
in content (not just position).

Example:
```
replacements: [
  {oldText: "foo bar baz", newText: "X"},
  {oldText: "bar baz",     newText: "Y"}
]
```
After the first replacement, "bar baz" no longer exists. The second will fail (good) but
with a confusing "text not found" error rather than "these replacements have overlapping content".

**Fix:** After pre-validating existence, check that no `oldText` is a substring of another.

---

## 5. Performance Issues

### 5.1 `withConnection` Opens a New JDBC Connection Per Call

Every `flushToDb`, `loadTodayFromDb`, `buildPeriodStats`, and `ensureSchema` call opens
and closes a JDBC connection. SQLite JDBC has non-trivial connection overhead (~5-20ms per
open). For the periodic flush (every 10 min), this is fine. But `buildPeriodStats` is called
on every `tools action=stats` request, meaning every stats call pays connection-open cost.

**Fix:** Hold a single `Connection` open for the lifetime of the service (SQLite is embedded,
not a pool concern). Open in `@PostConstruct`, close in `@PreDestroy`.

### 5.2 `StructureCache` Synchronization

The cache uses `synchronized(structureCache)` on a `LinkedHashMap` (for LRU). All access
(get + put) is serialized under one lock. For concurrent `multi` reads hitting different
files, each file's structure lookup blocks the others.

**Fix:** Use `ConcurrentHashMap` with `computeIfAbsent` for the cache map, with per-entry
locking for the expensive scan operation. Or use Caffeine cache (already a common Spring
dependency).

### 5.3 `file_read:structure` Response Size

For large files, `structure` returns every method/field entry. A 700-line service class
might return 20-30 entries, each with `line`, `endLine`, `type`, `content`, `owner`. The
`content` field includes the full method signature (can be 80-100 chars). For a 30-method
class this is ~3KB of structure data.

**Suggestion:** Add a `compact` option to `structure` that returns only `line, endLine, type,
name` (strip the full signature content). This is useful when you just want line numbers for
a `patch` call without reading the full signature.

---

## 6. Code Quality

### 6.1 Version Comments Have Drifted

`FileReadService.groovy` header says `v0.0.7 Phase 2 Core File Tools` but the server is
at v0.7.22. Version comments in the service files are ~70 versions stale and misleading.
Either update them to track meaningful changes or remove the version from class comments
(the git log is the canonical version history).

### 6.2 `DEBUG` Environment Variable vs Spring Logging

`StdioMcpServer` uses `System.getenv("MCP_DEBUG")` for debug logging to stderr. The rest
of the codebase uses SLF4J with Spring profiles. These should be unified — either use
`log.isDebugEnabled()` (driven by `logback-spring.xml`) or document that `MCP_DEBUG` is a
STDIO-specific override for when logback output would corrupt the JSON-RPC stream.

Currently the comment doesn't explain this distinction. Someone unfamiliar with MCP's
STDIO constraint might be confused why there's a separate debug mechanism.

### 6.3 `McpRequestEvent` Has `payloadSizeBytes` That Is Never Used for Analytics

The event carries `payloadSizeBytes` (the incoming request size) but `onMcpEvent` in
`UsageTracker` never reads it. The field exists but serves no analytics purpose. Either
store it, or document that it's intentionally unused (diagnostics only, not analytics).

### 6.4 `tools:stats` Returns Bytes But Not Token Estimates

The public-facing stats response (what Claude sees when calling `tools action=stats`) returns
`totalBytes` and `totalKB` but not token estimates. Since `estimated_tokens` is always `0`
in the DB, even fixing the DB won't help the stats response until `getStats()` is also updated
to compute `estimatedTokens` from bytes:
```groovy
estimatedTokens: Math.round(mergedBytes / 4.0)
```

---

## 7. Missing Indexes on `token_usage`

The `buildPeriodStats` query runs:
```sql
SELECT tool_name, SUM(call_count), SUM(response_bytes)
FROM token_usage
WHERE context_layer = ? AND recorded_date >= ? AND recorded_date < ?
GROUP BY tool_name
```

There is **no index** on `(context_layer, recorded_date)`. For a small DB (~5000 rows) this
is a full table scan but fast. As the DB grows over months, this query will slow down.

**Fix:** Add in `ensureSchema`:
```sql
CREATE INDEX IF NOT EXISTS idx_token_usage_date_layer
    ON token_usage(recorded_date, context_layer);
```

---

## 8. Prioritised Action List

### P1  Fix, High Impact, Low Effort — ALL DONE ✅ (v0.7.23)

| # | Issue | Location | Status |
|---|-------|----------|--------|
| 1 | `estimated_tokens` always 0 | `UsageTracker.flushToDb` | ✅ Fixed — compute `round(bytes/4)` on insert |
| 2 | Same-day sessions overwrite each other | `UsageTracker.flushToDb` | ✅ Fixed — UPSERT with UNIQUE constraint |
| 3 | `doPatch` double disk read after write | `FileWriteService.doPatch` | ✅ Fixed — hash/verify from `resultBytes` (no re-read) |
| 4 | Missing DB index on `token_usage` | `UsageTracker.ensureSchema` | ✅ Fixed — composite + UNIQUE indexes added |
| 5 | `normalizAndCheckPath` typo | `FileWriteService` | ✅ Fixed — renamed to `normalizeAndCheckPath` |

### P2  Improve, Medium Impact, Medium Effort

| # | Issue | Location | Status |
|---|-------|----------|--------|
| 6 | Hardcoded SKILL.md path in `file_write` description | `FileWriteService.getToolDefinitions` | ✅ Fixed — dynamic `pathService.activeProjectRoot + /skills/SKILL.md` |
| 7 | `replace` failure doesn't show match line numbers | `FileWriteService.doReplace` | ✅ Fixed — exact line numbers added to count>1 error |
| 8 | Session ID format mismatch | `UsageTracker` (flushToDb + stats) | ✅ Fixed — `yyyy-MM-dd-HH-mm` format throughout |
| 9 | Input bytes not tracked | `UsageTracker.onMcpEvent` | ⏳ Pending — needs `input_bytes` schema column |
| 10 | `file_write` description ~500 chars bloat | `FileWriteService.getToolDefinitions` | ✅ Fixed — trimmed, workflow replaced with 2-line summary |

### P3 — Nice to Have, Lower Priority

| # | Issue | Location | Fix |
|---|-------|----------|-----|
| 11 | `tools` description lists all git subcommands (120 chars) | `ToolsService.getToolDefinitions` | Shorten (Claude knows git) |
| 12 | `file_read` CRITICAL block duplicates `file_write` guidance | `FileReadService.getToolDefinitions` | Remove or shorten to 1 line |
| 13 | `withConnection` opens new JDBC connection per call | `UsageTracker` | Keep single open connection |
| 14 | `StructureCache` lock contention on multi reads | `StructureCache` | ConcurrentHashMap + per-entry lock |
| 15 | `multi_replace` doesn't check substring overlap | `FileWriteService.doMultiReplace` | Add overlap content check |
| 16 | Per-call telemetry (repeat call detection) | New | Log into existing `tool_call_telemetry` table |
| 17 | `tools:stats` response doesn't show token estimates | `UsageTracker.buildTodayStats` | ✅ Fixed (v0.7.23) — `estimatedTokens` added to today + period stats |
| 18 | `structure` action has no compact mode | `FileReadService.handleStructure` | Add compact=true omitting full signature |

---

## 9. Token Burn — Summary of What Is and Isn't Being Captured

```
What IS correctly tracked:
  ✓ Response bytes per tool:action (live in-memory + SQLite flush)
  ✓ Bounded vs full-read classification
  ✓ Tool call counts per session
  ✓ Periodic flush (10 min) + shutdown flush

What IS tracked correctly (after v0.7.23):
   Response bytes + estimated_tokens per tool:action (in-memory + SQLite flush)
   Bounded vs full-read classification, tool call counts, periodic flush
   Session IDs aligned to context server format (yyyy-MM-dd-HH-mm)
   tools:stats includes estimatedTokens field in response
What IS NOT tracked (remaining gaps):
   Input/request bytes (payloadSizeBytes) not stored — P2-9, pending
   Per-call granularity (repeat read detection impossible) — P3-16
```

The practical effect: when you call `tools action=stats period=week`, the numbers show
`totalBytes` accurately but `estimatedTokens = 0` everywhere, making it impossible to
compare filesystem token burn vs context server token burn in one report.

---

## 10. What Is Working Well — Don't Change

- The 8-tool split is well-balanced; no tool is too large or too small
- `file_list:children` vs `tree` vs `list` tiering is excellent for bounded queries
- `get_method` + `patch` as the primary editing pattern is the right design
- The SKILL.md companion document is well-written and referenced correctly from Claude's system prompt
- `compact` defaults on write responses (tiny JSON by default, verbose on request) is correct
- Atomic write via temp + rename is correct and safe
- `ChunkBufferService` TTL and sweep are correctly implemented
- Virtual thread Promise-based multi-file read is correct and efficient
- The `@EventListener` + `McpRequestEvent` pipeline is clean and non-intrusive

---
_End of assessment_
