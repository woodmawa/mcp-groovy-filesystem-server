# v0.7.25 Improvements Summary

_Date: 2026-03-01_

---

## Item 1 — Response-size warning on large `file_read` payloads

**Files:** `FileReadService.groovy`, `application.yml`

Large reads (73k, 56k, 46k chars observed in telemetry) are the primary cause of context window resets.
The 300KB chunking threshold only guards against very large files; reads under 300KB sailed through
with no feedback.

**Changes:**
- Added `@Value('${mcp.filesystem.large-response-warn-chars:15000}') int largeResponseWarnChars`
- Added `maybeAddSizeWarning(Map response, int contentLength)` helper: appends `_sizeWarning` field
  when content exceeds threshold: `"NOTE: response is NKB (~M tokens). Consider head/range/grep..."`
- Applied to `doRead`, `doHead`, `doRange`
- Threshold configurable via `mcp.filesystem.large-response-warn-chars: 15000` in `application.yml`
  (default 15 000 chars ≈ 3 750 tokens)

---

## Item 2 — `StructureCache` per-entry lock

**File:** `StructureCache.groovy`

Under concurrent access, two threads hitting the same uncached file would both compute the structure/hash
(wasted work). All reads of different files were already uncontested since `cache` uses `ConcurrentHashMap`.

**Changes:**
- Added `ConcurrentHashMap<String, Object> computeLocks` for per-path mutex objects
- `getStructure()`: acquires `computeLocks.computeIfAbsent(path)` lock before compute; double-checks
  cache inside the lock (another thread may have populated it while waiting)
- `getHash()`: same pattern applied
- Threads for different files remain fully parallel; only threads competing for the **same** file serialize

---

## Item 3 — `structure` compact mode

**File:** `FileReadService.groovy`

Structure responses for large files list every class, method, and field. No compact alternative existed.

**Changes:**
- `doStructure` now accepts `Map<String, Object> options` (passed through from `handleToolCall`)
- When `options.compact = true`: returns only `method`-type entries, each with `line`, `type`, `content`
  — `endLine` omitted. Reduces response size by ~50% for files with many fields/class declarations
- Tool description updated: `structure(path): ... options.compact=true returns methods only (no endLine, ~50% smaller)`
- `compact` option description updated to list `structure` as a supported action
- `file_content_hash` included in compact structure response

---

## Build

- Version bumped: `0.7.24` → `0.7.25`
- JAR copied to `C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-0.7.25.jar`
- Both configs updated (Desktop + Claude Code) to reference `0.7.25.jar`
- All tests pass (5 actionable tasks, 0 failures)
