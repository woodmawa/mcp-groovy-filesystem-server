package com.softwood.mcp

import com.softwood.mcp.controller.McpController
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ToolHandler
import groovy.json.JsonSlurper
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.function.IntConsumer

/**
 * Found by the C5 review-holdout blind graders (local-model-reliability, 2026-10-08; two local reviewers passed the
 * file), checked by Claude: StdioMcpServer hands JsonRpcWriter.sendError `requestId as String`, and the writer then
 * turns any all-digit string back into a number. So a client that sends the STRING id "123" gets an error for the
 * NUMBER 123 -- JSON-RPC says the response id must be the same value, and a client keyed on "123" never matches it.
 * And a request that cannot be parsed is answered with the synthetic id "unknown-<n>", where JSON-RPC requires null.
 * Drives the REAL run() loop and asserts on what reached stdout.
 */
class StdioErrorIdSpec extends Specification {

    static class ThrowingHandler implements ToolHandler {
        List<Map<String, Object>> getToolDefinitions() {
            [[name: 'boom', description: 'always fails', inputSchema: [type: 'object']]] as List<Map<String, Object>>
        }
        boolean canHandle(String n) { n == 'boom' }
        McpResponse handleToolCall(String n, Map<String, Object> a, Object id) { throw new IllegalArgumentException('bad arg') }
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

    /**
     * McpController catches a handler's exception itself and answers with the id intact, so the error path under test --
     * StdioMcpServer.handleError -> JsonRpcWriter.sendError -- is only reached when the dispatch itself throws.
     */
    static class ThrowingController extends McpController {
        ThrowingController() { super([new ThrowingHandler()] as List<ToolHandler>) }
        @Override
        McpResponse handleRequest(com.softwood.mcp.model.McpRequest request) { throw new IllegalArgumentException('dispatch failed') }
    }

    private List<Map> run(String... msgs) {
        McpController controller = new ThrowingController()
        StdioMcpServer server = new StdioMcpServer(controller, null, null)
        System.setIn(new ByteArrayInputStream((msgs.join('\n') + '\n').getBytes(StandardCharsets.UTF_8)))
        server.run()
        captured.toString('UTF-8').readLines().findAll { it.trim().startsWith('{') }
            .collect { new JsonSlurper().parseText(it) as Map }
    }

    private static String call(String idJson) {
        '{"jsonrpc":"2.0","id":' + idJson + ',"method":"tools/call","params":{"name":"boom","arguments":{}}}'
    }

    def 'EI-1: a string id that looks numeric comes back as the same STRING'() {
        when:
        List<Map> out = run(call('"123"'))

        then:
        out.size() == 1
        out[0].error != null
        out[0].id instanceof String
        out[0].id == '123'
    }

    def 'EI-2: a numeric id comes back as a number, and a non-numeric string id as itself'() {
        when:
        List<Map> out = run(call('7'), call('"req-abc"'))

        then:
        out*.id == [7, 'req-abc']
        out[0].id instanceof Number
        out.every { it.error != null }
    }

    def 'EI-4: id 0 is a valid JSON-RPC id and comes back as 0 (Groovy truth must not treat it as missing)'() {
        when:
        List<Map> out = run(call('0'))

        then:
        out.size() == 1
        out[0].id == 0
        out[0].id instanceof Number
    }

    def 'EI-3: a request that cannot be parsed is answered with id null, not a synthetic id'() {
        when:
        List<Map> out = run('{this is not json')

        then:
        out.size() == 1
        out[0].error.code == -32700
        out[0].containsKey('id')
        out[0].id == null
    }
}
