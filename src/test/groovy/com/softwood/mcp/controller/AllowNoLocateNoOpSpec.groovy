package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.ToolHandler
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.Title

/**
 * FS 0.9.123 (CS decision 440, WP1.3 as verified): ONTOLOGY-GATE is deleted, but options.allowNoLocate stays in the
 * file_read schema as an accepted no-op. Deleting it (as 0.9.122 did) would refuse every caller still passing it
 * UNREAD_ARG -- a new protocol mistake with no class to count it. Will, 2026-11-05: keep it as a no-op for now.
 *
 * Judged against the REAL file_read definition through the controller's UNREAD_ARG guard, with a stub handler whose
 * call count is the layer that would act.
 *
 * NL-1  file_read with options.allowNoLocate=true reaches the handler (not refused UNREAD_ARG)
 * NL-2  CONTROL: an options key file_read does not declare is still refused -- the guard is on, so NL-1 is not vacuous
 */
@Title('FS -- NL allowNoLocate is an accepted no-op after the ontology gate')
@CompileDynamic
class AllowNoLocateNoOpSpec extends Specification {

    int calls = 0

    private McpController controller() {
        Map toolDef = new FileReadService().getToolDefinitions().find { it.name == 'file_read' } as Map
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [toolDef]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> { calls++; McpResponse.success(1, [content: [[type: 'text', text: '{"ok":true}']]]) }
        McpController c = new McpController([h] as List<ToolHandler>)
        c.globalResponseCapChars = 64000
        c.refuseUnreadArgs = true
        c
    }

    private static String text(McpResponse r) {
        ((r.result?.get('content') as List)?.first() as Map)?.get('text') as String
    }

    private static McpResponse send(McpController c, Map args) {
        c.handleRequest(new McpRequest(id: 1, method: 'tools/call', params: [name: 'file_read', arguments: args]))
    }

    def 'NL-1: a read carrying allowNoLocate reaches the handler'() {
        given:
        McpController c = controller()

        when:
        McpResponse r = send(c, [action: 'range', path: 'C:/tmp/x.groovy', options: [startLine: 1, maxLines: 10, allowNoLocate: true]])

        then:
        !text(r).startsWith('UNREAD_ARG')
        calls == 1
    }

    def 'NL-2: CONTROL -- an undeclared options key is still refused'() {
        given:
        McpController c = controller()

        when:
        McpResponse r = send(c, [action: 'range', path: 'C:/tmp/x.groovy', options: [startLine: 1, maxLines: 10, noSuchOption: true]])

        then:
        text(r).startsWith('UNREAD_ARG')
        calls == 0
    }
}
