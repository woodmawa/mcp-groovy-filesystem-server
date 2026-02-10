package com.softwood.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.softwood.mcp.controller.McpController
import com.softwood.mcp.event.McpRequestEvent
import com.softwood.mcp.event.McpRequestEvent.Stage
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.support.JsonRpcWriter
import com.softwood.mcp.support.LogCleaner
import com.softwood.mcp.support.Sanitizer
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * STDIO MCP Server - reads JSON-RPC from stdin, writes responses to stdout.
 *
 * Responsibilities (this class only handles the read loop + event publishing):
 *   - Sanitization         → Sanitizer
 *   - JSON-RPC output      → JsonRpcWriter
 *   - Log cleanup          → LogCleaner
 *   - Lifecycle events     → McpRequestEvent / McpDiagnosticsListener
 *
 * v0.0.5: Refactored from 357 lines to ~150. Support classes extracted.
 *         Added request lifecycle events for stall diagnosis.
 */
@Component
@ConditionalOnProperty(name = "mcp.mode", havingValue = "stdio")
@Slf4j
@CompileStatic
class StdioMcpServer implements CommandLineRunner {

    private static final boolean DEBUG = System.getenv("MCP_DEBUG") != null

    private final McpController mcpController
    private final ApplicationEventPublisher eventPublisher
    private final ObjectMapper objectMapper = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
    private final JsonRpcWriter writer = new JsonRpcWriter()

    StdioMcpServer(McpController mcpController, ApplicationEventPublisher eventPublisher) {
        this.mcpController = mcpController
        this.eventPublisher = eventPublisher
    }

    @Override
    void run(String... args) {
        LogCleaner.clearLogsOnStartup()

        debugLog("Starting MCP stdio server v0.0.5...")

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
        int requestCount = 0

        // Log when server is ready for input
        log.info("STDIO server ready, waiting for input...")

        try {
            while (true) {
                String line
                try {
                    // Check for available input with a polling approach for diagnostics
                    if (!reader.ready()) {
                        log.trace("Waiting for input...")
                    }
                    line = reader.readLine()
                } catch (IOException e) {
                    debugLog("Error reading input: ${Sanitizer.sanitize(e.message)}")
                    break
                }

                if (line == null) {
                    debugLog("EOF received, shutting down")
                    break
                }

                line = line.trim()
                if (line.isEmpty()) continue

                requestCount++
                long startNanos = System.nanoTime()
                int payloadSize = line.length()

                debugLog("Request ${requestCount} (${payloadSize}B): ${line.take(200)}${payloadSize > 200 ? '...' : ''}")
                publishEvent(Stage.RECEIVED, null, null, null, requestCount, startNanos, payloadSize)

                // --- Parse ---
                McpRequest request
                Object requestId = "unknown-${requestCount}" as String
                String method = null
                String toolName = null

                try {
                    request = objectMapper.readValue(line, McpRequest.class)
                    requestId = request.id ?: requestId
                    method = request.method
                    toolName = request.params?.name as String
                    publishEvent(Stage.PARSED, requestId, method, toolName, requestCount, startNanos, payloadSize)
                } catch (Exception e) {
                    debugLog("Parse error: ${Sanitizer.sanitize(e.message)}")
                    publishEvent(Stage.ERROR, requestId, null, null, requestCount, startNanos, payloadSize, 0, Sanitizer.sanitize(e.message))
                    writer.sendError(requestId as String, -32700, "Parse error: ${Sanitizer.sanitize(e.message)}")
                    continue
                }

                // --- Dispatch ---
                try {
                    publishEvent(Stage.DISPATCHED, requestId, method, toolName, requestCount, startNanos, payloadSize)

                    McpResponse response = mcpController.handleRequest(request)

                    if (response == null) {
                        debugLog("Notification, no response")
                        continue
                    }

                    publishEvent(Stage.COMPLETED, requestId, method, toolName, requestCount, startNanos, payloadSize)

                    int responseSize = writer.sendResponse(response)
                    long elapsedMs = (long)((System.nanoTime() - startNanos) / 1_000_000L)

                    publishEvent(Stage.SENT, requestId, method, toolName, requestCount, startNanos, payloadSize, responseSize)
                    debugLog("Request ${requestCount} done in ${elapsedMs}ms (in=${payloadSize}B out=${responseSize}B)")

                } catch (SecurityException e) {
                    handleError(e, requestId, method, toolName, requestCount, startNanos, payloadSize, -32001, "Security error")
                } catch (FileNotFoundException e) {
                    handleError(e, requestId, method, toolName, requestCount, startNanos, payloadSize, -32002, "File not found")
                } catch (IllegalArgumentException e) {
                    handleError(e, requestId, method, toolName, requestCount, startNanos, payloadSize, -32602, "Invalid params")
                } catch (Throwable t) {
                    handleError(t, requestId, method, toolName, requestCount, startNanos, payloadSize, -32603, t.class.simpleName)
                }
            }
        } catch (Throwable t) {
            debugLog("Fatal: ${t.class.simpleName}: ${Sanitizer.sanitize(t.message)}")
            t.printStackTrace(System.err)
        } finally {
            debugLog("Stdio server stopped after ${requestCount} requests")
        }
    }

    private void handleError(Throwable error, Object requestId, String method, String toolName,
                             int requestNumber, long startNanos, int payloadSize, int code, String prefix) {
        String msg = Sanitizer.sanitize(error.message ?: 'Unknown error')
        debugLog("${prefix}: ${msg}")
        publishEvent(Stage.ERROR, requestId, method, toolName, requestNumber, startNanos, payloadSize, 0, msg)
        writer.sendError(requestId as String, code, "${prefix}: ${msg}")
    }

    private void publishEvent(Stage stage, Object requestId, String method, String toolName,
                              int requestNumber, long startNanos, int payloadSize = 0,
                              int responseSize = 0, String errorMessage = null) {
        try {
            eventPublisher.publishEvent(new McpRequestEvent(
                this, stage, requestId, method, toolName, requestNumber, startNanos,
                payloadSize, responseSize, errorMessage
            ))
        } catch (Exception e) {
            debugLog("Event publish failed: ${e.message}")
        }
    }

    private static void debugLog(String message) {
        if (DEBUG) {
            try {
                System.err.println("[${java.time.LocalTime.now()}] MCP: ${message}")
                System.err.flush()
            } catch (Exception ignored) {}
        }
    }
}
