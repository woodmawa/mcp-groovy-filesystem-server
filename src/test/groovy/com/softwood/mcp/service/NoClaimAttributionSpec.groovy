package com.softwood.mcp.service

import com.softwood.mcp.controller.McpController
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import spock.lang.Specification

/**
 * X86 caller-session phase 3, item P3-3 -- the PROCESS CLAIM stops being an attribution source.
 *
 * <p>Desktop runs ONE stdio process set for every chat, so this process's claim names whichever chat claimed last.
 * Since phase 2 every chat stdio call carries callerSession (FS refuses it otherwise, bar the exempt server_lifecycle
 * claim_session / claim_status / status) and a flow node's companion call carries the X-Mcp-Caller-Session header.
 * Measured over 7 days: the only FS calls filed session_source='claim' were server_lifecycle claim_session. So:</p>
 * <ul>
 *   <li>the ATTRIBUTION resolver ({@link FilesystemTelemetryService#readActiveSessionId}) answers the call's
 *       callerSession, then the declared header session ({@link AwCallContext#session}), else null -- never the claim;</li>
 *   <li>{@link FilesystemTelemetryService#processClaim} answers the claim alone, for the claim record and as evidence;</li>
 *   <li>an exempt claim_session call is filed under the session it claims (its own sessionId argument), source claim;
 *       any other call with no arg and no header files no session (system:fs-unclaimed), source none, with the claim
 *       kept beside it as claim_session evidence.</li>
 * </ul>
 * <ul>
 *   <li>NCA-1 (RED before P3-3): claimed to claim-Y, no arg, no header -> the resolver answers null, not claim-Y</li>
 *   <li>NCA-2: arg X -> X</li>
 *   <li>NCA-3: only a header session H -> H</li>
 *   <li>NCA-4: processClaim() still answers claim-Y, whatever the arg or header</li>
 *   <li>NCA-5 (RED before P3-3): the exempt claim_session call files args.sessionId, source claim, claim evidence;
 *       a non-claim no-arg call files no session, source none, claim evidence</li>
 * </ul>
 */
@CompileDynamic
class NoClaimAttributionSpec extends Specification {

    static final String CLAIM_Y = 'claim-Y'
    static final String X = '2026-11-04-15-23'
    static final String H = 'header-session-H'

    def cleanup() {
        CallerSession.clear()
        AwCallContext.clear()
    }

    private static FilesystemTelemetryService claimedTo(String sid) {
        FilesystemTelemetryService svc = new FilesystemTelemetryService()   // no dbPath: in-memory claim only
        svc.claimSession(sid, 'grp')
        return svc
    }

    def 'NCA-1: claimed to claim-Y, no arg and no header -- the attribution resolver answers null, never the claim'() {
        given:
        FilesystemTelemetryService svc = claimedTo(CLAIM_Y)

        expect:
        CallerSession.currentArg() == null
        AwCallContext.session() == null
        svc.readActiveSessionId() == null
    }

    def 'NCA-2: the call names X -- the resolver answers X'() {
        given:
        FilesystemTelemetryService svc = claimedTo(CLAIM_Y)
        CallerSession.setArg(X)

        expect:
        svc.readActiveSessionId() == X
    }

    def 'NCA-3: only the declared header session H on this thread -- the resolver answers H'() {
        given:
        FilesystemTelemetryService svc = claimedTo(CLAIM_Y)
        AwCallContext.set('run-1', 'node-1', H)

        expect:
        svc.readActiveSessionId() == H
    }

    def 'NCA-3b: the arg wins over the header'() {
        given:
        FilesystemTelemetryService svc = claimedTo(CLAIM_Y)
        AwCallContext.set('run-1', 'node-1', H)
        CallerSession.setArg(X)

        expect:
        svc.readActiveSessionId() == X
    }

