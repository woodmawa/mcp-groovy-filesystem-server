package com.softwood.mcp.service

import com.softwood.mcp.ProcessIdentity
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * FS 0.9.92 P3 -- the {@link JobEventSink} that tells AW about an async job, so {@code flow_management action=progress}
 * can answer "where is everything I started" for FS jobs too, and a job outlives the FS process's memory.
 *
 * <p>Posts {@code flow_management action=record_job_event} to the AW companion's {@code /mcp}, the same JSON-RPC route
 * {@link ContextServerClient} uses for CS -- so stdio and HTTP FS processes report the same way. The session travels as
 * DATA: the companion that receives it is not this process. Sent from one daemon thread, never the job's; short
 * timeouts; FAIL-OPEN: nothing is retried or queued, and nothing here can affect the job it describes.</p>
 */
@Slf4j
@Service
@CompileStatic
class AwJobEventClient implements JobEventSink {

    static final int TAIL_LINES = 10
    static final int TAIL_SCAN_CHARS = 8000

    @Value('${mcp.agentic-workflow.url:http://localhost:8084}')
    String awUrl

    @Autowired(required = false)
    FilesystemTelemetryService telemetryService

    private final ExecutorService sender = Executors.newSingleThreadExecutor({ Runnable r ->
        Thread t = new Thread(r, 'fs-aw-job-events')
        t.daemon = true
        return t
    } as ThreadFactory)

    @Override
    void jobEvent(ExecuteJob job, String status) {
        try {
            String sid = telemetryService?.readActiveSessionId()
            Map<String, Object> payload = payloadFor(job, status, sid)
            sender.submit({ send(payload) } as Runnable)
        } catch (Exception e) {
            log.debug('AwJobEventClient: job event dropped [{} {}]: {}', job?.jobId, status, e.message)
        }
    }

    /** The record_job_event arguments for one transition. Finish fields only once the job has ended. */
    Map<String, Object> payloadFor(ExecuteJob job, String status, String sessionId) {
        Map<String, Object> p = [jobId    : job.jobId,
                                 jobStatus: status,
                                 sessionId: sessionId,
                                 jobAction: job.action,
                                 command  : job.commandSummary,
                                 ownerKey : ProcessIdentity.OWNER_KEY] as Map<String, Object>
        if (status != 'running') {
            p.exitCode = job.exitCode
            p.durationMs = job.elapsedMs()
            p.tail = tail(job)
        }
        return p
    }

    private static String tail(ExecuteJob job) {
        int len = job.stdoutLength()
        List<String> lines = job.stdoutFrom(Math.max(0, len - TAIL_SCAN_CHARS)).readLines()
        if (len > TAIL_SCAN_CHARS && !lines.isEmpty()) lines = lines.drop(1)   // first line may be cut
        List<String> last = lines.size() > TAIL_LINES ? lines.subList(lines.size() - TAIL_LINES, lines.size()) : lines
        String out = last.join('\n')
        if (job.error) out = (out ? out + '\n' : '') + 'error: ' + job.error
        return out
    }

    /** One POST; true when AW answered 200. Never throws. */
    boolean send(Map<String, Object> args) {
        try {
            Map<String, Object> arguments = [action: 'record_job_event'] as Map<String, Object>
            arguments.putAll(args)
            String json = JsonOutput.toJson([jsonrpc: '2.0', method: 'tools/call', id: 1,
                                             params : [name: 'flow_management', arguments: arguments]])
            HttpURLConnection conn = (HttpURLConnection) new URL("${awUrl}/mcp").openConnection()
            try {
                conn.requestMethod = 'POST'
                conn.doOutput = true
                conn.connectTimeout = 500
                conn.readTimeout = 1500
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.setRequestProperty('Accept', 'application/json, text/event-stream')
                conn.outputStream.withWriter('UTF-8') { Writer w -> w << json }
                return conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        } catch (Exception e) {
            log.debug('AwJobEventClient: record_job_event not sent [{}]: {}', args?.get('jobId'), e.message)
            return false
        }
    }
}
