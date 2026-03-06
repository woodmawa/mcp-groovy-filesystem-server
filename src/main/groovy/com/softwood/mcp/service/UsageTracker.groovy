package com.softwood.mcp.service

import com.softwood.mcp.event.McpRequestEvent
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


/**
 * UsageTracker — per-event INSERT tracking with SQLite persistence.
 *
 * Hooks into McpRequestEvent lifecycle. Stats surfaced via 'tools stats' action.
 *
 * Persistence strategy (v0.7.55 — Option B rewrite):
 * - Each tool call is written as a single INSERT row immediately (per-event pattern).
 * - Matches the context server's SqliteTelemetryStore pattern exactly.
 * - No in-memory accumulation, no periodic flush, no loadTodayFromDb.
 * - Eliminates the entire class of read-accumulate-write-back compounding bugs
 *   (v0.7.52 scope mismatch, duplicate rows from missing UNIQUE index).
 * - Uses context_layer = 'filesystem' to partition from context server rows.
 *
 * Stats periods: today | week | month | all (matches context server token-report)
 */
@Service
@Slf4j
@CompileStatic
class UsageTracker {

    // -----------------------------------------------------------------------
    // Config
    // -----------------------------------------------------------------------

    @Value('${mcp.usage.db-path:}')
    String dbPath

    // -----------------------------------------------------------------------
    // Classification sets
    // -----------------------------------------------------------------------

    private static final Set<String> BOUNDED_ACTIONS = [
        'head', 'tail', 'range', 'grep', 'summary', 'exists',
        'info', 'structure', 'checksum', 'project_root', 'allowed_dirs',
        'normalize', 'children', 'sizes', 'content', 'name', 'project'
    ] as Set<String>

    private static final Set<String> FULL_READ_ACTIONS = [
        'read', 'multi'
    ] as Set<String>

    private static final String LAYER = 'filesystem'

    // -----------------------------------------------------------------------
    // Session identity (for partitioning rows)
    // -----------------------------------------------------------------------

    private volatile LocalDateTime sessionStart = LocalDateTime.now()

