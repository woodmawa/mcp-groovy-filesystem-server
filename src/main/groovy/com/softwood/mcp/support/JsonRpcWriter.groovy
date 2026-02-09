package com.softwood.mcp.support

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * JSON-RPC response writer for STDIO transport.
 *
 * Handles serialization, sanitization, and robust error fallbacks.
 * All output goes to System.out (stdout) with flush after each message.
 *
 * v0.0.5: Extracted from StdioMcpServer to keep transport concerns separated.
 */
@Slf4j
@CompileStatic
class JsonRpcWriter {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    /**
     * Send a successful response. Returns response size in bytes.
     */
    int sendResponse(Object response) {
        try {
            def sanitized = Sanitizer.sanitizeObject(response)

            String json
            try {
                json = objectMapper.writeValueAsString(sanitized)
            } catch (Exception jsonError) {
                log.debug("JSON serialization error: {}", Sanitizer.sanitize(jsonError.message))
                sendError("error", -32603, "Response serialization failed: ${Sanitizer.sanitize(jsonError.message)}")
                return 0
            }

            // Final check for control characters
            if (json.find(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/) != null) {
                log.warn("Response contained control characters, cleaned before sending")
                json = Sanitizer.sanitize(json)
            }

            System.out.println(json)
            System.out.flush()
            return json.length()

        } catch (Throwable t) {
            log.error("Critical error sending response: {}", t.class.simpleName)
            sendLastResort()
            return 0
        }
    }

    /**
     * Send a JSON-RPC error response
     */
    void sendError(String requestId, int code, String message) {
        try {
            String safeId = Sanitizer.sanitize(requestId ?: "unknown")
            String safeMessage = Sanitizer.sanitize(message ?: "Unknown error")

            def errorResponse = [
                jsonrpc: "2.0",
                id: safeId,
                error: [
                    code: code,
                    message: safeMessage
                ]
            ]

            String json
            try {
                json = objectMapper.writeValueAsString(errorResponse)
            } catch (Exception jsonError) {
                log.debug("Error response serialization failed")
                json = """{"jsonrpc":"2.0","id":"${safeId.replaceAll('"', '\\\\"')}","error":{"code":${code},"message":"Error serialization failed"}}"""
            }

            json = Sanitizer.sanitize(json)
            System.out.println(json)
            System.out.flush()

        } catch (Throwable t) {
            log.error("CRITICAL: Failed to send error response: {}", t.class.simpleName)
            sendLastResort()
        }
    }

    /**
     * Absolute last resort - hardcoded minimal JSON error
     */
    private static void sendLastResort() {
        try {
            System.out.println('{"jsonrpc":"2.0","id":"error","error":{"code":-32603,"message":"Critical error"}}')
            System.out.flush()
        } catch (Exception ignored) {
            // Nothing more we can do
        }
    }
}
