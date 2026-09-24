package com.softwood.mcp.service

import com.softwood.mcp.ProcessIdentity
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FS 0.9.63 -- a chat FS process's claim brings its own unbound telemetry home.
 *
 * <p>Measured 2026-09-24 (session 2026-09-24-15-06): fs-telemetry-not-stranded-in-unknown read 91.
 * The 66 tools/gradle rows the brief blamed stop at 2026-09-23 13:37 -- FS 0.9.58 already fixed them
 * (X-Mcp-Caller-Session). What still arrives is ~1-2 rows per session from the chat's own FS process:
 * calls made before claim_session, e.g. reading the arc brief while bootstrap runs. 16 of the 93
 * 'unknown' rows were written AFTER the session they belonged to had started.</p>
 *
 * <p>CS has repaired exactly this for its own process since 1.0.73. FS claims by writing its own
 * session_claims row, so it never reached that repair. CS 1.1.34 exposes it as
 * context_lifecycle action=reattribute_claim; this is FS asking.</p>
 *
 * <p>Two properties matter. ORDER: telemetry goes to CS from a single writer thread, so the request
 * must be queued on that same thread -- otherwise it can overtake the very rows it exists to move.
 * WHO: only a chat's own stdio process asks. The HTTP companion serves every chat, so its rows are
 * never one session's to take.</p>
 *
 * CONTRACT (asserted on what arrives over HTTP, practice #1485):
 *  FCR-1  a chat process's claim sends reattribute_claim with the claimed session and its own owner_key
 *  FCR-2  it arrives AFTER a telemetry row recorded before the claim
 *  FCR-3  a companion process's claim sends nothing of the kind
 */
@CompileDynamic
class FsClaimReattributionSpec extends Specification {

    static final String S = '2026-09-24-15-06'

    @TempDir Path tmp

    HttpServer server
    final List<Map> bodies = Collections.synchronizedList(new ArrayList<Map>())
    CountDownLatch latch

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/mcp') { ex ->
            String raw = ex.requestBody.getText('UTF-8')
            Map body = new JsonSlurper().parseText(raw) as Map
            bodies << ((body.params as Map)?.arguments as Map ?: [:])
            byte[] b = '{"jsonrpc":"2.0","id":1,"result":{"content":[]}}'.getBytes('UTF-8')
            ex.sendResponseHeaders(200, b.length)
            ex.responseBody.withStream { it.write(b) }
            latch?.countDown()
        }
        server.start()
    }

    def cleanup() { server?.stop(0) }

    private FilesystemTelemetryService service(boolean companion) {
        String dbPath = tmp.resolve('claims.db').toString().replace((char) 92, (char) 47)
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            conn.createStatement().execute('''CREATE TABLE IF NOT EXISTS session_claims (
                owner_key TEXT PRIMARY KEY, server TEXT NOT NULL, session_id TEXT NOT NULL DEFAULT '',
                group_id TEXT, pid INTEGER, jvm_started_at TEXT,
                claimed_at TEXT NOT NULL DEFAULT (datetime('now')),
                last_seen_at TEXT NOT NULL DEFAULT (datetime('now')))''')
        } finally { conn.close() }

        ContextServerClient client = new ContextServerClient()
        client.contextServerUrl = "http://127.0.0.1:${server.address.port}".toString()

        FilesystemTelemetryService svc = new FilesystemTelemetryService()
        svc.dbPath = dbPath
        svc.contextServerClient = client
        svc.companionOverride = companion
        return svc
    }

    private List<String> actions() { bodies.collect { it.action as String } }

    def 'FCR-1: a chat process claim asks CS to bring its own unknown rows to the claimed session'() {
        given:
        latch = new CountDownLatch(1)
        FilesystemTelemetryService svc = service(false)

        when:
        svc.claimSession(S, 'mcp-servers')

        then:
        latch.await(5, TimeUnit.SECONDS)
        Map ask = bodies.find { it.action == 'reattribute_claim' }
        ask != null
        ask.sessionId == S
        ask.ownerKey == ProcessIdentity.OWNER_KEY
    }

    def 'FCR-2: the ask arrives after telemetry recorded before the claim'() {
        given:
        latch = new CountDownLatch(2)
        FilesystemTelemetryService svc = service(false)

        when: 'a call is recorded while unbound, then the chat claims'
        svc.recordToolCall(null, 'file_read', 100, [path: 'x'] as Map<String, Object>, 'range')
        svc.claimSession(S, 'mcp-servers')

        then:
        latch.await(5, TimeUnit.SECONDS)
        actions() == ['record_tool_call', 'reattribute_claim']
    }

    def 'FCR-3: a companion process never asks -- its rows belong to every chat'() {
        given:
        latch = new CountDownLatch(1)
        FilesystemTelemetryService svc = service(true)

        when:
        svc.claimSession(S, 'mcp-servers')
        svc.recordToolCall(null, 'file_read', 100, [path: 'y'] as Map<String, Object>, 'range')

        then: 'the telemetry row still arrives -- the writer is alive -- but no ask came before it'
        latch.await(5, TimeUnit.SECONDS)
        actions() == ['record_tool_call']
    }
}
