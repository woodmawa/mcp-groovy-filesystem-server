# Performance & Memory Assessment — mcp-groovy-filesystem-server v0.7.29
**Assessor:** Claude Sonnet 4.6 (direct deep-dive)  
**Date:** 2026-03-03  
**Scope:** All service implementations + McpController web endpoints

---

## Executive Summary

The codebase is already in a **good baseline state** from prior hardening rounds. The large-file streaming fix (S-I1), chunk-write streaming (S-I4), stream-leak fixes (withCloseable), and per-path double-checked locking in StructureCache are all solid. However, this assessment identifies **10 concrete issues** spanning memory management, missed concurrency opportunities, and web-endpoint concerns — ranging from high to low severity.

---

## 1. HIGH — FileWriteService: `doReplace` & `doMultiReplace` load full file into heap as String

**File:** `FileWriteService.groovy` — `doReplace()` line ~175, `doMultiReplace()` line ~230

**Issue:** Both methods call `Files.readAllBytes()` and immediately construct a full `String` from the raw bytes. For a 50 MB source file this means **two full copies in heap simultaneously** — `byte[]` + `String`. There is no size guard before loading.

```groovy
// CURRENT — two full copies in heap
byte[] rawBytes   = Files.readAllBytes(Paths.get(normalized))     // copy 1
String rawContent = new String(rawBytes, encoding)                // copy 2
```

**Impact:** Large files (e.g., generated code, SQL scripts, large JSONs) can cause GC pressure or OOM. The `doRead` path already has a threshold guard and chunking; `doReplace`/`doMultiReplace` have none.

**Fix:** Add a pre-check against `readChunkThresholdKb` (already used in FileReadService) and return a helpful error for files above threshold, directing the caller to use `patch` (line-range edit) instead. Since uniqueness checking requires the full content, full-load is unavoidable for replace semantics — the fix is the **guard + error**, not a stream rewrite.

---

## 2. HIGH — `doReplace` nearest-match scan: iterates lines twice with `.toSet().intersect()`

**File:** `FileWriteService.groovy` — `doReplace()`, fallback nearest-match block (~line 215)

**Issue:** When `oldText` is not found, the fallback scans all file lines twice — once with `contains()` and once with `.toSet().intersect()` (a character-level set intersection). The `toSet()` call on potentially long strings allocates `HashSet<Character>` per line on every iteration. For a 10k-line file this is ~20k temporary sets.

```groovy
// Allocates a HashSet<Character> per line — O(n * lineLen)
int common = fl.trim().toSet().intersect(firstLine.toSet()).size()
```

**Fix:** Replace the `toSet().intersect()` Levenshtein-proxy with a simple `containsIgnoreCase` or Jaro-Winkler over the first N lines only (cap at 500). The nearest-match hint is informational — it doesn't need exhaustive accuracy.

---

## 3. MEDIUM — `FileSearchService.searchFileContent`: uses `Path.eachLine` (no explicit stream close)

**File:** `FileSearchService.groovy` — `searchFileContent()` line ~165

**Issue:** `path.eachLine('UTF-8') { ... }` is a Groovy GDK shortcut that wraps a `BufferedReader` internally. On most JVM builds GDK's `eachLine` does properly close the reader via `withCloseable`, but this was the exact same pattern that caused resource leaks in `FileListService` and was fixed there. It should be made explicit for consistency and certainty.

**Fix:** Replace with explicit `new File(path.toString()).withReader('UTF-8') { BufferedReader br -> ... }` — matching the pattern used throughout `FileReadService`.

---

## 4. MEDIUM — `ToolsService.runToolRaw`: unbounded `StringBuilder` stdout/stderr accumulation

**File:** `ToolsService.groovy` — `runToolRaw()` and mirrored in `ExecuteService.runProcess()`

**Issue:** Both process-runner methods drain `inputStream` and `errorStream` into `StringBuilder` objects with no size cap during accumulation. The cap (`take(50000)`) is applied **after** the full output has been captured. A command producing 200 MB of stdout (e.g., a large `gradle dependencies` tree, a grep across a large repo) will heap-fill the StringBuilder before truncation.

```groovy
// CURRENT — full accumulation before truncation
StringBuilder stdout = new StringBuilder()
process.inputStream.eachLine { line -> stdout.append(sanitize(line)).append('\n') }
// ...
String stdoutStr = stdout.toString().take(maxStdout)  // truncation too late
```

