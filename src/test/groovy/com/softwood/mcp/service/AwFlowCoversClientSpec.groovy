package com.softwood.mcp.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Title

/**
 * FS 0.9.128 -- the FLOW_COVERS guard ASKS AW for the covering flows (flow_management catalogue rows=true). AW owns its
 * tables (decision 311): FS never reads agentic-workflow.db and CS is not a go-between. Asserted against a stub AW
 * companion speaking MCP over HTTP: what was asked, and how JSON and event-stream answers are read.
 */
@Title('FS 0.9.128 -- AwJobEventClient.flowCovers asks AW, never reads its tables')
@groovy.transform.CompileDynamic
class AwFlowCoversClientSpec extends Specification {

    HttpServer server
    List<Map> asked = []
    boolean sse = false

    static final Map CATALOGUE = [flows: 2, catalogue: 'x', rows: [
        [flow: 'git-release', version: '1.11', line: 'git-release:1.11  (repoDir*)  Release.  covers: ^git push\\b', covers: ['^git push\\b']],
        [flow: 'long-call', version: '1.0', line: 'long-call:1.0  (toolName*)  One call.', covers: []]]]

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/mcp') { HttpExchange ex ->
            Map body = new JsonSlurper().parseText(ex.requestBody.getText('UTF-8')) as Map
            String out
            if (body.method == 'initialize') {
                ex.responseHeaders.add('Mcp-Session-Id', 'sess-1')
                out = JsonOutput.toJson([jsonrpc: '2.0', id: body.id, result: [:]])
            } else {
                asked << (body.params as Map)
                String env = JsonOutput.toJson([jsonrpc: '2.0', id: body.id,
                    result: [content: [[type: 'text', text: JsonOutput.toJson(CATALOGUE)]]]])
                out = sse ? ('event: message\ndata: ' + env + '\n\n') : env
            }
            byte[] b = out.getBytes('UTF-8')
            ex.sendResponseHeaders(200, b.length)
            ex.responseBody.withStream { it.write(b) }
        }
        server.start()
    }

    def cleanup() { server?.stop(0) }

    private AwJobEventClient client() {
        AwJobEventClient c = new AwJobEventClient()
        c.awUrl = "http://127.0.0.1:${server.address.port}".toString()
        c
    }

    def 'AFC-1: it asks AW flow_management catalogue rows=true, and keeps only the flows that declare covers'() {
        when:
        List<Map> got = client().flowCovers()

        then:
        asked.size() == 1
        asked[0].name == 'flow_management'
        asked[0].arguments == [action: 'catalogue', rows: true]
        got*.flow == ['git-release']
        got[0].covers == ['^git push\\b']
    }

    def 'AFC-2: an event-stream answer is read from its data line'() {
        given:
        sse = true

        expect:
        client().flowCovers()*.flow == ['git-release']
    }

    def 'AFC-3: no AW listening answers null, never throws -- the guard then passes'() {
        given:
        AwJobEventClient c = new AwJobEventClient()
        c.awUrl = 'http://127.0.0.1:1'

        expect:
        c.flowCovers() == null
    }
}
