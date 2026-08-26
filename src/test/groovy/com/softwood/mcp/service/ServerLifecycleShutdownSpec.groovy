package com.softwood.mcp.service

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * ServerLifecycleShutdownSpec -- FS 0.9.13.
 *
 * <h3>The afternoon this cost</h3>
 * On 2026-08-26 every CS-to-AW local inference call failed silently for an entire session. The
 * cause was four lines in {@code stopAllOnShutdown}. From the FS log:
 *
 * <pre>
 *   16:51:21.912  started agentic-workflow on port 8084 (pid=16436)
 *   16:51:22.638  server_lifecycle: stopped agentic-workflow on shutdown
 * </pre>
 *
 * <p>The FS instance that started AW's HTTP companion shut down 0.7 seconds later and its
 * {@code @PreDestroy} killed the companion it owned. Claude Desktop starts two launcher+child
 * pairs of every server, so a short-lived FS instance takes down a companion that every other
 * live instance depends on. Nothing retries, because {@code autoStartHttpCompanions} is
 * {@code @PostConstruct} -- one shot at boot.</p>
 *
 * <h3>The category error</h3>
 * {@code ServerRegistry} knows two kinds of process: <em>owned</em> (we started it, kill it on
 * shutdown) and <em>adopted</em> (someone else started it, leave it alone). An HTTP companion on
 * a fixed port is neither. It is <b>shared infrastructure</b>: started by whichever instance won
 * the race, depended on by all of them, and expected to outlive any one of them.
 *
 * <p>CS's companion survived on :8082 purely because this FS instance found it already listening
 * and adopted it. AW's did not, because this instance started it. That asymmetry is ordering
 * luck, not design -- which is the definition of a race the code does not know it is running.</p>
 *
 * <p>The rest of the service already assumes companions outlive sessions:
 * {@code killStalePidIfPresent} pings a live port, <em>adopts</em> a healthy server and evicts
 * only an unresponsive one. Cleanup at next start is the design. {@code stopAllOnShutdown} was
 * the one place that disagreed.</p>
 *
 * <ul>
 *   <li>SL-1 -- shutdown does not destroy a companion</li>
 *   <li>SL-2 -- the companion stays in the registry, so an explicit stop can still reach it</li>
 *   <li>SL-3 -- runtime state still records it as running, so the next session adopts rather
 *       than re-spawns (practice #1485: assert on persisted state)</li>
 * </ul>
 */
class ServerLifecycleShutdownSpec extends Specification {

    @TempDir
    Path tempDir

    ServerRegistry registry
    ServerLifecycleService service
    Process companion

    static final String NAME = 'agentic-workflow'
    static final long   PID  = 4242L

    def setup() {
        registry = new ServerRegistry()

        service = new ServerLifecycleService(Stub(PathService))
        service.registry = registry
        service.claudeSyncPath = tempDir.toString().replace('\\', '/')

        // writeRuntimeState resolves jar/port per companion from the config, so the config has
        // to exist for the persisted assertion in SL-3 to mean anything.
        new File(tempDir.toFile(), 'mcp-http-servers.json').text = '''\
{
  "jarsDir": "C:/tmp/jars",
  "javaCmd": "java",
  "servers": [
    { "name": "agentic-workflow", "jar": "mcp-agentic-workflow-1.30.3.jar",
      "port": 8084, "startupPolicy": "eager", "autoHttpCompanion": true }
  ]
}'''

        companion = Mock(Process)
        companion.pid()     >> PID
        companion.isAlive() >> true

        // Exactly what startServer does once waitForPort confirms the bind.
        registry.register(NAME, 8084, companion)
    }

    // =========================================================================
    // SL-1  the line that cost the afternoon
    // =========================================================================

    def 'SL-1: a shared HTTP companion is not destroyed when its starting instance shuts down'() {
        when: 'this FS instance shuts down -- routinely, mid-session, as DT churns instances'
        service.stopAllOnShutdown()

        then: 'the companion every other instance depends on is left running'
        0 * companion.destroy()
        0 * companion.destroyForcibly()
    }

    // =========================================================================
    // SL-2  not killing it is only half the fix
    // =========================================================================

    def 'SL-2: the companion stays in the registry so an explicit stop can still reach it'() {
        given: 'it is reachable before shutdown'
        assert registry.ownedProcesses.containsKey(NAME)

        when:
        service.stopAllOnShutdown()

        then: 'clearing the registry would orphan it -- alive, and no longer stoppable by name'
        registry.ownedProcesses.containsKey(NAME)
        registry.ownedProcesses.get(NAME).is(companion)
    }

    // =========================================================================
    // SL-3  the persisted record the NEXT session reads
    // =========================================================================

    def 'SL-3: runtime state still records the companion, so the next start adopts it'() {
        when:
        service.stopAllOnShutdown()

        then: 'killStalePidIfPresent needs this entry to find and adopt the live companion'
        Map state = new ObjectMapper().readValue(
            new File(tempDir.toFile(), 'mcp-http-servers-runtime.json'), Map) as Map
        List managed = state.get('managedServers') as List
        Map entry = managed.find { (it as Map).get('name') == NAME } as Map

        entry != null
        (entry.get('pid') as Number).longValue() == PID
        (entry.get('port') as Number).intValue() == 8084
    }
}
