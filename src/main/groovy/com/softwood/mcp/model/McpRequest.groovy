package com.softwood.mcp.model

import com.fasterxml.jackson.annotation.JsonInclude
import groovy.transform.CompileStatic

@CompileStatic
@JsonInclude(JsonInclude.Include.NON_NULL)
class McpRequest {
    String jsonrpc = "2.0"
    Object id  // Can be String, Number, or null (for notifications)
    String method
    Map<String, Object> params = [:]
}
