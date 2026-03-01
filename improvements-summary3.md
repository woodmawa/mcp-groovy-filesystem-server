# Improvements Summary — v0.7.24
_Implemented: 2026-03-01. Third pass — P2-9, P3-11, P3-12, P3-13, P3-15 + file_write description fixes._

---

## What Was Done

Eight changes across four files. Four items from the original `codebase-assessment.md` roadmap
(P2-9, P3-11, P3-13, P3-15), one item promoted from the assessment (P3-12), and two small
`file_write` description clarifications requested mid-session. All verified with a clean
`gradle test` run (26 tests, 0 failures).

---

## Fix 1 — Input Bytes Tracking Added (P2-9)

**File:** `src/main/groovy/com/softwood/mcp/service/UsageTracker.groovy`

**Problem:** `McpRequestEvent.payloadSizeBytes` (the incoming request payload size) was carried on
every event but never read by `UsageTracker`. Input bytes were invisible to analytics — the stats
response showed output (response) bytes only, giving a one-sided picture.

**Changes:**
- New field `inputBytes` (`ConcurrentHashMap<String, AtomicLong>`) per-key map, plus `totalInputBytes` aggregate
- `onMcpEvent`: reads `event.payloadSizeBytes` → `inSize`, increments both maps
- `ensureSchema`: adds `input_bytes INTEGER DEFAULT 0` to the `CREATE TABLE IF NOT EXISTS`
  statement; tries `ALTER TABLE token_usage ADD COLUMN input_bytes INTEGER DEFAULT 0` to
  migrate any pre-existing table (catches `duplicate column name` and ignores it)
- `flushToDb`: INSERT now includes `input_bytes` as column 7 (9th param total)
- `loadTodayFromDb`: SELECT adds `SUM(input_bytes) as ibytes`; loads into `inputBytes` map and `totalInputBytes`
- `buildTodayStats`: adds `totalInputBytes` to response map; per-action breakdown adds `inputKB`
- `buildPeriodStats`: SELECT adds `SUM(input_bytes) as ibytes`; merges live + DB values; adds `totalInputBytes` and per-action `inputKB` to response
- `checkDateRollover`: clears `inputBytes` and resets `totalInputBytes` on day rollover

**Stats response now includes:**
```json
{
  "totalBytes": 124800,
  "totalKB": 122,
  "estimatedTokens": 31200,
  "totalInputBytes": 18432,
  "perAction": [
    { "key": "file_read:read", "calls": 14, "responseKB": 88, "estTokens": 22400, "inputKB": 3 }
  ]
}
```

---

## Fix 2 — Single Held JDBC Connection (P3-13)

**File:** `src/main/groovy/com/softwood/mcp/service/UsageTracker.groovy`

**Problem:** Every `flushToDb`, `loadTodayFromDb`, `buildPeriodStats`, and `ensureSchema` call
opened and closed a JDBC connection. SQLite JDBC has ~5–20ms connection-open overhead.
`buildPeriodStats` is called on every `tools action=stats` request, meaning every stats call
paid this overhead unnecessarily.

**Fix:**
- New fields: `private volatile Connection dbConn` + `private final Object dbLock`
- `@PostConstruct init()`: opens the connection once: `Class.forName('org.sqlite.JDBC'); dbConn = DriverManager.getConnection(...)`
- `@PreDestroy shutdown()`: closes the connection after the final flush: `try { dbConn?.close() } catch (Exception ignored) {}`
- `withConnection(Closure)`: now uses the shared `dbConn` inside `synchronized(dbLock)` block instead of opening/closing per-call

**Thread safety:** All callers use `withConnection`, which serialises under `dbLock`.
`flushToDb` uses `autoCommit = false` transactions — safe because no other thread can enter
the synchronized block while a transaction is in progress.

**Savings:** Eliminates JDBC open/close overhead on every stats request. Connection open
now happens once at startup.

---

## Fix 3 — `multi_replace` Content Overlap Validation (P3-15)

**File:** `src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy` · `doMultiReplace()`

**Problem:** `doMultiReplace` pre-validated that each `oldText` exists exactly once in the file.
But if `oldText[j]` is a substring of `oldText[i]`, applying entry `i` first removes the content
that entry `j` was targeting. Entry `j` then fails with a confusing "not found" error, even
though both entries passed the pre-validation. The error message gave no hint about the real cause.

**Example (before):**
```
replacements: [
  {oldText: "foo bar baz", newText: "X"},   // entry 0 — passes: found 1x
  {oldText: "bar baz",     newText: "Y"}    // entry 1 — passes: found 1x
]
// Apply: entry 0 replaces "foo bar baz" → "X". "bar baz" gone.
// Entry 1 fails: "oldText not found" ← confusing
```

