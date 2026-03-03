# Claude.ai Independent Performance Assessment
## mcp-groovy-filesystem-server — v0.7.26 → v0.8.0

> **Assessor:** Claude Sonnet 4.6 (claude.ai desktop chat, via groovy-filesystem MCP tool)  
> **Date:** 2026-03-02  
> **Method:** Direct source code review via groovy-filesystem file_read/grep tools (no execution)  
> **Scope:** All service, promise, and support files under `src/main/groovy`

This is the **third independent view** — alongside Claude Code's `cc-performance-assessment.md` and the agentic runner's `agentic-performance-review.md` — intended for consolidation by Claude Desktop before a v0.8.0 fix brief.

---

## 1. Promise Library Assessment

### P-I1 — `VIRTUAL_EXECUTOR` is a static field on `PromiseImpl` (MEDIUM)

`PromiseImpl.VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor()` is a **static final field**. This is correct for virtual threads (there is no pool to size), but:

- It is referenced from `Promises.async()` via `PromiseImpl.VIRTUAL_EXECUTOR` — a cross-class static coupling.
- If `PromiseImpl` is ever reloaded (hot-swap, Groovy MetaClass reset), a second executor could leak.
- **Recommendation:** Move `VIRTUAL_EXECUTOR` to `Promises` class (the public API surface) and have `PromiseImpl` accept the executor as a constructor arg. Cleaner ownership.

### P-I2 — No timeout default on `Promises.async{}` chains (MEDIUM)

Every call site does `Promises.async { ... }.get(N, UNIT)` with hard-coded timeouts at the call site (e.g. `doMulti` uses 30s, `doDiff` uses 30s, `doProjectScan` uses 120s). There is no default timeout on the Promise itself. If a caller forgets `.get(timeout)` and uses `.get()` (blocking indefinitely), the MCP request thread hangs forever.

**Recommendation:** Add `getWithDefaultTimeout()` or configure a default max timeout on `PromiseImpl`. Alternatively, document that `.get()` without timeout is forbidden and enforce via a `@SuppressWarnings`-guarded override that logs a warning.

### P-I3 — `Promises.all()` wraps each result in another list unnecessarily (LOW)

Looking at the `Promises.all(List<Promise>)` pattern — if each inner promise already resolves to a `List`, callers get `List<List>` back from `all()`. In `doMulti` this is handled correctly (results are iterated as a flat list). But the API shape could confuse future callers. A typed `Promises.all(List<Promise<T>>): Promise<List<T>>` signature would make this clearer at compile time. Currently `@CompileStatic` is applied but the generic erasure means no compile-time checking.

---

## 2. Streaming Assessment

### S-I1 — `FileReadService.doRead()` — confirmed double-heap via string load (HIGH)

This confirms CC assessment M1. The specific issue: `new File(normalized).getText(encoding)` allocates a `String` whose internal `char[]` is `2 × fileSize` bytes on Java 17+ (UTF-16 internal representation for non-Latin). For a 1MB file this is a 2MB String, then `splitIntoChunks()` produces substring views that prevent GC of the backing array until all chunks expire. **Peak heap is ~3× file size.**

Fix skeleton:
```groovy
// In doRead, replace the full-load path:
void streamToChunks(Path path, String sessionId, String encoding, long maxChunkBytes) {
    def reader = Files.newBufferedReader(path, Charset.forName(encoding))
    def buf    = new char[(int)(maxChunkBytes / 2)]  // char = 2 bytes in UTF-16
    int chunkIdx = 0
    int read
    def sb = new StringBuilder()
    while ((read = reader.read(buf)) != -1) {
        sb.append(buf, 0, read)
        if (sb.length() >= maxChunkBytes / 2) {
            chunkBufferService.addChunk(sessionId, chunkIdx++, sb.toString())
            sb.setLength(0)
        }
    }
    if (sb.length() > 0) chunkBufferService.addChunk(sessionId, chunkIdx++, sb.toString())
}
```

### S-I2 — `FileReadService.doMulti()` reads small files via `Files.readString()` (MEDIUM)

`doMulti` uses `Promises.async { Files.readString(p, charset) }` for each file. For small files this is fine, but there is no size guard — a caller passing 10 × 5MB files to `multi` will load 50MB concurrently on 10 virtual threads simultaneously. The same `threshBytes` check from `doRead` should apply inside `doMulti` per file, capping single-file load at `MAX_RESPONSE_KB`.

### S-I3 — `AstStructureScanner` loads entire file into `GroovyShell` for AST parse (MEDIUM)

AST parsing via `new GroovyShell().parse(source)` requires the full source `String` in memory. For large generated Groovy files (e.g. config DSLs) this can be several hundred KB. There is no streaming path here — it's inherent to the Groovy AST API. **Mitigation:** enforce the existing file-size cap before triggering AST scan; currently there's no size check before `doStructure` calls `astStructureScanner.scanFile()`. Add:
```groovy
if (Files.size(target) > 512 * 1024) {
    return errorResponse(requestId, "File too large for structure scan (>512KB)")
}
```

