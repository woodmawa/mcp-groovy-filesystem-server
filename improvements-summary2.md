# Improvements Summary — v0.7.23
_Implemented: 2026-03-01. Second pass — P2 quick wins + test fixes._

---

## What Was Done

Eight fixes across four files: four pre-existing test failures resolved, four P2 quick
wins from `codebase-assessment.md` implemented, plus P3 item 17 (token estimates in stats
response). All changes verified with a clean `gradle test` run (26 tests, 0 failures).

---

## Fix 1 — Stale Tool Count in McpControllerSmokeSpec (Test)

**File:** `src/test/groovy/com/softwood/mcp/controller/McpControllerSmokeSpec.groovy`

**Problem:** Three assertions were written when there were 7 tools, but `server_lifecycle`
was added later making 8. Tests failed with count mismatch and a stale version string
(`'0.7.3'` vs `'dev'` — `SERVER_VERSION` reads from JAR manifest which is null in tests).

**Fixes (3 assertions):**
- `toolHandlers.size() == 7` → `== 8`
- `tools.size() == 7` → `== 8`; added `'server_lifecycle'` to expected names set
- `version == '0.7.3'` → `== 'dev'` (manifest-based version is null in test context)

---

## Fix 2 — Patch Test Needed `verbose: true` (Test)

**File:** `src/test/groovy/com/softwood/mcp/service/FileServicesSmokeSpec.groovy`

**Problem:** The patch test asserted `result.original_lines == 5` and `result.result_lines == 5`,
but `isWriteCompact()` returns `true` by default, omitting those fields from the response.
The test was written before compact mode became the default.

**Fix:** Added `verbose: true` to the test's `options` map:
```groovy
options: [verbose: true, replacements: [
    [startLine: 2, endLine: 3, newText: 'replaced2\nreplaced3']
]]
```

---

## Fix 3 — `replace` Count>1 Error Now Shows Line Numbers (P2-7)

**File:** `src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy` · `doReplace()`

**Problem:** When `oldText` appeared more than once, the error was:
```
replace: oldText appears 3 times (must be unique). Provide more context.
```
Claude had to do an extra `grep` call to find where the matches were — wasted round-trip.

**Fix:** Added a loop to collect exact starting line numbers for each occurrence:
```groovy
if (count > 1) {
    List<Integer> matchLines = new ArrayList<Integer>()
    int searchFrom = 0
    while (searchFrom < current.length()) {
        int idx = current.indexOf(oldText, searchFrom)
        if (idx < 0) break
        matchLines.add(current.substring(0, idx).count('\n') + 1)
        searchFrom = idx + 1
    }
    String lineInfo = matchLines.isEmpty() ? '' : (' at lines ' + matchLines.join(', '))
    return McpResponse.error(requestId, -32602,
        ('replace: oldText appears ' + count + ' times' + lineInfo + ' (must be unique). Provide more context.'))
}
```

**Error now reads:** `replace: oldText appears 3 times at lines 45, 112, 203 (must be unique)...`

---

## Fix 4 — `file_write` Description Trimmed + SKILL.md Path Dynamic (P2-6 + P2-10)

**File:** `src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy` · `getToolDefinitions()`

**Problem 1 (P2-10):** The description contained a ~500-char verbatim copy of the safe editing
workflow (3 step-by-step patterns + CRITICAL RULES), which is already in SKILL.md. This added
unnecessary tokens to every `tools/list` response.

**Problem 2 (P2-6):** The SKILL.md reference used a hardcoded absolute path:
```
path=C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/skills/SKILL.md
```
This breaks on any other machine, Docker container, or cloned project.

**Fix:** Restructured `getToolDefinitions()` to compute the path dynamically and replace
the verbose workflow block with a 2-line summary:
```groovy
String root = pathService.activeProjectRoot?.replace('\\', '/') ?: ''
String skillPath = root ? (root + '/skills/SKILL.md') : 'skills/SKILL.md'
// Description now ends with:
// SAFE EDITING: always pass expectedHash (hash from last read). For targeted code edits:
//   get_method -> patch. For unique strings: grep to confirm -> replace. For multiple
//   changes: multi_replace. NEVER sequential replace calls without re-reading.
// SKILL: For worked examples read:
//   file_read action=read path=${skillPath}
```

