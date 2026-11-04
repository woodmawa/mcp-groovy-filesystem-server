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

    /**
     * X86 P3-3: a REAL telemetry service claimed to {@code claim} (in memory, no database), so the precedence under test
     * is the shipped one rather than a stub's copy of it: the call's arg, then the declared header, never the claim.
     */
    private ContextServerClient clientWithClaim(String claim) {
        ContextServerClient c = new ContextServerClient()
        c.contextServerUrl = "http://127.0.0.1:${server.address.port}".toString()
        c.structurePersistEnabled = true
        FilesystemTelemetryService real = new FilesystemTelemetryService()
        real.claimSession(claim, 'grp')
        c.telemetryService = real
        c
    }

    private boolean waitForSeen(int n, long ms) {
        long end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (seen.size() >= n) return true; Thread.sleep(20) }
        return seen.size() >= n
    }

    def 'CH-4 (RED, X86 P3-4): work queued for the writer thread carries the CALLER session, not the process claim'() {
        given: 'this process claims another chat (Desktop shares one stdio set between chats)'
        ContextServerClient c = clientWithClaim('claim-Y')

        when: 'a chat call naming callerSession=chat-X triggers a re-index, which runs on the writer thread'
        CallerSession.setArg('chat-X')
        try { c.reindexFileAsync('C:/tmp/x.groovy') } finally { CallerSession.clear() }

        then:
        waitForSeen(1, 5000)
        seen == ['chat-X']
    }

    // X86 P3-3: inverted. The second task, submitted with no caller session, used to go out under the process claim
    // (claim-Y). The claim is no attribution source: it goes out with no session header (X-Mcp-Actor names FS instead).
    def 'CH-5 CONTROL (X86 P3-4, inverted P3-3): a carried session does not leak into the next task, and the claim does not stand in'() {
        given:
        ContextServerClient c = clientWithClaim('claim-Y')

        when: 'one task submitted for chat-X, then one submitted with no caller session at all'
        CallerSession.setArg('chat-X')
        try { c.reindexFileAsync('C:/tmp/a.groovy') } finally { CallerSession.clear() }
        c.reindexFileAsync('C:/tmp/b.groovy')

        then:
        waitForSeen(2, 5000)
        seen == ['chat-X', '<none>']
    }

    def 'CH-6 (X86 P3-3): a flow node declared header session is carried to the writer thread too'() {
        given:
        ContextServerClient c = clientWithClaim('claim-Y')

        when: 'a companion call declaring header-H (no arg) queues a re-index'
        AwCallContext.set('run-1', 'node-1', 'header-H')
        try { c.reindexFileAsync('C:/tmp/h.groovy') } finally { AwCallContext.clear() }

        then:
        waitForSeen(1, 5000)
        seen == ['header-H']
    }

    def 'CH-3: with no active session no header is invented'() {
        when:
        client(null).postWithTimeout('{"jsonrpc":"2.0","id":1,"method":"tools/call"}', 2000)

        then:
        seen == ['<none>']
    }
}
