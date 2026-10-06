package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.lang.Title

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Chain 18eb7c8d -- an async job was still killed at the SYNCHRONOUS default timeout.
 *
 * FS-EXEC-2 added options.async so long work (gradle suites, builds) stops blocking under the
 * ~60 s client deadline. But handleToolCall resolved one timeout for both paths:
 * `options.timeout ?: maxExecutionTimeSeconds` (60). An async job submitted without an explicit
 * timeout -- which is how the async schema text invites you to submit it -- was killed at 60 s,
 * the very ceiling async exists to get out from under. The tool description made it worse by
 * saying options.timeout "cannot extend" the deadline, which reads as "timeout does not apply".
 *
 * Fix: an async job with no explicit timeout gets maxAsyncExecutionTimeSeconds; an explicit
 * timeout is honoured on both paths. Asserted through the ROUTE (handleToolCall, real
 * PathService) and on the job's final status in the registry, not on a returned flag.
 *
 * maxExecutionTimeSeconds is lowered to 2 s on the shared Spring bean so a ~4 s job crosses it;
 * cleanup() restores it, because this context is cached and shared with other specs.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Requires({ os.windows })
@Title('ExecuteService -- async jobs are not killed at the synchronous default (chain 18eb7c8d)')
class ExecuteServiceAsyncTimeoutSpec extends Specification {

    /** Absolute path: the gradle test worker's PATH has no System32 (see ExecuteServiceAsyncSpec). */
    static final String FOUR_SECONDS = '%SystemRoot%\\System32\\ping.exe -n 5 127.0.0.1'

    @Autowired ExecuteService executeService

    @TempDir Path tempDir

    int savedSyncMax

    def setup() {
        savedSyncMax = executeService.maxExecutionTimeSeconds
        executeService.maxExecutionTimeSeconds = 2
    }

    def cleanup() {
        executeService.maxExecutionTimeSeconds = savedSyncMax
    }

    private static Map payload(McpResponse r) {
        def content = r?.result?.content
        return content ? (new JsonSlurper().parseText(content[0].text as String) as Map) : [:]
    }

    private Map submitAsync(Map extraOptions) {
        Map<String, Object> options = ([workingDir: tempDir.toString(), async: true] + extraOptions) as Map<String, Object>
        payload(executeService.handleToolCall('execute',
            [action: 'cmd', script: FOUR_SECONDS, options: options] as Map<String, Object>, 'async-timeout'))
    }

    private Map finalStatus(String jobId) {
        Map status = [:]
        for (int i = 0; i < 150 && status.finished != true; i++) {
            Thread.sleep(100)
            status = executeService.jobRegistry.get(jobId).statusMap()
        }
        return status
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'AT-1: an async job with no explicit timeout outlives the synchronous default and completes'() {
        when: 'a ~4 s job is submitted async with no options.timeout, sync default 2 s'
        Map submit = submitAsync([:])

        then: 'the fixture reached dispatch -- a job id, not a refusal'
        submit.async == true
        submit.jobId

        and: 'the timeout it was given is the async one, not the 2 s sync default'
        (submit.timeoutSec as Integer) > 2

        when:
        Map status = finalStatus(submit.jobId as String)

        then: 'it ran to completion rather than being killed'
        status.finished == true
        status.status == 'completed'
        status.exitCode == 0
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    def 'AT-2 CONTROL: an explicit timeout is still honoured on the async path'() {
        when: 'the same job, async, with options.timeout=2'
        Map submit = submitAsync([timeout: 2])
        Map status = finalStatus(submit.jobId as String)

        then: 'the caller asked for 2 s and got 2 s'
        submit.timeoutSec == 2
        status.status == 'timeout'
    }

    def 'AT-3: the served async option text says what bounds an async job'() {
        when: 'the execute tool definition as served'
        // get('properties'), never .properties: on a Map the latter is the Map gotcha and NPEs.
        Map schema    = executeService.getToolDefinitions().find { (it.name as String) == 'execute' }.inputSchema as Map
        Map options   = (schema.get('properties') as Map).options as Map
        Map asyncProp = (options.get('properties') as Map).async as Map

        then: 'it names the timeout that applies, so nobody submits a long job expecting no bound'
        (asyncProp.description as String).contains('options.timeout')
    }
}
