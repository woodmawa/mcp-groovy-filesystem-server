package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.ExecuteService
import com.softwood.mcp.service.FileLifecycleService
import com.softwood.mcp.service.FileListService
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileSearchService
import com.softwood.mcp.service.FileWriteService
import com.softwood.mcp.service.ToolHandler
import com.softwood.mcp.service.ToolsService
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.Title
import spock.lang.Unroll

/**
 * WP1 (decisions 382/383, Will 2026-10-19): FS refuses a key its tool does not read, before dispatch.
 *
 * Until now every FS tool dropped an unknown key silently -- top level or inside options -- and answered as if it
 * had not been sent: the soft-accept the assessment found is never noticed under pressure. A ledger of reads (CS's
 * R2) cannot work here: file_read iterates the top-level args and copies options by design (FS 0.9.46 promotion),
 * so a recording map sees a bulk read on every call. The guard is schema-derived instead: after FS's own case-fix
 * and promotion, a key the tool's declared inputSchema does not have -- and no code of that tool reads -- is
 * refused UNREAD_ARG, nothing runs. Chat stdio process only; the companion keeps today's behaviour.
 *
 * Handlers are stubs that carry the REAL tool definitions, so the guard is judged against the live schemas and the
 * stub's call count is the layer that would act.
 *
 * AG-1  an unknown options key on file_list is refused UNREAD_ARG and the handler is not called (RED today)
 * AG-2  an unknown top-level key on file_search is refused (RED today)
 * AG-3  file_read action=range with options.knownHash is refused KNOWNHASH_ON_RANGE (RED today)
 * AG-4  CONTROL: allowUnread=true passes the same call through
 * AG-5  CONTROL: the companion (refuse off) passes it through
 * AG-6  CONTROL: a tool whose schema declares no properties is not checked
 * AG-7  CONTROL: file_read keeps its 0.9.46 tolerance -- options.startline (case) and top-level startLine pass
 * AG-8  CONTROL: knownHash on action=read passes
 * AG-9  CONTROL (coverage): every key a tool's own source reads -- top level and options -- passes the guard, so
 *       no working call is refused. Keys come from scanning the tool's source; the assertion is on runtime dispatch.
 */
@Title('WP1 -- FS refuses a key its tool does not read, before dispatch')
@CompileDynamic
class UnreadArgSchemaGuardSpec extends Specification {

    int calls = 0

    /** Set a seam only if it exists, so today's code goes red on behaviour, not on a missing property. */
    private static void seam(Object o, String p, Object v) { if (o.hasProperty(p)) o."$p" = v }

    private static Map defOf(ToolHandler real, String tool) {
        real.getToolDefinitions().find { it.name == tool } as Map
    }

    private ToolHandler stubFor(Map toolDef) {
        ToolHandler h = Stub(ToolHandler)
        h.getToolDefinitions() >> [toolDef]
        h.canHandle(_) >> true
        h.handleToolCall(_, _, _) >> { calls++; McpResponse.success(1, [content: [[type: 'text', text: '{"ok":true}']]]) }
        h
    }

    private McpController controller(Map toolDef, boolean refuse = true) {
        McpController c = new McpController([stubFor(toolDef)] as List<ToolHandler>)
        c.globalResponseCapChars = 64000
        seam(c, 'refuseUnreadArgs', refuse)
        c
    }

    /** Not named call(): inside a closure that resolves to Closure.call (obs 12953). */
    private static McpResponse send(McpController c, String tool, Map args) {
        c.handleRequest(new McpRequest(id: 1, method: 'tools/call', params: [name: tool, arguments: args]))
    }

    private static String text(McpResponse r) {
        ((r.result?.get('content') as List)?.first() as Map)?.get('text') as String
    }

    def 'AG-1: an unknown options key on file_list is refused and the handler is not called'() {
        given:
        McpController c = controller(defOf(new FileListService(), 'file_list'))

        when:
        McpResponse r = send(c, 'file_list', [action: 'children', path: 'C:/tmp', options: [limit: 5]])

        then:
        text(r).startsWith('UNREAD_ARG')
        text(r).contains('options.limit')
        calls == 0
    }

    def 'AG-2: an unknown top-level key on file_search is refused'() {
        given:
        McpController c = controller(defOf(new FileSearchService(), 'file_search'))

        when:
        McpResponse r = send(c, 'file_search', [action: 'content', path: 'C:/tmp', contentPattern: 'x'])

        then:
        text(r).startsWith('UNREAD_ARG')
        text(r).contains('contentPattern')
        calls == 0
    }

    def 'AG-3: knownHash on action=range is refused KNOWNHASH_ON_RANGE'() {
        given:
        McpController c = controller(defOf(new FileReadService(), 'file_read'))

        when:
        McpResponse r = send(c, 'file_read', [action: 'range', path: 'C:/tmp/x.groovy',
                                              options: [startLine: 1, maxLines: 5, knownHash: 'abcdef123456']])

        then:
        text(r).startsWith('KNOWNHASH_ON_RANGE')
        calls == 0
    }

