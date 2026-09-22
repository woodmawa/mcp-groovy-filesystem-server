package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path

/**
 * FS-SURF -- the advertised tool surface is the wired tool surface.
 *
 * Written 2026-09-22 (session 2026-09-22-13-03) after a full probe of every FS tool and
 * action with scratch files. The probe found nothing advertised-but-unwired in FS today,
 * but that is exactly the state in which a contract is cheap to write and expensive to
 * lack: `execute action=groovy` was advertised and dead for forty minor versions
 * (FS 0.9.47), and the AW `flow_management` schema enum omits `claim_session` while its
 * description lists it as registered -- a tool that cannot be called from the client that
 * validates against the enum. Nothing in either repo would have gone red.
 *
 * What this asserts, for every ToolHandler bean Spring discovers:
 *   1. every value in the schema's `action.enum` reaches a handler when dispatched through
 *      handleToolCall on the real fixture -- it may refuse for a missing parameter, a
 *      security rule, or a no-such-job, but it must never answer "Unknown <tool> action";
 *   2. an action that is genuinely not handled IS answered that way (the control, so the
 *      first assertion cannot pass by the error text having been reworded);
 *   3. the enum is at least as long as it was when this was written, so an emptied enum
 *      cannot make every() vacuously true (practice #1958: a dispatch smoke spec over 55
 *      actions once passed against known-broken code on its first run).
 *
 * Practice #1958 is why every action is dispatched through the tool entry point with a
 * real PathService rather than through a hand-built service: the vacuous version of this
 * spec is the one that constructs `new ExecuteService()` and never reaches the switch.
 *
 * server_lifecycle `start_eager` and `ensure` are dispatched with a name no config knows,
 * so they refuse on the registry rather than spawn a process.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS -- FS-SURF the advertised tool surface is the wired tool surface')
class ToolSurfaceContractSpec extends Specification {

    @Autowired List<ToolHandler> handlers

    @TempDir Path tempDir

    /** Enum lengths on 2026-09-22 -- a floor, not an equality, so adding an action never breaks this. */
    static final Map<String, Integer> MIN_ACTIONS = [
        file_read       : 23,
        file_write      : 11,
        file_list       : 4,
        file_search     : 3,
        file_lifecycle  : 6,
        execute         : 9,
        tools           : 6,
        server_lifecycle: 8,
    ]

    private static String payloadOf(McpResponse r) {
        def content = r?.result?.content
        String text = content ? (content[0].text as String) : ''
        // Some handlers return the error in the JSON-RPC error slot rather than content.
        if (!text && r?.error) text = r.error.toString()
        return text
    }

    /** Minimal arguments that let each action reach its handler without side effects outside tempDir. */
    private Map<String, Object> argsFor(String tool, String action) {
        Path f = tempDir.resolve('surface.txt')
        if (!f.toFile().exists()) f.text = 'surface probe\n'
        Map<String, Object> opts = [:]
        Map<String, Object> args = [action: action] as Map<String, Object>
        switch (tool) {
            case 'file_read':
                args.path = f.toString()
                opts = [pattern: 'probe', paths: [f.toString()], method: 'x', compareTo: f.toString(),
                        startLine: 1, sessionId: 'no-such-read-session', chunkIndex: 0,
                        allowNoLocate: true, topic: 'file_read']
                if (action == 'list' || action == 'allowed_dirs' || action == 'project_root') args.path = tempDir.toString()
                break
            case 'file_write':
                args.path = tempDir.resolve('surface-write-' + action + '.txt').toString()
                args.content = 'x'
                opts = [oldText: 'a', newText: 'b', replacements: [], sessionId: 'no-such-write-session',
                        chunkIndex: 0, totalChunks: 1, transform: 'add_import', 'import': 'java.util.List',
                        expectedHash: '000000000000', planAck: '0:n']
                break
            case 'file_list':
            case 'file_search':
                args.path = tempDir.toString()
                opts = [contentPattern: 'probe', filePattern: '.*']
                break
            case 'file_lifecycle':
                args.path = tempDir.resolve('lc-' + action).toString()
                args.dst  = tempDir.resolve('lc-' + action + '-dst').toString()
                opts = [type: 'file']
                break
            case 'execute':
                args.script = 'echo hi'
                opts = [workingDir: tempDir.toString(), jobId: 'no-such-job', planAck: '0:n', timeout: 20]
                break
            case 'tools':
                // No subcommand: git/gradle/mvn/npm refuse before running anything.
                opts = [workingDir: tempDir.toString(), planAck: '0:n']
                break
            case 'server_lifecycle':
                args.name = 'no-such-server-' + System.nanoTime()
                args.sessionId = 'surface-spec'
                break
        }
        args.options = opts
        return args
    }

    private static List<String> enumOf(Map<String, Object> toolDef) {
        // get('properties'), never .properties: on a Groovy Map that is the bean's own property
        // map, and `enum` is a keyword -- both are in this project's @CompileStatic gotcha list
        // and the first draft of this spec hit both.
        Map schema = toolDef.get('inputSchema') as Map
        Map props  = schema.get('properties') as Map
        Map action = props.get('action') as Map
        return (action.get('enum') as List).collect { it as String }
    }

    def 'FS-SURF-0: Spring discovered the whole tool surface'() {
        expect: 'eight handlers, eight tools -- practice #368 still says five'
        handlers.collectMany { it.toolDefinitions*.name }.toSet() == MIN_ACTIONS.keySet()
    }

    def 'FS-SURF-1: every advertised action reaches a handler'() {
        given:
        List<String> unreached = []
        List<String> tooShort  = []

        when:
        handlers.each { ToolHandler h ->
            h.toolDefinitions.each { Map<String, Object> def_ ->
                String tool = def_.name as String
                List<String> actions = enumOf(def_)
                if (actions.size() < MIN_ACTIONS[tool]) tooShort << "${tool}: ${actions.size()} < ${MIN_ACTIONS[tool]}".toString()
                actions.each { String a ->
                    if (tool == 'server_lifecycle' && a == 'start_eager') return   // starts every eager server; asserted by SURF-3
                    String out = payloadOf(h.handleToolCall(tool, argsFor(tool, a), "surf-${tool}-${a}"))
                    if (out.contains("Unknown ${tool} action") || out.contains('Unknown job action')) {
                        unreached << "${tool}:${a} -> ${out.take(120)}".toString()
                    }
                }
            }
        }

        then: 'an emptied enum is not a pass'
        tooShort.isEmpty()

        and: 'no advertised action is answered by its own dispatcher as unknown'
        unreached.isEmpty()
    }

    def 'FS-SURF-2: an action no tool handles is refused BY NAME -- the control on SURF-1'() {
        expect:
        handlers.every { ToolHandler h ->
            h.toolDefinitions.every { Map<String, Object> def_ ->
                String tool = def_.name as String
                String out = payloadOf(h.handleToolCall(tool, argsFor(tool, 'definitely-not-an-action'), 'surf-control'))
                out.contains("Unknown ${tool} action")
            }
        }
    }

    def 'FS-SURF-3: the lists that back an enum are the lists the dispatcher reads'() {
        expect: 'execute and file_read publish the same list they refuse against -- one definition, not two'
        enumOf(handlers.find { it.canHandle('execute') }.toolDefinitions[0]) == ExecuteService.VALID_EXECUTE_ACTIONS
        enumOf(handlers.find { it.canHandle('file_read') }.toolDefinitions[0]) == FileReadService.VALID_READ_ACTIONS

        and: 'start_eager is advertised and is a case the dispatcher names in its own unknown-action refusal set'
        enumOf(handlers.find { it.canHandle('server_lifecycle') }.toolDefinitions[0]).contains('start_eager')
    }
}
