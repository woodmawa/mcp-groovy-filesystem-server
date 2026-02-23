# mcp-groovy-filesystem-server v0.7.5

A Spring Boot MCP server providing filesystem and developer toolchain operations to Claude Desktop via STDIO, plus HTTP transport for local LLM agentic loops.

## What's New in v0.7.5

**HTTP dual-transport + Server lifecycle management:**

- `ServerLifecycleService` — new `server_lifecycle` tool that manages all HTTP MCP server processes. Reads config from `claude-sync/mcp-http-servers.json`, tracks PIDs, starts/stops servers on demand. Supports `start_eager` (eager servers at session begin), `ensure` (lazy on-demand start), `stop`, `status`, `reload`.
- HTTP endpoint on port 8081 (`spring-boot-starter-web` added). `HttpMcpController` is a thin `@RestController` wrapper delegating to the existing `@Component McpController`. STDIO safety guaranteed by `web-application-type=none` in the stdio Spring profile.
- Both STDIO (Claude Desktop) and HTTP (agentic loop) transports coexist. Claude Desktop launches via `stdio` profile; HTTP mode is the default for standalone use.

**Previously in v0.7.4:**
- `UsageTracker` SQLite persistence: daily flush to `best_practices.db`, survive restarts, period stats via `tools stats options.period`

**Previously in v0.7.3:**
- Atomic `finalise_write`, `CommandWhitelistConfig` wired, `git commit` message enforced, `.execute()` blocked, `doTail` ring buffer, version constant unified

## Architecture

```
controller/
  McpController.groovy           @Component - thin JSON-RPC dispatcher, auto-discovers ToolHandlers
  HttpMcpController.groovy       @RestController - HTTP wrapper, delegates to McpController
service/
  ToolHandler.groovy             interface: getToolDefinitions(), canHandle(), handleToolCall()
  AbstractFileService.groovy     shared base: sanitize(), path validation
  FileLifecycleService.groovy    create, delete, copy, move, rename, touch
  FileListService.groovy         children, list, tree, sizes
  FileSearchService.groovy       content, name, project search
  FileReadService.groovy         read, head, tail, range, grep, multi, info, exists, diff, checksum, chunked read
  FileWriteService.groovy        write, append, replace, multi_replace, patch, chunked write
  ExecuteService.groovy          bash, powershell, groovy, cmd execution
  ToolsService.groovy            git, gradle, mvn, npm, project_scan, stats
  ServerLifecycleService.groovy  NEW: start/stop/status HTTP MCP server processes
  ChunkBufferService.groovy      chunked transfer session management
  SecurityService.groovy         script validation, bounded execution, resource monitoring
  UsageTracker.groovy            per-action call counts, SQLite persistence
  PathService.groovy             cross-platform path normalisation
```

## The 8 Tools

| Tool | Actions |
|------|---------|
| `file_lifecycle` | create, delete, copy, move, rename, touch |
| `file_list` | children, list, tree, sizes |
| `file_search` | content, name, project |
| `file_read` | read, head, tail, range, grep, multi, info, summary, exists, project_root, allowed_dirs, normalize, diff, checksum, structure, chunk_read, finalise_read |
| `file_write` | write, append, replace, multi_replace, patch, chunk_write, finalise_write, abort_write |
| `execute` | bash, powershell, groovy, cmd |
| `tools` | git, gradle, mvn, npm, project_scan, stats |
| `server_lifecycle` | start_eager, ensure, stop, status, reload |

## HTTP Server Lifecycle

The `server_lifecycle` tool manages the other HTTP MCP servers. Config lives in `claude-sync/mcp-http-servers.json`:

```json
{
  "jarsDir": "C:/Users/willw/claude-sync/jars",
  "javaCmd": "C:/Program Files/Java/jdk-25/bin/java.exe",
  "servers": [
    { "name": "filesystem",  "jar": "mcp-groovy-filesystem-server-0.7.5.jar", "port": 8081, "startupPolicy": "eager" },
    { "name": "context",     "jar": "mcp-groovy-context-server-0.11.0.jar",   "port": 8082, "startupPolicy": "eager" },
    { "name": "orchestrator","jar": "mcp-llm-orchestrator-0.4.0.jar",         "port": 8083, "startupPolicy": "lazy" },
    { "name": "agentic-workflow","jar": "mcp-agentic-workflow-0.6.0.jar",     "port": 8084, "startupPolicy": "lazy" }
  ]
}
```

**Session pattern:**
```
# Session start - bring up eager servers
server_lifecycle action=start_eager

# On demand - bring up lazy server
server_lifecycle action=ensure name=orchestrator

# Session end - stop everything we started
server_lifecycle action=stop

# After deploying new jar versions
server_lifecycle action=reload
```

Servers already listening are skipped (no double-start). PIDs tracked in `mcp-http-servers-runtime.json`. All managed servers stopped via `@PreDestroy` on JVM shutdown.

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

Key rule: `McpController` is always `@Component`, never `@RestController`. `HttpMcpController` is the thin `@RestController` wrapper — only registered when Tomcat is active. `web-application-type=none` in the stdio profile is the gate — Tomcat never starts so the HTTP endpoint is unreachable regardless.

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
        "C:/Users/willw/claude-sync/jars/mcp-groovy-filesystem-server-0.7.5.jar"
      ]
    }
  }
}
```

## Build

```bash
./gradlew bootJar
# Output: build/libs/mcp-groovy-filesystem-server-0.7.5.jar
# Deploy: copy to claude-sync/jars/, update mcp-http-servers.json version
```

## Version History

| Version | Highlights |
|---------|-----------|
| 0.7.5 | HTTP dual-transport (port 8081), HttpMcpController, ServerLifecycleService managing all 4 HTTP servers via mcp-http-servers.json |
| 0.7.4 | UsageTracker SQLite persistence, period stats |
| 0.7.3 | Atomic finalise_write, CommandWhitelistConfig wired, doTail ring buffer |
| 0.7.2 | doPatch hardened: overlap detection, atomic write, CRLF preservation |
| 0.7.1 | 7 consolidated tools, chunked I/O, Promise module, SecurityService |
