package com.softwood.mcp.service

import com.softwood.mcp.event.McpRequestEvent
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


/**
 * UsageTracker  per-action call count and response size tracking with SQLite persistence.
 *
 * Hooks into McpRequestEvent lifecycle. Stats surfaced via 'tools stats' action.
 *
 * Persistence strategy:
 * - In-memory counters accumulate during the day (fast, no DB writes per call)
 * - Flushed to shared SQLite DB (context server's best_practices.db) on:
 *     (a) every 10 minutes via @Scheduled periodic flush (mid-session visibility)
 *     (b) daily date rollover
 *     (c) server shutdown (@PreDestroy) - crash resilience
 * - Startup loads today's existing rows so counts survive restarts
 * - Uses context_layer = 'filesystem' to partition from context server rows
 *
 * Stats periods: today | week | month  (matches context server token-report)
 *
 * v0.7.4 / v0.7.17 periodic flush
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

    @Value('${mcp.usage.flush-on-shutdown:true}')
    boolean flushOnShutdown

    @Value('${mcp.usage.periodic-flush-interval-ms:600000}')
    long periodicFlushIntervalMs

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
    // In-memory state  (today's live counts)
    // -----------------------------------------------------------------------

    private volatile LocalDate currentDate = LocalDate.now()
    private volatile LocalDateTime sessionStart = LocalDateTime.now()

    /** "tool:action" -> call count (today, live) */
    private final ConcurrentHashMap<String, AtomicLong> callCounts = new ConcurrentHashMap<>()

    /** "tool:action" -> total response bytes (today, live) */
    private final ConcurrentHashMap<String, AtomicLong> responseBytes = new ConcurrentHashMap<>()

    /** "tool:action" -> total request payload bytes (today, live) */
    private final ConcurrentHashMap<String, AtomicLong> inputBytes = new ConcurrentHashMap<>()

    private final AtomicLong    totalCalls      = new AtomicLong(0)
    private final AtomicLong    totalBytes      = new AtomicLong(0)
    private final AtomicLong    totalInputBytes = new AtomicLong(0)
    private final AtomicLong    boundedCalls    = new AtomicLong(0)
    private final AtomicLong    fullReadCalls   = new AtomicLong(0)

    /** Dirty flag: true when in-memory counters have unsaved changes. */
    private volatile boolean dirty = false

    // FIX-9: no shared connection - per-operation connections with WAL mode for concurrent access

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
            // FIX-9: verify connectivity and set up schema via short-lived connection
            ensureSchema()
            loadTodayFromDb()
            log.info('UsageTracker: SQLite persistence active at {} (WAL mode)', dbPath)
        } catch (Exception e) {
            log.warn('UsageTracker: DB init failed, running in-memory only: {}', e.message)
        }
    }

    @PreDestroy
    void shutdown() {
        if (flushOnShutdown && dbPath) {
            try {
                flushToDb(currentDate)
                log.info('UsageTracker: flushed to DB on shutdown')
            } catch (Exception e) {
                log.warn('UsageTracker: flush on shutdown failed: {}', e.message)
            }
        }
        // FIX-9: no persistent connection to close
    }

    /**
     * Periodic flush - every 10 minutes by default (configurable via
     * mcp.usage.periodic-flush-interval-ms). Ensures mid-session data is
     * visible in the shared SQLite DB without waiting for shutdown.
     * No-op if DB not configured or no calls recorded yet.
     */
    @Scheduled(fixedRateString = '${mcp.usage.periodic-flush-interval-ms:600000}')
    void periodicFlush() {
        if (!dbPath || callCounts.isEmpty()) return
        try {
            flushToDb(currentDate)
            log.debug('UsageTracker: periodic flush complete ({} keys)', callCounts.size())
        } catch (Exception e) {
            log.warn('UsageTracker: periodic flush failed: {}', e.message)
        }
    }

    // -----------------------------------------------------------------------
    // Event listener
    // -----------------------------------------------------------------------

    @EventListener
    void onMcpEvent(McpRequestEvent event) {
        if (event.stage != McpRequestEvent.Stage.SENT) return
        if (event.method != 'tools/call') return

        checkDateRollover()

        String tool    = event.toolName ?: 'unknown'
        String action  = extractAction(event.toolArgs)
        String key     = action ? "${tool}:${action}" as String : tool
        long size      = event.responseSizeBytes
        long inSize    = event.payloadSizeBytes

        totalCalls.incrementAndGet()
        totalBytes.addAndGet(size)
        totalInputBytes.addAndGet(inSize)
        callCounts.computeIfAbsent(key, { k -> new AtomicLong(0) }).incrementAndGet()
        responseBytes.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(size)
        inputBytes.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(inSize)
        dirty = true

        if (action && action in BOUNDED_ACTIONS)       boundedCalls.incrementAndGet()
        else if (action && action in FULL_READ_ACTIONS) fullReadCalls.incrementAndGet()
    }

    private static String extractAction(Map<String, Object> toolArgs) {
        toolArgs?.action as String
    }

    // -----------------------------------------------------------------------
    // Stats API  (consumed by ToolsService.doStats)
    // -----------------------------------------------------------------------

    /**
     * Returns usage stats for the requested period.
     * period: 'today' | 'week' | 'month' | 'all'  (default: 'today')
     */
    Map<String, Object> getStats(String period = 'today') {
        checkDateRollover()

        if (!dbPath || period == 'today') {
            return buildTodayStats()
        }

        try {
            return buildPeriodStats(period)
        } catch (Exception e) {
            log.warn('UsageTracker: period stats query failed, falling back to today: {}', e.message)
            return buildTodayStats()
        }
    }

    private Map<String, Object> buildTodayStats() {
        // Only flush when counters have changed since last flush - avoids unnecessary DB writes on read-only stats calls
        if (dbPath && dirty) {
            try { flushToDb(currentDate); dirty = false } catch (Exception e) { log.warn('Stats flush failed: {}', e.message) }
        }
        long total     = totalCalls.get()
        long bounded   = boundedCalls.get()
        long fullReads = fullReadCalls.get()
        long readTotal = bounded + fullReads

        List<Map<String, Object>> breakdown = []
        callCounts.each { String key, AtomicLong count ->
            long bytes   = responseBytes[key]?.get() ?: 0L
            long inBytes = inputBytes[key]?.get() ?: 0L
            breakdown << ([key: key, calls: count.get(), responseKB: Math.round(bytes / 1024.0d), estTokens: Math.round(bytes / 4.0d), inputKB: Math.round(inBytes / 1024.0d)] as Map<String, Object>)
        }
        breakdown.sort { Map a, Map b -> (b.calls as long) <=> (a.calls as long) }

        float ratio = queryFileToContextRatio()
        Float displayRatio = ratio < 0f ? null : (Math.round(ratio * 100) / 100.0f) as Float
        String ratioHealth = ratio < 0f ? 'UNKNOWN' : (ratio < 3.0f ? 'OK' : (ratio < 6.0f ? 'DEGRADED' : 'POOR'))

        return [
            period              : 'today',
            date                : currentDate.toString(),
            sessionStart        : sessionStart.format(DateTimeFormatter.ofPattern('yyyy-MM-dd-HH-mm')),
            totalCalls          : total,
            totalBytes          : totalBytes.get(),
            totalKB             : Math.round(totalBytes.get() / 1024.0d),
            estimatedTokens     : Math.round(totalBytes.get() / 4.0d),
            totalInputBytes     : totalInputBytes.get(),
            boundedReads        : bounded,
            fullReads           : fullReads,
            boundedRatio        : readTotal > 0 ? Math.round(bounded * 100.0d / readTotal) : 0,
            fileToContextRatio  : displayRatio,
            ratioHealth         : ratioHealth,
            persistent          : dbPath ? true : false,
            perAction           : breakdown.take(20)
        ] as Map<String, Object>
    }

    private Map<String, Object> buildPeriodStats(String period) {
        LocalDate from = periodStart(period)
        String fromStr = from.toString()
        String todayStr = currentDate.toString()

        // Query DB for historical days (excludes today - we'll merge live counts)
        Map<String, Long> dbCalls      = [:] as Map<String, Long>
        Map<String, Long> dbBytes      = [:] as Map<String, Long>
        Map<String, Long> dbInputBytes = [:] as Map<String, Long>
        long dbTotalCalls      = 0
        long dbTotalBytes      = 0
        long dbTotalInputBytes = 0
        long dbBounded = 0
        long dbFull = 0

        withConnection { Connection conn ->
            PreparedStatement ps = conn.prepareStatement(
                "SELECT tool_name, SUM(call_count) as calls, SUM(response_bytes) as bytes, SUM(input_bytes) as ibytes " +
                "FROM token_usage " +
                "WHERE context_layer = ? AND recorded_date >= ? AND recorded_date < ? " +
                "GROUP BY tool_name")
            ps.setString(1, LAYER)
            ps.setString(2, fromStr)
            ps.setString(3, todayStr)
            ResultSet rs = ps.executeQuery()
            while (rs.next()) {
                String key  = rs.getString('tool_name')
                long calls  = rs.getLong('calls')
                long bytes  = rs.getLong('bytes')
                long iBytes = rs.getLong('ibytes')
                dbCalls[key]      = calls
                dbBytes[key]      = bytes
                dbInputBytes[key] = iBytes
                dbTotalCalls      += calls
                dbTotalBytes      += bytes
                dbTotalInputBytes += iBytes
                // classify
                String action = key.contains(':') ? key.split(':')[1] : ''
                if (action in BOUNDED_ACTIONS)       dbBounded += calls
                else if (action in FULL_READ_ACTIONS) dbFull += calls
            }
            rs.close(); ps.close()
        }

        // Merge today's live in-memory counts
        callCounts.each { String key, AtomicLong count ->
            dbCalls[key]      = (dbCalls[key] ?: 0L) + count.get()
            dbBytes[key]      = (dbBytes[key] ?: 0L) + (responseBytes[key]?.get() ?: 0L)
            dbInputBytes[key] = (dbInputBytes[key] ?: 0L) + (inputBytes[key]?.get() ?: 0L)
        }
        long mergedTotal      = dbTotalCalls + totalCalls.get()
        long mergedBytes      = dbTotalBytes + totalBytes.get()
        long mergedInputBytes = dbTotalInputBytes + totalInputBytes.get()
        long mergedBounded    = dbBounded + boundedCalls.get()
        long mergedFull       = dbFull + fullReadCalls.get()
        long readTotal        = mergedBounded + mergedFull

        List<Map<String, Object>> breakdown = []
        dbCalls.each { String key, Long calls ->
            long bytes   = dbBytes[key] ?: 0L
            long inBytes = dbInputBytes[key] ?: 0L
            breakdown << ([key: key, calls: calls, responseKB: Math.round(bytes / 1024.0d), estTokens: Math.round(bytes / 4.0d), inputKB: Math.round(inBytes / 1024.0d)] as Map<String, Object>)
        }
        breakdown.sort { Map a, Map b -> (b.calls as long) <=> (a.calls as long) }

        return [
            period          : period,
            from            : fromStr,
            to              : todayStr,
            totalCalls      : mergedTotal,
            totalBytes      : mergedBytes,
            totalKB         : Math.round(mergedBytes / 1024.0d),
            estimatedTokens : Math.round(mergedBytes / 4.0d),
            totalInputBytes : mergedInputBytes,
            boundedReads    : mergedBounded,
            fullReads       : mergedFull,
            boundedRatio    : readTotal > 0 ? Math.round(mergedBounded * 100.0d / readTotal) : 0,
            persistent      : true,
            perAction       : breakdown.take(20)
        ] as Map<String, Object>
    }

    /**
     * Queries today's file_read / context_* call ratio from tool_call_telemetry.
     * Returns -1f if DB unavailable or no context calls recorded.
     * Target: ratio < 3.0 (file reads should not dominate context reads 3:1+)
     */
    private float queryFileToContextRatio() {
        if (!dbPath) return -1f
        float[] result = [-1f]
        try {
            withConnection { Connection conn ->
                PreparedStatement stmt = conn.prepareStatement('''
                    SELECT
                      CAST(SUM(CASE WHEN tool_name = \'file_read\' THEN 1 ELSE 0 END) AS FLOAT),
                      CAST(SUM(CASE WHEN tool_name LIKE \'context_%\' THEN 1 ELSE 0 END) AS FLOAT)
                    FROM tool_call_telemetry
                    WHERE called_at > datetime(\'now\', \'-1 day\')''')
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
    // SQLite flush / load
    // -----------------------------------------------------------------------

    /** Flush today's in-memory counts to DB as upsert rows per tool:action key */
    private void flushToDb(LocalDate date) {
        if (!dbPath || callCounts.isEmpty()) return
        String dateStr = date.toString()

        withConnection { Connection conn ->
            conn.autoCommit = false
            try {
                // INSERT OR REPLACE: preserves multiple sessions on the same day (different
                // session_ids never conflict) while correctly refreshing totals for periodic
                // flushes within the same session (same session_id -> UNIQUE index triggers replace).
                PreparedStatement ins = conn.prepareStatement(
                    "INSERT OR REPLACE INTO token_usage (recorded_date, session_id, tool_name, call_count, estimated_tokens, response_bytes, input_bytes, context_layer) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")

                callCounts.each { String key, AtomicLong count ->
                    long bytes    = responseBytes[key]?.get() ?: 0L
                    long inBytes  = inputBytes[key]?.get() ?: 0L
                    long estTokens = Math.round(bytes / 4.0d)
                    ins.setString(1, dateStr)
                    ins.setString(2, sessionStart.format(DateTimeFormatter.ofPattern('yyyy-MM-dd-HH-mm')))
                    ins.setString(3, key)
                    ins.setLong(4, count.get())
                    ins.setLong(5, estTokens)
                    ins.setLong(6, bytes)
                    ins.setLong(7, inBytes)
                    ins.setString(8, LAYER)
                    ins.addBatch()
                }
                ins.executeBatch()
                ins.close()
                conn.commit()
                log.debug('UsageTracker: flushed {} keys for {} to DB', callCounts.size(), dateStr)
            } catch (Exception e) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Load THIS session's existing DB rows into memory on startup (survive restarts).
     *  BUG-FIX v0.7.53: was using SUM across ALL sessions for the day, but INSERT OR REPLACE
     *  only replaces the current session's row. This caused exponential compounding on
     *  multi-restart days: each restart loaded the sum of all sessions into counters, then
     *  flushed that inflated total back under the current session_id. */
    private void loadTodayFromDb() {
        String todayStr = currentDate.toString()
        String sessionId = sessionStart.format(DateTimeFormatter.ofPattern('yyyy-MM-dd-HH-mm'))
        withConnection { Connection conn ->
            PreparedStatement ps = conn.prepareStatement(
                "SELECT tool_name, SUM(call_count) as calls, SUM(response_bytes) as bytes, SUM(input_bytes) as ibytes " +
                "FROM token_usage WHERE recorded_date = ? AND context_layer = ? AND session_id = ? GROUP BY tool_name")
            ps.setString(1, todayStr)
            ps.setString(2, LAYER)
            ps.setString(3, sessionId)
            ResultSet rs = ps.executeQuery()
            int loaded = 0
            while (rs.next()) {
                String key  = rs.getString('tool_name')
                long calls  = rs.getLong('calls')
                long bytes  = rs.getLong('bytes')
                long iBytes = rs.getLong('ibytes')
                callCounts.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(calls)
                responseBytes.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(bytes)
                inputBytes.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(iBytes)
                totalCalls.addAndGet(calls)
                totalBytes.addAndGet(bytes)
                totalInputBytes.addAndGet(iBytes)
                String action = key.contains(':') ? key.split(':')[1] : ''
                if (action in BOUNDED_ACTIONS)       boundedCalls.addAndGet(calls)
                else if (action in FULL_READ_ACTIONS) fullReadCalls.addAndGet(calls)
                loaded++
            }
            rs.close(); ps.close()
            if (loaded > 0) log.info('UsageTracker: loaded {} keys from DB for {}', loaded, todayStr)
        }
    }

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
            // UNIQUE constraint required for INSERT OR REPLACE: one row per
            // (date, tool, layer, session). Multiple sessions on the same day
            // accumulate independently; periodic flushes within a session update in-place.
            try {
                conn.createStatement().withCloseable { it.execute(
                    'CREATE UNIQUE INDEX IF NOT EXISTS idx_token_usage_unique ' +
                    'ON token_usage(recorded_date, tool_name, context_layer, session_id)') }
            } catch (Exception ignored) {
                // Index or equivalent constraint already exists (e.g. created by context server with different column order)
                log.debug('UsageTracker: idx_token_usage_unique already exists, skipping')
            }
            // Composite index for period stats query (WHERE recorded_date >= ? AND context_layer = ?)
            try {
                conn.createStatement().withCloseable { it.execute(
                    'CREATE INDEX IF NOT EXISTS idx_token_usage_date_layer ' +
                    'ON token_usage(recorded_date, context_layer)') }
            } catch (Exception ignored) {}
        }
    }

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
    // Rollover
    // -----------------------------------------------------------------------

    private void checkDateRollover() {
        LocalDate today = LocalDate.now()
        if (today != currentDate) {
            log.info('UsageTracker: day rollover {} -> {}', currentDate, today)
            // Flush previous day before clearing
            try { flushToDb(currentDate) } catch (Exception e) { log.warn('Flush on rollover failed: {}', e.message) }

            callCounts.clear()
            responseBytes.clear()
            inputBytes.clear()
            totalCalls.set(0)
            totalBytes.set(0)
            totalInputBytes.set(0)
            boundedCalls.set(0)
            fullReadCalls.set(0)
            dirty = false
            currentDate = today
            sessionStart = LocalDateTime.now()

            // Load any existing rows for the new day (e.g. if another instance ran earlier)
            try { loadTodayFromDb() } catch (Exception e) { log.warn('Load on rollover failed: {}', e.message) }
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
