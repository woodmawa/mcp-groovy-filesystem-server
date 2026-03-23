# SESSION ORIENTATION — Read This First
_Every session. Before any filesystem exploration._

---

## The Rule: Ontology Before Filesystem

The context server maintains an indexed ontology of all four MCP projects.
Before using ANY of the following for orientation, query the ontology first:
- `file_list:tree` or `file_list:children`
- `file_read:read` on a file you haven't edited yet this session
- `file_read:structure` to understand a class layout

### Step 1 — Get the file map for your target cluster (replaces tree/children)

```
context_read scope=ontology action=file-map cluster=<target>
```
cluster values: `filesystem-server` | `context-server` | `llm-orchestrator` | `agentic-workflow`

Returns: all files in the cluster with one-line purpose and line count.
Cost: ~200 tokens. Replaces: `file_list:tree` (~626 tokens) + follow-up structure calls.

**NOTE**: This endpoint exists from context-server v0.13.0 onwards. If you get an error
saying unknown action, the server is pre-v0.13.0 — fall back to `file_list:children` and
note that Ship 2 has not yet been deployed.

### Step 2 — Understand a specific class (replaces structure + read)

```
context_read scope=ontology action=class-detail nodeId=<ClassName>
```

Returns: class purpose, all methods with doc comment, startLine, endLine, visibility.
Cost: ~400-600 tokens. Replaces: `file_read:structure` (~400 tokens) + context you'd only
get by reading the file body.

**CRITICAL**: `endLine` from class-detail gives you exact patch bounds — you still need
`file_read:get_method` immediately before patching to get the current body and file hash,
but you no longer need `file_read:structure` first to find the method.

**NOTE**: Requires context-server v0.13.0+. If error, fall back to `file_read:structure`.

### Step 3 — Fall through to filesystem only when ontology can't answer

Ontology CANNOT answer:
- Config files (build.gradle, application.yml, *.json, *.ps1)
- Files added since last `context_write scope=ontology action=index-dir`
- The actual current body of a method (always use get_method before patching)

Ontology CAN answer:
- What files exist in a project and what they do
- What methods a class has, where they start/end, what their purpose is
- What annotations a class/method has
- What dependencies a class injects

---

## Orientation Anti-Patterns — Never Do These

| Anti-pattern | Why | Alternative |
|---|---|---|
| `file_list:tree` to understand project layout | 626+ tokens, re-run every session | `context_read scope=ontology action=file-map cluster=X` |
| `file_list:children` loop to find a file | Expensive if project is large | `file_search action=name` or ontology file-map |
| `file_read:read` on >100-line file for orientation | 1,000–3,000 tokens, you only need structure | `class-detail` then `get_method` only when patching |
| `file_read:structure` before every edit | 400 tokens, often repeated | `class-detail` gives same info + doc + end_line |
| Re-reading a file you already read this session | Wastes tokens if unchanged | Pass `options.knownHash` — returns {unchanged:true} instantly |
| `file_list:children` on multiple dirs to orient | Chain of expensive calls | One `file-map` call covers the whole cluster |
| `execute powershell Get-ChildItem` to list a dir | Full execute round-trip ~800-1200 bytes | `file_read action=list` — compact JSON, ~300-500 bytes |
| `file_read:structure` without `className` on multi-class file | Returns entire file outline | Add `options.className=Foo` to get just that class subtree |
| `file_read:grep` with a directory path | Hard error: "Path is not a file" | `grep` requires a FILE path — use `file_search action=content` to search across a directory |

---

# Filesystem Editing Skill — Safe Workflow
_Part of: mcp-groovy-filesystem-server_
_Read this before making any multi-step edits to large source files._

---

## Why This Exists

Unsafe editing patterns caused 3+ broken builds in mcp-agentic-workflow Phase 6.
Symptoms: stray brackets, duplicate declarations, wrong class references — caused by
blind `replace` calls without hash guards, or sequential edits without re-reading.

---

## Pre-Edit Guards — Check Before ANY Edit

### Guard 1: Package path (cross-project editing)

Each server has a **different** package root. Using the wrong one gives `File not found` with no further hint.
Always verify before constructing paths from memory.

| Server | Package root | Path segment |
|--------|-------------|-------------|
| filesystem-server | `com.softwood.mcp` | `.../mcp-groovy-filesystem-server/src/main/groovy/com/softwood/mcp/` |
| context-server | `com.woodmawa.mcp.context` | `.../mcp-groovy-context-server/src/main/groovy/com/woodmawa/mcp/context/` |
| llm-orchestrator | `com.woodmawa.mcp.orchestrator` | `.../mcp-llm-orchestrator/src/main/groovy/com/woodmawa/mcp/orchestrator/` |
| agentic-workflow | `com.woodmawa.mcp.workflow` | `.../mcp-agentic-workflow/src/main/groovy/com/woodmawa/mcp/workflow/` |

