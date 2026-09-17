package com.softwood.mcp.service

import spock.lang.Specification

/**
 * FS 0.9.40 (chain f33d662f) -- `tools action=git|gradle` is gated like `execute`.
 *
 * PlanGateGuard was called only from ExecuteService and FileWriteService, so the path that
 * skills/SKILL.md recommends for builds -- tools action=gradle -- never asked CS. The tools
 * call is rendered as the command line it runs and handed to the same guard, so the read-only,
 * listing and directory rules are the execute rules, not a second copy of them.
 *
 * TPG-1  a writing git subcommand is asked about, as kind git, in the tool's working dir
 * TPG-2  read-only and listing git subcommands are not asked about
 * TPG-3  gradle tasks are asked about as kind gradle; task-less housekeeping is not
 * TPG-4  a refusal is returned as the message; mvn/npm/stats are never asked
 * TPG-5  intent is passed through
 */
class ToolsServicePlanGateSpec extends Specification {

    ContextServerClient client = Mock()
    FilesystemTelemetryService telemetry = Mock()
    PlanGateGuard guard = new PlanGateGuard(contextServerClient: client, telemetryService: telemetry)
    ToolsService tools = new ToolsService(Mock(PathService))

    def setup() {
        telemetry.readActiveSessionId() >> 'sid-1'
        tools.planGateGuard = guard
    }

    def 'TPG-1: git commit is asked about in the working dir'() {
        when:
        String r = tools.planGateRefusal('git', 'commit', ['-a'], 'C:/repo', null)
        then:
        1 * client.planGateCheck({ it.kind == 'git' && it.workingDir == 'C:/repo' && it.command.startsWith('git commit') }, 'sid-1') >> [allow: true]
        r == null
    }

    def 'TPG-2: git status, log and tag listing are not asked about'() {
        when:
        tools.planGateRefusal('git', 'status', [], 'C:/repo', null)
        tools.planGateRefusal('git', 'log', ['-1'], 'C:/repo', null)
        tools.planGateRefusal('git', 'tag', ['-l'], 'C:/repo', null)
        then:
        0 * client.planGateCheck(_, _)
    }

    def 'TPG-3: gradle tasks are asked about; tasks alone is housekeeping'() {
        when:
        tools.planGateRefusal('gradle', 'packageMcpbThin installMcpbLocal', [], 'C:/repo', null)
        tools.planGateRefusal('gradle', 'tasks', [], 'C:/other', null)
        then:
        1 * client.planGateCheck({ it.kind == 'gradle' && it.workingDir == 'C:/repo' && it.command.contains('installMcpbLocal') }, 'sid-1') >> [allow: true]
        0 * client.planGateCheck({ it.workingDir == 'C:/other' }, _)
    }

    def 'TPG-4: a refusal comes back as the message; other toolchains are never asked'() {
        when:
        String r = tools.planGateRefusal('git', 'push', [], 'C:/repo', null)
        String m = tools.planGateRefusal('mvn', 'install', [], 'C:/repo', null)
        String s = tools.planGateRefusal('stats', '', [], 'C:/repo', null)
        then:
        1 * client.planGateCheck(_, 'sid-1') >> [allow: false, component: 'repo:git', practices: []]
        r.startsWith("PLAN-GATE: first mutating call on 'repo:git'")
        m == null
        s == null
    }

    def 'TPG-5: intent is passed through'() {
        when:
        tools.planGateRefusal('gradle', 'build', [], 'C:/repo', 'build the release')
        then:
        1 * client.planGateCheck({ it.intent == 'build the release' }, 'sid-1') >> [allow: true]
    }
}
