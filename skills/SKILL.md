# Filesystem Editing Skill — Safe Workflow
_Part of: mcp-groovy-filesystem-server_
_Read this before making any multi-step edits to large source files._

---

## Why This Exists

Unsafe editing patterns caused 3+ broken builds in mcp-agentic-workflow Phase 6.
Symptoms: stray brackets, duplicate declarations, wrong class references — caused by
blind `replace` calls without hash guards, or sequential edits without re-reading.

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

---

## Critical Rules

- NEVER use `replace` or `patch` without `expectedHash` on files > 100 lines
- NEVER use `replace` without confirming uniqueness via `grep` first
- NEVER make sequential `replace` calls -- use `multi_replace` or `patch`
- NEVER use a hash from before a previous edit -- re-read after every write
- PREFER `patch` over `replace` for files > 200 lines

---

## Anti-Patterns That Caused Phase 6 Failures

| Anti-pattern | Risk | Fix |
|---|---|---|
| `replace` without `expectedHash` | Silent drift, edit lands in wrong place | Always pass hash |
| `range()` then `replace()` ignoring returned hash | Blind write | Pass `file_content_hash` from range |
| 5+ sequential `replace` calls | Each corrupts context for next | Use `multi_replace` or re-read between |
| `replace` with non-unique `oldText` | Edits wrong occurrence | Grep first, or use `patch` |

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

---

## How to Read This File

From any Claude session with groovy-filesystem access:

```
file_read action=read
          path=C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/skills/SKILL.md
```