    def 'NCA-4: processClaim() still answers the claim, whatever the call names'() {
        given:
        FilesystemTelemetryService svc = claimedTo(CLAIM_Y)

        expect: 'bare'
        svc.processClaim() == CLAIM_Y

        when: 'an arg and a header are held'
        CallerSession.setArg(X)
        AwCallContext.set('run-1', 'node-1', H)

        then: 'the claim is unmoved by either'
        svc.processClaim() == CLAIM_Y

        and: 'an unclaimed process has none'
        new FilesystemTelemetryService().processClaim() == null
    }

    def 'NCA-4b: claim_status reports the claim as this_process_session, even while a call names another session'() {
        given:
        FilesystemTelemetryService svc = claimedTo(CLAIM_Y)
        CallerSession.setArg(X)

        expect:
        svc.claimStatus().this_process_session == CLAIM_Y
    }

    // ---------------------------------------------------------------- the telemetry row

    private static ToolHandler handler() {
        ToolHandler h = new ToolHandler() {
            List<Map<String, Object>> getToolDefinitions() {
                [[name: 'tools', description: 'stub', inputSchema: [type: 'object', properties: [action: [type: 'string']]]],
                 [name: 'server_lifecycle', description: 'stub',
                  inputSchema: [type: 'object', properties: [action: [type: 'string'], sessionId: [type: 'string']]]]]
            }
            boolean canHandle(String toolName) { true }
            McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
                McpResponse.success(requestId, [content: [[type: 'text', text: JsonOutput.toJson([ok: true])]]])
            }
        }
        return h
    }

    private static McpController controller(FilesystemTelemetryService t) {
        McpController c = new McpController([handler()])
        c.telemetryService = t
        c.globalResponseCapChars = 64000
        return c
    }

    private static McpRequest call(String tool, Map<String, Object> args) {
        new McpRequest(id: 1, method: 'tools/call', params: [name: tool, arguments: args])
    }

    def 'NCA-5a: the exempt claim_session call, no arg -- filed under the session it claims, source claim, claim evidence'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.processClaim() >> CLAIM_Y
        t.readActiveSessionId() >> CLAIM_Y   // the pre-P3-3 resolver; the row must not follow it

        when:
        controller(t).handleRequest(call('server_lifecycle', [action: 'claim_session', sessionId: X]))

        then:
        1 * t.recordToolCall(X, 'server_lifecycle', _, _, _, _, _,
            { Map d -> d.session_source == 'claim' && d.claim_session == CLAIM_Y && !d.containsKey('caller_session') })
    }

    def 'NCA-5b: a non-claim call with no arg and no header files NO session -- source none, claim kept as evidence'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.processClaim() >> CLAIM_Y
        t.readActiveSessionId() >> CLAIM_Y

        when:
        McpResponse r = controller(t).handleRequest(call('tools', [action: 'gradle']))

        then:
        1 * t.recordToolCall('system:fs-unclaimed', 'tools', _, _, _, _, _,
            { Map d -> d.session_source == 'none' && d.claim_session == CLAIM_Y })

        and: 'the process IS claimed, so no unbound warning'
        !JsonOutput.toJson(r).contains('unbound_warning')
    }

    def 'NCA-5c: claim_status, no arg -- files no session, source none (it is not a claim)'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.processClaim() >> CLAIM_Y
        t.readActiveSessionId() >> CLAIM_Y

        when:
        controller(t).handleRequest(call('server_lifecycle', [action: 'claim_status']))

        then:
        1 * t.recordToolCall('system:fs-unclaimed', 'server_lifecycle', _, _, _, _, _,
            { Map d -> d.session_source == 'none' && d.claim_session == CLAIM_Y })
    }

    def 'NCA-5d: an unclaimed process, no arg, no header -- unbound, warned, no session'() {
        given:
        FilesystemTelemetryService t = Mock()
        t.processClaim() >> null

        when:
        McpResponse r = controller(t).handleRequest(call('tools', [action: 'gradle']))

        then:
        1 * t.recordToolCall('system:fs-unclaimed', 'tools', _, _, _, _, _,
            { Map d -> d.session_source == 'none' && !d.containsKey('claim_session') })
        JsonOutput.toJson(r).contains('unbound_warning')
    }
}
