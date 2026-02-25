# mcp-groovy-filesystem-server v0.7.7

A Spring Boot MCP server providing filesystem and developer toolchain operations to Claude Desktop via STDIO, plus HTTP transport for local LLM agentic loops.

Eight parameterised tools replace what would otherwise be 30+ individual tools, keeping the MCP schema compact and token-efficient.

---

## What's New in v0.7.7

**Token optimisation — reduced per-session context cost:**

- **Tighter tool descriptions** — all 7 service descriptions rewritten in telegraphic style. ~40–50% reduction on the tool schema payload injected every session.
- **Slim `pathToMap`** — `readable`, `writable`, `executable` fields removed from every `file_list` / `file_read info` response. Always `true/true/true` on Windows; never used by Claude.
- **Relative paths in `file_list tree`** — tree nodes now use paths relative to the tree root (e.g. `"groovy/com/softwood"`) instead of repeating the full Windows absolute path on every node. Root is reported once as `rootPath` in the top-level response. Saves ~3 500 tokens on a 200-node tree.
- **Compact response mode** — pass `options.compact=true` to `file_read read`, `file_list children`, `file_write write/replace` to strip the action/path echo from responses. Useful in bulk or agentic loops where the caller already knows the context.

**Previously in v0.7.6:**
- Security hardening: cancel-on-timeout, cmd whitelist, env passthrough, `toRealPath`, join budget

**Previously in v0.7.5:**
- HTTP dual-transport (port 8081), `HttpMcpController`, `ServerLifecycleService` managing all 4 HTTP servers

**Previously in v0.7.4:**
- `UsageTracker` SQLite persistence, period stats via `tools stats options.period`

---

## Architecture

```
controller/
  McpController.groovy           @Component — thin JSON-RPC dispatcher, auto-discovers ToolHandlers
  HttpMcpController.groovy       @RestController — HTTP wrapper, delegates to McpController
service/
  ToolHandler.groovy             interface: getToolDefinitions(), canHandle(), handleToolCall()
  AbstractFileService.groovy     shared base: sanitize(), path validation, compact mode helpers
  FileLifecycleService.groovy    create, delete, copy, move, rename, touch
  FileListService.groovy         children, list, tree (relative paths), sizes
  FileSearchService.groovy       content, name, project search
  FileReadService.groovy         read, head, tail, range, grep, multi, info, summary, exists,
                                 diff, checksum, structure, get_method, chunk_read, finalise_read
  FileWriteService.groovy        write, append, replace, multi_replace, patch,
                                 chunk_write, finalise_write, abort_write
  ExecuteService.groovy          bash, powershell, groovy, cmd execution
  ToolsService.groovy            git, gradle, mvn, npm, project_scan, stats
  ServerLifecycleService.groovy  start/stop/status HTTP MCP server processes
  ChunkBufferService.groovy      chunked transfer session management
  SecurityService.groovy         script validation, bounded execution, resource monitoring
  UsageTracker.groovy            per-action call counts, SQLite persistence
  PathService.groovy             cross-platform path normalisation
```

---

## The 8 Tools

| Tool | Actions |
|------|---------|
| `file_lifecycle` | create, delete, copy, move, rename, touch |
| `file_list` | children, list, tree, sizes |
| `file_search` | content, name, project |
| `file_read` | read, head, tail, range, grep, multi, info, summary, exists, project_root, allowed_dirs, normalize, diff, checksum, structure, get_method, chunk_read, finalise_read |
| `file_write` | write, append, replace, multi_replace, patch, chunk_write, finalise_write, abort_write |
| `execute` | bash, powershell, groovy, cmd |
| `tools` | git, gradle, mvn, npm, project_scan, stats |
| `server_lifecycle` | start_eager, ensure, stop, status, reload |

---

## Token-Efficient Usage Patterns

### Compact mode
Skip the action/path echo on responses you don't need:
```
file_read   action=read  path=... options.compact=true   → {content, lines, file_content_hash}
file_list   action=children path=... options.compact=true → {count, entries:[{name,type,size}]}
file_write  action=write path=... options.compact=true   → {success, content_hash}
```

### Tree with relative paths
```
file_list action=tree path=C:/Users/willw/IdeaProjects/myproject options.maxDepth=3
→ rootPath: "C:/Users/willw/IdeaProjects/myproject"
  tree.path: "."
  tree.children[0].path: "src"
  tree.children[0].children[0].path: "src/main/groovy"
```

