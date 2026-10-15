package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import spock.lang.Specification

/**
 * FS 0.9.59 -- the declared session must survive the REAL HTTP entry, POST /mcp.
 *
 * <h3>What 0.9.58 got wrong</h3>
 * 0.9.58 read X-Mcp-Caller-Session on McpController's POST / and FsCallerSessionSpec proved it there.
 * Verified live after the install: the next flow call to FS still filed as 'unknown'. AW posts to
 * /mcp, which HttpMcpController serves -- and it delegates to the ONE-argument
 * McpController.handleRequest, so the header never reached the code that was tested. The spec
 * asserted one layer short of the entry point (practice 3502: grep for the caller before believing a
 * method is on the path -- acked 'c' at the gate without doing the grep).
 *
 * This spec drives POST /mcp exactly as AW does: initialize for a transport session, then tools/call
 * carrying both headers.
 *
 * CONTRACT:
 *   HC-1  a tools/call on POST /mcp carrying X-Mcp-Caller-Session is attributed to that session
 *   HC-2  CONTROL: without the header, an unclaimed process files system:fs-unclaimed (FS 0.9.80 SB-2; 'unknown' before)
 *   HC-3  FS 0.9.95: X-Aw-Run / X-Aw-Node on POST /mcp are visible to the tool DURING the call (AwCallContext), so a
 *         long gradle call can post progress to the AW node waiting on it -- and are gone after the call
 */
@CompileDynamic
class HttpCallerSessionSpec extends Specification {

    private ToolHandler handler() {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: 'tools', description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> McpResponse.success(2, [content: [[type: 'text', text: JsonOutput.toJson([ok: true])]]])
        return h
    }

    private HttpServletRequest servlet(String callerSession) {
        HttpServletRequest r = Stub(HttpServletRequest)
        r.getHeader('Origin') >> null
        r.getHeader('X-Mcp-Caller-Session') >> callerSession
        return r
    }

    private String initialize(HttpMcpController hc) {
        ResponseEntity<McpResponse> init = hc.handleRequest(
            new McpRequest(id: 1, method: 'initialize', params: [protocolVersion: '2025-03-26']), null, servlet(null))
        return init.headers.getFirst('Mcp-Session-Id')
    }

    private static McpRequest toolsCall() {
        new McpRequest(id: 2, method: 'tools/call', params: [name: 'tools', arguments: [action: 'gradle']])
    }

    def 'HC-1 POST /mcp carries the declared session into telemetry'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> null
        McpController c = new McpController([handler()])
        c.telemetryService = t
        c.globalResponseCapChars = 64000
        HttpMcpController hc = new HttpMcpController()
        hc.mcpController = c
        String sid = initialize(hc)

        when:
        hc.handleRequest(toolsCall(), sid, servlet('2026-09-23-13-21'))

        then:
        1 * t.recordToolCall('2026-09-23-13-21', 'tools', _, _, _, _, _, _)
    }

    def 'HC-2 CONTROL: no header on POST /mcp still files unknown'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> null
        McpController c = new McpController([handler()])
        c.telemetryService = t
        c.globalResponseCapChars = 64000
        HttpMcpController hc = new HttpMcpController()
        hc.mcpController = c
        String sid = initialize(hc)

        when:
        hc.handleRequest(toolsCall(), sid, servlet(null))

        then:
        1 * t.recordToolCall('system:fs-unclaimed', 'tools', _, _, _, _, _, _)   // FS 0.9.80 SB-2 (D2/D3): named, not 'unknown'
    }

    def 'HC-3 X-Aw-Run / X-Aw-Node reach the tool during the call, and are cleared after'() {
        given:
        List<String> seen = []
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: 'tools', description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> {
            seen << com.softwood.mcp.service.AwCallContext.run() << com.softwood.mcp.service.AwCallContext.node()
            seen << com.softwood.mcp.service.AwCallContext.session()   // FS 0.9.97 G2
            McpResponse.success(2, [content: [[type: 'text', text: JsonOutput.toJson([ok: true])]]])
        }
        McpController c = new McpController([h])
        c.globalResponseCapChars = 64000
        HttpMcpController hc = new HttpMcpController()
        hc.mcpController = c
        String sid = initialize(hc)
        HttpServletRequest r = Stub(HttpServletRequest)
        r.getHeader('X-Mcp-Caller-Session') >> '2026-10-14-14-01'
        r.getHeader('X-Aw-Run') >> 'run-77'
        r.getHeader('X-Aw-Node') >> 'run-gradle'

        when:
        hc.handleRequest(toolsCall(), sid, r)

        then:
        seen == ['run-77', 'run-gradle', '2026-10-14-14-01']
        com.softwood.mcp.service.AwCallContext.run() == null
        com.softwood.mcp.service.AwCallContext.node() == null
        com.softwood.mcp.service.AwCallContext.session() == null
    }
}
