package com.softwood.mcp.controller

import com.softwood.mcp.ProcessIdentity
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ContextServerClient
import com.softwood.mcp.service.ExecuteService
import com.softwood.mcp.service.ToolDescriptionRegistry
import com.softwood.mcp.service.ToolHandler
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.function.LongSupplier

/**
 * Align kit 2026-09-25 -- one tool-description source, served live.
 *
 * <p>Every top-level FS tool description is read from CS help_sections key tool_desc_&lt;tool&gt; on
 * tools/list (TTL cache), falls back to last-good then to the SOURCE text, is announced with
 * capabilities.tools.listChanged, and is reported to CS as {tool, 12-hex sha256} via
 * context_lifecycle action=record_tool_descriptions. Asserted at the McpController layer -- the one
 * both transports (StdioMcpServer and HttpMcpController) dispatch through -- on what tools/list
 * actually returns.</p>
 */
class ToolDescriptionLiveSpec extends Specification {

    /** CS stand-in: rows by section key, a down switch, and a record of every report. */
    static class StubCs extends ContextServerClient {
        Map<String, String> rows = [:]
        boolean down = false
        boolean reportAccepted = true
        List<String> fetched = []
        List<Map> reports = []

        @Override
        String getHelpSection(String key) {
            fetched << key
            return down ? null : rows[key]
        }

        @Override
        boolean recordToolDescriptions(String server, String ownerKey, String transport,
                                       List<Map<String, String>> descriptions) {
            reports << [server: server, owner_key: ownerKey, transport: transport, descriptions: descriptions]
            return reportAccepted
        }
    }

    /** Two tools with source-text descriptions, as every FS handler has. */
    static class FakeHandler implements ToolHandler {
        List<Map<String, Object>> getToolDefinitions() {
            [[name: 'file_list', description: 'SOURCE list text', inputSchema: [type: 'object']],
             [name: 'execute', description: 'SOURCE execute text', inputSchema: [type: 'object']]] as List<Map<String, Object>>
        }
        boolean canHandle(String n) { n in ['file_list', 'execute'] }
        McpResponse handleToolCall(String n, Map<String, Object> a, Object id) { McpResponse.success(id, [:]) }
    }

    long now = 1_000_000L
    StubCs cs = new StubCs()
    ToolDescriptionRegistry registry
    McpController controller

    def setup() {
        registry = new ToolDescriptionRegistry()
        registry.contextServerClient = cs
        registry.ttlMs = 45_000L
        registry.clock = { -> now } as LongSupplier
        controller = new McpController([new FakeHandler()] as List<ToolHandler>)
        controller.toolDescriptionRegistry = registry
        controller.registerDescriptionDefaults()
    }

    private Map<String, String> listed() {
        McpResponse r = controller.handleRequest(new McpRequest(id: 'l', method: 'tools/list', params: [:]))
        assert r.error == null
        (r.result.tools as List<Map>).collectEntries { Map t -> [(t.name as String): t.description as String] }
    }

    private static String sha12(String s) {
        MessageDigest.getInstance('SHA-256').digest(s.getBytes(StandardCharsets.UTF_8))
            .collect { String.format('%02x', it) }.join().substring(0, 12)
    }

    def 'TD-1: initialize advertises capabilities.tools.listChanged = true'() {
        when:
        McpResponse r = controller.handleRequest(new McpRequest(id: 'i', method: 'initialize',
            params: [protocolVersion: '2025-06-18']))

        then:
        ((r.result.capabilities as Map).tools as Map).listChanged == true
    }

    def 'TD-2: tools/list serves the CS row text; a tool with no row serves its source text'() {
        given:
        cs.rows['tool_desc_file_list'] = 'ROW list text'

        expect:
        listed() == [file_list: 'ROW list text', execute: 'SOURCE execute text']
        cs.fetched.containsAll(['tool_desc_file_list', 'tool_desc_execute'])
    }

    def 'TD-3: a changed row is served after the TTL without a restart, and not before'() {
        given:
        cs.rows['tool_desc_file_list'] = 'ROW v1'
        assert listed().file_list == 'ROW v1'

        when: 'the row is edited and less than the TTL passes'
        cs.rows['tool_desc_file_list'] = 'ROW v2'
        now += 10_000L

        then: 'the cached text is still served'
        listed().file_list == 'ROW v1'

        when: 'the TTL expires'
        now += 40_000L

        then:
        listed().file_list == 'ROW v2'
    }

