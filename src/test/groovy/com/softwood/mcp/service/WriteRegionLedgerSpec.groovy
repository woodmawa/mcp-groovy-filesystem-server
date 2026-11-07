package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FS 0.9.127 -- read efficiency (a) and (b), handoff 2026-11-07.
 *
 * <p>Measured 2026-11-07: 8.1% of file_read tokens in 7 days went on a file the same session had written in the previous
 * five minutes, and 35 of the 38 files read 4+ times in one session had been edited in it. A write moved the file's
 * hash, the ledger is keyed on the hash, so every edit threw away everything the chat was known to hold.</p>
 *
 * <p>(a) replace, multi_replace, patch and server_transform return the region they changed (new lines plus
 * {@code CONTEXT_LINES} of context, capped at 80, region_truncated above it) and the ledger records that region as served
 * at the NEW hash, before the response returns. (b) the intervals this chat held at the old hash are carried to the new
 * hash, shifted past the edit, so a read afterwards is sent only lines the chat was never given.</p>
 *
 * <p>Asserted on what reaches the stub CS ledger (the record bodies, keyed by sessionId as the real store is) and on what
 * a read returns over the wire -- never on a flag in the write response alone.</p>
 */
@CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class WriteRegionLedgerSpec extends Specification {

    static final String A = '2026-11-07-20-00'
    static final String B = '2026-11-07-21-00'

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService
    @Autowired PathService      pathService

    @TempDir Path tempDir

    HttpServer server
    final List<Map> bodies = new CopyOnWriteArrayList<Map>()
    ContextServerClient client
    ContextServerClient savedWriteClient
    ContextServerClient savedReadClient

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/rangeCache') { HttpExchange ex ->
            Map body = new JsonSlurper().parseText(ex.requestBody.getText('UTF-8')) as Map
            bodies << body
            Map out = [:]
            if (body.action == 'coverage') {
                out.intervals = bodies.findAll { it.action == 'record' && it.sessionId == body.sessionId &&
                        it.sourceFile == body.sourceFile && it.contentHash == body.fileHash }
                    .collect { [it.startLine, it.endLine] }
            }
            reply(ex, out)
        }
        server.createContext('/') { HttpExchange ex -> ex.requestBody.getText('UTF-8'); reply(ex, [:]) }
        server.start()

        FilesystemTelemetryService svc = new FilesystemTelemetryService()
        svc.@claimedSessionRef.set(A)
        svc.@sessionClaimed.set(true)

        client = new ContextServerClient()
        client.contextServerUrl = "http://127.0.0.1:${server.address.port}".toString()
        client.structurePersistEnabled = true
        client.telemetryService = svc

        savedWriteClient = fileWriteService.contextServerClient
        savedReadClient  = fileReadService.@contextServerClient
        fileWriteService.contextServerClient = client
        fileReadService.contextServerClient  = client
        // Every chat call carries callerSession (operating core); the ledger is keyed on it, not on the claim.
        CallerSession.setArg(A)
    }

    def cleanup() {
        fileWriteService.contextServerClient = savedWriteClient
        fileReadService.contextServerClient  = savedReadClient
        CallerSession.clear()
        server?.stop(0)
    }

    // -----------------------------------------------------------------------------------------------------------------

    private static void reply(HttpExchange ex, Map out) {
        byte[] b = JsonOutput.toJson(out).getBytes('UTF-8')
        ex.sendResponseHeaders(200, b.length)
        ex.responseBody.withStream { it.write(b) }
    }

    private File file(String name, int n) {
        File f = tempDir.resolve(name).toFile()
        f.text = (1..n).collect { "line $it" }.join('\n') + '\n'
        return f
    }

    private String p(File f) { pathService.normalizePath(f.absolutePath) }

    private static String hashOf(File f) {
        java.security.MessageDigest.getInstance('SHA-256').digest(f.bytes).encodeHex().toString()[0..11]
    }

    private static Map body(McpResponse r) {
        assert r.error == null
        new JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    private Map read(File f, int start, int max) {
        body(fileReadService.handleToolCall('file_read',
            [action: 'range', path: p(f), options: [startLine: start, maxLines: max]], 'r'))
    }

    private List<Map> records(String hash) { bodies.findAll { it.action == 'record' && it.contentHash == hash } }

    private Map replace(File f, String oldText, String newText) {
        body(fileWriteService.handleToolCall('file_write', [action: 'replace', path: p(f),
            options: [oldText: oldText, newText: newText, expectedHash: hashOf(f)]], 'w'))
    }

    private <T> T under(String session, Closure<T> work) {
        String prev = CallerSession.setArg(session)
        try { return work.call() } finally { CallerSession.setArg(prev) }
    }

    // ---------------------------------------------------------------------------------------------------------- (a)

    def "WR-1: a replace returns the region it changed -- new lines, 3 lines of context, its line numbers, the new hash"() {
        given:
        File f = file('wr1.txt', 20)

        when:
        Map b = replace(f, 'line 10\n', 'line 10a\nline 10b\n')

        then:
        b.region != null
        b.region.startLine == 7
        b.region.endLine == 14
        b.region.changed == '10-11'
        b.region.content == 'line 7\nline 8\nline 9\nline 10a\nline 10b\nline 11\nline 12\nline 13'
        b.region.file_content_hash == hashOf(f)
        b.region.region_truncated == false
    }

    def "WR-2: the region is on the ledger at the NEW hash, for this chat, by the time the write returns"() {
        given:
        File f = file('wr2.txt', 20)
        String oldHash = hashOf(f)

        when:
        replace(f, 'line 10\n', 'line 10a\nline 10b\n')
        String newHash = hashOf(f)

        then: 'no wait: the record is made before the response, so a read straight after cannot overtake it'
        newHash != oldHash
        records(newHash).any { it.sessionId == A && it.sourceFile == p(f) && it.startLine == 7 && it.endLine == 14 }
        records(oldHash).isEmpty()
    }

    def "WR-3: a read of the region straight after the write is answered unchanged; a read outside it is not"() {
        given:
        File f = file('wr3.txt', 20)
        replace(f, 'line 10\n', 'line 10a\nline 10b\n')

        when:
        Map held   = read(f, 7, 8)
        Map fresh  = read(f, 1, 5)

        then:
        held.unchanged == true
        held.content == null
        fresh.unchanged != true
        (fresh.content as String).startsWith('line 1\nline 2')
    }

    def "WR-4: a region over 80 lines is cut at 80, says so, and only the cut part is recorded; the rest is sent on read"() {
        given:
        File f = file('wr4.txt', 20)
        String hundred = (1..100).collect { "new $it" }.join('\n') + '\n'

        when:
        Map b = replace(f, 'line 10\n', hundred)
        String h = hashOf(f)

        then:
        b.region.region_truncated == true
        b.region.startLine == 7
        b.region.endLine == 86
        b.region.lines == 80
        b.region.changed == '10-109'
        records(h).every { it.endLine <= 86 }

        when: 'the chat reads on past the cut'
        Map rest = read(f, 80, 33)

        then: 'only the lines it was never sent come back'
        rest.startLine == 87
        (rest.content as String).startsWith('new 78')
        (rest.already_served as List).any { it == '7-86' || (it as String).startsWith('7-') }
    }

    def "WR-5: multi_replace returns one region spanning its edits"() {
        given:
        File f = file('wr5.txt', 20)

        when:
        Map b = body(fileWriteService.handleToolCall('file_write', [action: 'multi_replace', path: p(f),
            options: [replacements: [[oldText: 'line 10\n', newText: 'X\n'], [oldText: 'line 15\n', newText: 'Y\n']],
                      expectedHash: hashOf(f)]], 'w'))

        then:
        b.region.changed == '10-15'
        b.region.startLine == 7
        b.region.endLine == 18
        records(hashOf(f)).any { it.startLine == 7 && it.endLine == 18 }
    }

    def "WR-6: patch returns its region"() {
        given:
        File f = file('wr6.txt', 20)

        when:
        Map b = body(fileWriteService.handleToolCall('file_write', [action: 'patch', path: p(f),
            options: [replacements: [[startLine: 10, endLine: 10, newText: 'patched 10']], expectedHash: hashOf(f)]], 'w'))

        then:
        b.region != null
        (b.region.content as String).contains('patched 10')
        b.region.file_content_hash == hashOf(f)
        records(hashOf(f)).any { it.sessionId == A }
    }

    def "WR-7: server_transform returns its region"() {
        given:
        File f = file('wr7.md', 20)

        when:
        Map b = body(fileWriteService.handleToolCall('file_write', [action: 'server_transform', path: p(f),
            options: [transform: 'insert_before_match', match: 'line 10', content: 'inserted\n', expectedHash: hashOf(f)]], 'w'))

        then:
        b.region != null
        (b.region.content as String).contains('inserted')
        records(hashOf(f)).any { it.sessionId == A }
    }

    // ---------------------------------------------------------------------------------------------------------- (b)

    def "WR-8: what the chat held before the edit is still held after it, shifted past the edit"() {
        given: 'the chat holds lines 1-20 of a 40-line file'
        File f = file('wr8.txt', 40)
        read(f, 1, 20)

        when: 'one line in the middle becomes two'
        replace(f, 'line 10\n', 'line 10a\nline 10b\n')
        Map again = read(f, 1, 21)

        then: '1-9 unchanged, 10-11 came back with the write, old 11-20 are new 12-21'
        again.unchanged == true

        when: 'the chat reads the whole file'
        Map whole = read(f, 1, 41)

        then: 'only the lines it was never given are sent -- old 21-40, now 22-41'
        whole.startLine == 22
        (whole.content as String).startsWith('line 21')
        whole.already_served != null
    }

    def "WR-9: another chat's holdings are not carried -- it is sent the edited lines in full"() {
        given: 'chat B holds lines 1-20'
        File f = file('wr9.txt', 40)
        under(B) { read(f, 1, 20) }

        when: 'chat A edits'
        replace(f, 'line 10\n', 'line 10a\nline 10b\n')
        Map bRead = under(B) { read(f, 1, 21) }

        then:
        bRead.unchanged != true
        bRead.startLine == 1
        (bRead.content as String).contains('line 10a')
    }

    def "WR-10: an edit that only deletes reports an empty changed span and carries the rest"() {
        given:
        File f = file('wr10.txt', 30)
        read(f, 1, 30)

        when:
        Map b = replace(f, 'line 10\nline 11\n', '')
        Map again = read(f, 1, 28)

        then:
        b.region.changed == 'deleted 2 at 10'
        b.region.startLine == 7
        b.region.endLine == 12
        again.unchanged == true
    }
}
