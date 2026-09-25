package com.softwood.mcp

import com.softwood.mcp.controller.McpController
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.ToolDescriptionRegistry
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonSlurper
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.function.IntConsumer

/**
 * Align kit 2026-09-25 item 3 on stdio: notifications/tools/list_changed is written to the stdio
 * stream (a) once after the client's notifications/initialized and (b) when the background poll
 * finds the served text changed. Drives the REAL run() loop and asserts on what reached stdout.
 */
class StdioToolListChangedSpec extends Specification {

    static class StubCs extends ContextServerClient {
        Map<String, String> rows = [:]
        @Override String getHelpSection(String key) { rows[key] }
        @Override boolean recordToolDescriptions(String s, String o, String t, List<Map<String, String>> d) { true }
    }

    static class FakeHandler implements ToolHandler {
        List<Map<String, Object>> getToolDefinitions() {
            [[name: 'file_list', description: 'SOURCE list text', inputSchema: [type: 'object']]] as List<Map<String, Object>>
        }
        boolean canHandle(String n) { n == 'file_list' }
        McpResponse handleToolCall(String n, Map<String, Object> a, Object id) { McpResponse.success(id, [:]) }
    }

    private InputStream originalIn
    private PrintStream originalOut
    private ByteArrayOutputStream captured

    def setup() {
        originalIn = System.in
        originalOut = System.out
        captured = new ByteArrayOutputStream()
        System.setOut(new PrintStream(captured, true, 'UTF-8'))
        StdioMcpServer.exitAction = { int code -> } as IntConsumer
    }

    def cleanup() {
        System.setIn(originalIn)
        System.setOut(originalOut)
        StdioMcpServer.exitAction = StdioMcpServer.DEFAULT_EXIT_ACTION
    }

    private List<Map> lines() {
        captured.toString('UTF-8').readLines().findAll { it.trim().startsWith('{') }
            .collect { new JsonSlurper().parseText(it) as Map }
    }

    private static String input(String... msgs) { msgs.join('\n') + '\n' }

    def 'SL-1: list_changed is written once, after notifications/initialized, and not before'() {
        given:
        StubCs cs = new StubCs()
        ToolDescriptionRegistry registry = new ToolDescriptionRegistry(contextServerClient: cs)
        McpController controller = new McpController([new FakeHandler()] as List<ToolHandler>)
        controller.toolDescriptionRegistry = registry
        controller.registerDescriptionDefaults()
        StdioMcpServer server = new StdioMcpServer(controller, null, null)
        server.toolDescriptionRegistry = registry
        System.setIn(new ByteArrayInputStream(input(
            '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}',
            '{"jsonrpc":"2.0","method":"notifications/initialized"}',
            '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
        ).getBytes(StandardCharsets.UTF_8)))

        when:
        server.run()
        List<Map> out = lines()
        List<String> kinds = out.collect { it.method ?: "resp:${it.id}" as String }

        then:
        kinds == ['resp:1', 'notifications/tools/list_changed', 'resp:2']
        out[1].jsonrpc == '2.0'
        !out[1].containsKey('id')
        ((out[0].result.capabilities as Map).tools as Map).listChanged == true
    }

    def 'SL-2: a change found by the background poll writes list_changed to stdout'() {
        given:
        StubCs cs = new StubCs()
        ToolDescriptionRegistry registry = new ToolDescriptionRegistry(contextServerClient: cs)
        McpController controller = new McpController([new FakeHandler()] as List<ToolHandler>)
        controller.toolDescriptionRegistry = registry
        controller.registerDescriptionDefaults()
        StdioMcpServer server = new StdioMcpServer(controller, null, null)
        server.toolDescriptionRegistry = registry
        System.setIn(new ByteArrayInputStream(new byte[0]))
        server.run()
        registry.pollOnce()                       // baseline
        int before = lines().count { it.method == 'notifications/tools/list_changed' } as int

        when: 'the row changes and the poll runs'
        cs.rows['tool_desc_file_list'] = 'ROW new text'
        registry.pollOnce()

        then:
        lines().count { it.method == 'notifications/tools/list_changed' } == before + 1

        when: 'the poll runs again with nothing changed'
        registry.pollOnce()

        then:
        lines().count { it.method == 'notifications/tools/list_changed' } == before + 1
    }
}
