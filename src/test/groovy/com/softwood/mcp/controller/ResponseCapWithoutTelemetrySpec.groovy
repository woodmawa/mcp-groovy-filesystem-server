package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ToolHandler
import spock.lang.Specification
import spock.lang.Title

/**
 * Found by the C5 review-sample blind graders (local-model-reliability, 2026-10-08; reviewer task b2922654 called the
 * backstop "correctly applied"), checked by Claude: charCount was computed only inside `if (telemetryService != null)`,
 * so with no telemetry bean the global response cap compared against 0 and never fired. Telemetry is silently absent
 * whenever -Dmcp.usage.db-path is missing (practice #380), and the cap is a safety limit: it must not depend on it.
 */
@Title('Global response cap -- applies without telemetry')
class ResponseCapWithoutTelemetrySpec extends Specification {

    static class BigHandler implements ToolHandler {
        int size
        List<Map<String, Object>> getToolDefinitions() {
            [[name: 'big', description: 'returns size chars', inputSchema: [type: 'object']]] as List<Map<String, Object>>
        }
        boolean canHandle(String n) { n == 'big' }
        McpResponse handleToolCall(String n, Map<String, Object> a, Object id) {
            McpResponse.success(id, [content: [[type: 'text', text: 'x' * size]]] as Map<String, Object>)
        }
    }

    private static McpRequest call() {
        new McpRequest(jsonrpc: '2.0', id: 1, method: 'tools/call', params: [name: 'big', arguments: [:]])
    }

    private static String text(McpResponse r) { ((r.result?.content as List)?.getAt(0) as Map)?.text as String }

    def 'RC-1: with no telemetry service, a response over the cap is replaced by the backstop error'() {
        given:
        McpController c = new McpController([new BigHandler(size: 200)] as List<ToolHandler>)
        c.globalResponseCapChars = 50
        assert c.telemetryService == null

        when:
        McpResponse r = c.handleRequest(call())

        then:
        r.result.isError == true
        text(r).startsWith('Response too large')
    }

    def 'RC-2: with no telemetry service, a response under the cap passes through unchanged'() {
        given:
        McpController c = new McpController([new BigHandler(size: 20)] as List<ToolHandler>)
        c.globalResponseCapChars = 50

        when:
        McpResponse r = c.handleRequest(call())

        then:
        !(r.result.isError == true)
        text(r) == 'x' * 20
    }
}
