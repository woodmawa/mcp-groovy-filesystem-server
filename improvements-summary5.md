# Improvements Summary — v0.7.26
_Date: 2026-03-01_

## Changes

### P1 — `patch` options-as-string crash fix (Item 1)

**Root cause:** When Claude Code serialises MCP tool arguments, it sometimes passes `options` as a pre-serialised JSON string (e.g. `"{\"replacements\": [...]}"`) rather than a Map object. The existing cast `(arguments.options as Map<String, Object>)` did not parse the string, leaving `options` as a String. Subsequent calls to `options.encoding`, `options.replacements`, etc. threw:

```
No signature of method: get for class: java.lang.String is applicable for argument types: (String) values: [encoding]
```

This caused a hard MCP -32603 error on `patch`, `replace`, `multi_replace`, and `chunk_write` actions.

**Fix:**
- Added `normaliseOptions(Object raw)` static helper to `AbstractFileService` — checks if `raw` is a String, parses it with `JsonSlurper`, and returns a Map. Returns empty map for null/unparseable input.
- Updated `handleToolCall` in `FileWriteService` to call `normaliseOptions(arguments.options)` instead of the bare cast. This single change covers all write actions (write, append, replace, patch, multi_replace, chunk_write, finalise_write, abort_write) since they all receive options from this method.
- Added `import groovy.json.JsonSlurper` to `AbstractFileService`.

### P1 — `grep` and `get_method` FILE-only warning in description (Item 2)

**Root cause:** Claude Code passed a directory path to `grep`, received a -32603 error, and had to retry with `file_search`. The "FILE path only, NOT directory" note appeared only on the `structure` action description. `grep` and `get_method` have the same constraint but no warning, causing avoidable retry loops.

**Fix:** Updated the `file_read` tool description in `FileReadService.getToolDefinitions()`:
- `grep` line now includes: `FILE path only, NOT directory.`
- `get_method` line now includes: `FILE path only, NOT directory.`

### P3 — StructureCache per-entry lock (Item 3) — Already done in v0.7.25

Verified: `StructureCache.groovy` already uses `ConcurrentHashMap<String, Object> computeLocks` with `computeIfAbsent` + `synchronized(pathLock)` for per-entry locking. No change needed.

### P3 — `structure` compact mode (Item 4) — Already done in v0.7.24/v0.7.25

Verified: `doStructure()` already implements compact mode — when `options.compact=true`, returns only `method` type entries with `line`, `type`, `content` fields (no `endLine`). Description already documents this. No change needed.

## Files Changed

| File | Change |
|------|--------|
| `src/main/groovy/com/softwood/mcp/service/AbstractFileService.groovy` | Added `normaliseOptions()` helper + `JsonSlurper` import |
| `src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy` | Use `normaliseOptions()` in `handleToolCall` |
| `src/main/groovy/com/softwood/mcp/service/FileReadService.groovy` | Updated `grep` and `get_method` description lines |
| `build.gradle` | Version bumped to `0.7.26` |

## Test Results

`gradle test` — BUILD SUCCESSFUL, 0 failures.
