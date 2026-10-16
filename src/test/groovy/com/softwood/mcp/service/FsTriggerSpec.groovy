package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Title

/**
 * FS 0.9.100 -- optimised-learning WS2 on FS (design TD-4).
 *
 * <ul>
 *   <li>FT-1 -- SLEEP_REFUSED with an active trigger: one extra {"lesson":...} element, the refusal text untouched,
 *       the show reported with the session id</li>
 *   <li>FT-2 -- the same refusal again in the session: no lesson, no second report</li>
 *   <li>FT-3 -- fire_on=call on execute action=cmd rides on a cmd success only, not on bash</li>
 *   <li>FT-4 -- no session: the response comes back as it was</li>
 * </ul>
 */
@Title('FS triggered lessons')
class FsTriggerSpec extends Specification {

    static final String S = '2026-10-16-10-00'

    static class StubCache extends FsTriggerCache {
        List<Map<String, Object>> stubRows = []
        List<List> reports = Collections.synchronizedList([])
        @Override protected List<Map<String, Object>> fetch() { stubRows }
        @Override protected void report(Long triggerId, String sessionId) { reports << [triggerId, sessionId] }
    }

    StubCache cache = new StubCache()

    private static Map trig(long id, String action, String code, String fireOn) {
        [id: id, practice_id: 3556, server: 'fs', tool: 'execute', action: action, template: null, param: null,
         error_code: code, fire_on: fireOn,
         lesson: [id: 3556, trigger: id, title: 'FS execute on Windows', text: 'Wait with flow_management progress, never sleep.']]
    }

    private static List<Map> contents(McpResponse r) { ((r.result as Map).content as List<Map>) }

    private void awaitReports(int n) {
        long deadline = System.currentTimeMillis() + 3000
        while (cache.reports.size() < n && System.currentTimeMillis() < deadline) sleep(20)
    }

    def 'FT-1: SLEEP_REFUSED with an active trigger gains one lesson element and reports the show'() {
        given:
        cache.stubRows = [trig(11, null, 'SLEEP_REFUSED', 'refusal')]
        McpResponse refusal = McpResponse.toolError(1, 'SLEEP_REFUSED: the first statement is a wait')

        when:
        McpResponse out = cache.offer('execute', [action: 'cmd', script: 'timeout /t 30'], refusal, S)
        awaitReports(1)

        then:
        contents(out).size() == 2
        contents(out)[0].text == 'SLEEP_REFUSED: the first statement is a wait'
        (new JsonSlurper().parseText(contents(out)[1].text as String) as Map).lesson.trigger == 11
        cache.reports == [[11L, S]]
    }

    def 'FT-2: the same refusal again in the session -- no lesson, no second report'() {
        given:
        cache.stubRows = [trig(11, null, 'SLEEP_REFUSED', 'refusal')]
        McpResponse refusal = McpResponse.toolError(1, 'SLEEP_REFUSED: wait')

        when:
        cache.offer('execute', [action: 'cmd'], refusal, S)
        McpResponse second = cache.offer('execute', [action: 'cmd'], refusal, S)
        awaitReports(1)
        sleep(100)

        then:
        contents(second).size() == 1
        cache.reports.size() == 1
    }

    def 'FT-3: fire_on=call on execute action=cmd rides on a cmd success only'() {
        given:
        cache.stubRows = [trig(12, 'cmd', null, 'call')]
        McpResponse ok = McpResponse.success(1, [content: [[type: 'text', text: JsonOutput.toJson([success: true, exitCode: 0])]]])

        when:
        McpResponse bash = cache.offer('execute', [action: 'bash'], ok, S)
        McpResponse cmd  = cache.offer('execute', [action: 'cmd'], ok, S)

        then:
        contents(bash).size() == 1
        contents(cmd).size() == 2
    }

    def 'FT-4: no session -- the response comes back as it was'() {
        given:
        cache.stubRows = [trig(11, null, 'SLEEP_REFUSED', 'refusal')]
        McpResponse refusal = McpResponse.toolError(1, 'SLEEP_REFUSED: wait')

        expect:
        cache.offer('execute', [action: 'cmd'], refusal, null).is(refusal)
        cache.reports.isEmpty()
    }
}
