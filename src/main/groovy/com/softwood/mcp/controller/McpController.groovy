package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.GroovyScriptService
import com.softwood.mcp.service.ToolHandler
import com.softwood.mcp.support.Sanitizer
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * MCP Controller - thin dispatcher using auto-discovered ToolHandler beans.
 * Tool definitions and handling logic live in the service classes, not here.
 *
 * v0.0.5: Uses shared Sanitizer from support package.
 */
@RestController
@Slf4j
@CompileStatic
class McpController {

    private final List<ToolHandler> toolHandlers
    private final GroovyScriptService groovyScriptService
    private final Map<String, ToolHandler> handlerMap = [:]

    McpController(List<ToolHandler> toolHandlers, GroovyScriptService groovyScriptService) {
        this.toolHandlers = toolHandlers
        this.groovyScriptService = groovyScriptService
        buildHandlerMap()
    }

    private void buildHandlerMap() {
        toolHandlers.each { ToolHandler handler ->
            handler.getToolDefinitions().each { Map<String, Object> toolDef ->
                String name = toolDef.name as String
                if (handlerMap.containsKey(name)) {
                    log.warn("Duplicate tool name '{}' - overwriting previous handler", name)
                }
                handlerMap[name] = handler
                log.debug("Registered tool: {} → {}", name, handler.class.simpleName)
            }
        }
        log.info("Registered {} tools from {} handlers", handlerMap.size(), toolHandlers.size())
    }

    @PostMapping("/")
    McpResponse handleRequest(@RequestBody McpRequest request) {
        try {
            return dispatch(request)
        } catch (Exception e) {
            log.error("Error handling request", e)
            return McpResponse.error(request.id, -32603, Sanitizer.sanitize("Internal error: ${e.message}") as String)
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
                serverInfo: [name: "mcp-groovy-filesystem-server", version: "0.0.5"]
        ])
    }

    private McpResponse handleToolsList(McpRequest request) {
        List<Map<String, Object>> allTools = []
        toolHandlers.each { ToolHandler handler ->
            allTools.addAll(handler.getToolDefinitions())
        }

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

    private McpResponse handleToolsCall(McpRequest request) {
        String toolName = request.params.name as String
        Map<String, Object> arguments = request.params.arguments as Map<String, Object> ?: [:]

        if (toolName == 'executeGroovyScript') {
            String script = arguments.script as String
            String workingDirectory = arguments.workingDirectory as String
            def result = groovyScriptService.executeScript(script, workingDirectory)
            return McpResponse.success(request.id, [
                content: [[type: "text", text: JsonOutput.toJson(result)]]
            ] as Map<String, Object>)
        }

        ToolHandler handler = handlerMap[toolName]
        if (!handler) {
            return McpResponse.error(request.id, -32601, "Unknown tool: ${toolName}" as String)
        }

        return handler.handleToolCall(toolName, arguments, request.id)
    }
}
