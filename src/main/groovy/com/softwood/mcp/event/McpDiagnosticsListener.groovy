package com.softwood.mcp.event

import groovy.util.logging.Slf4j
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Diagnostics listener for MCP request lifecycle events.
 *
 * Logs timing and payload info to stderr (via Slf4j) so it doesn't
 * interfere with the JSON-RPC stdout channel.
 *
 * Configurable thresholds:
 *   - Warns on requests taking > 2 seconds
 *   - Warns on payloads > 50KB (potential buffering concern)
 *   - Always logs SENT and ERROR stages for audit trail
 *
 * v0.0.5: Added to diagnose intermittent stalls on large writeFile payloads.
 */
@Component
@Slf4j
class McpDiagnosticsListener {

    /** Warn if any request stage takes longer than this */
    private static final long SLOW_REQUEST_THRESHOLD_MS = 2000

    /** Warn if request payload exceeds this size */
    private static final int LARGE_PAYLOAD_THRESHOLD_BYTES = 50_000

    @EventListener
    void onMcpRequestEvent(McpRequestEvent event) {
        switch (event.stage) {
            case McpRequestEvent.Stage.RECEIVED:
                if (event.payloadSizeBytes > LARGE_PAYLOAD_THRESHOLD_BYTES) {
                    log.warn("LARGE PAYLOAD: {} bytes for req#{} method={}",
                        event.payloadSizeBytes, event.requestNumber, event.method ?: 'unknown')
                }
                log.debug("RECEIVED: {}", event)
                break

            case McpRequestEvent.Stage.PARSED:
                log.debug("PARSED: {}", event)
                break

            case McpRequestEvent.Stage.DISPATCHED:
                log.debug("DISPATCHED: {}", event)
                break

            case McpRequestEvent.Stage.COMPLETED:
                if (event.elapsedMs > SLOW_REQUEST_THRESHOLD_MS) {
                    log.warn("SLOW PROCESSING: {}ms for req#{} {}/{}",
                        event.elapsedMs, event.requestNumber, event.method, event.toolName ?: '')
                }
                log.debug("COMPLETED: {}", event)
                break

            case McpRequestEvent.Stage.SENT:
                String level = event.elapsedMs > SLOW_REQUEST_THRESHOLD_MS ? 'SLOW' : 'OK'
                log.info("SENT [{}]: req#{} {}{} {}ms payload={}B response={}B",
                    level,
                    event.requestNumber,
                    event.method ?: '',
                    event.toolName ? '/' + event.toolName : '',
                    event.elapsedMs,
                    event.payloadSizeBytes,
                    event.responseSizeBytes)
                break

            case McpRequestEvent.Stage.ERROR:
                log.error("ERROR: {} - {}", event, event.errorMessage)
                break
        }
    }
}
