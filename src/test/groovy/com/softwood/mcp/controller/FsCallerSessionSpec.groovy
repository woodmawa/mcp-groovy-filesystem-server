package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import spock.lang.Specification

/**
 * FS 0.9.58 -- telemetry honours the caller's declared session ({@code X-Mcp-Caller-Session}).
 *
 * <h3>Measured first (2026-09-23)</h3>
 * {@code fs-telemetry-not-stranded-in-unknown} went 60 -> 85 -> 102 over the day and was suspected
 * to be restart-driven. It is not: of 61 FS rows filed as 'unknown' in two days, 55 are
 * {@code tool_name='tools'} -- gradle-test-summary's mcp.tool_call to FS's HTTP companion, which
 * holds no chat's claim. AW stamps {@code X-Mcp-Caller-Session} on EVERY outbound mcp.tool_call
 * ({@code DeclaredSession.applyTo}, AW 1.30.22) and CS reads it; FS never did (no reference to the
 * header anywhere in FS). The count rose with how often flows called FS, not with restarts.
 *
 * <h3>Scope: attribution only</h3>
 * The declared session is used for the TELEMETRY row and the unbound warning, nothing else.
 * PLAN-GATE and the read ledger still read this process's own claim -- a flow node's gradle call
 * must not start being gated because its caller declared a session.
 *
 * <h3>Contracts</h3>
 * <ul>
 *   <li>CS-1 an unclaimed process attributes a call carrying the header to that session, and does
 *       not append the unbound warning</li>
 *   <li>CS-2 CONTROL: unclaimed and no header -- 'unknown' and the warning, as before</li>
 *   <li>CS-3 the header wins over this process's own claim: the caller says whose work it is</li>
 *   <li>CS-4 a blank header is no header</li>
 *   <li>CS-5 the declared session does not leak into the NEXT request on the same thread</li>
 * </ul>
 */
@CompileDynamic
class FsCallerSessionSpec extends Specification {

    private ToolHandler handler() {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: 'tools', description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> McpResponse.success(1, [content: [[type: 'text', text: JsonOutput.toJson([ok: true])]]])
        return h
    }

    private McpController controller(FilesystemTelemetryService t) {
        McpController c = new McpController([handler()])
        c.telemetryService = t
        c.globalResponseCapChars = 64000
        return c
    }

    private static McpRequest call() {
        new McpRequest(id: 1, method: 'tools/call', params: [name: 'tools', arguments: [action: 'gradle']])
    }

    private static int contentCount(McpResponse r) { ((r.result?.get('content') as List) ?: []).size() }

    def 'CS-1 an unclaimed process attributes a declared call to the declared session'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> null
        McpController c = controller(t)

        when:
        McpResponse r = c.handleRequest(call(), '2026-09-23-13-21')

        then:
        1 * t.recordToolCall('2026-09-23-13-21', 'tools', _, _, _, _, _)
        contentCount(r) == 1
    }

    def 'CS-2 CONTROL: unclaimed with no header files as unknown and warns'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> null
        McpController c = controller(t)

        when:
        McpResponse r = c.handleRequest(call(), null)

        then:
        1 * t.recordToolCall('unknown', 'tools', _, _, _, _, _)
        contentCount(r) == 2
    }

    def 'CS-3 the declared session wins over this process own claim'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> 'own-claim'
        McpController c = controller(t)

        when:
        c.handleRequest(call(), 'declared-by-caller')

        then:
        1 * t.recordToolCall('declared-by-caller', 'tools', _, _, _, _, _)
    }

    def 'CS-4 a blank header is no header'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> 'own-claim'
        McpController c = controller(t)

        when:
        c.handleRequest(call(), '   ')

        then:
        1 * t.recordToolCall('own-claim', 'tools', _, _, _, _, _)
    }

    def 'CS-5 the declared session does not leak into the next request'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> null
        McpController c = controller(t)

        when:
        c.handleRequest(call(), 'first')
        c.handleRequest(call(), null)

        then:
        1 * t.recordToolCall('first', 'tools', _, _, _, _, _)
        1 * t.recordToolCall('unknown', 'tools', _, _, _, _, _)
    }
}