**Fix:** Cap inside the reader loop using a character counter. Once `charCount >= maxStdout`, stop consuming (and drain remaining bytes without storing) to prevent backpressure killing the child process.

```groovy
// PROPOSED — cap during accumulation
int captured = 0
process.inputStream.eachLine { String line ->
    if (captured < maxStdout) {
        String s = sanitize(line)
        stdout.append(s).append('\n')
        captured += s.length() + 1
    }
    // else: read and discard to prevent process blocking
}
```

This pattern applies identically to `ExecuteService.runProcess()`.

---

## 5. MEDIUM — `UsageTracker`: single shared JDBC `Connection` is not thread-safe

**File:** `UsageTracker.groovy` — `withConnection()` + `dbConn`

**Issue:** The service holds a single `volatile Connection dbConn` and synchronises access via `dbLock`. This serialises all DB operations onto one monitor, including the periodic flush, shutdown flush, startup load, and every stats read that triggers a flush. While correct under `synchronized`, it means:
- `periodicFlush()` blocks `getStats()` (or vice versa) for the full duration of a `executeBatch()`
- Both are called potentially concurrently — periodic timer fires while a stats request is in flight

**Fix options (in order of effort):**
1. Use `SQLiteConfig` with `pragma journal_mode=WAL` which allows concurrent reads + one writer
2. Open a new connection per operation (cheap for SQLite with WAL; avoids the shared-state risk)
3. Keep current approach but add a timeout guard to `withConnection` so a stuck flush doesn't hang stats

The simplest safe fix: change `withConnection` to use a short-lived connection per call, matching `FilesystemTelemetryService`'s `withConnection` fallback pattern. SQLite WAL is highly recommended regardless.

---

## 6. MEDIUM — `McpController`: telemetry `estimateResponseSize` serialises the full result twice

**File:** `McpController.groovy` — `estimateResponseSize()` + `handleToolsCall()`

**Issue:** `estimateResponseSize()` calls `.result.toString()` on the `McpResponse`. Since `result` is typically a Map containing the serialised JSON content, this forces a second `toString()` traversal purely for a char count. For large file-read responses this can add 50–200ms of extra work on the hot path, even though the telemetry write is fire-and-forget.

```groovy
// CURRENT — forces full toString() on the response Map
return response?.result?.toString()?.length() ?: 0
```

**Fix:** The response content is already a `Map<String, Object>` where the text content is stored as `content[0].text` (a String). Grab `.length()` directly from that field rather than serialising the whole Map.

```groovy
// PROPOSED — zero-copy size estimate
private static int estimateResponseSize(McpResponse response) {
    try {
        List content = response?.result?.content as List
        return (content?.first() as Map)?.text?.length() ?: 0
    } catch (Exception ignored) { return 0 }
}
```

---

## 7. MEDIUM — `StructureCache.evictIfNeeded`: double-locking anti-pattern between `cache` and `lruLock`

**File:** `StructureCache.groovy` — `evictIfNeeded()` and `getStructure()`/`getHash()`

**Issue:** `evictIfNeeded()` is called inside the per-path `pathLock` synchronized block, but it then acquires `lruLock`. This creates a nested lock order: `pathLock → lruLock`. The `touchLru()` method also acquires `lruLock` independently. If two threads call `getStructure()` for different paths simultaneously, they can each hold their respective `pathLock` and then contend on `lruLock` — not a deadlock but a serialisation bottleneck on every cache miss.

**Fix:** Move `evictIfNeeded()` out of the per-path critical section. Call it before acquiring `pathLock`. Since eviction is best-effort (LRU is approximate), a slightly stale eviction decision is safe.

---

## 8. LOW — `FileListService.doList`: `stream.each { ... }` does not short-circuit on maxResults

**File:** `FileListService.groovy` — `doList()` line ~115

**Issue:** The `stream.each { ... }` closure checks `results.size() >= max` and returns early from the closure, but `each` on a Java Stream does not short-circuit — it continues to call the closure for every element even after the `return` (which just exits the closure, not the stream). The `return` inside `each` is equivalent to `continue`, not `break`. For large directories (e.g., `node_modules`, a Maven local repo) this iterates every path even when only N results are wanted.

