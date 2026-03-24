# SESSION ORIENTATION — Read This First
_Every session. Before any filesystem exploration._

---

## CRITICAL CALL PATTERNS — Get These Right First

These are the patterns most commonly called incorrectly. Read before doing anything else.

### `file_read action=list` — returns `listing_hash`, supports `knownHash` short-circuit

```
# First call — get entries AND listing_hash
file_read action=list path=<dir>
→ {action, path, entries:[...], count, listing_hash:"abc123456789"}

# Repeat call — pass prior listing_hash as options.knownHash
file_read action=list path=<dir> options={knownHash:"abc123456789"}
→ {unchanged:true, listing_hash:"abc123456789", count:N}   # ~15 tokens, no entries
→ {action, path, entries:[...], listing_hash:"new_hash"}    # only if directory changed
```

**Always store `listing_hash` after any `action=list` call. Pass it on repeat calls.**

---

### `file_read action=multi_grep` — grep across multiple files in ONE call

```
# NO path param needed — paths go in options.paths[]
file_read action=multi_grep
          options={
            pattern: "import org.softwood",
            paths: ["File1.groovy", "File2.groovy", "File3.groovy"],
            maxMatches: 5,        # per-file match cap (default 5)
            contextLines: 0       # optional before/after lines
          }
→ {action:"multi_grep", pattern, fileCount:3, matchingFiles:2, totalMatches:N,
   results:[{path, matchCount, matches:[{line, content}]}]}
   # Only files WITH matches appear in results
```

**Use instead of calling `file_read action=grep` 3+ times in a row on different files.**
**Maximum 20 files per call.**

---

### `file_read action=grep` — FILE path only, NOT a directory

```
# CORRECT — single file
file_read action=grep path=<exact_file_path> options={pattern:"regex"}

# WRONG — directory path → hard error "Path is not a file"
file_read action=grep path=<directory>       ← FAILS

# If you need to grep across a directory: use file_search action=content
file_search action=content path=<dir> options={contentPattern:"regex"}
```

---

### `file_read action=read` — refused for files >200 lines

```
# WRONG — for large files
file_read action=read path=<large_file>     ← refused if >200 lines

# CORRECT alternatives:
file_read action=structure path=<file>                          # class/method outline
file_read action=structure path=<file> options={className:Foo}  # single class only
file_read action=get_method path=<file> options={method:doThing} # one method body
file_read action=range path=<file> options={startLine:N, maxLines:50}
file_read action=read path=<file> options={force:true}           # override, use sparingly
```

---

### `file_write action=server_transform` — correct param names

```
# replace_method: body goes in options.newBody — NOT content
file_write action=server_transform path=<file>
           options={transform:"replace_method", method:"doThing",
                    newBody:"    ReturnType doThing(...) {\n        ...\n    }",
                    expectedHash:"<hash>"}

# replace_between: content goes in options.newContent
file_write action=server_transform path=<file>
           options={transform:"replace_between",
                    startAnchor:"// START", endAnchor:"// END",
                    newContent:"...", expectedHash:"<hash>"}

# replace_section: heading-based (## headings only), content in options.newContent
# insert_after_heading: content in options.content
# append_section: content in options.content
# add_method: body in options.newBody
# add_import: import statement in options.importStatement

# WRONG — content is top-level param for write/append only, not server_transform
file_write action=server_transform ... content="..."  ← WRONG PARAM
```

---

### `execute` — builds and shell commands

```
# CORRECT — always action=cmd for gradle and git on Windows
groovy-filesystem:execute action=cmd
  script="gradlew.bat bootJar"
  options={workingDir:"C:/Users/willw/IdeaProjects/<server>", timeout:120,
           maxStdout:2000, maxStderr:1000}

# Git operations via execute action=cmd
groovy-filesystem:execute action=cmd
  script="git add -A && git commit -m \"message\" && git tag v1.2.3 && git push --tags"
  options={workingDir:"C:/path/to/repo"}

# WRONG — groovy-filesystem:tools no longer exists as a separate MCP tool
# groovy-filesystem:tools action=git subcommand=commit  ← DOES NOT EXIST
```

---

### `file_write` — `expectedHash` is mandatory for all mutations

```
# ALL write/replace/patch/multi_replace/server_transform need expectedHash
# Get it from the most recent file_read response (file_content_hash field)

file_read action=get_method path=<file> options={method:"doThing"}
→ {content, file_content_hash:"abc123456789"}

file_write action=patch path=<file>
           options={replacements:[{startLine:N, endLine:M, newText:"..."}],
                    expectedHash:"abc123456789"}   ← from prior read

# Omitting expectedHash on multi_replace / replace silently passes but is unsafe
```

---

## The Rule: Ontology Before Filesystem

Before reading source files for orientation, check the ontology first.

### Orientation order (cheapest to most expensive)

| Goal | Tool | Cost |
|------|------|------|
| What files exist in a project | `context_read scope=ontology action=file-map cluster=<X>` | ~200 tok |
| What methods a class has | `context_read scope=ontology action=class-detail nodeId=<Class>` | ~400 tok |
| Full method body (before patching) | `file_read action=get_method path=<file> options={method:<n>}` | ~200 tok |
| Directory contents (first time) | `file_read action=list path=<dir>` | ~300-800 tok |
| Directory contents (repeat) | `file_read action=list options={knownHash:<prior_hash>}` | ~15 tok |
| Grep one pattern across N files | `file_read action=multi_grep options={pattern, paths:[...]}` | ~100-300 tok |
| Grep in single file | `file_read action=grep path=<file> options={pattern}` | ~100-200 tok |
| Grep across a directory | `file_search action=content path=<dir> options={contentPattern}` | ~200-400 tok |

