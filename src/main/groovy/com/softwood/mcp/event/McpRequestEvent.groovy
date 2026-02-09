package com.softwood.mcp.event

import org.springframework.context.ApplicationEvent

/**
 * MCP Request Lifecycle Event - published at key stages of request processing.
 *
 * Stages:
 *   RECEIVED  - raw line read from stdin (includes payload size)
 *   PARSED    - JSON deserialized to McpRequest
 *   DISPATCHED - about to call controller/handler
 *   COMPLETED  - response ready, about to send
 *   SENT       - response written to stdout and flushed
 *   ERROR      - error occurred at any stage
 *
 * Listeners can use these for diagnostics, metrics, or alerting on slow requests.
 * All timing is relative to requestStartNanos for the same requestId.
 *
 * v0.0.5: Added to diagnose intermittent stalls on large writeFile payloads.
 */
class McpRequestEvent extends ApplicationEvent {

    enum Stage {
        RECEIVED, PARSED, DISPATCHED, COMPLETED, SENT, ERROR
    }

    final Stage stage
    final Object requestId
    final String method
    final String toolName
    final int requestNumber
    final long requestStartNanos
    final long eventNanos
    final int payloadSizeBytes
    final int responseSizeBytes
    final String errorMessage

    McpRequestEvent(Object source, Stage stage, Object requestId, String method,
                    String toolName, int requestNumber, long requestStartNanos,
                    int payloadSizeBytes = 0, int responseSizeBytes = 0,
                    String errorMessage = null) {
        super(source)
        this.stage = stage
        this.requestId = requestId
        this.method = method
        this.toolName = toolName
        this.requestNumber = requestNumber
        this.requestStartNanos = requestStartNanos
        this.eventNanos = System.nanoTime()
        this.payloadSizeBytes = payloadSizeBytes
        this.responseSizeBytes = responseSizeBytes
        this.errorMessage = errorMessage
    }

    /** Elapsed time since request started, in milliseconds */
    long getElapsedMs() {
        return (eventNanos - requestStartNanos) / 1_000_000L
    }

    @Override
    String toString() {
        String base = "[req#${requestNumber} id=${requestId}] ${stage} ${method ?: ''}${toolName ? '/' + toolName : ''} +${elapsedMs}ms"
        if (payloadSizeBytes > 0) base += " payload=${payloadSizeBytes}B"
        if (responseSizeBytes > 0) base += " response=${responseSizeBytes}B"
        if (errorMessage) base += " error=${errorMessage}"
        return base
    }
}
