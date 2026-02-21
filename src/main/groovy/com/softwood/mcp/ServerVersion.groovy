package com.softwood.mcp

/**
 * Single source of truth for the server version.
 * Referenced from both McpController and ToolsService to avoid circular imports.
 */
class ServerVersion {
    static final String VERSION = '0.7.3'
}
