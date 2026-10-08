package com.softwood.mcp.support

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.util.concurrent.atomic.AtomicLong

/**
 * One line a minute recording that this stdio server is alive and how long it has been since the
 * client last sent it anything.
 *
 * <h3>Why this exists</h3>
 *
 * <p>On 2026-09-08 the FS tool list vanished from a live session several times and came back on its
 * own. Six hypotheses were wrong before a {@code jstack} of the live process settled it: {@code main}
 * was RUNNABLE and parked in {@code System.in.read()}, i.e. the server was healthy, idle and simply
 * receiving nothing. The fault was upstream, in the client or the bridge.</p>
 *
 * <p><b>A server that is receiving nothing logs nothing</b>, so no amount of log retention would
 * have shown that gap. That is the hole this fills. With a heartbeat, the same question is answered
 * by reading one file: idle climbs minute by minute while the client is away and resets the moment
 * it comes back, and both edges carry a timestamp.</p>
 *
 * <h3>Deliberate properties</h3>
 * <ul>
 *   <li><b>Never writes to stderr.</b> It goes through the FILE appender only. Writing a heartbeat
 *       to the pipe whose blocking behaviour we are diagnosing would be its own kind of joke, and
 *       from FS 0.9.19 stdio attaches no console appender at all.</li>
 *   <li><b>Daemon thread.</b> It must never be the reason the JVM stays alive after the client
 *       closes stdin.</li>
 *   <li><b>Bounded volume.</b> One line per minute is roughly 50-100 KB a day, against a 20 MB
 *       file cap.</li>
 *   <li><b>Silent when nothing has ever arrived.</b> Before the first request there is no idle
 *       time to report, only uptime, and the line says so rather than printing a misleading age.</li>
 *   <li><b>WARN past the threshold</b>, so a long gap is greppable rather than buried among
 *       identical INFO lines.</li>
 * </ul>
 */
@Slf4j
@CompileStatic
class McpHeartbeat {

    /** A gap longer than this is worth finding later, so it is logged at WARN. */
    private static final long IDLE_WARN_MS = 120_000L

    private static final long INTERVAL_MS = 60_000L

    private static final AtomicLong REQUESTS = new AtomicLong(0L)
    private static final AtomicLong LAST_REQUEST_AT = new AtomicLong(0L)
    private static final AtomicLong STARTED_AT = new AtomicLong(0L)
    private static volatile Thread thread = null

    /** Called from the stdio read loop for every request actually received. */
    static void recordRequest() {
        REQUESTS.incrementAndGet()
        LAST_REQUEST_AT.set(System.currentTimeMillis())
    }

    /**
     * Starts the heartbeat. Idempotent: a second call is ignored rather than starting a second
     * thread, because several instances of this server share a log file and duplicated heartbeats
     * would make the file harder to read, not easier.
     */
    static synchronized void start(String serverName, String version) {
        if (thread != null) return
        STARTED_AT.set(System.currentTimeMillis())
        long pid = ProcessHandle.current().pid()

        Runnable body = new Runnable() {
            @Override
            void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(INTERVAL_MS)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    try { emit(serverName, version, pid) }
                    catch (Exception e) { log.debug('heartbeat emit failed (non-fatal): {}', e.message) }
                }
            }
        }

        Thread t = new Thread(body, 'mcp-heartbeat')
        t.setDaemon(true)
        t.start()
        thread = t
        log.info('HEARTBEAT started server={} v={} pid={} interval={}s', serverName, version, pid, (INTERVAL_MS / 1000L))
    }

    /** FS 0.9.73: what the stdio thread is handling now, and since when (0 = nothing in flight). */
    private static volatile String inFlight = null
    private static final AtomicLong IN_FLIGHT_SINCE = new AtomicLong(0L)

    /** FS 0.9.73: a request is being handled. While it is, the stdio thread reads nothing else. */
    static void begin(String label) {
        IN_FLIGHT_SINCE.set(System.currentTimeMillis())
        inFlight = label
    }

    /** FS 0.9.73: the request in flight has finished. */
    static void end() {
        inFlight = null
        IN_FLIGHT_SINCE.set(0L)
    }

    /**
     * FS 0.9.73: the heartbeat line for {@code now}. A request in flight is named -- "client has sent nothing"
     * was what this said for 494 s on 2026-10-08 while two requests sat unread behind a wedged execute.
     */
    static String line(String serverName, String version, long pid, long now) {
        long uptimeS = (now - STARTED_AT.get()).intdiv(1000L)
        String busy = inFlight
        if (busy != null) {
            long busyS = (now - IN_FLIGHT_SINCE.get()).intdiv(1000L)
            return ("HEARTBEAT server=${serverName} v=${version} pid=${pid} uptime=${uptimeS}s requests=${REQUESTS.get()} " +
                    "busy: ${busy} for ${busyS}s -- requests behind it are not read until it returns") as String
        }
        long last = LAST_REQUEST_AT.get()
        if (last == 0L) {
            return "HEARTBEAT server=${serverName} v=${version} pid=${pid} uptime=${uptimeS}s requests=0 idle=never-any-request" as String
        }
        long idleS = (now - last).intdiv(1000L)
        String base = "HEARTBEAT server=${serverName} v=${version} pid=${pid} uptime=${uptimeS}s requests=${REQUESTS.get()} idle=${idleS}s" as String
        ((now - last) >= IDLE_WARN_MS) ? base + " -- client has sent nothing for over ${(IDLE_WARN_MS / 1000L)}s" : base
    }

    private static void emit(String serverName, String version, long pid) {
        long now = System.currentTimeMillis()
        String text = line(serverName, version, pid, now)
        boolean warn = text.contains('busy: ') ? (now - IN_FLIGHT_SINCE.get()) >= IDLE_WARN_MS : text.contains('client has sent nothing')
        if (warn) { log.warn(text) } else { log.info(text) }
    }
}
