# Fix Plan — mcp-groovy-filesystem-server v0.7.29
**Based on:** cd-performance-assessment.md  
**Date:** 2026-03-03  
**Approach:** One fix at a time, checkpoint after each batch, build → test → commit.

---

## Fix Sequence

Ordered: HIGH first, then MEDIUM, then LOW, then Opportunities.  
Each fix is self-contained and reviewable independently.

---

### FIX 1 — HIGH · FileWriteService · `doReplace` size guard
**File:** `FileWriteService.groovy`  
**Issue:** No size guard before `Files.readAllBytes()` — large files load two full copies into heap.  
**Fix:** Before `Files.readAllBytes()`, check file size against `readChunkThresholdKb` (from AbstractFileService). If exceeded, return a helpful error directing caller to use `patch` instead.  
**Lines:** ~238 (before `byte[] rawBytes = Files.readAllBytes(...)`)  
**Pattern:**
```groovy
long fileSizeKb = Files.size(Paths.get(normalized)) / 1024
if (fileSizeKb > readChunkThresholdKb) {
    return McpResponse.error(requestId, -32602,
        "replace: file is ${fileSizeKb}KB (threshold ${readChunkThresholdKb}KB). Use patch (line-range edit) for large files.")
}
```
**Risk:** Low — purely additive guard, no logic change.

---

### FIX 2 — HIGH · FileWriteService · `doMultiReplace` size guard
**File:** `FileWriteService.groovy`  
**Issue:** Same as Fix 1 — `doMultiReplace` also has no size guard.  
**Fix:** Identical size guard before `Files.readAllBytes()` at ~line 328.  
**Risk:** Low.

---

### FIX 3 — HIGH · FileWriteService · `doReplace` nearest-match O(n*m) scan
**File:** `FileWriteService.groovy`  
**Issue:** Fallback nearest-match scan calls `.toSet().intersect()` per line — allocates `HashSet<Character>` for every line, O(n×m).  
**Fix:** Remove the `toSet().intersect()` second pass entirely. The first pass (`contains` / `containsIgnoreCase`) already finds good candidates. Cap the first pass at 500 lines. The nearest-match hint is informational only — accuracy over 500 lines is not worth the allocation cost.  
**Current code (lines ~260–270):**
```groovy
if (nearestLine < 0 && firstLine.length() <= 80) {
    fileLines.eachWithIndex { String fl, int idx ->
        int common = fl.trim().toSet().intersect(firstLine.toSet()).size()
        int score  = firstLine.length() - common
        if (score < bestScore) { bestScore = score; nearestContent = fl; nearestLine = idx + 1 }
    }
}
```
**Replace with:** Delete the block entirely; limit the first `eachWithIndex` to `fileLines.take(500)`.  
**Risk:** Low — no functional change to the replace logic; only affects the hint in error messages.

---

### FIX 4 — MEDIUM · FileSearchService · explicit `withReader` in `searchFileContent`
**File:** `FileSearchService.groovy`  
**Issue:** `file.eachLine('UTF-8')` at line ~210 uses GDK shortcut without explicit close — inconsistent with rest of codebase.  
**Fix:** Wrap in `new File(file.toString()).withReader('UTF-8') { BufferedReader br -> br.eachLine { ... } }`.  
**Risk:** Very low — defensive hygiene fix.

---

### FIX 5 — MEDIUM · ToolsService · cap stdout/stderr inside reader loop
**File:** `ToolsService.groovy` — `runToolRaw()` lines ~294–298  
**Issue:** `StringBuilder` accumulates full output before `.take(maxStdout)` truncates — a 200MB command output fills heap before truncation.  
**Fix:** Add a `captured` counter inside the virtual-thread closure; stop appending (but keep reading to drain) once cap is hit.
```groovy
int maxStdout = 50000  // or pull from options if refactored
int captured = 0
Thread stdoutThread = Thread.ofVirtual().start({
    process.inputStream.eachLine { String line ->
        if (captured < maxStdout) {
            String s = sanitize(line)
            stdout.append(s).append('\n')
            captured += s.length() + 1
        }
        // else: read and discard — prevents child process blocking on full pipe
    }
})
```
**Note:** `runToolRaw` doesn't currently accept options — use hardcoded cap of 50000 chars matching the existing `.take(50000)` downstream. Remove the downstream `.take()` after fixing.  
**Risk:** Low.

---

