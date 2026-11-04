package com.softwood.mcp.service

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList

/**
 * FS 0.9.120 (handoff 2026-11-04 step 1) -- the read de-dup ledger is keyed on the chat that made the call.
 *
 * <p>Desktop runs ONE stdio FS process for every chat, so its claim names whichever chat claimed last. If the ledger
 * were keyed on the claim, chat B would be told {unchanged:true} for a file only chat A was ever sent -- content B
 * does not hold. Every ledger call (rangeCoverage, servedKeySeen, recordRangeCacheAsync, recordServedKeyAsync) resolves
 * its session on the CALLER's thread, where {@link CallerSession} holds the call's callerSession; the record is then
 * posted from the async writer with that id already fixed. Asserted on what reaches CS, against a stub CS that keys
 * the ledger by sessionId as the real one does.</p>
 *
 * <ul>
 *   <li>SL-1: a range recorded under A is held for A, not for B, and not for the claim C</li>
 *   <li>SL-2: the record that reaches CS names A, not the claim -- the async writer does not re-resolve it</li>
 *   <li>SL-3: a served key (get_method, grep) recorded under A is seen for A only</li>
 * </ul>
 */
@CompileDynamic
class ServedLedgerPerSessionSpec extends Specification {

    static final String A = '2026-11-04-10-00'
    static final String B = '2026-11-04-11-00'
    static final String C = '2026-11-04-12-00'
    static final String FILE = 'C:/x/Foo.groovy'
    static final String HASH = 'abcdef123456'

    HttpServer server
    final List<Map> bodies = new CopyOnWriteArrayList<Map>()
    ContextServerClient client

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/rangeCache') { ex ->
            Map body = new JsonSlurper().parseText(ex.requestBody.getText('UTF-8')) as Map
            bodies << body
            Map out = [:]
            switch (body.action) {
                case 'coverage':
                    out.intervals = bodies.findAll { it.action == 'record' && it.sessionId == body.sessionId &&
                            it.sourceFile == body.sourceFile && it.contentHash == body.fileHash }
                        .collect { [it.startLine, it.endLine] }
                    break
                case 'seen_check':
                    out.seen = bodies.any { it.action == 'seen_record' && it.sessionId == body.sessionId &&
                            it.sourceFile == body.sourceFile && it.servedKey == body.servedKey && it.fileHash == body.fileHash }
                    break
            }
            byte[] b = JsonOutput.toJson(out).getBytes('UTF-8')
            ex.sendResponseHeaders(200, b.length)
            ex.responseBody.withStream { it.write(b) }
        }
        server.start()

        FilesystemTelemetryService svc = new FilesystemTelemetryService()
        svc.@claimedSessionRef.set(C)
        svc.@sessionClaimed.set(true)

        client = new ContextServerClient()
        client.contextServerUrl = "http://127.0.0.1:${server.address.port}".toString()
        client.structurePersistEnabled = true
        client.telemetryService = svc
    }

    def cleanup() {
        CallerSession.clear()
        server?.stop(0)
    }

    private <T> T under(String session, Closure<T> work) {
        String prev = CallerSession.setArg(session)
        try { return work.call() } finally { CallerSession.setArg(prev) }
    }

    private boolean arrived(String action, int n) {
        long until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            if (bodies.count { it.action == action } >= n) return true
            Thread.sleep(20)
        }
        return false
    }

    def 'SL-1: a range recorded under A is held for A, not for B, not for the claim'() {
        when:
        under(A) { client.recordRangeCacheAsync(FILE, 1, 40, HASH) }
        boolean recorded = arrived('record', 1)

        then:
        recorded
        under(A) { client.rangeCoverage(FILE, HASH) } == [[1, 40]]
        under(B) { client.rangeCoverage(FILE, HASH) } == []
        under(null) { client.rangeCoverage(FILE, HASH) } == []
    }

    def 'SL-2: the record that reaches CS names A, not the claim'() {
        when:
        under(A) { client.recordRangeCacheAsync(FILE, 1, 40, HASH) }
        boolean recorded = arrived('record', 1)

        then:
        recorded
        bodies.find { it.action == 'record' }.sessionId == A
    }

    def 'SL-3: a served key recorded under A is seen for A only'() {
        when:
        under(A) { client.recordServedKeyAsync(FILE, 'method:foo', HASH) }
        boolean recorded = arrived('seen_record', 1)

        then:
        recorded
        bodies.find { it.action == 'seen_record' }.sessionId == A
        under(A) { client.servedKeySeen(FILE, 'method:foo', HASH) }
        !under(B) { client.servedKeySeen(FILE, 'method:foo', HASH) }
        !under(null) { client.servedKeySeen(FILE, 'method:foo', HASH) }
    }
}
