package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Unroll

/**
 * optimised-learning WS1-D (DESIGN-2026-10-15-optimised-learning.md section 2): 18 caller corrections on execute since
 * 09-15. The ones a script check catches before anything runs:
 * <ul>
 *   <li>a leading sleep (2026-10-14: `sleep 20` before git status, timed out the bridge) -- REFUSED;</li>
 *   <li>shell text sent to action=python, which runs the script as Python source (2026-10-15: `python spec_census.py`
 *       failed exit 1 with no output) -- REFUSED, it can never do what was meant;</li>
 *   <li>`%` under action=cmd (git log --format=%h) -- WARNED, it may be intended;</li>
 *   <li>gradle -q with test, which hides per-test FAILED lines (2026-10-14 M1/M2 read SURVIVED) -- WARNED.</li>
 * </ul>
 * This mechanises the first line of Rule2c lesson #3556; when it ships, that line is retired from the practice.
 * The dispatch-level proof (refused before anything runs) is ExecuteServiceDispatchSpec FS-EXEC-OL-1.
 */
@groovy.transform.CompileDynamic
class ScriptLintSpec extends Specification {

    @Unroll
    def 'SL-1 a leading wait is refused SLEEP_REFUSED: #script'() {
        expect:
        ScriptLint.check(action, script).refusal?.startsWith('SLEEP_REFUSED')

        where:
        action       | script
        'bash'       | 'sleep 20; git status'
        'bash'       | '  sleep 5 && ls'
        'powershell' | 'Start-Sleep -Seconds 30\nGet-ChildItem'
        'cmd'        | 'timeout /t 10\ndir'
        'cmd'        | 'ping -n 6 127.0.0.1 > nul\ndir'
    }

    def 'SL-2 a sleep that is not the first statement is not refused'() {
        expect:
        ScriptLint.check('bash', 'git fetch && sleep 1 && git status').refusal == null
        ScriptLint.check('bash', '# wait for nothing\necho hi').refusal == null
    }

    def 'SL-3 shell text sent to action=python is refused; a real Python script is not'() {
        expect:
        ScriptLint.check('python', 'python spec_census.py').refusal?.startsWith('EXECUTE_ACTION_MISMATCH')
        ScriptLint.check('python', 'py -3 tool.py --x').refusal?.startsWith('EXECUTE_ACTION_MISMATCH')
        ScriptLint.check('python', 'import os\nprint(os.getcwd())').refusal == null
    }

    def 'SL-4 a stray % under cmd is warned; %VAR%, %%, %~ and %1 are not'() {
        expect:
        ScriptLint.check('cmd', 'git log --format=%h -3').warnings.any { it.contains('%') }
        ScriptLint.check('cmd', 'echo %PATH% && echo 100%%').warnings.isEmpty()
        ScriptLint.check('cmd', 'echo %~dp0 %1').warnings.isEmpty()
        ScriptLint.check('bash', 'git log --format=%h').warnings.isEmpty()
    }

    def 'SL-5 gradle -q with test is warned'() {
        expect:
        ScriptLint.check('powershell', './gradlew test -q --tests *ASpec').warnings.any { it.contains('-q') }
        ScriptLint.check('powershell', './gradlew test --tests *ASpec').warnings.isEmpty()
        ScriptLint.check('powershell', './gradlew build -q').warnings.isEmpty()
    }

    def 'SL-6 null and blank scripts pass through'() {
        expect:
        ScriptLint.check('bash', null).refusal == null
        ScriptLint.check('bash', '   ').warnings.isEmpty()
    }
}
