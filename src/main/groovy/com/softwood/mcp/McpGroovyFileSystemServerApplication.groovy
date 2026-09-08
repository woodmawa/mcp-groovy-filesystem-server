package com.softwood.mcp

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
@Slf4j
@CompileStatic
class McpGroovyFileSystemServerApplication {

    static void main(String[] args) {
        // v0.8.40: force UTF-8 on stdout so JsonRpcWriter.System.out.println correctly
        // encodes U+2192 and other non-Latin-1 Unicode. Default JVM charset on Windows
        // is Cp1252 which corrupts these chars in tool responses.
        System.setOut(new java.io.PrintStream(System.out, true, 'UTF-8'))

        System.setProperty("spring.main.banner-mode", "off")

        // FS 0.9.18 -- losing a companion start race is a normal outcome, not a failure.
        //
        // ServerLifecycleService.autoStartHttpCompanions checks isPortListening() and then spawns
        // a JVM that takes ~24 seconds to bind. Four stdio instances boot within four seconds of a
        // desktop restart and each runs that pass, so two can both pass the check before either has
        // bound. The loser aborted with APPLICATION FAILED TO START, printed into the log every
        // instance shares -- which is the first thing anyone finds when investigating something
        // else, and on 2026-09-08 it cost an hour of exactly that.
        //
        // The check cannot be made safe where it is: it sits 24 seconds and one process away from
        // the bind it predicts. So the arbiter moves into the child, immediately before Spring
        // starts, where the window is milliseconds rather than seconds; the residual case is caught
        // after run(). Neither is a lock -- who OWNS companions is F-4 of
        // BUILD-BRIEF-2026-09-08-the-companions-that-race, a decision deliberately not invented here.
        Integer fixedPort = configuredHttpPort()
        if (fixedPort != null && isServed(fixedPort)) {
            System.err.println("mcp-filesystem: port ${fixedPort} is already served by another " +
                               "instance -- this companion is not needed, exiting quietly (exit 0)")
            System.exit(0)
        }

        try {
            SpringApplication.run(McpGroovyFileSystemServerApplication, args)
        } catch (Exception e) {
            Integer lost = portInUseFrom(e)
            if (lost != null) {
                System.err.println("mcp-filesystem: lost the race for port ${lost} to another " +
                                   "instance -- exiting quietly (exit 0), not a failure")
                System.exit(0)
            }
            throw e
        }
    }

    /**
     * The port this process will actually bind, or null when it will bind nothing.
     *
     * <p>Only the {@code http} profile takes a fixed port; companions are launched with
     * {@code -Dspring.profiles.active=http -DMCP_HTTP_PORT=<n>}. The stdio profile sets
     * {@code server.port: 0}, so a stdio instance must never take this path -- returning a port for
     * one would make every stdio instance exit whenever a companion happened to be up, which is the
     * opposite of the intent and would take the tools down for real.</p>
     */
    @CompileStatic
    static Integer configuredHttpPort() {
        String profiles = (System.getProperty('spring.profiles.active') ?: '').toLowerCase()
        if (!profiles.contains('http')) return null
        String raw = System.getProperty('MCP_HTTP_PORT') ?:
                     System.getProperty('server.port') ?:
                     System.getenv('MCP_HTTP_PORT')
        if (!raw?.trim()) return null
        try {
            int p = Integer.parseInt(raw.trim())
            return (p > 0) ? Integer.valueOf(p) : null
        } catch (NumberFormatException ignored) {
            return null
        }
    }

    /**
     * True when something is already accepting connections on the port.
     *
     * <p>Deliberately a CONNECT test rather than a trial bind: a trial bind would briefly occupy
     * the port and make a competing instance's probe fail for the wrong reason, turning one race
     * into two.</p>
     */
    @CompileStatic
    static boolean isServed(int port) {
        Socket s = null
        try {
            s = new Socket()
            s.connect(new InetSocketAddress('127.0.0.1', port), 400)
            return true
        } catch (Exception ignored) {
            return false
        } finally {
            try { s?.close() } catch (Exception ignored2) { }
        }
    }

    /**
     * Walks the cause chain for Spring's PortInUseException and returns its port, or null.
     *
     * <p>Matched by class simple name rather than by import so this stays correct if the exception
     * moves package between Boot versions -- and the port is read reflectively for the same reason.
     * A cycle guard is present because a cause chain that loops would otherwise hang startup, which
     * would be a worse defect than the one being fixed.</p>
     */
    @CompileStatic
    static Integer portInUseFrom(Throwable t) {
        Set<Throwable> seen = new HashSet<>()
        Throwable cur = t
        while (cur != null && seen.add(cur)) {
            if (cur.class.simpleName == 'PortInUseException') {
                try {
                    return (Integer) cur.class.getMethod('getPort').invoke(cur)
                } catch (Exception ignored) {
                    return Integer.valueOf(-1)
                }
            }
            cur = cur.cause
        }
        return null
    }
}
