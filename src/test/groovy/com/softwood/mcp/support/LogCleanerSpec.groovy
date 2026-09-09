package com.softwood.mcp.support

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS 0.9.23. Two obligations, both learned the hard way.
 *
 * <p><b>It must not touch Claude Desktop's directory.</b> This class used to truncate files under
 * {@code %APPDATA%/Roaming/Claude/logs} on every start, including the shared {@code mcp.log}.
 * Those are not ours.</p>
 *
 * <p><b>It must prune our own per-pid files.</b> FS 0.9.23 moved to one log file per pid so that
 * logback's size trigger works at all. {@code maxHistory} cannot prune a dead pid's file, because
 * that file is managed by an appender that no longer exists -- so without this sweep, per-pid
 * simply trades an unbounded file for an unbounded directory.</p>
 */
class LogCleanerSpec extends Specification {

    private Path fakeHome
    private String originalHome

    def setup() {
        fakeHome = Files.createTempDirectory('logcleaner-home')
        originalHome = System.getProperty('user.home')
        System.setProperty('user.home', fakeHome.toAbsolutePath().toString())
    }

    def cleanup() {
        System.setProperty('user.home', originalHome)
        fakeHome.toFile().deleteDir()
    }

    private Path logsDir() {
        Path d = fakeHome.resolve('claude-sync/logs')
        Files.createDirectories(d)
        return d
    }

    private Path aged(Path dir, String name, int daysOld) {
        Path p = dir.resolve(name)
        p.text = 'x'
        p.toFile().setLastModified(System.currentTimeMillis() - (daysOld * 24L * 60L * 60L * 1000L))
        return p
    }

    def "stale per-pid log files and their archives are removed"() {
        given:
        Path dir = logsDir()
        Path oldLive    = aged(dir, 'mcp-filesystem-12345.log', 5)
        Path oldArchive = aged(dir, 'mcp-filesystem-12345.2026-09-01.0.log', 5)
        Path legacy     = aged(dir, 'mcp-filesystem.log', 5)

        when:
        int deleted = LogCleaner.clearLogsOnStartup()

        then: 'all three are ours, all three are stale'
        deleted == 3
        !Files.exists(oldLive)
        !Files.exists(oldArchive)
        !Files.exists(legacy)
    }

    def "recent files are kept, whatever their pid"() {
        given: 'a sibling instance writing right now, and one from this morning'
        Path dir = logsDir()
        Path fresh  = aged(dir, 'mcp-filesystem-99999.log', 0)
        Path today  = aged(dir, 'mcp-filesystem-88888.log', 1)

        when:
        int deleted = LogCleaner.clearLogsOnStartup()

        then: 'the retention window is the same 2 days logback promises for its own archives'
        deleted == 0
        Files.exists(fresh)
        Files.exists(today)
    }

    def "other servers' logs and the companion stderr captures are never touched"() {
        given: 'all stale, none of them this server\'s'
        Path dir = logsDir()
        Path cs        = aged(dir, 'mcp-context-111.log', 9)
        Path aw        = aged(dir, 'mcp-agentic-workflow-222.log', 9)
        Path csStdio   = aged(dir, 'mcp-context-stdio-333.log', 9)
        Path companion = aged(dir, 'mcp-companion-filesystem-stderr.log', 9)
        Path unrelated = aged(dir, 'config-watcher.log', 9)

        when:
        int deleted = LogCleaner.clearLogsOnStartup()

        then: '''each server prunes only its own prefix. A sweep that reached across servers would
                 delete a sibling's live file the moment that sibling was idle for two days'''
        deleted == 0
        Files.exists(cs)
        Files.exists(aw)
        Files.exists(csStdio)
        Files.exists(companion)
        Files.exists(unrelated)
    }

    def "Claude Desktop's log directory is left untouched"() {
        given: 'the files this class used to truncate on every start'
        Path claudeLogs = fakeHome.resolve('AppData/Roaming/Claude/logs')
        Files.createDirectories(claudeLogs)
        Path serverLog = claudeLogs.resolve('mcp-server-groovy-filesystem.log')
        Path mcpLog = claudeLogs.resolve('mcp.log')
        String body = 'connected to mcp-groovy-filesystem-server (8 tools)'
        String big = 'x' * 1_200_000
        serverLog.text = body
        mcpLog.text = big
        serverLog.toFile().setLastModified(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000))
        mcpLog.toFile().setLastModified(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000))
        logsDir()

        when: 'even though both are far older than the retention window'
        LogCleaner.clearLogsOnStartup()

        then: 'they are byte-for-byte what they were. That directory is not ours to prune'
        serverLog.text == body
        mcpLog.text.length() == big.length()
    }

    def "a missing claude-sync logs directory is not an error"() {
        when:
        int deleted = LogCleaner.clearLogsOnStartup()

        then:
        deleted == 0
        noExceptionThrown()
    }
}
