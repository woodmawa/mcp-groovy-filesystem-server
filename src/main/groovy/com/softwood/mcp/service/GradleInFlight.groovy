package com.softwood.mcp.service

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.time.Instant

/**
 * FS 0.9.126 -- remedy for class edit-test-same-batch (CS decision 451).
 *
 * <p>PENDING_EDIT refuses a gradle run sent in the SAME batch as an edit. It cannot see the other order: an edit
 * made while a gradle run on that repo is still in flight (obs 12955, 13090, 13193 -- each got_through: the run
 * compiled the old source and its verdict was read against the new). This closes that half.</p>
 *
 * <p>Every gradle call FS makes leaves a marker in {@code <workingDir>/.gradle/fs-inflight/} for its duration.
 * A mutating file_write to a source or build-script file under that workingDir is refused {@code REPO_BUILDING}
 * while a marker's owner process is alive. Markers live on disk, not in memory, because the chat's FS (stdio) and
 * the FS that runs a flow's gradle (HTTP companion) are different JVMs. {@code .gradle/} is used, not
 * {@code build/}, so a {@code clean} inside the run does not silently delete the marker. A marker whose owner is
 * gone (crash, kill) is deleted on sight: a dead build never holds a repo. GradleInFlightSpec.</p>
 */
@Slf4j
final class GradleInFlight {

    static final String MARKER_DIR    = '.gradle/fs-inflight'
    static final String MARKER_PREFIX = 'gradle-'
    static final int    MAX_ANCESTORS = 24

    private GradleInFlight() {}

    /** Mark a gradle run on workingDir as in flight; returns the marker, or null when it could not be written. */
    static File begin(String workingDir, String tasks) {
        if (!workingDir) return null
        try {
            File dir = new File(workingDir, MARKER_DIR)
            dir.mkdirs()
            ProcessHandle me = ProcessHandle.current()
            File m = new File(dir, MARKER_PREFIX + me.pid() + '-' + UUID.randomUUID().toString().take(8) + '.json')
            m.text = JsonOutput.toJson([
                pid         : me.pid(),
                jvmStartedAt: startOf(me),
                tasks       : tasks,
                startedAt   : Instant.now().toString(),
                workingDir  : new File(workingDir).absolutePath
            ])
            return m
        } catch (Exception e) {
            log.debug('GradleInFlight.begin failed (non-fatal): {}', e.message)
            return null
        }
    }

    static void end(File marker) {
        try { marker?.delete() } catch (Exception ignored) { }
    }

    /**
     * The live gradle run whose workingDir contains path: [workingDir, tasks, startedAt, pid, marker], else null.
     * Walks up from path; stale markers met on the way are deleted.
     */
    static Map<String, Object> holding(String path) {
        if (!path) return null
        File d = new File(path).absoluteFile.parentFile
        int n = 0
        while (d != null && n++ < MAX_ANCESTORS) {
            File md = new File(d, MARKER_DIR)
            if (md.isDirectory()) {
                File[] ms = md.listFiles({ File x -> x.name.startsWith(MARKER_PREFIX) && x.name.endsWith('.json') } as FileFilter)
                for (File m : (ms ?: new File[0])) {
                    Map<String, Object> meta = parse(m)
                    if (meta == null || !alive(meta)) { end(m); continue }
                    meta.marker = m.absolutePath
                    return meta
                }
            }
            d = d.parentFile
        }
        return null
    }

    /** A write that a build in flight would misjudge: sources and build scripts, never build output. */
    static boolean guarded(String path) {
        if (!path) return false
        String p = path.replace('\\', '/').toLowerCase()
        if (p.contains('/build/') || p.contains('/.gradle/')) return false
        return p.contains('/src/') || p.endsWith('.gradle') || p.endsWith('.gradle.kts') || p.endsWith('/gradle.properties')
    }

    static boolean alive(Map<String, Object> meta) {
        Object pidObj = meta?.get('pid')
        if (!(pidObj instanceof Number)) return false
        Optional<ProcessHandle> ph = ProcessHandle.of(((Number) pidObj).longValue())
        if (!ph.isPresent() || !ph.get().isAlive()) return false
        String recorded = meta.get('jvmStartedAt') as String
        if (!recorded) return true
        String now = startOf(ph.get())
        return !now || now == recorded      // a reused pid is a different process
    }

    private static String startOf(ProcessHandle ph) {
        try { return ph.info().startInstant().map { Instant i -> i.toString() }.orElse('') }
        catch (Exception ignored) { return '' }
    }

    private static Map<String, Object> parse(File m) {
        try { return new JsonSlurper().parseText(m.text) as Map<String, Object> }
        catch (Exception ignored) { return null }
    }
}
