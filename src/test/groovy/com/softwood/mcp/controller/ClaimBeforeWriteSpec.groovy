package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.Title
import spock.lang.Unroll

/**
 * WP2 C1 (optimised-learning, decision 384, Will 2026-10-19): an unclaimed chat process refuses a mutating call.
 *
 * <p>Until now an unclaimed FS process ran every call and appended an unbound_warning: the edit or build happened, its
 * telemetry was filed as unattributed and PLAN-GATE had no session to key it by -- a warning on a success, after the
 * fact. In a chat (stdio) a mutating tool -- file_write, file_lifecycle, execute, tools, worktree -- is now refused
 * NOT_CLAIMED before dispatch until the process is claimed. Reads still run (they keep the restart notice), execute's
 * job_* actions are reads, and the companion, which serves flows, is never refused.</p>
 *
 * CB-1  unclaimed chat: each mutating tool is refused NOT_CLAIMED and the handler is not called (RED on 0.9.106)
 * CB-2  CONTROL: unclaimed chat, file_read still runs
 * CB-3  CONTROL: unclaimed chat, execute action=job_status still runs
 * CB-4  CONTROL: claimed chat, file_write runs
 * CB-5  CONTROL: unclaimed companion, file_write runs
 */
@Title('WP2 C1 -- claim before a mutating call')
@CompileDynamic
class ClaimBeforeWriteSpec extends Specification {

    int calls = 0

    private static void seam(Object o, String p, Object v) { if (o.hasProperty(p)) o."$p" = v }

    private ToolHandler handler(String tool) {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: tool, description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> { calls++; McpResponse.success(1, [content: [[type: 'text', text: '{"ok":true}']]]) }
        h
    }

    private McpController controller(String tool, String claimed, boolean chat = true) {
        McpController c = new McpController([handler(tool)] as List<ToolHandler>)
        c.globalResponseCapChars = 64000
        FilesystemTelemetryService t = Stub(FilesystemTelemetryService)
        t.readActiveSessionId() >> claimed
        c.telemetryService = t
        seam(c, 'requireClaimForWrites', chat)
        c
    }

    private static McpResponse send(McpController c, String tool, Map args) {
        c.handleRequest(new McpRequest(id: 1, method: 'tools/call', params: [name: tool, arguments: args]))
    }

    private static String text(McpResponse r) {
        ((r.result?.get('content') as List)?.first() as Map)?.get('text') as String
    }

    @Unroll
    def 'CB-1: unclaimed chat -- #tool is refused NOT_CLAIMED and nothing runs'() {
        given:
        McpController c = controller(tool, null)

        when:
        McpResponse r = send(c, tool, [action: action, path: 'C:/tmp/x'])

        then:
        text(r).startsWith('NOT_CLAIMED')
        text(r).contains('server_lifecycle action=claim_session')
        calls == 0

        where:
        tool             | action
        'file_write'     | 'write'
        'file_lifecycle' | 'delete'
        'execute'        | 'cmd'
        'tools'          | 'git'
        'worktree'       | 'create'
    }

    def 'CB-2: CONTROL -- unclaimed chat, a read still runs'() {
        when:
        send(controller('file_read', null), 'file_read', [action: 'read', path: 'C:/tmp/x'])

        then:
        calls == 1
    }

    def 'CB-3: CONTROL -- unclaimed chat, execute job_status still runs'() {
        when:
        send(controller('execute', null), 'execute', [action: 'job_status', options: [jobId: 'j1']])

        then:
        calls == 1
    }

    def 'CB-4: CONTROL -- claimed chat, a write runs'() {
        when:
        send(controller('file_write', '2026-10-19-12-56'), 'file_write', [action: 'write', path: 'C:/tmp/x'])

        then:
        calls == 1
    }

    def 'CB-5: CONTROL -- the companion is never refused'() {
        when:
        send(controller('file_write', null, false), 'file_write', [action: 'write', path: 'C:/tmp/x'])

        then:
        calls == 1
    }
}
