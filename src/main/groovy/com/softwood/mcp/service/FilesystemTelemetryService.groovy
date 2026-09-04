package com.softwood.mcp.service

import com.softwood.mcp.ProcessIdentity
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

    /**
     * FS 0.9.17 WP-5: the session THIS process was claimed for, and whether it was claimed.
     *
     * <p>A claim arrives on this process's own MCP pipe, so it is proof of which chat this JVM
     * serves -- the discriminator the protocol never supplies. Authoritative, and no TTL: the
     * three-second revalidation CS used to run is exactly how five FS JVMs all ended up answering
     * for whichever chat bootstrapped last.
     */
    private final java.util.concurrent.atomic.AtomicReference<String> claimedSessionRef =
            new java.util.concurrent.atomic.AtomicReference<String>()
    private final java.util.concurrent.atomic.AtomicBoolean sessionClaimed =
            new java.util.concurrent.atomic.AtomicBoolean(false)
    private final java.util.concurrent.atomic.AtomicReference<String> claimedGroupRef =
            new java.util.concurrent.atomic.AtomicReference<String>()

    // FIX-B: v0.7.43 session token accumulator
    // Tracks cumulative read tokens synchronously (not async) so callers can
    // include _session_read_tokens in their response before returning it.
    private final java.util.concurrent.atomic.AtomicInteger sessionReadTokens = new java.util.concurrent.atomic.AtomicInteger(0)
    private final java.util.concurrent.atomic.AtomicInteger sessionReadCalls  = new java.util.concurrent.atomic.AtomicInteger(0)

    /** FS 0.9.9: counts file_read calls that omitted options.knownHash despite file being in StructureCache. */
    private final java.util.concurrent.atomic.AtomicInteger missingKhCount = new java.util.concurrent.atomic.AtomicInteger(0)

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
        missingKhCount.set(0)
        lastCallEpochMs.set(0L)
    }

    /** Current cumulative read tokens this session. */
    int getSessionReadTokens() { sessionReadTokens.get() }
    int getSessionReadCalls()  { sessionReadCalls.get() }

    /**
     * FS 0.9.9: increments the missing-knownHash counter for this session.
     * Called by {@link ReadResponseHelper#maybeWarnMissingKnownHash} when a
     * {@code file_read action=read} or {@code action=get_method} is issued without
     * {@code options.knownHash} despite the file being in the session StructureCache.
     */
    void incrementMissingKhCount() { missingKhCount.incrementAndGet() }

    /** Returns the number of missing-knownHash violations recorded this session. */
    int getMissingKhCount() { missingKhCount.get() }

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

        // v0.8.39: resolve real session ID lazily on first call -- one JDBC read per session, cached thereafter
        // McpController passes 'unknown' because it cannot intercept context_lifecycle (a context-server tool).
        // readActiveSessionId() opens a short-lived read-only connection -- safe from asyncWriter thread.
        String resolvedId = sessionId
        if (!resolvedId || resolvedId == 'unknown') {
            if (trackedSessionId && trackedSessionId != 'unknown') {
                resolvedId = trackedSessionId  // already resolved this session
            } else {
                String fromDb = readActiveSessionId()
                if (fromDb) resolvedId = fromDb
            }
        }

        // Reset repeat cache and token accumulator when session changes
        if (resolvedId && resolvedId != trackedSessionId) {
            sessionCallCache.clear()
            resetSessionAccumulator()
            trackedSessionId = resolvedId
            log.debug('FilesystemTelemetry: new session {} - accumulator reset', resolvedId)
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
                    stmt.setString(1, resolvedId ?: 'unknown')
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
            // Ensure table exists even when context server has never run (standalone mode)
            def stmt = dbConn.createStatement()
            stmt.execute('''CREATE TABLE IF NOT EXISTS tool_call_telemetry (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id          TEXT NOT NULL,
                tool_name           TEXT NOT NULL,
                server_name         TEXT NOT NULL DEFAULT 'context-server',
                called_at           TEXT DEFAULT (datetime('now')),
                response_char_count INTEGER DEFAULT 0,
                response_token_est  INTEGER DEFAULT 0,
                is_repeat_call      INTEGER DEFAULT 0,
                args_hash           TEXT
            )''')
            stmt.execute('CREATE INDEX IF NOT EXISTS idx_telemetry_session ON tool_call_telemetry(session_id)')
            stmt.execute('CREATE INDEX IF NOT EXISTS idx_telemetry_tool ON tool_call_telemetry(tool_name)')
            stmt.execute('CREATE INDEX IF NOT EXISTS idx_telemetry_server ON tool_call_telemetry(server_name)')
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
            ensurePendingReindexTable()
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

    /**
     * FS 0.9.17 WP-5 -- bind THIS process to a session, over this process's own connection.
     *
     * <p>The row goes into the same {@code session_claims} table CS writes, tagged
     * {@code server='fs'}, so CS's reaper decides liveness for FS rows too. One table, one
     * liveness rule -- FS does not get separate bookkeeping to drift out of step with.
     *
     * @param sessionId the session this chat is working in
     * @param groupId   the project group, or null
     * @since FS 0.9.17
     */
    Map<String, Object> claimSession(String sessionId, String groupId = null) {
        if (!sessionId) throw new IllegalArgumentException('claim_session requires a sessionId')

        String previous = claimedSessionRef.get()
        claimedSessionRef.set(sessionId)
        sessionClaimed.set(true)
        if (groupId) claimedGroupRef.set(groupId)

        boolean persisted = writeOwnClaimRow(sessionId, groupId)

        log.info('FilesystemTelemetryService: claimed session {} (was {}) owner_key={} group={}',
                 sessionId, previous ?: 'unset', ProcessIdentity.OWNER_KEY, groupId ?: 'none')

        return [owner_key       : ProcessIdentity.OWNER_KEY,
                server          : ProcessIdentity.SERVER,
                session_id      : sessionId,
                group_id        : groupId,
                pid             : ProcessIdentity.PID,
                jvm_started_at  : ProcessIdentity.JVM_STARTED_AT,
                previous_session: previous,
                persisted       : persisted,
                status          : 'claimed'] as Map<String, Object>
    }

    /** Drop this process's claim, so it reads as UNBOUND rather than holding a dead session. */
    Map<String, Object> releaseClaim() {
        String was = claimedSessionRef.get()
        claimedSessionRef.set(null)
        claimedGroupRef.set(null)
        sessionClaimed.set(false)
        try {
            withOwnConnection { Connection conn ->
                PreparedStatement ps = conn.prepareStatement('DELETE FROM session_claims WHERE owner_key = ?')
                ps.setString(1, ProcessIdentity.OWNER_KEY)
                ps.executeUpdate()
                ps.close()
            }
        } catch (Exception e) {
            log.debug('releaseClaim: session_claims delete failed (non-fatal): {}', e.message)
        }
        return [owner_key: ProcessIdentity.OWNER_KEY, released_session_id: was,
                status: 'released'] as Map<String, Object>
    }

    /** What this process believes it is serving. Diagnostics read this. */
    Map<String, Object> claimStatus() {
        return [owner_key           : ProcessIdentity.OWNER_KEY,
                server              : ProcessIdentity.SERVER,
                pid                 : ProcessIdentity.PID,
                jvm_started_at      : ProcessIdentity.JVM_STARTED_AT,
                this_process_session: readActiveSessionId(),
                this_process_group  : claimedGroupRef.get(),
                claimed             : sessionClaimed.get() && claimedSessionRef.get() != null
               ] as Map<String, Object>
    }

    /** Upsert this process's own row. Returns false when it could not be written. */
    private boolean writeOwnClaimRow(String sessionId, String groupId) {
        if (!dbPath) return false
        try {
            withOwnConnection { Connection conn ->
                PreparedStatement ps = conn.prepareStatement('''
                    INSERT INTO session_claims
                        (owner_key, server, session_id, group_id, pid, jvm_started_at, claimed_at, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
                    ON CONFLICT(owner_key) DO UPDATE SET
                        session_id   = excluded.session_id,
                        group_id     = excluded.group_id,
                        claimed_at   = datetime('now'),
                        last_seen_at = datetime('now')''')
                ps.setString(1, ProcessIdentity.OWNER_KEY)
                ps.setString(2, ProcessIdentity.SERVER)
                ps.setString(3, sessionId)
                ps.setString(4, groupId)
                ps.setLong(5, ProcessIdentity.PID)
                ps.setString(6, ProcessIdentity.JVM_STARTED_AT)
                ps.executeUpdate()
                ps.close()
            }
            return true
        } catch (Exception e) {
            log.warn('claimSession: session_claims write-through failed, claim still held in ' +
                     'process: {}', e.message)
            return false
        }
    }

    /**
     * A short-lived connection of our own. Deliberately not the shared {@code dbConn}, which
     * belongs to the single telemetry writer thread -- claims are issued from the MCP request
     * thread and must not contend with it.
     */
    private void withOwnConnection(Closure work) {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            conn.autoCommit = true
            work(conn)
        } finally {
            conn.close()
        }
    }

    /**
     * Resolve the session this process is serving.
     *
     * <p><b>FS 0.9.17 WP-5 -- claim-scoped, in three steps:</b>
     * <ol>
     *   <li>the in-process claim -- authoritative, no TTL, no query;</li>
     *   <li>if unclaimed, THIS process's own row in {@code session_claims};</li>
     *   <li>otherwise UNBOUND.</li>
     * </ol>
     *
     * <p>It used to read {@code active_session ORDER BY id DESC LIMIT 1} -- one row for the whole
     * machine, shared by every FS JVM (five were running on 2026-09-03). Every tool call's
     * telemetry and every range-cache key was therefore attributed to whichever chat bootstrapped
     * most recently. With two chats open, one chat's reads were filed under the other's session,
     * and because the range cache is keyed by session id they also missed.
     *
     * <p>Step 3 does NOT fall back to {@code active_session}. Telemetry tolerates a null by
     * recording {@code 'unknown'}, which makes the fallback tempting -- and it is precisely the
     * defect: a wrong session id is worse than an absent one, because downstream it is
     * indistinguishable from correct data.
     *
     * @return the session id this process is bound to, or {@code null} when unbound
     */
    String readActiveSessionId() {
        // 1. The in-process claim.
        String inMemory = claimedSessionRef.get()
        if (sessionClaimed.get() && inMemory) return inMemory

        // 2. This process's OWN claim row -- never another process's.
        String own = readOwnClaimSessionId()
        if (own) {
            claimedSessionRef.set(own)
            sessionClaimed.set(true)
            log.info('FilesystemTelemetryService: adopted session {} from own session_claims row (owner_key={})',
                     own, ProcessIdentity.OWNER_KEY)
            return own
        }

        // 3. Unbound. Never the newest global session.
        return null
    }

    /** Reads only the row keyed by this process's own owner_key. Fail-open to null. */
    private String readOwnClaimSessionId() {
        if (!dbPath) return null
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
            try {
                PreparedStatement ps = conn.prepareStatement(
                    'SELECT session_id FROM session_claims WHERE owner_key = ?')
                ps.setString(1, ProcessIdentity.OWNER_KEY)
                ResultSet rs = ps.executeQuery()
                String sid = rs.next() ? rs.getString('session_id') : null
                rs.close(); ps.close()
                return sid ?: null
            } finally {
                conn.close()
            }
        } catch (Exception e) {
            log.debug('FilesystemTelemetryService.readOwnClaimSessionId failed (non-fatal): {}', e.message)
            return null
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

    /**
     * Creates pending_reindex table in shared SQLite if absent.
     * Called from init() so the table is always ready when FS starts.
     */
    void ensurePendingReindexTable() {
        if (!dbPath) return
        try {
            withConnection { conn ->
                conn.createStatement().execute('''
                    CREATE TABLE IF NOT EXISTS pending_reindex (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_path TEXT NOT NULL,
                        queued_at TEXT DEFAULT (datetime('now')),
                        cluster   TEXT
                    )''')
                conn.createStatement().execute(
                    'CREATE INDEX IF NOT EXISTS idx_pending_reindex_path ON pending_reindex(file_path)')
            }
            log.debug('FilesystemTelemetryService: pending_reindex table ready')
        } catch (Exception e) {
            log.debug('FilesystemTelemetryService: ensurePendingReindexTable failed (non-fatal): {}', e.message)
        }
    }

    /**
     * Queues a file path for ontology reindex via the shared SQLite pending_reindex table.
     * Called by ContextServerClient when the HTTP path to context server is unavailable (MCPB/stdio mode).
     * The context server drains this table on every context_lifecycle action=start.
     * Only queues .groovy and .java files — skips all others.
     */
    void queueReindexAsync(String filePath) {
        if (!dbPath || !filePath) return
        if (!filePath.endsWith('.groovy') && !filePath.endsWith('.java')) return
        asyncWriter.submit({
            try {
                withConnection { conn ->
                    def ps = conn.prepareStatement(
                        'INSERT OR IGNORE INTO pending_reindex(file_path) VALUES(?)')
                    ps.setString(1, filePath)
                    ps.executeUpdate()
                    ps.close()
                    log.debug('queueReindex: queued {}', filePath)
                }
            } catch (Exception e) {
                log.debug('queueReindex failed (non-fatal): {}', e.message)
            }
        } as Runnable)
    }
}
