package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import spock.lang.Specification

/**
 * FsUnboundLoudnessSpec — FS 0.9.27, W11.1.
 *
 * <p><b>RED on 0.9.26.</b> CS shouts when a process holds no session claim: an observation write
 * comes back carrying {@code unbound:true}, {@code unbound_warning} and {@code owner_key}
 * (see {@code ContextWriteActionRouter.markIfUnbound}). FS said nothing at all, and quietly filed
 * every subsequent call into the {@code session_id='unknown'} holding pen.</p>
 *
 * <p>The asymmetry is the defect, and it is not theoretical. On 2026-09-11 a Claude Desktop
 * auto-update respawned all three JVMs mid-session. CS was re-claimed within the minute because it
 * complained. FS did not, and session {@code 2026-09-11-08-52} filed 13 file_read/file_search calls
 * to the pen — every read it made after the restart — so {@code read_count} and
 * {@code ontology_pct} both recorded 0 for a session that had located before every indexed read.
 * The five FS rows that WERE attributed to it are all pre-restart and contain no read action at
 * all, which is why a startup-time check would have passed and is not what is built here.</p>
 *
 * <h3>Why a second content element rather than CS's in-map keys</h3>
 * <ol>
 *   <li>The tool payload is a JSON string inside {@code content[0].text}. Injecting a key into it
 *       at the dispatch boundary means re-parsing and re-serialising every handler's output.</li>
 *   <li>It is added OUTSIDE the handler, so no trim can eat it. CS's own warning was eaten by
 *       {@code KEEP_KEYS}, and that list's comments record it as the sixth such occurrence. This is
 *       deliberately not the seventh.</li>
 *   <li>It is appended AFTER {@code estimateResponseSize} and {@code recordToolCall}, so the
 *       warning never inflates {@code tool_call_telemetry.response_char_count} nor trips the global
 *       response backstop. CT-UBW-3 is what pins that property.</li>
 * </ol>
 *
 * <h3>Contracts</h3>
 * <ul>
 *   <li>CT-UBW-1 — {@code withUnboundWarning} appends a second content element whose text parses to
 *       a map carrying {@code unbound:true}, a non-blank {@code unbound_warning} naming the FS claim
 *       call, and the {@code owner_key} it was given.</li>
 *   <li>CT-UBW-2 — {@code content[0].text} is byte-identical before and after. The handler's own
 *       payload is not touched.</li>
 *   <li>CT-UBW-3 — the input response is NOT mutated: its content list still has one element after
 *       the call. This is the property that keeps telemetry sizing honest, so it is asserted rather
 *       than assumed from call ordering.</li>
 *   <li>CT-UBW-4 — the asserted key set is derived from {@code unboundWarningMap}'s own keySet
 *       rather than transcribed, so a key added there later fails this spec instead of vanishing.
 *       Borrowed from CS's CT-UBW-3, which is why that list stopped rotting.</li>
 *   <li>CT-UBW-5 — a tool-level error response still receives the warning. Being unbound is
 *       orthogonal to the call having failed, and the session most needs telling in that case.</li>
 *   <li>CT-UBW-6 — END TO END through {@code handleRequest}: an unclaimed telemetry service
 *       produces two content elements; a claimed one produces exactly one. Asserted one layer
 *       further out than the helper, because a helper nothing calls is this platform's signature
 *       defect.</li>
 * </ul>
 *
 * @since FS 0.9.27
 */
@CompileDynamic
class FsUnboundLoudnessSpec extends Specification {

    private static final String KEY = 'fs-12345-1789000000000-deadbeef'

    private static McpResponse payloadResponse(Map<String, Object> payload) {
        McpResponse.success(1, [content: [[type: 'text', text: JsonOutput.toJson(payload)]]])
    }

    /** Minimal handler so the controller can be built without Spring. */
    private ToolHandler handlerReturning(McpResponse response) {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: 'file_read', description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> response
        return h
    }

    private McpController controllerWith(McpResponse response, String claimedSession) {
        McpController c = new McpController([handlerReturning(response)])
        FilesystemTelemetryService t = Stub(FilesystemTelemetryService)
        t.readActiveSessionId() >> claimedSession
        c.telemetryService = t
        c.globalResponseCapChars = 64000
        return c
    }

