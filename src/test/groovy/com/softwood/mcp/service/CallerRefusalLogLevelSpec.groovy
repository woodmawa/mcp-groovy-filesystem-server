package com.softwood.mcp.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS 0.9.91 -- a refusal of the CALLER's input is a WARN, not an ERROR.
 *
 * <p>Found 2026-10-14 by AW's app-error scan: FileReadService, FileSearchService and FileLifecycleService log every
 * exception from a catch-all at ERROR, so "Path is not a file", "Path is not a directory" and "Source not found" --
 * the tool correctly refusing a bad request -- read as server faults and became incidents. And
 * AbstractFileService logged "Error checking path allowed: null" -- a message-less exception with no class named.</p>
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS -- CR caller-input refusals log at WARN')
class CallerRefusalLogLevelSpec extends Specification {

    @Autowired FileReadService fileReadService
    @Autowired FileSearchService fileSearchService
    @Autowired FileLifecycleService fileLifecycleService

    @TempDir Path tempDir

    ListAppender<ILoggingEvent> captured
    Logger serviceLogger

    def setup() {
        serviceLogger = (Logger) LoggerFactory.getLogger('com.softwood.mcp.service')
        captured = new ListAppender<ILoggingEvent>()
        captured.start()
        serviceLogger.addAppender(captured)
    }

    def cleanup() { serviceLogger?.detachAppender(captured) }

    private List<ILoggingEvent> at(Level level, String fragment) {
        captured.list.findAll { ILoggingEvent e -> e.level == level && e.formattedMessage.contains(fragment) }
    }

    private String p(Path x) { x.toString().replace('\\', '/') }

    def "CR-1: file_read on a directory is refused, logged at WARN, never ERROR"() {
        given:
        Path dir = Files.createDirectories(tempDir.resolve('adir'))

        when:
        def r = fileReadService.handleToolCall('file_read', [action: 'read', path: p(dir), options: [allowNoLocate: true]], 1)

        then:
        r != null
        at(Level.ERROR, 'file_read error').isEmpty()
        !at(Level.WARN, 'Path is not a file').isEmpty()
    }

    def "CR-2: file_search content on a file is refused, logged at WARN, never ERROR"() {
        given:
        Path f = Files.writeString(tempDir.resolve('afile.txt'), 'x')

        when:
        def r = fileSearchService.handleToolCall('file_search', [action: 'content', path: p(f), options: [contentPattern: 'x']], 1)

        then:
        r != null
        at(Level.ERROR, 'file_search error').isEmpty()
        !at(Level.WARN, 'Path is not a directory').isEmpty()
    }

    def "CR-3: file_lifecycle move of a missing source is refused, logged at WARN, never ERROR"() {
        when:
        def r = fileLifecycleService.handleToolCall('file_lifecycle',
            [action: 'move', path: p(tempDir.resolve('nope.txt')), dst: p(tempDir.resolve('dest.txt'))], 1)

        then:
        r != null
        at(Level.ERROR, 'file_lifecycle error').isEmpty()
        !at(Level.WARN, 'Source not found').isEmpty()
    }

    def "CR-4: isPathAllowed(null) is a plain false, with no ERROR"() {
        expect:
        !fileReadService.isPathAllowed(null)
        at(Level.ERROR, 'Error checking path allowed').isEmpty()
    }
}
