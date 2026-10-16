package com.softwood.mcp.service

import com.softwood.mcp.support.TestPorts
import spock.lang.Specification

/**
 * ServerLifecycleKillByPortSpec -- FS 0.9.71.
 *
 * <p>2026-10-07 15:21:18: {@code server_lifecycle stop force=true} on the AW companion (:8084)
 * destroyed the companion AND this chat's CS stdio JVM, which merely held a CLIENT connection to
 * 8084. killByPort took every netstat line containing {@code :8084 } that was LISTENING or
 * ESTABLISHED, without asking which column the port was in. Desktop showed
 * "mcp-groovy-context-server: Server disconnected".</p>
 *
 * <ul>
 *   <li>KP-1 -- only a LISTENING row whose LOCAL address is the port is a target</li>
 *   <li>KP-2 -- a stdio JVM is refused even when it is listening</li>
 *   <li>KP-3 -- this process is never a target</li>
 * </ul>
 */
class ServerLifecycleKillByPortSpec extends Specification {

    static final long LISTENER   = 59956L   // the AW companion
    static final long CLIENT     = 45296L   // CS stdio JVM, a client of :8084
    static final long OTHER_SRV  = 33333L   // a server on :8082 whose FOREIGN address is :8084
    static final long PREFIX     = 77777L   // listens on :80840 -- must not match :8084

    /**
     * X58: the shape of the 2026-10-07 netstat, on a DYNAMIC dead port P instead of the live AW port 8084 --
     * killByPort's post-kill socket probe reached the live companion (LivePortTripwire, socket://localhost:8084).
     * S is the other server whose FOREIGN address is P; P0 the prefix trap (":P" must not match ":P0").
     */
    static String netstat(int p, int s) {
        """
Active Connections

  Proto  Local Address          Foreign Address        State           PID
  TCP    0.0.0.0:${p}           0.0.0.0:0              LISTENING       59956
  TCP    127.0.0.1:${p}         127.0.0.1:51234        ESTABLISHED     59956
  TCP    127.0.0.1:51234        127.0.0.1:${p}         ESTABLISHED     45296
  TCP    127.0.0.1:${s}         127.0.0.1:${p}         ESTABLISHED     33333
  TCP    0.0.0.0:${p}0          0.0.0.0:0              LISTENING       77777
  TCP    [::]:${p}              [::]:0                 LISTENING       59956
  UDP    0.0.0.0:${p}           *:*                                    11111
""".toString()
    }

    int port

    ServerLifecycleService service
    List<Long> destroyed = []
    Map<Long, String> commandLines = [:]

    def setup() {
        port = TestPorts.deadPort()
        String table = netstat(port, TestPorts.deadPort())
        service = new ServerLifecycleService(Stub(PathService))
        service.netstatRunner = { -> table }
        service.commandLineOf = { Long pid -> commandLines.get(pid) ?: 'java -jar mcp-agentic-workflow.jar' }
        service.pidDestroyer  = { Long pid -> destroyed << pid; true }
        service.ownPid = 1L
    }

    def 'KP-1: only the listener on the port is destroyed -- never a client of it, never a server talking to it'() {
        when:
        service.killByPort(port)

        then:
        destroyed == [LISTENER]
    }

    def 'KP-2: a listening stdio JVM is refused'() {
        given:
        commandLines.put(LISTENER, 'java -Dspring.profiles.active=stdio -jar mcp-agentic-workflow.jar')

        when:
        service.killByPort(port)

        then:
        destroyed.isEmpty()
    }

    def 'KP-3: this process is never a target, even when it holds the port'() {
        given:
        service.ownPid = LISTENER

        when:
        service.killByPort(port)

        then:
        destroyed.isEmpty()
    }
}
