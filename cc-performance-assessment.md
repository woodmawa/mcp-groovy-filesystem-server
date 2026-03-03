# CC Performance Assessment — MCP Groovy Filesystem Server v0.7.26

**Date:** 2026-03-02
**Assessed by:** Claude Sonnet 4.6 (claude-code)
**Scope:** FileReadService, FileWriteService, FileLifecycleService, ToolsService, plus shared infrastructure (AbstractFileService, StructureCache, ChunkBufferService, UsageTracker, FilesystemTelemetryService, Promises library)

---

## 1. Smoke Test Summary

Ran 46 tool calls across all four services during this session. No errors, no panics, no assertion failures. Log (`mcp-server-groovy-filesystem.log`) was clean — only the startup banner was present as the log resets on each new session.

**Timings observed:**

| Action | Est. cost | Notes |
|---|---|---|
| `file_read:exists` | ~0ms | Metadata only — optimal |
| `file_read:summary` | ~1ms | Streaming line count — optimal |
| `file_read:info` | ~1ms | `BasicFileAttributes` — optimal |
| `file_read:head/tail` | ~2ms | BufferedReader loop — good |
| `file_read:grep` | ~3ms | Streaming, capped — good |
| `file_read:get_method` | ~4ms | Cache hit on 2nd call |
| `file_read:structure` | ~5ms (miss), <1ms (hit) | AST scanner on miss |
| `file_read:checksum` | ~5ms | 8KB buffer loop — acceptable |
| `file_read:range` | ~3ms | Line slice — good |
| `file_read:read` (small) | ~5ms | Full load — see issue #M1 |
| `file_read:diff` | ~8ms | Parallel reads via Promises — correct |
| `file_read:multi` (3 files) | ~10ms | Parallel Promises.all — correct |
| `tools:git log` | **35ms** | ProcessBuilder overhead |
| `tools:project_scan` | ~250ms | 3 parallel processes — good |
| `tools:stats` | ~5ms | DB flush included — see issue #T4 |

**Session token consumption at assessment close:**
284KB response / ~72K estimated tokens over 46 calls. Range reads dominate (94KB / 8 calls = 11.75KB avg). Multi reads are costly (51KB / 2 calls = 25.5KB avg). Bounded-read ratio: **76%** — target is >85%.

---

## 2. Memory Consumption Opportunities

### M1 — `doRead`: Full-file load before chunking (HIGH)
**File:** `FileReadService.groovy:179–183`

The comment says "FIX 2: size-check BEFORE loading" but the implementation still loads the entire file into a `String` even for the chunking path:
```groovy
if (fileSize > threshBytes) {
    String content = new File(normalized).getText(encoding)   // ← full load
    chunkBufferService.createReadSession(sessionId, content)
```
For a 600KB file: `Files.size()` is called (cheap), then the entire 600KB is loaded into a Java String, then `splitIntoChunks()` creates N substring copies of up to 400KB each. Peak heap pressure is **≥ 2× the file size** (original String + chunk Strings). A streaming approach reading directly into fixed-size byte arrays would cap peak allocation at ~MAX_CHUNK_BYTES (400KB), regardless of file size.

**Recommendation:** Read the file in `MAX_CHUNK_BYTES` slices directly into the ChunkBufferService using a `BufferedInputStream`, rather than loading the full string first.

---

### M2 — `fileHash()` after write re-reads from disk (MEDIUM)
**File:** `FileWriteService.groovy:155–164, 180, 207`

`doWrite()` and `doAppend()` call `fileHash(target)` which re-reads the file from disk with an 8KB buffer loop. The bytes were just written and are already in memory. `doReplace` and `doPatch` correctly call `computeHash(bytes)` (the in-memory variant). `doWrite` should do the same:
```groovy
// Current (unnecessary disk read):
String hash = fileHash(target)

// Fix (use bytes already in memory):
String hash = computeHash(body.getBytes(encoding))
```
`doAppend` is trickier (appended bytes differ from total file bytes) but the file hash could be computed from `Files.readAllBytes()` instead of the streaming-open approach in `fileHash()`.

---

### M3 — `StructureCache.computeLocks` grows unboundedly (LOW/MEDIUM)
**File:** `StructureCache.groovy:42`

```groovy
private final ConcurrentHashMap<String, Object> computeLocks = new ConcurrentHashMap<>()
```
Lock objects are added via `computeIfAbsent` but never removed. For a long session working across a large codebase, every distinct file path accumulates a lock object permanently. Each `Object` lock is ~16 bytes on the heap, so 10,000 files = ~160KB of unreachable lock objects. Consider using the normalized path's `String.intern()` as a lock (or clean up `computeLocks` during `invalidate()`):
```groovy
void invalidate(String normalizedPath) {
    cache.remove(normalizedPath)
    computeLocks.remove(normalizedPath)      // ← add this
    ...
}
```

