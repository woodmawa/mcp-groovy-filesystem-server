package com.softwood.mcp.service.write

import spock.lang.Specification
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

/**
 * AtomicWriteRetrySpec &mdash; FS 0.9.25.
 *
 * <h3>What this actually is</h3>
 * The FS suite failed exactly one spec per full run and a different one each time &mdash;
 * {@code WriteCommitterSpec CT-PCOMMIT-2} on one tree, {@code FileContractSpec CT-18} on a clean
 * one &mdash; while both passed in isolation. That was carried as "the suite is flaky". It is not.
 * It is a real, intermittent <b>data-losing</b> defect in {@code WriteUtils.atomicWrite}, and the
 * suite has been reporting it honestly.
 *
 * <p>The CT-18 failure text is the whole diagnosis:
 * <pre>
 * ...\ct18.groovy.tmp -&gt; ...\ct18.groovy    isError:true
 * </pre>
 * A bare {@code source -> target} with no reason is the {@code toString()} of a
 * {@code java.nio.file.FileSystemException}. Reproduced deliberately rather than inferred: hold any
 * open handle on the target and
 * {@code Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)} throws
 * {@code AccessDeniedException} with message {@code "<tmp> -> <target>"} and {@code getReason()}
 * null &mdash; the exact shape observed &mdash; and succeeds the instant the handle closes.
 *
 * <p>On Windows that handle is routinely somebody else's and it is gone milliseconds later:
 * Defender scanning a freshly created {@code .tmp}, the search indexer, an editor, a sibling
 * thread. `atomicWrite` caught only {@code AtomicMoveNotSupportedException}, so a transient
 * sharing violation propagated as a permanent failure, the {@code catch} deleted the {@code .tmp},
 * and <b>the write was lost</b> &mdash; reported to the caller as "atomic write failed". Under a
 * 350-test suite hammering %TEMP% the odds of hitting one such window per run are high; alone they
 * are nearly nil. That is precisely the signature that was read as flakiness.
 *
 * <h3>A/B &mdash; both directions</h3>
 * CT-AWR-1/2 prove a transient lock no longer costs the write. CT-AWR-3 is the half that matters
 * more: a lock that is never released must still FAIL, and fail promptly. A retry loop that turns
 * a permanent error into a hang, or into silence, would pass CT-AWR-1 and be far worse than the
 * defect it replaced. CT-AWR-4 pins the uncontended path unchanged.
 *
 * CONTRACT:
 *  CT-AWR-1  a transient lock on the target does not lose the write
 *  CT-AWR-2  no .tmp is left behind after a retried write
 *  CT-AWR-3  a lock that is never released still throws, and within a bounded time
 *  CT-AWR-4  the uncontended path is unchanged: exact bytes, no retry-induced delay
 */
@Title('a transient Windows lock must not lose a write')
@groovy.transform.CompileDynamic
class AtomicWriteRetrySpec extends Specification {

    /** Longer than the retry budget, so CT-AWR-3 proves boundedness rather than patience. */
    private static final long PERMANENT_FAILURE_CEILING_MS = 15_000L

    File   tempDir
    Path   target
    RandomAccessFile holder

    def setup() {
        tempDir = Files.createTempDirectory('atomic_write_retry_spec_').toFile()
        tempDir.deleteOnExit()
        target = new File(tempDir, 'target.txt').toPath()
        Files.write(target, 'original'.bytes)
    }

    def cleanup() {
        try { holder?.close() } catch (Exception ignored) { }
        tempDir?.deleteDir()
    }

    /** Holds an open handle on the target for `holdMs`, then releases it. */
    private void holdTargetFor(long holdMs) {
        holder = new RandomAccessFile(target.toFile(), 'rw')
        Thread releaser = new Thread({
            try { Thread.sleep(holdMs) } catch (InterruptedException ignored) { }
            try { holder.close() } catch (Exception ignored) { }
        } as Runnable)
        releaser.daemon = true
        releaser.start()
    }

    // -------------------------------------------------------------------------
    def 'CT-AWR-1: a transient lock on the target does not lose the write'() {
        given: 'the handle Defender or the indexer would hold, gone 300ms later'
        holdTargetFor(300L)

        when:
        WriteUtils.atomicWrite(target, 'updated'.bytes)

        then: '''RED before the fix: AccessDeniedException "<tmp> -> <target>", the .tmp deleted
                 by the catch, and the write silently gone'''
        noExceptionThrown()
        new String(Files.readAllBytes(target)) == 'updated'
    }

    def 'CT-AWR-2: no .tmp is left behind after a retried write'() {
        given:
        holdTargetFor(300L)

        when:
        WriteUtils.atomicWrite(target, 'updated'.bytes)

        then: 'a retry loop must not leak a sibling temp file per attempt'
        !Files.exists(target.resolveSibling(target.fileName.toString() + '.tmp'))
        tempDir.listFiles().findAll { it.name.endsWith('.tmp') }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // The direction that matters more.
    // -------------------------------------------------------------------------
    def 'CT-AWR-3: a lock that is never released still throws, and promptly'() {
        given: 'a handle held for the whole test -- a genuinely unwritable target'
        holder = new RandomAccessFile(target.toFile(), 'rw')

        when:
        long startedAt = System.currentTimeMillis()
        WriteUtils.atomicWrite(target, 'updated'.bytes)

        then: '''retrying must not turn a permanent failure into a hang or, worse, into silence.
                 The caller has to be told the write did not happen.'''
        thrown(Exception)
        (System.currentTimeMillis() - startedAt) < PERMANENT_FAILURE_CEILING_MS

        and: 'the original content is intact and no temp file is left behind'
        new String(Files.readAllBytes(target)) == 'original'
        !Files.exists(target.resolveSibling(target.fileName.toString() + '.tmp'))
    }

    def 'CT-AWR-4: the uncontended path is unchanged'() {
        when: 'no handle held at all -- the overwhelmingly common case'
        long startedAt = System.currentTimeMillis()
        WriteUtils.atomicWrite(target, 'updated'.bytes)
        long elapsed = System.currentTimeMillis() - startedAt

        then: 'exact bytes, and no backoff paid by a write that never needed one'
        new String(Files.readAllBytes(target)) == 'updated'
        elapsed < 1_000L
    }
}
