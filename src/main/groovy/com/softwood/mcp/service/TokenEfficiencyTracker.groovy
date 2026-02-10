package com.softwood.mcp.service

import com.softwood.mcp.event.McpRequestEvent
import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

import java.nio.file.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Token Efficiency Tracker - measures how much data the groovy-filesystem MCP
 * server's targeted read tools save vs naive full-file reads.
 *
 * Hooks into the existing McpRequestEvent lifecycle. On each COMPLETED tool call,
 * calculates:
 *   - responseBytes: actual bytes returned to Claude
 *   - fullFileBytes: what a full readFile would have returned (the "naive" baseline)
 *   - bytesSaved:    fullFileBytes - responseBytes
 *
 * Accumulates daily and per-tool statistics. Exposes a tool endpoint so you can
 * query efficiency stats from the MCP client.
 *
 * Tools classified as "optimised" (bounded reads):
 *   headFile, tailFile, readFileRange, grepFile, countLines, getFileSummary,
 *   fileExists, searchInProject, searchFiles, findFilesByName,
 *   listChildrenOnly, getDirectoryTree
 *
 * Tools classified as "full" (return all content):
 *   readFile, readMultipleFiles
 *
 * Tools classified as "write/other" (no read savings):
 *   writeFile, replaceInFile, appendToFile, copyFile, moveFile, deleteFile,
 *   createDirectory, executeGroovyScript, etc.
 *
 * @since v0.0.6
 */
@Service
@Slf4j
@CompileStatic
class TokenEfficiencyTracker implements ToolHandler {

    // ========================================================================
    // TOOL CATEGORIES
    // ========================================================================

    /** Tools that return bounded/filtered content - these are the "savings" tools */
    private static final Set<String> OPTIMISED_READ_TOOLS = [
        'headFile', 'tailFile', 'readFileRange', 'grepFile', 'countLines',
        'getFileSummary', 'fileExists', 'getFileInfo',
        'searchInProject', 'searchFiles', 'findFilesByName',
        'listChildrenOnly', 'listDirectory', 'listDirectoryWithSizes',
        'getDirectoryTree'
    ] as Set<String>

    /** Tools that return full file content - the "naive" baseline */
    private static final Set<String> FULL_READ_TOOLS = [
        'readFile', 'readMultipleFiles'
    ] as Set<String>

    /** All read tools (optimised + full) */
    private static final Set<String> ALL_READ_TOOLS = (OPTIMISED_READ_TOOLS + FULL_READ_TOOLS) as Set<String>

    // ========================================================================
    // METRICS STATE
    // ========================================================================

    /** Current tracking date - resets stats on day change */
    private volatile LocalDate currentDate = LocalDate.now()

    /** Per-tool call counts */
    private final ConcurrentHashMap<String, AtomicInteger> toolCallCounts = new ConcurrentHashMap<>()

    /** Per-tool total response bytes */
    private final ConcurrentHashMap<String, AtomicLong> toolResponseBytes = new ConcurrentHashMap<>()

    /** Per-tool estimated full-read bytes (what readFile would have returned) */
    private final ConcurrentHashMap<String, AtomicLong> toolFullReadBytes = new ConcurrentHashMap<>()

    /** Global counters */
    private final AtomicInteger totalRequests = new AtomicInteger(0)
    private final AtomicLong totalResponseBytes = new AtomicLong(0)
    private final AtomicLong totalFullReadBytes = new AtomicLong(0)
    private final AtomicLong totalBytesSaved = new AtomicLong(0)
    private final AtomicInteger optimisedCallCount = new AtomicInteger(0)
    private final AtomicInteger fullReadCallCount = new AtomicInteger(0)
    private final AtomicInteger writeCallCount = new AtomicInteger(0)

    /** Recent call log for detail view (bounded circular buffer) */
    private final List<Map<String, Object>> recentCalls = Collections.synchronizedList(new ArrayList<>())
    private static final int MAX_RECENT_CALLS = 100

    // ========================================================================
    // PATHSERVICE FOR FILE SIZE LOOKUPS
    // ========================================================================

    private final PathService pathService

    TokenEfficiencyTracker(PathService pathService) {
        this.pathService = pathService
    }

    // ========================================================================
    // EVENT LISTENER - hooks into existing McpRequestEvent lifecycle
    // ========================================================================

