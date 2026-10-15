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
            // FS 0.9.97 (G2): the session the job was started FOR wins over this process's claim -- the FS companion
            // a flow calls holds none, and its jobs went to AW with sessionId null, invisible to progress.
            String sid = job.callerSession ?: telemetryService?.readActiveSessionId()
            Map<String, Object> payload = payloadFor(job, status, sid)
            sender.submit({ send(payload) } as Runnable)
            // FS 0.9.97: a running job made for an AW node shows its output as that node's live progress too.
            if (status == 'running' && job.awRun && job.awNode && job.lineCount > 0) {
                nodeProgress([runId  : job.awRun, nodeId: job.awNode,
                              detail : clip("${job.action}: ${job.commandSummary}".toString(), 120),
                              done   : job.lineCount, current: job.lastLine] as Map<String, Object>)
            }
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
                                 ownerKey : ProcessIdentity.OWNER_KEY,
                                 // FS 0.9.97: output so far -- a heartbeat re-sends 'running' while these move
                                 lines    : job.lineCount,
                                 lastLine : job.lastLine] as Map<String, Object>
        if (status != 'running') {
            p.exitCode = job.exitCode
            p.durationMs = job.elapsedMs()
            p.tail = tail(job)
        }
        return p
    }

    private static String clip(String s, int max) { s == null ? null : (s.length() > max ? s.substring(0, max) : s) }

    private static String tail(ExecuteJob job) {
        int len = job.stdoutLength()
        List<String> lines = job.stdoutFrom(Math.max(0, len - TAIL_SCAN_CHARS)).readLines()
        if (len > TAIL_SCAN_CHARS && !lines.isEmpty()) lines = lines.drop(1)   // first line may be cut
        List<String> last = lines.size() > TAIL_LINES ? lines.subList(lines.size() - TAIL_LINES, lines.size()) : lines
        String out = last.join('\n')
        if (job.error) out = (out ? out + '\n' : '') + 'error: ' + job.error
        return out
    }

    /**
     * FS 0.9.93: the AW companion speaks Streamable HTTP -- {@code initialize} issues an {@code Mcp-Session-Id}, and
     * any other call without a KNOWN one is answered 400 (AW HttpMcpController). 0.9.92 posted bare tools/call and
     * every event was refused and dropped (obs 12722). The id is kept for reuse; a companion restart forgets it, so a
     * 400 opens a fresh session once and retries.
     */
    private volatile String mcpSessionId

    /**
     * FS 0.9.95 -- live progress for the AW node a gradle call is running for (record_node_progress). Queued on the
     * same single sender thread and returned at once: it is called from the gradle output reader, which must never
     * wait on AW. Fail-open like every event here.
     */
    void nodeProgress(Map<String, Object> args) {
        try {
            sender.submit({ sendAction('record_node_progress', args) } as Runnable)
        } catch (Exception e) {
            log.debug('AwJobEventClient: node progress dropped [{}]: {}', args?.get('runId'), e.message)
        }
    }

    /** One record_job_event; true when AW accepted it. Never throws. */
    boolean send(Map<String, Object> args) {
        return sendAction('record_job_event', args)
    }

    /** One flow_management call of {@code action} on the AW companion; true when AW accepted it. Never throws. */
    boolean sendAction(String action, Map<String, Object> args) {
        try {
            Map<String, Object> arguments = [action: action] as Map<String, Object>
            arguments.putAll(args)
            String json = JsonOutput.toJson([jsonrpc: '2.0', method: 'tools/call', id: 2,
                                             params : [name: 'flow_management', arguments: arguments]])
            if (mcpSessionId == null) mcpSessionId = openSession()
            int code = (int) post(json, mcpSessionId).code
            if (code == 400) {
                mcpSessionId = openSession()
                code = (int) post(json, mcpSessionId).code
            }
            if (code != 200) log.debug('AwJobEventClient: {} [{}] answered {}', action, args?.get('jobId') ?: args?.get('runId'), code)
            return code == 200
        } catch (Exception e) {
            log.debug('AwJobEventClient: {} not sent [{}]: {}', action, args?.get('jobId') ?: args?.get('runId'), e.message)
            return false
        }
    }

    private String openSession() {
        String init = JsonOutput.toJson([jsonrpc: '2.0', method: 'initialize', id: 1,
                                         params : [protocolVersion: '2025-03-26', capabilities: [:],
                                                   clientInfo     : [name: 'fs-job-events', version: '1']]])
        Map<String, Object> r = post(init, null)
        return ((int) r.code) == 200 ? (String) r.sessionId : null
    }

    private Map<String, Object> post(String json, String sessionId) {
        HttpURLConnection conn = (HttpURLConnection) new URL("${awUrl}/mcp").openConnection()
        try {
            conn.requestMethod = 'POST'
            conn.doOutput = true
            conn.connectTimeout = 500
            conn.readTimeout = 1500
            conn.setRequestProperty('Content-Type', 'application/json')
            conn.setRequestProperty('Accept', 'application/json, text/event-stream')
            if (sessionId) conn.setRequestProperty('Mcp-Session-Id', sessionId)
            conn.outputStream.withWriter('UTF-8') { Writer w -> w << json }
            int code = conn.responseCode
            return [code: code, sessionId: conn.getHeaderField('Mcp-Session-Id')] as Map<String, Object>
        } finally {
            conn.disconnect()
        }
    }
}
