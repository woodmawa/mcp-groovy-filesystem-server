# SESSION FIX PLAN — mcp-groovy-filesystem-server
**Version being fixed:** v0.7.29  
**Target version:** v0.7.30  
**Source assessment:** cd-performance-assessment.md (written 2026-03-03)  
**Fix plan detail:** fix-plan-v0.7.29.md (also in project root)

---

- [x] Fix 1  DONE
- [x] Fix 2  DONE
- [x] Fix 3  DONE
- [x] Fix 4  DONE
- [x] Fix 5  DONE
- [x] Fix 6  DONE
- [x] Fix 7  DONE
- [x] Fix 8  DONE
- [x] Fix 9  DONE
- [x] Fix 10  DONE
- [x] Fix 11  DONE
- [x] Fix 12  DONE
- [x] Fix 13  DONE
- [x] Fix 14  DONE
- [ ] Opportunity A  DEFERRED
- [ ] Opportunity B  DEFERRED
- [x] Build passing  DONE (exitCode=0, 2026-03-03)
- [x] Jar copied to claude-sync/jars  DONE
- [x] Desktop config updated  DONE (references 0.7.30)
- [x] Claude Code config updated  DONE (uses HTTP URL, version-agnostic)
- [ ] Git commit  PENDING
- [x] README updated  DONE (2026-03-03)
---

## FIX DETAILS (quick reference for session resume)

### FIX 1 — HIGH | FileWriteService.doReplace | size guard
File: `src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy`
Before `byte[] rawBytes = Files.readAllBytes(...)` at ~line 238, insert:
```groovy
long fileSizeKb = Files.size(Paths.get(normalized)) / 1024
if (fileSizeKb > readChunkThresholdKb) {
    return McpResponse.error(requestId, -32602,
        "replace: file is ${fileSizeKb}KB (threshold ${readChunkThresholdKb}KB). Use patch (line-range edit) for large files.")
}
```

### FIX 2 — HIGH | FileWriteService.doMultiReplace | size guard
File: same as Fix 1
Before `byte[] rawBytes = Files.readAllBytes(...)` at ~line 328, same guard as Fix 1.

### FIX 3 — HIGH | FileWriteService.doReplace | remove O(n*m) nearest-match scan
File: same as Fix 1
DELETE this block entirely (~lines 260-270):
```groovy
if (nearestLine < 0 && firstLine.length() <= 80) {
    fileLines.eachWithIndex { String fl, int idx ->
        int common = fl.trim().toSet().intersect(firstLine.toSet()).size()
        int score  = firstLine.length() - common
        if (score < bestScore) { bestScore = score; nearestContent = fl; nearestLine = idx + 1 }
    }
}
```
Also cap the first eachWithIndex scan to `.take(500)`.

### FIX 4 — MEDIUM | FileSearchService.searchFileContent | explicit withReader
File: `src/main/groovy/com/softwood/mcp/service/FileSearchService.groovy`
Line ~210: replace `file.eachLine('UTF-8') { ... }` with:
```groovy
new File(file.toString()).withReader('UTF-8') { BufferedReader br ->
    br.eachLine { String line -> ... }
}
```

### FIX 5 — MEDIUM | ToolsService.runToolRaw | cap stdout inside loop
File: `src/main/groovy/com/softwood/mcp/service/ToolsService.groovy`
In the two virtual-thread closures (~lines 294-298), add captured counter:
```groovy
int capturedOut = 0
Thread stdoutThread = Thread.ofVirtual().start({
    process.inputStream.eachLine { String line ->
        if (capturedOut < 50000) {
            String s = sanitize(line)
            stdout.append(s).append('\n')
            capturedOut += s.length() + 1
        }
        // else: drain without storing
    }
})
```
Same pattern for stderr with cap 5000. Remove downstream `.take()` calls.

### FIX 6 — MEDIUM | ExecuteService.runProcess | cap stdout inside loop
File: `src/main/groovy/com/softwood/mcp/service/ExecuteService.groovy`
Same pattern as Fix 5 but use `maxStdout`/`maxStderr` from options (already available).
Replace virtual-thread closures (~lines 232-236). Remove `.take(maxStdout)` and `.take(maxStderr)` at ~lines 259-260.

### FIX 7 — MEDIUM | McpController.estimateResponseSize | zero-copy fix
File: `src/main/groovy/com/softwood/mcp/controller/McpController.groovy`
Replace:
```groovy
private static int estimateResponseSize(McpResponse response) {
    try { return response?.result?.toString()?.length() ?: 0 } catch (Exception e) { return 0 }
}
```
With:
```groovy
private static int estimateResponseSize(McpResponse response) {
    try {
        List content = response?.result?.content as List
        return (content?.first() as Map)?.text?.length() ?: 0
    } catch (Exception ignored) { return 0 }
}
```

