package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FilesystemTelemetryService
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    FilesystemTelemetryService telemetryService

    // FIX-C: v0.7.43 global backstop - hard ceiling on any single tool response
    @org.springframework.beans.factory.annotation.Value('${mcp.filesystem.global-response-cap-chars:64000}')
    int globalResponseCapChars


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
            return McpResponse.protocolError(request?.id, -32603,
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
                return McpResponse.protocolError(request.id, -32601, "Unknown method: ${request.method}" as String)
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
            return McpResponse.toolError(request.id, "tools/call missing required param: name")
        }

        Map<String, Object> arguments = (request.params?.arguments as Map<String, Object>) ?: [:] as Map<String, Object>

        ToolHandler handler = handlerMap[toolName]
        if (!handler) {
            log.warn("Unknown tool called: {}", toolName)
            return McpResponse.toolError(request.id,
                "Unknown tool: '${toolName}'. Available: ${handlerMap.keySet().join(', ')}" as String)
        }

        log.debug('Dispatching tool: {}', toolName)
        McpResponse response = handler.handleToolCall(toolName, arguments, request.id)

        // v0.7.19: telemetry - fire-and-forget, never blocks response
        int charCount = 0
        try {
            if (telemetryService != null) {
                charCount = estimateResponseSize(response)
                String action   = arguments.action as String
                String rawPath  = arguments.path as String
                String pathHash = rawPath ? sha256Prefix(rawPath) : null
                String outcome  = extractOutcome(response)
                telemetryService.recordToolCall('unknown', toolName, charCount, arguments,
                    action, pathHash, outcome)
            }
        } catch (Exception e) {
            log.debug('Telemetry hook failed (non-fatal): {}', e.message)
        }

        // FIX-C: v0.7.43 global response backstop - no response may exceed cap regardless of handler
        if (globalResponseCapChars > 0 && charCount > globalResponseCapChars) {
            int tokenEst = Math.round(charCount / 4.0f) as int
            log.warn('BACKSTOP triggered: {} response {}chars (~{}tok) exceeds global cap {}chars',
                toolName, charCount, tokenEst, globalResponseCapChars)
            return McpResponse.toolError(request.id,
                "Response too large: ${toolName} produced ${charCount} chars (~${tokenEst} tokens). " +
                "Use targeted actions: structure/get_method/range/grep instead of full reads.")
        }

        return response
    }

    // FIX-7: zero-copy size estimate - read text field directly instead of serialising whole result Map
    private static String sha256Prefix(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance('SHA-256').digest(input.getBytes('UTF-8'))
            StringBuilder sb = new StringBuilder(12)
            for (int i = 0; i < 6; i++) sb.append(String.format('%02x', hash[i]))
            return sb.toString()
        } catch (Exception ignored) { return null }
    }

    private static String extractOutcome(McpResponse response) {
        if (response?.error != null) {
            String msg = response.error.message ?: ''
            if (msg.toLowerCase().contains('refus')) return 'refused'
            return 'error'
        }
        try {
            List content = response?.result?.content as List
            String text = ((content?.first() as Map)?.get('text') as String) ?: ''
            if (text.contains('"unchanged":true')) return 'unchanged'
            if (text.contains('_truncated')) return 'truncated'
        } catch (Exception ignored) {}
        return 'success'
    }

    private static int estimateResponseSize(McpResponse response) {
        try {
            List content = response?.result?.content as List
            return ((content?.first() as Map)?.get('text') as String)?.length() ?: 0
        } catch (Exception ignored) {
            return 0
        }
    }
}

