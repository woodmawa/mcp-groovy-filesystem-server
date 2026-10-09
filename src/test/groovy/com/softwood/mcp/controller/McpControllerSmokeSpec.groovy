package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ToolHandler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Smoke tests for McpController v0.0.7.
 *
 * Validates tool registration, initialize handshake, tools/list response,
 * and dispatch to the 7 registered handlers.
 *
 * v0.0.7 — Phase 5 Polish
 */
@SpringBootTest
@ActiveProfiles('test')
class McpControllerSmokeSpec extends Specification {

    @Autowired McpController controller
    @Autowired List<ToolHandler> toolHandlers

    def "All 9 ToolHandlers are registered"() {
        expect: "exactly 9 handlers injected (WorktreeService joined in FS 0.9.78)"
        toolHandlers.size() == 9
    }

    def "Controller registers exactly 9 tools"() {
        when:
        McpRequest req = new McpRequest(id: 'list-1', method: 'tools/list', params: [:])
        McpResponse response = controller.handleRequest(req)

        then:
        response.result != null
        response.error == null
        List tools = response.result.tools as List
        tools.size() == 9

        and: "all 9 expected tool names are present (worktree joined in FS 0.9.78)"
        def names = tools.collect { (it as Map).name } as Set
        names == ['file_lifecycle', 'file_list', 'file_search', 'file_read',
                  'file_write', 'execute', 'tools', 'server_lifecycle', 'worktree'] as Set
    }

    def "initialize handshake returns correct protocol version and server info"() {
        when:
        McpRequest req = new McpRequest(
            id: 'init-1',
            method: 'initialize',
            params: [protocolVersion: '2024-11-05']
        )
        McpResponse response = controller.handleRequest(req)

        then:
        response.result != null
        response.error == null
        response.result.protocolVersion == '2024-11-05'
        (response.result.serverInfo as Map).version == 'dev'
    }

    def "ping returns empty success result"() {
        when:
        McpRequest req = new McpRequest(id: 'ping-1', method: 'ping', params: [:])
        McpResponse response = controller.handleRequest(req)

        then:
        response.result != null
        response.error == null
    }

    def "Unknown method returns -32601 error"() {
        when:
        McpRequest req = new McpRequest(id: 'err-1', method: 'unknownMethod', params: [:])
        McpResponse response = controller.handleRequest(req)

        then:
        response.error != null
        response.error.code == -32601
    }

    def "Unknown tool returns isError:true tool error (updated for RCA-1 fix)"() {
        when:
        McpRequest req = new McpRequest(
            id: 'err-2',
            method: 'tools/call',
            params: [name: 'nonExistentTool', arguments: [:]]
        )
        McpResponse response = controller.handleRequest(req)

        then: "toolError: result with isError:true, not a JSON-RPC error object"
        response.error == null
        response.result != null
        response.result.isError == true
        (response.result.content[0] as Map).text?.toString()?.contains('Unknown tool')
    }

    def "tools/call dispatches file_read project_root correctly"() {
        when:
        McpRequest req = new McpRequest(
            id: 'dispatch-1',
            method: 'tools/call',
            params: [name: 'file_read', arguments: [action: 'project_root']]
        )
        McpResponse response = controller.handleRequest(req)

        then:
        response.result != null
        response.error == null
    }

    def "Notification (null id) returns null without error"() {
        when:
        McpRequest req = new McpRequest(id: null, method: 'notifications/initialized', params: [:])
        McpResponse response = controller.handleRequest(req)

        then:
        response == null
    }
}