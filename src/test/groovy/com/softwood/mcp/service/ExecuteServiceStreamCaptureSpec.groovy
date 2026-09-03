package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Title

/**
 * FS-EXEC-STREAM -- an unread stream is not an empty stream.
 *
 * <h3>The defect</h3>
 * <p>{@code runAndCapture} started two virtual threads whose bodies had no try/catch and whose
 * completion was never checked. Any exception inside the drain loop -- {@code IOException: Stream
 * closed}, a decode fault -- killed the thread silently, leaving an empty StringBuilder. The
 * method then returned {@code exitCode 0} with empty stdout and no indication whatsoever. The
 * second half is the same in effect: {@code join(remainingMs)} can time out, leaving the thread
 * alive and the buffer partially drained, and that partial buffer was returned as though it were
 * the whole output.</p>
 *
 * <p>So FS could not distinguish "the child produced nothing" from "our reader died", and
 * reported the second as the first. That is the identical shape to the PATHEXT defect fixed in
 * 0.9.15 one layer up, and to {@code catch(Exception ignored)} around a value feeding a counter:
 * the failure presents as a legitimate zero. It was recorded as known residue when 0.9.15
 * shipped -- the marker text proved the stdout thread had survived that time, so the swallow was
 * not the cause, but it is why the PATHEXT failure was invisible rather than loud.</p>
 *
 * <h3>Why it had never been tested</h3>
 * <p>The drain loop was inline in a private method, so the failure could not be constructed from
 * a spec at all. The fix extracts {@code pumpStream} as a protected seam -- practice #1166 seam 2,
 * the same treatment {@code doCmd} and later {@code doPowershell} needed. An untestable condition
 * is one nobody tests.</p>
 *
 * <ul>
 *   <li>STREAM-1: the control. A normal run still succeeds and reports no stream error, so the
 *       fix cannot pass by failing everything.</li>
 *   <li>STREAM-2: a reader that throws must NOT yield success with empty output. RED before.</li>
 *   <li>STREAM-3: the compact response shape carries the reason too. Compact is what most callers
 *       read, and a guard only present in the verbose shape is one most callers never see.</li>
 * </ul>
 */
@Title('ExecuteService -- FS-EXEC-STREAM reader-thread failures are never silent')
@Requires({ os.windows })
class ExecuteServiceStreamCaptureSpec extends Specification {

    String workDir = System.getProperty('java.io.tmpdir')

    private static CommandWhitelistConfig permissive() {
        CommandWhitelistConfig cfg = new CommandWhitelistConfig()
        cfg.powershellAllowed = ['.*']
        cfg.cmdAllowed = ['.*']
        cfg.bashAllowed = ['.*']
        cfg.initPatterns()
        return cfg
    }

    private static void configure(ExecuteService svc) {
        svc.enableCmd = true
        svc.enablePowershell = true
        svc.enableBash = true
        svc.maxExecutionTimeSeconds = 30
        svc.whitelistConfig = permissive()
    }

    /** McpResponse.result.content[0].text carries the JSON payload runProcess produced. */
    private static Map payloadOf(def response) {
        def content = response?.result?.content
        String text = content ? (content[0].text as String) : '{}'
        return new JsonSlurper().parseText(text) as Map
    }

    def 'FS-EXEC-STREAM-1: a healthy run still succeeds and reports no stream error'() {
        given:
        ExecuteService svc = new ExecuteService()
        configure(svc)

        when:
        Map r = payloadOf(svc.doCmd('echo HEALTHY', workDir, 30, null,
            [:] as Map<String, Object>, 'stream-1'))

        then: '''the control. Withholding success on an unread stream is only safe if a read
                 stream still reports success -- otherwise the fix passes by failing everything.'''
        r.success == true
        (r.stdout as String).contains('HEALTHY')
        r.stream_error == null
    }

    def 'FS-EXEC-STREAM-2: a reader that throws does not yield success with empty output'() {
        given: 'a service whose drain loop fails, as a closed stream or decode fault would'
        ExecuteService svc = new ThrowingPumpExecuteService()
        configure(svc)

        when: 'a command that would normally print'
        Map r = payloadOf(svc.doCmd('echo NEVER_READ', workDir, 30, null,
            [:] as Map<String, Object>, 'stream-2'))

        then: 'the child still exited 0 -- the exit code was never the unreliable part'
        r.exitCode == 0

        and: 'and stdout really is empty, which is precisely why this is dangerous'
        !(r.stdout as String).contains('NEVER_READ')

        and: '''but it must NOT be reported as success. An exit code says the child finished, not
                that we know what it said. Before this fix the call returned success:true with
                empty stdout -- indistinguishable from a command that printed nothing, which is how
                an empty `git status --porcelain` reads as a clean tree.'''
        assert r.success == false,
            'reader threw and the call still reported success -- an unread stream is being ' +
            'reported as an empty one'

        and: 'and the reason is stated rather than left for the caller to infer'
        r.stream_error != null
        (r.stream_error as String).contains('IOException')
    }

    def 'FS-EXEC-STREAM-3: the compact response shape carries the reason too'() {
        given:
        ExecuteService svc = new ThrowingPumpExecuteService()
        configure(svc)

        when: 'compact is requested -- the shape most callers actually read'
        Map r = payloadOf(svc.doCmd('echo NEVER_READ', workDir, 30, null,
            [compact: true] as Map<String, Object>, 'stream-3'))

        then: 'the guard survives the shape change'
        r.success == false
        r.stream_error != null
    }
}

/**
 * Forces the failure the inline drain loop made unconstructable. Throws on every stream, so both
 * readers record an error -- the point under test is that a dead reader is reported at all, not
 * which of the two died.
 */
class ThrowingPumpExecuteService extends ExecuteService {

    // ExecuteService declares only ExecuteService(PathService), so a subclass needs an explicit
    // super call. null is safe here: doCmd's path for this spec never dereferences pathService,
    // and the sibling specs construct the service the same way.
    ThrowingPumpExecuteService() { super(null) }

    @Override
    protected void pumpStream(InputStream stream, Closure<?> onLine) {
        throw new IOException('Stream closed')
    }
}
