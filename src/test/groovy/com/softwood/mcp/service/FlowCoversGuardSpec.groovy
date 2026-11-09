package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Title

/**
 * FS 0.9.128 -- brief 2026-11-07 item 2: a chat's hand-run command that an approved flow fully covers is refused
 * FLOW_COVERS, naming the flow and the start call with its params filled. 2026-11-07 ran packageMcpbThin through
 * raw execute twice and re-checked a push git-release had already VERIFIED.
 *
 * <ul>
 *   <li>Only a chat is refused: the same command from a flow node (X-Aw-Node, AwCallContext) is the flow itself.</li>
 *   <li>options.flowAck="&lt;why&gt;" runs it and is counted, like planAck.</li>
 *   <li>CS down or no covers: pass. A guard that blocks on its own dependency is the W17 shape.</li>
 * </ul>
 */
@Title('FS 0.9.128 -- FLOW_COVERS: a covered command from a chat names the flow')
class FlowCoversGuardSpec extends Specification {

    static final String DIR = 'C:/Users/willw/IdeaProjects/mcp-groovy-context-server'

    static final List<Map<String, Object>> COVERED = [
        [flow: 'git-release', version: '1.11', covers: ['^git (commit|tag|push)\\b'],
         line: 'git-release:1.11  (message*,repoDir*,tag,version,paths,untagged)  Commit, tag and push a release.'],
        [flow: 'package-mcpb', version: '1.0', covers: ['(?i)\\bgradlew(\\.bat)?\\b.*\\bpackageMcpb\\w*'],
         line: 'package-mcpb:1.0  (repoDir*,task)  Build a server MCPB bundle.']
    ] as List<Map<String, Object>>

    /** AW is asked (catalogue rows=true) -- AW owns its tables; neither FS nor CS reads them (decision 311). */
    static class StubClient extends AwJobEventClient {
        List<Map<String, Object>> answer
        int asks = 0
        @Override List<Map<String, Object>> flowCovers() { asks++; return answer }
    }

    StubClient client = new StubClient(answer: COVERED)
    FlowCoversGuard guard = new FlowCoversGuard(awClient: client)

    def cleanup() { AwCallContext.clear() }

    def 'FC-1: a chat git push is refused FLOW_COVERS, naming git-release and its start call with repoDir filled'() {
        when:
        String r = guard.check('git push origin HEAD --follow-tags', DIR, null)

        then:
        r.startsWith('FLOW_COVERS -- ')
        r.contains('git-release:1.11')
        r.contains('templateName=git-release')
        r.contains('repoDir:"' + DIR + '"')
        r.contains('message:"<')
        r.contains('options.flowAck')
    }

    def 'FC-2: packageMcpbThin through gradlew is refused, naming package-mcpb'() {
        expect:
        guard.check('.\\gradlew.bat packageMcpbThin', DIR, null).contains('templateName=package-mcpb')
    }

    def 'FC-3: the same command from a flow node is not refused -- it IS the flow'() {
        given:
        AwCallContext.set('run-1', 'git-push')

        expect:
        guard.check('git push origin HEAD --follow-tags', DIR, null) == null
    }

    def 'FC-4: flowAck runs it and is counted'() {
        when:
        String r = guard.check('git push origin HEAD', DIR, 'repo has no stamp registry yet')

        then:
        r == null
        guard.acked.get() == 1
        guard.refused.get() == 0
    }

    def 'FC-5: a command no flow covers, a read-only git command, and no git or gradle at all all pass'() {
        expect:
        guard.check('git status --porcelain', DIR, null) == null
        guard.check('.\\gradlew.bat test --tests *Spec', DIR, null) == null
        guard.check('dir /b', DIR, null) == null
    }

    def 'FC-6: AW unreachable (no answer) passes, and the covers are asked once a minute, not per call'() {
        given:
        StubClient down = new StubClient(answer: null)
        FlowCoversGuard g = new FlowCoversGuard(awClient: down)

        when:
        String a = g.check('git push origin HEAD', DIR, null)
        guard.check('git push origin HEAD', DIR, null)
        guard.check('git tag v1', DIR, null)

        then:
        a == null
        client.asks == 1
        guard.refused.get() == 2
    }

    def 'FC-7: a command two flows cover names both, each with its own start call -- not only the first match'() {
        given: 'the live catalogue order: git-commit (commit|push) before git-release (commit|tag|push)'
        StubClient two = new StubClient(answer: [
            [flow: 'git-commit', version: '1.8', covers: ['^git (commit|push)\\b'],
             line: 'git-commit:1.8  (message*,projectDir*,push,paths)  nodes: git-add > git-commit'],
        ] + COVERED)
        FlowCoversGuard g = new FlowCoversGuard(awClient: two)

        when:
        String r = g.check('git push origin HEAD --follow-tags', DIR, null)

        then:
        r.startsWith('FLOW_COVERS -- ')
        r.contains('git-commit:1.8')
        r.contains('git-release:1.11')
        r.contains('templateName=git-commit params={message:"<message>", projectDir:"' + DIR + '"}')
        r.contains('templateName=git-release params={message:"<message>", repoDir:"' + DIR + '"}')
        !r.contains('package-mcpb')
        g.refused.get() == 1
    }

    def 'FC-7b: at most three flows are named'() {
        given:
        List<Map<String, Object>> many = (1..5).collect { int i ->
            [flow: 'f' + i, version: '1.0', covers: ['^git push\\b'], line: 'f' + i + ':1.0  (repoDir*)  x'] as Map<String, Object>
        }
        FlowCoversGuard g = new FlowCoversGuard(awClient: new StubClient(answer: many))

        when:
        String r = g.check('git push origin HEAD', DIR, null)

        then:
        r.contains('templateName=f1') && r.contains('templateName=f2') && r.contains('templateName=f3')
        !r.contains('templateName=f4')
    }
}