**Rule:** When editing a server other than the one in your current working directory,
do a `file_read action=exists` on one known file first to confirm the path root is correct.

---

### Guard 2: Triple-quoted strings in multi_replace

Before including a method in a `multi_replace` `oldText` entry, check:
> Does the method body contain Groovy `'''` triple-quoted strings?

- **YES** → use `server_transform replace_method` with `options.newBody` for that method
  (do not include it in multi_replace at all)
- **NO** → safe to include in multi_replace

The failure mode: JSON `\n` ≠ Groovy literal newline inside `'''` blocks — match silently fails,
file is NOT modified, error says `oldText not found`.

---

### Guard 3: server_transform replace_method parameter name

The new body parameter is `options.newBody` — **not** `content`.
`content` is a top-level param for `write`/`append` only.

```
# CORRECT
file_write action=server_transform path=<file>
           options.transform=replace_method
           options.method=<methodName>
           options.newBody="    ReturnType method(...) {\n        ...\n    }"
           options.expectedHash=<hash>

# WRONG — gives: "options.newBody is required for replace_method"
file_write action=server_transform path=<file>
           options.transform=replace_method
           options.method=<methodName>
           content="..."              ← WRONG PARAM NAME
```

---

## The Three Safe Patterns

### Pattern 1: Edit a method body (PREFERRED for any method-level change)

```
# 1. Read the method — gets exact line range AND file hash
file_read  action=get_method  path=<file>  options.method=<methodName>
           -> returns: startLine, endLine, file_content_hash

# 2. Patch using those line numbers + the hash (MANDATORY)
file_write action=patch  path=<file>
           options.replacements=[{startLine: N, endLine: M, newText: "..."}]
           options.expectedHash=<hash from step 1>
```

Why patch beats replace: line addresses are always unique. String matching is fragile
in large Groovy files where patterns like `] as Map<String,Object>)` repeat.

---

### Pattern 2: Small unique insertion

> **CRITICAL: `grep` requires a FILE path, not a directory.**
> Passing a directory gives: `IllegalArgumentException: Path is not a file`.
> To search across multiple files in a directory, use `file_search action=content` instead.
> Only use `file_read action=grep` once you already have the exact file path.

```
# 1. Confirm exactly ONE match exists
file_read  action=grep  path=<file>  options.pattern=<unique anchor>
           options.contextLines=2
           -> if matchCount != 1, use Pattern 1 instead

# 2. Replace using the hash from grep (MANDATORY)
file_write action=replace  path=<file>
           options.oldText=<confirmed unique text>
           options.newText=<replacement>
           options.expectedHash=<hash from grep>
```

---

### Pattern 3: Multiple edits to the same file

```
# ONE call - pre-validates ALL replacements before writing anything
file_write action=multi_replace  path=<file>
           options.replacements=[
             {oldText: "...", newText: "..."},
             {oldText: "...", newText: "..."}
           ]
           options.expectedHash=<hash>
```

NEVER make sequential `replace` calls to the same file. Each call changes the hash;
the next call writes blind against stale context.