### S-I4 — `FileWriteService.doChunkWrite()` accumulates all chunks in `ChunkBufferService` before finalise (MEDIUM)

Chunk writes are accumulated in `ChunkBufferService` as a `List<String>` (one String per chunk). On `finalise_write`, these are joined: `chunks.join('')` — allocating a single String of the entire file content before writing. This is the write-path equivalent of M1. For a 5MB file written in 1MB chunks, the join allocates 5MB on top of the 5 × 1MB chunk strings already in memory = **6× peak**.

Fix: `finalise_write` should iterate chunks directly to an `OutputStream` using a `BufferedWriter`, never joining:
```groovy
Files.newBufferedWriter(target, charset, WRITE, CREATE, TRUNCATE_EXISTING).withCloseable { w ->
    chunks.each { chunk -> w.write(chunk) }
}
```

---

## 3. Memory Assessment

### M-I1 — `ChunkBufferService` TTL cleanup races with active sessions (MEDIUM)

`ChunkBufferService` uses a `@Scheduled` sweep that calls `sessions.entrySet().removeIf { isExpired(it.value) }`. If a chunk-read session is in active use and a session is slow (large file, slow client), the TTL can expire mid-session and the chunks disappear, causing a confusing `session not found` error to the client. There is no `touchSession()` / keep-alive mechanism. Sessions that are actively chunking should reset their TTL on each `chunk_read` call.

Fix: add `session.lastAccess = System.currentTimeMillis()` in `getChunk()` and use that for expiry rather than creation time.

### M-I2 — `UsageTracker.flushToDb()` builds a `Map` copy of all tracked keys under `dbLock` (LOW)

```groovy
synchronized (dbLock) {
    callCounts.each { tool, count ->
        // INSERT OR REPLACE per tool
    }
}
```
The lock is held for the duration of all INSERT OR REPLACE statements. For a session with 50+ unique tool calls, this is 50 synchronous SQLite writes under a single lock. Use a prepared statement with `executeBatch()` instead — the lock is still needed but the IO is batched:
```groovy
def ps = dbConn.prepareStatement('INSERT OR REPLACE INTO usage_stats VALUES (?,?,?,?,?)')
callCounts.each { tool, count ->
    ps.setString(1, tool); ps.setInt(2, count.get()); ... ; ps.addBatch()
}
ps.executeBatch()
```

### M-I3 — `StructureCache.CacheEntry` stores full structure list + full hash as separate fields (LOW)

`CacheEntry` holds `List<Map<String,Object>> structure` (potentially hundreds of entries for large files) and `String hash` (64 chars). The structure list is always fully materialised even if the caller only needs the hash (e.g. `doRead` with `compact=true` adds `file_content_hash` to the response). A lazy/separate cache for hash-only vs structure would avoid materialising the AST for hash-only callers.

---

## 4. Throughput Assessment

### TH-I1 — `AbstractFileService.sanitize()` called on every tool invocation but does unnecessary regex on safe paths (LOW)

`sanitize()` always runs path traversal regex (`\.\.` check etc.) even for paths that have already been normalised. After the first `pathService.normalize()` call, a traversal-free path cannot introduce `..` — the redundant sanitize on the already-normalised `normalized` variable adds pattern-matching overhead on every call. Consider a `isSafe(Path)` flag set post-normalisation to short-circuit the second sanitize.

### TH-I2 — `SecurityService.validatePath()` calls `toRealPath()` which does a filesystem syscall (MEDIUM)

`SecurityService.validatePath()` calls `path.toRealPath()` to resolve symlinks. This is a blocking filesystem call (kernel stat) on every tool invocation. For high-frequency metadata calls (`exists`, `summary`, `info`) this adds ~1–2ms per call on networked filesystems. The call is unavoidable for security but should only be done once per request — currently it may be called both in `SecurityService` and again in the service method.

### TH-I3 — `ServerLifecycleService` manages HTTP server processes with `ProcessBuilder` polling (MEDIUM)

`ServerLifecycleService.checkProcess()` (used in `status` and `ensure`) polls process liveness with `process.isAlive()` in a loop, sleeping between polls. For `ensure` (start a lazy server), this busy-polls until the server is up. Virtual thread sleep is cheap, but the pattern could use `process.waitFor(timeout, unit)` + a `CompletableFuture` for the health-check, which would free the calling thread entirely rather than blocking it in a sleep loop.

### TH-I4 — `JsonRpcWriter` uses `synchronized` on the output stream (LOW/CONFIRM GOOD)

`JsonRpcWriter` correctly synchronises writes to stdout. This is the right pattern for STDIO MCP — no change needed. Confirming as deliberate and correct.

---

## 5. Correctness / API Issues

### C-I1 — `FileReadService.normaliseOptions()` absent — confirms CC T1 (HIGH/BUG)