**Fix:** After the existence/uniqueness pre-validation, a second pass checks all pairs:
```groovy
if (!validationErrors) {
    List<String> oldTexts = replacements.collect { (it.oldText as String) ?: '' }.findAll { it }
    for (int i = 0; i < oldTexts.size() - 1; i++) {
        for (int j = i + 1; j < oldTexts.size(); j++) {
            if (oldTexts[i].contains(oldTexts[j])) {
                validationErrors << "Entry ${j}: oldText is a substring of entry ${i} — ..."
            } else if (oldTexts[j].contains(oldTexts[i])) {
                validationErrors << "Entry ${i}: oldText is a substring of entry ${j} — ..."
            }
        }
    }
}
```

**Error now reads:**
```
multi_replace validation failed (file NOT modified):
  Entry 1: oldText is a substring of entry 0 — replacements overlap in content and will interact unexpectedly
```

The check runs only if basic validation passes (avoids noise when there are already missing/duplicate errors).

---

## Fix 4 — `tools` Description Git Subcommand List Trimmed (P3-11)

**File:** `src/main/groovy/com/softwood/mcp/service/ToolsService.groovy`

**Problem:** The `tools` description listed all 19 git subcommands verbatim:
`status|log|diff|add|commit|push|pull|branch|stash|clone|fetch|checkout|merge|show|tag|remote|reset|revert`
This adds ~100 chars with no real benefit — Claude already knows git subcommands, and the
runtime whitelist in `doGit()` is the authoritative constraint.

**Fix:**
```
Before: ...status|log|diff|add|commit|push|pull|branch|stash|clone|fetch|checkout|merge|show|tag|remote|reset|revert. IMPORTANT: commit requires options.message or process hangs.
After:  ...common git commands. IMPORTANT: commit requires options.message or process hangs.
```

**Savings:** ~100 chars removed from the `tools` tool description.

---

## Fix 5 — `file_read` CRITICAL Block Removed (P3-12)

**File:** `src/main/groovy/com/softwood/mcp/service/FileReadService.groovy`

**Problem:** The `file_read` description ended with two blocks covering `expectedHash`:

1. A NOTE: `"read/head/tail/.../get_method all return file_content_hash... Pass as options.expectedHash..."`
2. A CRITICAL FOR EDITING paragraph repeating the same guidance more urgently:
   `"Every read response includes file_content_hash. You MUST capture this hash and pass it..."`

Both blocks conveyed identical information. The CRITICAL paragraph is also present in the
`file_write` description, so Claude was seeing it duplicated across both tool schemas.

**Fix:** Removed the 3-line `CRITICAL FOR EDITING` paragraph entirely. Kept the NOTE.

**Savings:** ~150 chars removed from `file_read` tool description.

---

## Fix 6 — `file_write` Description Clarifications

**File:** `src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy`

Two small description improvements:

**patch action line:**
```
Before: ...line-range edits [{startLine,endLine,newText}], 1-indexed. ALWAYS read exact lines first.
After:  ...line-range edits [{startLine,endLine,newText}], 1-indexed, both startLine AND endLine required. ALWAYS read exact lines first.
```
Clarifies that both fields must be present (a common source of errors when only `startLine` is provided).

**replace action line:**
```
Before: ...replace ONE unique string. Fails with nearest_match hint if not found.
After:  ...replace ONE unique string. Not-found returns nearest_match hint; duplicate returns line numbers.
```
Documents both failure modes explicitly. Previously the description only described the
"not found" path; the count>1 case (which returns line numbers, not a nearest_match hint)
was undocumented in the tool schema.

---

## Build Verification

```
gradle test result:
  compileGroovy      SUCCESS
  test               26 tests, 0 failures
  BUILD SUCCESSFUL

gradle bootJar result:
  bootJar            SUCCESS  (mcp-groovy-filesystem-server-0.7.24.jar, 55.4 MB)
```

**Zero regressions.**

---

## Files Changed

| File | Changes |
|------|---------|
| `src/main/groovy/.../UsageTracker.groovy` | Fix 1, Fix 2 — input_bytes tracking + single JDBC connection |
| `src/main/groovy/.../ToolsService.groovy` | Fix 4 — git subcommand list trimmed |
| `src/main/groovy/.../FileReadService.groovy` | Fix 5 — CRITICAL block removed |
| `src/main/groovy/.../FileWriteService.groovy` | Fix 3, Fix 6 — multi_replace overlap check + description fixes |
| `build.gradle` | Version 0.7.23 → 0.7.24 |

---

## Remaining Work (P3 from codebase-assessment.md)

| # | Item | Notes |
|---|------|-------|
| P3-14 | `StructureCache` lock contention | ConcurrentHashMap + per-entry lock (or Caffeine) |
| P3-16 | Per-call telemetry / repeat detection | Log into `tool_call_telemetry` table |
| P3-18 | `structure` compact mode | Add compact=true option |

---
_See also: `codebase-assessment.md` for the full design review, `improvements-summary.md` for v0.7.22 P1 fixes, `improvements-summary2.md` for v0.7.23 P2 fixes._