### FIX 8 — MEDIUM | McpController | virtual threads for async dispatch
File: `src/main/resources/application.properties`
Add line: `spring.threads.virtual.enabled=true`
(Check it's not already there first.)

### FIX 9 — MEDIUM | UsageTracker | per-op connection + WAL
File: `src/main/groovy/com/softwood/mcp/service/UsageTracker.groovy`
1. Remove `volatile Connection dbConn` field and `dbLock` field.
2. Rewrite `withConnection` to open short-lived connection:
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
3. Simplify `init()` — remove `dbConn` assignment, just call `withConnection { ensureSchema() }` then `loadTodayFromDb()`.
4. Simplify `destroy()` — remove `dbConn?.close()`.

### FIX 10 — LOW | FileListService | stream.each short-circuit
File: `src/main/groovy/com/softwood/mcp/service/FileListService.groovy`
In `doChildren` (~line 110), `doList`, and `doSizes` (~line 182):
Replace `stream.each { if (results.size() >= max) return; ... }` with:
```groovy
stream.filter { Path p ->
    compiled == null || (p.fileName.toString() =~ compiled)
}.limit(max).each { Path p ->
    results << pathToMap(p)
}
```

### FIX 11 — LOW | FileReadService.doDiff | size pre-check
File: `src/main/groovy/com/softwood/mcp/service/FileReadService.groovy`
After path normalisation in `doDiff()`, add:
```groovy
long sizeAKb = Files.size(Paths.get(normalizedA)) / 1024
long sizeBKb = Files.size(Paths.get(normalizedB)) / 1024
if (sizeAKb > readChunkThresholdKb || sizeBKb > readChunkThresholdKb) {
    return McpResponse.error(requestId, -32602,
        "diff: files exceed ${readChunkThresholdKb}KB threshold. Use grep or range for targeted comparison.")
}
```

### FIX 12 — LOW | FilesystemTelemetryService | bound sessionCallCache
File: `src/main/groovy/com/softwood/mcp/service/FilesystemTelemetryService.groovy`
Line ~81, change:
```groovy
if (!isRepeat) sessionCallCache.put(cacheKey, new Date().toInstant().toString())
```
To:
```groovy
if (!isRepeat && sessionCallCache.size() < 1000) {
    sessionCallCache.put(cacheKey, new Date().toInstant().toString())
}
```

### FIX 13 — LOW | AstStructureScanner | shared static CompilerConfiguration
File: `src/main/groovy/com/softwood/mcp/service/AstStructureScanner.groovy`
Add class-level field:
```groovy
private static final CompilerConfiguration SHARED_CONFIG = new CompilerConfiguration().tap {
    setTolerance(10)
}
```
In `scanGroovyAst()`, replace `CompilerConfiguration config = new CompilerConfiguration(); config.setTolerance(10)` with `SHARED_CONFIG`.
Change `new CompilationUnit(config)` to `new CompilationUnit(SHARED_CONFIG)`.

### FIX 14 — LOW | StructureCache | evictIfNeeded outside pathLock
File: `src/main/groovy/com/softwood/mcp/service/StructureCache.groovy`
In `getStructure()`: move `evictIfNeeded()` call from inside `synchronized(pathLock)` to just before `Object pathLock = computeLocks.computeIfAbsent(...)`.
Same in `getHash()`.

### OPPORTUNITY A — FileListService.buildTree parallel child scan
Defer until all 14 fixes done and build is clean.

### OPPORTUNITY B — FileSearchService.doContentSearch parallel scan
Defer until Opportunity A is done.

---

## COMMIT CHECKPOINTS

| Fixes | Commit message |
|---|---|
| 1, 2, 3 | `fix: HIGH-1-3 FileWriteService size guards and remove O(nm) nearest-match scan` |
| 4, 5, 6, 7 | `fix: MEDIUM-4-7 FileSearch withReader, stdout cap in loop, McpController estimateResponseSize` |
| 8, 9 | `fix: MEDIUM-8-9 virtual threads app.properties, UsageTracker per-op WAL connection` |
| 10, 11, 12, 13, 14 | `fix: LOW-10-14 stream limit, doDiff guard, telemetry cache bound, shared CompilerConfig, evict lock order` |
| A, B | `perf: OPP-A-B parallel buildTree and parallel FileSearch content scan` |
| Final | Bump version to v0.7.30. Copy jar. Update configs. |

---

## POST-BUILD STEPS (when all fixes done and build passes)

### 1. Find the built jar
```
C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server\build\libs\mcp-groovy-filesystem-server-0.7.30.jar
```

### 2. Copy jar to claude-sync
```powershell
Copy-Item "build\libs\mcp-groovy-filesystem-server-0.7.30.jar" "C:\Users\willw\claude-sync\jars\"
```

### 3. Update claude-sync/mcp-http-servers.json
Change filesystem server jar entry from `0.7.29` to `0.7.30`.

### 4. Update Claude Desktop config
File: `C:\Users\willw\AppData\Roaming\Claude\claude_desktop_config.json`
Update the filesystem server jar path/version reference.

### 5. Update Claude Code config
File: `C:\Users\willw\.claude\claude.json` (or equivalent)
Update the filesystem server jar path/version reference.

### 6. Tell Will to restart Claude Desktop + Claude Code to pick up new jar.

---

## RESUME INSTRUCTIONS (after a reset)
1. Start servers: use groovy-filesystem server_lifecycle start_eager
2. Start context session: context_lifecycle start
3. Read this file to know where we are
4. Update the STATUS checklist at the top as fixes complete
5. Continue from the first unchecked fix
