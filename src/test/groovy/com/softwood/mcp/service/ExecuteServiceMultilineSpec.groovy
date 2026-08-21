package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Title

/**
 * FS-EXEC-1 — execute action=cmd must run EVERY line of a multi-line script.
 *
 * doCmd passed the script as ['cmd', '/c', script]. `cmd /c` accepts a SINGLE command, so
 * everything after the first line was discarded — silently, with exitCode 0 and the first
 * command's stdout, which is indistinguishable from full success.
 *
 * Observed 2026-08-21 (observation 9881): a script of `git add <paths>` followed by
 * `git commit -F msg` returned success with only CRLF warnings on stderr. The add ran; the
 * commit never did. The next call, `git push`, then reported "Everything up-to-date" —
 * true, and reading exactly like success. Only `git status -sb` showing the files still
 * staged revealed it. An earlier four-command diagnostic script silently lost three of its
 * four commands.
 *
 * doPowershell and doPython already write the script to a temp file for precisely this class
 * of problem, each with a comment saying so. cmd never got the same treatment.
 *
 * These specs MUST FAIL on today's code.
 */
@Title('ExecuteService — FS-EXEC-1 multi-line script support')
@Requires({ os.windows })
class ExecuteServiceMultilineSpec extends Specification {

    ExecuteService service
    String workDir = System.getProperty('java.io.tmpdir')

    def setup() {
        service = new ExecuteService()
        service.enableCmd = true
        service.enableBash = true
        service.maxExecutionTimeSeconds = 30
        // Permissive whitelist: this spec is about execution semantics, not policy.
        CommandWhitelistConfig cfg = new CommandWhitelistConfig()
        cfg.initPatterns()
        service.whitelistConfig = cfg
    }

    /** McpResponse.result.content[0].text carries the JSON payload runProcess produced. */
    private static String stdoutOf(def response) {
        def content = response?.result?.content
        return content ? (content[0].text as String) : ''
    }

    def 'FS-EXEC-1: every line of a multi-line cmd script runs'() {
        given: 'a three-line script — the shape that silently lost its 2nd and 3rd commands'
        String script = 'echo ALPHA\necho BRAVO\necho CHARLIE'

        when:
        def response = service.doCmd(script, workDir, 30, null, [:] as Map<String, Object>, 'test-1')
        String out = stdoutOf(response)

        then: 'all three ran — today only ALPHA does'
        out.contains('ALPHA')
        out.contains('BRAVO')
        out.contains('CHARLIE')
    }

    def 'FS-EXEC-1b: a failing first line does not hide later output'() {
        given: 'a script whose second line is the one that matters'
        String script = 'echo FIRST\necho SECOND'

        when:
        def response = service.doCmd(script, workDir, 30, null, [:] as Map<String, Object>, 'test-2')
        String out = stdoutOf(response)

        then:
        out.contains('FIRST')
        out.contains('SECOND')
    }

    def 'FS-EXEC-1c: a single-line cmd script still behaves exactly as before'() {
        when:
        def response = service.doCmd('echo SOLO', workDir, 30, null, [:] as Map<String, Object>, 'test-3')
        String out = stdoutOf(response)

        then: 'no regression for the overwhelmingly common single-command case'
        out.contains('SOLO')
    }

    def 'FS-EXEC-1d: cmd script output is not polluted by echoed commands'() {
        when: 'a batch file runs, echo must be off or every command is echoed into stdout'
        def response = service.doCmd('echo MARKER', workDir, 30, null, [:] as Map<String, Object>, 'test-4')
        String out = stdoutOf(response)

        then: 'the literal command text does not appear alongside its output'
        out.contains('MARKER')
        !out.contains('>echo MARKER')
    }
}
