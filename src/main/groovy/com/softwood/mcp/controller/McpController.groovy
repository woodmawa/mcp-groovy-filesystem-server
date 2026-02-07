package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.GroovyScriptService
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * MCP Controller - thin dispatcher using auto-discovered ToolHandler beans
 * Tool definitions and handling logic live in the service classes, not here.
 * 
 * REFACTORED: Replaced 400-line switch statement with ToolHandler auto-discovery.
 * Adding a new tool now only requires adding it to the relevant service class.
 */
@RestController
@Slf4j
@CompileStatic
class McpController {

    private final List<ToolHandler> toolHandlers
    private final GroovyScriptService groovyScriptService

    // Lookup map built once at startup: toolName → handler
    private Map<String, ToolHandler> handlerMap = [:]

    McpController(List<ToolHandler> toolHandlers, GroovyScriptService groovyScriptService) {
        this.toolHandlers = toolHandlers
        this.groovyScriptService = groovyScriptService
        buildHandlerMap()
    }

    /**
     * Build the toolName → handler lookup map from all registered ToolHandler beans
     */
    private void buildHandlerMap() {
        toolHandlers.each { ToolHandler handler ->
            handler.getToolDefinitions().each { Map<String, Object> toolDef ->
                String name = toolDef.name as String
                if (handlerMap.containsKey(name)) {
                    log.warn("Duplicate tool name '${name}' - overwriting previous handler")
                }
                handlerMap[name] = handler
                log.debug("Registered tool: ${name} → ${handler.class.simpleName}")
            }
        }
        log.info("Registered ${handlerMap.size()} tools from ${toolHandlers.size()} handlers")
    }

    /**
     * Sanitize string by removing control characters
     */
    private static String sanitize(String text) {
        if (!text) return text
        return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
    }

    @PostMapping("/")
    McpResponse handleRequest(@RequestBody McpRequest request) {
        try {
            return dispatch(request)
        } catch (Exception e) {
            log.error("Error handling request", e)
            return McpResponse.error(request.id, -32603, sanitize("Internal error: ${e.message}") as String)
        }
    }

    private McpResponse dispatch(McpRequest request) {
        if (request.id == null) {
            log.debug("Received notification: {}", request.method)
            return null
        }

        switch (request.method) {
            case "initialize":
                return handleInitialize(request)

            case "tools/list":
                return handleToolsList(request)

            case "tools/call":
                return handleToolsCall(request)

            default:
                return McpResponse.error(request.id, -32601, "Unknown method: ${request.method}" as String)
        }
    }

    private McpResponse handleInitialize(McpRequest request) {
        def clientVersion = request.params.protocolVersion
        return McpResponse.success(request.id, [
                protocolVersion: clientVersion ?: "2024-11-05",
                capabilities: [tools: [:]],
                serverInfo: [name: "mcp-groovy-filesystem-server", version: "1.1.0"]
        ])
    }

    /**
     * Collect tool definitions from ALL registered ToolHandler beans
     * Plus the executeGroovyScript tool (handled separately)
     */
    private McpResponse handleToolsList(McpRequest request) {
        List<Map<String, Object>> allTools = []

        // Collect from all ToolHandler beans
        toolHandlers.each { ToolHandler handler ->
            allTools.addAll(handler.getToolDefinitions())
        }

        // Add executeGroovyScript (handled directly, not via ToolHandler)
        allTools.add([
            name: "executeGroovyScript",
            description: "Execute a Groovy script with secure DSL for PowerShell, Bash, Git, and Gradle commands",
            inputSchema: [
                type: "object",
                properties: [
                    script: [type: "string", description: "Groovy script to execute"],
                    workingDirectory: [type: "string", description: "Working directory for script execution"]
                ],
                required: ["script", "workingDirectory"]
            ]
        ] as Map<String, Object>)

        return McpResponse.success(request.id, [tools: allTools] as Map<String, Object>)
    }

    /**
     * Dispatch tool calls to the appropriate ToolHandler
     */
    private McpResponse handleToolsCall(McpRequest request) {
        String toolName = request.params.name as String
        Map<String, Object> arguments = request.params.arguments as Map<String, Object> ?: [:]

        // Special case: executeGroovyScript (needs its own service, not a ToolHandler)
        if (toolName == 'executeGroovyScript') {
            String script = arguments.script as String
            String workingDirectory = arguments.workingDirectory as String
            def result = groovyScriptService.executeScript(script, workingDirectory)
            return McpResponse.success(request.id, [
                content: [[type: "text", text: JsonOutput.toJson(result)]]
            ] as Map<String, Object>)
        }

        // Lookup handler from map
        ToolHandler handler = handlerMap[toolName]
        if (!handler) {
            return McpResponse.error(request.id, -32601, "Unknown tool: ${toolName}" as String)
        }

        return handler.handleToolCall(toolName, arguments, request.id)
    }
}
