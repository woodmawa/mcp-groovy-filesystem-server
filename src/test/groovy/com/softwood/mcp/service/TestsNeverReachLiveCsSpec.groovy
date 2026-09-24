package com.softwood.mcp.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * FS 0.9.61 (value-review-phase2) -- an FS test run must never reach the LIVE context server.
 *
 * <p>Measured 2026-09-24: 20,247 of the 22,504 rows in the live CS {@code file_hash_registry} (90%) were
 * paths inside Spock {@code @TempDir}s, 5,008 of them in the last 7 days. The FS test Spring context
 * never set {@code mcp.context-server.url}, so the real {@link ContextServerClient} bean took its
 * {@code @Value} default -- {@code http://localhost:8082}, the live CS HTTP companion -- and every
 * {@code @SpringBootTest} that read a file upserted it into the live store, and asked the live gate,
 * locate and range-cache endpoints. 23 of the 31 {@code @SpringBootTest} specs use the real bean.
 * The earlier structure / directory-listing writes (160 rows in project_group_practices, 9 from temp
 * dirs) were the same leak through a path FS 0.9.54 has since removed.</p>
 *
 * <p>Asserted on the value Spring actually injected into the bean every spec shares -- not on the YAML
 * text, which a comment or a second profile could contradict.</p>
 *
 * @since FS 0.9.61
 */
@SpringBootTest
@ActiveProfiles('test')
class TestsNeverReachLiveCsSpec extends Specification {

    /** The live CS HTTP companion -- see mcp-http-servers.json. */
    static final int LIVE_CS_PORT = 8082

    @Autowired ContextServerClient contextServerClient

    def 'TNL-1: the test context points ContextServerClient at a dead sink, never the live CS companion'() {
        when:
        URI target = new URI(contextServerClient.contextServerUrl)

        then: 'not the live companion'
        target.port != LIVE_CS_PORT

        and: 'a port nothing listens on, so every call fails fast and fail-open'
        target.host == '127.0.0.1'
        target.port == 1
    }
}