**Triple-quoted string warning:** If ANY entry in `replacements` targets a method that
contains Groovy `'''` triple-quoted strings, `multi_replace` will fail (JSON `\n` ≠
Groovy literal newline inside `'''`). Use `server_transform replace_method` for those
methods instead, and `multi_replace` only for the entries that don't contain `''']`.

---

## Critical Rules

- NEVER use `replace` or `patch` without `expectedHash` on files > 100 lines
- NEVER use `replace` without confirming uniqueness via `grep` first
- NEVER make sequential `replace` calls -- use `multi_replace` or `patch`
- NEVER use a hash from before a previous edit -- re-read after every write
- PREFER `patch` over `replace` for files > 200 lines

---

## Anti-Patterns That Caused Failures

| Anti-pattern | Risk | Fix |
|---|---|---|
| `replace` without `expectedHash` | Silent drift, edit lands in wrong place | Always pass hash |
| `range()` then `replace()` ignoring returned hash | Blind write | Pass `file_content_hash` from range |
| 5+ sequential `replace` calls | Each corrupts context for next | Use `multi_replace` or re-read between |
| `replace` with non-unique `oldText` | Edits wrong occurrence | Grep first, or use `patch` |
| `replace` without `path` param | Returns opaque "Path not allowed: null" | **Always** include `path` at top level |
| `patch` with wrong line range | Duplicates code or orphans lines | **Always** `get_method` or `range` immediately before patching |
| `patch` that doesn't cover the full old block | Leaves stale lines above/below | Verify startLine/endLine span the **entire** block being replaced |
| Multiple sequential `patch` calls | Line numbers shift after first patch | Re-read between patches, or combine into single patch with multiple replacements |
| `multi_replace` when `oldText` contains Groovy triple-quoted strings (`'''`) | JSON `\n` ≠ Groovy `\<newline>` — match fails silently; file unchanged | Use `server_transform replace_method` for any method whose body contains `'''` strings |
| `server_transform replace_method` with `content` param | Returns "options.newBody is required for replace_method" | Param is `options.newBody`, not `content` — always |

---

## Patch Discipline (added v0.7.54 — from session that produced 4 broken patches)

The `patch` action replaces lines startLine..endLine with newText. Getting this wrong
leaves the file in a broken state (duplicate lines, orphaned code, missing closures).

### The Iron Rule: Read → Patch → Verify

1. **Read** the exact lines you intend to replace (`get_method` or `range`)
2. **Count** the startLine and endLine from the response — these are your patch bounds
3. **Write** the complete replacement in newText — include ALL structural elements
   (closing braces, method javadoc, blank separator lines)
4. **Verify** the result with another `range` read after patching

### Common Patch Mistakes

- **Partial replacement**: Your endLine doesn't cover the closing `}` or javadoc of
  the next method. Result: duplicate closing braces or orphaned comment lines.
- **Forgetting `ins.executeBatch()` / `ins.close()`**: When replacing loop bodies,
  the lines after the loop are part of the method but NOT part of the loop.
  If your endLine stops at the loop's `}`, you lose the lines between loop-end
  and method-end.
- **Line shift blindness**: After a patch that adds/removes lines, all line numbers
  below the patch point have shifted. A second patch using pre-shift line numbers
  will edit the wrong lines.

### Safe Patch Checklist

```
☐ Did I read the file IMMEDIATELY before this patch? (not 3 tool calls ago)
☐ Does my startLine..endLine span cover EXACTLY the lines I want to replace?
☐ Does my newText include ALL structural elements (braces, blank lines, javadoc)?
☐ Am I passing expectedHash from my most recent read?
☐ After patching, will I verify the result before making another patch?
```

---

## Pattern 4: Server-side transform (v0.8.2+) — PREFER for named method/section edits

When you need to replace a named method or markdown section, use `server_transform`.
File content **never** crosses the context boundary — only the hash and result come back.
Saves ~500–2000 tokens vs read → patch.

```
# Replace a method — name-driven, no line numbers needed
file_write action=server_transform  path=<file>
           options.transform=replace_method
           options.method=<methodName>
           options.newBody="    ReturnType methodName(...) {\n        // new body\n    }"
           options.expectedHash=<hash from any prior read>

# Replace a markdown section body (heading is preserved)
file_write action=server_transform  path=<file>
           options.transform=replace_section
           options.heading="Section Title"      # without # prefix
           options.newContent="new body text"
           options.expectedHash=<hash>

# Insert after a heading (additive)
file_write action=server_transform  path=<file>
           options.transform=insert_after_heading
           options.heading="Section Title"
           options.content="lines to insert"
           options.expectedHash=<hash>

# Replace lines between two unique anchors (anchors preserved)
file_write action=server_transform  path=<file>
           options.transform=replace_between
           options.startAnchor="// BEGIN GENERATED"
           options.endAnchor="// END GENERATED"
           options.newContent="new content"
           options.expectedHash=<hash>

# Append new heading + body at EOF
file_write action=server_transform  path=<file>
           options.transform=append_section
           options.heading="New Section"
           options.content="body text"
           options.headingDepth=2        # optional, default 2
           options.expectedHash=<hash>
