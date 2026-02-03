package com.softwood.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.softwood.mcp.controller.McpController
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = "mcp.mode", havingValue = "stdio")
@Slf4j
@CompileStatic
class StdioMcpServer implements CommandLineRunner {
    
    private static final boolean DEBUG = System.getenv("MCP_DEBUG") != null
    
    private final McpController mcpController
    private final ObjectMapper objectMapper
    
    StdioMcpServer(McpController mcpController) {
        this.mcpController = mcpController
        this.objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }
    
    /**
     * Sanitize string by removing control characters (except newlines and tabs)
     * CRITICAL: Prevents JSON serialization errors in exception messages
     */
    private static String sanitize(String text) {
        if (!text) return text
        try {
            // Remove control characters except \n (10) and \t (9)
            String cleaned = text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F-\x9F]/, '')
            
            // Additional safety: replace any remaining non-printable characters
            cleaned = cleaned.replaceAll(/[^\p{Print}\p{Space}]/, '')
            
            return cleaned
        } catch (Exception e) {
            return "[sanitization error]"
        }
    }
    
    /**
     * Sanitize object recursively for safe JSON serialization
     */
    private static Object sanitizeObject(Object obj) {
        try {
            if (obj == null) {
                return null
            } else if (obj instanceof String) {
                return sanitize((String) obj)
            } else if (obj instanceof Map) {
                Map result = [:]
                ((Map) obj).each { k, v ->
                    result[sanitizeObject(k)] = sanitizeObject(v)
                }
                return result
            } else if (obj instanceof List) {
                return ((List) obj).collect { sanitizeObject(it) }
            } else {
                return obj
            }
        } catch (Exception e) {
            return "[object sanitization error]"
        }
    }
    
    @Override
    void run(String... args) {
        debugLog("Starting MCP stdio server...")
        debugLog("Debug mode: ${DEBUG}")
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
        int requestCount = 0
        
        try {
            while (true) {
                String line = null
                try {
                    line = reader.readLine()
                } catch (IOException e) {
                    debugLog("Error reading input: ${sanitize(e.message)}")
                    break
                }
                
                if (line == null) {
                    debugLog("EOF received, shutting down")
                    break
                }
                
                line = line.trim()
                if (line.isEmpty()) {
                    continue
                }
                
                requestCount++
                debugLog("Request ${requestCount}: ${line.take(200)}${line.length() > 200 ? '...' : ''}")
                
                // Parse request with enhanced error handling
                McpRequest request = null
                String requestId = "unknown-${requestCount}"
                
                try {
                    request = objectMapper.readValue(line, McpRequest.class)
                    requestId = request?.id ?: requestId
                    debugLog("Parsed request ID: ${requestId}")
                } catch (Exception parseError) {
                    debugLog("JSON parse error: ${sanitize(parseError.message)}")
                    sendJsonRpcError(requestId, -32700, "Parse error: ${sanitize(parseError.message)}")
                    continue
                }
                
                // Process request with enhanced error handling
                try {
                    McpResponse response = mcpController.handleRequest(request)
                    
                    // Check if this was a notification (no response expected)
                    if (response == null) {
                        debugLog("Notification processed, no response sent")
                        continue
                    }
                    
                    sendResponse(response, requestCount)
                    
                } catch (SecurityException e) {
                    debugLog("Security error: ${sanitize(e.message)}")
                    sendJsonRpcError(requestId, -32001, "Security error: ${sanitize(e.message)}")
                } catch (FileNotFoundException e) {
                    debugLog("File not found: ${sanitize(e.message)}")
                    sendJsonRpcError(requestId, -32002, "File not found: ${sanitize(e.message)}")
                } catch (IllegalArgumentException e) {
                    debugLog("Invalid argument: ${sanitize(e.message)}")
                    sendJsonRpcError(requestId, -32602, "Invalid params: ${sanitize(e.message)}")
                } catch (Throwable t) {
                    debugLog("Unexpected error: ${t.class.simpleName}: ${sanitize(t.message)}")
                    sendJsonRpcError(requestId, -32603, "${t.class.simpleName}: ${sanitize(t.message ?: 'Unknown error')}")
                }
            }
        } catch (Throwable t) {
            debugLog("Fatal error in stdio server: ${t.class.simpleName}: ${sanitize(t.message)}")
            t.printStackTrace(System.err)
        } finally {
            debugLog("Stdio server stopped")
        }
    }
    
    /**
     * Send a successful response with robust error handling
     */
    private void sendResponse(McpResponse response, int requestCount) {
        try {
            // Sanitize the entire response before serialization
            def sanitizedResponse = sanitizeObject(response)
            
            String json = null
            try {
                json = objectMapper.writeValueAsString(sanitizedResponse)
            } catch (Exception jsonError) {
                debugLog("JSON serialization error: ${sanitize(jsonError.message)}")
                // Try to send a minimal error response instead
                sendJsonRpcError(
                    response?.id as String ?: "error",
                    -32603,
                    "Response serialization failed: ${sanitize(jsonError.message)}"
                )
                return
            }
            
            // Validate JSON doesn't contain control characters
            if (json.find(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/) != null) {
                debugLog("WARNING: Response contains control characters, attempting to clean")
                json = sanitize(json)
            }
            
            System.out.println(json)
            System.out.flush()
            debugLog("Response ${requestCount} sent successfully (${json.length()} bytes)")
            
        } catch (Throwable t) {
            debugLog("Critical error sending response: ${t.class.simpleName}")
            // Last resort - try to send minimal error
            try {
                System.out.println('{"jsonrpc":"2.0","id":"error","error":{"code":-32603,"message":"Response transmission failed"}}')
                System.out.flush()
            } catch (Exception e2) {
                debugLog("Failed to send last resort error")
            }
        }
    }
    
    /**
     * Send a JSON-RPC error response with maximum robustness
     */
    private void sendJsonRpcError(String requestId, int code, String message) {
        try {
            // Ensure all fields are sanitized
            String safeId = sanitize(requestId ?: "unknown")
            String safeMessage = sanitize(message ?: "Unknown error")
            
            // Build minimal error response
            def errorResponse = [
                jsonrpc: "2.0",
                id: safeId,
                error: [
                    code: code,
                    message: safeMessage
                ]
            ]
            
            String json = null
            try {
                json = objectMapper.writeValueAsString(errorResponse)
            } catch (Exception jsonError) {
                // If even this fails, send hardcoded minimal JSON
                debugLog("Error response serialization failed: ${sanitize(jsonError.message)}")
                json = """{"jsonrpc":"2.0","id":"${safeId.replaceAll('"', '\\\\"')}","error":{"code":${code},"message":"Error serialization failed"}}"""
            }
            
            // Final sanitization check
            json = sanitize(json)
            
            System.out.println(json)
            System.out.flush()
            debugLog("Error response sent: code=${code}, message=${safeMessage.take(100)}")
            
        } catch (Throwable t) {
            debugLog("CRITICAL: Failed to send error response: ${t.class.simpleName}")
            // Absolute last resort - hardcoded minimal error
            try {
                System.out.println('{"jsonrpc":"2.0","id":"error","error":{"code":-32603,"message":"Critical error"}}')
                System.out.flush()
            } catch (Exception e2) {
                // If even this fails, we can't do anything more
                debugLog("CRITICAL: All error response attempts failed")
            }
        }
    }
    
    private static void debugLog(String message) {
        if (DEBUG) {
            try {
                String timestamp = java.time.LocalTime.now().toString()
                System.err.println("[${timestamp}] MCP: ${message}")
                System.err.flush()
            } catch (Exception e) {
                // If even logging fails, silently ignore
            }
        }
    }
}
