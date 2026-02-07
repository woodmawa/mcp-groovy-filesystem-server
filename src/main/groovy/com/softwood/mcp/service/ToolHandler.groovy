package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse

/**
 * Interface for MCP tool handlers - enables auto-discovery via Spring
 * Each implementing service registers its own tool definitions and handles its own tool calls
 */
interface ToolHandler {

    /**
     * Return tool definitions for tools/list response
     * Each entry is a Map with: name, description, inputSchema
     */
    List<Map<String, Object>> getToolDefinitions()

    /**
     * Check if this handler can handle the given tool name
     */
    boolean canHandle(String toolName)

    /**
     * Handle a tool call and return the result
     * @param toolName the tool being invoked
     * @param arguments the arguments map from the MCP request
     * @param requestId the MCP request ID for building the response
     * @return McpResponse with the result
     */
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId)
}
