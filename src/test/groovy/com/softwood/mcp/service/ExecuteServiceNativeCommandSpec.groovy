package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Title

/**
 * FS-EXEC-PATHEXT -- action=powershell must run NATIVE executables, whatever PATHEXT it inherits.
 *
 * <h3>The defect, observed live 2026-09-02</h3>
 * <p>{@code execute action=powershell} returned {@code exitCode 0} with EMPTY stdout AND empty
 * stderr for every invocation of git.exe, while cmdlet output from the same script came back
 * normally. The same query via {@code action=cmd} returned the correct output immediately, and
 * forty minutes earlier -- before the server process was restarted, same version -- the identical
 * powershell script had worked.</p>
 *
 * <h3>Root cause</h3>
 * <p>The child process inherited {@code PATHEXT=.CPL}. With {@code .EXE} absent from PATHEXT,
 * PowerShell classifies {@code git.exe} as a DOCUMENT rather than an application, so {@code &}
 * hands it to file-association activation instead of running it. The observable signature is
 * silence in every channel that matters: {@code $LASTEXITCODE} is never set, {@code $?} stays
 * {@code True}, {@code $Error.Count} is 0, stdout and stderr are empty, and the process exits 0.
 * Only inside a pipeline does it surface, as {@code CantActivateDocumentInPipeline}. Redirecting
 * the native command to a file produced an empty file too, which is what ruled out every
 * stream-capture explanation. {@code Start-Process} on the same path worked and printed the
 * version, proving the executable was never the problem.</p>
 *
 * <p>cmd.exe was immune because it normalises PATHEXT itself rather than trusting what it
 * inherits. FS cannot control the environment its parent hands it, so it must repair PATHEXT for
 * the children it spawns.</p>
 *
 * <h3>Why this is worth a spec rather than a note</h3>
 * <p>An empty {@code git status --porcelain} reads as a CLEAN TREE. The project instructions
 * require checking {@code git rev-parse HEAD} against {@code origin/<branch>} before believing a
 * push happened; under this defect both sides return the empty string and compare equal. A
 * verification step that passes because both of its inputs are missing is the exact failure class
 * this platform keeps recording, so the guard belongs in the suite.</p>
 *
 * <ul>
 *   <li>PATHEXT-1: a native executable's stdout is captured even when PATHEXT lacks .EXE.</li>
 *   <li>PATHEXT-2: cmd and powershell agree on the same native command under the same hostile
 *       PATHEXT. This is the controlled comparison that would have caught it on day one --
 *       neither path alone looked wrong, only the difference between them did.</li>
 *   <li>PATHEXT-3: a caller's explicit PATHEXT is preserved when it is already usable. The repair
 *       must fix a broken inheritance, not overwrite deliberate intent.</li>
 * </ul>
 *
 * <p>PATHEXT-1 and PATHEXT-2 MUST FAIL on today's code.</p>
 */
@Title('ExecuteService -- FS-EXEC-PATHEXT native command invocation')
@Requires({ os.windows })
class ExecuteServiceNativeCommandSpec extends Specification {

    ExecuteService service
    String workDir = System.getProperty('java.io.tmpdir')

    /** PATHEXT as actually inherited on 2026-09-02 -- a single entry, no .EXE. */
    private static final String HOSTILE_PATHEXT = '.CPL'

    /** cmd.exe by absolute path: a native executable guaranteed present on any Windows host. */
    private static final String NATIVE_EXE = System.getenv('SystemRoot') + '/System32/cmd.exe'

    def setup() {
        service = new ExecuteService()
        service.enableCmd = true
        service.enablePowershell = true
        service.enableBash = true
        service.maxExecutionTimeSeconds = 30
        // Explicitly permissive: this spec is about native-command invocation, not policy.
        // An empty allowed-list is not the same as an open one, and a whitelist refusal would
        // present here as empty stdout -- the very symptom under test.
        CommandWhitelistConfig cfg = new CommandWhitelistConfig()
        cfg.powershellAllowed = ['.*']
        cfg.cmdAllowed = ['.*']
        cfg.bashAllowed = ['.*']
        cfg.initPatterns()
        service.whitelistConfig = cfg
    }

    private static String stdoutOf(def response) {
        def content = response?.result?.content
        return content ? (content[0].text as String) : ''
    }

    def 'FS-EXEC-PATHEXT-1: a native executable runs under powershell when PATHEXT lacks .EXE'() {
        given: 'a script invoking a native exe, and the broken PATHEXT seen live'
        String script = """& '${NATIVE_EXE}' /c echo NATIVE_MARKER
                           Write-Output 'CMDLET_MARKER'""".stripIndent()

        when:
        def response = service.doPowershell(script, workDir, 30,
            ['PATHEXT': HOSTILE_PATHEXT] as Map<String, String>,
            [:] as Map<String, Object>, 'pathext-1')
        String out = stdoutOf(response)

        then: '''the cmdlet half always worked -- asserting it first proves the script ran at all,
                 so the native assertion below is about the native path and not about a dead run'''
        out.contains('CMDLET_MARKER')

        and: 'and the native half must be there too -- today it is silently absent'
        assert out.contains('NATIVE_MARKER'),
            'native executable produced no output under powershell; PATHEXT was ' +
            HOSTILE_PATHEXT + ' and the process still reported success'
    }

    def 'FS-EXEC-PATHEXT-2: cmd and powershell agree on the same native command'() {
        given: 'the same native invocation expressed for each interpreter, same hostile PATHEXT'
        Map<String, String> env = ['PATHEXT': HOSTILE_PATHEXT] as Map<String, String>

        when:
        String viaCmd = stdoutOf(service.doCmd(
            'echo AGREE_MARKER', workDir, 30, env, [:] as Map<String, Object>, 'pathext-2a'))
        String viaPs = stdoutOf(service.doPowershell(
            "& '${NATIVE_EXE}' /c echo AGREE_MARKER", workDir, 30, env,
            [:] as Map<String, Object>, 'pathext-2b'))

        then: '''one interpreter silently disagreeing with the other over the same command is the
                 signal that was available all along and that nothing was comparing. Neither path
                 looks wrong on its own; only the difference does.'''
        viaCmd.contains('AGREE_MARKER')
        assert viaPs.contains('AGREE_MARKER'),
            'cmd saw the output and powershell did not, for the same native command'
    }

    def 'FS-EXEC-PATHEXT-3: a caller PATHEXT that already works is preserved'() {
        given: 'an explicit, usable PATHEXT with an unusual extra entry'
        String deliberate = '.EXE;.MYAPP'

        when:
        def response = service.doPowershell(
            'Write-Output ("SAW=" + $env:PATHEXT)', workDir, 30,
            ['PATHEXT': deliberate] as Map<String, String>,
            [:] as Map<String, Object>, 'pathext-3')
        String out = stdoutOf(response)

        then: '''the repair exists to fix a broken inheritance, not to overwrite intent. A caller
                 who passes a working PATHEXT gets exactly that one back.'''
        out.contains('SAW=' + deliberate)
    }
}
