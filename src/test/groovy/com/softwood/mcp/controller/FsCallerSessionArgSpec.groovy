package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.CallerSession
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Caller-session brief 2026-10-21, phase 1 (D1), FS's chat door.
 *
 * <p>Desktop runs ONE stdio process set for every chat, so this process's claim names whichever chat claimed last. A
 * chat call now carries {@code callerSession}. FS's McpController reads and strips it BEFORE the unread-argument guard
 * (it is in no tool's own schema -- the controller owns it), checks its shape, and holds it for the call
 * ({@link CallerSession}). While held, {@code readActiveSessionId} answers it, and the telemetry row is filed under it
 * with session_source arg and the claim beside it. Without it nothing changes; a header (a flow node's call) keeps its
 * rule and is recorded as header.</p>
 *
 * <ul>
 *   <li>FCA-1 (RED): claim own-claim, arg X -> row X, source arg, claim own-claim, caller_session X</li>
 *   <li>FCA-2 CONTROL: no arg -> row own-claim, source claim</li>
 *   <li>FCA-3: stripped before the unread-argument guard and the handler; held during, gone after</li>
 *   <li>FCA-4: a malformed id is refused CALLER_SESSION_INVALID and nothing runs</li>
 *   <li>FCA-5: tools/list advertises it on every tool</li>
 *   <li>FCA-6: readActiveSessionId answers the held session, then the claim again</li>
 *   <li>FCA-7: a declared header with no arg is recorded as header</li>
 * </ul>
 */
@CompileDynamic
class FsCallerSessionArgSpec extends Specification {

    static final String X = '2026-10-21-12-22'

    final List<Map<String, Object>> handled = new CopyOnWriteArrayList<>()
    final List<String> heldDuring = new CopyOnWriteArrayList<>()

    def cleanup() { CallerSession.clear() }

    private ToolHandler handler() {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: 'tools', description: 'stub',
                                    inputSchema: [type: 'object', properties: [action: [type: 'string']]]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> { String tool, Map<String, Object> args, Object id ->
            handled << new LinkedHashMap<String, Object>(args)
            heldDuring << CallerSession.currentArg()
            McpResponse.success(1, [content: [[type: 'text', text: JsonOutput.toJson([ok: true])]]])
        }
        return h
    }

    private McpController controller(FilesystemTelemetryService t) {
        McpController c = new McpController([handler()])
        c.telemetryService = t
        c.globalResponseCapChars = 64000
        c.refuseUnreadArgs = Boolean.TRUE
        return c
    }

    private static McpRequest call(Map<String, Object> extra = [:]) {
        new McpRequest(id: 1, method: 'tools/call', params: [name: 'tools', arguments: [action: 'gradle'] + extra])
    }

    def 'FCA-1 (RED until D1): the call names X while the process is claimed by another chat -- the row is Xs'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> 'own-claim'

        when:
        controller(t).handleRequest(call([callerSession: X]))

        then:
        1 * t.recordToolCall(X, 'tools', _, _, _, _, _,
            { Map d -> d.session_source == 'arg' && d.claim_session == 'own-claim' && d.caller_session == X })
    }

    def 'FCA-2 CONTROL (phase-1 fallback): without callerSession the claim decides'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> 'own-claim'

        when:
        controller(t).handleRequest(call())

        then:
        1 * t.recordToolCall('own-claim', 'tools', _, _, _, _, _,
            { Map d -> d.session_source == 'claim' && d.claim_session == 'own-claim' && !d.containsKey('caller_session') })
    }

    def 'FCA-3: stripped before the unread-argument guard and the handler; held during the call, gone after'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> 'own-claim'

        when:
        McpResponse r = controller(t).handleRequest(call([callerSession: X]))

        then:
        !JsonOutput.toJson(r).contains('UNREAD_ARG')
        handled.size() == 1
        !handled[0].containsKey('callerSession')
        heldDuring == [X]
        CallerSession.currentArg() == null
    }

    def 'FCA-4: a malformed id is refused CALLER_SESSION_INVALID and nothing runs'() {
        given:
        FilesystemTelemetryService t = Mock()

        when:
        McpResponse r = controller(t).handleRequest(call([callerSession: "x' OR 1=1 --"]))

        then:
        JsonOutput.toJson(r).contains('CALLER_SESSION_INVALID')
        handled.isEmpty()
    }

    def 'FCA-5: tools/list advertises callerSession on every tool'() {
        when:
        McpResponse r = controller(Mock(FilesystemTelemetryService)).handleRequest(
            new McpRequest(id: 1, method: 'tools/list', params: [:]))
        Map tool = ((r.result as Map).get('tools') as List).first() as Map
        Map props = (tool.get('inputSchema') as Map).get('properties') as Map

        then:
        (props.get('callerSession') as Map).get('type') == 'string'
        (props.get('action') as Map).get('type') == 'string'
    }

    def 'FCA-6: readActiveSessionId answers the held session, then the claim again'() {
        given: 'a real service with no claim and no database'
        FilesystemTelemetryService real = new FilesystemTelemetryService()

        when:
        String before = real.readActiveSessionId()
        CallerSession.setArg(X)
        String during = real.readActiveSessionId()
        CallerSession.clear()
        String after = real.readActiveSessionId()

        then:
        before == null
        during == X
        after == null
    }

    def 'FCA-7: a declared header with no arg is recorded as header'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> 'own-claim'

        when:
        controller(t).handleRequest(call(), 'declared-by-flow')

        then:
        1 * t.recordToolCall('declared-by-flow', 'tools', _, _, _, _, _,
            { Map d -> d.session_source == 'header' && d.claim_session == 'own-claim' })
    }
}