---

### M4 — `validateResponseSize()` full JSON serialisation for size check (LOW)
**File:** `AbstractFileService.groovy:381–393`

```groovy
String json = JsonOutput.toJson(response)
BigDecimal sizeKb = json.length() / 1024
```
This serialises the entire response to JSON just to measure its character length — the response will be serialised again when sent. A lightweight estimate using the content field length would be sufficient:
```groovy
// Cheaper: inspect the known content string directly
if (response instanceof Map && response.content instanceof List) { ... }
```
Or accept the double-serialisation cost as negligible (it only fires when size exceeds the threshold).

---

### M5 — `UsageTracker` triple-map overhead (LOW)
**File:** `UsageTracker.groovy:82–91`

Three parallel `ConcurrentHashMap`s keyed by the same String:
```groovy
ConcurrentHashMap<String, AtomicInteger> callCounts
ConcurrentHashMap<String, AtomicLong>    responseBytes
ConcurrentHashMap<String, AtomicLong>    inputBytes
```
A single `ConcurrentHashMap<String, UsageBucket>` (where `UsageBucket` holds call count, response bytes, input bytes as a struct) would halve HashMap entry overhead and reduce three separate `computeIfAbsent` calls per event to one. Minor at current scale but cleaner.

---

## 3. Throughput Performance Opportunities

### T1 — `FileReadService.handleToolCall()` skips `normaliseOptions()` (BUG / HIGH)
**File:** `FileReadService.groovy:126`

```groovy
Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>
```
`FileWriteService` correctly calls `normaliseOptions(arguments.options)` which handles the case where `options` arrives as a pre-serialised JSON string (the crash that v0.7.26 fixed for `patch`). `FileReadService` does the raw cast. If any MCP client serialises the options sub-object as a JSON string for `file_read` actions, it will arrive as a `String`, be cast to `Map` (giving null/empty), and options like `lines`, `pattern`, `compact`, `startLine` will silently default. This should be brought into parity:
```groovy
Map<String, Object> options = normaliseOptions(arguments.options)
```

---

### T2 — `FilesystemTelemetryService` opens a new JDBC connection per write (MEDIUM)
**File:** `FilesystemTelemetryService.groovy:112–120`

```groovy
private void withConnection(Closure work) {
    Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
    try { ... }
    finally { conn.close() }
}
```
`UsageTracker` correctly holds a single persistent JDBC connection (`volatile Connection dbConn`) for the lifetime of the service. `FilesystemTelemetryService` opens and closes a new connection on every telemetry write. SQLite connection initialisation (~1–3ms each) adds up at scale. The telemetry writer is already serialised on a single daemon thread, so a shared connection is safe:
```groovy
private volatile Connection dbConn = null

@PostConstruct void init() {
    dbConn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
}

@PreDestroy void shutdown() {
    asyncWriter.shutdown()
    dbConn?.close()
}
```

---

### T3 — `StructureCache` double-lock risk in `touchLru` + `evictIfNeeded` (MEDIUM)
**File:** `StructureCache.groovy:217–237`

`touchLru()` runs inside `synchronized(lruLock)` and contains a loop that calls `cache.remove(k)` for orphaned keys. `evictIfNeeded()` also runs inside `synchronized(lruLock)` and calls `cache.remove()`. Both are called from `getStructure()` and `getHash()` while already holding the per-path `pathLock`. The locking sequence is therefore:
```
pathLock → lruLock  (in getStructure/getHash)
```
As long as no path inverts this ordering this is safe. However, `touchLru()` also calls `cache.size()` (reading ConcurrentHashMap) and `cache.keySet()` inside the lruLock — these are atomic on `ConcurrentHashMap` but could produce stale views. The `accessOrder.size() < cache.size()` guard in `touchLru()` has a TOCTOU window between the size reads. Consider removing the orphan-cleanup from `touchLru()` and only doing eviction in `evictIfNeeded()`.

---

### T4 — `buildTodayStats()` flushes to DB on every stats read (LOW/MEDIUM)
**File:** `UsageTracker.groovy:165`

```groovy
private Map<String, Object> buildTodayStats() {
    if (dbPath) { try { flushToDb(currentDate) } catch ... }   // ← every stats call
```
`tools action=stats` triggers a synchronous DB write (batched INSERT OR REPLACE for all tracked keys) under `dbLock`. For interactive stats queries, this is a hidden write cost. Periodic flush (`@Scheduled` every 10min) already handles mid-session persistence. The eager flush on every read could be made conditional on a dirty flag:
```groovy
private volatile boolean dirty = false

@EventListener void onMcpEvent(McpRequestEvent event) {
    ...
    dirty = true
}

private Map<String, Object> buildTodayStats() {
    if (dbPath && dirty) { flushToDb(currentDate); dirty = false }
```

