package com.softwood.mcp.support

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Startup log hygiene for this server. It prunes OUR OWN logs and nothing else.
 *
 * <h3>What it used to do, and why it stopped</h3>
 *
 * <p>Until FS 0.9.21 this class truncated {@code mcp-server-groovy-filesystem.log} and
 * {@code mcp.log} under {@code %APPDATA%/Roaming/Claude/logs} on every start. Both are under
 * Claude Desktop's own directory and neither is ours; {@code mcp.log} in particular is shared by
 * every MCP server on the machine. That clearing was removed and the class did nothing at all for
 * three versions.</p>
 *
 * <h3>What it does now, and why it had to come back</h3>
 *
 * <p>FS 0.9.23 moved the log file to one file per pid -- {@code mcp-filesystem-<pid>.log} -- because
 * a single shared file made logback's size trigger inoperative. The byte counter belongs to the
 * appender instance, and with several JVMs appending to one file, and every restart resetting the
 * count, no instance ever lived long enough to reach the 20MB cap however large the file grew.
 * {@code mcp-context.log} reached 96.6MB while its retention policy was perfectly happy.</p>
 *
 * <p>Per-pid files fix the trigger and introduce a new problem: {@code maxHistory} only prunes
 * archives matching the pattern of the appender that wrote them, so a dead pid's file is managed by
 * nobody and stays forever. At four to five instances per server per restart, that accumulates.
 * This sweep is the other half of that decision -- without it, per-pid trades a file that grows
 * without bound for a directory that does.</p>
 *
 * <h3>Deliberate limits</h3>
 * <ul>
 *   <li><b>Only under {@code claude-sync/logs}.</b> The path is derived here, not passed in.</li>
 *   <li><b>Only names beginning {@link #BASE_NAME} and ending {@code .log}.</b> That covers the
 *       per-pid files, their dated archives, and the pre-0.9.23 single file, and nothing else in
 *       the directory -- not the companion stderr captures, not the other two servers' logs.</li>
 *   <li><b>Only files older than {@link #RETENTION_DAYS} days</b>, which is the same window
 *       {@code maxHistory} promises. A live instance's own file is minutes old and is never a
 *       candidate; the pid guard below is belt and braces on top of that.</li>
 *   <li><b>Never fatal.</b> A server that cannot tidy its logs must still serve the session.</li>
 * </ul>
 */
@Slf4j
@CompileStatic
class LogCleaner {

    /** Every file this server writes begins with this. */
    private static final String BASE_NAME = 'mcp-filesystem'

    /** Matches the window logback's maxHistory promises for the archives it does manage. */
    private static final int RETENTION_DAYS = 2

    /**
     * Delete this server's own log files older than the retention window.
     *
     * @return the number of files deleted
     */
    static int clearLogsOnStartup() {
        int deleted = 0
        try {
            String home = System.getProperty('user.home')
            if (!home) return 0
            File logsDir = new File(new File(home, 'claude-sync'), 'logs')
            if (!logsDir.isDirectory()) return 0

            long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24L * 60L * 60L * 1000L)
            long ownPid = ProcessHandle.current().pid()
            String ownMarker = "${BASE_NAME}-${ownPid}"

            File[] candidates = logsDir.listFiles()
            if (candidates == null) return 0

            for (File f : candidates) {
                if (!f.isFile()) continue
                String name = f.name
                if (!name.startsWith(BASE_NAME) || !name.endsWith('.log')) continue
                if (name.startsWith(ownMarker)) continue          // never our own live file
                if (f.lastModified() >= cutoff) continue          // inside the retention window
                try {
                    if (f.delete()) {
                        deleted++
                    } else {
                        // Almost always a live sibling holding the handle open on Windows. Not a
                        // problem: it is by definition being written to, so it is not stale.
                        log.debug('LogCleaner: could not delete {} (in use?)', name)
                    }
                } catch (Exception e) {
                    log.debug('LogCleaner: could not delete {}: {}', name, e.message)
                }
            }

            if (deleted > 0) {
                log.info('LogCleaner: removed {} log file(s) older than {} days from {}',
                         deleted, RETENTION_DAYS, logsDir.absolutePath)
            } else {
                log.debug('LogCleaner: nothing older than {} days to remove', RETENTION_DAYS)
            }
        } catch (Exception e) {
            log.warn('LogCleaner: sweep failed (non-fatal): {}', e.message)
        }
        return deleted
    }
}