    def 'TD-4: CS unreachable -- DEFAULT text at first, last good text later, tools/list never fails'() {
        given: 'CS down before any fetch ever succeeded'
        cs.down = true

        expect: 'source DEFAULT, never empty'
        listed() == [file_list: 'SOURCE list text', execute: 'SOURCE execute text']

        when: 'CS comes up with a row, then goes down again past the TTL'
        cs.down = false
        cs.rows['tool_desc_file_list'] = 'ROW good'
        now += 50_000L
        assert listed().file_list == 'ROW good'
        cs.down = true
        now += 50_000L

        then: 'the last good text is served, not the default and not empty'
        listed() == [file_list: 'ROW good', execute: 'SOURCE execute text']
    }

    def 'TD-5: CS throwing does not fail tools/list'() {
        given:
        ContextServerClient boom = new ContextServerClient() {
            @Override String getHelpSection(String key) { throw new IllegalStateException('boom') }
        }
        registry.contextServerClient = boom

        expect:
        listed() == [file_list: 'SOURCE list text', execute: 'SOURCE execute text']
    }

    def 'TD-6: file_write in verbose mode reads tool_desc_file_write_verbose; everything else tool_desc_<tool>'() {
        expect:
        ToolDescriptionRegistry.sectionKeyFor('file_write', 'verbose') == 'tool_desc_file_write_verbose'
        ToolDescriptionRegistry.sectionKeyFor('file_write', 'compact') == 'tool_desc_file_write'
        ToolDescriptionRegistry.sectionKeyFor('server_lifecycle', 'verbose') == 'tool_desc_server_lifecycle'
    }

    def 'TD-7: the poll reports {server:fs, owner_key, transport, descriptions:[{tool, hash}]} with the served hashes'() {
        given:
        registry.transportMode = 'stdio'
        cs.rows['tool_desc_file_list'] = 'ROW list text'
        Map<String, String> served = listed()

        when:
        registry.pollOnce()

        then:
        cs.reports.size() == 1
        Map rep = cs.reports[0]
        rep.server == 'fs'
        rep.owner_key == ProcessIdentity.OWNER_KEY
        rep.transport == 'stdio'
        (rep.descriptions as List<Map>).collectEntries { [(it.tool): it.hash] } ==
            [file_list: sha12(served.file_list), execute: sha12(served.execute)]
        (rep.descriptions as List<Map>).every { (it.hash as String) ==~ /[0-9a-f]{12}/ }

        when: 'nothing changed'
        registry.pollOnce()

        then: 'no second report'
        cs.reports.size() == 1

        when: 'the row changes'
        cs.rows['tool_desc_file_list'] = 'ROW list text v2'
        registry.pollOnce()

        then: 'the new hash is reported'
        cs.reports.size() == 2
        (cs.reports[1].descriptions as List<Map>).find { it.tool == 'file_list' }.hash == sha12('ROW list text v2')
    }

    def 'TD-8: the poll fires change listeners only when the served text changes'() {
        given:
        int fired = 0
        registry.addChangeListener({ -> fired++ } as Runnable)
        cs.rows['tool_desc_execute'] = 'ROW exec'
        registry.pollOnce()
        int afterFirst = fired

        when:
        registry.pollOnce()

        then:
        fired == afterFirst

        when:
        cs.rows['tool_desc_execute'] = 'ROW exec v2'
        registry.pollOnce()

        then:
        fired == afterFirst + 1
        listed().execute == 'ROW exec v2'
    }

    def 'TD-9: a CS without the action (report refused) is ignored and retried; tools/list unaffected'() {
        given:
        cs.reportAccepted = false

        when:
        registry.pollOnce()
        registry.pollOnce()

        then: 'retried on the next poll because nothing was accepted'
        noExceptionThrown()
        cs.reports.size() == 2
        listed().execute == 'SOURCE execute text'
    }

    def 'TD-10: real handler source text is the default (ExecuteService)'() {
        given:
        McpController c = new McpController([new ExecuteService()] as List<ToolHandler>)
        c.toolDescriptionRegistry = registry
        c.registerDescriptionDefaults()
        cs.down = true

        when:
        McpResponse r = c.handleRequest(new McpRequest(id: 'l', method: 'tools/list', params: [:]))
        String d = ((r.result.tools as List<Map>)[0]).description

        then:
        d == new ExecuteService().getToolDefinitions()[0].description
        d.startsWith('Execute scripts or shell commands.')
    }
}
