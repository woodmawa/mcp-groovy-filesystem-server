package com.softwood.mcp.service

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicLong

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FilesystemTelemetryService — records filesystem tool call sizes into the
 * shared SQLite DB (same best_practices.db used by the context server).
 *
 * The tool_call_telemetry table is created by the context server on startup.
 * This service assumes it exists; if it doesn't (context server not yet run)
 * all writes silently no-op so the filesystem server is never blocked.
 *
 * Writes are fire-and-forget via a single daemon thread — hot path is never
 * blocked by telemetry I/O.
 *
 * server_name = 'filesystem-server' on all rows so the dashboard can
 * distinguish filesystem vs context-server tool sizes.
 *
 * v0.7.19
 */
@Service
@Slf4j
class FilesystemTelemetryService {

    @Value('${mcp.usage.db-path:}')
    String dbPath

    @Value('${mcp.filesystem.session-gap-minutes:30}')
    int sessionGapMinutes

    /** Single daemon writer — serialises all SQLite writes */
    private final ExecutorService asyncWriter = Executors.newSingleThreadExecutor { Runnable r ->
        Thread t = new Thread(r, 'fs-telemetry-writer')
        t.daemon = true
        t
    }

    /** Persistent JDBC connection — shared by the single async writer thread (no pool needed). */
    private volatile Connection dbConn = null

    /** Repeat-call detection: "toolName:argsHash" -> first-call timestamp, per session */
    private final Map<String, String> sessionCallCache = new ConcurrentHashMap<>()
    private volatile String trackedSessionId = null

    // FIX-B: v0.7.43 session token accumulator
    // Tracks cumulative read tokens synchronously (not async) so callers can
    // include _session_read_tokens in their response before returning it.
    private final java.util.concurrent.atomic.AtomicInteger sessionReadTokens = new java.util.concurrent.atomic.AtomicInteger(0)
    private final java.util.concurrent.atomic.AtomicInteger sessionReadCalls  = new java.util.concurrent.atomic.AtomicInteger(0)

    // v0.7.44: time-based session gap detection - reset accumulator after 30 min inactivity
    private final AtomicLong lastCallEpochMs = new AtomicLong(0L)

    private long getSessionGapMs() { sessionGapMinutes * 60_000L }

    /**
     * Record a read-family tool call synchronously and return the cumulative
     * session token count AFTER this call. Used by FileReadService to inject
     * _session_read_tokens into every read response.
     * Thread-safe; O(1).
     */
    int accumulateReadTokens(int responseChars) {
        long now = System.currentTimeMillis()
        long last = lastCallEpochMs.get()
        if (last > 0L && (now - last) > getSessionGapMs()) {
            resetSessionAccumulator()
            log.debug('FilesystemTelemetry: {}min gap - session accumulator reset', sessionGapMinutes)
        }
        lastCallEpochMs.set(now)
        int tokens = Math.round(responseChars / 4.0f) as int
        sessionReadCalls.incrementAndGet()
        return sessionReadTokens.addAndGet(tokens)
    }

    /** Reset the session accumulator (called when a new session starts or gap detected). */
    void resetSessionAccumulator() {
        sessionReadTokens.set(0)
        sessionReadCalls.set(0)
        lastCallEpochMs.set(0L)
    }

    /** Current cumulative read tokens this session. */
    int getSessionReadTokens() { sessionReadTokens.get() }
    int getSessionReadCalls()  { sessionReadCalls.get() }

    /**
     * Returns a health summary for the current session.
     * Queries today's file_read vs context_* ratio from SQLite.
     * Non-blocking: returns UNKNOWN if DB is unavailable.
     */
    Map<String, Object> getSessionHealthSummary() {
        int tokens = sessionReadTokens.get()
        int calls  = sessionReadCalls.get()
        float ratio = getFileToContextRatio()
        String healthStatus
        if (ratio < 0f) {
            healthStatus = 'UNKNOWN'
        } else if (ratio < 3.0f) {
            healthStatus = 'OK'
        } else if (ratio < 6.0f) {
            healthStatus = 'DEGRADED'
        } else {
            healthStatus = 'POOR'
        }
        Float displayRatio = ratio < 0f ? null : (Math.round(ratio * 100) / 100.0f) as Float
        return [fileReadTokens: tokens, fileReadCalls: calls,
                fileToContextRatio: displayRatio, healthStatus: healthStatus] as Map<String, Object>
    }