```groovy
// CURRENT — does NOT short-circuit
stream.each { Path p ->
    if (results.size() >= max) return   // <- this is 'continue', not 'break'
    ...
}
```

The same issue exists in `doChildren()` and `doSizes()` for the same reason.

**Fix:** Replace the `stream.each` pattern with a `Stream.limit(max).filter(...).collect(...)` pipeline, or use an explicit iterator with early exit. `doChildren` and `doSizes` need the same fix.

```groovy
// PROPOSED
stream.filter { Path p ->
    String name = p.fileName.toString()
    compiled == null || (name =~ compiled)
}.limit(max).each { Path p -> results << pathToMap(p) }
```

---

## 9. LOW — `FileReadService.doDiff`: loads both files fully into memory as `List<String>`

**File:** `FileReadService.groovy` — `doDiff()` line ~330

**Issue:** Both files are read entirely into `List<String>` in parallel via Promises (good — concurrent I/O). However, for large files this can load two full file line-lists into heap simultaneously. The result is capped at 200 diff entries but the full load happens regardless.

**Fix:** For files under the chunk threshold this is acceptable. Add a pre-check: if either file exceeds `readChunkThresholdKb`, return an informative error directing the caller to use `grep` or `range` for targeted comparison.

---

## 10. LOW — `McpController`: Web endpoint lacks async/reactive dispatch for long-running tools

**File:** `McpController.groovy` — `handleRequest()` / `@PostMapping('/')`

**Issue:** The controller is synchronous — the calling HTTP thread is blocked for the full duration of any tool call. For tools like `execute` (bash/gradle, up to 60s timeout) or `tools/gradle build` (minutes), this ties up a Spring thread pool thread for the entire execution. Under concurrent load (multiple web clients), this starves the thread pool.

**Current state:** The endpoint is new (recently added) and this is a foundational architectural concern for the web transport path.

**Fix options:**
1. **Minimal:** Annotate `handleRequest` with `@Async` and return `CompletableFuture<McpResponse>`. Spring will dispatch to a task executor, freeing the HTTP thread. Return `DeferredResult<McpResponse>` or `Mono<McpResponse>` from the endpoint.
2. **Better:** Use Spring WebFlux `Mono.fromCallable { dispatch(request) }.subscribeOn(Schedulers.boundedElastic())` for non-blocking dispatch of blocking tool calls.
3. **Best long-term:** Since the JVM already uses virtual threads for process I/O in `ExecuteService`/`ToolsService`, configure the Spring task executor to use `Thread.ofVirtual()` to eliminate thread-count concerns entirely. Add `spring.threads.virtual.enabled=true` in application config (Spring Boot 3.2+).

---

## 11. LOW — `FilesystemTelemetryService`: `sessionCallCache` grows unbounded within a session

**File:** `FilesystemTelemetryService.groovy` — `sessionCallCache` ConcurrentHashMap

**Issue:** The repeat-call cache is cleared on session change but never pruned within a session. In a long-running session with many unique tool calls (common in agentic pipelines), this map grows without bound. Each key is `toolName:argsHash` (~30–50 chars), so 10k unique calls = ~500KB — minor but not zero.

**Fix:** Bound the cache to 1000 entries (a `LinkedHashMap` with `removeEldestEntry` or a simple `size() > 1000` guard before `put`).

---

## 12. LOW — `AstStructureScanner.scanGroovyAst`: CompilationUnit allocated per scan call

**File:** `AstStructureScanner.groovy` — `scanGroovyAst()` line ~55

**Issue:** A new `CompilerConfiguration` + `CompilationUnit` is created on every scan. Both are heavyweight objects. Since scan results are cached in `StructureCache`, the cost is amortised — but on a cold cache (e.g., after a restart touching a large codebase), many scans run in parallel and each allocates its own compiler.

**Opportunity:** The `CompilerConfiguration` is stateless after construction. A single `static final CompilerConfiguration` instance can be shared safely (it's read-only after `setTolerance()`). Only `CompilationUnit` needs to be per-call (it's stateful).

```groovy
// Shared — thread-safe after init
private static final CompilerConfiguration SHARED_CONFIG = new CompilerConfiguration().tap {
    setTolerance(10)
}
```

---

## Concurrency Opportunities (Promises)

