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
 * FS 0.9.97 (HANDOFF-2026-10-15 G2 + live output; agreed with Will 2026-10-15 12:03).
 *
 * <p>G2 -- a job is attributed to whoever STARTED it. The AW companion calls FS's companion, which holds no claim, so a
 * flow-started job went to AW with sessionId null and never appeared in {@code progress}. The caller's declared session
 * (X-Mcp-Caller-Session) and the AW run/node (X-Aw-Run / X-Aw-Node) are now captured on the job at submit, on the
 * request thread -- the work runs on another, where the request's ThreadLocal is gone.</p>
 *
 * <p>Live output -- a running job re-reports itself every heartbeat while its output grows: line count and last line,
 * so {@code progress} shows a long job moving rather than only its age. Nothing is posted when nothing changed, and
 * nothing after the job has ended (a late 'running' would read as running for ever). A job made for an AW node also
 * posts the same detail as that node's progress.</p>
 *
 * <p>Asserted on what a strict stand-in AW actually ACCEPTS (the consumer side), not on the client's own payload map:
 * the 2026-10-14 lesson was specs proving the producer while the consumer did something else.</p>
 */
@Title('FS jobs are attributed to their caller and report live output')
class ExecuteJobVisibilitySpec extends Specification {

    ExecuteJobRegistry registry = new ExecuteJobRegistry()
    String workDir = System.getProperty('java.io.tmpdir')

    def cleanup() {
        AwCallContext.clear()
        registry.shutdownHeartbeat()
    }

