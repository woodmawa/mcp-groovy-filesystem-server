package com.softwood.mcp.service

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.promise.PromiseFactory
import org.softwood.promise.core.PromiseConfiguration
import org.springframework.stereotype.Service

import java.util.concurrent.ConcurrentHashMap

/**
 * FS-EXEC-2 — background execution jobs.
 *
 * <h3>Why this exists</h3>
 * {@code execute} is bounded by a hard ~60s timeout imposed at the MCP client boundary, and
 * {@code options.timeout} does NOT extend it — FS honours that value in
 * {@code process.waitFor}, but the caller has already given up. Worse, the blocked call
 * serialises everything behind it: a cold Gradle compile timed out at the tool boundary while
 * still running, then blocked the next two calls (observation 9821, chain ef8cae5c).
 *
 * The 60s ceiling is not ours to raise. The fix is to stop blocking underneath it: submit the
 * work, return a job id immediately, and let the caller poll cheaply.
 *
 * <h3>Why GCU</h3>
 * {@code org.softwood:GroovyConcurrentUtils} is already an FS dependency and is the async
 * primitive the rest of this platform is built on (AW's TaskGraph runs on the same Promise
 * abstraction). {@link PromiseFactory#executeAsync} gives cancellation and completion state
 * without hand-rolling an executor, and keeps FS consistent with AW rather than inventing a
 * second concurrency model in the same stack.
 */
@Slf4j
@Service
@CompileStatic
class ExecuteJobRegistry {

    /** Jobs are evicted once complete and older than this. */
    static final long RETAIN_COMPLETED_MS = 30L * 60L * 1000L

    /** Hard ceiling on retained jobs; oldest finished jobs are evicted first. */
    static final int MAX_JOBS = 100

    private final Map<String, ExecuteJob> jobs = new ConcurrentHashMap<String, ExecuteJob>()

    private PromiseFactory promiseFactory

    /**
     * Test seam (practice #1166 seam 1): explicit setter so a @CompileDynamic spec can inject
     * a factory. Production resolves lazily from GCU's static configuration.
     */
    @CompileDynamic
    void setPromiseFactory(PromiseFactory f) { this.promiseFactory = f }

    PromiseFactory getPromiseFactory() {
        if (promiseFactory == null) promiseFactory = PromiseConfiguration.getFactory()
        return promiseFactory
    }

    /**
     * Submit work to run in the background.
     *
     * @param action         executor label (bash|cmd|powershell|python|groovy)
     * @param commandSummary short, sanitised description for listings
     * @param workingDir     resolved working directory
     * @param work           the actual execution; receives the job so it can stream output
     *                       into it and register the Process for cancellation
     * @return the registered job, already running
     */
    ExecuteJob submit(String action, String commandSummary, String workingDir,
                      Closure<Map<String, Object>> work) {
        evict()
        ExecuteJob job = new ExecuteJob(
            jobId         : UUID.randomUUID().toString(),
            action        : action,
            commandSummary: commandSummary,
            workingDir    : workingDir,
            startedAt     : System.currentTimeMillis()
        )
        jobs.put(job.jobId, job)
        job.promise = getPromiseFactory().executeAsync({ ->
            try {
                Map<String, Object> result = work.call(job)
                job.exitCode = result?.exitCode as Integer
                // A timed-out job is not a failed one: the distinction matters to the caller.
                if (job.status == 'running') {
                    job.status = (result?.timedOut) ? 'timeout'
                               : (result?.success) ? 'completed' : 'failed'
                }
                return result
            } catch (Throwable t) {
                job.status = 'failed'
                job.error = t.message ?: t.class.simpleName
                log.warn('ExecuteJobRegistry: job {} failed - {}', job.jobId, job.error)
                throw t
            } finally {
                job.finishedAt = System.currentTimeMillis()
                // The job owns its script file (see ExecuteService.submitAsyncJob) precisely
                // because the submitting call has long since returned.
                try { job.tempScript?.delete() } catch (Exception ignored) { }
            }
        } as Closure<Map<String, Object>>)
        log.info('ExecuteJobRegistry: submitted job {} action={} dir={}', job.jobId, action, workingDir)
        return job
    }

