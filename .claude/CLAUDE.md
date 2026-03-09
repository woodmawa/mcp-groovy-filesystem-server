# mcp-groovy-filesystem-server — Claude Code Standing Orders
_Read this before any action. Non-negotiable._

---

## 1. Environment — Hard Constraints

| Fact | Rule |
|------|------|
| Windows project, Linux CC container | Use `groovy-filesystem` MCP tools for ALL file I/O. Do NOT use `bash` to read/write Windows paths. `/mnt/c/` does NOT exist in the container. |
| Build tool | **Will runs builds manually on Windows.** Do not emit `./gradlew` or `gradlew.bat`. Do not suggest or attempt build/test from bash. |
| Execution (scripting) | Use `groovy-filesystem:execute action=powershell` or `action=groovy` for Windows-side scripting when needed. |
| JAR deployment | After build, copy jar to `C:/Users/willw/claude-sync/jars/`. Will handles service restart. |

---

## 2. Session Start Sequence — Do This First, Every Time

```
1. context_lifecycle  action=start
2. context_read       scope=project  action=context  groupId=mcp-servers  knownHash=<last_stable_hash if provided>
3. context_read       scope=session  action=resume
```

Then read the SKILL.md before making any edits:
```
file_read action=read  path=C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/skills/SKILL.md
```

The SKILL.md contains the safe editing workflow, ontology-first orientation pattern,
and anti-patterns that caused broken builds in previous sessions. **Read it every session.**

---

## 3. Project Architecture — Fast Orientation

```
src/main/groovy/com/softwood/mcp/
  service/          ← 7 ToolHandler implementations (ExecuteService, FileReadService, etc.)
  filesystem/       ← PathService, SecurityService, AbstractFileService base
  controller/       ← HttpMcpController (HTTP transport), McpController (stdio)
  config/           ← JacksonConfig, CommandWhitelistConfig, ServerVersion
  telemetry/        ← FilesystemTelemetryService, UsageTracker (SQLite)
skills/SKILL.md     ← Safe editing workflow (read every session)
```

**ToolHandler auto-discovery:** all `@Component` classes implementing `ToolHandler` are
automatically registered. Never manually wire a new tool handler — just annotate it.

**Key service dependencies:**
- `PathService` — path normalisation, allowed-dir enforcement, `normalizePath()`, `isPathAllowed()`
- `SecurityService` — `validateScript()`, `sanitize()` — call on all user-supplied input
- `AbstractFileService` — base for all service classes; provides `pathService`, `securityService`, `activeProjectRoot`

---

## 4. Critical Groovy / @CompileStatic Rules — Cheatsheet

Full detail in `skills/SKILL.md` and (for @CompileStatic) in the groovyfx project's
`.claude/groovy-compilestatic-pitfalls.md` if you need the deep reference.

### 4.1 Map literals need explicit cast under @CompileStatic
```groovy
// WRONG
results << [key: value, count: 42]

// CORRECT — parentheses + cast always
results << ([key: value, count: 42] as Map<String, Object>)
```

### 4.2 GString `${}` in shell/script content — already solved
`ExecuteService.doPowershell()` and `doPython()` both write to temp files — this is the
correct pattern. **Never revert to `-c` or `-Command` for these two actions.**
`doCmd()` still uses inline `/c` — be aware `{}` will be eaten if the cmd script contains them.

### 4.3 CRLF before editing any pre-existing file
Run `file_read action=head` first. If lines appear double-spaced or `\r` is visible, it's
CRLF. Use `patch` (line numbers). LF files: use `replace` (string match). Never mix.

---

## 5. File Editing Rules — Summary

Full workflow in `skills/SKILL.md`. Minimum rules:

1. **Always pass `expectedHash`** on every write to any file > 100 lines.
2. **`get_method` immediately before every `patch`** — not 3 calls ago.
3. **`replace` only after `grep` confirms uniqueness** — duplicate oldText → wrong location edited.
4. **Multiple changes to same file → `multi_replace`** — never sequential replace calls.
5. **Range-read after every write** — verify brace balance before moving on.

---

## 6. Version and Deploy Protocol

- Version string is in `build.gradle` → `version = 'X.Y.Z'`
- Version comment format: `// vX.Y.Z: <one-line description>`
- After bumping version: update `README.md` "What's New" section AND the version table
- Jar naming: `mcp-groovy-filesystem-server-X.Y.Z.jar`
- Deploy: copy to `C:/Users/willw/claude-sync/jars/`
- Five-config rule: on version bumps, ALL five canonical config files must reference the new jar:
  - `%APPDATA%\Claude\claude_desktop_config.json`
  - `~/.claude.json`
  - `claude-sync/claude_desktop_config.json`
  - `claude-sync/claude_code_mcp_config.json`
  - `claude-sync/mcp-http-servers.json`

---

## 7. Session End

```
context_lifecycle action=end
```

Always call this. It indexes the session and enables resumption next time.
