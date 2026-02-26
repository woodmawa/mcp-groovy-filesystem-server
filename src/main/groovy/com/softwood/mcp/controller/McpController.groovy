package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ToolHandler
import com.softwood.mcp.support.Sanitizer
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * McpController — thin dispatcher for all MCP JSON-RPC requests.
 *
 * Auto-discovers all ToolHandler beans via Spring injection.
 * No business logic here — all tool behaviour lives in the service layer.
 *
 * v0.0.7: 7 consolidated tools, no GroovyScriptService dependency.
 */
@RestController
@Slf4j
@CompileStatic
class McpController {

    static final String SERVER_VERSION = com.softwood.mcp.McpGroovyFileSystemServerApplication.package?.implementationVersion ?: 'dev'

    // Supported MCP protocol versions (newest first).
    // 2025-11-25: added in Claude Desktop post-Nov-2025 reinstall - includes ui extension capability.
    // 2025-06-18: previous stable version.
    // 2024-11-05: legacy fallback.
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS = [
        '2025-11-25',
        '2025-06-18',
        '2024-11-05'
    ].asImmutable()
    private final List<ToolHandler> toolHandlers
    private final Map<String, ToolHandler> handlerMap = new LinkedHashMap<String, ToolHandler>()
    private List<Map<String, Object>> cachedToolDefinitions = []

    McpController(List<ToolHandler> toolHandlers) {
        this.toolHandlers = toolHandlers
        buildHandlerMap()
    }

    private void buildHandlerMap() {
        List<Map<String, Object>> allDefs = []
        toolHandlers.each { ToolHandler handler ->
            handler.getToolDefinitions().each { Map<String, Object> toolDef ->
                String name = toolDef.name as String
                if (handlerMap.containsKey(name)) {
                    log.warn("Duplicate tool name '{}'  overwriting with {}", name, handler.class.simpleName)
                }
                handlerMap[name] = handler
                allDefs << toolDef
                log.debug("Registered tool: {}  {}", name, handler.class.simpleName)
            }
        }
        cachedToolDefinitions = allDefs.asImmutable()
        log.info("v{} ready  {} tools registered from {} handlers: {}",
            SERVER_VERSION, handlerMap.size(), toolHandlers.size(), handlerMap.keySet().join(', '))
    }
    @PostMapping('/')
    McpResponse handleRequest(@RequestBody McpRequest request) {
        try {
            return dispatch(request)
        } catch (Exception e) {
            log.error("Unhandled error in handleRequest", e)
            return McpResponse.error(request?.id, -32603,
                Sanitizer.sanitize("Internal error: ${e.message}") as String)
        }
    }

    private McpResponse dispatch(McpRequest request) {
        // Notifications (no id) — acknowledge silently
        if (request.id == null) {
            log.debug("Notification received: {}", request.method)
            return null
        }

        switch (request.method) {
            case 'initialize'  : return handleInitialize(request)
            case 'tools/list'  : return handleToolsList(request)
            case 'tools/call'  : return handleToolsCall(request)
            case 'ping'        : return McpResponse.success(request.id, [:] as Map<String, Object>)
            default:
                log.warn("Unknown MCP method: {}", request.method)
                return McpResponse.error(request.id, -32601, "Unknown method: ${request.method}" as String)
        }
    }

    // -----------------------------------------------------------------------
    // MCP protocol handlers
    // -----------------------------------------------------------------------

    private McpResponse handleInitialize(McpRequest request) {
        String clientVersion = request.params?.protocolVersion as String
        String negotiated = negotiateProtocolVersion(clientVersion)

        // Log client identity if provided (Claude Desktop sends clientInfo from 2025-11-25 onwards)
        def clientInfo = request.params?.clientInfo
        if (clientInfo instanceof Map) {
            log.info("MCP Initialize: client='{}' v='{}', requested='{}', negotiated='{}'",
                clientInfo.name, clientInfo.version, clientVersion, negotiated)
        } else {
            log.info("MCP Initialize: client requested '{}', negotiated '{}'", clientVersion, negotiated)
        }

        return McpResponse.success(request.id, [
            protocolVersion: negotiated,
            capabilities   : [tools: [:]] as Map<String, Object>,
            serverInfo     : [name: 'mcp-groovy-filesystem-server', version: SERVER_VERSION] as Map<String, Object>
        ] as Map<String, Object>)
    }

    private String negotiateProtocolVersion(String clientVersion) {
        if (clientVersion && SUPPORTED_PROTOCOL_VERSIONS.contains(clientVersion)) {
            return clientVersion
        }
        return SUPPORTED_PROTOCOL_VERSIONS.first()
    }

    private McpResponse handleToolsList(McpRequest request) {
        // Use cached definitions built at startup - no rebuild on every call
        log.debug("tools/list returning {} tools (cached)", cachedToolDefinitions.size())
        return McpResponse.success(request.id, [tools: cachedToolDefinitions] as Map<String, Object>)
    }

    private McpResponse handleToolsCall(McpRequest request) {
        String toolName = request.params?.name as String
        if (!toolName) {
            return McpResponse.error(request.id, -32602, "tools/call missing required param: name")
        }

        Map<String, Object> arguments = (request.params?.arguments as Map<String, Object>) ?: [:] as Map<String, Object>

        ToolHandler handler = handlerMap[toolName]
        if (!handler) {
            log.warn("Unknown tool called: {}", toolName)
            return McpResponse.error(request.id, -32601,
                "Unknown tool: '${toolName}'. Available: ${handlerMap.keySet().join(', ')}" as String)
        }

        log.debug("Dispatching tool: {}", toolName)
        return handler.handleToolCall(toolName, arguments, request.id)
    }
}
