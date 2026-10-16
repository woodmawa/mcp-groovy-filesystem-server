package com.softwood.mcp.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Title

/**
 * X58 (decision 378, FS 0.9.101, chain dd40c060) -- an FS test run must never touch the LIVE claude-sync.
 *
 * <p>Found 2026-10-16 by the WS10-C live-port tripwire: the test context never set MCP_CONTEXT_STORAGE_PATH, so
 * ServerLifecycleService took DERIVE = user.home/claude-sync, read the real mcp-http-servers.json, auto-started a
 * real agentic-workflow companion from the INSTALLED extension jar at context boot (pid 63324, lost 8084 to the live
 * companion and exited) and rewrote the live mcp-http-servers-runtime.json with the test worker as its stdioPid.
 * Every FS test run had done it.</p>
 *
 * <p>Asserted on the value Spring injected into the bean every spec shares, as TestsNeverReachLiveCsSpec does --
 * not on the YAML text.</p>
 */
@SpringBootTest
@ActiveProfiles('test')
@Title('FS tests never reach the live claude-sync -- TNS-1..3')
class TestsNeverReachLiveClaudeSyncSpec extends Specification {

    @Autowired ServerLifecycleService lifecycle
    @Autowired ServerRegistry registry

    static String norm(String p) { new File(p).canonicalPath.replace('\\', '/').toLowerCase() }

    def 'TNS-1: the test context resolves claude-sync to the build fixture, not the real folder'() {
        when:
        String resolved = norm(lifecycle.claudeSyncPath)
        String live = norm(System.getProperty('user.home') + '/claude-sync')

        then:
        resolved != live
        resolved.endsWith('/build/resources/test/test-claude-sync')
    }

    def 'TNS-2: the companion config it reads is the fixture -- one lazy entry, nothing auto-started'() {
        expect:
        new File(lifecycle.claudeSyncPath, 'mcp-http-servers.json').text.contains('fixture-only')
    }

    def 'TNS-3: booting the test context started no companion process'() {
        expect:
        registry.ownedProcesses.isEmpty()
    }
}
