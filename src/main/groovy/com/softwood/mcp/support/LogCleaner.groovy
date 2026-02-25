package com.softwood.mcp.support

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Clears MCP log files on startup to keep logs relevant to current session.
 *
 * Clears:
 *   ~/.mcp-logs/filesystem-server.log
 *   Claude AppData logs (mcp-server-groovy-filesystem.log, mcp.log if >1MB)
 *
 * v0.0.5: Extracted from StdioMcpServer.
 */
@Slf4j
@CompileStatic
class LogCleaner {

    static int clearLogsOnStartup() {
        String sessionHeader = "=== Log cleared on startup - New Claude session ===\n" +
                               "Timestamp: ${new Date()}\n" +
                               "=" * 60 + "\n\n"

        int clearedCount = 0
        String userHome = System.getProperty("user.home")

        // Clear Claude AppData logs (canonical log location - ~/.mcp-logs is abolished)
        Path claudeLogsDir = Paths.get(userHome, "AppData", "Roaming", "Claude", "logs")
        if (Files.exists(claudeLogsDir)) {
            clearedCount += clearFile(claudeLogsDir.resolve("mcp-server-groovy-filesystem.log"), sessionHeader)

            Path mcpLog = claudeLogsDir.resolve("mcp.log")
            if (Files.exists(mcpLog) && Files.size(mcpLog) > 1_000_000) {
                clearedCount += clearFile(mcpLog, sessionHeader)
            }
        }

        log.debug("Log cleanup complete: {} file(s) cleared", clearedCount)
        return clearedCount
    }

    private static int clearFile(Path path, String header) {
        try {
            if (Files.exists(path)) {
                Files.newBufferedWriter(path).withCloseable { writer ->
                    writer.write(header)
                }
                return 1
            }
        } catch (Exception e) {
            log.debug("Could not clear log file {}: {}", path, e.message)
        }
        return 0
    }
}
