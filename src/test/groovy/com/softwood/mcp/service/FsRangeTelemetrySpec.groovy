package com.softwood.mcp.service

import com.softwood.mcp.controller.McpController
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.read.RangeTelemetry
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FS 0.9.67 -- value-review-phase3 item 3, CAPTURE (decision 272).
 *
 * A relative response-size threshold needs "lines asked for" against "lines the symbol spans", and
 * telemetry recorded neither: a range row carried path_hash only (1,861 range calls / 1.57 M tok in
 * 30 days, avg 841, 126 over 2k). This captures FS's half -- what was REQUESTED and what was RETURNED --
 * so a week of data can choose the ratio before any guard is written. CS records the located half.
 *
 * Asserted on what arrives at CS over HTTP (practice #1485), and on the controller route.
 *
 * RT-1 detail(): startLine + maxLines from options, lines from the response
 * RT-2 detail(): endLine instead of maxLines gives the same window
 * RT-3 detail(): a held window ('unchanged') returned 0 lines
 * RT-4 CONTROL detail(): not a range -> nothing
 * RT-5 the row that reaches CS carries req_start_line, req_max_lines, lines_returned
 * RT-6 the controller passes the detail for a range call
 */
@CompileDynamic
class FsRangeTelemetrySpec extends Specification {

    @TempDir Path tmp

    HttpServer server
    final List<Map> bodies = Collections.synchronizedList(new ArrayList<Map>())
    CountDownLatch latch

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/mcp') { ex ->
            Map body = new JsonSlurper().parseText(ex.requestBody.getText('UTF-8')) as Map
            bodies << ((body.params as Map)?.arguments as Map ?: [:])
            byte[] b = '{"jsonrpc":"2.0","id":1,"result":{"content":[]}}'.getBytes('UTF-8')
            ex.sendResponseHeaders(200, b.length)
            ex.responseBody.withStream { it.write(b) }
            latch?.countDown()
        }
        server.start()
    }

    def cleanup() { server?.stop(0) }

    private static String rangeBody(int start, int end) {
        JsonOutput.toJson([action: 'range', startLine: start, endLine: end, lines: end - start + 1, content: 'x'])
    }

    def 'RT-1 detail: startLine and maxLines from options, lines from the response'() {
        when:
        Map d = RangeTelemetry.detail('file_read',
            [action: 'range', path: 'C:/x.groovy', options: [startLine: 40, maxLines: 30]], rangeBody(40, 69))

        then:
        d == [req_start_line: 40, req_max_lines: 30, lines_returned: 30]
    }

    def 'RT-2 detail: endLine gives the same window as maxLines'() {
        expect:
        RangeTelemetry.detail('file_read',
            [action: 'range', options: [startLine: 10, endLine: 19]], rangeBody(10, 15)) ==
            [req_start_line: 10, req_max_lines: 10, lines_returned: 6]
    }

    def 'RT-3 detail: a held window returned 0 lines'() {
        expect:
        RangeTelemetry.detail('file_read', [action: 'range', options: [startLine: 1, maxLines: 50]],
            JsonOutput.toJson([unchanged: true, cached: true])).lines_returned == 0
    }

    def 'RT-4 CONTROL detail: not a range, nothing'() {
        expect:
        RangeTelemetry.detail('file_read', [action: 'read'], rangeBody(1, 5)) == null
        RangeTelemetry.detail('file_write', [action: 'range'], rangeBody(1, 5)) == null
    }

    def 'RT-5 the row that reaches CS carries the window'() {
        given:
        latch = new CountDownLatch(1)
        ContextServerClient client = new ContextServerClient()
        client.contextServerUrl = "http://127.0.0.1:${server.address.port}".toString()
        FilesystemTelemetryService svc = new FilesystemTelemetryService()
        svc.dbPath = tmp.resolve('t.db').toString().replace((char) 92, (char) 47)
        svc.contextServerClient = client

        when:
        svc.recordToolCall('S', 'file_read', 100, [path: 'x'] as Map<String, Object>, 'range', 'h', 'success',
                           [req_start_line: 40, req_max_lines: 30, lines_returned: 30] as Map<String, Object>)

        then:
        latch.await(5, TimeUnit.SECONDS)
        Map row = bodies.find { it.action == 'record_tool_call' }?.telemetry as Map
        row.req_start_line == 40
        row.req_max_lines == 30
        row.lines_returned == 30
    }

    def 'RT-6 the controller passes the detail for a range call'() {
        given:
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [[name: 'file_read', description: 'stub', inputSchema: [:]]]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> McpResponse.success(1, [content: [[type: 'text', text: rangeBody(5, 9)]]])
        FilesystemTelemetryService t = Mock()
        t.readActiveSessionId() >> 'S'
        McpController c = new McpController([h])
        c.telemetryService = t
        c.globalResponseCapChars = 64000

        when:
        c.handleRequest(new McpRequest(id: 1, method: 'tools/call', params: [name: 'file_read',
            arguments: [action: 'range', path: 'C:/x', options: [startLine: 5, maxLines: 5]]]), null)

        then:
        1 * t.recordToolCall('S', 'file_read', _, _, 'range', _, _,
                             [req_start_line: 5, req_max_lines: 5, lines_returned: 5])
    }
}
