# Changelog — mcp-groovy-filesystem-server

All notable changes to this project are documented in this file.
Entries are **oldest-first** — new entries are always appended at the bottom.

---

## [0.7.49]
ContextServerClient postToContextServer+postWithTimeout endpoint / -> /mcp (fixes 404 on file-structure registry calls).

## [0.7.50]
Fix sanitize() stripping non-ASCII Unicode (em-dashes, smart quotes, accented chars) — removed \p{Print} filter that only matched ASCII; added NFC normalization fallback in replace/multi_replace; normalized oldText line endings in doReplace.

## [0.8.5]
Streamable HTTP transport (POST /mcp + GET /mcp SSE) — HttpMcpController; keeps legacy McpSseController for backward compat.

## [0.8.7]
Fix mkdirs silent failure — WriteUtils.atomicWrite redundant !Files.exists guard raced with createDirectories.

## [0.8.8]
Fix mkdirs boolean cast — Boolean.valueOf(toString()) not 'as boolean' under @CompileStatic.

## [0.8.9]
CODE-DEFECT-004 ContextServerClient liveness guard (contextServerReachable flag, ConnectException silenced after first hit); SKILL-UPDATE-004 server_transform file-type guard with descriptive error for non-Groovy/Java targets.

## [0.8.10]
@PostConstruct autoStartHttpCompanions — servers with autoHttpCompanion:true in mcp-http-servers.json start as HTTP child processes when filesystem stdio server starts; killed cleanly on DT/CC exit via existing stopAllOnShutdown().

## [0.8.11]
OfficeDocumentHandler — read_office/write_office actions for .xlsx/.docx/.pptx via Apache POI; wired into FileReadService + FileWriteService dispatch.

## [0.8.12]
OfficeDocumentHandler DSL bridge — XlsxAdapter/DocxAdapter/PptxAdapter via GCU; legacy flat-map paths preserved.

## [0.8.13]
ServerLifecycleService port-conflict race fix — killStalePidIfPresent guards on isPortListening + retry loop; HttpMcpController session-id poll noise already at DEBUG.

## [0.8.18]
Toon encoding support on file_read action=list (toon=true in options).

## [0.8.21]
file_read action=list returns listing_hash + knownHash short-circuit; file_list action=list wired to listing cache; file_read action=multi_grep — grep one pattern across options.paths[] in one call.

## [0.8.22]
Fix multi_grep path guard — add multi_grep to path-exempt action list so it works without a path param. SKILL.md/CLAUDE.md/USAGE.md/README doc sync.

## [0.8.23]
file_list action=list returns listing_hash + knownHash short-circuit (parity with file_read action=list).

## [0.8.25]
Fixed the create directory under missing parent problem.

## [0.8.31]
BUGFIX ServerLifecycleService.startServer() now passes -Dspring.profiles.active=http + -DMCP_MODE=http to ProcessBuilder cmd. Without these, HTTP companion processes inherited/defaulted to stdio profile, binding no port and running as useless duplicate stdio processes.

## [0.8.33]
replace_section headingStyle=text for arbitrary anchor matching in source files.

## [0.8.35]
FileReplaceService replace+multi_replace: add NFKC normalisation fallback after NFC (handles em-dash/en-dash variants, smart quotes). Fix contradictory error guidance — patch is now correctly recommended when oldText contains non-ASCII. See practice #214.

## [0.8.40]
Fix Unicode corruption on stdio transport. Two changes: (1) StdioMcpServer.groovy — InputStreamReader(System.in) now uses explicit StandardCharsets.UTF_8 (was JVM default Cp1252 on Windows, corrupted U+2192 and other non-Latin-1 chars in tool params causing file_write replace to fail silently). (2) McpGroovyFileSystemServerApplication.main() — System.setOut(PrintStream UTF-8) so JsonRpcWriter output also encodes Unicode correctly.

## [0.8.41]
ServerLifecycleService.doEnsure() early-exit via isPortListening(port,1,0) before startServer. ContextServerClient @PostConstruct eagerResolveSessionId() — async session ID prime at startup eliminates 'new session unknown' log spam. FileReplaceService new replace-threshold-kb=150 separate property. FileReadService unknown-action error lists valid actions. ExecuteService workingDir error shows allowed dirs.

## [0.8.42]
ContextServerClient.resolveSessionId() fix — null result from JDBC no longer cached (was blocking retries). HTTP fallback log message clarified as expected-in-MCPB. resolveSessionId() now retries JDBC on every call until active_session row exists. Fixes session_working_files always getting session_id='unknown' in MCPB mode.

## [0.8.44]
FS-T7: FileStructureReader.doGetMethod() surfaces fallback:true + fallback_note when regex scanner used (AST compile error). FS-T8: ChunkBufferService.getWriteChunkStatus() + FileChunkWriter.doChunkStatus() + FileWriteService action=chunk_status — returns receivedChunks/missingChunks/ready:bool so callers can verify all chunks arrived before finalise_write.

## [0.8.46]
FS-T6 — doPatch empty/missing options.replacements now returns textResponse([error:..., hint:...]) instead of McpResponse.error(-32602). DT was rendering -32602 as opaque 'Tool execution failed' rather than showing the message. textResponse wraps in MCP content block so DT shows the error map as readable text.

## [0.8.47]
Fix A'' — positionalReplace in doReplace/doMultiReplace: unicode NFC/NFKC normalisation now uses per-position replace so only the matched region is rewritten, not the whole file (ideation #35). Fix B — per-entry normalisation tracking in doMultiReplace: each entry independently resolved, no global NFKC clobber of ASCII entries. Fix C — sequential boundary patch (endLine==EOF) within 60s now BLOCKED with clear error. Fix D — lines_shifted added to all patch responses. Fix E — tail_content (last 5 lines) included in boundary patch responses. Fix F — pre-apply brace balance check for .groovy/.java boundary patches. Fix G — removed_lines snippet in patch response.

## [0.8.48]
RCA-1 McpResponse.toolError() — all tool handler errors now return isError:true content (not JSON-RPC error object); Claude Desktop was silently swallowing -326xx errors. RCA-2 doMultiReplace: suffix/prefix partial overlap detection + simulation pass before apply (entry-makes-entry-unfindable fails whole batch). RCA-3 requires_reread:true on boundary patches (startLine==1 OR endLine==last); recentPatches only updated after successful write. RCA-5 doMultiReplace applies in reverse position order. RCA-6 FileTransformService all errors via toolError. RCA-7 checkBraceBalance runs on simulated result BEFORE atomicWrite. RCA-8 legacy specs updated to new isError:true contract. TDD: FileContractSpec CT-1..CT-13 all green.

## [0.8.49]
Fix F extended — FilePatchService brace delta check now covers ALL mid-file .groovy/.java patches, not just boundary patches. New algorithm: per-replacement brace delta check (removedLines delta must equal newText delta). Catches the exact failure mode where a patch removes 'if (!x) {\n body' (delta +1) but replaces with flat content (delta 0), orphaning the closing } in the surrounding scope. TDD: CT-14 (delta mismatch blocked) + CT-15 (balanced replacement passes). Full suite CT-1..CT-15 green. Root cause of PipelineExecutionService corruption session.

## [0.8.50]
RCA-1 COMPLETION — bulk McpResponse.error(requestId,-326xx) -> toolError() across 14 service files (92 replacements). v0.8.48 claimed this fix but only converted a subset of call sites; the remainder were silently left as JSON-RPC error objects that Claude Desktop swallows. Root cause of incomplete 0.8.48 fix: fix applied manually file-by-file using server_transform/multi_replace with too-narrow scope; deep service subpackages (ExecuteService, ToolsService, FileChunkWriter, FileContentReader, FileMetaReader, FileStructureReader, ReadResponseHelper, OfficeDocumentHandler) were missed entirely. Fix verified via Python bulk regex replace + post-scan clean check. 92 occurrences in 14 files. McpController.groovy + HttpMcpController.groovy excluded (correct to use .error() for protocol-level dispatch). FileReplaceService already clean from 0.8.48. TDD: FileContractSpec CT-1..CT-15 preserved; CT-16 added (containment overlap surfacing). FileServicesSmokeSpec: chunk_status unknown-session test updated to isError:true contract.

## [0.8.51]
ServiceErrorContractSpec — static analysis contract test added. Walks src/main/groovy recursively via Files.walkFileTree, asserts zero matches of McpResponse.error(requestId,-326xx) in service handlers. Excludes McpController.groovy + HttpMcpController.groovy (protocol level, correct). Prevents silent re-introduction of swallowed errors in any future edit. TDD: 59 tests, 0 failures, 0 skipped (+2: zero-violations scan + exclusions sanity check).

## [0.8.52]
McpResponse.error() renamed to protocolError() — compiler-enforced + spec-enforced contract. McpController + HttpMcpController updated to call protocolError(). ServiceErrorContractSpec rewritten (v0.8.52): 4 tests, fully recursive via Files.walkFileTree. Test 1: McpResponse.error() must not exist anywhere (compile-level removal). Test 2: McpResponse.protocolError() must not appear in non-controller service files. Test 3: controller files exist and actually use protocolError() (exclusion is real). Test 4: src/main/groovy accessible + non-empty (guards vacuous scan on CWD drift). findProjectRoot() resolves project root by walking up to build.gradle — no fragile CWD reliance. TDD: 61 tests, 0 failures, 0 skipped.

## [0.8.53]
Fix C — session-scoped range read cache intercept (ContextServerClient.checkRangeCache/recordRangeCacheAsync, FileReadService range+get_method cache check/record). CT-19..CT-23 added to FileContractSpec (all pass). CT-17 pre-existing failure logged to idea #52 (ReplaceMethodTransformer signature validation missing).

## [0.8.54]
Fix D — action=multi guard for unranged ontology-indexed files (ContextServerClient.isOntologyIndexed, FileReadService BLOCKED_UNRANGED_INDEXED_READ per-path). Fix F FS — ContextServerClient.invalidateFileAsync (POST /invalidate), FileWriteService post-write invalidation hook. Fix #52 — ReplaceMethodTransformer signature validation (CT-17 now green). FileContractSpec CT-23: 23/23 pass.

## [0.8.58]
MCP-EFFICIENCY-BUILD-BRIEF-V1 Turn 4 — New-3: _tok estimate injected into every textResponse payload (Map payloads only, chars/4). AbstractFileService.textResponse enriched. CT-32: append_section wrong option name returns structured error. FileContractSpec 32 tests. Practice #424 (section transforms use content not newContent). Turn 2 — Fix C (get_method now records real startLine/endLine to range cache, parseResponsePayload helper added). CT-30 (server_transform extension guard), CT-31 (replace_method wrong option name). Practice #422 (replace_method requires newBody not newMethod). Fix E — InsertBeforeMatchTransformer: matchLast=true (alias for occurrence=-1) and fromLine=N (1-based, skip occurrences before line N) options. Both compose: fromLine restricts window first, matchLast/occurrence resolves within window.

