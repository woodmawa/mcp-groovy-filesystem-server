package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Tag
import spock.lang.Title

/**
 * X74 (optimised-learning ARC-STATE, chain ae8b7c25) -- execute action=powershell reported a failed
 * native command as exit 0.
 *
 * <p>Observed 2026-10-19: async job a3f3f163 ran `.\gradlew.bat test --rerun -Ptests.forks=1`; gradle
 * printed BUILD FAILED and the job came back status=completed exitCode=0. A release gate read green
 * for a build that never ran a test. doPowershell runs the script with -File, and PowerShell exits 0
 * at the end of a -File script unless the script itself calls exit -- a native command's
 * $LASTEXITCODE is never carried out. Same "success over nothing" class as FS 0.9.48 / 0.9.50.</p>
 *
 * <p>The contract is the one the tool states for every action: the LAST command's exit code is
 * returned. So a failing native command followed by a succeeding one is 0 (PSX-3), and an explicit
 * exit N is N (PSX-4).</p>
 *
 * PSX-1: a last native command exiting 3 reports exitCode 3 (RED on 0.9.103)
 * PSX-2: a .bat that exits 1, the gradlew shape, reports exitCode 1 (RED on 0.9.103)
 * PSX-3: CONTROL -- a failing native command followed by a succeeding cmdlet reports 0
 * PSX-4: CONTROL -- an explicit `exit 5` still reports 5
 * PSX-5: CONTROL -- a plain cmdlet script reports 0 and its output
 */
@Title('ExecuteService -- X74 powershell carries the last native exit code out')
@Requires({ os.windows })
@Tag('integration')
class ExecutePowershellExitCodeSpec extends Specification {

    ExecuteService service
    File workDir

    def setup() {
        workDir = File.createTempDir('psx-')
        service = new ExecuteService()
        service.enablePowershell = true
        service.maxExecutionTimeSeconds = 30
        // Explicitly permissive (as ExecuteServiceNativeCommandSpec): a bare config refuses every
        // powershell script, which would present here as a parse failure, not an exit code.
        CommandWhitelistConfig cfg = new CommandWhitelistConfig()
        cfg.powershellAllowed = ['.*']
        cfg.initPatterns()
        service.whitelistConfig = cfg
    }

    def cleanup() { workDir?.deleteDir() }

    private Map run(String script) {
        def response = service.doPowershell(script, workDir.absolutePath, 30, null, [:] as Map<String, Object>, 'psx')
        def content = response?.result?.content
        String text = content ? (content[0].text as String) : '{}'
        return (Map) new JsonSlurper().parseText(text)
    }

    def 'PSX-1: a last native command exiting 3 reports exitCode 3'() {
        when:
        Map r = run('Write-Output before\ncmd /c exit 3')

        then:
        r.exitCode == 3
    }

    def 'PSX-2: a .bat that exits 1 (the gradlew shape) reports exitCode 1'() {
        given:
        new File(workDir, 'fails.bat').text = '@echo off\r\necho BUILD FAILED 1>&2\r\nexit /b 1\r\n'

        when:
        Map r = run('.\\fails.bat')

        then:
        r.exitCode == 1
    }

    def 'PSX-3: CONTROL -- a failing native command followed by a succeeding cmdlet reports 0'() {
        when:
        Map r = run('cmd /c exit 3\nWrite-Output after')

        then:
        r.exitCode == 0
        (r.stdout as String).contains('after')
    }

    def 'PSX-4: CONTROL -- an explicit exit 5 still reports 5'() {
        when:
        Map r = run('Write-Output x\nexit 5')

        then:
        r.exitCode == 5
    }

    def 'PSX-5: CONTROL -- a plain cmdlet script reports 0 and its output'() {
        when:
        Map r = run('Write-Output hello')

        then:
        r.exitCode == 0
        (r.stdout as String).contains('hello')
    }
}
