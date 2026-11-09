package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.read.RangeTelemetry
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
 * FS 0.9.127 -- read efficiency (c), handoff 2026-11-07: {@code file_read action=range options.symbol=Class.method}.
 *
 * <p>Measured: 24 locates against 622 ranges in 10 days; 7% of ranges had a locate of the same file in the ten
 * minutes before. A caller guessing line numbers reads windows that miss and re-reads. FS now resolves the symbol
 * itself: CS ontology locate first (one call, attributed to the caller's session so the RE-ranges-preceded-by-locate
 * measure credits it), FS's own structure when locate has no end line, is stale, or names another file, and
 * SYMBOL_NOT_FOUND -- with the locate answer -- when neither knows it.</p>
 */
@CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class SymbolRangeSpec extends Specification {

    static final String A = '2026-11-07-20-00'

    @Autowired FileReadService fileReadService
    @Autowired PathService     pathService

    @TempDir Path tempDir

    HttpServer server
    final Map<String, Map> locates = [:]
    final List<Map> locateCalls = new CopyOnWriteArrayList<Map>()
    ContextServerClient client
    ContextServerClient saved

    static final String SRC = '''class Foo {
    int a() {
        return 1
    }

    String bar(String x) {
        String y = x
        return y
    }
}
'''

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/mcp') { HttpExchange ex ->
            Map body = new JsonSlurper().parseText(ex.requestBody.getText('UTF-8')) as Map
            Map args = (body?.params as Map)?.arguments as Map
            if (args?.scope == 'ontology' && args?.action == 'locate') {
                locateCalls << [query: args.query, session: ex.requestHeaders.getFirst('X-Mcp-Caller-Session')]
                Map data = locates[args.query as String] ?: [found: false, hint: 'No node matched.']
                reply(ex, [jsonrpc: '2.0', id: body.id, result: [content: [[type: 'text', text: JsonOutput.toJson(data)]]]])
            } else {
                reply(ex, [:])
            }
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
        saved = fileReadService.@contextServerClient
        fileReadService.contextServerClient = client
        CallerSession.setArg(A)
    }

    def cleanup() {
        fileReadService.contextServerClient = saved
        CallerSession.clear()
        server?.stop(0)
    }

    private static void reply(HttpExchange ex, Map out) {
        byte[] b = JsonOutput.toJson(out).getBytes('UTF-8')
        ex.sendResponseHeaders(200, b.length)
        ex.responseBody.withStream { it.write(b) }
    }

    private File foo() {
        File f = tempDir.resolve('Foo.groovy').toFile()
        f.text = SRC
        return f
    }

    private String p(File f) { pathService.normalizePath(f.absolutePath) }

    private McpResponse sym(File f, String symbol) {
        fileReadService.handleToolCall('file_read', [action: 'range', path: p(f), options: [symbol: symbol]], 'r')
    }

    private static Map body(McpResponse r) { new JsonSlurper().parseText(r.result.content[0].text as String) as Map }

    def "SR-1: a symbol located by CS is read at exactly its lines, and the locate went to CS as this chat"() {
        given:
        File f = foo()
        locates['Foo.bar'] = [found: true, source_file: p(f), source_line: 6, end_line: 9, symbol_type: 'method']

        when:
        Map b = body(sym(f, 'Foo.bar'))

        then:
        b.startLine == 6
        b.endLine == 9
        (b.content as String).startsWith('    String bar(String x) {')
        (b.content as String).endsWith('    }')
        b.symbol == 'Foo.bar'
        b.resolved_by == 'locate'
        locateCalls.any { it.query == 'Foo.bar' && it.session == A }
    }

    def "SR-2: a locate with no end line falls back to FS's own structure bounds"() {
        given:
        File f = foo()
        locates['Foo.bar'] = [found: true, source_file: p(f), source_line: 6, end_line: 0, symbol_type: 'method']

        when:
        Map b = body(sym(f, 'Foo.bar'))

        then:
        b.startLine == 6
        b.endLine == 9
        b.resolved_by == 'locate+structure'
    }

    def "SR-3: a stale locate (its line no longer holds the symbol) is not trusted -- structure decides"() {
        given:
        File f = foo()
        locates['Foo.bar'] = [found: true, source_file: p(f), source_line: 2, end_line: 4, symbol_type: 'method']

        when:
        Map b = body(sym(f, 'Foo.bar'))

        then:
        b.startLine == 6
        b.endLine == 9
        b.resolved_by == 'structure'
    }

    def "SR-4: a locate naming another file is not used for this one"() {
        given:
        File f = foo()
        locates['Foo.bar'] = [found: true, source_file: 'C:/elsewhere/Foo.groovy', source_line: 2, end_line: 4]

        when:
        Map b = body(sym(f, 'Foo.bar'))

        then:
        b.startLine == 6
        b.resolved_by == 'structure'
    }

    def "SR-5: an unknown symbol is refused SYMBOL_NOT_FOUND, giving the locate answer, and nothing is read"() {
        given:
        File f = foo()

        when:
        McpResponse r = sym(f, 'Foo.nope')
        String text = r.result.content[0].text as String

        then:
        r.result.isError == true
        text.contains('SYMBOL_NOT_FOUND')
        text.contains('No node matched')
        !text.contains('return y')
    }

    def "SR-6: a symbol read held by the ledger is answered like any other range (the de-dup still applies)"() {
        given:
        File f = foo()
        locates['Foo.bar'] = [found: true, source_file: p(f), source_line: 6, end_line: 9]
        server.createContext('/rangeCache') { HttpExchange ex ->
            Map rb = new JsonSlurper().parseText(ex.requestBody.getText('UTF-8')) as Map
            reply(ex, rb.action == 'coverage' ? [intervals: [[1, 10]]] : [:])
        }

        when:
        Map b = body(sym(f, 'Foo.bar'))

        then:
        b.unchanged == true
        b.symbol == 'Foo.bar'
    }

    def "SR-7: the read schema declares symbol, so the call is not refused as an unread argument"() {
        when:
        Map def_ = fileReadService.getToolDefinitions().find { it.name == 'file_read' } as Map
        Map opts  = ((def_.inputSchema as Map).get('properties') as Map).get('options') as Map
        Map props = opts.get('properties') as Map

        then:
        props.containsKey('symbol')
    }

    def "SR-8: range telemetry for a symbol read reports the window that was resolved, not the 1-100 default"() {
        when:
        Map d = RangeTelemetry.detail('file_read', [action: 'range', options: [symbol: 'Foo.bar']],
            '{"startLine":6,"endLine":9,"lines":4,"content":"x"}')

        then:
        d.req_start_line == 6
        d.req_max_lines == 4
        d.lines_returned == 4
    }

    def "SR-9: FS 0.9.128 -- a symbol read's telemetry carries how it was resolved; a line range carries none"() {
        expect:
        RangeTelemetry.detail('file_read', [action: 'range', options: [symbol: 'Foo.bar']],
            '{"startLine":6,"endLine":9,"lines":4,"content":"x","symbol":"Foo.bar","resolved_by":"locate+structure"}').resolved_by == 'locate+structure'
        RangeTelemetry.detail('file_read', [action: 'range', options: [symbol: 'Foo.bar']],
            '{"startLine":6,"endLine":9,"lines":4,"content":"x","resolved_by":"structure"}').resolved_by == 'structure'
        !RangeTelemetry.detail('file_read', [action: 'range', options: [startLine: 6, maxLines: 4]],
            '{"startLine":6,"endLine":9,"lines":4,"content":"x","symbol":"Foo.bar"}').containsKey('resolved_by')
    }
}
