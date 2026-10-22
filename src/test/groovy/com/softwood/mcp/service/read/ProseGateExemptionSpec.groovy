package com.softwood.mcp.service.read

import com.softwood.mcp.service.ContextServerClient
import spock.lang.Specification
import spock.lang.Unroll

/**
 * R4 (decision 381, Will 2026-10-19; WS9 slice). ONTOLOGY-GATE refused reads of arc briefs and CHANGELOGs six times
 * in a week (10-12 x3, 10-15, 10-19 x2), and every one was answered by passing allowNoLocate or locating and reading
 * the same lines: a refusal that changes nothing is a retry tax, and it taught the override reflex. FS 0.9.26 gated
 * prose "on the same terms" as code; this carves out exactly the two prose kinds that recurred, and nothing else.
 *
 * PG-1  a .md under claude-sync/briefs is not gated (RED on 0.9.104)
 * PG-2  a repo CHANGELOG.md is not gated (RED on 0.9.104)
 * PG-3  CONTROL: a .groovy source file is still gated
 * PG-4  CONTROL: an indexed doc that is neither (CLAUDE.md, an asciidoc chapter) is still gated
 *
 * productive-learning WP1 1a (Will, 2026-10-22): config and data files are not gated either. Since R4 the gate's only
 * non-code refusal was claude_desktop_config.json (obs 13190/13191, a config: node): locate answers "where in the file"
 * for code and long prose, but a config or data file has no such place to find. CLAUDE.md and asciidoc stay gated (PG-4).
 *
 * PG-5  a config or data file (.json .yml .yaml .properties .toml .xml .csv .txt .html) is not gated (RED on 0.9.114)
 */
class ProseGateExemptionSpec extends Specification {

    ReadResponseHelper  helper
    ContextServerClient csMock = Mock(ContextServerClient)

    def setup() {
        helper = new ReadResponseHelper()
        helper.contextServerClient    = csMock
        helper.locateEvidenceRegistry = new LocateEvidenceRegistry()
        helper.ontologyGateEnforced   = true
        csMock.isCsReachable() >> true
        csMock.ontologyGateCheck(_, _) >> ([allow: false, locate_query: 'doc:x'] as Map<String, Object>)
    }

    @Unroll
    def 'PG-1/2: #path is not gated'() {
        expect:
        helper.ontologyGateEntry(path, [:] as Map<String, Object>, 'read') == null

        where:
        path << ['C:/Users/willw/claude-sync/briefs/optimised-learning/ARC-STATE.md',
                 'C:\\Users\\willw\\claude-sync\\briefs\\optimised-learning\\NEXT-SESSION-2026-10-17-optimised-learning.md',
                 'C:/Users/willw/IdeaProjects/mcp-groovy-context-server/CHANGELOG.md']
    }

    @Unroll
    def 'PG-5 (RED on 0.9.114): config/data #path is not gated'() {
        expect:
        helper.ontologyGateEntry(path, [:] as Map<String, Object>, 'read') == null

        where:
        path << ['C:/Users/willw/AppData/Roaming/Claude/claude_desktop_config.json',
                 'C:\\Users\\willw\\claude-sync\\mcp-http-servers.json',
                 'C:/Users/willw/IdeaProjects/mcp-agentic-workflow/src/main/resources/application.yml',
                 'C:/Users/willw/IdeaProjects/mcp-agentic-workflow/src/main/resources/application-stdio.YAML',
                 'C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/gradle.properties',
                 'C:/Users/willw/IdeaProjects/x/build/test-results/test/TEST-FooSpec.xml',
                 'C:/Users/willw/claude-sync/briefs/x/census/spec_census.csv',
                 'C:/Users/willw/IdeaProjects/x/notes.txt',
                 'C:/Users/willw/IdeaProjects/x/build/reports/tests/test/index.html',
                 'C:/Users/willw/IdeaProjects/x/config.toml']
    }

    @Unroll
    def 'PG-3/4: CONTROL -- #path is still gated'() {
        expect:
        helper.ontologyGateEntry(path, [:] as Map<String, Object>, 'read')?.error == 'BLOCKED_ONTOLOGY_GATE'

        where:
        path << ['C:/Users/willw/IdeaProjects/mcp-groovy-context-server/src/main/groovy/Foo.groovy',
                 'C:/Users/willw/IdeaProjects/mcp-groovy-context-server/CLAUDE.md',
                 'C:/Users/willw/IdeaProjects/mcp-agentic-workflow/docs/asciidoc/chapters/06-platform-flows.adoc']
    }
}
