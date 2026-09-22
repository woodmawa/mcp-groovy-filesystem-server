package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS-EXEC-6 -- bash scripts survive Windows argv quoting.
 *
 * Found 2026-09-22 by probing every execute action with scratch files (session
 * 2026-09-22-13-03, chain 41212de1). `execute action=bash` built
 * ['bash', '-c', script] and handed it to ProcessBuilder. On Windows the argument is
 * wrapped in double quotes for the child's command line, and an inner double quote that
 * sits next to whitespace closes that wrapper early: `echo "one two"` reaches bash as
 * `echo "one` with `two"` and everything after it delivered as POSITIONAL PARAMETERS.
 * bash printed `one`, exit 0, success:true -- and a side-effect later in the script never
 * happened. `echo "one"; echo "two"` (no whitespace inside the quotes) was fine, which is
 * exactly why it stayed dark: the common test strings do not trip it and
 * `git commit -m "fix the thing"` does.
 *
 * doPowershell, doCmd and doPython already write the script to a temp file for this class
 * of problem, each with a comment saying so. bash was assumed safe because `bash -c`
 * accepts multi-line input -- true, and irrelevant to quoting. Practices #77 and #202
 * recorded the identical silent-blank-output shape for python-via-cmd long ago; the
 * knowledge existed and was never extended one executor over.
 *
 * Today's FS stats made the cost visible: execute:cmd 94 calls, execute:bash 8, all eight
 * from the probe. Claude had learned to avoid bash without anyone knowing why.
 *
 * Every case asserts on stdout AND on a file the script writes AFTER the quoted phrase,
 * so a fix that repairs the echo but still truncates the tail cannot pass.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('ExecuteService -- FS-EXEC-6 bash quoting on Windows')
class ExecuteServiceBashQuotingSpec extends Specification {

    @Autowired ExecuteService executeService

    @TempDir Path tempDir

    private static String payloadOf(McpResponse r) {
        def content = r?.result?.content
        return content ? (content[0].text as String) : ''
    }

    private McpResponse runBash(String script, Map extraOptions = [:]) {
        Map<String, Object> options = ([workingDir: tempDir.toString()] + extraOptions) as Map<String, Object>
        return executeService.handleToolCall('execute', [
            action : 'bash',
            script : script,
            options: options
        ] as Map<String, Object>, 'bash-quoting')
    }

    def 'FS-EXEC-6: a double-quoted phrase with whitespace does not truncate the script'() {
        when:
        String out = payloadOf(runBash('echo "one two"; echo "three four"; echo marker > side-effect.txt'))

        then: 'every echo reached stdout'
        out.contains('one two')
        out.contains('three four')

        and: 'the command AFTER the quoted phrase ran -- the half a stdout-only check cannot see'
        Files.exists(tempDir.resolve('side-effect.txt'))
        tempDir.resolve('side-effect.txt').text.trim() == 'marker'
    }

    def 'FS-EXEC-6b: the same on separate lines'() {
        given: 'three lines joined with real newlines -- built here so no escape pass can touch them'
        String script = ['echo "alpha beta"', 'echo "gamma delta"', 'echo done > multiline.txt', ''].join(System.lineSeparator())

        when:
        String out = payloadOf(runBash(script))

        then:
        out.contains('alpha beta')
        out.contains('gamma delta')
        Files.exists(tempDir.resolve('multiline.txt'))
    }

    def 'FS-EXEC-6c: nothing leaks into positional parameters'() {
        when: 'a script that prints $1 -- under the defect $1 held the truncated tail'
        String out = payloadOf(runBash('echo "x y"; echo "p1=[$1]"'))

        then:
        out.contains('x y')
        out.contains('p1=[]')
    }

    def 'FS-EXEC-6d: grepPattern filters the real output, not a truncated one'() {
        when:
        String out = payloadOf(runBash('echo "BUILD SUCCESSFUL in 3s"; echo "445 tests completed"; echo noise',
                                       [grepPattern: 'BUILD|tests completed']))

        then:
        out.contains('BUILD SUCCESSFUL in 3s')
        out.contains('445 tests completed')
        !out.contains('noise')
    }

    def 'FS-EXEC-6e: exit code is the last command, as documented'() {
        when:
        String out = payloadOf(runBash('echo "a b"; false'))

        then:
        out.contains('a b')
        out.contains('exitCode":1')
    }
}
