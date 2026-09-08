package com.softwood.mcp.support

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Startup log handling for this server.
 *
 * WP-0 (0.9.21): this class no longer clears anything. It used to truncate, on every
 * stdio start:
 *
 *   %APPDATA%/Roaming/Claude/logs/mcp-server-groovy-filesystem.log
 *   %APPDATA%/Roaming/Claude/logs/mcp.log            (when larger than 1MB)
 *
 * Both of those are Claude Desktop's OWN files. They are the client's half of the MCP
 * conversation and the only place a client-side timeout, disconnect or re-spawn is
 * recorded. Truncating them at the exact second the server starts is why three separate
 * investigations of the intermittent tool-list drop (2026-09-06..08) had to be built out
 * of server-side logs alone: every one of them read a file we had just emptied. See
 * BUILD-BRIEF-2026-09-08-the-four-that-were-spawned, WP-0.
 *
 * mcp.log is shared by every MCP server on this machine, so a single server clearing it
 * destroys the evidence for all of them.
 *
 * Our own logs are capped where they are written (claude-sync/logs: 2 days / 20MB /
 * 100MB total) and need no help from here. If a future need to clear something arises,
 * it must be a path we own -- never one under Claude Desktop's directory.
 *
 * The method and its caller are kept so the startup path is unchanged and the decision
 * stays visible at the point where it used to happen.
 *
 * v0.0.5: Extracted from StdioMcpServer.
 * v0.9.21: WP-0 -- clearing removed; Claude Desktop's logs are left intact.
 */
@Slf4j
@CompileStatic
class LogCleaner {

    /**
     * Deliberately clears nothing. Returns 0 files cleared, always.
     *
     * @return 0
     */
    static int clearLogsOnStartup() {
        log.debug("LogCleaner: clearing disabled (WP-0) - Claude Desktop's mcp-server-*.log and mcp.log are left intact")
        return 0
    }
}