    ExecuteJob get(String jobId) { jobId ? jobs.get(jobId) : null }

    /** Newest first. */
    List<ExecuteJob> list() {
        return jobs.values().toList().sort { ExecuteJob a, ExecuteJob b -> b.startedAt <=> a.startedAt }
    }

    /**
     * Cancel a running job. Destroys the OS process first — cancelling only the promise would
     * leave the build running and the caller believing it had stopped.
     */
    boolean cancel(String jobId) {
        ExecuteJob job = jobs.get(jobId)
        if (!job || job.finished) return false
        job.status = 'cancelled'
        try { job.process?.destroyForcibly() } catch (Exception ignored) { }
        try { job.promise?.cancel(true) } catch (Exception ignored) { }
        try { job.tempScript?.delete() } catch (Exception ignored) { }
        job.finishedAt = System.currentTimeMillis()
        log.info('ExecuteJobRegistry: cancelled job {}', jobId)
        return true
    }

    /** Drop finished jobs past the retention window, then trim to MAX_JOBS oldest-finished-first. */
    private void evict() {
        long now = System.currentTimeMillis()
        jobs.values().findAll { ExecuteJob j -> j.finished && (now - j.finishedAt) > RETAIN_COMPLETED_MS }
            .each { ExecuteJob j -> jobs.remove(j.jobId) }
        if (jobs.size() >= MAX_JOBS) {
            jobs.values().findAll { ExecuteJob j -> j.finished }
                .sort { ExecuteJob a, ExecuteJob b -> a.finishedAt <=> b.finishedAt }
                .take(Math.max(1, jobs.size() - MAX_JOBS + 1))
                .each { ExecuteJob j -> jobs.remove(j.jobId) }
        }
    }
}

/**
 * One background execution. Mutable fields are written by the executing virtual thread and read
 * by polling MCP calls, hence volatile; the output buffers are guarded by their own monitor.
 */
@CompileStatic
class ExecuteJob {
    String jobId
    String action
    String commandSummary
    String workingDir
    long startedAt

    volatile long finishedAt
    volatile String status = 'running'
    volatile Integer exitCode
    volatile String error
    volatile Process process

    /** Temp script backing this job; deleted once the job finishes, not when submit returns. */
    volatile File tempScript

    Promise<Map<String, Object>> promise

    private final StringBuilder stdoutBuf = new StringBuilder()
    private final StringBuilder stderrBuf = new StringBuilder()

    boolean isFinished() { status != 'running' }

    void appendStdout(String s) { synchronized (stdoutBuf) { stdoutBuf.append(s) } }
    void appendStderr(String s) { synchronized (stderrBuf) { stderrBuf.append(s) } }

    int stdoutLength() { synchronized (stdoutBuf) { return stdoutBuf.length() } }
    int stderrLength() { synchronized (stderrBuf) { return stderrBuf.length() } }

    /** Incremental read so a caller can tail a long build without re-sending what it has. */
    String stdoutFrom(int offset) {
        synchronized (stdoutBuf) {
            int from = Math.max(0, Math.min(offset, stdoutBuf.length()))
            return stdoutBuf.substring(from)
        }
    }

    String stderrFrom(int offset) {
        synchronized (stderrBuf) {
            int from = Math.max(0, Math.min(offset, stderrBuf.length()))
            return stderrBuf.substring(from)
        }
    }

    long elapsedMs() {
        return (finished && finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - startedAt
    }

    Map<String, Object> statusMap() {
        return [
            jobId         : jobId,
            status        : status,
            action        : action,
            command       : commandSummary,
            workingDir    : workingDir,
            exitCode      : exitCode,
            error         : error,
            elapsedMs     : elapsedMs(),
            stdoutBytes   : stdoutLength(),
            stderrBytes   : stderrLength(),
            finished      : finished
        ] as Map<String, Object>
    }
}