### FIX 6 — MEDIUM · ExecuteService · cap stdout/stderr inside reader loop
**File:** `ExecuteService.groovy` — `runProcess()` lines ~232–236  
**Issue:** Same problem as Fix 5 — full accumulation before `.take(maxStdout)`.  
**Fix:** Same pattern as Fix 5 but use `maxStdout`/`maxStderr` from `options` (already extracted at line ~218). Replace the two virtual-thread closures with capped versions. Remove the downstream `.take()` calls at lines ~259–260.  
**Risk:** Low.

---

### FIX 7 — MEDIUM · McpController · `estimateResponseSize` zero-copy fix
**File:** `McpController.groovy` — `estimateResponseSize()` lines ~163–167  
**Issue:** `response?.result?.toString()?.length()` forces full Map serialisation just for a char count.  
**Current:**
```groovy
private static int estimateResponseSize(McpResponse response) {
    try {
        return response?.result?.toString()?.length() ?: 0
    } catch (Exception e) { return 0 }
}
```
**Fix:** Read `.text` directly from the content list:
```groovy
private static int estimateResponseSize(McpResponse response) {
    try {
        List content = response?.result?.content as List
        return (content?.first() as Map)?.text?.length() ?: 0
    } catch (Exception ignored) { return 0 }
}
```
**Risk:** Low — telemetry only, non-fatal if wrong.

---

### FIX 8 — MEDIUM · McpController · async dispatch for long-running tools
**File:** `McpController.groovy` — `handleRequest()` / `@PostMapping('/')`  
**Issue:** HTTP thread blocked for full duration of `execute`/gradle/bash tool calls.  
**Fix:** Use Spring Boot 3.2+ virtual threads. Add to `application.properties`:
```properties
spring.threads.virtual.enabled=true
```
This enables virtual threads for the entire Tomcat thread pool — zero code change in controller, zero risk, and long-running handlers no longer monopolise platform threads.  
**Note:** If `spring.threads.virtual.enabled` is already set, verify and skip.  
**Risk:** Very low — idiomatic Spring Boot 3.2+ approach.

---

### FIX 9 — MEDIUM · UsageTracker · per-operation connection + WAL mode
**File:** `UsageTracker.groovy`  
**Issue:** Single shared `volatile Connection dbConn` serialises all DB ops through `dbLock` — periodic flush blocks stats reads.  
**Fix:**  
1. Remove `dbConn` field and `dbLock`.  
2. Change `withConnection` to open a short-lived connection per call using the stored `dbPath`.  
3. Enable WAL mode on first connection: `conn.createStatement().execute("PRAGMA journal_mode=WAL")`.  
**Pattern:**
```groovy
private void withConnection(Closure action) {
    Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
    try {
        conn.createStatement().execute("PRAGMA journal_mode=WAL")
        action(conn)
    } finally {
        conn?.close()
    }
}
```
Keep `init()` for schema creation (first call) and `destroy()` simplified (no conn to close).  
**Risk:** Medium — touches DB access pattern. Test flush + stats concurrently after.

---

### FIX 10 — LOW · FileListService · `stream.each` doesn't short-circuit
**File:** `FileListService.groovy`  
**Issue:** `stream.each { if (results.size() >= max) return }` — `return` is `continue` not `break`; iterates entire directory.  
**Affected methods:** `doChildren` (line ~110), `doList` (line ~145+), `doSizes` (line ~182).  
**Fix:** Replace `stream.each` with `stream.filter(...).limit(max)` pipeline in each method.  
**Pattern for doChildren:**
```groovy
stream.filter { Path p ->
    String name = p.fileName.toString()
    compiled == null || (name =~ compiled)
}.limit(max).each { Path p ->
    results << pathToMap(p)
}
```
Remove the `if (results.size() >= max) return` guard inside the closure.  
**Risk:** Low — correct semantics, same output.

---

### FIX 11 — LOW · FileReadService · `doDiff` size pre-check
**File:** `FileReadService.groovy` — `doDiff()` line ~330  
**Issue:** Both files loaded fully into `List<String>` with no size guard.  
**Fix:** After path normalisation, check `Files.size()` for both files against `readChunkThresholdKb`. If either exceeds, return an informative error.
```groovy
long sizeA = Files.size(Paths.get(normalizedA)) / 1024
long sizeB = Files.size(Paths.get(normalizedB)) / 1024
if (sizeA > readChunkThresholdKb || sizeB > readChunkThresholdKb) {
    return McpResponse.error(requestId, -32602,
        "diff: one or both files exceed ${readChunkThresholdKb}KB threshold. Use grep or range for targeted comparison.")
}
```
**Risk:** Low.

