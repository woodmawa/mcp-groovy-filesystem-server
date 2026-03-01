# Filesystem Server v0.7.26 — Work Brief
_Date: 2026-03-01 | Current version: v0.7.25 | Target: v0.7.26_

---

## Background

Follow-up to v0.7.25. Three new issues identified during the context server uplift
session — one correctness bug, one description fix, and the deferred P3 items.

---

## Item 1 — `patch` action: options passed as JSON string causes crash (BUG — P1)

**File:** `FileWriteService.groovy` → `doPatch()` / options parsing

**Problem:** When Claude Code passes `options` as a pre-serialised JSON string
(e.g. `"{\"replacements\": [...]}"`  ) instead of a Map object, the server tries
to call `.get("encoding")` on a String and throws:

```
No signature of method: get for class: java.lang.String is applicable for
argument types: (String) values: [encoding]
```

This causes a hard MCP -32603 error. The server should handle both cases gracefully.

**Fix:** In the options parsing path, check if `options` is a String and parse it
first before treating it as a Map:
```groovy
if (options instanceof String) {
    options = new groovy.json.JsonSlurper().parseText(options as String) as Map
}
```
Apply this defensive parse at the top of `doPatch()` and any other action that
reads from `options` — `doReplace`, `doMultiReplace`, `doChunkWrite` etc.
Consider extracting to a shared `normaliseOptions(raw)` helper in `AbstractFileService`.

---

## Item 2 — `grep` and `get_method` descriptions missing FILE-only warning (P1)

**File:** `FileReadService.groovy` → `getToolDefinitions()`

**Problem:** Observed during context server pass — Claude Code passed a directory
path to `grep`, got a -32603 error, then had to retry with `file_search`. The
"FILE path only, NOT directory" note only appears on the `structure` action line.
`grep` and `get_method` have the same constraint but no warning.

**Fix:** Update the action lines in the description:
```
Before:
- grep(path, options.pattern, options.maxMatches=10, options.contextLines=0): regex matches; set contextLines>0 for before/after context on each match
- get_method(path, options.method): returns complete named method body in ONE call - preferred over structure+range for editing

After:
- grep(path, options.pattern, options.maxMatches=10, options.contextLines=0): regex matches; FILE path only, NOT directory. set contextLines>0 for before/after context
- get_method(path, options.method): returns complete named method body - FILE path only, NOT directory. Preferred over structure+range for editing
```

---

## Item 3 — P3-14: `StructureCache` lock contention

**File:** `StructureCache.groovy`

Replace single coarse lock with `ConcurrentHashMap.computeIfAbsent` for per-entry
locking. Low priority — minimal real-world impact with single client.

---

## Item 4 — P3-18: `structure` compact mode

**File:** `FileReadService.groovy` → `doStructure()`

When `options.compact = true`, return only method/function entries, skip fields
and class declarations, omit `endLine`. Reduces structure response ~50% for
large files. Already partially implemented per v0.7.24 notes — verify and complete.

---

## Build & Release

1. `gradle test` — 0 failures required
2. Bump `build.gradle` to `0.7.26`
3. `gradle bootJar`
4. Copy JAR to `C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-0.7.26.jar`
5. Update Desktop config `C:/Users/willw/AppData/Roaming/Claude/claude_desktop_config.json`
6. Update Claude Code config `C:/Users/willw/.claude.json`
7. `git commit -m "v0.7.26: patch options string fix, grep/get_method description, StructureCache, structure compact"`
8. `git tag v0.7.26 && git push && git push --tags`
9. Write `improvements-summary5.md` in project root

---

## Priority Order

| # | Item | Priority | Notes |
|---|------|----------|-------|
| 1 | patch options-as-string crash | P1 | Hard error, happens in practice |
| 2 | grep/get_method FILE-only warning | P1 | Causes retry loops |
| 3 | StructureCache per-entry lock | P3 | Low urgency |
| 4 | structure compact mode | P3 | Low urgency |
