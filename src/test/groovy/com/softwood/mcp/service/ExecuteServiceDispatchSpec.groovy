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
 * FS-EXEC-5 -- the fixture ExecuteService never had.
 *
 * ALL FIVE pre-existing ExecuteService specs construct the service by hand with
 * `new ExecuteService()`, which leaves the final `pathService` field null. Every one of
 * them therefore calls doGroovy/doBash/doCmd DIRECTLY and not one goes through
 * handleToolCall. Everything between the tool entry point and the dispatch switch --
 * working-directory resolution, normalizePath, isPathAllowed, the plan-gate guard,
 * option promotion, the action guard -- has never been asserted by anything.
 *
 * That is not a theoretical gap. On 2026-09-22 it produced a spec (the original
 * FS-EXEC-4b) that PASSED ITS MUTATION CHECK TWICE: with a null PathService every
 * script action NPE'd before the switch, and the NPE message does not contain the
 * string the assertion looked for. The spec was true of its fixture and blind to its
 * defect, and had to be deleted. Practice #1958 is the same failure in CS: a dispatch
 * smoke spec over 55 actions that passed against known-broken code on its first run.
 * A dispatch spec is unusually good at being vacuous, because "nothing complained"
 * is indistinguishable from "it never got there".
 *
 * The fix turned out to be small: @SpringBootTest gives a real PathService and the
 * test profile already allows ${java.io.tmpdir}, which is what @TempDir hands out.
 * FileWriteContractSpec had been doing exactly this for the write side all along.
 *
 * The cases below are ordered so that the first one FAILS LOUDLY if the fixture ever
 * stops reaching dispatch -- without it the two after it would go quietly vacuous
 * again, which is the whole reason this file has a header this long.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('ExecuteService -- FS-EXEC-5 the dispatch path')
class ExecuteServiceDispatchSpec extends Specification {

    @Autowired ExecuteService executeService

    @TempDir Path tempDir

    private static String payloadOf(McpResponse r) {
        def content = r?.result?.content
        return content ? (content[0].text as String) : ''
    }

    // NOT named call(). A helper named call() is invisible from inside a closure: the
    // closure's own call(...) wins the dispatch, and the failure is a MissingMethodException
    // naming doCall, which reads like the spec is broken rather than the name being taken.
    // (Practice #442, one family over: Groovy resolves this-dispatch inside closures in ways
    // that do not match where the method is written.)
    private McpResponse runAction(String action, Map extraOptions = [:]) {
        Map<String, Object> options = ([workingDir: tempDir.toString()] + extraOptions) as Map<String, Object>
        return executeService.handleToolCall('execute', [
            action : action,
            script : "echo hi",
            options: options
        ] as Map<String, Object>, "dispatch-${action}")
    }

    // -----------------------------------------------------------------------
    // FS-EXEC-5: the fixture reaches dispatch at all.
    //
    // THIS IS THE GUARD ON THE OTHER TWO. If working-directory validation, the plan
    // gate, or anything else starts refusing before the switch, this goes red and
    // names the problem -- instead of the cases below silently passing for a reason
    // that has nothing to do with what they claim to test.
    // -----------------------------------------------------------------------
    def 'FS-EXEC-5: handleToolCall reaches the dispatcher and runs the script'() {
        when: 'the tool entry point, which no ExecuteService spec had ever called'
        McpResponse r = runAction('cmd')
        String out = payloadOf(r)

        then: 'the command actually ran -- not a path refusal, not an NPE, not a gate'
        out.contains('hi')

        and: 'and specifically none of the ways this fixture has failed before'
        !out.contains('Unknown execute action')
        !out.contains('not allowed')
        !out.contains('NullPointerException')
        !out.contains('Cannot invoke')
    }

    // -----------------------------------------------------------------------
    // FS-EXEC-5b: the restored FS-EXEC-4b, on a fixture that can see it.
    // -----------------------------------------------------------------------
    def 'FS-EXEC-5b: every advertised action dispatches'() {
        // VALID_EXECUTE_ACTIONS is hand-derived from two dispatch points -- the switch
        // in handleToolCall and the job-action block above it -- and nothing else
        // guards the correspondence. The failure this catches is a name advertised by
        // the unknown-action error and handled nowhere: a caller does exactly what the
        // error told them and is refused for using an action that does not exist.
        expect: 'the list is not empty -- every() over an emptied list is vacuously true'
        ExecuteService.VALID_EXECUTE_ACTIONS.size() >= 9

        and:
        ExecuteService.VALID_EXECUTE_ACTIONS.every { String a ->
            String out = payloadOf(runAction(a, [jobId: 'no-such-job']))
            // Any answer is acceptable -- a security refusal, a missing interpreter, a
            // no-such-job. The one answer that must not come back is the dispatcher
            // saying it has never heard of an action it advertises.
            !out.contains('Unknown execute action')
        }
    }

    // -----------------------------------------------------------------------
    // FS-EXEC-5c: and the converse, so 5b cannot pass by the error text vanishing.
    // -----------------------------------------------------------------------
    def 'FS-EXEC-5c: an action that is genuinely not handled is named as such'() {
        when:
        String out = payloadOf(runAction('definitely-not-an-action'))

        then: 'the refusal happens, and enumerates -- file_read has always done this'
        out.contains('Unknown execute action')

        and:
        ExecuteService.VALID_EXECUTE_ACTIONS.every { out.contains(it) }
    }
}
