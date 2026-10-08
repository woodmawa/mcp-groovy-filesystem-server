package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.support.McpHeartbeat
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Title

import java.util.concurrent.TimeUnit

/**
 * FS 0.9.73 -- chain bacfc195 (critical), local-model-reliability WP-FS, ARC-STATE C3a.
 *
 * <p>2026-10-08 13:52: a synchronous `execute` of `gradlew packageMcpbThin` wedged the FS stdio server for 494 s.
 * Gradle finished in 11 s, but the daemon it started inherited the output pipe, so the readers never saw EOF;
 * runAndCapture then joined stdout for the whole remaining budget AND THEN stderr for the whole budget again
 * (11 + 289 + 194 s), all on the stdio main thread, so every later request sat unread while the heartbeat logged
 * "client has sent nothing". options.timeout=300 bounded none of it.</p>
 *
 * <p>The slow child is ping to a marker address, so a survivor can be found by its command line and nothing else
 * on the machine is touched. Absolute paths: the test worker's PATH has no System32.</p>
 */
@Title('ExecuteService -- WEDGE-1..5 a command can never wedge the stdio server')
@Requires({ os.windows })
class ExecuteServiceWedgeSpec extends Specification {

    static final String PING = '%SystemRoot%\\System32\\ping.exe'
    static final String PS = System.getenv('SystemRoot') + '\\System32\\WindowsPowerShell\\v1.0\\powershell.exe'

    ExecuteService service
    ExecuteJobRegistry registry
    String workDir = System.getProperty('java.io.tmpdir')

    def setup() {
        registry = new ExecuteJobRegistry()
        service = new ExecuteService()
        service.enableCmd = true
        service.maxExecutionTimeSeconds = 60
        service.jobRegistry = registry
        CommandWhitelistConfig cfg = new CommandWhitelistConfig()
        cfg.cmdAllowed = ['.*']
        cfg.initPatterns()
        service.whitelistConfig = cfg
    }

    def cleanup() {
        ['127.0.0.2', '127.0.0.3', '127.0.0.4'].each { String m -> killPings(m) }
        McpHeartbeat.end()
    }

    private static Map payload(def response) {
        def content = response?.result?.content
        content ? (new JsonSlurper().parseText(content[0].text as String) as Map) : [:]
    }

    private Map runCmd(String script, int timeout, Map<String, Object> options = [:]) {
        payload(service.doCmd(script, workDir, timeout, null, options, 'wedge'))
    }

    /** Runs PowerShell with -EncodedCommand: quotes inside -Command do not survive ProcessBuilder on Windows. */
    private static String ps(String command) {
        // Without a profile PowerShell writes 'Preparing modules for first use' progress to stderr as CLIXML;
        // merged into stdout it buried the count. Progress off, stderr discarded.
        String encoded = Base64.encoder.encodeToString(("\$ProgressPreference='SilentlyContinue'; " + command).getBytes('UTF-16LE'))
        Process p = new ProcessBuilder(PS, '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        String out = p.inputStream.text.trim()
        p.waitFor(20, TimeUnit.SECONDS)
        out
    }

    private static String pingQuery(String marker) {
        "Get-CimInstance Win32_Process -Filter \"Name='PING.EXE'\" | Where-Object { \$_.CommandLine -like '*${marker}*' }"
    }

    /** How many ping.exe processes aimed at this marker address are alive. */
    private static int pings(String marker) {
        String out = ps("@(${pingQuery(marker)}).Count")
        String last = out.readLines().findAll { it.trim() }.with { it ? it.last().trim() : '' }
        last.isInteger() ? last.toInteger() : -1
    }

    private static void killPings(String marker) {
        ps("${pingQuery(marker)} | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }")
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'WEDGE-1: a grandchild holding the output pipe does not hold the call once the command has exited'() {
        given: 'cmd starts ping in the background on the same handles and exits at once -- the Gradle daemon shape'
        String script = "start \"\" /b ${PING} -n 30 127.0.0.2"

        when:
        long t0 = System.currentTimeMillis()
        Map r = runCmd(script, 60)
        long elapsed = System.currentTimeMillis() - t0

        then: 'it returns within the drain window, not when the grandchild lets go (about 29 s)'
        elapsed < 12_000L

        and: 'the command itself succeeded, and the response says why output may be incomplete'
        r.exitCode == 0
        r.success == true
        (r.stream_note as String)?.contains('held open')

        and: 'the grandchild was the command\'s business, not ours: it is left running'
        pings('127.0.0.2') >= 1
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'WEDGE-2: a timeout kills the whole process tree, not just the shell'() {
        when: 'cmd waits on ping, and the call times out'
        long t0 = System.currentTimeMillis()
        Map r = runCmd("${PING} -n 30 127.0.0.3", 3)
        long elapsed = System.currentTimeMillis() - t0

        then: 'a named timeout, promptly'
        r.success == false
        (r.error as String).contains('timed out')
        elapsed < 12_000L

        and: 'and the ping under cmd is dead too -- before this only cmd was destroyed'
        sleep(1500)
        pings('127.0.0.3') == 0
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'WEDGE-3: a synchronous call that outlives the sync window is answered with a job, not by blocking'() {
        given:
        service.syncWaitSeconds = 2

        when:
        long t0 = System.currentTimeMillis()
        Map r = runCmd("${PING} -n 8 127.0.0.4", 30)
        long elapsed = System.currentTimeMillis() - t0

        then: 'the caller gets an answer inside the window -- the stdio thread is free again'
        elapsed < 6_000L
        r.async == true
        r.moved_to_background == true
        r.jobId

        and: 'and the work carries on to completion as a job'
        ExecuteJob job = registry.get(r.jobId as String)
        long deadline = System.currentTimeMillis() + 20_000L
        while (!job.finished && System.currentTimeMillis() < deadline) { sleep(200) }
        job.status == 'completed'
        job.exitCode == 0
    }

    def 'WEDGE-4: the heartbeat names the request in flight instead of saying the client sent nothing'() {
        given:
        McpHeartbeat.recordRequest()
        long now = System.currentTimeMillis() + 180_000L

        when:
        McpHeartbeat.begin('req#73 tools/call/execute')
        String busy = McpHeartbeat.line('filesystem', 'test', 1L, now)
        McpHeartbeat.end()
        String idle = McpHeartbeat.line('filesystem', 'test', 1L, now)

        then:
        busy.contains('busy')
        busy.contains('req#73 tools/call/execute')
        !busy.contains('client has sent nothing')

        and: 'with nothing in flight the idle reading stands'
        idle.contains('client has sent nothing')
    }

    def 'WEDGE-5: CONTROL -- a quick synchronous call still answers inline with its output'() {
        given:
        service.syncWaitSeconds = 2

        when:
        Map r = runCmd('echo QUICK', 30)

        then:
        r.success == true
        (r.stdout as String).contains('QUICK')
        r.jobId == null
        r.async == null
    }
}
