package com.softwood.mcp.service

import com.sun.net.httpserver.HttpServer
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FS 0.9.60 -- every call FS makes to CS declares the session it is for.
 *
 * <p>Measured 2026-09-23 over six hours: of CS's 5,273 telemetry rows, 2,931 were unattributed, and the
 * FS-originated ones carried NO caller_session at all -- 1,018 file-registry upserts (one per FS file read),
 * 215 ontology gate checks, 115 plan-gate checks, 46 locates. ContextServerClient already knew the session
 * (resolveSessionId(), sent as a tool ARGUMENT on some calls) but never sent the X-Mcp-Caller-Session header,
 * and CS attributes telemetry only from the header. FS 0.9.58 fixed the receiving half for FS's own HTTP
 * companion; this is the sending half.</p>
 *
 * <p>Asserted on what actually arrives over HTTP (practice #1485), for a synchronous path, the asynchronous
 * file-registry path that carries most of the volume, and the no-session case.</p>
 */
class ContextServerClientCallerHeaderSpec extends Specification {

    HttpServer server
    final List<String> seen = Collections.synchronizedList(new ArrayList<String>())
    CountDownLatch latch = new CountDownLatch(1)

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/mcp') { ex ->
            String h = ex.requestHeaders.getFirst('X-Mcp-Caller-Session')
            seen << (h == null ? '<none>' : h)
            byte[] b = '{"jsonrpc":"2.0","id":1,"result":{"content":[]}}'.getBytes('UTF-8')
            ex.sendResponseHeaders(200, b.length)
            ex.responseBody.withStream { it.write(b) }
            latch.countDown()
        }
        server.start()
    }

    def cleanup() { server?.stop(0) }

    private ContextServerClient client(String sid) {
        ContextServerClient c = new ContextServerClient()
        c.contextServerUrl = "http://127.0.0.1:${server.address.port}".toString()
        c.structurePersistEnabled = true
        c.telemetryService = Stub(FilesystemTelemetryService) { readActiveSessionId() >> sid }
        c
    }

    def 'CH-1: a synchronous call to CS carries the session as X-Mcp-Caller-Session'() {
        when:
        client('2026-09-23-16-22').postWithTimeout('{"jsonrpc":"2.0","id":1,"method":"tools/call"}', 2000)

        then:
        seen == ['2026-09-23-16-22']
    }

    def 'CH-2: the async file-registry upsert -- most of the unattributed volume -- carries it too'() {
        when:
        client('2026-09-23-16-22').upsertFileRegistryAsync('C:/tmp/x.groovy', 'abc123def456', 10, 1L)

        then:
        latch.await(5, TimeUnit.SECONDS)
        seen == ['2026-09-23-16-22']
    }

    def 'CH-3: with no active session no header is invented'() {
        when:
        client(null).postWithTimeout('{"jsonrpc":"2.0","id":1,"method":"tools/call"}', 2000)

        then:
        seen == ['<none>']
    }
}
