package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import spock.lang.Specification
import spock.lang.Title

/**
 * FS-EXEC-3 -- execute action=groovy has been completely dead, and nothing noticed.
 *
 * EVERY groovy script failed with:
 *   No such property: scriptOutput for class: Script1
 * including `println 'hello'`. Found by accident on 2026-09-21 while rewriting a flow
 * template; action=python and action=powershell were used instead and the whole backend
 * stayed dark. Chain 816b2861.
 *
 * TWO defects, and the second survives a fix to the first.
 *
 * (1) SecureMcpScript.getScriptOutput() is PRIVATE. `println` is declared on
 *     SecureMcpScript but dispatches on the runtime class -- the user's compiled
 *     Script1, which extends it. Groovy resolves `scriptOutput` as a property against
 *     the metaclass of Script1, where a private superclass getter is not visible. Hence
 *     "No such property ... for class: Script1" rather than an access error.
 *     Introduced by e259442 (v0.7.1), so this has been broken for roughly forty minor
 *     versions.
 *
 * (2) doGroovy never READS the buffer. It returns `result.toString()` -- the script's
 *     last expression -- and ignores the scriptOutput binding entirely. So even with (1)
 *     fixed, `println 'hello'` returns an empty string: the output capture the base class
 *     exists to provide is written and then thrown away.
 *
 * WHY IT STAYED DEAD: there was no spec for action=groovy. Four ExecuteService specs
 * existed -- async, multiline, native-command, stream-capture -- and not one ran a Groovy
 * script. A backend with no test can go dark and stay dark.
 *
 * These specs MUST FAIL on today's code.
 */
@Title('ExecuteService -- FS-EXEC-3 the groovy runner')
class ExecuteServiceGroovySpec extends Specification {

    ExecuteService service
    String workDir = System.getProperty('java.io.tmpdir')

    def setup() {
        service = new ExecuteService()
        service.enableGroovy = true
        service.maxExecutionTimeSeconds = 30
        CommandWhitelistConfig cfg = new CommandWhitelistConfig()
        cfg.initPatterns()
        service.whitelistConfig = cfg
    }

    private static String payloadOf(def response) {
        def content = response?.result?.content
        return content ? (content[0].text as String) : ''
    }

    def 'FS-EXEC-3: the smallest possible groovy script runs at all'() {
        when: 'the one-liner that failed for forty versions'
        def response = service.doGroovy("println 'hello'", workDir, 30, [:] as Map<String, Object>, 'g-1')
        String out = payloadOf(response)

        then: 'no No-such-property, and the call reports success'
        !out.contains('No such property')
        !out.contains('scriptOutput')
        out.contains('true')
    }

    def 'FS-EXEC-3b: what the script PRINTS is what comes back'() {
        when: 'output is produced by println, not by the last expression'
        def response = service.doGroovy("println 'ALPHA'\nprintln 'BRAVO'\nnull",
                                        workDir, 30, [:] as Map<String, Object>, 'g-2')
        String out = payloadOf(response)

        then: 'both lines are returned -- doGroovy used to discard the buffer entirely'
        out.contains('ALPHA')
        out.contains('BRAVO')
    }

    def 'FS-EXEC-3c: a return value still comes back when nothing is printed'() {
        when: 'the pre-existing behaviour, which must not regress'
        def response = service.doGroovy("2 + 2", workDir, 30, [:] as Map<String, Object>, 'g-3')

        then:
        payloadOf(response).contains('4')
    }

    def 'FS-EXEC-3d: print composes onto the current line'() {
        when:
        def response = service.doGroovy("print 'AB'\nprint 'CD'\nnull",
                                        workDir, 30, [:] as Map<String, Object>, 'g-4')

        then: 'print appends rather than starting a new entry'
        payloadOf(response).contains('ABCD')
    }

    def 'FS-EXEC-3e: workingDir reaches the script'() {
        when: 'the binding the base class exposes as a property'
        def response = service.doGroovy("println workingDir", workDir, 30, [:] as Map<String, Object>, 'g-5')
        String out = payloadOf(response)

        then: 'getWorkingDir() is public and must keep working'
        out.length() > 0
        !out.contains('No such property')
    }

    def 'FS-EXEC-4: an unknown action names the valid set'() {
        when:
        def response = service.handleToolCall('execute',
                [action: 'native', script: 'echo hi'] as Map<String, Object>, 'g-6')
        String out = payloadOf(response)

        then: 'the error enumerates, as file_read and file_write do'
        out.contains('Unknown execute action')
        ExecuteService.VALID_EXECUTE_ACTIONS.every { out.contains(it) }
    }

    // FS-EXEC-4b WAS HERE AND HAS BEEN DELETED. It claimed to prove that every name in
    // VALID_EXECUTE_ACTIONS actually dispatches. Its mutation check -- remove `case 'python'`
    // from the switch and leave 'python' advertised -- STAYED GREEN, twice, for two different
    // reasons: unwired, every script action NPE'd before the switch; wired, every script
    // action is refused by working-directory validation before the switch. Both refusals
    // satisfy "the payload does not say Unknown execute action", so the case was true of its
    // fixture and blind to its defect.
    //
    // It is deleted rather than weakened because a check that cannot fail is worse than no
    // check: it is a green light over an untested claim. VALID_EXECUTE_ACTIONS is hand-derived
    // from two dispatch points and NOTHING GUARDS THAT -- add a case without adding the name
    // and it is merely unadvertised; add a name without a case and the switch default refuses
    // a caller who did what the error told them. Covering it needs a fixture that reaches the
    // switch, i.e. a working directory that survives normalizePath + isPathAllowed, which no
    // ExecuteService spec has ever had -- all five construct the service with a null
    // PathService. That is the real gap and it is bigger than this one list.
}