```

**Decision rule:**
- Named method edit → always try `replace_method` first (no line lookup needed)
- Named markdown section → `replace_section` or `insert_after_heading`
- Need exact line-range control, or edit spans outside a named boundary → use `patch`
- Not-found errors always include a hint listing available methods/headings

**Common `replace_method` mistakes:**
- `content` is WRONG — the param is `options.newBody` ("options.newBody is required" error)
- `options.method` must match the exact method name as it appears in `structure` output
- If `replace_method` says method not found, check the structure listing for the exact name

**Returns:** `{success, content_hash, lines_affected, message}` — use `content_hash` as
`expectedHash` for any subsequent edit to the same file.

---

## Quick Reference

| Situation | Action |
|---|---|
| Edit a single method | `get_method` -> `patch` + hash |
| Small unique insertion | `grep` -> `replace` + hash |
| Multiple edits, same file | `multi_replace` + hash |
| New file or full overwrite | `write` (no hash needed) |
| Append to end | `append` (no hash needed) |
| Any file > 200 lines | Prefer `patch` always |
| List directory contents | `file_read action=list` (not `execute powershell Get-ChildItem`) |
| Gauge method size before reading | Check `lineCount` in `structure` response (v0.8.1+) |
| Multi-class file, only need one class | `file_read action=structure options.className=Foo` |
| Replace a named method (server-side, zero context cost) | `file_write action=server_transform options.transform=replace_method options.method=<name> options.newBody=<body> options.expectedHash=<hash>` |
| Replace a markdown section body | `file_write action=server_transform options.transform=replace_section options.heading=<text> options.newContent=<body> options.expectedHash=<hash>` |
| Insert lines after a heading | `file_write action=server_transform options.transform=insert_after_heading options.heading=<text> options.content=<lines> options.expectedHash=<hash>` |
| Replace lines between two anchors | `file_write action=server_transform options.transform=replace_between options.startAnchor=<text> options.endAnchor=<text> options.newContent=<body> options.expectedHash=<hash>` |
| Append new section at EOF | `file_write action=server_transform options.transform=append_section options.heading=<text> options.content=<body> options.expectedHash=<hash>` |

---

## Toolchain (git / gradle) — Required Params

---

## Groovy Gotchas — Language-Level Traps

### Fully-qualified class names fail inside closures

**Rule:** Never use fully-qualified class names inline in Groovy source — especially inside closure bodies or static helper methods. Groovy's compiler treats a dotted name like `org.apache.poi.xssf.usermodel.XSSFWorkbook` as a property-access chain, not a type reference, and fails to resolve it.

**This affects all POI/ooxml namespaces:**
- `org.apache.poi.ss.usermodel.*` (CellType, BorderStyle, FillPatternType, HorizontalAlignment…)
- `org.apache.poi.xssf.usermodel.*` (XSSFWorkbook, XSSFCell, XSSFCellStyle…)
- `org.apache.poi.xwpf.usermodel.*` (XWPFDocument, XWPFParagraph, XWPFRun…)
- `org.apache.poi.xslf.usermodel.*` (XMLSlideShow, XSLFSlide, XSLFTextShape…)
- `org.openxmlformats.schemas.*` (CTPageMar, CTPageSz, STPageOrientation, CTTransition…)

**Fix:** Add an explicit named `import` at the top of the file for every class used anywhere — including inside closures, nested methods, and static helpers. Do not rely on wildcard imports; they can mask missing types at Groovy compile time.

**Pattern — when writing any POI adapter file:**
1. List every POI/ooxml class you intend to use anywhere in the file
2. Write ALL imports first at the top, before any class/method bodies
3. Only then write method and closure bodies using short names

```groovy
// CORRECT
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation

class DocxAdapter {
    static void applyPage(def sectPr, PageSpec page) {
        CTPageSz pgSz = sectPr.addNewPgSz()   // works — short name, import present
    }
}

// WRONG — fails inside closure / static context
class DocxAdapter {
    static void applyPage(def sectPr, PageSpec page) {
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz pgSz = ...  // compile error
    }
}
```

**Caught in:** DocxAdapter.groovy — `CTPageMar`, `CTPageSz`, `STPageOrientation` used without imports in `applyPageSettings()`. Required a fix pass after `compileGroovy` failed.

---


`groovy-filesystem:tools` always requires **both** `action` and `subcommand` for git/gradle.
Omitting `subcommand` gives: `MCP error -32602: subcommand required for git`.

```
# CORRECT
mcp__groovy-filesystem__tools
  action=git
  subcommand=commit
  options.workingDir=<path>
  options.message="commit message"    ← required for commit or process hangs

mcp__groovy-filesystem__tools
  action=git
  subcommand=add
  args=["-A"]
  options.workingDir=<path>

mcp__groovy-filesystem__tools
  action=gradle
  subcommand=bootJar
  options.workingDir=<path>

# WRONG — missing subcommand
mcp__groovy-filesystem__tools
  action=git
  command="add -A && git commit -m ..."   ← no compound commands, no subcommand=
```

Allowed git subcommands: `status | log | diff | add | commit | push | pull | branch | stash | clone | fetch | checkout | merge | show | tag | remote | reset | revert`

Allowed gradle subcommands: `build | test | clean | compileGroovy | compileJava | bootRun | bootJar | jar | dependencies | tasks | check | assemble | publish | wrapper`

**git commit ALWAYS needs `options.message`** — without it the process hangs waiting for an editor.

---

## How to Read This File

From any Claude session with groovy-filesystem access:

```
file_read action=read
          path=C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/skills/SKILL.md
```