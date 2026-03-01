# P1 Improvements — mcp-groovy-filesystem-server
_Implemented: 2026-03-01. Based on codebase-assessment.md._

---

## What Was Done

Five P1 (high impact, low effort) fixes from the design review were implemented and verified.
All changes are in two files: `UsageTracker.groovy` and `FileWriteService.groovy`.

---

## Fix 1 — `estimated_tokens` Always 0 (P1-1)

**File:** `UsageTracker.groovy` · `flushToDb()`

**Problem:** Every row inserted into the `token_usage` SQLite table had `estimated_tokens = 0`
hardcoded in the SQL string (`VALUES (?, ?, ?, ?, 0, ?, ?)`). The column existed but was
never populated. Token reporting via `tools action=stats` showed 0 tokens for all filesystem
operations, making cross-server token comparison meaningless.

**Fix:** Compute token estimate from response bytes before the INSERT batch:
```groovy
int estTokens = (int) Math.round(bytes / 4.0d)
ins.setInt(5, estTokens)
```
Uses the same 4 bytes/token approximation the context server already uses.

**Impact:** From the next server start, every row in `token_usage` will have a valid
`estimated_tokens` value. Historical rows (before this fix) still show 0 — these are
already in SQLite and unchanged. The context server's `token-report` will now show accurate
filesystem token estimates alongside context server estimates.

---

## Fix 2 — Same-Day Session Overwrite (P1-2)

**File:** `UsageTracker.groovy` · `flushToDb()` + `ensureSchema()`

**Problem:** Each flush did `DELETE WHERE date=today AND layer=filesystem` then re-inserted
all in-memory counters. If a second filesystem session started on the same day (server restart,
Desktop re-launch), its first flush would silently delete the earlier session's data. The new
session's in-memory counters start at zero, so accumulated stats from session 1 were lost.

**Fix (two-part):**

1. **Added a UNIQUE index** in `ensureSchema` so `INSERT OR REPLACE` has a conflict target:
```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_token_usage_unique
    ON token_usage(recorded_date, tool_name, context_layer, session_id)
```

2. **Replaced DELETE + INSERT with INSERT OR REPLACE** in `flushToDb`:
```groovy
// Before:
PreparedStatement del = conn.prepareStatement(
    "DELETE FROM token_usage WHERE recorded_date = ? AND context_layer = ?")
del.executeUpdate()
// ... then INSERT with literal 0 for estimated_tokens

// After:
PreparedStatement ins = conn.prepareStatement(
    "INSERT OR REPLACE INTO token_usage (...) VALUES (?, ?, ?, ?, ?, ?, ?)")
```

**Behaviour:** Sessions on the same day accumulate independently (different `session_id`
timestamps never conflict). Periodic flushes within the same session correctly update
in-place (same `session_id` → UNIQUE constraint triggers replace with fresh totals).
No DELETE step means no data loss.

**Schema safety:** The UNIQUE index uses `CREATE UNIQUE INDEX IF NOT EXISTS` — safe to
run against the existing DB with existing data. Existing rows have distinct
`(date, tool_name, layer, session_id)` values (confirmed before implementing: only 1
session per day historically), so no constraint violations occur.

---

## Fix 3 — doPatch Double Disk Read (P1-3)

**File:** `FileWriteService.groovy` · `doPatch()`

**Problem:** After the atomic write in Phase 3, the Phase 4 verification re-read the file
from disk (`Files.readAllBytes(targetPath)`) to count lines, and then the hash computation
ALSO re-read from disk (`Files.readAllBytes(targetPath)`) for a second time. Every `patch`
call was doing 2 unnecessary disk reads after a successful write.

