package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.regex.Pattern

/**
 * FS 0.9.100 -- optimised-learning WS2 on FS: a lesson rides on the FS call it belongs to.
 *
 * <p>CS owns {@code practice_triggers} and the ledger; FS fetches its rows ({@code context_lifecycle
 * action=trigger_rows server=fs}) and matches every tool call locally. CS is never a dependency of an FS call (#377):
 * only the first load runs inline, with short timeouts; after that a stale cache is refreshed on the daemon thread and
 * the old rows serve. On a match not yet shown to this session, the response gains ONE extra content element
 * {@code {"lesson":{...}}} (the handler's payload is not touched) and the show is reported to CS
 * ({@code record_trigger_show}) on the daemon thread. Same matching rule as CS TriggerService.firstMatch.
 * Pinned by FsTriggerSpec.</p>
 */
@Service
@Slf4j
@CompileDynamic
class FsTriggerCache {

    static final long TTL_MS = 600_000L

    @Autowired(required = false)
    ContextServerClient contextServerClient

    private volatile List<Map<String, Object>> rows
    private volatile long loadedAt = 0L
    private volatile boolean refreshing = false
    private final Map<String, Set<Long>> shown = new ConcurrentHashMap<String, Set<Long>>()
    private final Map<String, Pattern> globs = new ConcurrentHashMap<String, Pattern>()

    private final ExecutorService worker = Executors.newSingleThreadExecutor({ Runnable r ->
        Thread t = new Thread(r, 'fs-trigger-show')
        t.daemon = true
        return t
    } as ThreadFactory)

    /** Overridden in specs. */
    protected List<Map<String, Object>> fetch() {
        Map r = contextServerClient?.lifecycleCall([action: 'trigger_rows', server: 'fs'] as Map<String, Object>, 1500)
        return (r?.triggers ?: []) as List<Map<String, Object>>
    }

    /** Overridden in specs. */
    protected void report(Long triggerId, String sessionId) {
        contextServerClient?.lifecycleCall([action: 'record_trigger_show', triggerId: triggerId, sessionId: sessionId] as Map<String, Object>, 3000)
    }

    void refresh() { rows = null }

    List<Map<String, Object>> rows() {
        long now = System.currentTimeMillis()
        if (rows == null) {
            load(now)
        } else if (now - loadedAt > TTL_MS && !refreshing) {
            refreshing = true
            worker.submit({ try { load(System.currentTimeMillis()) } finally { refreshing = false } } as Runnable)
        }
        return rows ?: []
    }

    private void load(long now) {
        try { rows = fetch() ?: [] } catch (Exception e) { rows = []; log.debug('FsTriggerCache: fetch failed: {}', e.message) }
        loadedAt = now
    }

    Map<String, Object> firstMatch(List<Map<String, Object>> triggers, String tool, String action, Set<String> sentKeys,
                                   boolean refusal, String code, String text) {
        for (Map<String, Object> t : (triggers ?: [])) {
            if (t.server != 'fs' || t.tool != tool) continue
            if (t.action && t.action != action) continue
            if (t.param && !(sentKeys?.contains(t.param as String))) continue
            if (t.fire_on == 'refusal' && !refusal) continue
            if (t.fire_on == 'call' && refusal) continue
            String g = t.error_code as String
            if (g && !(glob(g, code) || glob(g, text))) continue
            return t
        }
        return null
    }

    boolean glob(String g, String value) {
        if (!g || value == null) return false
        Pattern p = globs.computeIfAbsent(g) { String s ->
            Pattern.compile(s.split('\\*', -1).collect { Pattern.quote(it) }.join('.*'), Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
        }
        return p.matcher(value).matches()
    }

    /** The response, plus a lesson element when a trigger matches and is new to this session. Never throws. */
    McpResponse offer(String tool, Map<String, Object> args, McpResponse response, String sessionId) {
        try {
            if (!sessionId || !(response?.result instanceof Map)) return response
            Map result = response.result as Map
            boolean refusal = result.isError == true || response.error != null
            String text = ((result.content as List)?.getAt(0) as Map)?.text as String
            String code = com.softwood.mcp.controller.McpController.errorCodeOf(response)
            Map<String, Object> t = firstMatch(rows(), tool, args?.action as String,
                (args?.keySet() ?: []) as Set<String>, refusal, code, text)
            if (t == null) return response
            Long id = (t.id as Number).longValue()
            if (!shown.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(id)) return response
            worker.submit({ try { report(id, sessionId) } catch (Exception e) { log.debug('FsTriggerCache: report failed: {}', e.message) } } as Runnable)
            Object content = result.content
            if (!(content instanceof List)) return response
            List extended = new ArrayList((List) content)
            extended.add([type: 'text', text: JsonOutput.toJson([lesson: t.lesson])] as Map<String, Object>)
            Map<String, Object> copied = new LinkedHashMap<String, Object>(result)
            copied.put('content', extended)
            return McpResponse.success(response.id, copied)
        } catch (Exception e) {
            log.debug('FsTriggerCache.offer: skipped (non-fatal): {}', e.message)
            return response
        }
    }
}
