package com.softwood.mcp

import groovy.transform.CompileDynamic
import spock.lang.Specification

/**
 * CompanionRaceExitSpec — FS 0.9.18.
 *
 * <p>Mirror of the CS spec of the same name. Covers the quiet-exit path added for the companion
 * start race in BUILD-BRIEF-2026-09-08-the-companions-that-race:
 * {@code ServerLifecycleService.autoStartHttpCompanions} checks {@code isPortListening()} and then
 * spawns a JVM that takes ~24 seconds to bind, so two of the four stdio instances that boot within
 * four seconds of a desktop restart can both pass the check. The loser aborted with
 * {@code APPLICATION FAILED TO START}, in the log every instance shares.</p>
 *
 * <h3>FRE-1 is the one that matters, and it matters more here than in CS.</h3>
 * <p>The dangerous failure mode is this fix, not the defect. If {@code configuredHttpPort()} ever
 * returns a port for a <b>stdio</b> instance, every stdio FS instance exits the moment a companion
 * is listening — and FS stdio serves every file read, write and shell command there is. FRE-1
 * asserts the profile guard holds with {@code MCP_HTTP_PORT} explicitly set, which is precisely the
 * state a stdio instance is in while a companion runs on the same machine.</p>
 *
 * <ul>
 *   <li>FRE-1  a stdio instance resolves NO port, even with MCP_HTTP_PORT set.</li>
 *   <li>FRE-2  an http companion resolves the port it was launched with.</li>
 *   <li>FRE-3  no profile, or http with no usable port, resolves nothing.</li>
 *   <li>FRE-4  a nested PortInUseException is found and its port returned.</li>
 *   <li>FRE-5  an unrelated failure returns null, so a real startup failure still throws.</li>
 *   <li>FRE-6  a cyclic cause chain terminates rather than hanging startup.</li>
 *   <li>FRE-7  isServed distinguishes a bound port from a free one.</li>
 * </ul>
 */
@CompileDynamic
class CompanionRaceExitSpec extends Specification {

    private List<String> setProps = []

    private void prop(String k, String v) {
        setProps << k
        if (v == null) { System.clearProperty(k) } else { System.setProperty(k, v) }
    }

    def cleanup() {
        setProps.each { System.clearProperty(it) }
        setProps.clear()
    }

    def 'FRE-1: a stdio instance resolves no port even when MCP_HTTP_PORT is set'() {
        given: 'a stdio instance on a machine where the FS companion is also configured'
        prop('spring.profiles.active', 'stdio')
        prop('MCP_HTTP_PORT', '8081')

        expect: 'it binds nothing, so it must never take the quiet-exit path'
        McpGroovyFileSystemServerApplication.configuredHttpPort() == null
    }

    def 'FRE-2: an http companion resolves the port it was launched with'() {
        given:
        prop('spring.profiles.active', 'http')
        prop('MCP_HTTP_PORT', '8081')

        expect:
        McpGroovyFileSystemServerApplication.configuredHttpPort() == 8081
    }

    def 'FRE-3: no profile, or http without a usable port, resolves nothing'() {
        given:
        prop('spring.profiles.active', profile)
        prop('MCP_HTTP_PORT', port)
        prop('server.port', null)

        expect:
        McpGroovyFileSystemServerApplication.configuredHttpPort() == null

        where:
        profile | port
        null    | '8081'
        'stdio' | null
        'http'  | null
        'http'  | 'not-a-num'
        'http'  | '0'
    }

    def 'FRE-4: a nested PortInUseException is found and its port returned'() {
        given: 'the shape Spring actually throws — wrapped, not top level'
        def cause = new PortInUseException(8081)
        def wrapped = new RuntimeException('Failed to start bean webServerStartStop',
                          new IllegalStateException('context init failed', cause))

        expect:
        McpGroovyFileSystemServerApplication.portInUseFrom(wrapped) == 8081
    }

    def 'FRE-5: an unrelated failure returns null so a real startup failure still throws'() {
        given: 'the half that matters — this must NOT become a blanket catch'
        def boom = new RuntimeException('allowed-directories misconfigured',
                       new IllegalStateException('no such path'))

        expect:
        McpGroovyFileSystemServerApplication.portInUseFrom(boom) == null
    }

    def 'FRE-6: a cyclic cause chain terminates instead of hanging startup'() {
        given:
        def a = new RuntimeException('a')
        def b = new RuntimeException('b', a)
        a.initCause(b)

        when:
        Integer result = McpGroovyFileSystemServerApplication.portInUseFrom(b)

        then:
        result == null
    }

    def 'FRE-7: isServed is true for a bound port and false for a free one'() {
        given:
        ServerSocket held = new ServerSocket(0)
        int boundPort = held.localPort
        ServerSocket probe = new ServerSocket(0)
        int freePort = probe.localPort
        probe.close()

        expect:
        McpGroovyFileSystemServerApplication.isServed(boundPort)
        !McpGroovyFileSystemServerApplication.isServed(freePort)

        cleanup:
        held.close()
    }

    /** Stands in for Spring's exception, matched by simple name exactly as production does. */
    static class PortInUseException extends RuntimeException {
        private final int port
        PortInUseException(int port) { super("Port ${port} is already in use"); this.port = port }
        int getPort() { return port }
    }
}
