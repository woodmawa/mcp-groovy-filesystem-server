package com.softwood.mcp

import org.springframework.context.support.GenericApplicationContext
import spock.lang.Specification

import java.util.function.IntConsumer

/**
 * FS 0.9.22 guard for the stdin-EOF shutdown contract.
 *
 * <p>FS has shut down correctly on EOF since v0.7.17, so this spec fixes no defect. It exists
 * because nothing in any of the three servers tested this path, and AW silently did not do it -
 * found from the process table on 2026-09-09 when two AW instances survived a Claude Desktop
 * auto-update that replaced every FS and CS instance. The same spec now sits in all three
 * repositories so none of them can drift into AW's state unobserved.</p>
 *
 * <p>Both tests drive the REAL {@code run()} loop rather than calling the shutdown method
 * directly, because "the method exists" was never the thing in doubt - "the EOF branch reaches
 * it" was. {@code System.in} is replaced with an empty stream, which is exactly what Claude
 * Desktop closing the pipe looks like to {@code readLine()}.</p>
 */
class StdioMcpServerEofShutdownSpec extends Specification {

    private InputStream originalIn

    def setup() {
        originalIn = System.in
    }

    def cleanup() {
        System.setIn(originalIn)
        StdioMcpServer.exitAction = StdioMcpServer.DEFAULT_EXIT_ACTION
    }

    def "EOF on stdin closes the Spring context and terminates the JVM"() {
        given: 'a real, refreshed context - a mock would let a missing SpringApplication.exit pass'
        GenericApplicationContext context = new GenericApplicationContext()
        context.refresh()
        assert context.isActive()

        and: 'the exit call captured rather than executed'
        List<Integer> exitCodes = []
        StdioMcpServer.exitAction = { int code -> exitCodes << code } as IntConsumer

        and: 'stdin already at EOF, as it is the instant Claude Desktop closes the pipe'
        System.setIn(new ByteArrayInputStream(new byte[0]))

        and: '''mcpController and eventPublisher are null on purpose: the EOF path must not touch
                either of them, and an NPE here would be a real finding rather than a broken test'''
        StdioMcpServer server = new StdioMcpServer(null, null, context)

        when:
        server.run()

        then: 'the Spring context was closed, so UsageTracker flushed and companions were stopped'
        !context.isActive()

        and: 'and the JVM was told to exit, exactly once, with the code Spring computed'
        exitCodes == [0]
    }

    def "a failure inside the Spring shutdown still terminates the JVM"() {
        given: 'a null context, so SpringApplication.exit throws inside triggerCleanShutdown'
        List<Integer> exitCodes = []
        StdioMcpServer.exitAction = { int code -> exitCodes << code } as IntConsumer
        System.setIn(new ByteArrayInputStream(new byte[0]))
        StdioMcpServer server = new StdioMcpServer(null, null, null)

        when:
        server.run()

        then: '''the fallback fires. Without it the catch block could swallow the exit and leave a
                 resident JVM holding its SQLite connections open, which is the whole defect'''
        exitCodes == [1]
    }

    def "the default exit action is restored between tests"() {
        expect: 'guards against one spec leaking a captured exitAction into another'
        StdioMcpServer.exitAction === StdioMcpServer.DEFAULT_EXIT_ACTION
    }
}
