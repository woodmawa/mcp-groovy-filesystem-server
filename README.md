# mcp-groovy-filesystem-server v0.8.5

A Spring Boot MCP server providing filesystem and developer toolchain operations to Claude Desktop and Claude Code via Streamable HTTP and legacy SSE. Also supports STDIO transport for compatibility.

Eight parameterised tools replace what would otherwise be 30+ individual tools, keeping the MCP schema compact and token-efficient.

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