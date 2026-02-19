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

    def "All 7 ToolHandlers are registered"() {
        expect: "exactly 7 handlers injected"
        toolHandlers.size() == 7
    }

    def "Controller registers exactly 7 tools"() {
        when:
        McpRequest req = new McpRequest(id: 'list-1', method: 'tools/list', params: [:])
        McpResponse response = controller.handleRequest(req)

        then:
        response.result != null
        response.error == null
        List tools = response.result.tools as List
        tools.size() == 7

        and: "all 7 expected tool names are present"
        def names = tools.collect { (it as Map).name } as Set
        names == ['file_lifecycle', 'file_list', 'file_search', 'file_read',
                  'file_write', 'execute', 'tools'] as Set
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
        (response.result.serverInfo as Map).version == '0.7.1'
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

    def "Unknown tool returns -32601 error"() {
        when:
        McpRequest req = new McpRequest(
            id: 'err-2',
            method: 'tools/call',
            params: [name: 'nonExistentTool', arguments: [:]]
        )
        McpResponse response = controller.handleRequest(req)

        then:
        response.error != null
        response.error.code == -32601
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
