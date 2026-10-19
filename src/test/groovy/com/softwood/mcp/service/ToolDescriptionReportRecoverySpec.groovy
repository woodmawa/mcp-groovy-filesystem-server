package com.softwood.mcp.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * B6 (optimised-learning ARC-STATE): ToolDescriptionRegistry logged the first refused report of
 * the served tool descriptions and never the later success. On 2026-10-16 (X68 item 1) the log
 * read as a CS that was still refusing, when CS had accepted the next poll -- and the
 * investigation that followed was spent finding that out.
 *
 * TDR-1: a refusal is logged once, however many polls it lasts (CONTROL -- green before and after)
 * TDR-2: the first accepted report after a logged refusal says so (RED on 0.9.102)
 * TDR-3: an accepted report with no prior refusal logs no recovery (CONTROL)
 */
class ToolDescriptionReportRecoverySpec extends Specification {

    ListAppender<ILoggingEvent> captured
    Logger logger
    ContextServerClient client
    ToolDescriptionRegistry registry

    def setup() {
        logger = (Logger) LoggerFactory.getLogger(ToolDescriptionRegistry)
        captured = new ListAppender<ILoggingEvent>()
        captured.start()
        logger.addAppender(captured)
        client = Mock(ContextServerClient)
        registry = new ToolDescriptionRegistry()
        registry.setContextServerClient(client)
    }

    def cleanup() { logger?.detachAppender(captured) }

    private List<String> infos(String fragment) {
        captured.list.findAll { ILoggingEvent e -> e.level == Level.INFO && e.formattedMessage.contains(fragment) }
                     *.formattedMessage
    }

    private static Map<String, String> hashes(String h) { ['file_read': h] as Map<String, String> }

    def 'TDR-1: CONTROL -- a refusal is logged once however many polls it lasts'() {
        given:
        client.recordToolDescriptions(*_) >> false

        when:
        3.times { registry.report(hashes('aaaaaaaaaaaa')) }

        then:
        infos('not accepted').size() == 1
    }

    def 'TDR-2: the first accepted report after a logged refusal says so'() {
        given:
        client.recordToolDescriptions(*_) >>> [false, false, true]

        when:
        registry.report(hashes('aaaaaaaaaaaa'))
        registry.report(hashes('aaaaaaaaaaaa'))
        registry.report(hashes('aaaaaaaaaaaa'))

        then:
        infos('not accepted').size() == 1
        infos('accepted after').size() == 1
    }

    def 'TDR-3: CONTROL -- an accepted report with no prior refusal logs no recovery'() {
        given:
        client.recordToolDescriptions(*_) >> true

        when:
        registry.report(hashes('aaaaaaaaaaaa'))

        then:
        infos('accepted after').isEmpty()
    }
}