## [0.8.59]
CT-33..CT-53 contract tests (checksum/stat/exists/diff/write/append/head/tail/info/grep — 21 new, total 100). Idea #62: file_read action=multi with knownHashes or compact=true now exempt from BLOCKED_UNRANGED_INDEXED_READ guard. Idea #16: server_transform insert_before_match — options.matchIsRegex=true compiles match as Pattern.find() not contains(). copyToJarsDir: auto-updates mcp-http-servers.json jar ref + server_versions in best_practices.db on every deploy.

## [0.8.60]
CT-54..CT-58 from log analysis 2026-04-16. doGetMethod: add isFile() guard before hash-check (was missing — directory paths returned 'method not found' instead of toolError). CT-57: patch without expectedHash confirmed working (degraded safety). CT-54/55/56: grep/read/range on dir already toolError-safe — contracts added to lock that in. URLClassLoader fix: use Thread.currentThread().contextClassLoader as parent (not null) so java.sql.Driver is visible to sqlite-jdbc driver loaded in isolated CL.

## [0.8.61]
CT-59..CT-63 replace contract tests (happy-path, multi-match ambiguity, no-hash degraded safety, multi_replace happy-path, missing-file guard). FileReplaceService.doReplace: Files.exists() check added before Files.size() — missing file now returns toolError instead of uncaught NoSuchFileException (CT-63). CT-60 spec assertion updated to match FS actual wording ('appears N times / must be unique'). Practice #433. 110 tests total, all green.

## [0.8.62]
CT-64..CT-65 non-ASCII oldText contracts. CT-64: replace where oldText contains non-ASCII chars (e.g. section-sign U+00A7 in doc comments) returns toolError with non_ascii_hint field directing use of action=patch. CT-65: ASCII-only not-found omits non_ascii_hint (no false positives). Root cause: G4 session AwToCsSignalClient resolveActiveSession() doc comment had \u00a7 in oldText — FS correctly blocked it. Behaviour was already correct; contracts added to lock it in. 112 tests total.

## [0.8.63]
CT-66b..CT-69 bare-box-drawing-at-line-start contracts + FS-T10 check. Root cause (G1 session): multi_replace oldText ended mid-section-divider, leaving trailing \u2500\u2500\u2500 chars without // prefix -> Groovy 'Unexpected character'. Fix: checkBareBoxDrawing() runs on simulated result for .groovy/.java/.kt files before atomicWrite in both doReplace and doMultiReplace. Returns structured error with bare_box_drawing_hint if any line starts with U+2500..U+257F outside a comment. .txt/.md/.adoc exempt. CT-67 (correct // prefix passes) + CT-69 (.txt exempt) green.

## [0.8.64]
Tool hint clarity — knownHash verbose description updated for action=range to warn that passing knownHash suppresses content (returns unchanged:true). Prevents caller confusion from misleading hint wording.

## [0.8.65]
D5 fix — McpController.handleRequest() session ID hardcode resolved.

## [0.8.66]
CT-74 fix — doPatch now validates startLine/endLine presence before int cast. Missing line numbers return structured toolError instead of NullPointerException/ClassCastException.

## [0.8.67]
CT-DR-1..CT-DR-4 destructive-replace ratio guard. doReplace now rejects when removed content exceeds threshold and added content is less than 20% of removed (guards accidental wipe). force=true escape hatch available. Structured error includes removed/added char counts.

## [0.8.68]
CT-77..CT-79 patch expectedRemovedText content guard. doPatch validates that the lines being replaced actually match options.expectedRemovedText when supplied. Prevents wrong-range patches from silently corrupting files.

## [0.8.69]
FIX-6A — BLOCKED_UNRANGED_INDEXED_READ error now includes known_hash field so callers can immediately pass knownHash on retry without a separate read round-trip.

## [0.8.70]
FileReadService.getToolDefinitions() now DB-driven via ContextServerClient.getHelpSection(). Tool description for file_read loadable from CS help_sections at startup; falls back to DEFAULT_DESC if CS unreachable. DT restart required to reload after CS section update.

## [0.8.71]
CT-80/CT-81 — patch paren-delta guard (FilePatchService). Patch on .groovy/.java that removes/adds unbalanced parentheses is now a hard reject (file NOT modified). Mirrors the brace-delta guard added in 0.8.49. CT-80: delta mismatch blocked. CT-81: balanced replacement passes.

## [0.8.72]
CT-RW-1..5 — replace structural safety (TDD). CT-RW-1: replace on .groovy/.java with unbalanced brace in newText is now a hard error (file NOT modified) — same as patch/multi_replace. Previously only a warning. CT-RW-3: DESTRUCTIVE_REPLACE guard now accepts force=true escape hatch for legitimate large deletions. CT-RW-4: DESTRUCTIVE_REPLACE error message now includes 'pass options.force=true' hint. CT-RW-5: replace with oldText not found returns clear not-found error (pre-existing behaviour, now contract-tested). Full suite: 143 tests, 0 failures.

## [0.8.73]
CT-EH-1 — expectedHash is now MANDATORY for replace|patch|multi_replace (FS TDD). Root cause: absent expectedHash allowed silent double-writes and cross-session file bleed. FileReplaceService.doReplace: warn->toolError when expectedHash absent. FileReplaceService.doMultiReplace: same. FilePatchService.doPatch: same. FileWriteService.promoteTopLevelParams: fix — when oldText+expectedHash both top-level, case 'replace' block now seeds from merged (not raw options), so expectedHash carried forward. CT-EH-1a/b/c: reject replace|multi_replace|patch when expectedHash absent. CT-EH-2: stale expectedHash -> drift guard fires, file unchanged. CT-EH-3: correct expectedHash -> succeeds (guard not over-blocking). CT-57/CT-61: updated from 'succeeds without hash' to 'rejected, file unchanged'. CT-63: updated to pass dummy expectedHash so file-not-found fires (not hash guard). FileServicesSmokeSpec patch tests: expectedHash added. FileWriteService.getToolDefinitions: compact+verbose descriptions updated to reflect mandatory. CS tool_descriptions row inserted for file_write with correct mandatory language. CS help_sections tool_desc_file_read updated: last line corrected from 'optional' to 'mandatory'. Full suite: 153 tests, 0 failures.