### Cheap existence / size check before reading
```
file_read action=summary path=...   → {lines, size}  (no content)
file_read action=exists  path=...   → {exists, type}
```

### Single method read (cheaper than structure + range)
```
file_read action=get_method path=MyService.groovy options.method=doRead
```

### Bulk parallel read
```
file_read action=multi options.paths=[path1, path2, path3]   (up to 10 in parallel)
```

---

## HTTP Server Lifecycle

`server_lifecycle` manages the other HTTP MCP servers. Config in `claude-sync/mcp-http-servers.json`:

```json
{
  "jarsDir": "C:/Users/willw/claude-sync/jars",
  "javaCmd": "C:/Program Files/Java/jdk-25/bin/java.exe",
  "servers": [
    { "name": "filesystem",       "jar": "mcp-groovy-filesystem-server-0.7.7.jar", "port": 8081, "startupPolicy": "eager" },
    { "name": "context",          "jar": "mcp-groovy-context-server-0.11.0.jar",   "port": 8082, "startupPolicy": "eager" },
    { "name": "orchestrator",     "jar": "mcp-llm-orchestrator-0.4.0.jar",         "port": 8083, "startupPolicy": "lazy"  },
    { "name": "agentic-workflow", "jar": "mcp-agentic-workflow-0.6.0.jar",         "port": 8084, "startupPolicy": "lazy"  }
  ]
}
```

Session pattern:
```
server_lifecycle action=start_eager          # bring up eager servers at session start
server_lifecycle action=ensure name=orchestrator   # on-demand lazy start
server_lifecycle action=stop                 # stop all at session end
server_lifecycle action=reload               # re-read config after deploying new jars
```

Servers already listening are skipped. PIDs tracked in `mcp-http-servers-runtime.json`. All managed servers stopped via `@PreDestroy` on JVM shutdown.

---

## Dual Transport Pattern

```
Claude Desktop (STDIO)              Local LLM agentic loop (HTTP)
        |                                      |
   stdio profile                         default profile
  web-type=none                         web-type=servlet
  port=0 (disabled)                     port=8081
        |                                      |
        +-----------> McpController <----------+
                       (@Component)
                            |
                     ToolHandler beans
                     (auto-discovered)
```

`McpController` is always `@Component`, never `@RestController`. `HttpMcpController` is the thin `@RestController` wrapper — only active when Tomcat is running. `web-application-type=none` in the stdio Spring profile ensures Tomcat never starts, making the HTTP endpoint unreachable in STDIO mode regardless.

---

## Claude Desktop Config

```json
{
  "mcpServers": {
    "groovy-filesystem": {
      "command": "C:/Program Files/Java/jdk-25/bin/java.exe",
      "args": [
        "--enable-native-access=ALL-UNNAMED",
        "-Dmcp.filesystem.allowed-directories=C:/Users/willw/IdeaProjects,C:/Users/willw/claude-sync",
        "-Dspring.profiles.active=stdio",
        "-Dmcp.mode=stdio",
        "-jar",
        "C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-0.7.7.jar"
      ]
    }
  }
}
```

---

## Build & Deploy

```bash
./gradlew clean bootJar
# Output: build/libs/mcp-groovy-filesystem-server-0.7.7.jar
# Deploy: copy to claude-sync/jars/, update mcp-http-servers.json jar name
# Restart Claude Desktop to pick up the new STDIO process
```

---

## Version History

| Version | Highlights |
|---------|-----------|
| 0.7.7 | Token optimisation: tighter descriptions, slim pathToMap, relative tree paths, compact response mode |
| 0.7.6 | Security hardening: cancel-on-timeout, cmd whitelist, env passthrough, toRealPath, join budget |
| 0.7.5 | HTTP dual-transport (port 8081), HttpMcpController, ServerLifecycleService |
| 0.7.4 | UsageTracker SQLite persistence, period stats |
| 0.7.3 | Atomic finalise_write, CommandWhitelistConfig wired, doTail ring buffer |
| 0.7.2 | doPatch hardened: overlap detection, atomic write, CRLF preservation |
| 0.7.1 | 7 consolidated tools, chunked I/O, Promise module, SecurityService |
