package com.softwood.mcp.support

import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * WP-0 regression guard.
 *
 * LogCleaner used to truncate files under %APPDATA%/Roaming/Claude/logs on every stdio start.
 * That directory belongs to Claude Desktop. Whether or not the client is writing there in any
 * given app build, it is not ours to empty, and for three days a 154-byte file in it was read
 * as erased evidence of a fault.
 *
 * This spec asserts on persisted state - what is on disk after the call - rather than on a
 * return value, because the return value was never the thing that hurt.
 */
class LogCleanerSpec extends Specification {

    def "clearLogsOnStartup leaves Claude Desktop's log files untouched"() {
        given: 'a home directory laid out like the real one, with client logs in it'
        Path fakeHome = Files.createTempDirectory('logcleaner-home')
        Path claudeLogs = fakeHome.resolve('AppData/Roaming/Claude/logs')
        Files.createDirectories(claudeLogs)

        Path serverLog = claudeLogs.resolve('mcp-server-groovy-filesystem.log')
        Path aliasLog = claudeLogs.resolve('mcp-server-filesystem.log')
        Path mcpLog = claudeLogs.resolve('mcp.log')

        String serverBody = 'connected to mcp-groovy-filesystem-server (8 tools)'
        String aliasBody = 'announcing mcp-groovy-filesystem-server: 8 tool(s)'
        // (bodies deliberately carry no trailing newline - the assertion is byte equality)
        // Comfortably over the 1MB threshold the old code used to decide mcp.log was fair game
        String mcpBody = 'x' * 1_200_000

        serverLog.text = serverBody
        aliasLog.text = aliasBody
        mcpLog.text = mcpBody

        and: 'user.home points at it for the duration of the call'
        String originalHome = System.getProperty('user.home')
        System.setProperty('user.home', fakeHome.toAbsolutePath().toString())

        when:
        int cleared = LogCleaner.clearLogsOnStartup()

        then: 'nothing was cleared, and every file is byte-for-byte what it was'
        cleared == 0
        serverLog.text == serverBody
        aliasLog.text == aliasBody
        mcpLog.text.length() == mcpBody.length()

        cleanup:
        System.setProperty('user.home', originalHome)
        fakeHome.toFile().deleteDir()
    }

    def "clearLogsOnStartup creates nothing when the Claude log directory is absent"() {
        given:
        Path fakeHome = Files.createTempDirectory('logcleaner-empty-home')
        String originalHome = System.getProperty('user.home')
        System.setProperty('user.home', fakeHome.toAbsolutePath().toString())

        when:
        int cleared = LogCleaner.clearLogsOnStartup()

        then:
        cleared == 0
        !Files.exists(fakeHome.resolve('AppData/Roaming/Claude/logs'))

        cleanup:
        System.setProperty('user.home', originalHome)
        fakeHome.toFile().deleteDir()
    }
}
