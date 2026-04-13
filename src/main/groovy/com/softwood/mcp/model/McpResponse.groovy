package com.softwood.mcp.model

import com.fasterxml.jackson.annotation.JsonInclude
import groovy.transform.CompileStatic

@CompileStatic
@JsonInclude(JsonInclude.Include.NON_NULL)
class McpResponse {
    String jsonrpc = "2.0"
    Object id
    Map<String, Object> result
    McpError error
    
    static McpResponse success(Object id, Map<String, Object> result) {
        new McpResponse(id: id, result: result)
    }
    
    static McpResponse error(Object id, int code, String message) {
        new McpResponse(
            id: id,
            error: new McpError(code: code, message: message as String)
        )
    }

    /**
     * Tool-level error: returns isError:true in content array.
     * This is what MCP tools/call requires — Claude Desktop renders content[0].text.
     * Use this everywhere a tool handler wants to surface an error to Claude.
     * Do NOT use McpResponse.error() from tool handlers — that produces a JSON-RPC
     * protocol-level error object that Claude Desktop silently swallows.
     */
    static McpResponse toolError(Object id, String message) {
        success(id, [
            content: [[type: 'text', text: message]],
            isError: true
        ] as Map<String, Object>)
    }
}

@CompileStatic
@JsonInclude(JsonInclude.Include.NON_NULL)
class McpError {
    int code
    String message
    Map<String, Object> data
}
