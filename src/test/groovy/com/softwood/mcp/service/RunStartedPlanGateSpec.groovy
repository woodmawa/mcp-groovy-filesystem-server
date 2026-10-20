package com.softwood.mcp.service

import com.softwood.mcp.controller.HttpMcpController
import com.softwood.mcp.controller.McpController
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * optimised-learning WP3 (decision 389, obs 13009) -- FS carries the flow run's start time to PLAN-GATE.
 *
 * <p>AW sends X-Aw-Run-Started (epoch ms) beside X-Aw-Run / X-Aw-Node (AW RunStartedHeaderSpec). CS's same-batch
 * guard needs it on the ask, because a flow's gradle node reaches the gate ~3 s after the batch that started it and the
 * window is 2 s. FS's part is to carry it: from the real HTTP entry (POST /mcp, the route AW uses -- the 0.9.59 lesson)
 * into AwCallContext, and from there onto a flow-node PLAN-GATE ask as runStartedAt.</p>
 *
 *  RSF-1  POST /mcp: X-Aw-Run-Started is visible during the call as a long, and gone after
 *  RSF-2  a flow-node gradle ask carries runStartedAt
 *  RSF-3  CONTROL: a chat call (no run/node) asks with no runStartedAt
 *  RSF-4  CONTROL: a malformed header is dropped -- the flow-node ask still goes, without runStartedAt
 */
@CompileDynamic
class RunStartedPlanGateSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)

    @TempDir Path tmp
    String repoDir

    def setup() {
        Files.createDirectories(tmp.resolve('repo-a/.git'))
        repoDir = tmp.resolve('repo-a').toString()
    }

    def cleanup() { AwCallContext.clear() }

    def 'RSF-1: POST /mcp carries X-Aw-Run-Started into the call, and clears it after'() {
        given:
        List<Object> seen = []
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: 'tools', description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> {
            seen << AwCallContext.run() << AwCallContext.runStartedAt()
            McpResponse.success(2, [content: [[type: 'text', text: JsonOutput.toJson([ok: true])]]])
        }
        McpController c = new McpController([h])
        c.globalResponseCapChars = 64000
        HttpMcpController hc = new HttpMcpController()
        hc.mcpController = c
        HttpServletRequest init = Stub(HttpServletRequest)
        ResponseEntity<McpResponse> r0 = hc.handleRequest(
            new McpRequest(id: 1, method: 'initialize', params: [protocolVersion: '2025-03-26']), null, init)
        String sid = r0.headers.getFirst('Mcp-Session-Id')
        HttpServletRequest r = Stub(HttpServletRequest)
        r.getHeader('X-Mcp-Caller-Session') >> '2026-10-20-10-33'
        r.getHeader('X-Aw-Run') >> 'run-9'
        r.getHeader('X-Aw-Node') >> 'run-gradle'
        r.getHeader('X-Aw-Run-Started') >> '1792480000000'

        when:
        hc.handleRequest(new McpRequest(id: 2, method: 'tools/call', params: [name: 'tools', arguments: [action: 'gradle']]),
                         sid, r)

        then:
        seen == ['run-9', 1792480000000L]
        AwCallContext.runStartedAt() == null
    }

    def 'RSF-2: a flow-node gradle ask carries runStartedAt'() {
        given:
        telemetry.readActiveSessionId() >> null
        AwCallContext.set('run-9', 'run-gradle', 'sid-flow', '1792480000000')
        Map sent = null

        when:
        guard.checkExecute('gradlew test', repoDir)

        then:
        1 * client.planGateCheck(_, _) >> { Map a, String s -> sent = a; [allow: true] }
        sent.flowNode == true
        sent.runStartedAt == 1792480000000L
    }

    def 'RSF-3: CONTROL a chat call asks with no runStartedAt'() {
        given:
        telemetry.readActiveSessionId() >> 'sid-chat'
        Map sent = null

        when:
        guard.checkExecute('gradlew test', repoDir)

        then:
        1 * client.planGateCheck(_, _) >> { Map a, String s -> sent = a; [allow: true] }
        !sent.containsKey('runStartedAt')
    }

    def 'RSF-4: CONTROL a malformed start header is dropped; the flow-node ask still goes'() {
        given:
        telemetry.readActiveSessionId() >> null
        AwCallContext.set('run-9', 'run-gradle', 'sid-flow', 'not-a-number')
        Map sent = null

        when:
        guard.checkExecute('gradlew test', repoDir)

        then:
        1 * client.planGateCheck(_, _) >> { Map a, String s -> sent = a; [allow: true] }
        sent.flowNode == true
        !sent.containsKey('runStartedAt')
    }
}
