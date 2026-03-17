# mcp-groovy-filesystem-server v0.8.8

A Spring Boot MCP server providing filesystem and developer toolchain operations to Claude Desktop and Claude Code via Streamable HTTP and legacy SSE. Also supports STDIO transport for compatibility.

Eight parameterised tools replace what would otherwise be 30+ individual tools, keeping the MCP schema compact and token-efficient.

---

## What's New in v0.8.8

**mkdirs boolean cast fix under `@CompileStatic` (v0.8.8)**

Fixed a `@CompileStatic` boolean cast issue in `WriteUtils` where `mkdirs` option was not being parsed correctly, causing silent failures when creating parent directories. Used `Boolean.valueOf(toString())` instead of `as boolean`.

**`atomicWrite` race condition fix (v0.8.7)**

Fixed a race condition in `WriteUtils.atomicWrite` where a redundant `!Files.exists()` guard raced with the subsequent `createDirectories()` call, causing intermittent failures on concurrent writes to new directories.

**`BufferedReader` 8KB line limit fix (v0.8.6)**

Fixed `StdioMcpServer` `BufferedReader` hard limit of 8KB per line — large MCP messages were silently truncated. Replaced with 1MB buffer.

---

## What's New in v0.8.5

**Streamable HTTP transport — `HttpMcpController` (v0.8.5)**

Adds the MCP spec 2025-03-26 Streamable HTTP transport alongside the existing legacy SSE transport:

- **`POST /mcp`** — primary JSON-RPC channel. `initialize` request issues an `Mcp-Session-Id` response header; all subsequent requests must include this header or receive `400 Bad Request`.
- **`GET /mcp`** — SSE stream on the unified `/mcp` endpoint for spec compliance and future server-push support. Claude Desktop and Claude Code use POST-only; GET is provided for forward compatibility.
- **Origin validation** — non-localhost `Origin` headers rejected with `403` to prevent DNS-rebinding attacks. Null origin (Claude Desktop/CC behaviour) is always permitted.
- **Heartbeat** — 30-second ping comments keep SSE connections alive through proxies.
- **Legacy `McpSseController` retained** — `GET /sse` + `POST /message` kept for backward compatibility with Claude Desktop v1.1.x (bug #31864). Do not remove until Streamable HTTP is confirmed end-to-end.

Regression suite `HTTP-FS-*` tests now pass for all three ports (8081/8082/8083/8084).

---

## What's New in v0.8.4