The Promises/virtual thread infrastructure is already used in:
- `doMulti` (parallel file reads) ✅
- `doDiff` (parallel two-file load) ✅
- `doProjectScan` (parallel git + dir scan) ✅

**Missed opportunities:**

### A. `FileListService.buildTree` — recursive single-threaded directory walk

`buildTree()` recurses synchronously. Each subdirectory is listed sequentially. For a deep tree (e.g., a Maven project with many modules), multiple directory levels could be fetched concurrently. Fan out child directory scans as Promises when depth allows:

```groovy
// PROPOSED — fan out child dir reads concurrently
List<Promise<Map>> childPromises = childDirs.collect { Path dir ->
    Promises.async { buildTree(dir, rootPath, depth+1, ...) }
}
List<Map> childResults = Promises.all(childPromises).get(10, TimeUnit.SECONDS)
```
Cap parallelism at ~8 children to avoid overwhelming the filesystem.

### B. `ToolsService.doStats` — sequential JVM + buffer + usage stats

The three stat sources (JVM runtime, ChunkBufferService, UsageTracker) are all in-memory reads and take microseconds. No concurrency win here — already fast.

### C. `FileSearchService.doContentSearch` — Files.walk is single-threaded

The file content search walks the tree and scans each file sequentially. For large projects this is the bottleneck. Files could be scanned concurrently in batches:

```groovy
// PROPOSED — parallel file content scan
List<Promise<List<Map>>> scanPromises = filePaths.collect { Path p ->
    Promises.async { searchFileContent(p, contentPattern, maxMatchesPerFile) }
}
List<List<Map>> allResults = Promises.all(scanPromises).get(30, TimeUnit.SECONDS)
```
This would typically halve scan time on an 8-core machine. Cap batch size at 20 files to avoid OS file descriptor exhaustion.

---

## Summary Table

| # | Severity | Service | Issue | Fix Type |
|---|----------|---------|-------|----------|
| 1 | HIGH | FileWriteService | replace/multi_replace no size guard — full file in heap | Add size guard + error |
| 2 | HIGH | FileWriteService | nearest-match: O(n·m) toSet().intersect() per line | Cap + cheaper algorithm |
| 3 | MEDIUM | FileSearchService | eachLine no explicit reader close | Use withReader explicitly |
| 4 | MEDIUM | ToolsService + ExecuteService | stdout StringBuilder caps after full accumulation | Cap inside reader loop |
| 5 | MEDIUM | UsageTracker | Single shared JDBC connection blocks concurrent calls | Per-op connection + WAL |
| 6 | MEDIUM | McpController | estimateResponseSize() does full toString() on response | Direct text field access |
| 7 | MEDIUM | StructureCache | Nested lock order pathLock→lruLock serialises misses | Move eviction before pathLock |
| 8 | LOW | FileListService | stream.each doesn't short-circuit on maxResults | Use Stream.limit() |
| 9 | LOW | FileReadService | doDiff loads both files fully with no size guard | Add size pre-check |
| 10 | LOW | McpController | Web endpoint blocks HTTP thread for long-running tools | @Async / virtual threads |
| 11 | LOW | FilesystemTelemetryService | sessionCallCache unbounded within session | Bound to 1000 entries |
| 12 | LOW | AstStructureScanner | CompilerConfiguration re-allocated per scan | Share static instance |
| A | OPPORTUNITY | FileListService | buildTree: recursive serial dir walk | Fan out child dirs as Promises |
| B | OPPORTUNITY | FileSearchService | doContentSearch: serial file scan | Parallel scan batches via Promises |

---

## Recommended Fix Priority

**Immediate (HIGH):**
- Issue 1: Add size guard to replace/multi_replace
- Issue 2: Fix nearest-match O(n·m) scan

**Next sprint (MEDIUM):**
- Issue 4: Cap stdout/stderr accumulation in both process runners
- Issue 6: Fix estimateResponseSize in McpController
- Issue 10: Make web endpoint non-blocking for long tool calls
- Issue 5: UsageTracker connection model

**Backlog (LOW + Opportunities):**
- Issues 3, 7, 8, 9, 11, 12
- Opportunity B (parallel FileSearchService) — highest real-world latency win
- Opportunity A (parallel buildTree) — worthwhile for large codebases
