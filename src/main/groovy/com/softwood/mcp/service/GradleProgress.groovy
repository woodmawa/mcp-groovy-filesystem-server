package com.softwood.mcp.service

import groovy.transform.CompileStatic

/**
 * FS 0.9.95 -- live progress inside a long gradle test run (HANDOFF-2026-10-14-live-step-progress G1).
 *
 * <p>FS runs test-bearing gradle tasks with {@link #INIT_SCRIPT}, which prints one {@link #MARKER} line per finished
 * test. Each streamed output line is offered to {@link #onLine}; the counts are posted through {@code sink} as
 * record_node_progress arguments -- at once for the first test, then at most every {@link #MIN_INTERVAL_MS}, and once
 * more from {@link #finish}. An init script rather than counting XML results: gradle writes the JUnit XML at the end
 * of the task, so a results-directory count stays at zero for the whole run. No repo's build.gradle is touched.</p>
 *
 * <p>Called on the stdout reader thread; the sink must not block (AwJobEventClient queues and returns).</p>
 */
@CompileStatic
class GradleProgress {

    static final String MARKER = '##AWTEST|'
    static final long MIN_INTERVAL_MS = 5000L

    static final String INIT_SCRIPT = '''// FS 0.9.95: live test progress for flow_management action=progress -- one line per finished test.
gradle.beforeProject { p ->
    p.tasks.withType(Test).configureEach { t ->
        t.afterTest { d, r -> println "##AWTEST|${r.resultType}|${d.className}|${d.name}" }
    }
}
'''

    private static volatile File initFile

    /** The init script on disk, written once per FS process. */
    static synchronized File initScriptFile() {
        if (initFile == null || !initFile.exists()) {
            File f = File.createTempFile('fs-aw-test-progress-', '.gradle')
            f.deleteOnExit()
            f.setText(INIT_SCRIPT, 'UTF-8')
            initFile = f
        }
        return initFile
    }

    final String runId
    final String nodeId
    final String detail
    private final Closure sink
    private final Closure<Long> clock

    private int passed
    private int failed
    private int skipped
    private String current
    private long lastPost = -1L

    GradleProgress(String runId, String nodeId, String detail, Closure sink,
                   Closure<Long> clock = { System.currentTimeMillis() } as Closure<Long>) {
        this.runId = runId
        this.nodeId = nodeId
        this.detail = detail
        this.sink = sink
        this.clock = clock
    }

    void onLine(String line) {
        if (line == null || !line.startsWith(MARKER)) return
        String[] parts = line.substring(MARKER.length()).split('\\|', 3)
        if (parts.length < 2) return
        switch (parts[0]) {
            case 'SUCCESS': passed++; break
            case 'FAILURE': failed++; break
            case 'SKIPPED': skipped++; break
            default: return
        }
        String cls = parts[1]
        current = cls.contains('.') ? cls.substring(cls.lastIndexOf('.') + 1) : cls
        long now = clock.call()
        if (lastPost < 0L || now - lastPost >= MIN_INTERVAL_MS) post(now)
    }

    /** The final count, once the process has ended. Nothing when no test ran. */
    void finish() {
        if (passed + failed + skipped > 0) post(clock.call())
    }

    private void post(long now) {
        lastPost = now
        Map<String, Object> m = [runId  : runId, nodeId: nodeId, detail: detail,
                                 done   : passed + failed + skipped, failed: failed,
                                 current: current] as Map<String, Object>
        try {
            sink.call(m)
        } catch (Exception ignored) {
            // fail-open: progress is a view, never a reason to disturb the build it describes
        }
    }
}
