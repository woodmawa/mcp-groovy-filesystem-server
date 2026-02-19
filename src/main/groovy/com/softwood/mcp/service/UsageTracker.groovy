package com.softwood.mcp.service

import com.softwood.mcp.event.McpRequestEvent
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * UsageTracker — lightweight per-action call count and response size tracking.
 *
 * Hooks into McpRequestEvent lifecycle. No separate tool — stats are
 * surfaced through the existing 'tools stats' action.
 *
 * Tracks:
 * - Per tool+action call counts and total response bytes
 * - Bounded vs full read ratio (head/tail/grep/range vs read/multi)
 * - Session totals with daily rollover
 *
 * v0.7.1
 */
@Service
@Slf4j
@CompileStatic
class UsageTracker {

    /** Actions classified as bounded (token-efficient) reads */
    private static final Set<String> BOUNDED_ACTIONS = [
        'head', 'tail', 'range', 'grep', 'summary', 'exists',
        'info', 'structure', 'checksum', 'project_root', 'allowed_dirs',
        'normalize', 'children', 'sizes', 'content', 'name', 'project'
    ] as Set<String>

    /** Actions classified as full reads */
    private static final Set<String> FULL_READ_ACTIONS = [
        'read', 'multi'
    ] as Set<String>

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private volatile LocalDate currentDate = LocalDate.now()

    /** "tool:action" -> call count */
    private final ConcurrentHashMap<String, AtomicInteger> callCounts = new ConcurrentHashMap<>()

    /** "tool:action" -> total response bytes */
    private final ConcurrentHashMap<String, AtomicLong> responseBytes = new ConcurrentHashMap<>()

    private final AtomicInteger totalCalls = new AtomicInteger(0)
    private final AtomicLong totalBytes = new AtomicLong(0)
    private final AtomicInteger boundedCalls = new AtomicInteger(0)
    private final AtomicInteger fullReadCalls = new AtomicInteger(0)
    private volatile LocalDateTime sessionStart = LocalDateTime.now()

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
        String key    = action ? "${tool}:${action}" : tool
        int size      = event.responseSizeBytes

        totalCalls.incrementAndGet()
        totalBytes.addAndGet(size)
        callCounts.computeIfAbsent(key, { k -> new AtomicInteger(0) }).incrementAndGet()
        responseBytes.computeIfAbsent(key, { k -> new AtomicLong(0) }).addAndGet(size)

        // Classify
        if (action && action in BOUNDED_ACTIONS) {
            boundedCalls.incrementAndGet()
        } else if (action && action in FULL_READ_ACTIONS) {
            fullReadCalls.incrementAndGet()
        }
    }

    private static String extractAction(Map<String, Object> toolArgs) {
        return toolArgs?.action as String
    }

    // -----------------------------------------------------------------------
    // Stats (consumed by ToolsService.doStats)
    // -----------------------------------------------------------------------

    Map<String, Object> getStats() {
        checkDateRollover()

        int total     = totalCalls.get()
        int bounded   = boundedCalls.get()
        int fullReads = fullReadCalls.get()
        int readTotal = bounded + fullReads

        // Per-action breakdown sorted by call count
        List<Map<String, Object>> breakdown = []
        callCounts.each { String key, AtomicInteger count ->
            long bytes = responseBytes[key]?.get() ?: 0L
            breakdown.add([
                key       : key,
                calls     : count.get(),
                responseKB: Math.round(bytes / 1024.0d)
            ] as Map<String, Object>)
        }
        breakdown.sort { Map a, Map b -> (b.calls as int) <=> (a.calls as int) }

        return [
            date         : currentDate.toString(),
            sessionStart : sessionStart.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            totalCalls   : total,
            totalBytes   : totalBytes.get(),
            totalKB      : Math.round(totalBytes.get() / 1024.0d),
            boundedReads : bounded,
            fullReads    : fullReads,
            boundedRatio : readTotal > 0 ? Math.round(bounded * 100.0d / readTotal) : 0,
            perAction    : breakdown.take(20)
        ] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // Rollover
    // -----------------------------------------------------------------------

    private void checkDateRollover() {
        LocalDate today = LocalDate.now()
        if (today != currentDate) {
            log.info("UsageTracker: day rollover {} -> {}", currentDate, today)
            callCounts.clear()
            responseBytes.clear()
            totalCalls.set(0)
            totalBytes.set(0)
            boundedCalls.set(0)
            fullReadCalls.set(0)
            currentDate = today
            sessionStart = LocalDateTime.now()
        }
    }
}