    def 'AG-4: CONTROL -- allowUnread=true passes the call through'() {
        given:
        McpController c = controller(defOf(new FileListService(), 'file_list'))

        when:
        send(c, 'file_list', [action: 'children', path: 'C:/tmp', options: [limit: 5], allowUnread: true])

        then:
        calls == 1
    }

    def 'AG-5: CONTROL -- the companion passes the call through'() {
        given:
        McpController c = controller(defOf(new FileListService(), 'file_list'), false)

        when:
        send(c, 'file_list', [action: 'children', path: 'C:/tmp', options: [limit: 5]])

        then:
        calls == 1
    }

    def 'AG-6: CONTROL -- a schema with no properties is not checked'() {
        given:
        McpController c = controller([name: 'file_list', description: 'stub', inputSchema: [:]])

        when:
        send(c, 'file_list', [action: 'children', anything: 1, options: [limit: 5]])

        then:
        calls == 1
    }

    def 'AG-7: CONTROL -- file_read keeps its case-fix and top-level promotion'() {
        given:
        McpController c = controller(defOf(new FileReadService(), 'file_read'))

        when:
        send(c, 'file_read', [action: 'range', path: 'C:/tmp/x.groovy', options: [startline: 3, maxLines: 5]])
        send(c, 'file_read', [action: 'range', path: 'C:/tmp/x.groovy', startLine: 3, endLine: 9])

        then:
        calls == 2
    }

    def 'AG-8: CONTROL -- knownHash on action=read passes'() {
        given:
        McpController c = controller(defOf(new FileReadService(), 'file_read'))

        when:
        send(c, 'file_read', [action: 'read', path: 'C:/tmp/x.groovy', options: [knownHash: 'abcdef123456']])

        then:
        calls == 1
    }

    // ---- AG-9: coverage. Keys come from the tool's own source; the judgement is runtime dispatch. ----

    static final File SERVICE_DIR = new File(System.getProperty('user.dir'),
        'src/main/groovy/com/softwood/mcp/service')

    /** Which sources implement each tool: its service plus the subpackages it delegates to. */
    static final Map<String, List<String>> SOURCES = [
        file_read     : ['FileReadService.groovy', 'read/', 'office/'],
        file_write    : ['FileWriteService.groovy', 'write/', 'transform/', 'office/'],
        execute       : ['ExecuteService.groovy', 'ScriptLint.groovy'],
        file_list     : ['FileListService.groovy'],
        file_search   : ['FileSearchService.groovy'],
        file_lifecycle: ['FileLifecycleService.groovy'],
        tools         : ['ToolsService.groovy'],
    ]

    static final Map<String, Closure<ToolHandler>> REAL = [
        file_read     : { new FileReadService() },
        file_write    : { new FileWriteService() },
        execute       : { new ExecuteService() },
        file_list     : { new FileListService() },
        file_search   : { new FileSearchService() },
        file_lifecycle: { new FileLifecycleService() },
        tools         : { new ToolsService() },
    ]

    static final Set<String> NOT_KEYS = ['get', 'containsKey', 'getOrDefault', 'put', 'remove', 'keySet', 'entrySet',
        'size', 'isEmpty', 'each', 'findAll', 'collect', 'values', 'clone', 'putAll', 'asImmutable'] as Set

    /** [top: keys read off arguments, options: keys read off options] for one tool. */
    static Map<String, Set<String>> codeReads(String tool) {
        Set<String> top = new TreeSet<String>(), opt = new TreeSet<String>()
        SOURCES[tool].each { String part ->
            File f = new File(SERVICE_DIR, part)
            List<File> files = []
            if (f.isDirectory()) f.eachFileRecurse { File g -> if (g.name.endsWith('.groovy')) files << g }
            else if (f.exists()) files << f
            files.each { File g ->
                String src = g.text
                src.findAll(~/\b(?:options|opts)\??\.(?:get\('|containsKey\('|getOrDefault\(')?([A-Za-z_]\w*)/) { m, String k -> if (!NOT_KEYS.contains(k)) opt << k }
                src.findAll(~/\barguments\??\.(?:get\('|containsKey\(')?([A-Za-z_]\w*)/) { m, String k -> if (!NOT_KEYS.contains(k)) top << k }
            }
        }
        [top: top - (['options'] as Set), options: opt]
    }

    @Unroll
    def 'AG-9: CONTROL -- every key #tool reads in its own source passes the guard'() {
        given:
        Map<String, Set<String>> reads = codeReads(tool)
        McpController c = controller(defOf(REAL[tool].call(), tool))
        List<String> refused = []

        when:
        reads.top.each { String k ->
            int before = calls
            send(c, tool, [(k): 'v'])                       // alone: a refusal can only be about k
            if (calls == before) refused << k
        }
        reads.options.each { String k ->
            int before = calls
            send(c, tool, [options: [(k): 'v']])
            if (calls == before) refused << ('options.' + k)
        }

        then: 'the scan found something to check, and nothing the code reads was refused'
        !reads.options.isEmpty() || !reads.top.isEmpty()
        refused == []

        where:
        tool << SOURCES.keySet().toList()
    }
}
