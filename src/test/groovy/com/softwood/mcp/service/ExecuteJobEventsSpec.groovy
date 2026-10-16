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
@spock.lang.Tag('slow')   // WS10-C tiering: measured >= 0.8 s per full run
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

    /**
     * A stand-in for the AW companion that behaves like the real one (HttpMcpController, Streamable HTTP):
     * {@code initialize} issues an {@code Mcp-Session-Id}; any other call without a KNOWN id is 400. FS 0.9.92's
     * AJ-1 used a stand-in that accepted anything, and passed against a route the real companion refuses
     * (obs 12722). Records the bodies of the calls it ACCEPTED, apart from initialize.
     */
    private static class StrictAw {
        HttpServer server
        Set<String> known = java.util.concurrent.ConcurrentHashMap.newKeySet()
        List<String> accepted = new CopyOnWriteArrayList<String>()
        int initializes = 0
        int refused = 0

        StrictAw start() {
            server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
            server.createContext('/mcp') { ex ->
                String body = ex.requestBody.getText('UTF-8')
                Map rpc = new JsonSlurper().parseText(body) as Map
                String sid = ex.requestHeaders.getFirst('Mcp-Session-Id')
                int code = 200
                if (rpc.method == 'initialize') {
                    initializes++
                    String issued = UUID.randomUUID().toString()
                    known << issued
                    ex.responseHeaders.add('Mcp-Session-Id', issued)
                } else if (!sid || !known.contains(sid)) {
                    refused++
                    code = 400
                } else {
                    accepted << body
                }
                byte[] out = (code == 200 ? '{"jsonrpc":"2.0","id":1,"result":{}}'
                                          : '{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Missing Mcp-Session-Id header"}}').getBytes('UTF-8')
                ex.sendResponseHeaders(code, out.length); ex.responseBody.write(out); ex.close()
            }
            server.start()
            return this
        }

        String url() { "http://127.0.0.1:${server.address.port}".toString() }
        void forgetSessions() { known.clear() }   // what a companion restart does
        void stop() { server?.stop(0) }
    }

    def 'AJ-1: the client opens an MCP session, then posts record_job_event with the session and the output tail'() {
        given: 'a stand-in for the AW companion that enforces sessions like the real one'
        StrictAw aw = new StrictAw().start()
        List<String> bodies = aw.accepted
        AwJobEventClient client = new AwJobEventClient(awUrl: aw.url())
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

        and: 'one handshake, nothing refused'
        aw.initializes == 1
        aw.refused == 0

        when: 'a second event reuses the session'
        boolean again = client.send([jobId: 'j-9', jobStatus: 'running'] as Map<String, Object>)

        then:
        again
        aw.initializes == 1
        bodies.size() == 2

        cleanup:
        aw?.stop()
    }

    def 'AJ-3: after a companion restart the session is unknown -- the client re-opens one and the event still lands'() {
        given:
        StrictAw aw = new StrictAw().start()
        AwJobEventClient client = new AwJobEventClient(awUrl: aw.url())
        assert client.send([jobId: 'j-1', jobStatus: 'running'] as Map<String, Object>)

        when: 'the companion restarts and forgets every session'
        aw.forgetSessions()
        boolean sent = client.send([jobId: 'j-1', jobStatus: 'completed', exitCode: 0] as Map<String, Object>)

        then:
        sent
        aw.initializes == 2
        aw.refused == 1
        aw.accepted.size() == 2

        cleanup:
        aw?.stop()
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