    @EventListener
    void onMcpRequestEvent(McpRequestEvent event) {
        // Only track completed tool calls (skip our own stats tool)
        if (event.stage != McpRequestEvent.Stage.SENT) return
        if (event.method != 'tools/call') return
        if (event.toolName == 'getEfficiencyStats') return

        checkDateRollover()

        String tool = event.toolName ?: 'unknown'
        int responseBytes = event.responseSizeBytes

        totalRequests.incrementAndGet()
        totalResponseBytes.addAndGet(responseBytes)
        toolCallCounts.computeIfAbsent(tool, { new AtomicInteger(0) }).incrementAndGet()
        toolResponseBytes.computeIfAbsent(tool, { new AtomicLong(0) }).addAndGet(responseBytes)

        // Classify and estimate savings
        long estimatedFullBytes = responseBytes  // default: no savings
        String category = 'other'

        if (tool in OPTIMISED_READ_TOOLS) {
            category = 'optimised'
            optimisedCallCount.incrementAndGet()
            // For optimised tools, estimate what a full readFile would have cost
            estimatedFullBytes = estimateFullFileBytes(tool, event, responseBytes)
        } else if (tool in FULL_READ_TOOLS) {
            category = 'full_read'
            fullReadCallCount.incrementAndGet()
            estimatedFullBytes = responseBytes  // full read = no savings
        } else {
            writeCallCount.incrementAndGet()
        }

        toolFullReadBytes.computeIfAbsent(tool, { new AtomicLong(0) }).addAndGet(estimatedFullBytes)
        totalFullReadBytes.addAndGet(estimatedFullBytes)

        long saved = Math.max(0, estimatedFullBytes - responseBytes)
        totalBytesSaved.addAndGet(saved)

        // Log to recent calls buffer
        addRecentCall(tool, category, responseBytes, estimatedFullBytes, saved, event.elapsedMs)

        if (saved > 1024) {
            log.debug("TOKEN EFFICIENCY: {} saved {}KB (returned {}B vs full {}B)",
                tool, saved / 1024, responseBytes, estimatedFullBytes)
        }
    }

    // ========================================================================
    // FULL FILE SIZE ESTIMATION
    // ========================================================================

    /**
     * Estimate what a full readFile would have returned for this optimised tool call.
     *
     * Strategy:
     * - If the tool args contain a 'path' pointing to a real file, use ACTUAL file size
     * - For directory tools, use a conservative multiplier based on typical listings
     * - Falls back to response * multiplier if file size unavailable
     */
    private long estimateFullFileBytes(String tool, McpRequestEvent event, int responseBytes) {
        // v0.0.6: Try to get actual file size from tool args
        long actualFileSize = getActualFileSize(event.toolArgs)

        if (actualFileSize > 0) {
            // We know the real file size - use it directly
            // For search/list tools, the "full" alternative is still a multiplier
            if (tool in ['searchInProject', 'searchFiles', 'findFilesByName',
                         'listChildrenOnly', 'listDirectory', 'listDirectoryWithSizes',
                         'getDirectoryTree']) {
                // Directory/search tools: actual file is a directory, not directly comparable
                return Math.max(responseBytes * 15L, 8192L)
            }
            // For all file-targeting tools, the baseline is "what if you read the whole file"
            return actualFileSize
        }

        // Fallback: heuristic multipliers when we can't determine file size
        switch (tool) {
            case 'countLines':
            case 'fileExists':
            case 'getFileInfo':
            case 'getFileSummary':
                return Math.max(responseBytes * 10L, 8192L)

            case 'headFile':
            case 'tailFile':
                return Math.max(responseBytes * 8L, 4096L)

            case 'readFileRange':
                return Math.max(responseBytes * 6L, 4096L)

            case 'grepFile':
                return Math.max(responseBytes * 12L, 4096L)

            case 'searchInProject':
            case 'searchFiles':
            case 'findFilesByName':
                return Math.max(responseBytes * 20L, 16384L)

            case 'listChildrenOnly':
            case 'listDirectory':
            case 'listDirectoryWithSizes':
            case 'getDirectoryTree':
                return Math.max(responseBytes * 15L, 8192L)

            default:
                return Math.max(responseBytes * 5L, 2048L)
        }
    }

    /**
     * Try to resolve actual file size from the tool arguments.
     * Returns -1 if no path found or file doesn't exist.
     */
    private long getActualFileSize(Map<String, Object> toolArgs) {
        if (toolArgs == null) return -1L

        String filePath = toolArgs.path as String
        if (!filePath) return -1L

        try {
            String normalized = pathService.normalizePath(filePath)
            Path p = Paths.get(normalized)
            if (Files.isRegularFile(p)) {
                return Files.size(p)
            }
        } catch (Exception ignored) {
            // File doesn't exist or path invalid - fall back to heuristic
        }
        return -1L
    }

    // ========================================================================
    // RECENT CALLS BUFFER
    // ========================================================================

    private void addRecentCall(String tool, String category, int responseBytes,
                               long fullBytes, long saved, long elapsedMs) {
        Map<String, Object> entry = [
            timestamp: LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            tool: tool,
            category: category,
            responseBytes: responseBytes,
            estimatedFullBytes: fullBytes,
            bytesSaved: saved,
            savingsPercent: fullBytes > 0 ? Math.round((saved * 100.0d) / fullBytes) : 0,
            elapsedMs: elapsedMs
        ] as Map<String, Object>

        synchronized (recentCalls) {
            recentCalls.add(entry)
            while (recentCalls.size() > MAX_RECENT_CALLS) {
                recentCalls.remove(0)
            }
        }
    }

    // ========================================================================
    // DATE ROLLOVER
    // ========================================================================