    private static boolean waitFor(long ms, Closure<Boolean> cond) {
        long end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond.call()) return true
            Thread.sleep(20)
        }
        return cond.call()
    }

    /** Same contract as the real companion (initialize issues Mcp-Session-Id; unknown id = 400). */
    private static class StrictAw {
        HttpServer server
        Set<String> known = java.util.concurrent.ConcurrentHashMap.newKeySet()
        List<Map> accepted = new CopyOnWriteArrayList<Map>()

        StrictAw start() {
            server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
            server.createContext('/mcp') { ex ->
                Map rpc = new JsonSlurper().parseText(ex.requestBody.getText('UTF-8')) as Map
                String sid = ex.requestHeaders.getFirst('Mcp-Session-Id')
                int code = 200
                if (rpc.method == 'initialize') {
                    String issued = UUID.randomUUID().toString()
                    known << issued
                    ex.responseHeaders.add('Mcp-Session-Id', issued)
                } else if (!sid || !known.contains(sid)) {
                    code = 400
                } else {
                    accepted << ((rpc.params as Map).arguments as Map)
                }
                byte[] out = '{"jsonrpc":"2.0","id":1,"result":{}}'.getBytes('UTF-8')
                ex.sendResponseHeaders(code, out.length); ex.responseBody.write(out); ex.close()
            }
            server.start()
            return this
        }

        String url() { "http://127.0.0.1:${server.address.port}".toString() }
        List<Map> of(String action) { accepted.findAll { it.action == action } }
        void stop() { server?.stop(0) }
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JV-1 (G2): a job submitted for a declared caller session reaches AW under THAT session, though this process holds no claim'() {
        given: 'a strict AW, and a client whose own claim resolves to nothing -- the FS companion'
        StrictAw aw = new StrictAw().start()
        AwJobEventClient client = new AwJobEventClient(awUrl: aw.url())
        client.telemetryService = Stub(FilesystemTelemetryService) { readActiveSessionId() >> null }
        registry.jobEventSink = client
        CountDownLatch release = new CountDownLatch(1)

        when: 'the call that submits it declared a session (X-Mcp-Caller-Session), and the work runs on another thread'
        AwCallContext.set('run-9', 'run-build', '2026-10-14-14-01')
        ExecuteJob job = registry.submit('powershell', 'gradle bootJar', workDir, { ExecuteJob j ->
            release.await(10, TimeUnit.SECONDS)
            [success: true, exitCode: 0] as Map<String, Object>
        } as Closure<Map<String, Object>>)
        AwCallContext.clear()
        release.countDown()

        then: 'AW accepted a start and a finish, both filed under the caller session'
        waitFor(10000) { aw.of('record_job_event').size() == 2 }
        aw.of('record_job_event')*.sessionId == ['2026-10-14-14-01', '2026-10-14-14-01']
        aw.of('record_job_event')*.jobStatus == ['running', 'completed']
        job.callerSession == '2026-10-14-14-01'

        cleanup:
        aw.stop()
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JV-2 CONTROL: with no declared session the job still falls back to this process claim'() {
        given:
        StrictAw aw = new StrictAw().start()
        AwJobEventClient client = new AwJobEventClient(awUrl: aw.url())
        client.telemetryService = Stub(FilesystemTelemetryService) { readActiveSessionId() >> 'claimed-chat' }
        registry.jobEventSink = client

        when:
        registry.submit('cmd', 'x', workDir, { ExecuteJob j -> [success: true, exitCode: 0] as Map<String, Object> }
            as Closure<Map<String, Object>>)

        then:
        waitFor(10000) { aw.of('record_job_event').size() == 2 }
        aw.of('record_job_event')*.sessionId == ['claimed-chat', 'claimed-chat']

        cleanup:
        aw.stop()
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JV-3: while output grows a running job re-reports with its line count and last line; silence posts nothing'() {
        given: 'a fast heartbeat and a recording sink'
        registry.heartbeatMs = 50L
        List<Map> beats = new CopyOnWriteArrayList<Map>()
        registry.jobEventSink = { ExecuteJob j, String st ->
            beats << [status: st, lines: j.lineCount, last: j.lastLine]
        } as JobEventSink
        CountDownLatch more = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)

        when:
        ExecuteJob job = registry.submit('powershell', 'gradle bootJar', workDir, { ExecuteJob j ->
            j.appendStdout('> Task :compileGroovy\n')
            j.appendStdout('> Task :bootJar\n')
            more.await(10, TimeUnit.SECONDS)
            j.appendStdout('BUILD SUCCESSFUL\n')
            release.await(10, TimeUnit.SECONDS)
            [success: true, exitCode: 0] as Map<String, Object>
        } as Closure<Map<String, Object>>)

        then: 'a heartbeat carries the output so far'
        waitFor(5000) { beats.any { it.status == 'running' && it.lines == 2 } }
        beats.find { it.lines == 2 }.last == '> Task :bootJar'

        when: 'nothing new for several heartbeats'
        int before = beats.size()
        Thread.sleep(400)

        then: 'nothing more was posted'
        beats.size() == before

        when: 'more output, then the end'
        more.countDown()
        waitFor(5000) { beats.any { it.lines == 3 } }
        release.countDown()
        waitFor(5000) { job.finished && beats.last().status == 'completed' }
        Thread.sleep(300)

        then: 'the last line moved, and nothing was posted after the finish'
        beats.find { it.lines == 3 }.last == 'BUILD SUCCESSFUL'
        beats.last().status == 'completed'
        beats.count { it.status == 'completed' } == 1
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JV-4: a job made for an AW node also reports its live output as that node progress'() {
        given:
        StrictAw aw = new StrictAw().start()
        AwJobEventClient client = new AwJobEventClient(awUrl: aw.url())
        client.telemetryService = Stub(FilesystemTelemetryService) { readActiveSessionId() >> null }
        registry.jobEventSink = client
        registry.heartbeatMs = 50L
        CountDownLatch release = new CountDownLatch(1)

        when:
        AwCallContext.set('run-9', 'package', '2026-10-14-14-01')
        registry.submit('powershell', 'gradlew packageMcpbThin', workDir, { ExecuteJob j ->
            j.appendStdout('> Task :stageJarForMcpb\n')
            release.await(10, TimeUnit.SECONDS)
            [success: true, exitCode: 0] as Map<String, Object>
        } as Closure<Map<String, Object>>)
        AwCallContext.clear()

        then: 'AW accepted node progress for that run and node, carrying the output so far'
        waitFor(5000) { aw.of('record_node_progress').size() >= 1 }
        Map np = aw.of('record_node_progress')[0]
        np.runId == 'run-9'
        np.nodeId == 'package'
        np.done == 1
        np.current == '> Task :stageJarForMcpb'

        and: 'and the job heartbeat carried the same lines, under the caller session'
        waitFor(5000) { aw.of('record_job_event').any { it.jobStatus == 'running' && it.lines == 1 } }
        aw.of('record_job_event').find { it.lines == 1 }.lastLine == '> Task :stageJarForMcpb'
        aw.of('record_job_event').find { it.lines == 1 }.sessionId == '2026-10-14-14-01'

        cleanup:
        release.countDown()
        aw.stop()
    }

    def 'JV-5: line count and last line track appended output, ignoring blank lines for the last line'() {
        given:
        ExecuteJob j = new ExecuteJob(jobId: 'j')

        when:
        j.appendStdout('one\n')
        j.appendStdout('two\n')
        j.appendStdout('\n')

        then:
        j.lineCount == 3
        j.lastLine == 'two'
    }

    // FS 0.9.98 (Will 2026-10-15: make it visible). Found live: an install killed the AW companion at 13:15; for 11
    // minutes every job event was refused and dropped, and AW progress answered quiet:true -- nothing said so.
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JV-6: refused posts are counted and named; the first accepted post clears them'() {
        given: 'a port nothing listens on'
        int closedPort = new ServerSocket(0).withCloseable { it.localPort }
        AwJobEventClient client = new AwJobEventClient(awUrl: "http://127.0.0.1:${closedPort}".toString())

        expect: 'healthy until something fails'
        client.health() == null

        when:
        client.send([jobId: 'j1', jobStatus: 'running'] as Map<String, Object>)
        client.send([jobId: 'j1', jobStatus: 'completed'] as Map<String, Object>)
        Map h = client.health()

        then:
        h.reachable == false
        h.dropped == 2
        h.since != null
        (h.note as String).contains('progress')

        when: 'AW is back'
        StrictAw aw = new StrictAw().start()
        client.awUrl = aw.url()
        client.send([jobId: 'j2', jobStatus: 'running'] as Map<String, Object>)

        then:
        client.health() == null

        cleanup:
        aw?.stop()
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JV-7: job_status through ExecuteService says when job events are not reaching AW; a healthy sink adds nothing'() {
        given:
        ExecuteService service = new ExecuteService()
        service.jobRegistry = registry
        Map unhealthy = [reachable: false, since: '2026-10-15T12:15:21Z', dropped: 7, note: 'x'] as Map<String, Object>
        Map current = null
        registry.jobEventSink = new JobEventSink() {
            void jobEvent(ExecuteJob j, String s) { }
            Map<String, Object> health() { current }
        }
        ExecuteJob job = registry.submit('cmd', 'x', workDir, { ExecuteJob j ->
            [success: true, exitCode: 0] as Map<String, Object> } as Closure<Map<String, Object>>)

        when:
        Map healthy = payload(service.handleToolCall('execute', [action: 'job_status', options: [jobId: job.jobId]], 'spec'))
        current = unhealthy
        Map broken = payload(service.handleToolCall('execute', [action: 'job_status', options: [jobId: job.jobId]], 'spec'))

        then:
        !healthy.containsKey('aw_events')
        broken.aw_events.reachable == false
        broken.aw_events.dropped == 7
    }

    private static Map payload(def response) {
        def content = response?.result?.content
        return content ? (new groovy.json.JsonSlurper().parseText(content[0].text as String) as Map) : [:]
    }

}