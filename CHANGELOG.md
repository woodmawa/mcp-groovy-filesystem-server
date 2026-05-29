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
