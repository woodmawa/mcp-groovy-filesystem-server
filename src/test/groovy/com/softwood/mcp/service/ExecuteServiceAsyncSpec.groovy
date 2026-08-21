package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import groovy.json.JsonSlurper
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Title

import java.util.concurrent.TimeUnit

/**
 * FS-EXEC-2 — background execution jobs.
 *
 * execute is bounded by a hard ~60s deadline imposed at the MCP CLIENT boundary.
 * options.timeout does not extend it: FS honours that value in process.waitFor, but the caller
 * has already given up, and the blocked call serialises everything behind it. A cold Gradle
 * compile timed out at the tool boundary while still running and blocked the next two calls
 * (observation 9821, chain ef8cae5c).
 *
 * The ceiling is not ours to raise, so the fix is to stop blocking underneath it. This is new
 * capability rather than a defect fix, so there is no red-then-green here — the specs below
 * assert the contract the async path has to honour.
 */
@Title('ExecuteService — FS-EXEC-2 background jobs')
@Requires({ os.windows })
class ExecuteServiceAsyncSpec extends Specification {

    /** See the note in FS-EXEC-2: the test worker's PATH has no System32. */
    static final String SLOW      = '%SystemRoot%\\System32\\ping.exe -n 5 127.0.0.1'
    static final String VERY_SLOW = '%SystemRoot%\\System32\\ping.exe -n 30 127.0.0.1'

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
        cfg.initPatterns()
        service.whitelistConfig = cfg
    }

    private static Map payload(def response) {
        def content = response?.result?.content
        return content ? (new JsonSlurper().parseText(content[0].text as String) as Map) : [:]
    }

    private Map runCmd(String script, Map<String, Object> options) {
        payload(service.doCmd(script, workDir, 60, null, options, 'spec'))
    }

    private Map job(String action, Map<String, Object> options) {
        payload(service.handleToolCall('execute',
            [action: action, options: options] as Map<String, Object>, 'spec'))
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'FS-EXEC-2: async submit returns a jobId immediately instead of blocking'() {
        given: 'a script that takes several seconds — long enough that blocking would be visible'
        // Absolute path deliberately: the gradle test worker's PATH does not include System32,
        // so cmd builtins resolve but external executables do not. Nothing to do with FS.
        String script = SLOW

        when: 'submitted asynchronously'
        long t0 = System.currentTimeMillis()
        Map submit = runCmd(script, [async: true] as Map<String, Object>)
        long submitMs = System.currentTimeMillis() - t0

        then: 'it comes back essentially at once, with a job id'
        submit.async == true
        submit.jobId
        submit.status == 'running'
        submitMs < 2000

        when: 'we poll until it finishes'
        Map status = [:]
        for (int i = 0; i < 100 && (status.finished != true); i++) {
            Thread.sleep(200)
            status = registry.get(submit.jobId as String).statusMap()
        }

        then: 'the work actually ran to completion in the background'
        status.finished == true
        job('job_output', [jobId: submit.jobId] as Map<String, Object>).stderr == ''
        status.status == 'completed'
        status.exitCode == 0
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'FS-EXEC-2b: job_output tails incrementally via nextOffset'() {
        given:
        Map submit = runCmd('echo ONE\\necho TWO\\necho THREE', [async: true] as Map<String, Object>)
        String id = submit.jobId as String
        waitForFinish(id)

        when: 'read from the start'
        Map first = job('job_output', [jobId: id] as Map<String, Object>)

        then:
        first.stdout.contains('ONE')
        first.stdout.contains('THREE')
        first.nextOffset == (first.stdout as String).length()

        when: 'read again from where we stopped'
        Map second = job('job_output', [jobId: id, sinceOffset: first.nextOffset] as Map<String, Object>)

        then: 'nothing is re-sent — this is what makes polling a long build cheap'
        second.stdout == ''
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'FS-EXEC-2c: job_cancel stops a running job and kills its process'() {
        given: 'a long job'
        Map submit = runCmd(VERY_SLOW, [async: true] as Map<String, Object>)
        String id = submit.jobId as String
        Thread.sleep(500)

        when:
        Map cancelled = job('job_cancel', [jobId: id] as Map<String, Object>)

        then: 'reported cancelled, and the OS process is gone rather than orphaned'
        cancelled.cancelled == true
        cancelled.status == 'cancelled'
        registry.get(id).process?.isAlive() == false
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'FS-EXEC-2d: job_list reports the job and job_status reads back'() {
        given:
        Map submit = runCmd('echo LISTED', [async: true] as Map<String, Object>)
        String id = submit.jobId as String
        waitForFinish(id)

        when:
        Map listed = job('job_list', [:] as Map<String, Object>)
        Map status = job('job_status', [jobId: id] as Map<String, Object>)

        then:
        (listed.jobs as List).any { (it as Map).jobId == id }
        status.jobId == id
        status.status == 'completed'
    }

    def 'FS-EXEC-2e: an unknown jobId is an explicit error, not a silent empty result'() {
        when:
        def response = service.handleToolCall('execute',
            [action: 'job_status', options: [jobId: 'no-such-job']] as Map<String, Object>, 'spec')

        then: 'the caller is told, rather than being handed a blank status to misread'
        response.result.isError == true
        (response.result.content[0].text as String).contains('Unknown jobId')
    }

    private void waitForFinish(String id) {
        for (int i = 0; i < 150; i++) {
            if (registry.get(id)?.finished) return
            Thread.sleep(100)
        }
    }
}
