# mcp-groovy-filesystem-server (FS)

- **Current version:** `0.9.117`
- **Baseline stack:** FS 0.9.117 / CS 1.1.121 / AW 1.30.114
<!-- Both lines are stamped by context_lifecycle action=bump_baseline and read by contracts stamp-fs-md-current,
     stamp-fs-md-cs and stamp-fs-md-aw. Do not hand-edit them and do not append release notes to them. -->

Shared rules (session shape, guards, deploy boundary, test discipline) are in `~/.claude/CLAUDE.md`, and the
session protocol is served by CS. This file holds only what is specific to FS. Release history: `CHANGELOG.md`.
The previous long-form guide is archived at `archive/claude-md/`. FS is described as a Part of the AW book.

## What FS is

File read/write/search/list, script execution, gradle and git, and the HTTP companions' lifecycle, for Windows.
Stdio for Claude Desktop plus an HTTP companion. Package root `com.softwood.mcp` (CS is `com.woodmawa.mcp.context`,
AW `com.woodmawa.mcp.workflow`). Every `@Component` implementing `ToolHandler` is registered automatically.

```
src/main/groovy/com/softwood/mcp/
  controller/   McpController (dispatch, response cap), HttpMcpController
  service/      FileRead/Write/List/Search/Lifecycle services, ExecuteService, ToolsService,
                ServerLifecycleService, ContextServerClient, FilesystemTelemetryService, PlanGateGuard
  service/read/   FileContentReader, FileStructureReader, FileMetaReader, ReadResponseHelper
  service/write/  FileContentWriter, FileReplaceService, FilePatchService, WriteUtils
  service/transform/  FileTransformService (server_transform)
```

## Calling FS correctly

- `file_read action=grep` takes a FILE; for a directory use `multi_grep` (paths may be directories) or
  `file_search action=content`. `range` takes `startLine` + `maxLines` or `endLine` (1-indexed, inclusive).
- Every content read is de-duplicated against what this chat was already sent (45-minute window) and answers
  `unchanged:true`; pass `options.force=true` after a compaction or when a subagent made the read.
- Every mutating write needs `options.expectedHash`. A write's returned `content_hash` is the next `expectedHash`;
  do not re-read to verify a write.
- `server_transform` keys: `replace_method` -> `method` + `newBody`; `add_method` -> `newBody`;
  `replace_section` / `replace_between` -> `newContent`; `insert_after_heading` / `append_section` ->
  `content`; `insert_before_match` -> `match` + `content`; `add_import` -> `import`.
- `options.allowStructuralEdit=true` bypasses the brace/paren guard for one repair call (never the box-drawing check).
  Appending to a code file is refused `CODE_APPEND`; use replace or `add_method`.
- Tool errors return `result.isError:true` with the text in `content[0].text`, not a JSON-RPC `error`.

## execute

- Every line of a multi-line script runs and the LAST exit code is returned; a mid-script failure does not
  abort the rest. Verify mutated state, do not trust one exit code.
- `success` requires the streams were actually read; `stream_error` means "could not measure", not "no output".
- Anything that may pass ~60 s is submitted, not awaited: `options.async=true` + `options.timeout`, then one AW
  `flow_management action=progress`. A long synchronous call moves to the background after 45 s and answers
  `moved_to_background:true` with a `jobId`. `job_status` on this process is the authority if AW is down.
- Builds use `tools action=gradle`, not `execute action=cmd`. `action=groovy` runs in-process and sandboxed:
  `workingDir` is a binding, not a chdir.
- FS repairs a broken inherited `PATHEXT` so native executables run under PowerShell.

## PLAN-GATE

`PlanGateGuard` asks CS once per (session, component) before a writing `file_write` or an `execute` whose
statements run `git` or `gradlew` in command position. Read-only git and listing forms, task-less gradle,
scratch files and `.md` / `.adoc` writes are not gated. `options.intent` (one sentence) is what it selects on.
Answer with `planAck`. Every uncertain path (no CS, no claim, timeout) passes.

## Isolation

- FS does not write `tool_call_telemetry`; it hands the row to CS (`record_tool_call`) and drops it if CS is down.
  Its only direct access to `best_practices.db` is its own `session_claims` row and the `pending_reindex` queue.
  Never DDL against CS's database. Do not add a third exception.
- Tests never reach live services: `src/test/resources/application.yml` points the CS client at
  `http://127.0.0.1:1`, the job-event client at a dead address, and claude-sync at a test-only fixture.
  Pinned by `TestsNeverReachLiveCsSpec`, `TestsNeverReachLiveAwSpec`, `TestsNeverReachLiveClaudeSyncSpec`. Do not remove them.

## Build and test

```
tools action=gradle subcommand=compileGroovy   options={workingDir:"C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server"}
tools action=gradle subcommand=testFast        options={workingDir:"..."}
tools action=gradle subcommand=packageMcpbThin options={workingDir:"..."}   # Claude stops here
```

Prefer the `gradle-test-summary` flow for test runs. `test` is the release gate; before a release run it with
`-Ptests.forks=1`.

## Gotchas

- In stdio mode never write to stderr; keep `<springProfile>` blocks top-level in `logback-spring.xml` and no
  `--` inside XML comments.
- A PowerShell rewrite of a `.groovy` file can re-encode non-ASCII as CP1252; use `\u` escapes in source and
  grep for mojibake after any such rewrite.
