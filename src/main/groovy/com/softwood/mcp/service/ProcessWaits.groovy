package com.softwood.mcp.service

import groovy.transform.CompileStatic

import java.util.concurrent.TimeUnit

/**
 * FS 0.9.73 (chain bacfc195) -- how FS waits for a child process and the threads reading its output.
 *
 * <p>Two rules, both learned from a 494 s wedge on 2026-10-08:</p>
 * <ul>
 *   <li><b>One deadline.</b> The child and its readers share the caller's timeout. Before this, each reader was
 *       joined for the WHOLE remaining budget in turn, so "timeout 300" could mean nearly 600 s.</li>
 *   <li><b>A short drain after exit.</b> Once the child has exited, its readers get {@link #EXIT_DRAIN_MS} to reach
 *       end of stream. If they have not, something the child started is holding the pipe open -- on Windows a
 *       Gradle daemon inherits it -- and that is not the command's output. We stop waiting and say so.</li>
 * </ul>
 * <p>On timeout the whole process tree is killed, descendants first: destroying only the shell left the real work
 * (and its pipes) running.</p>
 */
@CompileStatic
class ProcessWaits {

    static final long EXIT_DRAIN_MS = 5_000L

    static final String HELD_OPEN_NOTE = 'output still held open by a process the command started (e.g. a Gradle ' +
            'daemon) -- returned what the command wrote before it exited'

    /** What happened: finished=false means it timed out (and the tree has been killed). */
    static class Outcome {
        boolean finished
        boolean heldOpen
    }

    /**
     * Waits for {@code p} until {@code deadlineMs} (epoch ms), then gives {@code readers} at most
     * {@code drainMs} to finish. Never waits past the deadline for the child, never more than the drain for the
     * readers, and never joins them one after another for the full budget.
     */
    static Outcome await(Process p, List<Thread> readers, long deadlineMs, long drainMs = EXIT_DRAIN_MS) {
        long waitMs = Math.max(0L, deadlineMs - System.currentTimeMillis())
        boolean finished = p.waitFor(waitMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            killTree(p)
            joinAll(readers, System.currentTimeMillis() + 1_000L)
            return new Outcome(finished: false, heldOpen: false)
        }
        joinAll(readers, System.currentTimeMillis() + drainMs)
        new Outcome(finished: true, heldOpen: readers.any { Thread t -> t.isAlive() })
    }

    /** Joins every thread against ONE shared cut-off, not a fresh allowance each. */
    private static void joinAll(List<Thread> threads, long untilMs) {
        threads.each { Thread t ->
            long left = untilMs - System.currentTimeMillis()
            if (left > 0L) {
                try { t.join(left) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
            }
        }
    }

    /** Kills the process and everything under it, descendants first so none is re-parented and missed. */
    static void killTree(Process p) {
        if (p == null) { return }
        try {
            p.toHandle().descendants().forEach { ProcessHandle h ->
                try { h.destroyForcibly() } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        try { p.destroyForcibly() } catch (Exception ignored) { }
    }
}