## [0.8.74]
DB-driven tool description for FileWriteService (idea #109 completion). @PostConstruct init() loads tool_desc_file_write (compact) and tool_desc_file_write_verbose from CS help_sections at FS startup. Falls back to DEFAULT_DESC_COMPACT/VERBOSE static constants if CS unreachable. help_sections rows seeded: tool_desc_file_write + tool_desc_file_write_verbose. Tool description for file_write is now updatable via CS context_write without a build or jar deploy. DT restart required to reload after CS section update. ContextServerClient.getHelpSection() already implemented (v0.8.70) — reused. FileReadService (v0.8.70) + FileWriteService (v0.8.74) are now both DB-driven. Idea #109 status updated to delivered.

## [0.8.76]
knownHash hint injection. ReadResponseHelper.injectKnownHashHint() added. FileContentReader.doRead/doRange and FileStructureReader.doGetMethod now inject _knownhash_hint into every content response where options.knownHash was NOT passed. Root cause: @PostConstruct fires before CS HTTP companion (:8082) is ready to accept connections. ServerLifecycleService.autoStartHttpCompanions() spawns the companion process but returns immediately after fork — :8082 is not yet listening when FileReadService/FileWriteService init() call getHelpSection(). Fix: both init() methods now retry 3 times (0ms / 300ms / 700ms) before falling back to DEFAULT_DESC. Covers the typical 200-500ms companion startup window. Safe: Thread.sleep on Spring @PostConstruct thread; no blocking on hot path. Fallback unchanged — DEFAULT_DESC_* always used if all retries fail.

## [0.8.77]
FIX-KH-AUTO: ContextServerClient.storeFileHashAsync/lookupFileHash via CS /fileHashCache. ReadResponseHelper: checkKnownHash(autoLookup=true) for doRead only (Option A, brief s18.3); storeAndHintKnownHash replaces injectKnownHashHint; feature flag auto-kh-lookup.enabled.

## [0.8.78]
FIX-KH-AUTO hardening. ReadResponseHelper.autoKhHintsSuppressed flag: hint suppressed when autoLookup active+CS available (token noise reduction). FileHashAutoLookupSpec CT-KH-AUTO-9..13 (malformed hash, persistent null, same-length content change, hint suppression, hint restored when auto disabled).

## [0.8.79]
FIX-5 circuit breaker (CLOSED/OPEN/HALF_OPEN) replaces permanent boolean latch in ContextServerClient. Fixes KH-AUTO being permanently disabled for entire sessions when CS starts after FS. CS recovers after backoff [5s,15s,30s,60s]. onCsSuccess() closes circuit on any successful fileHashCache hit. All contextServerReachable references replaced with isCsReachable()/onCsConnectFailure()/onCsSuccess(). CT-KH-AUTO-14..17 added.

## [0.8.80]
FIX-6 shadow auto-KH for range/get_method (E2E-FIX-BRIEF-2026-05-01). ReadResponseHelper.shadowAutoKhProbe() called after doRange (FileContentReader) and doGetMethod (FileStructureReader) builds its response map. Shadow mode: CS hash lookup + disk hash compare; if match annotates _shadow_kh:true, if stale annotates _shadow_kh:false and updates cache async. Never returns unchanged:true (read semantics unchanged). Graduate to active once shadow accuracy >=99% over 3 sessions. New field: ReadResponseHelper.autoKhShadowEnabled (@Value auto-kh-shadow.enabled, default true). CT-KH-AUTO-18..20 added and GREEN (20/20 total).

## [0.8.81]
FIX-KH-RANGE-AUTO — server-side auto range-cache lookup without caller supplying knownHash. FileReadService: @Autowired StructureCache; case 'range' derives fileHash from structureCache before the existing Fix C gate, enabling checkRangeCache to fire on repeat reads. case 'get_method': same auto-lookup using (0,0) sentinel entry; recordRangeCacheAsync now records both the actual line range AND the (0,0) sentinel for next-call detection. ReadResponseHelper.storeAndHintKnownHash: hint suppression now scoped to autoStore=true (whole-file reads only) — range/get_method always emit _knownhash_hint. CT-FS-RANGE-AUTO-1..4, CT-FS-GM-AUTO-1, CT-FS-HINT-RANGE-1 added and GREEN. Source: KH-BOOTSTRAP-ANALYSIS-2026-05-03.md v2.0 PART 1 FIX.

## [0.8.82]
OW-3 fix — ContextServerClient.resolveSessionId stale cache root cause. Removed permanent cache-on-first-resolve pattern. resolveSessionId now calls telemetryService.readActiveSessionId() on every invocation and compares against cached value. If live active_session differs (DT restart or new context_lifecycle start), cache is updated before returning. JDBC read is sub-millisecond; cost is negligible. Root cause: cache-forever meant all recordRangeCacheAsync writes and checkRangeCache lookups used the prior session ID after a DT restart, causing 100% cache misses and real_kh_pct stuck at ~15% despite FS 0.8.81 auto-range-cache.

## [0.8.83]
SecurityService — make DANGEROUS_SCRIPT_PATTERNS configurable via application.yml. Replaced hardcoded private static final List with @Value-bound dangerousPatternsConfig, allowedLiteralsConfig, and executorExtraPatternsConfig. Allowlist scrubs known-safe literals (Class.forName('org.sqlite.JDBC')) before pattern check so JDBC boilerplate is never blocked. Per-executor extras let python/bash keep .execute() blocked while groovy eval stays clean. ProcessBuilder removed from global list (internal tooling). application.yml: new mcp.script.dangerous-patterns, allowed-literals, executor-extra-patterns keys. No behaviour change for existing blocked patterns.

## [0.9.0]
FS Read/Write Architecture refactor — clean-sheet extraction of six helper classes resolving 13 accumulated defects across FileReplaceService, FilePatchService, FileContentReader, and WriteUtils. Source: FS-READWRITE-ARCHITECTURE-BRIEF-0.9.0.md.

New helpers: WriteContext (D1/D3/D4/D13 — unified file load, size cap, strict charset decode, binary guard), TextMatcher (D2 — NFC/NFKC/box-drawing Unicode match with original-span offsets, eliminating wrong-offset replacement bug), MultiReplaceValidator (D9 — three validation phases extracted from doMultiReplace), StructuralGuard (D5/D8 — pre-write brace/paren/bare-box guards with string-strip heuristic; dead post-write advisory removed; brace_warning field eliminated), DestructiveChangeGuard (D6/D10 — bounded LRU maps capped at 200 entries; ratio guard now uniform across replace/multi_replace/patch), WriteCommitter (D11 — final pre-commit drift gate re-reads file hash before atomicWrite).

Read-side: D7 fix — _knownhash_hint for range reads corrected to explicitly say do NOT pass knownHash to action=range. D12 fix — normaliseOptions throws InvalidOptionsException on malformed JSON (invalid_options error) instead of silently returning empty map. doGrep unified to single-pass implementation (D8).

FileReplaceService: 615 → 327 lines. FilePatchService: 405 → 348 lines. All existing CT-1..CT-81 tests preserved and green. New test specs: WriteContextSpec, WriteCommitterSpec, TextMatcherSpec, StructuralGuardSpec, DestructiveChangeGuardSpec, MultiReplaceValidatorSpec, FileWriteContractSpec, FileReadContractSpec.

Changelog migrated from build.gradle comments to CHANGELOG.md.

---

## [0.9.1]
Ontology-first guard — `ReadResponseHelper.maybeAddOntologyGuardHint()` injects `_ontology_guard_warn` on `action=read` of any `.groovy` or `.java` file that is confirmed indexed in the ontology (via `ContextServerClient.isOntologyIndexed(fileStem)`, 500 ms timeout, fail-silent). Reminds Claude to call `context_read scope=ontology action=locate` before expensive whole-file reads; targeted range reads are unaffected. Feature-flagged: `mcp.filesystem.ontology-guard.enabled` (default `true`). No behaviour change when CS is unreachable.

## [0.9.2]
Ontology guard range hint — `ContextServerClient.getOntologyRange(fileStem)` replaces the separate `isOntologyIndexed` call: one HTTP call to `scope=ontology action=locate` now returns `{found, source_line, end_line}` in a single round-trip. `ReadResponseHelper.maybeAddOntologyGuardHint()` updated to use this; when class bounds are available it additionally injects `_ontology_guard_hint: "Call range startLine=N maxLines=M instead (source: ontology index)"` alongside the existing `_ontology_guard_warn`, converting the advisory from "you did the wrong thing" to "here is the correct call". Guard behaviour unchanged when CS is unreachable or file is not indexed. New test spec: `OntologyGuardHintSpec` (OGH-CT-1..6, including transport-independence contract).

## [0.9.3]
Replace pre-flight guard (Bug #107 fix) — two layered defects closed.

**Bug A — post-write side-effects on toolError:** `McpResponse.toolError()` is implemented as `success()` wrapping `isError:true`, so `response.error == null` was `true` even for error responses. The post-write integrity block (structure cache invalidation, file-registry upsert, ontology reindex) fired unconditionally after every `action=replace` call, including rejected ones. Fixed by extracting `boolean isToolError = (response.result as Map)?.get('isError') == true` and gating the post-write block on `&& !isToolError`. Fix is transport-invariant: both STDIO and HTTP paths converge at `handler.handleToolCall()` in `FileWriteService`; neither controller layer is touched.

**Bug B — no early pre-flight gate for replace:** Validation of required `oldText` / `newText` params only existed deep inside `doReplace()`, after the dispatch switch, meaning the post-write block was always reachable on the error path. Added an explicit pre-flight gate in `FileWriteService.handleToolCall()` immediately after `promoteTopLevelParams()`, before dispatch. Gate uses `return` (not `response =`) so it exits the method entirely, bypassing both the switch and the post-write block. Defence-in-depth: `doReplace()` validation retained as secondary guard.

**Doc gap — options field descriptions:** `oldText` and `newText` descriptions in `getToolDefinitions()` now explicitly state `REQUIRED for action=replace` (not just "required for replace" buried in prose). The `options` object description now leads with an action-specific required-field summary so schema-aware clients see the constraint before sending.

**TDD:** 4 new named contracts in `FileWriteContractSpec` — CT-FW-RG-1 (empty options → toolError, file unchanged), CT-FW-RG-2 (newText only → toolError, file unchanged), CT-FW-RG-3 (oldText only, newText key absent → toolError, file unchanged), CT-FW-RG-4 (pre-flight toolError does not trigger post-write side-effects, verified by hash stability). All 7 specs in `FileWriteContractSpec` green.

## [0.9.4]
ServerLifecycleService adopt fix (BUILD-5) — `startServer` and `doEnsure` now adopt
untracked processes when a port is already listening at eager-start or ensure time.

**Root cause:** `pingMcp(port)` (used in `killStalePidIfPresent`) sends an MCP
`initialize` JSON-RPC request. AW's HTTP port 8084 is a REST endpoint (`/aw/*`), not an
MCP protocol endpoint, so `pingMcp` always returns `null` for AW. This caused
`killStalePidIfPresent` to fall through to the evict path, which failed (process alive),
leaving the port occupied. `startServer` then returned early with no `registry.adopt()`
call, so AW always appeared as `managedBySession=false, processAlive=false` even though it
was genuinely running.

**Fix:** Added adopt-on-detect guard in both `startServer` and `doEnsure`:
```groovy
if (!registry.isOwned(name) && !registry.isAdopted(port)) {
    registry.adopt(name, port)
    result.put('adopted', true)
}
```
Result: AW and any other untracked eager process is now adopted on first `start_eager` or
`ensure` call. `managedBySession=true` confirmed via `server_lifecycle status verbose=true`
after DT restart. `processAlive` remains `false` for adopted processes (no Process handle
held); this is correct and expected.

## v0.9.5

v0.9.5: BUILD-16B — `extractOutcome` handles tool-level `isError=true`; package-accessible for TDD.

### Fixed: `McpController.extractOutcome` — tool-level error detection

Prior to this version `extractOutcome` only detected protocol-level errors (`response.error != null`).
Tool-level errors returned via `McpResponse.toolError` (which sets `result.isError=true`) were
recorded as `outcome='success'` instead of `outcome='error'` in `tool_call_telemetry`.
Fixed by adding `result instanceof Map && isError==true` check, matching the CS `deriveOutcome` logic.
`extractOutcome` visibility changed from `private static` to package-accessible so `TelemetryOutcomeSpec` can call it directly.

### New spec: `TelemetryOutcomeSpec` CT-16B-1..5 (5/5 GREEN)


## v0.9.6 — StructuralGuard `allowStructuralEdit` bypass + append-on-code warning

v0.9.6: fix #142 (StructuralGuard no-bypass trap) + append-on-code soft warning. Brief: FS-CS-FRICTION-FIXES-2026-05-22.

### Fixed: `StructuralGuard.checkAll` — add `allowStructuralEdit` bypass (fix #142)

Prior to this version, when a prior `action=append` on a code file left an orphaned `}`,
every subsequent targeted repair attempt was also rejected by `StructuralGuard` (net-negative
brace delta) with no escape path. The only workaround was a full-file rewrite costing ~8
extra tool calls.

`checkAll` now accepts `boolean allowStructuralEdit = false`. When `true`:
- `checkBraceDelta` and `checkParenDelta` are **skipped**
- The brace mismatch is still **logged as WARN** for observability
- `checkBareBoxDrawing` is **never bypassed** — it guards against corrupted AI output

Callers pass the flag via `options.allowStructuralEdit=true` in `file_write` options.
Threaded through `FileReplaceService` at both `replace` and `multi_replace` call sites,
and through `FilePatchService` at the `patch` call site (missed in initial implementation).
Static `org.slf4j.Logger log` field added to `StructuralGuard` to support the WARN.

### Feature: append-on-code soft warning

`FileContentWriter.doAppend` now detects when `action=append` targets a `.groovy/.java/.kt/.kts`
file and includes a `code_append_warning` field in the response:

```
action=append on a code file may corrupt brace structure.
Prefer action=replace or server_transform add_method.
Set options.suppressCodeAppendWarning=true to suppress this warning.
```

The write is **not blocked** — advisory only. Suppressible via `options.suppressCodeAppendWarning=true`.

**Contracts:** `StructuralGuardBypassSpec` CT-SG-BYPASS-1..5 (5/5 GREEN)

<!-- New entries go HERE at the bottom — append only, never edit above this line -->

## [0.9.7]

**Fix: `file_write action=write` now correctly interprets `\n` escape sequences as actual newlines.**

Claude's tool-call serialiser sends `\n` as the two-character literal sequence (backslash + n,
bytes `0x5C 0x6E`) rather than as the actual newline character (`0x0A`). Previously `doWrite`
wrote these literals verbatim, producing a single-line file containing embedded `\n` sequences
that compilers and editors could not parse.

`FileContentWriter.doWrite` now unescapes Java-style sequences before writing:
- `\n` → newline (`0x0A`)
- `\t` → tab (`0x09`)
- `\r` → carriage return (`0x0D`)
- `\\` → single backslash (double-backslash preserved)

Opt-out: pass `options.raw=true` to write content verbatim (for JSON, binary text, or any content
where literal backslash sequences are intentional).

Applies to `action=write` only. `action=replace` and `action=append` are unaffected — those paths
receive content with actual newlines already embedded.

**Contracts:** `FileContractSpec` CT-82 (write unescapes `\n`), CT-83 (raw=true preserves literals) — 2/2 GREEN. Full FileContractSpec CT-1..CT-83 clean.


## [0.9.8]
**E-5: ONTOLOGY-GATE promoted from warn-only to `block_and_observe` with `allowNoLocate` override (A6 Phase E-5 / NS-1+NS-2).**

`file_read` actions `read`, `range`, and `get_method` on ontology-indexed `.groovy`/`.java` files are now
**blocked** when no `context_read scope=ontology action=locate` call has been recorded for that file stem
this session. Mirrors the SQL-GATE pattern in CS `ContextLifecycleActionRouter.handleExecuteSql`.

**Gate behaviour:**
- Block returns `BLOCKED_ONTOLOGY_GATE` error with `locate_query`, `action`, `file`, and `hint` fields.
- `options.allowNoLocate=true` bypasses the block but increments the blocked-token telemetry counter on CS.
- Fail-open: CS unreachable, file not in ontology, or path mismatch → gate not applied.
- Path-scope guard: CS `source_file` must match the exact normalized path being read — prevents spurious
  blocks from residual ontology entries for unrelated files with the same stem.
- Feature flag: `mcp.filesystem.ontology-gate.enforced=false` reverts to warn-only.

**New API on `ContextServerClient`:** `locateCalledThisSession(stem)`, `recordLocateCalled(stem)`,
`incrementOntologyGateBlockedToken(stem)`, `writeOntologyGateObservationAsync(stem, action)`.

**New on `ReadResponseHelper`:** `ontologyGateEnforced` flag, `checkOntologyGate(normalized, options, requestId, action)`.

**`FileContentReader`:** gate callsite in `doRead` and `doRange`; new `doGetMethod` test seam delegating
to `FileStructureReader`. **`FileReadService`:** gate callsite in `get_method` case.

**Bugfix (CT-PCOMMIT-2):** `WriteCommitterSpec.readContent` helper now retries up to 3× on transient
`File not found` errors after concurrent writes, eliminating a pre-existing flaky test failure on Windows.

**Contracts:** `OntologyGateEnforcementSpec` OGE-1..11 (11/11 GREEN).
Full suite: 289/289 GREEN.

## [0.9.9] — Missing-knownHash detection + StructureCache peekHash

### Problem
`knownhash_pct=0` in `sessions_index` for sessions with eligible reads meant Claude was
issuing `file_read action=read` without `options.knownHash` on files already seen this
session, wasting 400-2000 tokens per re-read. The violation was completely silent —
nothing in the response or telemetry indicated it had occurred, so distillation had nothing
to learn from.

### Changes

**`StructureCache`** (`+peekHash`):
New `peekHash(String normalizedPath)` method — checks the internal `CacheEntry` map
without triggering any disk I/O. Returns the cached hash only if an entry exists AND the
file has not been modified since it was cached; returns `null` otherwise. Used by
`ReadResponseHelper.maybeWarnMissingKnownHash` to distinguish "file already seen this
session" from "first-time encounter" without polluting the `getHash` call sequence.

**`FilesystemTelemetryService`** (`+incrementMissingKhCount`, `+getMissingKhCount`):
New `missingKhCount` `AtomicInteger` field, reset in `resetSessionAccumulator()` alongside
the existing token/call accumulators. `incrementMissingKhCount()` and `getMissingKhCount()`
are public; the count is available to `handleRecordSessionTelemetry` for inclusion in
session telemetry summaries.

**`ContextServerClient`** (`+writeMissingKnownHashObservationAsync`):
Fire-and-forget POST to `context_write scope=session type=observation` recording the
violation. Includes file stem, action, and remediation hint. Feeds the distillation
pipeline so the gap surfaces at next bootstrap via the learning loop.

**`ReadResponseHelper`** (`+missingKhWarnEnabled`, `+maybeWarnMissingKnownHash`, `+peekStructureCache`):
- `@Value('${mcp.filesystem.missing-kh-warn.enabled:true}') boolean missingKhWarnEnabled`
  — feature flag; default on.
- `maybeWarnMissingKnownHash(response, normalized, options, action, preCachedHash)` —
  advisory check. If `preCachedHash` is non-null (file was in cache before this call) AND
  caller omitted `options.knownHash`, injects `_missing_knownhash` hint into the response
  map, fires the async observation, and increments the session counter. Does NOT block.
- `peekStructureCache(normalized)` — convenience wrapper over `StructureCache.peekHash`.
  Called by `FileContentReader` **before** the read to capture pre-read cache state.

**`FileContentReader`** (callsites in `doRead` and `doGetMethod`):
- `doRead`: captures `preCachedHash = helper.peekStructureCache(normalized)` AFTER the
  ontology-gate check but BEFORE `checkKnownHash`/`storeAndHintKnownHash`. Passes it to
  `maybeWarnMissingKnownHash` after content is assembled.
- `doGetMethod`: same pattern — `peekStructureCache` before the `structureReader` delegate
  call; `maybeWarnMissingKnownHash` injected into the parsed response map if a hint is added.

**`MissingKnownHashDetectionSpec`** (MKH-1..9, all GREEN):
- MKH-1: read without knownHash, file in StructureCache → `_missing_knownhash` injected
- MKH-2: read WITH knownHash supplied → no hint
- MKH-3: read, file NOT in StructureCache → no hint
- MKH-4: hint contains the correct cached hash
- MKH-5: correction observation written async to CS on violation
- MKH-6: `FilesystemTelemetryService.incrementMissingKhCount` called on violation
- MKH-7: feature flag disabled → no hint
- MKH-8: hint is additive — read still returns content and `file_content_hash` normally
- MKH-9: `doGetMethod` without knownHash on cached file → hint injected

## [0.9.10]
ServerLifecycleService.startServer() HTTP companion jar resolution now prefers the MCPB extension master over the claude-sync/jars snapshot. New resolveCompanionJar() reads the extension manifest.json entry_point (%APPDATA%/Claude/Claude Extensions/<mcpbExtDir>/server/<jar>) and launches that jar; falls back to jarsDir/<pinned> only when the extension/manifest is absent (e.g. mcpb:false servers like ms-graph). Start result now carries jarSource=extension|jarsDir|none. Retires the drift class where a stale or removed jarsDir copy silently took an HTTP companion down - root cause of the 2026-07-30 context-server :8082 handshake outage (flow lifecycle-start / session-end ConnectException: Connection refused, while the stdio path stayed healthy). New helpers resolveCompanionJar/jarResult/extensionsBaseDir; manifest read try/catch logs exception class (practice #626). Verified: compileGroovy clean; behavioural replay resolves all four MCPB servers from the extension, ms-graph from jarsDir, and a forced-stale pin still resolves to the real deployed jar.


## [0.9.11]
FS-EXEC-1: `execute action=cmd` ran only the FIRST LINE of a multi-line script, silently.

`doCmd` passed the script as `['cmd', '/c', script]`. `cmd /c` accepts a **single** command, so
every line after the first was discarded — with `exitCode 0` and the first command's stdout,
which is indistinguishable from full success. There was no error, no warning, and no clue in the
response that anything had been dropped.

Cost, 2026-08-21 (observation 9881): a script of `git add <paths>` then `git commit -F msg`
returned success with only CRLF warnings on stderr. The add ran; the commit never happened. The
next call, `git push`, reported "Everything up-to-date" — technically true, and reading exactly
like success. Only `git status -sb`, showing the files still staged rather than committed,
revealed it. An earlier four-command diagnostic script silently lost three of its four commands
and returned just the branch name.

`doPowershell` and `doPython` already write the script to a temp file for precisely this class of
problem, each carrying a comment that says so. `cmd` never got the same treatment. It now writes a
temp `.cmd` (with `@echo off`, and CRLF-normalised body) and invokes `cmd /c <file>`.

**Semantics, now documented in the tool description:** every line runs, in order, and the LAST
command's exit code is returned. A mid-script failure does not abort the remaining lines — the same
contract as `bash -c`, which has no `set -e`. Callers mutating state should check that state
explicitly rather than trusting a single `exitCode`. `bash` was already correct (`bash -c` handles
multi-line input); `powershell` and `python` were already correct via their temp files.

`ExecuteServiceMultilineSpec` (FS-EXEC-1/1b/1c/1d) covers three-line and two-line scripts, a
single-line script to guard the common case against regression, and asserts `@echo off` keeps
command text out of stdout. 1c and 1d passed on the old code and 1/1b failed, which is what
isolated the defect to multi-line handling rather than the test harness.

`doCmd` and `doBash` are now `protected` rather than `private` — `@CompileStatic` private methods
are unreachable from a `@CompileDynamic` Spock spec even in the same package (practice #1166,
seam 2).


## [0.9.12]
FS-EXEC-2: background execution jobs — `execute` no longer has to finish inside the client's deadline.

### The problem

`execute` is bounded by a hard **~60s timeout imposed at the MCP client boundary**, and
`options.timeout` does **not** extend it. FS honours that value in `process.waitFor`, so the work
keeps running — but the caller has already given up, and the blocked call **serialises everything
behind it**. A cold Gradle compile of `mcp-agentic-workflow` timed out at the tool boundary while
still running, then blocked the next two calls (observation 9821, chain `ef8cae5c`).

The ceiling is not ours to raise. The only real fix is to stop blocking underneath it.

### The mechanism

`options.async: true` submits the work and returns a `jobId` immediately:

| action | purpose |
|---|---|
| `execute` + `options.async` | submit, returns `{jobId, status:'running', …}` |
| `job_status` | poll — status, exitCode, elapsedMs, stdout/stderr byte counts |
| `job_output` | read output; `sinceOffset` + returned `nextOffset` tail incrementally |
| `job_cancel` | destroy the process, then cancel the promise |
| `job_list` | enumerate, newest first |

Jobs are retained 30 minutes after finishing, capped at 100 (oldest finished evicted first).

Built on **GroovyConcurrentUtils** — already an FS dependency, and the async primitive the rest of
the platform uses (AW's TaskGraph runs on the same `Promise` abstraction). `PromiseFactory.executeAsync`
supplies completion state and cancellation without hand-rolling an executor, and avoids a second
concurrency model in the same stack.

`runProcess` was refactored so the process-and-drain loop lives once, in `runAndCapture`, shared by
the synchronous path and by jobs. The child blocks if a pipe fills, so that logic must not be
duplicated carelessly. When a job is present its output is mirrored in as it arrives — so a running
build can be tailed — and the `Process` is registered so `job_cancel` kills the OS process rather
than only the promise.

### Temp-file ownership moved

`doCmd`/`doPowershell`/`doPython` deleted their temp script in a `finally`. On the async path that
`finally` fires **immediately**, deleting the script before the background job could run it — the
first async run failed with *"'…mcp-cmd-….cmd' is not recognized as an internal or external
command"*. Caught by the new specs, not in production. `runProcess` now owns the file on the sync
path, and the job owns it on the async path, deleting it on completion or cancellation.

### Tests

`ExecuteServiceAsyncSpec` (FS-EXEC-2/2b/2c/2d/2e): submit returns in <2s for a 5-second job and the
work still completes; `job_output` tails via `nextOffset` and re-reads return nothing; `job_cancel`
leaves `process.isAlive() == false`; `job_list`/`job_status` round-trip; an unknown `jobId` is an
explicit error rather than a blank status a caller could misread.

This is new capability rather than a defect fix, so there is no red-then-green here — the ~60s
ceiling is external and cannot be fixed in FS at all.

Note for anyone reading a failure in these specs: the gradle test worker's `PATH` does not include
`System32`, so cmd builtins resolve but external executables do not. The specs use an absolute
`%SystemRoot%\System32\ping.exe` for their delays. Nothing to do with FS.

Full suite 307 tests, 0 failures.


## 0.9.13 - 2026-08-26 - The companion nobody owned

**`stopAllOnShutdown` was killing shared HTTP companions, and it cost an entire session.**

On 2026-08-26 every CS-to-AW local inference call failed silently for a whole afternoon. Zero
`llm_delegations`, zero AW `workflow_events`, while `reconcile_chain_failure_class` cheerfully
returned `submitted: 25`. The cause is four lines in this file's `@PreDestroy`. From the FS log:

```
16:51:21.912  started agentic-workflow on port 8084 (pid=16436)
16:51:22.638  server_lifecycle: stopped agentic-workflow on shutdown
```

The FS instance that started AW's companion shut down **0.7 seconds later** and its `@PreDestroy`
destroyed the companion it owned. Claude Desktop starts two launcher+child pairs of every server,
so a short-lived FS instance takes down a companion that every other live instance depends on.
Nothing retries: `autoStartHttpCompanions` is `@PostConstruct`, one shot at boot.

**The category error.** `ServerRegistry` knew two kinds of process: *owned* (we started it, kill it
on shutdown) and *adopted* (someone else started it, leave it alone). An HTTP companion on a fixed
port is neither. It is **shared infrastructure** — started by whichever instance won the race to
find the port free, depended on by all of them, expected to outlive any one of them.

CS's companion survived on :8082 purely because that FS instance found it already listening and
adopted it — the log line `HTTP companion context failed to start: already listening on port 8082`
is what saved it. AW's did not, because that instance started it. The asymmetry was ordering luck,
which is the definition of a race the code does not know it is running.

**The fix: `stopAllOnShutdown` now stops nothing.** It logs what it is leaving running and writes
the runtime record. No `destroy()`, no `clearOwned()` — the registry entry and the runtime record
are exactly what let the next start find, ping and *adopt* a live companion instead of spawning a
duplicate that loses the bind race.

This is not a new policy so much as removing the one place that disagreed with the existing one.
`killStalePidIfPresent` already pings a live port, **adopts** a healthy server and evicts only an
unresponsive one. Cleanup at next start was always the design.

Trade-off, stated plainly: companions now outlive Claude Desktop until something evicts them. That
is strictly better than the previous behaviour, which was not "clean shutdown" but "killed at a
random moment mid-session, whenever a transient instance happened to exit". To stop one
deliberately, `server_lifecycle action=stop` still does exactly that — an explicit request from a
caller who means it, rather than a side effect of one instance exiting.

**New spec `ServerLifecycleShutdownSpec`** — the first test this service has ever had, which is
its own finding for the component that failed. SL-1 shutdown does not destroy a companion, SL-2 it
stays in the registry so an explicit stop can still reach it, SL-3 the runtime state still records
it so the next start adopts rather than re-spawns (practice #1485, assert on persisted state). All
three confirmed failing on 0.9.12 first — SL-1 with `TooManyInvocationsError`, which is the defect
reproduced rather than merely a missing method.

**Corrected from the same investigation.** An earlier reading claimed `startServer` reports the
spawn rather than the bind. It does not: it calls `waitForPort(port, 10)` and only logs `started`
on success. The 8084 companion really did bind. It was killed 0.7s afterwards.

**Not fixed here, deliberately.** Two FS instances can still both pass `isPortListening` and both
spawn a companion; the loser dies with "Port 8084 was already in use". With this fix the winner
survives, so the cost is one wasted JVM start rather than a dead companion. A cross-process lock
is the real answer and is worth doing on its own, not inside a fix for something else.

Also noted: `dtOwned` in `mcp-http-servers.json` is read by no code at all. It sits on the
filesystem and context entries and not on agentic-workflow, so it reads exactly like the flag that
explains all of this. It explains nothing. Wire it up or delete it.

Suite 310 / 0.

---

## v0.9.15 (2026-09-02) — FS-EXEC-PATHEXT: powershell silently refused to run native executables

**Symptom.** `execute action=powershell` returned `exitCode: 0` with empty stdout AND empty stderr
for every invocation of `git.exe`, while cmdlet output from the same script came back normally.
The identical query via `action=cmd` returned the correct output immediately. Forty minutes
earlier, before the server process was restarted, the same powershell script had worked — same
version, so the trigger was environmental rather than a code change.

**Why it was dangerous rather than merely annoying.** An empty `git status --porcelain` reads as a
CLEAN TREE. This platform's own working practice requires checking `git rev-parse HEAD` against
`origin/<branch>` before believing a push happened; under this defect both sides return the empty
string and compare equal. A verification step that passes because both of its inputs are missing.
Same family as `catch(Exception ignored)` around a call feeding a counter: the failure presents as
a legitimate zero.

**Diagnosis.** Both actions share one `runProcess`, one `ProcessBuilder`, one set of reader
threads — only the interpreter differs — so the divergence could not be in FS's process plumbing,
environment inheritance, reader threads or charset handling. Redirecting the native command to a
file produced an *empty file*, which eliminated every remaining stream-capture explanation.
`Start-Process` on the same path worked and printed the version.

**Root cause.** The child inherited `PATHEXT=.CPL`. With `.EXE` absent from PATHEXT, PowerShell
classifies `git.exe` as a **document** rather than an application and hands it to file-association
activation instead of running it. `$LASTEXITCODE` is never set, `$?` stays `True`,
`$Error.Count` is 0, both streams are empty, exit code 0. Inside a pipeline it surfaces as
`CantActivateDocumentInPipeline`, which was the tell. `cmd.exe` was immune because it normalises
PATHEXT itself rather than trusting what it inherits.

**Fix.**

- `ExecuteService.repairPathExt(env)` — applied to every spawned child, AFTER caller
  `envOverrides` and only when the effective value cannot run executables, so a caller passing a
  working PATHEXT keeps exactly that one. Existing entries are preserved and appended to the
  standard set rather than replaced; the inherited value may be wrong without being worthless.
  Logs a warning when it repairs, so the condition is visible rather than silently corrected.
- `-InputFormat None` added to the powershell invocation. ProcessBuilder hands powershell a stdin
  pipe that is never written to and never closed.
- `doPowershell` made `protected` — a `@CompileStatic` private method is unreachable from a
  `@CompileDynamic` Spock spec even in the same package (practice #1166 seam 2), so there was no
  seam to test it through. `doCmd` already had this treatment; powershell never got it, which is
  why no spec covered this path at all.

**Specs.** `ExecuteServiceNativeCommandSpec`, confirmed red before the fix.

- PATHEXT-1 — a native executable's stdout is captured when PATHEXT lacks `.EXE`. Asserts the
  cmdlet half first, so the native assertion cannot pass or fail for the wrong reason.
- PATHEXT-2 — `cmd` and `powershell` agree on the same native command under the same hostile
  PATHEXT. This is the controlled comparison that would have caught it on day one: neither path
  looked wrong on its own, only the difference between them did.
- PATHEXT-3 — a caller PATHEXT that already works is preserved. The repair fixes a broken
  inheritance, it does not overwrite intent.

Suite 320 tests, 0 failures.

**Known residue.** The reader-thread bodies in `runAndCapture` still have no try/catch, and
`stdoutThread.join(remainingMs)` can time out and return a partially-drained buffer as if
complete. Neither caused this defect — the marker text proved the stdout thread survived — but
together they are why the failure was invisible: FS cannot currently distinguish "the child
produced nothing" from "our reader died or was abandoned". Worth closing separately.

### v0.9.15 — verified live after restart (2026-09-02)

`server_versions` reads `filesystem 0.9.15`. The exact call that returned nothing an hour earlier
now returns, via `execute action=powershell`:

```
inheritedPATHEXT_seen_by_script = .COM;.EXE;.BAT;.CMD;.VBS;.JS;.WS;.MSC;.CPL
git version 2.55.0.windows.2
lastexit = 0
master                        <- git -C <repo> rev-parse --abbrev-ref HEAD
```

The repaired value is the load-bearing detail: the standard set is restored **and the inherited
`.CPL` is preserved on the end** rather than discarded. That is the difference between repairing a
broken inheritance and overwriting the caller's environment, and it is what PATHEXT-3 exists to
hold. The push check the working practice mandates — `git rev-parse HEAD` against
`origin/<branch>` — now compares two real hashes rather than two empty strings.

---

## v0.9.16 (2026-09-03) — FS-EXEC-STREAM: a dead reader was reported as an empty one

Closes the known residue recorded when 0.9.15 shipped.

**The defect.** `runAndCapture` started two virtual threads whose bodies had no try/catch and
whose completion was never checked. An exception inside the drain loop — `IOException: Stream
closed`, a decode fault — killed the thread silently and left an empty StringBuilder, and the
method returned `exitCode 0` with empty stdout and nothing to distinguish it from a command that
legitimately printed nothing. The second half has the same effect: `join(remainingMs)` can time
out, leaving the thread alive and the buffer partially drained, and that partial buffer was
returned as though it were the whole output.

So FS could not tell "the child produced nothing" from "our reader died", and reported the second
as the first. Identical in shape to the PATHEXT defect fixed one layer up in 0.9.15, and to
`catch(Exception ignored)` around a value feeding a counter: the failure presents as a legitimate
zero. An empty `git status --porcelain` reads as a clean tree.

**Why it had never been covered.** The drain loop was inline in a private method, so the failure
could not be constructed from a spec at all. An untestable condition is one nobody tests.

**Fix.**

- `pumpStream(InputStream, Closure)` extracted as a **protected** seam — practice #1166 seam 2,
  the same treatment `doCmd` and then `doPowershell` needed.
- Both reader closures wrapped, catching `Throwable` rather than `Exception` so an Error in a
  virtual thread cannot vanish either. The failure is recorded in a per-stream `StreamState`,
  never swallowed.
- After the joins, a thread still alive is recorded as `abandoned: join timed out after Nms` —
  the partial-drain half of the defect.
- `runAndCapture` now returns `streamsOk` and `streamError`, and `success` requires
  `exitCode == 0 && streamsOk`. **An exit code is evidence the child finished, not evidence we
  know what it said.**
- `runProcess` surfaces `stream_error` in **both** response shapes. Compact is what most callers
  read, and a guard present only in the verbose shape is one most callers never see.

**Specs.** `ExecuteServiceStreamCaptureSpec`, confirmed red against the pre-fix behaviour by
reverting the response-side gating and re-running:

- STREAM-1 — the control. A healthy run still succeeds and reports no stream error, so the fix
  cannot pass by failing everything. Passed both before and after.
- STREAM-2 — a reader that throws must not yield success with empty output. Failed before on
  `r.success == false`; the pre-fix code returned `success: true` with empty stdout.
- STREAM-3 — the compact shape carries the reason too. Failed before.

Suite 323 tests, 0 failures (320 before).

## [0.9.17]

Session identity moved off the machine-wide singleton, and a manifest that had been understating
the server by half.

**FS no longer reads `active_session`.** It was `SELECT session_id FROM active_session ORDER BY id
DESC LIMIT 1` against a table declared `CHECK (id = 1)` — one row per machine, while the MCP stdio
contract is one JVM per client connection. With two Claude chats open, the second chat's bootstrap
overwrote the row the first was resolving through, and FS attributed one chat's telemetry and
range-cache keys to the other. Nothing errored. **The process is the chat**, so identity is now
per-process.

- `ProcessIdentity` (new) — `OWNER_KEY` = `fs-<pid>-<jvmStartMillis>-<random>`. The JVM start time
  is load-bearing: an OS reuses pids, so pid alone would let a new process inherit a dead one's
  claim.
- `FilesystemTelemetryService.readActiveSessionId()` resolves in three steps and stops: in-process
  claim → own `session_claims` row (keyed on `OWNER_KEY`) → **null**. It returns null rather than a
  sentinel, because a manufactured value lands upstream of every guard that checks for absence.
- `server_lifecycle action=claim_session | release_claim | claim_status`. The claim is model-issued
  from your own connection; a server cannot tell from the inside which chat it is serving.
- One table, one liveness rule: FS writes rows tagged `server='fs'` and **CS's reaper decides
  liveness for all three servers**, so FS gets no separate bookkeeping to drift out of step with.
- This makes the 0.8.82 stale-cache fix (OW-3) structurally unnecessary rather than merely correct:
  a restarted process is a new process with a new `OWNER_KEY` and no claim, so it reports UNBOUND
  instead of holding a previous session's id.

**The MCPB manifest declared four tools against eight served.** `file_list`, `file_lifecycle`,
`execute` and `tools` were missing. The Claude Desktop extensions panel renders that manifest, so
the server had been advertising half of itself — and the installed copy had been regenerated the
same day and still said four. A literal cannot go stale loudly.

**Deriving it at build time was built and rejected on evidence.** A `--emit-tool-manifest` mode
that boots the app and asks Spring for `List<ToolHandler>` does produce the true set, and the
attempt bound **Tomcat on 8081 — FS's own port** — opened the live `best_practices.db` in WAL, had
`ContextServerClient` resolve the running session, and had `ServerLifecycleService` probe the
companions on 8082 and 8084. It cannot be made inert either, because `ServerLifecycleService` *is*
one of the `ToolHandler` beans being enumerated. A packaging step must not be able to disturb the
running platform.

The guarantee moved into `McpbManifestToolCoverageSpec`, which compares the manifest against the
live Spring context's `List<ToolHandler>` by an independent path — the same pattern as CS's
`ActionSurfaceDriftSpec`, and the same property: the list cannot drift without the build going red.
When derivation has side effects, assert the equality instead.

**A regression this work caused, attributed properly.** `WriteCommitterSpec` CT-PCOMMIT-2 (20
concurrent writes) went deterministically red, attributed by `git stash` experiment rather than
assumption. `McpController` calls `readActiveSessionId()` once per tool call, and that had meant a
JDBC connection per call; the latency was accidentally serialising the twenty threads. An unclaimed
process now resolves in memory, the writes became genuinely concurrent, and the atomic-rename
window the spec's own helper comment already described started being hit — its direct-`File`
fallback was a single shot returning null, which NPE'd the assertion. The fallback now retries.

**The underlying property is unchanged and unmeasured.** FS's write path still has a window in
which the target file does not exist on disk, and nothing measures how wide it is. It was tolerable
while every tool call paid for a JDBC connect; it is worth measuring now that they do not. Do not
read the green spec as evidence the window closed — only that the test no longer trips over it.

Specs red first: `McpbManifestToolCoverageSpec` 2 tests / 1 failed, `FsSessionClaimSpec` 6 tests /
5 failed. Suite 331 tests, 0 failures.

---

## [0.9.18] — 2026-09-08

**Losing a companion start race is a normal outcome, not a failure.**
See `BUILD-BRIEF-2026-09-08-the-companions-that-race` in the mcp-servers project.

`ServerLifecycleService.autoStartHttpCompanions` checks `isPortListening(port)` and then spawns a
JVM that takes **~24 seconds** to bind. Four stdio instances boot within four seconds of a desktop
restart and each runs that pass, so two can both pass the check before either has bound. The loser
aborted with `APPLICATION FAILED TO START — Port 8081 was already in use`, printed twice into the
log every instance shares.

Measured on the 14:05 restart: one FS companion won 8081 (pid 50000); a second attempt died at
14:06:02. AW's companion on 8084 was started **twice**, pids 45440 and 52520 ten seconds apart, and
neither owns the port now — orphans accumulate. CS showed the identical failure on 8082.

**The cost was not the wasted JVM.** It was the banner: `APPLICATION FAILED TO START` is the first
thing anyone finds when investigating something unrelated, and on 2026-09-08 it sent an
investigation into an unrelated tool-listing problem down the wrong path for an hour.

### The fix, and where it had to go

The check cannot be made safe where it is — it sits 24 seconds and one process away from the bind
it is predicting. So the arbiter moves **into the child**, immediately before Spring starts, where
the window is milliseconds; the residual case is caught after `run()` and exits 0.

- `configuredHttpPort()` — the port this process will actually bind, or null. **Only the `http`
  profile takes a fixed port.** A stdio instance must never resolve one.
- `isServed(port)` — a **connect** test, deliberately not a trial bind: a trial bind would briefly
  occupy the port and make a competing instance's probe fail for the wrong reason, turning one race
  into two.
- `portInUseFrom(t)` — walks the cause chain for `PortInUseException`, matched by simple name and
  read reflectively so it survives the exception moving package between Boot versions. Carries a
  cycle guard, because a looping cause chain would hang startup — a worse defect than the one being
  fixed.

**Neither is a lock.** Who *owns* companions is F-4 of the brief and is a decision, not something to
invent here.

### The dangerous case is this fix, not the defect

If `configuredHttpPort()` ever returned a port for a **stdio** instance, every stdio FS instance
would exit the moment a companion happened to be listening — and FS stdio serves every file read,
write and shell command there is. `FRE-1` asserts the profile guard holds with `MCP_HTTP_PORT`
explicitly set, which is exactly the state a stdio instance is in while a companion runs.

A/B'd in both directions by removing the guard: `FRE-1` and the no-profile row of `FRE-3` fail, the
other nine stay green. Restored to a byte-identical hash. `FRE-5` is the other half — an unrelated
startup failure must still throw, so this never becomes a blanket catch.

Suite: 30 suites, 342 tests, 0 failures.

### Deliberately NOT done

FS's `stdio` profile sets `server.port: 0` but, unlike CS's, does **not** set
`spring.main.web-application-type: none` — so every stdio FS instance boots a full Tomcat on a
random port that nothing connects to, four per restart, and it is a plausible contributor to the
24-second startup. Left alone on purpose: `StdioMcpServer` holds an `@RestController`
(`McpController`), and disabling the servlet context is a change that cannot be verified without a
restart, with FS stdio — the thing that serves everything — as the blast radius. It is F-3 of the
brief, and it needs its own pass, not a ride-along on an unrelated fix.

---

## [0.9.19] — 2026-09-08

**ROOT CAUSE of the intermittent tool-list drops. In stdio mode the server must not write to
stderr at all.** Observation 10497. Aligned across FS, CS and AW in the same pass.
> **[WITHDRAWN in 0.9.20]** The root-cause claim on the two lines above is withdrawn. The blocking hazard is real and was measured; it was not shown to cause the drops. See the correction at the end of this file.

### The mechanism

stderr is a pipe with a fixed OS buffer, and whether it is drained is the **client's** choice, not
ours. If the client is slow to read it, pauses, or stops, the buffer fills and the next log write
**blocks the writing thread** — which is the thread reading stdin and answering MCP. The process
stays alive, healthy, holding its session claim, with no restart and no new pid, and answers
nothing. From the outside that is indistinguishable from the server being gone: the tool list
empties and later refills on its own.

FS emitted **6,676 bytes / 50 lines** to stderr on a bare stdio startup — root at INFO plus
`com.softwood.mcp` at DEBUG, through an unconditional `ConsoleAppender`.

### Measured, not argued

A probe performs the real MCP handshake over stdio with stderr redirected and **deliberately never
read**, which is exactly what a stalled client looks like from the server's side. One variable:

| | undrained stderr |
|---|---|
| FS 0.9.18 | **no answer in 40s** |
| FS 0.9.19 | **answered in 2.48s** |
| AW 1.30.9 | **no answer in 35s** |
| AW 1.30.10 | answered in 3.68s |
| CS 1.0.39 | answered in 2.41s *(never vulnerable)* |

CS was never exposed, and not because of its logback: its `application.yml` stdio profile sets
logging levels to `OFF`, a block its own config marks **CRITICAL**. FS and AW never received it.
The correct strategy existed in this codebase, in writing, applied to one server of three.

### The fix

`logback-spring.xml` now defines two complete `<root>` blocks, each inside a top-level
`<springProfile>`: stdio gets FILE only, everything else gets STDERR + FILE. The FILE appender is
what makes detaching stderr free — the logs do not disappear, they stop going down a pipe we do not
control.

The comment that justified the unconditional appender said *"stderr is a separate pipe from stdout,
it does NOT corrupt MCP JSON-RPC messages"*. True about corruption, and it answers a question
nobody asked. The hazard is blocking. That comment is replaced with the measurement.

### Two traps on the way, both worth keeping

**The first fix appeared not to work.** XML comments may not contain a double hyphen; I had used
them as dashes. Logback failed to parse the file, Spring Boot fell back to its **default console
appender**, and the server blocked on stderr exactly as before. *An invalid config fails in the
shape of the bug it fixes*, and that is very easy to read as the diagnosis being wrong.

**The second fix broke 279 of 342 tests.** `<springProfile>` nested *inside* `<root>` around a
single `appender-ref` is the tidier-looking form and does not work: logback resolves the ref outside
the appender registry and every Spring test failed with `Failed to find appender named [STDERR]`.
`springProfile` as a direct child of `<configuration>` is the documented, supported shape. The suite
caught this immediately, which is the machinery working.

Suite: 30 suites, 342 tests, 0 failures.

## [0.9.20]

**Two-day rolling logs, a heartbeat, and a correction to 0.9.19's claim.**

### Retention that survives being useful

0.9.19 gave stdio a FILE appender and took stderr away. That was the right move, and it
immediately created the problem it was always going to create: the reason stderr output had
been truncated by hand was file growth, and moving the volume into a file we keep does not
make the volume smaller.

The FILE appender now uses `SizeAndTimeBasedRollingPolicy`: 20MB per file, `maxHistory=2`
days, `totalSizeCap=100MB`. Two days is the window that matters. A tool-list drop is noticed
within minutes to hours, never next week; and the size cap is what stops one runaway loop
filling the disk before the daily roll arrives. All three servers now carry identical policy.

### HEARTBEAT

`McpHeartbeat` (new, `com.softwood.mcp.support`): a daemon thread on a 60s interval, started
by `StdioMcpServer` before the read loop and fed by `recordRequest()` on every accepted
request line. It emits

    HEARTBEAT server=<name> v=<version> pid=<pid> uptime=<n>s requests=<n> idle=<n>s

at INFO, promoted to WARN once idle passes 120s, and reports `idle=never-any-request` before
the first request arrives.

This is the instrument the last three days lacked. When the tool list next goes empty, the log
distinguishes two very different situations without anyone being present:

- **heartbeats continuing, `idle` climbing** - the server is alive and nothing is being sent
  to it. The fault is upstream of the server.
- **heartbeats stopped** - the server is wedged or gone, and the last line before the gap is
  the evidence.

Until now that question could only be answered by attaching to a live process at the moment of
failure, which requires someone to be watching.

### Correction to the 0.9.19 entry

The 0.9.19 entry calls the stderr fix "ROOT CAUSE of the intermittent tool-list drops". That
claim was not supported by the evidence and is withdrawn.

What *was* established: writing to an undrained stderr pipe in stdio mode is a real blocking
hazard, and it was measured - 0.9.18 gave no answer to `initialize` in 40s where 0.9.19
answered in 2.48s. A genuine latent defect, correctly found and correctly fixed.

What was **not** established is that it caused the observed drops. `jstack` taken against live
FS stdio PIDs *while the tool list was empty* showed `main` RUNNABLE inside `System.in.read()`
- healthy, idle, receiving nothing. A server blocked on a stderr write does not look like
that. The cause of the drops lies upstream of the server, in the client or the bridge, and
remains open. Observation 10507.

The heartbeat above exists so that the next occurrence is settled from a log file rather than
from a hypothesis.

Suite: 30 suites, 342 tests, 0 failures.

## [0.9.21]
WP-1 — companion startup is off the Spring startup path, and the claim on each port is atomic.

`autoStartHttpCompanions` used to spawn each companion and wait up to 10s per port for it to
listen, inline, inside `@PostConstruct`, so the stdio server did not reach its read loop until
every companion was up. Measured 2026-09-08 on one machine, one jar: a cold start (companions
absent) took **49.595s** to `Started McpGroovyFileSystemServerApplication`; a warm start 34
minutes later, where all three ports were already served and the method did nothing, took
**2.07s**, with `STDIO server ready` 0.04s behind it. The 24x is entirely this work, and it is
why the tool-list drops are intermittent: the drop follows the cold path, not the warm one.
The work now runs on a daemon thread (`fs-companion-starter`) started from `@PostConstruct`.

The guard on each port was `isPortListening(port)` and then spawn — check-then-act across
processes, which is not a guard. On the 17:22 restart six FS JVMs ran it at once: three raced
for :8081 (two lost and exited quietly), :8082 was attempted 19 times across the log, and six
AW companions were spawned for one port. Replaced with `startCompanionUnderClaim`, an OS file
lock (`FileChannel.tryLock()` on `claude-sync/locks/companion-<name>-<port>.lock`) with the
port re-checked inside the claim. tryLock either succeeds or returns null, atomically; the OS
drops the lock if the JVM dies, so there is no stale-lock case; and a loser now pays
microseconds instead of a 50-second startup.

Also off the startup path: the `@PostConstruct` retry loops in `FileReadService.init` and
`FileWriteService.init`, which slept 0/500/1000/2000/3000ms inline waiting for CS help_sections.
On a cold start they always spent the full budget and fell back to the baked-in defaults anyway,
because the CS companion they were waiting for had not been spawned yet — 13.5s of measured
cold-start time (17:23:11–24) to arrive at the string they now start with. Defaults are installed
synchronously; the CS refresh runs on a daemon thread; `reloadDescriptionsFromCs()` after the
companions come up is unchanged.

WP-0 — this server no longer writes into Claude Desktop's log directory, in either direction.

`LogCleaner` cleared nothing any more (it truncated `mcp-server-groovy-filesystem.log` and
`mcp.log` under `%APPDATA%/Roaming/Claude/logs` on every start), and `startServer` no longer
redirects companion stderr there: it writes `claude-sync/logs/mcp-companion-<name>-stderr.log`,
inside our own rotation.

**Correction to the WP-0 rationale in BUILD-BRIEF-2026-09-08-the-four-that-were-spawned.**
The brief says LogCleaner was destroying Claude Desktop's own client-side logs, and that this is
why three investigations had no client-side evidence. Measured: Claude Desktop app-1.46388.4
writes **no** MCP log to that directory at all — nothing in it has been touched by the client
since 2026-08-26. Every `mcp-server-*.log` sitting there today was written by *us*, by the
stderr redirect above, under Claude Desktop's own naming convention. The 154-byte file at
17:23:35 that read as erased evidence was our own header over a file that never held any.
Historical client logs do exist and do carry the right lines (`main1.log`:
`[LocalMcpServerManager] Connected to mcp-groovy-filesystem-server (8 tools)`,
`[localMcpBridge] announcing ... 8 tool(s)`, `... disconnected`) but the newest is 2026-08-04,
before the drops began. The section 3 hypothesis therefore cannot be settled from Claude
Desktop's logs and needs a different instrument. Both changes above stand on their own merits —
neither is ours to write and neither is ours to erase — but WP-0 does not unlock the proof it
was written to unlock.

Tests: 344 (342 + 2 new in `LogCleanerSpec`), 0 regressions. `WriteCommitterSpec` CT-PCOMMIT-2
fails roughly one run in four **on unmodified master** (verified by stashing this change and
re-running: 1 failure in 4 baseline runs, 1 in 4 with the change), so the "FS 342 / 0" baseline
in the brief is not a reliable gate — that spec is flaky and needs its own fix.

## [0.9.22]
No behaviour change. The EOF shutdown path gets a test, and a seam so it can have one.

FS has shut down correctly on stdin EOF since v0.7.17 and needed no fix. The reason this version
exists is that **nothing in any of the three servers tested that path**, and AW turned out not to
do it at all — found from the process table on 2026-09-09 when two AW instances survived a Claude
Desktop auto-update that replaced every FS and CS instance. A contract that three servers are
supposed to share, which one of them silently broke and no test noticed, needs a test in all three.

`System.exit(...)` inside `triggerCleanShutdown()` is now `exitAction.accept(...)`, where
`exitAction` is a static seam defaulting to `System.exit`. That is the only production-visible
change and it is behaviour-preserving.

`StdioMcpServerEofShutdownSpec` — 3 tests, identical in shape to the AW and CS copies. They drive
the real `run()` loop with `System.in` replaced by an empty stream, which is what Claude Desktop
closing the pipe looks like to `readLine()`, and use a real refreshed `GenericApplicationContext`
rather than a mock so that removing `SpringApplication.exit` would fail rather than pass. The
efficacy of this spec shape was proven by mutation in the AW repository, where deleting the
`triggerCleanShutdown()` call fails 2 of 3 tests.

Suite: 32 suites, 347 tests, 0 failures (344 + 3). `WriteCommitterSpec` CT-PCOMMIT-2 passed this
run; it remains flaky at roughly 1 in 4 on master and is still not a gate.

## [0.9.23]
Log rotation starts working, and `LogCleaner` earns its name back.

### Why the rolling policy was never bounding anything

FS 0.9.19 specified 20MB per file, `maxHistory=2`, `totalSizeCap=100MB` for the FILE appender, and
a note warned that logback's size trigger counts bytes written by the *current* appender instance
rather than the length of the file it appends to. Measured on 2026-09-09, that note understated it.

**Exactly one rolled archive existed on the entire machine.** It rolled at a process *restart*, not
on reaching 20MB and not at a date boundary, while `mcp-agentic-workflow.log` sat at 16.85MB having
never rolled and `mcp-context.log` had previously reached 96.6MB.

The mechanism fits both: the byte counter belongs to the appender instance and **every restart
resets it**. These processes restart constantly — the companions on every client restart, the stdio
instances on every replacement wave — so no instance ever lives long enough to count 20MB however
large the file on disk becomes. Several JVMs appending to one file compounds it, since logback does
not support that at all without `prudent` mode and each instance counts only its own bytes. So
`maxHistory` and `totalSizeCap` only act *once a roll occurs*, and rolls were not occurring.

### One file per pid

`<file>` and `fileNamePattern` now carry `${PID:-nopid}`, so each JVM owns the file it counts bytes
against and the size trigger has something to reach. `prudent` mode was the alternative and was
rejected: it takes a file lock on every append and logback documents it as materially slower, which
on a stdio server is latency on the path FS 0.9.21 just spent two days clearing.

### The consequence that per-pid brings, and its fix

`maxHistory` prunes only archives matching the pattern of the appender that wrote them, so a dead
pid's file is managed by nobody and stays forever. At four to five instances per server per restart
that accumulates quickly — per-pid on its own trades a file that grows without bound for a
directory that does.

`LogCleaner`, which had done nothing since 0.9.21 removed its Claude-Desktop clearing, now prunes
**our own** logs: names beginning `mcp-filesystem` and ending `.log`, under `claude-sync/logs` only,
older than 2 days — the same window `maxHistory` promises — never its own live file, never another
server's prefix, and still never anything under Claude Desktop's directory. That last point has its
own test, because it is the behaviour that was removed and must not come back.

6 tests, including that a sibling's recent file is kept whatever its pid, and that a stale file
belonging to CS or AW is left alone: a sweep that reached across servers would delete a sibling's
live file the moment that sibling had been idle for two days.

Verified live: every instance wrote `mcp-filesystem-<pid>.log`, each a few tens of KB, and every one
logged the sweep. The pre-per-pid files are correctly left in place for now — they were modified
today, so they are inside the retention window, and will be collected once they age out.

Suite: 32 suites, 350 tests, 0 failures.


## 0.9.25 — the flake that was losing writes

The suite failed exactly one spec per full run and a different one each time —
`WriteCommitterSpec CT-PCOMMIT-2` on one tree, `FileContractSpec CT-18` on a clean one — while
every one of them passed in isolation. Carried as "the suite is flaky", which is the reading that
makes a suite useless: a check that fails at random is a check nobody can read, and it had already
been written up as *"Suite: 32 suites, 350 tests, 0 failures"* on the strength of a run that
happened to be green.

It was never flakiness. It was a real, intermittent, **data-losing** defect in
`WriteUtils.atomicWrite`, and the suite had been reporting it honestly all along.

The CT-18 failure text was the whole diagnosis, sitting in the XML the entire time:

```
...\ct18.groovy.tmp -> ...\ct18.groovy    isError:true
```

A bare `source -> target` with no reason is `java.nio.file.FileSystemException.toString()`.
Reproduced deliberately rather than inferred: hold any open handle on the target and
`Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)` throws `AccessDeniedException` with
message `"<tmp> -> <target>"` and `getReason()` null — the exact shape observed — and the same move
succeeds the instant the handle closes.

On Windows that handle is routinely somebody else's and gone milliseconds later: Defender scanning
the freshly created `.tmp`, the search indexer, an editor, a sibling thread. `atomicWrite` caught
only `AtomicMoveNotSupportedException`, so the transient case propagated as permanent, the
enclosing `catch` deleted the `.tmp`, and **the write was lost** — surfaced to the caller as
"atomic write failed". Under a 350-test suite hammering `%TEMP%` the odds of hitting one such
window per run are high; inside a single spec they are nearly nil. That asymmetry is exactly what
made a real defect look like test noise, and why it survived: every investigation re-ran the one
spec, alone, and it passed.

`moveIntoPlace` now retries the rename 10 times with linear backoff (25ms … 225ms, 1125ms total).
`NoSuchFileException` is never retried — a missing source cannot appear — and the last exception is
rethrown unchanged so the caller still sees the real reason.

**Bounded on purpose, and the spec says so.** `AtomicWriteRetrySpec` CT-AWR-3 holds a lock that is
never released and asserts the write still throws, within 15s, with the original content intact and
no `.tmp` left behind. A retry loop that turned a permanent failure into a hang, or into silence,
would pass CT-AWR-1 and be worse than the defect it replaced. CT-AWR-4 pins the uncontended path:
exact bytes, no backoff paid by a write that never needed one.

This also retires an assumption in `WriteCommitterSpec`'s own comment — *"WriteCommitter reduces
(but cannot eliminate on Windows) the concurrent-write race window"*. The window it could not
eliminate was this one, and it was in the layer below.

Suite: 33 suites, 354 tests, 0 failures — measured over **6 consecutive full runs**, not one.
Before the fix it was one failure per run, reliably, and a different spec each time.


## 0.9.26 — ONTOLOGY-GATE: one question, asked at dispatch

Paired with **CS 1.0.56**, which carries the measurement and the two-defect analysis in full.
Neither half works alone: repair the path lookup on its own and every read blocks, repair the
locate recorder on its own and nothing changes.

**`checkOntologyGate` no longer decides anything itself.** It asks CS one question about one path —
`ontologyGateCheck(normalizedPath, claimedSessionId)` — and takes the answer. Gone with the old
implementation:

- the bare-**stem** fuzzy lookup and the path-scope guard that followed it, which between them
  switched the gate off for every file with a sibling `Spec` (which is most of the tree);
- `sessionLocatedStems` and `recordLocateCalled`, an in-memory Set and **a writer with no callers**;
- the `.groovy`/`.java` extension filter. Whether a file is gated is now decided by whether the
  **ontology** holds it, which is the actual question — `.md`, `.adoc` and anything else an indexer
  covers are gated on the same terms, and anything unindexed passes.

**The session passed to CS is this process's CLAIMED session** (`readActiveSessionId()`), not a
singleton and not CS's own idea of it. A claim arrives on this process's own pipe, so it is the one
thing that actually identifies the chat.

### The gate moved to dispatch, because three actions out of eight was gating the exception

It used to be applied inside `doRead`, `doRange` and `doGetMethod`. `grep`, `head`, `tail`,
`structure` and `summary` walked straight past — and those are the *cheap* calls, which is to say
the ones actually used. The check now runs once in `FileReadService` **before the action switch**,
the same correction CS 1.0.52 made when it moved its knowledge dirty-flag ahead of the handler
fast path.

The exempt set is **named explicitly** rather than left to fall through, so an action added later is
gated by default and has to be argued out of the set instead of quietly missing it. Exempt because
they return no file content (`exists`, `stat`, `info`, `checksum`, `normalize`, `project_root`,
`allowed_dirs`, `list`, `help`) or carry no single path (`multi`, `multi_grep`, `chunk_read`,
`finalise_read`). `multi` applies its own per-path guard; **`multi_grep` does not, and that is a
known remaining gap rather than a decision.**

### The specs that proved a world that does not exist

`OntologyGateEnforcementSpec` OGE-1..11 passed the whole time the gate was inert. It stubbed
`getOntologyRange` to return the file's own path — in reality the stem resolves to the Spec — and
stubbed `locateCalledThisSession` to be capable of returning `true` — in reality its writer had no
callers. The stubs hand-built the exact conditions production never produces. They now stub the
single gate answer: a stub can still lie, but only about the answer, not about the shape of the
world.

`OntologyGateCoverageSpec` OGC-1..4 holds both defects shut and pins the gate ahead of the switch,
with every assertion run over **code lines only** — this release's own comments discuss
`getOntologyRange` and `recordLocateCalled` at length, and a raw-source absence check would be
satisfied by that prose, which is exactly how CS 1.0.55 red-built earlier the same day.

Suite: FS **358 tests, 34 suites, 0 failures** — counted from the JUnit XML.

---

## 0.9.27 — 2026-09-11 — the warning that was not raised

W11.1 of `BUILD-BRIEF-2026-09-11-the-sweep-that-would-have-doubled-the-count.md`.

CS shouts when a process holds no session claim: an observation write comes back carrying
`unbound: true`, `unbound_warning` and `owner_key` (`ContextWriteActionRouter.markIfUnbound`).
**FS said nothing**, and quietly filed every subsequent call into the `session_id='unknown'`
holding pen. The asymmetry is the defect — a lost FS claim was invisible from inside the chat.

### What it cost, measured

A Claude Desktop auto-update respawned all three JVMs mid-session on 2026-09-11. CS was re-claimed
within the minute because it complained. FS was not. Session `2026-09-11-08-52` then filed
**13 `file_read`/`file_search` calls to the pen** — every read it made after the restart — so
`read_count` and `ontology_pct` both recorded **0** for a session that had located before every
indexed read it made.

Worth correcting the record: the five FS rows that *were* attributed to that session are all
pre-restart and contain **no read action at all** (`claim_session`, `file_write:write`,
`file_write:multi_replace`, `execute:powershell` ×2). So "every FS row went to unknown" is not what
happened, and a startup-time claim check would have passed. The warning has to fire **mid-session,
on a claim that was previously good**.

### What 0.9.27 does

`McpController.handleToolsCall` already resolved the session on every call and coalesced `null`
straight to `'unknown'`, throwing away the only signal that the row was about to be unattributed.
That `null` is now kept, and when it is seen the response carries the warning.

`unboundWarningMap(ownerKey)` returns CS's three keys with FS wording and the FS claim call.
`withUnboundWarning(response, ownerKey)` returns a **copy** of the response with that map appended
as a **second `content` element**.

Three deliberate properties, each the reason it is not done the way CS does it:

1. The handler's payload — a JSON string in `content[0].text` — is not parsed, not re-serialised
   and not touched. Injecting a key into it at the dispatch boundary would mean round-tripping
   every handler's output through a parser to add a warning.
2. It is appended **outside the handler**, so no response trim can reach it. CS computed its
   unbound warning correctly in 1.0.25 and never delivered it, because `KEEP_KEYS` dropped it —
   the sixth time that list ate the evidence a fix existed to produce. This is deliberately not
   the seventh.
3. It is appended **after** `estimateResponseSize` and `recordToolCall`, and `withUnboundWarning`
   does not mutate its input, so the warning never inflates
   `tool_call_telemetry.response_char_count` and never trips the global response backstop. The
   loudness costs nothing in the metric it would otherwise pollute.

The warning is attached to whatever is actually returned, **backstop error included**: being
unbound is orthogonal to the call having failed, and a session whose response was just refused is
precisely the one that needs telling why its telemetry will not count either.

The **response** warning fires on every call while unbound, deliberately, and extinguishes itself
on the next `claim_session`. The **log** line does not: one WARN per unbound streak, reset when a
claim is seen, because a flow-node process can make thousands of calls it was never meant to claim
for.

### Specs — and the A/B that matters

`FsUnboundLoudnessSpec` CT-UBW-1..6. CT-UBW-4 derives the expected key set from
`unboundWarningMap`'s own `keySet()` rather than transcribing it, so a key added later fails the
spec instead of vanishing — CS's CT-UBW-3 trick, and the reason that list stopped rotting.

CT-UBW-6 asserts end to end through `handleRequest`, one layer out from the helper. **A/B'd in both
directions before shipping:** with the dispatch wiring removed and the helper left in place,
CT-UBW-1..5 stay green and **only CT-UBW-6 goes red**. That is the point of it — a guard that runs
only in the test is this platform's signature defect, and this release is the one that proves the
guard is reached in production, not merely that it exists.

### Contract

`fs-telemetry-not-stranded-in-unknown` (code, warn) registered red at **356**. It fires only when
an FS row is *still* `unknown` after a later session has started — not when one is created
unknown, because flow nodes legitimately write from unclaimed processes and a contract counting
those would be permanently red for correct behaviour.

Suite: FS **364 tests, 35 suites, 0 failures** — counted from the JUnit XML (was 358/34 at 0.9.26;
the delta is exactly `FsUnboundLoudnessSpec`).
