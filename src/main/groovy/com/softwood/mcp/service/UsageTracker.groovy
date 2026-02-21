package com.softwood.mcp.service

import com.softwood.mcp.event.McpRequestEvent
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
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
 *     (a) daily date rollover  (b) server shutdown (@PreDestroy)
 * - Startup loads today's existing rows so counts survive restarts
 * - Uses context_layer = 'filesystem' to partition from context server rows
 *
 * Stats periods: today | week | month  (matches context server token-report)
 *
 * v0.7.4
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
    private final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>()

    /** "tool:action" -> total response bytes (today, live) */
    private final ConcurrentHashMap<String, AtomicLong> responseBytes = new ConcurrentHashMap<>()

    private final AtomicInteger totalCalls    = new AtomicInteger(0)
    private final AtomicLong    totalBytes    = new AtomicLong(0)
    private final AtomicInteger boundedCalls  = new AtomicInteger(0)
    private final AtomicInteger fullReadCalls = new AtomicInteger(0)

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
            ensureSchema()
            loadTodayFromDb()
            log.info('UsageTracker: SQLite persistence active at {}', dbPath)
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
    }

    // -----------------------------------------------------------------------
    // Event listener
    // -----------------------------------------------------------------------

    @EventListener
    void onMcpEvent(McpRequestEvent event) {
        if (event.stage != McpRequestEvent.Stage.SENT) return
        if (event.method != 'tools/call') return

        checkDateRollover()

        String tool   = event.toolName ?: 'unknown'
        String action = extractAction(event.toolArgs)
        String key    = action ? "${tool}:${action}" as String : tool
        int size      = event.responseSizeBytes

        totalCalls.incrementAndGet()
        totalBytes.addAndGet(size)
        callCounts.computeIfAbsent(key, { k -> new AtomicInteger(0) }).incrementAndGet()
        responseBytes.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(size)

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
        // Persist current state on every stats read - cheap and crash-safe
        if (dbPath) { try { flushToDb(currentDate) } catch (Exception e) { log.warn('Stats flush failed: {}', e.message) } }
        int total     = totalCalls.get()
        int bounded   = boundedCalls.get()
        int fullReads = fullReadCalls.get()
        int readTotal = bounded + fullReads

        List<Map<String, Object>> breakdown = []
        callCounts.each { String key, AtomicInteger count ->
            long bytes = responseBytes[key]?.get() ?: 0L
            breakdown << ([key: key, calls: count.get(), responseKB: Math.round(bytes / 1024.0d)] as Map<String, Object>)
        }
        breakdown.sort { Map a, Map b -> (b.calls as int) <=> (a.calls as int) }

        return [
            period       : 'today',
            date         : currentDate.toString(),
            sessionStart : sessionStart.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            totalCalls   : total,
            totalBytes   : totalBytes.get(),
            totalKB      : Math.round(totalBytes.get() / 1024.0d),
            boundedReads : bounded,
            fullReads    : fullReads,
            boundedRatio : readTotal > 0 ? Math.round(bounded * 100.0d / readTotal) : 0,
            persistent   : dbPath ? true : false,
            perAction    : breakdown.take(20)
        ] as Map<String, Object>
    }

    private Map<String, Object> buildPeriodStats(String period) {
        LocalDate from = periodStart(period)
        String fromStr = from.toString()
        String todayStr = currentDate.toString()

        // Query DB for historical days (excludes today - we'll merge live counts)
        Map<String, Long> dbCalls = [:] as Map<String, Long>
        Map<String, Long> dbBytes = [:] as Map<String, Long>
        long dbTotalCalls = 0
        long dbTotalBytes = 0
        long dbBounded = 0
        long dbFull = 0

        withConnection { Connection conn ->
            PreparedStatement ps = conn.prepareStatement(
                "SELECT tool_name, SUM(call_count) as calls, SUM(response_bytes) as bytes " +
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
                dbCalls[key] = calls
                dbBytes[key] = bytes
                dbTotalCalls += calls
                dbTotalBytes += bytes
                // classify
                String action = key.contains(':') ? key.split(':')[1] : ''
                if (action in BOUNDED_ACTIONS)       dbBounded += calls
                else if (action in FULL_READ_ACTIONS) dbFull += calls
            }
            rs.close(); ps.close()
        }

        // Merge today's live in-memory counts
        callCounts.each { String key, AtomicInteger count ->
            dbCalls[key] = (dbCalls[key] ?: 0L) + count.get()
            dbBytes[key] = (dbBytes[key] ?: 0L) + (responseBytes[key]?.get() ?: 0L)
        }
        long mergedTotal = dbTotalCalls + totalCalls.get()
        long mergedBytes = dbTotalBytes + totalBytes.get()
        long mergedBounded = dbBounded + boundedCalls.get()
        long mergedFull = dbFull + fullReadCalls.get()
        long readTotal = mergedBounded + mergedFull

        List<Map<String, Object>> breakdown = []
        dbCalls.each { String key, Long calls ->
            long bytes = dbBytes[key] ?: 0L
            breakdown << ([key: key, calls: calls, responseKB: Math.round(bytes / 1024.0d)] as Map<String, Object>)
        }
        breakdown.sort { Map a, Map b -> (b.calls as long) <=> (a.calls as long) }

        return [
            period      : period,
            from        : fromStr,
            to          : todayStr,
            totalCalls  : mergedTotal,
            totalBytes  : mergedBytes,
            totalKB     : Math.round(mergedBytes / 1024.0d),
            boundedReads: mergedBounded,
            fullReads   : mergedFull,
            boundedRatio: readTotal > 0 ? Math.round(mergedBounded * 100.0d / readTotal) : 0,
            persistent  : true,
            perAction   : breakdown.take(20)
        ] as Map<String, Object>
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
                // Delete existing rows for today+filesystem to replace with fresh totals
                PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM token_usage WHERE recorded_date = ? AND context_layer = ?")
                del.setString(1, dateStr)
                del.setString(2, LAYER)
                del.executeUpdate()
                del.close()

                // Insert one row per key with accumulated totals
                PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO token_usage (recorded_date, session_id, tool_name, call_count, estimated_tokens, response_bytes, context_layer) " +
                    "VALUES (?, ?, ?, ?, 0, ?, ?)")

                callCounts.each { String key, AtomicInteger count ->
                    long bytes = responseBytes[key]?.get() ?: 0L
                    ins.setString(1, dateStr)
                    ins.setString(2, sessionStart.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    ins.setString(3, key)
                    ins.setInt(4, count.get())
                    ins.setLong(5, bytes)
                    ins.setString(6, LAYER)
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

    /** Load today's existing DB rows into memory on startup (survive restarts) */
    private void loadTodayFromDb() {
        String todayStr = currentDate.toString()
        withConnection { Connection conn ->
            PreparedStatement ps = conn.prepareStatement(
                "SELECT tool_name, SUM(call_count) as calls, SUM(response_bytes) as bytes " +
                "FROM token_usage WHERE recorded_date = ? AND context_layer = ? GROUP BY tool_name")
            ps.setString(1, todayStr)
            ps.setString(2, LAYER)
            ResultSet rs = ps.executeQuery()
            int loaded = 0
            while (rs.next()) {
                String key = rs.getString('tool_name')
                int calls  = rs.getInt('calls')
                long bytes = rs.getLong('bytes')
                callCounts.computeIfAbsent(key, { k -> new AtomicInteger(0) }).addAndGet(calls)
                responseBytes.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(bytes)
                totalCalls.addAndGet(calls)
                totalBytes.addAndGet(bytes)
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
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS token_usage (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    recorded_date    TEXT NOT NULL,
                    session_id       TEXT,
                    tool_name        TEXT NOT NULL,
                    call_count       INTEGER DEFAULT 1,
                    estimated_tokens INTEGER DEFAULT 0,
                    response_bytes   INTEGER DEFAULT 0,
                    context_layer    TEXT DEFAULT 'other'
                )""")
        }
    }

    private void withConnection(Closure action) {
        Class.forName('org.sqlite.JDBC')
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            action(conn)
        } finally {
            conn.close()
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
            totalCalls.set(0)
            totalBytes.set(0)
            boundedCalls.set(0)
            fullReadCalls.set(0)
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