**Savings:** ~400 chars removed from the `file_write` tool description (down from ~1,190 to ~790 chars).

---

## Fix 5 — Session ID Format Aligned to Context Server (P2-8)

**File:** `src/main/groovy/com/softwood/mcp/service/UsageTracker.groovy`

**Problem:** Filesystem server stored session IDs as `ISO_LOCAL_DATE_TIME`:
`2026-03-01T13:55:50.9242093`

Context server stores session IDs as: `2026-03-01-13-58`

These never match, making cross-server SQL JOINs impossible for analytics.

**Fix:** Changed both locations (`flushToDb` DB insert and `buildTodayStats` stats response)
to use `yyyy-MM-dd-HH-mm` format:
```groovy
// Before:
sessionStart.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
// After:
sessionStart.format(DateTimeFormatter.ofPattern('yyyy-MM-dd-HH-mm'))
```

---

## Fix 6 — `estimatedTokens` Added to Stats Responses (P3-17, promoted)

**File:** `src/main/groovy/com/softwood/mcp/service/UsageTracker.groovy`

**Problem:** The `tools action=stats` response showed `totalBytes` and `totalKB` but not
token estimates. Even after P1-1 fixed the DB storage, the live stats response Claude reads
still showed no token estimates.

**Fix:** Added `estimatedTokens` to both `buildTodayStats()` and `buildPeriodStats()` return maps,
and `estTokens` to each per-action breakdown entry:
```groovy
// Return map (today + period):
estimatedTokens: Math.round(totalBytes.get() / 4.0d),

// Per-action breakdown:
estTokens: Math.round(bytes / 4.0d)
```

---

## Build Verification

```
gradle test result:
  compileGroovy      SUCCESS
  test               26 tests, 0 failures
  BUILD SUCCESSFUL

gradle build result:
  bootJar            SUCCESS  (mcp-groovy-filesystem-server-0.7.23.jar, 55.3 MB)
```

**All 4 pre-existing failures resolved. Zero regressions.**

---

## Files Changed

| File | Changes |
|------|---------|
| `src/test/groovy/.../McpControllerSmokeSpec.groovy` | Fix 1 — tool count (7→8), names set, version string |
| `src/test/groovy/.../FileServicesSmokeSpec.groovy` | Fix 2 — add `verbose: true` to patch test |
| `src/main/groovy/.../FileWriteService.groovy` | Fix 3, Fix 4 — doReplace line numbers, description trim + dynamic SKILL.md path |
| `src/main/groovy/.../UsageTracker.groovy` | Fix 5, Fix 6 — session ID format, estimatedTokens in stats |
| `build.gradle` | Version 0.7.22 → 0.7.23 |
| `codebase-assessment.md` | Updated P1/P2/P3 tables with status, exec summary update note |

---

## Remaining Work (P2/P3 from codebase-assessment.md)

| # | Item | Notes |
|---|------|-------|
| P2-9 | Input bytes not tracked | Needs new `input_bytes` column in `token_usage` schema |
| P3-11 | `tools` description git subcommand list | Minor trim (~120 chars) |
| P3-12 | `file_read` CRITICAL block redundancy | Remove or shorten |
| P3-13 | `withConnection` opens new JDBC per call | Hold single open connection |
| P3-14 | `StructureCache` lock contention | ConcurrentHashMap + per-entry lock |
| P3-15 | `multi_replace` substring overlap check | Add content overlap validation |
| P3-16 | Per-call telemetry / repeat detection | Log into `tool_call_telemetry` table |
| P3-18 | `structure` compact mode | Add compact=true option |

---
_See also: `codebase-assessment.md` for the full design review and `improvements-summary.md` for v0.7.22 P1 fixes._
