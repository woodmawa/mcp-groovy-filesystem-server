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