---

### T5 — `AstStructureScanner` produces duplicate inner-class entries (MEDIUM)
**File:** `AstStructureScanner.groovy`

Observed in the smoke test: `structure` on `StructureCache.groovy` returned `class StructureCache$1 extends LinkedHashMap` **twice** and `class StructureCache$CacheEntry` **twice** (16 entries vs 12 actual). The AST scanner visits anonymous inner classes via both the parent class visit and as standalone classes in the module. This wastes cache space and inflates responses. The scanner should track seen class names/lines and deduplicate:
```groovy
Set<String> seen = new HashSet<>()
// before adding entry:
String key = "${line}:${content}"
if (!seen.add(key)) return   // skip duplicate
```

---

### T6 — `doDelete()` returns soft success=false rather than error for missing path (LOW)
**File:** `FileLifecycleService.groovy:147–149`

```groovy
if (!Files.exists(target)) {
    return textResponse(requestId, [success: false, reason: 'Path does not exist'])
}
```
All other error paths use `McpResponse.error(...)`. A caller checking `response.error == null` to determine success would see no error but `success: false` — inconsistent. This should either throw `FileNotFoundException` (which the handler converts to `McpResponse.error`) or return `McpResponse.error(requestId, -32602, "Path does not exist: ...")`.

---

## 4. Promises Library — Internal Concurrency Opportunities

The Promises library (virtual-thread backed `CompletableFuture`) is already used well in three places: `doMulti` (parallel file reads), `doDiff` (parallel dual-file load), and `doProjectScan` (three parallel subprocess calls). The following are additional places where it could reduce latency or provide non-blocking execution:

### P1 — `doStats`: DB query could be async (LOW IMPACT)
`ToolsService.doStats()` calls `usageTracker.getStats(period)` which, for non-today periods, executes a synchronous SQL query under `dbLock`. Wrapping the stats assembly in `Promises.async { }` would free the MCP request thread immediately; however since `doStats` is a leaf call (no parallelism possible with other I/O), the benefit is limited to thread-pool efficiency. Worth doing for consistency:
```groovy
return Promises.async { usageTracker.getStats(period) }
              .then { Map stats -> buildStatsResponse(stats) }
              .get(5, TimeUnit.SECONDS)
```

### P2 — `StructureCache`: combined `getStructureAndHash()` (MEDIUM IMPACT)
When a caller needs both structure and hash (e.g. `doStructure` returns `file_content_hash`, `doGetMethod` returns `file_content_hash`), two separate cache lookups are made. Each acquires the per-path lock independently. A combined method would:
- Compute both structure and hash in a single scan pass (the AST scan and the SHA-256 hash could run in one file read)
- Acquire the path lock once
- Halve the number of `synchronized(pathLock)` entries for cache misses

```groovy
Map<String, Object> getStructureAndHash(String normalizedPath) {
    // Returns map with both structure list AND hash
    // Single lock, single file read on miss
}
```

### P3 — `doGrep` across multiple files (FUTURE API / MEDIUM IMPACT)
Currently `file_read grep` operates on a single file. A future `multi-grep` variant (e.g. `action=grep, options.paths=[...]`) could fan out with `Promises.all()` the same way `doMulti` does — one virtual thread per file, `Promises.all()` to collect. The Promises infrastructure already supports this pattern directly.

### P4 — `FilesystemTelemetryService` already async — keep it (CONFIRM GOOD)
The existing `asyncWriter.submit { ... }` pattern in `FilesystemTelemetryService` is the correct use of fire-and-forget async — the hot path (request handling) is never blocked by telemetry I/O. This is the right design; no changes needed except fixing the connection-per-call issue noted in T2.

### P5 — `Promises.all()` for batch lifecycle operations (FUTURE API / LOW IMPACT)
`FileLifecycleService` operations (copy, move, delete) are inherently sequential on single paths. If a batch lifecycle API were added (e.g. `action=copy, paths=[...]`), `Promises.all()` would be a natural fit for fan-out. Not applicable to current single-path API.

---

## 5. Tool Description / Schema Fidelity Issues

### D1 — `file_lifecycle` description too terse (MEDIUM)
**File:** `FileLifecycleService.groovy:37–38`

Current description:
```
'File/directory operations. Actions: create|delete|copy|move|rename|touch.
dst required for copy/move/rename. options.recursive=true required to delete non-empty directory.'
```
Missing from the human-readable description (present only in inputSchema):
- `options.verbose=true` for full response with path echo (default is compact/success-only)
- `options.mkdirs=true` (available for create, copy, move)
- `options.type` (file|directory) for create — determines whether a file or directory is created

Claude reads the description more than the schema for guidance. Without these in the description text, verbose mode and create-directory are not discoverable without schema inspection.

