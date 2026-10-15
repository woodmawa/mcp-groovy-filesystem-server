package com.softwood.mcp.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * FS 0.9.97 (HANDOFF-2026-10-15 D1, chain cd627a8a) -- an FS test run must never reach the LIVE AW companion.
 *
 * <p>Measured 2026-10-14 16:35Z (run f19c01a1): ExecuteService specs in the gradle test JVM posted
 * {@code record_job_event} to the real AW companion on :8084 -- flow_id {@code fsjob:*}, sessionId null, commands
 * pinging 127.0.0.1, one 'timeout' -- because the test context never set {@code mcp.agentic-workflow.url} and
 * {@link AwJobEventClient} took its {@code @Value} default. Same leak, same remedy, as FS 0.9.61 did for CS
 * ({@link TestsNeverReachLiveCsSpec}). (The four llm-classify runs seen at the same moment were NOT the tests:
 * they were the live AW classifying two chains the chat had just opened -- obs 12751.)</p>
 *
 * <p>Asserted on the value Spring actually injected into the bean, not on the YAML text.</p>
 *
 * @since FS 0.9.97
 */
@SpringBootTest
@ActiveProfiles('test')
class TestsNeverReachLiveAwSpec extends Specification {

    /** The live AW HTTP companion -- see mcp-http-servers.json. */
    static final int LIVE_AW_PORT = 8084

    @Autowired AwJobEventClient awJobEventClient

    def 'TNA-1: the test context points AwJobEventClient at a dead sink, never the live AW companion'() {
        when:
        URI target = new URI(awJobEventClient.awUrl)

        then: 'not the live companion'
        target.port != LIVE_AW_PORT

        and: 'a port nothing listens on, so every event fails fast and fail-open'
        target.host == '127.0.0.1'
        target.port == 1
    }
}
