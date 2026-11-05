package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.Title
import spock.lang.Unroll

/**
 * FS 0.9.124 -- CS re-architecture Phase 2 WP2.4 (brief section 7): a claim is not needed before a write.
 *
 * <p>WP2 C1 (FS 0.9.107) refused a mutating call on an unclaimed chat process NOT_CLAIMED, because the call would have
 * run unattributed. Since caller-session phase 2/3 (FS 0.9.120/0.9.121) every chat call names its session with
 * callerSession -- refused CALLER_SESSION_REQUIRED otherwise, before dispatch -- and a call that names its session was
 * already let through NOT_CLAIMED. With both guards live, NOT_CLAIMED could not fire: it was deleted (dead-code rule).
 * These pin the production path with both checks on, so the deletion is proved, not assumed.</p>
 *
 * CW-1  unclaimed chat process: a mutating call that names its session runs
 * CW-2  unclaimed chat process: a mutating call that names none is refused CALLER_SESSION_REQUIRED -- not NOT_CLAIMED
 */
@Title('FS -- CW a write needs a session, not a claim')
@CompileDynamic
class ClaimOptionalForWritesSpec extends Specification {

    int calls = 0

    private ToolHandler handler(String tool) {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: tool, description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> { calls++; McpResponse.success(1, [content: [[type: 'text', text: '{"ok":true}']]]) }
        h
    }

    /** A chat's stdio process with BOTH production checks on, holding no claim. */
    private McpController unclaimedChat(String tool) {
        McpController c = new McpController([handler(tool)] as List<ToolHandler>)
        c.globalResponseCapChars = 64000
        FilesystemTelemetryService t = Stub(FilesystemTelemetryService)
        t.processClaim() >> null
        c.telemetryService = t
        c.requireCallerSession = true
        // Before FS 0.9.124 the NOT_CLAIMED check was also live on a chat process; switch it on where it still exists, so
        // a green run on the old code shows it could not fire with the caller-session guard in front of it.
        if (c.hasProperty('requireClaimForWrites')) c.'requireClaimForWrites' = true
        c
    }

    private static McpResponse send(McpController c, String tool, Map args) {
        c.handleRequest(new McpRequest(id: 1, method: 'tools/call', params: [name: tool, arguments: args]))
    }

    private static String text(McpResponse r) {
        ((r.result?.get('content') as List)?.first() as Map)?.get('text') as String
    }

    @Unroll
    def 'CW-1: unclaimed chat -- #tool naming its session runs'() {
        when:
        McpResponse r = send(unclaimedChat(tool), tool, [action: action, path: 'C:/tmp/x', callerSession: '2026-11-05-13-19'])

        then:
        !JsonOutput.toJson(r).contains('CALLER_SESSION_REQUIRED')
        !JsonOutput.toJson(r).contains('NOT_CLAIMED')
        calls == 1

        where:
        tool             | action
        'file_write'     | 'write'
        'file_lifecycle' | 'delete'
        'execute'        | 'cmd'
        'tools'          | 'git'
        'worktree'       | 'create'
    }

    def 'CW-2: unclaimed chat -- a write naming no session is refused CALLER_SESSION_REQUIRED, and nothing runs'() {
        when:
        McpResponse r = send(unclaimedChat('file_write'), 'file_write', [action: 'write', path: 'C:/tmp/x'])

        then: 'the caller-session guard answers, the same shape FsCallerSessionRequiredSpec reads'
        JsonOutput.toJson(r).contains('CALLER_SESSION_REQUIRED')
        !JsonOutput.toJson(r).contains('NOT_CLAIMED')
        calls == 0
    }
}