**Recommendation:** Expand the description to one line per action:
```
- create(path, options.type=file|directory, options.mkdirs=true): create file or directory
- delete(path, options.recursive=true): delete file; directory requires recursive=true
- copy/move/rename(path, dst, options.overwrite, options.mkdirs=true)
- touch(path): update mtime or create if missing
All actions: options.verbose=true for full response (default: compact success-only).
```

---

### D2 — `file_write` schema: `path` in `required` but optional for `abort_write` (LOW)
**File:** `FileWriteService.groovy:100`

```groovy
required: ['action', 'path']
```
The path description says "required for all except abort_write" but `required` still lists `path`. This causes schema-strict clients (and Claude's tool-call validation) to always expect path for abort_write, even though the code doesn't use it. Either remove path from `required` and rely on individual action validation, or add a note in the action enum description.

---

### D3 — `file_write.patch`: `content` parameter not used (CONFUSION RISK)
**File:** `FileWriteService.groovy:58–103` (description), line 124 (handler)

The `patch` action uses `options.replacements[]` and ignores `content`. The description correctly describes this but lists `content: [type: 'string', description: 'Content for write/append/chunk_write']` as a top-level property alongside `patch`. A caller looking at the schema properties might pass `content` with patch expecting it to be used. The inputSchema should note `content` is unused for `patch`/`replace`/`multi_replace`.

---

### D4 — `FileReadService.handleToolCall()` options inconsistency vs FileWriteService (BUG — see T1)
Already captured as T1. The inconsistency is both a bug (options-as-string silently discarded) and a description/contract mismatch (the tool schema implies options are always a JSON object).

---

### D5 — `structure` action: duplicate inner-class entries in response (ACCURACY)
Already captured as T5. When `structure` returns duplicate entries for anonymous/inner classes, callers building code maps from the structure output will see phantom duplicates. This affects accuracy of automated editing workflows that rely on `get_method` line numbers derived from structure entries.

---

## 6. Summary Priority Table

| ID | Service | Category | Severity | Description |
|----|---------|----------|----------|-------------|
| M1 | FileReadService | Memory | HIGH | Full-file load before chunking — 2× peak heap |
| T1 | FileReadService | Bug/Throughput | HIGH | `normaliseOptions()` not called — options-as-string silently ignored |
| T2 | FilesystemTelemetry | Throughput | MEDIUM | New JDBC connection per telemetry write |
| T5 | AstStructureScanner | Accuracy | MEDIUM | Duplicate inner-class entries in structure output |
| D1 | FileLifecycleService | Description | MEDIUM | Terse tool description hides verbose, mkdirs, type options |
| M2 | FileWriteService | Memory | MEDIUM | `doWrite`/`doAppend` re-read file for hash — bytes already in memory |
| T3 | StructureCache | Throughput | MEDIUM | touchLru + evictIfNeeded — TOCTOU window in orphan cleanup |
| T4 | UsageTracker | Throughput | LOW/MED | DB flush on every stats read — add dirty flag |
| P2 | StructureCache | Concurrency | MEDIUM | Combined `getStructureAndHash()` — single lock, single scan |
| M3 | StructureCache | Memory | LOW/MED | `computeLocks` map grows unboundedly |
| D2 | FileWriteService | Schema | LOW | `path` in required[] but optional for abort_write |
| T6 | FileLifecycleService | Logic | LOW | `doDelete` soft failure vs McpResponse.error inconsistency |
| D3 | FileWriteService | Description | LOW | `content` unused for patch/replace/multi_replace but listed as property |
| M4 | AbstractFileService | Memory | LOW | Full JSON serialise in validateResponseSize() |
| M5 | UsageTracker | Memory | LOW | Triple-map overhead — could be single struct map |
| P1 | ToolsService | Concurrency | LOW | `doStats` DB query could be Promises.async |

---

## 7. Appendix — What Is Already Good

- **Promises.async** used correctly in `doMulti`, `doDiff`, `doProjectScan`
- **StructureCache** per-path locks prevent wasted duplicate computation for the common concurrent-miss case
- **`doSummary`** uses `Files.lines()` streaming — never loads file into heap
- **`doPatch`** bottom-up application, overlap detection, atomic write, post-write verification, expectedHash drift guard — robust
- **`doGrep`** with contextLines: streaming with sliding before-window and pending-after tracking — correct and memory-efficient
- **`atomicWrite`** with temp-file + ATOMIC_MOVE fallback — correct and crash-safe
- **`ChunkBufferService`** TTL expiry via scheduled sweep — prevents session leak
- **`runToolRaw`** uses virtual threads for stdout/stderr capture — avoids I/O deadlock
- **`validateWriteEnabled()`** guard on all mutating operations
- **`sanitize()`** centralised in AbstractFileService — single source of truth
- **JDBC single-connection** in UsageTracker is correct for SQLite embedded usage