    /** Queries today's file_read / context_* call ratio from SQLite. Returns -1 if unavailable. */
    private float getFileToContextRatio() {
        if (!dbPath) return -1f
        float[] result = [-1f]
        try {
            withConnection { Connection conn ->
                PreparedStatement stmt = conn.prepareStatement('''
                    SELECT
                      CAST(SUM(CASE WHEN tool_name = 'file_read' THEN 1 ELSE 0 END) AS FLOAT),
                      CAST(SUM(CASE WHEN tool_name LIKE 'context_%' THEN 1 ELSE 0 END) AS FLOAT)
                    FROM tool_call_telemetry
                    WHERE called_at > datetime('now', '-1 day')''')
                ResultSet rs = stmt.executeQuery()
                if (rs.next()) {
                    float fileReads = rs.getFloat(1)
                    float ctxReads  = rs.getFloat(2)
                    result[0] = ctxReads > 0f ? fileReads / ctxReads : -1f
                }
                rs.close()
                stmt.close()
            }
        } catch (Exception e) {
            log.debug('FilesystemTelemetry: ratio query failed (non-fatal): {}', e.message)
        }
        return result[0]
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fire-and-forget record of a single filesystem tool call.
     *
     * @param sessionId      current session ID (pass 'unknown' if unavailable)
     * @param toolName       the MCP tool name
     * @param responseChars  character length of the serialised response
     * @param args           tool arguments map (hashed for repeat detection)
     */
    void recordToolCall(String sessionId, String toolName,
                         int responseChars, Map<String, Object> args,
                         String action = null, String pathHash = null,
                         String outcome = 'success') {
        if (!dbPath) return   // persistence not configured  silent no-op


        // Reset repeat cache and token accumulator when session changes
        if (sessionId && sessionId != trackedSessionId) {
            sessionCallCache.clear()
            resetSessionAccumulator()
            trackedSessionId = sessionId
            log.debug('FilesystemTelemetry: new session {} - accumulator reset', sessionId)
        }

        asyncWriter.submit {
            try {
                String argsHash  = buildArgsHash(args)
                String cacheKey  = "${toolName}:${argsHash}"
                boolean isRepeat = sessionCallCache.containsKey(cacheKey)
                // FIX-12: bound cache to 1000 entries to prevent unbounded growth in long sessions
                if (!isRepeat && sessionCallCache.size() < 1000) sessionCallCache.put(cacheKey, new Date().toInstant().toString())

                int tokenEst = Math.round(responseChars / 4) as int

                withConnection { Connection conn ->
                    PreparedStatement stmt = conn.prepareStatement('''
                        INSERT INTO tool_call_telemetry
                            (session_id, tool_name, server_name,
                             response_char_count, response_token_est,
                             is_repeat_call, args_hash,
                             action, path_hash, outcome)
                        VALUES (?,?,?,?,?,?,?,?,?,?)''')
                    stmt.setString(1, sessionId ?: 'unknown')
                    stmt.setString(2, toolName)
                    stmt.setString(3, 'filesystem-server')
                    stmt.setInt(4, responseChars)
                    stmt.setInt(5, tokenEst)
                    stmt.setInt(6, isRepeat ? 1 : 0)
                    stmt.setString(7, argsHash)
                    stmt.setString(8, action)
                    stmt.setString(9, pathHash)
                    stmt.setString(10, outcome ?: 'success')
                    stmt.executeUpdate()
                    stmt.close()

                }
            } catch (Exception e) {
                // Table may not exist yet if context server hasn't run — silent
                log.debug('Filesystem telemetry write failed (non-fatal): {}', e.message)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @PostConstruct
    void init() {
        if (!dbPath) return   // persistence not configured
        try {
            Class.forName('org.sqlite.JDBC')
            dbConn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
            dbConn.autoCommit = true
            log.debug('FilesystemTelemetryService: persistent JDBC connection opened at {}', dbPath)
            // Addendum C: safe migration - add new columns if table exists but columns are absent
            ['action', 'path_hash', 'outcome'].each { String col ->
                try {
                    dbConn.createStatement().execute("ALTER TABLE tool_call_telemetry ADD COLUMN ${col} TEXT")
                    log.info('telemetry: added column {}', col)
                } catch (Exception ignored) {
                    // column already exists - fine
                    log.debug('telemetry column {} already present', col)
                }
            }
        } catch (Exception e) {
            log.debug('FilesystemTelemetryService: DB connection failed (non-fatal): {}', e.message)
        }
    }

    @PreDestroy
    void shutdown() {
        asyncWriter.shutdown()
        try {
            if (!asyncWriter.awaitTermination(3, TimeUnit.SECONDS)) asyncWriter.shutdownNow()
        } catch (InterruptedException e) {
            asyncWriter.shutdownNow()
            Thread.currentThread().interrupt()
        }
        try { dbConn?.close() } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void withConnection(Closure work) {
        if (dbConn) {
            work(dbConn)
        } else {
            // Fallback: open a one-off connection if persistent connection failed at init
            Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
            try {
                conn.autoCommit = true
                work(conn)
            } finally {
                conn.close()
            }
        }
    }

    private static String buildArgsHash(Map<String, Object> args) {
        if (!args) return 'noargs'
        try {
            String str = args.sort().toString()
            return Integer.toHexString(str.hashCode()).padLeft(8, '0')[0..7]
        } catch (Exception e) {
            return 'hasherr'
        }
    }
}
