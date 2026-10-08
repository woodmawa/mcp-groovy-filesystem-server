package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Title

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Found by the C4 blind graders (local-model-reliability arc, 2026-10-08), checked against the file:
 * submit()'s catch set status = 'failed' unconditionally. cancel() sets 'cancelled' and kills the
 * process tree, which typically makes the work throw (stream closed, interrupted) -- and the catch
 * then overwrote 'cancelled' with 'failed'. The success path already guarded on status == 'running';
 * the failure path did not.
 */
@Title('ExecuteJobRegistry -- a cancelled job stays cancelled when its work then throws')
class ExecuteJobRegistryCancelSpec extends Specification {

    ExecuteJobRegistry registry = new ExecuteJobRegistry()
    String workDir = System.getProperty('java.io.tmpdir')

    private static boolean waitFor(long ms, Closure<Boolean> cond) {
        long end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond.call()) return true
            Thread.sleep(20)
        }
        return cond.call()
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JC-1: cancel, then the work throws -- status stays cancelled, not failed'() {
        given: 'work that is running and will throw once released'
        CountDownLatch started = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        ExecuteJob job = registry.submit('cmd', 'spec', workDir, { ExecuteJob j ->
            started.countDown()
            release.await(20, TimeUnit.SECONDS)
            throw new IOException('Stream closed')
        } as Closure<Map<String, Object>>)
        assert started.await(10, TimeUnit.SECONDS)

        when: 'the job is cancelled and its work then fails'
        boolean cancelled = registry.cancel(job.jobId)
        release.countDown()

        then: 'the catch has run (error recorded) and did not overwrite the cancel'
        cancelled
        waitFor(10000) { job.error != null }
        job.status == 'cancelled'
        registry.get(job.jobId).status == 'cancelled'
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def 'JC-2: work that throws without a cancel is still failed'() {
        when:
        ExecuteJob job = registry.submit('cmd', 'spec', workDir, { ExecuteJob j ->
            throw new IllegalStateException('boom')
        } as Closure<Map<String, Object>>)

        then:
        waitFor(10000) { job.error != null }
        job.status == 'failed'
        job.error == 'boom'
        job.finished
    }
}