    private void checkDateRollover() {
        LocalDate today = LocalDate.now()
        if (today != currentDate) {
            log.info("TOKEN EFFICIENCY: Day rollover {} -> {} - resetting stats", currentDate, today)
            resetStats()
            currentDate = today
        }
    }

    private void resetStats() {
        toolCallCounts.clear()
        toolResponseBytes.clear()
        toolFullReadBytes.clear()
        totalRequests.set(0)
        totalResponseBytes.set(0)
        totalFullReadBytes.set(0)
        totalBytesSaved.set(0)
        optimisedCallCount.set(0)
        fullReadCallCount.set(0)
        writeCallCount.set(0)
        synchronized (recentCalls) {
            recentCalls.clear()
        }
    }

    // ========================================================================
    // STATS QUERY METHODS (used by tool endpoint)
    // ========================================================================

    Map<String, Object> getEfficiencyStats(boolean includeRecent = false) {
        checkDateRollover()

        long totalResp = totalResponseBytes.get()
        long totalFull = totalFullReadBytes.get()
        long totalSaved = totalBytesSaved.get()
        double savingsPercent = totalFull > 0 ? (totalSaved * 100.0d / totalFull) : 0.0d

        // Per-tool breakdown
        List<Map<String, Object>> toolBreakdown = []
        toolCallCounts.each { String tool, AtomicInteger count ->
            long respBytes = toolResponseBytes[tool]?.get() ?: 0
            long fullBytes = toolFullReadBytes[tool]?.get() ?: 0
            long saved = Math.max(0, fullBytes - respBytes)
            String cat = tool in OPTIMISED_READ_TOOLS ? 'optimised' :
                         tool in FULL_READ_TOOLS ? 'full_read' : 'other'

            toolBreakdown.add([
                tool: tool,
                category: cat,
                calls: count.get(),
                responseBytes: respBytes,
                responseKB: Math.round(respBytes / 1024.0d),
                estimatedFullBytes: fullBytes,
                estimatedFullKB: Math.round(fullBytes / 1024.0d),
                bytesSaved: saved,
                savedKB: Math.round(saved / 1024.0d),
                savingsPercent: fullBytes > 0 ? Math.round(saved * 100.0d / fullBytes) : 0
            ] as Map<String, Object>)
        }

        // Sort by savings descending
        toolBreakdown.sort { Map a, Map b -> (b.bytesSaved as long) <=> (a.bytesSaved as long) }

        // Approximate token estimate (1 token ≈ 4 bytes for English text)
        long estimatedTokensSaved = (long)(totalSaved / 4)

        Map<String, Object> stats = [
            date: currentDate.toString(),
            summary: [
                totalToolCalls: totalRequests.get(),
                optimisedCalls: optimisedCallCount.get(),
                fullReadCalls: fullReadCallCount.get(),
                writeCalls: writeCallCount.get(),
                optimisedCallPercent: totalRequests.get() > 0 ?
                    Math.round(optimisedCallCount.get() * 100.0d / totalRequests.get()) : 0,
                totalResponseBytes: totalResp,
                totalResponseKB: Math.round(totalResp / 1024.0d),
                estimatedFullReadBytes: totalFull,
                estimatedFullReadKB: Math.round(totalFull / 1024.0d),
                totalBytesSaved: totalSaved,
                totalSavedKB: Math.round(totalSaved / 1024.0d),
                savingsPercent: Math.round(savingsPercent),
                estimatedTokensSaved: estimatedTokensSaved
            ],
            perTool: toolBreakdown
        ] as Map<String, Object>

        if (includeRecent) {
            synchronized (recentCalls) {
                stats.recentCalls = new ArrayList<>(recentCalls)
            }
        }

        return stats
    }

    // ========================================================================
    // TOOL HANDLER INTERFACE
    // ========================================================================

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [
            [
                name: "getEfficiencyStats",
                description: "Get token efficiency statistics showing how much data the optimised read tools " +
                    "(headFile, tailFile, grepFile, etc.) save compared to naive full-file reads. " +
                    "Shows per-tool breakdown, total bytes/tokens saved, and savings percentages. " +
                    "Use includeRecent=true to see the last 100 individual tool calls.",
                inputSchema: [
                    type: "object",
                    properties: [
                        includeRecent: [type: "boolean",
                            description: "Include last 100 individual call details (default: false)"]
                    ],
                    required: []
                ]
            ] as Map<String, Object>
        ]
    }

    @Override
    boolean canHandle(String toolName) {
        toolName == 'getEfficiencyStats'
    }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> args, Object requestId) {
        if (toolName == 'getEfficiencyStats') {
            boolean includeRecent = args?.includeRecent as boolean ?: false
            Map<String, Object> stats = getEfficiencyStats(includeRecent)
            return McpResponse.success(requestId, [
                content: [[type: "text", text: groovy.json.JsonOutput.prettyPrint(
                    groovy.json.JsonOutput.toJson(stats)
                )]]
            ] as Map<String, Object>)
        }
        return McpResponse.error(requestId, -32601, "Unknown tool: ${toolName}" as String)
    }
}
