package com.softwood.mcp

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.Appender
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Title

/**
 * FS 0.9.90 -- a test JVM writes no log file into claude-sync/logs.
 *
 * <p>Found 2026-10-14 when AW 1.30.91 began scanning the servers' per-pid logs: 27 of the 110 FS files in
 * claude-sync/logs were gradle test JVMs (thread [Test worker], "Starting ...Spec"), because logback-spring.xml
 * attaches the FILE appender in every profile. Their deliberate failures became live incidents --
 * SecurityService 57, FileLifecycleService 40, ServerLifecycleService 16. CS and AW tests write none.</p>
 *
 * <p>Boots a Spring context, because Spring Boot's logging system is what loads logback-spring.xml; a plain spec
 * would pass on today's code without proving anything.</p>
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS -- LT test JVMs do not log into claude-sync/logs')
class TestLogTargetSpec extends Specification {

    def "LT-1: no logger in a test JVM has a file appender under claude-sync/logs, and the root still logs somewhere"() {
        when:
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory()
        List<String> files = []
        ctx.loggerList.each { Logger l ->
            Iterator<Appender> appenders = l.iteratorForAppenders()
            while (appenders.hasNext()) {
                Appender a = appenders.next()
                if (a instanceof FileAppender) files << ((a as FileAppender).file ?: '')
            }
        }

        then:
        files.findAll { String f -> f.replace('\\', '/').contains('claude-sync/logs') }.isEmpty()

        and: 'CONTROL: the root logger still has an appender, so this is not passing by logging nothing'
        ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders().hasNext()
    }
}