    private String getSessionId() {
        sessionStart.format(DateTimeFormatter.ofPattern('yyyy-MM-dd-HH-mm'))
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @PostConstruct
    void init() {
        if (!dbPath) {
            log.warn('UsageTracker: mcp.usage.db-path not configured - persistence disabled')
            return
        }
        try {
            Class.forName('org.sqlite.JDBC')
            ensureSchema()
            log.info('UsageTracker: SQLite persistence active at {} (WAL mode, per-event INSERT)', dbPath)
        } catch (Exception e) {
            log.warn('UsageTracker: DB init failed, stats unavailable: {}', e.message)
        }
    }

    // -----------------------------------------------------------------------
    // Event listener
    // -----------------------------------------------------------------------

    @EventListener
    void onMcpEvent(McpRequestEvent event) {
        if (event.stage != McpRequestEvent.Stage.SENT) return
        if (event.method != 'tools/call') return

        String tool    = event.toolName ?: 'unknown'
        String action  = extractAction(event.toolArgs)
        String key     = action ? "${tool}:${action}" as String : tool
        long size      = event.responseSizeBytes
        long inSize    = event.payloadSizeBytes

        insertEvent(key, size, inSize)
    }

    private static String extractAction(Map toolArgs) {
        toolArgs?.get('action') as String
    }

    // -----------------------------------------------------------------------
    // Per-event INSERT (matches context server pattern)
    // -----------------------------------------------------------------------

    private void insertEvent(String toolName, long responseBytes, long inputBytes) {
        if (!dbPath) return
        try {
            withConnection { Connection conn ->
                PreparedStatement ins = conn.prepareStatement(
                    'INSERT INTO token_usage (recorded_date, session_id, tool_name, call_count, estimated_tokens, response_bytes, input_bytes, context_layer) ' +
                    'VALUES (?, ?, ?, 1, ?, ?, ?, ?)')
                ins.setString(1, LocalDate.now().toString())
                ins.setString(2, sessionId)
                ins.setString(3, toolName)
                ins.setLong(4, Math.round(responseBytes / 4.0d))
                ins.setLong(5, responseBytes)
                ins.setLong(6, inputBytes)
                ins.setString(7, LAYER)
                ins.executeUpdate()
                ins.close()
            }
        } catch (Exception e) {
            log.debug('UsageTracker: insert failed (non-fatal): {}', e.message)
        }
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    Map<String, Object> getStats(String period = 'today') {
        if (!dbPath) {
            return [period: period, error: 'DB not configured', persistent: false] as Map<String, Object>
        }
        try {
            return buildStats(period)
        } catch (Exception e) {
            log.warn('UsageTracker: stats query failed: {}', e.message)
            return [period: period, error: e.message, persistent: true] as Map<String, Object>
        }
    }

    /**
     * Unified stats builder — queries DB for any period including 'today'.
     * No in-memory state, purely DB-driven.
     */
    private Map<String, Object> buildStats(String period) {
        LocalDate from = periodStart(period)
        String fromStr = from.toString()
        String toStr   = period == 'today'
            ? LocalDate.now().plusDays(1).toString()  // inclusive of today
            : LocalDate.now().plusDays(1).toString()  // inclusive through today for all periods

        Map<String, Long> dbCalls      = [:] as Map<String, Long>
        Map<String, Long> dbBytes      = [:] as Map<String, Long>
        Map<String, Long> dbInputBytes = [:] as Map<String, Long>
        long totalCalls      = 0
        long totalBytes      = 0
        long totalInputBytes = 0
        long bounded = 0
        long fullReads = 0

        withConnection { Connection conn ->
            PreparedStatement ps = conn.prepareStatement(
                'SELECT tool_name, SUM(call_count) as calls, SUM(response_bytes) as bytes, SUM(input_bytes) as ibytes ' +
                'FROM token_usage ' +
                'WHERE context_layer = ? AND recorded_date >= ? AND recorded_date < ? ' +
                'GROUP BY tool_name')
            ps.setString(1, LAYER)
            ps.setString(2, fromStr)
            ps.setString(3, toStr)
            ResultSet rs = ps.executeQuery()
            while (rs.next()) {
                String key  = rs.getString('tool_name')
                long calls  = rs.getLong('calls')
                long bytes  = rs.getLong('bytes')
                long iBytes = rs.getLong('ibytes')
                dbCalls[key]      = calls
                dbBytes[key]      = bytes
                dbInputBytes[key] = iBytes
                totalCalls      += calls
                totalBytes      += bytes
                totalInputBytes += iBytes
                String act = key.contains(':') ? key.split(':')[1] : ''
                if (act in BOUNDED_ACTIONS)       bounded += calls
                else if (act in FULL_READ_ACTIONS) fullReads += calls
            }
            rs.close(); ps.close()
        }

        long readTotal = bounded + fullReads

        List<Map<String, Object>> breakdown = []
        dbCalls.each { String key, Long calls ->
            long bytes   = dbBytes[key] ?: 0L
            long inBytes = dbInputBytes[key] ?: 0L
            breakdown << ([key: key, calls: calls, responseKB: Math.round(bytes / 1024.0d),
                           estTokens: Math.round(bytes / 4.0d), inputKB: Math.round(inBytes / 1024.0d)] as Map<String, Object>)
        }
        breakdown.sort { Map a, Map b -> (b.calls as long) <=> (a.calls as long) }

        float ratio = queryFileToContextRatio()
        Float displayRatio = ratio < 0f ? null : (Math.round(ratio * 100) / 100.0f) as Float
        String ratioHealth = ratio < 0f ? 'UNKNOWN' : (ratio < 3.0f ? 'OK' : (ratio < 6.0f ? 'DEGRADED' : 'POOR'))

        Map<String, Object> result = [
            period          : period,
            from            : fromStr,
            to              : LocalDate.now().toString(),
            sessionStart    : sessionId,
            totalCalls      : totalCalls,
            totalBytes      : totalBytes,
            totalKB         : Math.round(totalBytes / 1024.0d),
            estimatedTokens : Math.round(totalBytes / 4.0d),
            totalInputBytes : totalInputBytes,
            boundedReads    : bounded,
            fullReads       : fullReads,
            boundedRatio    : readTotal > 0 ? Math.round(bounded * 100.0d / readTotal) : 0,
            persistent      : true,
            perAction       : breakdown.take(20)
        ] as Map<String, Object>

        // Include ratio only for 'today' to match prior behaviour
        if (period == 'today') {
            result.fileToContextRatio = displayRatio
            result.ratioHealth = ratioHealth
        }

        return result
    }

    // -----------------------------------------------------------------------
    // File-to-context ratio (reads from context server's tool_call_telemetry)
    // -----------------------------------------------------------------------

    private float queryFileToContextRatio() {
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
                    result[0] = ctxReads > 0f ? (fileReads / ctxReads) as float : -1f
                }
                rs.close()
                stmt.close()
            }
        } catch (Exception e) {
            log.debug('UsageTracker: ratio query failed (non-fatal): {}', e.message)
        }
        return result[0]
    }

    // -----------------------------------------------------------------------
    // Schema
    // -----------------------------------------------------------------------

    private void ensureSchema() {
        withConnection { Connection conn ->
            // Table already exists in context server DB - CREATE IF NOT EXISTS is a no-op
            conn.createStatement().withCloseable { it.execute("""
                CREATE TABLE IF NOT EXISTS token_usage (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    recorded_date    TEXT NOT NULL,
                    session_id       TEXT,
                    tool_name        TEXT NOT NULL,
                    call_count       INTEGER DEFAULT 1,
                    estimated_tokens INTEGER DEFAULT 0,
                    response_bytes   INTEGER DEFAULT 0,
                    input_bytes      INTEGER DEFAULT 0,
                    context_layer    TEXT DEFAULT 'other'
                )""") }
            // Migrate: add input_bytes to any pre-existing table that lacks the column
            try {
                conn.createStatement().withCloseable { it.execute(
                    'ALTER TABLE token_usage ADD COLUMN input_bytes INTEGER DEFAULT 0') }
            } catch (Exception ignored) {}
            // Composite index for period stats query (WHERE recorded_date >= ? AND context_layer = ?)
            try {
                conn.createStatement().withCloseable { it.execute(
                    'CREATE INDEX IF NOT EXISTS idx_token_usage_date_layer ' +
                    'ON token_usage(recorded_date, context_layer)') }
            } catch (Exception ignored) {}
            // NOTE: No UNIQUE index needed. Per-event INSERT means each row is unique by ROWID.
            // The old idx_token_usage_unique is harmless if it exists but no longer required.
        }
    }

    // -----------------------------------------------------------------------
    // DB connection helper
    // -----------------------------------------------------------------------

    // FIX-9: per-operation connection with WAL mode - eliminates shared-state serialisation bottleneck
    // FIX-1: added busy_timeout=10000 so filesystem server waits rather than failing immediately on SQLITE_BUSY
    private void withConnection(Closure action) {
        if (!dbPath) throw new IllegalStateException('UsageTracker: DB path not configured')
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            conn.createStatement().withCloseable { it.execute('PRAGMA journal_mode=WAL') }
            conn.createStatement().withCloseable { it.execute('PRAGMA busy_timeout=10000') }
            action(conn)
        } finally {
            try { conn?.close() } catch (Exception ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static LocalDate periodStart(String period) {
        LocalDate today = LocalDate.now()
        switch (period) {
            case 'week' : return today.minusDays(today.dayOfWeek.value - 1)  // Monday
            case 'month': return today.withDayOfMonth(1)
            case 'all'  : return LocalDate.of(2024, 1, 1)
            default     : return today
        }
    }
}