---

### FIX 12 — LOW · FilesystemTelemetryService · bound `sessionCallCache`
**File:** `FilesystemTelemetryService.groovy` — line ~81  
**Issue:** `sessionCallCache` grows unbounded within a session.  
**Fix:** Add a size check before `put`:
```groovy
if (!isRepeat) {
    if (sessionCallCache.size() < 1000) {
        sessionCallCache.put(cacheKey, new Date().toInstant().toString())
    }
}
```
**Risk:** Very low.

---

### FIX 13 — LOW · AstStructureScanner · shared static `CompilerConfiguration`
**File:** `AstStructureScanner.groovy` — `scanGroovyAst()` lines ~70–72  
**Issue:** New `CompilerConfiguration` allocated per scan call — heavyweight but amortised by cache.  
**Fix:** Promote to `private static final`:
```groovy
private static final CompilerConfiguration SHARED_CONFIG = new CompilerConfiguration().tap {
    setTolerance(10)
}
```
Replace `CompilerConfiguration config = new CompilerConfiguration(); config.setTolerance(10)` with `SHARED_CONFIG`. Only `CompilationUnit` remains per-call.  
**Risk:** Very low — `CompilerConfiguration` is read-only after init.

---

### FIX 14 — LOW · StructureCache · `evictIfNeeded` outside `pathLock`
**File:** `StructureCache.groovy`  
**Issue:** `evictIfNeeded()` called inside `synchronized(pathLock)` then acquires `lruLock` — nested lock order creates serialisation bottleneck on every cache miss.  
**Fix:** Move `evictIfNeeded()` call to *before* `synchronized(pathLock)` in both `getStructure()` (line ~94) and `getHash()` (line ~145). Eviction is best-effort — a slightly stale decision is safe.  
**Risk:** Low — eviction correctness unaffected; minor race window is acceptable for LRU.

---

### OPPORTUNITY A — FileListService · parallel `buildTree`
**File:** `FileListService.groovy` — `buildTree()`  
**Issue:** Recursive directory walk is single-threaded — slow on deep trees (Maven multi-module, node_modules).  
**Fix:** Fan out child directory scans as Promises (cap at 8 parallel), collect with `Promises.all(...)`.  
**Risk:** Medium — new concurrency path; test deeply nested trees.  
**Priority:** Do after all numbered fixes are done.

---

### OPPORTUNITY B — FileSearchService · parallel content scan
**File:** `FileSearchService.groovy` — `doContentSearch()`  
**Issue:** Files scanned sequentially — bottleneck on large projects.  
**Fix:** Batch file paths into groups of 20; scan each batch concurrently via Promises.  
**Risk:** Medium — watch for file descriptor exhaustion on very large repos.  
**Priority:** Do after Opportunity A.

---

## Checkpoint Plan

| After fixing | Checkpoint action |
|---|---|
| Fix 1–3 (all HIGH) | Build + smoke test replace/multi_replace on large file. Git commit: `fix: HIGH issues 1-3 FileWriteService size guards and nearest-match` |
| Fix 4–7 (MEDIUM batch 1) | Build + test FileSearch, ToolsService, ExecuteService, McpController. Git commit: `fix: MEDIUM issues 4-7` |
| Fix 8–9 (MEDIUM batch 2) | Verify virtual threads in app.properties; test UsageTracker concurrency. Git commit: `fix: MEDIUM issues 8-9 async dispatch and UsageTracker WAL` |
| Fix 10–14 (all LOW) | Build + full test suite. Git commit: `fix: LOW issues 10-14` |
| Opportunity A | Build + test on deep tree project. Git commit: `perf: parallel buildTree` |
| Opportunity B | Build + test on large codebase search. Git commit: `perf: parallel FileSearchService content scan` |
| All done | Update `cd-performance-assessment.md` → mark all issues resolved. Bump version to v0.7.30. |

---

## Config Service Checkpoint

After completing each commit batch above, checkpoint into the context/config server with:
- What was fixed
- Any deviations from this plan
- Test results observed
- Next fix to tackle

This keeps the session recoverable if context runs out mid-session.