Cluster names: `filesystem-server` | `context-server` | `llm-orchestrator` | `agentic-workflow` | `groovy-concurrent-utils`

---

## Anti-Patterns — Never Do These

| Anti-pattern | Why | Alternative |
|---|---|---|
| `file_list:tree` for project layout | 626+ tokens, repeat every session | `context_read scope=ontology action=file-map cluster=X` |
| `file_read:read` on large file for orientation | 1K-3K tokens | `class-detail` then `get_method` only when patching |
| `file_read:grep` with directory path | Hard error: "Path is not a file" | `file_search action=content` for directories |
| Multiple `file_read:grep` calls on different files | N separate round-trips | `file_read action=multi_grep options={paths:[...]}` |
| `file_read:list` without storing `listing_hash` | Wastes tokens on repeat calls | Store hash, pass as `knownHash` next time |
| Re-reading unchanged file without `knownHash` | Full re-read cost | Pass `options.knownHash` from prior `file_content_hash` |
| `server_transform replace_method` with `content=` | Wrong param name, silent miss | Use `options.newBody=` |
| `execute action=powershell` with `cmd /c` inside | Blocked by SecurityService | Use `execute action=cmd` directly |
| Sequential `multi_replace` calls | Line numbers shift, second call misses | One `multi_replace` with all replacements |

---

## Package Roots — Cross-Project Editing

| Server | Package root path segment |
|--------|--------------------------|
| `filesystem-server` | `.../mcp-groovy-filesystem-server/src/main/groovy/com/softwood/mcp/` |
| `context-server` | `.../mcp-groovy-context-server/src/main/groovy/com/woodmawa/mcp/context/` |
| `llm-orchestrator` | `.../mcp-llm-orchestrator/src/main/groovy/com/woodmawa/mcp/orchestrator/` |
| `agentic-workflow` | `.../mcp-agentic-workflow/src/main/groovy/com/woodmawa/mcp/workflow/` |

**Rule:** When editing a server other than the current one, do `file_read action=exists` on one known file first to confirm the path root is correct.

---

## Safe Edit Workflow

### Pattern 1 — Edit a method body (preferred)

```
# 1. Get exact line range AND file hash
file_read action=get_method path=<file> options={method:"doThing"}
→ {startLine:N, endLine:M, file_content_hash:"abc123"}

# 2. Patch with those bounds + hash
file_write action=patch path=<file>
           options={replacements:[{startLine:N, endLine:M, newText:"..."}],
                    expectedHash:"abc123"}
```

### Pattern 2 — Small unique insertion

```
# 1. Confirm exactly ONE match
file_read action=grep path=<file> options={pattern:"unique anchor", contextLines:2}
→ matchCount must be 1, grab file_content_hash

# 2. Replace using confirmed unique text + hash
file_write action=replace path=<file>
           options={oldText:"<confirmed text>", newText:"<new>", expectedHash:"<hash>"}
```

### Pattern 3 — Multiple edits to the same file

```
# ONE call — pre-validates ALL replacements before writing
file_write action=multi_replace path=<file>
           options={replacements:[
             {oldText:"exact string 1", newText:"replacement 1"},
             {oldText:"exact string 2", newText:"replacement 2"}
           ], expectedHash:"<hash from last read>"}
```

**After any successful write, use the returned `content_hash` as `expectedHash` for the next edit.**

---

## Quick Reference

| Situation | Action |
|---|---|
| Edit a single method | `get_method` → `patch` + hash |
| Small unique insertion | `grep` → `replace` + hash |
| Multiple edits, same file | `multi_replace` + hash |
| Replace named method (server-side, zero context cost) | `server_transform transform=replace_method options.method=X options.newBody=Y` |
| Replace between anchors (any file type) | `server_transform transform=replace_between startAnchor=X endAnchor=Y newContent=Z` |
| Replace markdown section (## heading) | `server_transform transform=replace_section heading=X newContent=Y` |
| New file or full overwrite | `write` (no hash needed) |
| Append to end | `append` (no hash needed) |
| List directory (first time) | `file_read action=list path=<dir>` → store `listing_hash` |
| List directory (repeat) | `file_read action=list options={knownHash:<hash>}` → 15 tokens if unchanged |
| Grep one file | `file_read action=grep path=<file> options={pattern}` |
| Grep N files for one pattern | `file_read action=multi_grep options={pattern, paths:[...]}` |
| Grep across directory | `file_search action=content path=<dir> options={contentPattern}` |
| Build jar | `execute action=cmd script="gradlew.bat bootJar" options={workingDir, timeout:120}` |

---

## Groovy Gotcha — Fully-Qualified Class Names in Closures

Never use fully-qualified class names inside closure bodies or static helpers — Groovy treats dotted names as property-access chains and fails to resolve them. Always add explicit named imports at the top of the file for every class used anywhere, including inside closures.

Affected namespaces: `org.apache.poi.*`, `org.openxmlformats.schemas.*`, and any other deep package path.

```groovy
// CORRECT — import at top, short name everywhere
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz
class Foo { static void bar(def x) { CTPageSz s = x.addNewPgSz() } }

// WRONG — fails inside closures/static methods
class Foo { static void bar(def x) { org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz s = ... } }
```
