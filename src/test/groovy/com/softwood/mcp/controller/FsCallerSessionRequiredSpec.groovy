package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.CallerSession
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Caller-session phase 2 on FS (FS 0.9.120, handoff 2026-11-04 step 1) -- the same guard CS has shipped since 1.1.127.
 *
 * <p>A chat's own stdio process refuses a tools/call that does not say whose it is: CALLER_SESSION_REQUIRED, nothing
 * runs. The refusal leaves ONE telemetry row, filed under no session (sessionId 'unknown', never resolved to the
 * claim), session_source none, error_code CALLER_SESSION_REQUIRED and the claim beside it as evidence of which chat
 * leaked it -- so contract caller-session-required-refusals-24h counts all three servers. Exempt: server_lifecycle
 * claim_session / claim_status / status. The HTTP companion (flow nodes, every chat) is never refused.</p>
 *
 * <ul>
 *   <li>FCR-1 (RED): no callerSession on the chat process -> refused, handler not run, normal telemetry not written</li>
 *   <li>FCR-2 (RED): the refusal is recorded once via recordRefusedCall with error_code, session_source none</li>
 *   <li>FCR-3 CONTROL: with callerSession the call runs</li>
 *   <li>FCR-4: server_lifecycle claim_session / claim_status / status run without it</li>
 *   <li>FCR-5: the companion (not the chat process) is never refused</li>
 *   <li>FCR-6 (RED): the row CS is asked to write names session 'unknown', not the claim, and carries the claim --
 *       asserted at the client FS hands it to, after the async writer has drained</li>
 * </ul>
 */
@CompileDynamic
class FsCallerSessionRequiredSpec extends Specification {

    static final String X = '2026-11-03-15-52'

    final List<String> handled = new CopyOnWriteArrayList<>()

    def cleanup() { CallerSession.clear() }

    private ToolHandler handler(String name) {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: name, description: 'stub',
                                    inputSchema: [type: 'object', properties: [action: [type: 'string']]]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> { String tool, Map<String, Object> args, Object id ->
            handled << ((tool + ':' + args.action) as String)
            McpResponse.success(1, [content: [[type: 'text', text: JsonOutput.toJson([ok: true])]]])
        }
        return h
    }

    private McpController controller(FilesystemTelemetryService t, Boolean required = Boolean.TRUE) {
        McpController c = new McpController([handler('tools'), handler('server_lifecycle')])
        c.telemetryService = t
        c.globalResponseCapChars = 64000
        c.refuseUnreadArgs = Boolean.TRUE
        c.requireCallerSession = required
        return c
    }

    private static McpRequest call(String tool, String action, Map<String, Object> extra = [:]) {
        new McpRequest(id: 1, method: 'tools/call', params: [name: tool, arguments: [action: action] + extra])
    }

    def 'FCR-1 (RED): a chat call without callerSession is refused CALLER_SESSION_REQUIRED and nothing runs'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.processClaim() >> 'own-claim'

        when:
        McpResponse r = controller(t).handleRequest(call('tools', 'gradle'))

        then:
        JsonOutput.toJson(r).contains('CALLER_SESSION_REQUIRED')
        handled.isEmpty()
        0 * t.recordToolCall(*_)
    }

    def 'FCR-2 (RED): the refusal is recorded once, with error_code and session_source none'() {
        given:
        FilesystemTelemetryService t = Mock()

        when:
        controller(t).handleRequest(call('tools', 'gradle'))

        then:
        1 * t.recordRefusedCall('tools', _, _, 'gradle', _,
            { Map d -> d.error_code == 'CALLER_SESSION_REQUIRED' && d.session_source == 'none' })
    }

    def 'FCR-3 CONTROL: with callerSession the call runs'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.processClaim() >> 'own-claim'

        when:
        controller(t).handleRequest(call('tools', 'gradle', [callerSession: X]))

        then:
        handled == ['tools:gradle']
        0 * t.recordRefusedCall(*_)
    }

    def 'FCR-4: server_lifecycle #action runs without callerSession'() {
        given:
        FilesystemTelemetryService t = Mock()

        when:
        controller(t).handleRequest(call('server_lifecycle', action))

        then:
        handled == ["server_lifecycle:${action}".toString()]
        0 * t.recordRefusedCall(*_)

        where:
        action << ['claim_session', 'claim_status', 'status']
    }

    def 'FCR-5: the companion (not the chat stdio process) is never refused'() {
        given:
        FilesystemTelemetryService t = Mock()

        when: 'the seam is left null and this JVM has no stdio profile'
        controller(t, null).handleRequest(call('tools', 'gradle'))

        then:
        handled == ['tools:gradle']
        0 * t.recordRefusedCall(*_)
    }

    def 'FCR-6 (RED): the row CS is asked to write is filed under unknown, not the claim'() {
        given:
        ContextServerClient client = Mock()
        FilesystemTelemetryService t = Spy(FilesystemTelemetryService)
        t.processClaim() >> 'own-claim'   // X86 P3-3: the claim evidence comes from the claim record
        t.dbPath = 'configured'
        t.contextServerClient = client
        List<List> sent = new CopyOnWriteArrayList<>()
        client.recordToolCall(_, _) >> { String sid, Map row -> sent << [sid, row]; true }

        when:
        controller(t).handleRequest(call('tools', 'gradle'))
        boolean drained = t.drainWrites(5000)

        then:
        drained
        sent.size() == 1
        sent[0][0] == 'unknown'
        Map row = sent[0][1] as Map
        row.tool_name == 'tools'
        row.server_name == 'filesystem-server'
        row.error_code == 'CALLER_SESSION_REQUIRED'
        row.session_source == 'none'
        row.claim_session == 'own-claim'
        handled.isEmpty()
    }
}