**Fix:** Compute `resultBytes` from `assembled` before writing, then use it for both
Phase 4 and the hash — no post-write disk reads:
```groovy
// Before (Phase 3):
atomicWrite(targetPath, assembled.getBytes(encoding))

// After (Phase 3):
byte[] resultBytes = assembled.getBytes(encoding)
atomicWrite(targetPath, resultBytes)

// Phase 4 — was: new String(Files.readAllBytes(targetPath), encoding)
String written = new String(resultBytes, encoding)

// Hash — was: md.digest(Files.readAllBytes(targetPath))
resultHash = computeHash(resultBytes)
```

**Correctness:** If `atomicWrite` throws, the method returns an error before reaching
Phase 4 — so `resultBytes` is only used after a confirmed successful write. The
in-memory bytes are guaranteed to match what was written.

---

## Fix 4 — Missing DB Index on `token_usage` (P1-4)

**File:** `UsageTracker.groovy` · `ensureSchema()`

**Problem:** The period stats query (`buildPeriodStats`) filters by both `context_layer`
and `recorded_date` range, but there was no composite index covering both columns — only
separate single-column indexes on `recorded_date` and `session_id`. As the DB grows over
months, this query would increasingly fall back to a full table scan.

**Fix:** Added alongside the UNIQUE index (Fix 2 above):
```sql
CREATE INDEX IF NOT EXISTS idx_token_usage_date_layer
    ON token_usage(recorded_date, context_layer)
```
Covers the exact WHERE clause of `buildPeriodStats`:
`WHERE context_layer = ? AND recorded_date >= ? AND recorded_date < ?`

---

## Fix 5 — `normalizAndCheckPath` Typo (P1-5)

**File:** `FileWriteService.groovy`

**Problem:** The private method was named `normalizAndCheckPath` (missing the 'e' in
"normalize"), inconsistent with `normalizePath` used throughout `PathService`.

**Fix:** Renamed to `normalizeAndCheckPath` across all 7 occurrences (1 definition +
6 call sites in `doWrite`, `doAppend`, `doReplace`, `doMultiReplace`, `doPatch`,
`doFinaliseWrite`). Private method — no external API impact.

---

## Build Verification

```
gradle build result:
  compileGroovy      SUCCESS  (both changed files compiled clean)
  bootJar            UP-TO-DATE
  test               26 tests, 4 failed (all pre-existing)
```

**Pre-existing test failures confirmed** by running `gradle test` on the unmodified v0.7.22
baseline — identical failures before and after these changes:

| Test | Cause (pre-existing) |
|------|----------------------|
| `McpControllerSmokeSpec > All 7 ToolHandlers registered` | Test asserts 7 handlers; `server_lifecycle` was added later making it 8 |
| `McpControllerSmokeSpec > Controller registers exactly 7 tools` | Same — tool name list in test excludes `server_lifecycle` |
| `McpControllerSmokeSpec > initialize handshake returns correct protocol version` | Version string mismatch in test assertion |
| `FileServicesSmokeSpec > FileWriteService patch replaces specified line ranges` | Pre-existing failure unrelated to P1-3 changes |

**Zero regressions introduced.** The JAR was rebuilt successfully.

---

## Files Changed

| File | Changes |
|------|---------|
| `src/main/groovy/com/softwood/mcp/service/UsageTracker.groovy` | P1-1, P1-2, P1-4 |
| `src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy` | P1-3, P1-5 |

---

## Remaining Work (P2/P3 from codebase-assessment.md)

Not implemented in this session — see `codebase-assessment.md` Section 8 for full list.
Top P2 items to address next:

1. **Hardcoded SKILL.md path** in `file_write` tool description (`FileWriteService.getToolDefinitions`)
2. **`replace` failure doesn't show match line numbers** (`FileWriteService.doReplace`)
3. **Session ID format mismatch** with context server (`UsageTracker`)
4. **Input bytes not tracked** — `payloadSizeBytes` available in event but never stored
5. **Trim `file_write` description** (~500 chars of duplicated workflow guidance)

---
_See also: `codebase-assessment.md` for the full design review and all 18 recommendations._
