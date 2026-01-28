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
    
    @Override
    void run(String... args) {
        debugLog("Starting MCP stdio server...")
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
        int requestCount = 0
        
        try {
            while (true) {
                String line = reader.readLine()
                if (line == null) {
                    debugLog("EOF received, shutting down")
                    break
                }
                
                line = line.trim()
                if (line.isEmpty()) {
                    continue
                }
                
                requestCount++
                debugLog("Request ${requestCount}: ${line}")
                
                try {
                    McpRequest request = objectMapper.readValue(line, McpRequest.class)
                    McpResponse response = mcpController.handleRequest(request)
                    
                    // Check if this was a notification (no response expected)
                    if (response == null) {
                        debugLog("Notification processed, no response sent")
                        continue
                    }
                    
                    sendResponse(response, requestCount)
                    
                } catch (Exception e) {
                    debugLog("Error processing request: ${e.message}")
                    sendErrorResponse(requestCount, e)
                }
            }
        } catch (Exception e) {
            debugLog("Fatal error in stdio server: ${e.message}")
            e.printStackTrace(System.err)
        } finally {
            debugLog("Stdio server stopped")
        }
    }
    
    private void sendResponse(McpResponse response, int requestCount) {
        try {
            String json = objectMapper.writeValueAsString(response)
            System.out.println(json)
            System.out.flush()
            debugLog("Response ${requestCount} sent")
        } catch (Exception e) {
            debugLog("Error sending response: ${e.message}")
        }
    }
    
    private void sendErrorResponse(int requestCount, Exception e) {
        try {
            def errorResponse = [
                jsonrpc: "2.0",
                id: "error-${requestCount}" as String,
                error: [
                    code: -32603,
                    message: "Internal error: ${e.message}" as String
                ]
            ]
            String json = objectMapper.writeValueAsString(errorResponse)
            System.out.println(json)
            System.out.flush()
        } catch (Exception sendError) {
            debugLog("Error sending error response: ${sendError.message}")
        }
    }
    
    private static void debugLog(String message) {
        if (DEBUG) {
            String timestamp = java.time.LocalTime.now().toString()
            System.err.println("[${timestamp}] ${message}")
            System.err.flush()
        }
    }
}