Confirmed. `FileWriteService` line ~120 calls `normaliseOptions(arguments.options)` which handles `options` arriving as a pre-serialised JSON String. `FileReadService` does a raw cast. This will silently discard all options for any MCP client that serialises options as a JSON string sub-object.

### C-I2 — `doDelete()` inconsistent error response — confirms CC T6 (LOW)

Confirmed. The `textResponse(requestId, [success: false, reason: '...'])` path is inconsistent with all other error exits. One-line fix.

### C-I3 — `doChunkWrite` does not validate `chunkIndex` ordering (MEDIUM)

`ChunkBufferService.addChunk(sessionId, chunkIndex, content)` does not enforce monotonic chunk ordering. A client could send chunk 3 before chunk 1 and the join on `finalise_write` would still produce a scrambled file (it joins in insertion order, not by index). The `finalise_write` should sort by `chunkIndex` before joining/writing, or reject out-of-order chunks.

### C-I4 — `doReplace` uniqueness check scans entire file twice (LOW)

`doReplace` reads the file once to count occurrences of `oldText` (uniqueness check), then reads again to perform the replacement. Both reads go to disk. The occurrence count and the single replacement could be done in one streaming pass.

---

## 6. Streaming + Promise Combination Opportunities Not in CC Assessment

### SP-I1 — `doProjectScan` already excellent — extend pattern to `doGradle` and `doMvn` (MEDIUM)

`doProjectScan` uses `Promises.all([p1, p2, p3])` for three parallel subprocess calls. `doGradle` and `doMvn` run single subprocesses synchronously on the MCP thread. Wrapping them in `Promises.async { }` would free the MCP thread during the Gradle/Maven execution (which can be seconds). Not strictly necessary since the caller is already on a virtual thread, but it's consistent with the pattern.

### SP-I2 — `FileSearchService.contentSearch()` could fan-out across files with `Promises.all()` (MEDIUM)

`FileSearchService` iterates files sequentially. For large codebases with many files, a fan-out via `Promises.all(files.collect { f -> Promises.async { searchFile(f) } })` would parallelise the grep across all files. Cap concurrency with a semaphore or batch size to avoid overwhelming the file system.

---

## 7. Priority Table (Independent View)

| ID | Severity | File | Issue |
|----|----------|------|-------|
| S-I1 | HIGH | FileReadService | doRead full String load — 3× heap; stream into chunks |
| C-I1 | HIGH | FileReadService | normaliseOptions() missing — options silently dropped |
| S-I4 | MEDIUM | ChunkBufferService/FileWriteService | finalise_write joins all chunks into one String |
| C-I3 | MEDIUM | ChunkBufferService | chunk index ordering not enforced on finalise |
| S-I2 | MEDIUM | FileReadService | doMulti no size guard per file |
| S-I3 | MEDIUM | AstStructureScanner | no file-size guard before full AST parse |
| TH-I2 | MEDIUM | SecurityService | toRealPath() called multiple times per request |
| TH-I3 | MEDIUM | ServerLifecycleService | busy-poll in ensure; use waitFor+CompletableFuture |
| M-I1 | MEDIUM | ChunkBufferService | TTL expires active sessions; need touchSession() |
| M-I2 | LOW | UsageTracker | flushToDb holds dbLock for N individual INSERTs; use executeBatch() |
| P-I2 | MEDIUM | PromiseImpl | no default timeout; .get() without timeout hangs forever |
| SP-I2 | MEDIUM | FileSearchService | contentSearch sequential; parallelise with Promises.all |
| C-I4 | LOW | FileWriteService | doReplace reads file twice; one-pass possible |
| C-I2 | LOW | FileLifecycleService | doDelete soft-false vs McpResponse.error (confirms CC T6) |
| P-I1 | LOW | PromiseImpl | VIRTUAL_EXECUTOR on wrong class; move to Promises |
| M-I3 | LOW | StructureCache | CacheEntry materialises full structure even for hash-only callers |
| TH-I1 | LOW | AbstractFileService | redundant sanitize post-normalise |

---

## 8. What This Assessment Adds vs CC Assessment

The CC assessment (`cc-performance-assessment.md`) is comprehensive and accurate. This independent view adds:

- **S-I4**: `finalise_write` chunk-join issue (not in CC)
- **C-I3**: Out-of-order chunk index on `finalise_write` (not in CC)
- **M-I1**: ChunkBufferService TTL race with active sessions (not in CC)
- **TH-I3**: `ServerLifecycleService` process polling pattern
- **SP-I2**: `FileSearchService` fan-out opportunity
- **P-I2**: Missing default timeout risk on Promise `.get()`
- **S-I3**: No size guard before AstStructureScanner

Items confirmed from CC: M1, T1, T2, T3, T4, T5, T6, P2, M3, D1, D2.

---

*This document is input to a three-way consolidation by Claude Desktop.*  
*Consolidation output → fix brief → Claude Code → rebuild → v0.8.0 → git push.*
