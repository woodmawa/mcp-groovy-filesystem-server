package com.softwood.mcp.service

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import jakarta.annotation.PreDestroy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
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

    /** Single daemon writer — serialises all SQLite writes */
    private final ExecutorService asyncWriter = Executors.newSingleThreadExecutor { Runnable r ->
        Thread t = new Thread(r, 'fs-telemetry-writer')
        t.daemon = true
        t
    }

    /** Repeat-call detection: "toolName:argsHash" -> first-call timestamp, per session */
    private final Map<String, String> sessionCallCache = new ConcurrentHashMap<>()
    private volatile String trackedSessionId = null

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
                         int responseChars, Map<String, Object> args) {
        if (!dbPath) return   // persistence not configured — silent no-op

        // Reset repeat cache when session changes
        if (sessionId && sessionId != trackedSessionId) {
            sessionCallCache.clear()
            trackedSessionId = sessionId
        }

        asyncWriter.submit {
            try {
                String argsHash  = buildArgsHash(args)
                String cacheKey  = "${toolName}:${argsHash}"
                boolean isRepeat = sessionCallCache.containsKey(cacheKey)
                if (!isRepeat) sessionCallCache.put(cacheKey, new Date().toInstant().toString())

                int tokenEst = Math.round(responseChars / 4) as int

                withConnection { Connection conn ->
                    PreparedStatement stmt = conn.prepareStatement('''
                        INSERT INTO tool_call_telemetry
                            (session_id, tool_name, server_name,
                             response_char_count, response_token_est,
                             is_repeat_call, args_hash)
                        VALUES (?,?,?,?,?,?,?)''')
                    stmt.setString(1, sessionId ?: 'unknown')
                    stmt.setString(2, toolName)
                    stmt.setString(3, 'filesystem-server')
                    stmt.setInt(4, responseChars)
                    stmt.setInt(5, tokenEst)
                    stmt.setInt(6, isRepeat ? 1 : 0)
                    stmt.setString(7, argsHash)
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

    @PreDestroy
    void shutdown() {
        asyncWriter.shutdown()
        try {
            if (!asyncWriter.awaitTermination(3, TimeUnit.SECONDS)) asyncWriter.shutdownNow()
        } catch (InterruptedException e) {
            asyncWriter.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void withConnection(Closure work) {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            conn.autoCommit = true
            work(conn)
        } finally {
            conn.close()
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
