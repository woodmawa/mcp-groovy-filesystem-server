package com.softwood.mcp.service

import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Title

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FS 0.9.92 P3 (BUILD-BRIEF-2026-10-14-progress-and-aw-fixes): an async job's start and finish leave this process.
 *
 * <p>Until now a job lived only in the {@code jobs} map of the FS process that started it: invisible to AW, evicted
 * after 30 minutes, gone on restart. The registry now tells a {@link JobEventSink} when a job starts and, exactly once,
 * when it ends; {@link AwJobEventClient} is the sink that posts it to AW {@code flow_management action=record_job_event},
 * where {@code action=progress} reads it. FAIL-OPEN: a job never fails because the event could not be sent.</p>
 */
@Title('FS async jobs report their lifecycle to AW')
class ExecuteJobEventsSpec extends Specification {

    ExecuteJobRegistry registry = new ExecuteJobRegistry()
    String workDir = System.getProperty('java.io.tmpdir')
    List<String> seen = new CopyOnWriteArrayList<String>()
    List<ExecuteJob> jobsSeen = new CopyOnWriteArrayList<ExecuteJob>()

    private static boolean waitFor(long ms, Closure<Boolean> cond) {
        long end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond.call()) return true
            Thread.sleep(20)
        }
        return cond.call()
    }

    private void recordingSink() {
        registry.jobEventSink = { ExecuteJob j, String status -> seen << status; jobsSeen << j } as JobEventSink
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JE-1: a job reports running at submit and completed at the end, with its exit code already set'() {
        given:
        recordingSink()

        when:
        ExecuteJob job = registry.submit('powershell', 'gradle test', workDir, { ExecuteJob j ->
            j.appendStdout('compiling\nBUILD SUCCESSFUL\n')
            [success: true, exitCode: 0] as Map<String, Object>
        } as Closure<Map<String, Object>>)

        then:
        waitFor(10000) { seen.size() == 2 }
        seen == ['running', 'completed']
        jobsSeen[1].jobId == job.jobId
        jobsSeen[1].exitCode == 0
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JE-2: FAIL-OPEN -- a sink that throws never fails the job'() {
        given:
        registry.jobEventSink = { ExecuteJob j, String status -> throw new IllegalStateException('AW down') } as JobEventSink

        when:
        ExecuteJob job = registry.submit('cmd', 'x', workDir, { ExecuteJob j ->
            [success: true, exitCode: 0] as Map<String, Object>
        } as Closure<Map<String, Object>>)

        then:
        job != null
        waitFor(10000) { job.finished }
        job.status == 'completed'
        job.error == null
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JE-3: a cancelled job reports cancelled ONCE -- not cancelled and then failed'() {
        given:
        recordingSink()
        CountDownLatch started = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        ExecuteJob job = registry.submit('cmd', 'spec', workDir, { ExecuteJob j ->
            started.countDown()
            release.await(20, TimeUnit.SECONDS)
            throw new IOException('Stream closed')
        } as Closure<Map<String, Object>>)
        assert started.await(10, TimeUnit.SECONDS)

        when:
        registry.cancel(job.jobId)
        release.countDown()

        then:
        waitFor(10000) { job.error != null }
        Thread.sleep(200)
        seen == ['running', 'cancelled']
    }

    def 'AJ-1: the client posts record_job_event to AW over MCP, with the session and the output tail'() {
        given: 'a stand-in for the AW companion'
        List<String> bodies = new CopyOnWriteArrayList<String>()
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/mcp') { ex ->
            bodies << ex.requestBody.getText('UTF-8')
            byte[] ok = '{"jsonrpc":"2.0","id":1,"result":{}}'.getBytes('UTF-8')
            ex.sendResponseHeaders(200, ok.length); ex.responseBody.write(ok); ex.close()
        }
        server.start()
        AwJobEventClient client = new AwJobEventClient(awUrl: "http://127.0.0.1:${server.address.port}".toString())
        ExecuteJob job = new ExecuteJob(jobId: 'j-9', action: 'powershell', commandSummary: 'gradle test',
                                        workingDir: workDir, startedAt: System.currentTimeMillis() - 1500L)
        (1..15).each { job.appendStdout("line ${it}\n") }
        job.status = 'completed'
        job.exitCode = 0
        job.finishedAt = System.currentTimeMillis()

        when:
        boolean sent = client.send(client.payloadFor(job, 'completed', '2026-10-14-14-01'))

        then:
        sent
        Map rpc = new JsonSlurper().parseText(bodies[0]) as Map
        rpc.method == 'tools/call'
        rpc.params.name == 'flow_management'
        Map a = rpc.params.arguments as Map
        a.action == 'record_job_event'
        a.jobId == 'j-9'
        a.jobStatus == 'completed'
        a.sessionId == '2026-10-14-14-01'
        a.exitCode == 0
        (a.durationMs as long) >= 1500L
        a.command == 'gradle test'
        (a.tail as String).readLines() == (6..15).collect { "line ${it}".toString() }

        cleanup:
        server?.stop(0)
    }

    def 'AJ-2: FAIL-OPEN -- AW not listening is false, never an exception'() {
        given:
        ServerSocket s = new ServerSocket(0)
        int closedPort = s.localPort
        s.close()
        AwJobEventClient client = new AwJobEventClient(awUrl: "http://127.0.0.1:${closedPort}".toString())

        when:
        boolean sent = client.send([jobId: 'j-1', jobStatus: 'running'] as Map<String, Object>)

        then:
        noExceptionThrown()
        !sent
    }
}
