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
}

@CompileStatic
@JsonInclude(JsonInclude.Include.NON_NULL)
class McpError {
    int code
    String message
    Map<String, Object> data
}
