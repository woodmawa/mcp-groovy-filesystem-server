# Filesystem Server v0.7.25 — Work Brief
_Date: 2026-03-01 | Current version: v0.7.24_

---

## Background

Three improvement passes were completed today (v0.7.22 → v0.7.23 → v0.7.24).
This brief covers the remaining items identified from telemetry analysis and the
deferred P3 items from `codebase-assessment.md`.

---

## Item 1 — Response-size warning on large `file_read` payloads (HIGH PRIORITY)

**File:** `FileReadService.groovy` → `doRead()`, `doHead()`, `doRange()`

**Problem:** Telemetry shows `file_read` responses of 73k, 56k, 46k, 44k chars in single
calls — these are the primary cause of context window resets. The existing 300KB chunking
threshold guards against very large files but these reads are well under 300KB so they
sail through with no warning.

The context window fills gradually, and a 70k char read is ~17,500 tokens in one shot.

**Fix:** After assembling the response content, check its size and append a soft warning
if it exceeds a threshold:
```groovy
// Suggested threshold: ~15KB (approx 3,750 tokens)
private static final int LARGE_RESPONSE_WARN_CHARS = 15_000

// In doRead() / doHead() / doRange() — after content assembled:
if (content.length() > LARGE_RESPONSE_WARN_CHARS) {
    int kb = Math.round(content.length() / 1024.0f)
    responseMap._sizeWarning = "NOTE: response is ${kb}KB (~${Math.round(content.length()/4)} tokens). " +
        "Consider head/range/grep for targeted reads to preserve context window."
}
```

Make the threshold configurable via `application.yml`:
```yaml
mcp.filesystem.large-response-warn-chars: 15000
```

Apply to: `doRead`, `doHead`, `doRange`. The `doGrep` action is already targeted so
less of a concern, but could include it for completeness.

---

## Item 2 — P3-14: `StructureCache` lock contention

**File:** `StructureCache.groovy`

**Problem:** Current implementation uses a single coarse lock over the entire cache
map. Under concurrent access (multiple structure calls in quick succession), all threads
queue behind one lock.

**Fix:** Replace with `ConcurrentHashMap` and use `computeIfAbsent` for per-entry
locking — only blocks threads competing for the same file, not unrelated files.
```groovy
// Before: synchronized(lock) { cache.computeIfAbsent(...) }
// After:  cache.computeIfAbsent(key) { computeStructure(it) }
// ConcurrentHashMap.computeIfAbsent is atomic per-key
```

Note: lower priority since single-client usage rarely has true concurrency here.

---

## Item 3 — P3-18: `structure` compact mode

**File:** `FileReadService.groovy` → `doStructure()`

**Problem:** Structure responses for large files can be verbose (every method/field
with line numbers). No way to get a condensed outline.

**Fix:** When `options.compact = true`, return only method/function entries (skip
fields, imports, class declarations) and omit `endLine`. Reduces structure response
size by ~50% for files with many fields.

---

## Item 4 — P3-16: Per-call telemetry table (DESIGN FIRST)

**Note: Do not implement in this pass — design separately.**

This is a new feature (adding a `tool_call_telemetry` table to the filesystem server's
SQLite DB, similar to the context server's implementation). Needs design decisions:
- Should both servers share the same `best_practices.db`? (context server already does)
- Or does the filesystem server maintain its own DB?
- What's the write pattern — sync or async?

Revisit after context server telemetry improvements are shipped and working.

---

## Build & Release

After implementing Items 1-3:
1. Run `gradle test` — verify 0 failures
2. Bump version to `v0.7.25` in `build.gradle`
3. Run `gradle bootJar`
4. Copy JAR to `C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-0.7.25.jar`
5. Update Desktop config: `C:/Users/willw/AppData/Roaming/Claude/claude_desktop_config.json`
6. Update Claude Code config: `C:/Users/willw/.claude.json`
7. Git commit `v0.7.25: response-size warning, StructureCache per-entry lock, structure compact mode`
8. Git tag `v0.7.25` and push
9. Write `improvements-summary4.md` in project root

---

## Priority Order

| # | Item | Priority |
|---|------|----------|
| 1 | Response-size warning on large reads | High — directly reduces context resets |
| 2 | StructureCache per-entry lock | Low — minimal real-world impact single-client |
| 3 | structure compact mode | Low — nice to have |
| 4 | Per-call telemetry | Defer — needs design |
