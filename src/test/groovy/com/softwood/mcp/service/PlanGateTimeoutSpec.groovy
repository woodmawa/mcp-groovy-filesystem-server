package com.softwood.mcp.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.Timeout

/**
 * FS R5c (decision 356, Will 2026-10-12) -- PLAN-GATE waits two seconds for CS.
 *
 * <p>Measured 2026-10-12: 26 of 138 plan-gate asks in 7 days hit FS's 1500 ms read timeout and let the write through
 * unseen; a first ask took ~1.0-1.8 s on the live store. CS is being made faster (NeverApplicableCostSpec); FS also
 * waits longer. Through a real HTTP stub, not a mocked client, because the timeout is a property of the connection.</p>
 *
 * <p>PGT-1 a CS that answers in 1.7 s is answered. PGT-2 CONTROL a CS that answers in 2.6 s still fails open -- the
 * wait is longer, not unbounded.</p>
 */
@Timeout(20)
class PlanGateTimeoutSpec extends Specification {

    HttpServer server
    volatile long delayMs = 0L

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/') { HttpExchange ex ->
            byte[] body
            int code
            if (ex.requestURI.path == '/mcp') {
                Thread.sleep(delayMs)
                String text = JsonOutput.toJson([allow: true, reason: 'empty'])
                body = JsonOutput.toJson([jsonrpc: '2.0', id: 1, result: [content: [[type: 'text', text: text]]]]).getBytes('UTF-8')
                code = 200
            } else {
                body = '{}'.getBytes('UTF-8')
                code = 404
            }
            ex.responseHeaders.add('Content-Type', 'application/json')
            ex.sendResponseHeaders(code, body.length)
            ex.responseBody.withStream { it.write(body) }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    def cleanup() { server?.stop(0) }

    private ContextServerClient client() {
        ContextServerClient c = new ContextServerClient()
        c.contextServerUrl = "http://127.0.0.1:${server.address.port}".toString()
        return c
    }

    def 'PGT-1: a CS that answers in 1.7 s is answered, not failed open'() {
        given:
        delayMs = 1700L

        when:
        Map<String, Object> r = client().planGateCheck([tool: 'file_write', component: 'repo/Thing'], 'sess-1')

        then:
        r != null
        r.reason == 'empty'
        ContextServerClient.PLAN_GATE_READ_TIMEOUT_MS == 2000
    }

    def 'PGT-2 CONTROL: a CS that answers in 2.6 s still fails open'() {
        given:
        delayMs = 2600L

        when:
        long t0 = System.currentTimeMillis()
        Map<String, Object> r = client().planGateCheck([tool: 'file_write', component: 'repo/Thing'], 'sess-1')

        then:
        r == null
        System.currentTimeMillis() - t0 < 2500L
    }
}