    private static McpRequest readRequest() {
        new McpRequest(id: 1, method: 'tools/call',
            params: [name: 'file_read', arguments: [action: 'read', path: 'C:/tmp/x.groovy']])
    }

    private static List<Object> contentOf(McpResponse r) {
        (r.result?.get('content') as List<Object>) ?: []
    }

    private static Map<String, Object> lastElementAsMap(McpResponse r) {
        List<Object> content = contentOf(r)
        String text = (content.last() as Map<String, Object>).get('text') as String
        new JsonSlurper().parseText(text) as Map<String, Object>
    }

    // =========================================================================
    // CT-UBW-1 — the warning is appended, and it carries the three keys
    // =========================================================================

    def 'CT-UBW-1: withUnboundWarning appends an element carrying unbound, unbound_warning and owner_key'() {
        given:
        McpResponse original = payloadResponse([content_hash: 'abc123', lines: 12])

        when:
        McpResponse out = McpController.withUnboundWarning(original, KEY)
        Map<String, Object> note = lastElementAsMap(out)

        then:
        contentOf(out).size() == 2
        note.get('unbound') == true
        (note.get('unbound_warning') as String)?.trim()
        (note.get('unbound_warning') as String).contains('claim_session')
        note.get('owner_key') == KEY
    }

    // =========================================================================
    // CT-UBW-2 — the handler's payload is untouched
    // =========================================================================

    def 'CT-UBW-2: content[0].text is byte-identical before and after'() {
        given:
        McpResponse original = payloadResponse([content_hash: 'abc123', lines: 12])
        String before = (contentOf(original).first() as Map).get('text') as String

        when:
        McpResponse out = McpController.withUnboundWarning(original, KEY)
        String after = (contentOf(out).first() as Map).get('text') as String

        then:
        after == before
    }

    // =========================================================================
    // CT-UBW-3 — the input is not mutated, so telemetry sizing stays honest
    // =========================================================================

    def 'CT-UBW-3: the input response is not mutated'() {
        given:
        McpResponse original = payloadResponse([content_hash: 'abc123'])

        when:
        McpResponse out = McpController.withUnboundWarning(original, KEY)

        then:
        contentOf(original).size() == 1
        contentOf(out).size() == 2
        !out.is(original)
    }

    // =========================================================================
    // CT-UBW-4 — the key set is derived, not transcribed
    // =========================================================================

    def 'CT-UBW-4: every key unboundWarningMap produces reaches the caller'() {
        given:
        Set<String> declared = McpController.unboundWarningMap(KEY).keySet()

        when:
        Map<String, Object> delivered = lastElementAsMap(
            McpController.withUnboundWarning(payloadResponse([ok: true]), KEY))

        then:
        delivered.keySet().containsAll(declared)
    }

    // =========================================================================
    // CT-UBW-5 — an error response still gets the warning
    // =========================================================================

    def 'CT-UBW-5: a tool-level error response still carries the warning'() {
        given:
        McpResponse err = McpResponse.toolError(1, 'Response too large')

        when:
        McpResponse out = McpController.withUnboundWarning(err, KEY)

        then:
        contentOf(out).size() == 2
        lastElementAsMap(out).get('unbound') == true
        out.result.get('isError') == true
    }

    // =========================================================================
    // CT-UBW-6 — end to end through the dispatcher
    // =========================================================================

    def 'CT-UBW-6: an unclaimed FS process warns on every call, a claimed one never does'() {
        given:
        McpResponse handlerResult = payloadResponse([content_hash: 'abc123', lines: 3])

        when: 'the process holds no claim'
        McpResponse unbound = controllerWith(handlerResult, null).handleRequest(readRequest())

        then:
        contentOf(unbound).size() == 2
        lastElementAsMap(unbound).get('unbound') == true

        when: 'the process holds a claim'
        McpResponse bound = controllerWith(handlerResult, '2026-09-11-10-45').handleRequest(readRequest())

        then:
        contentOf(bound).size() == 1
        !(((contentOf(bound).first() as Map).get('text') as String).contains('unbound'))
    }
}
