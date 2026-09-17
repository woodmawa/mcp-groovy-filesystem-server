package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * FS 0.9.37 C1 -- PLAN-GATE at the two mutating entry points FS owns: file_write (every action that
 * writes) and execute when the script runs gradlew or git. CS decides (it owns the corpus and the
 * per-session record); this class only asks, and turns a refusal into a tool error that names the
 * practices and says a retry passes.
 *
 * Every uncertain path passes: no CS, no claimed session, a timeout, a malformed answer. A gate that
 * blocks on its own dependency is the W17 shape. Those passes are counted as 'unavailable' here,
 * because CS cannot record its own absence.
 *
 * Pinned by PlanGateGuardSpec.
 */
@Service
@Slf4j
@CompileStatic
class PlanGateGuard {

    @Autowired(required = false) ContextServerClient contextServerClient
    @Autowired(required = false) FilesystemTelemetryService telemetryService

    @Value('${mcp.filesystem.plan-gate.enforced:true}')
    boolean enforced = true

    static final Pattern BUILD_OR_GIT = Pattern.compile('(?i)(\\bgradlew\\b|\\bgit\\s)')
    static final Set<String> NON_MUTATING_WRITE_ACTIONS = (['abort_write', 'chunk_status'] as Set<String>).asImmutable()

    final AtomicInteger unavailable = new AtomicInteger()

    /** @return a refusal message, or null to proceed. */
    String checkWrite(String action, String path) {
        if (!enforced || !path || NON_MUTATING_WRITE_ACTIONS.contains(action)) return null
        return ask([tool: 'file_write', path: path] as Map<String, Object>)
    }

    /** @return a refusal message, or null to proceed. */
    String checkExecute(String script, String workingDir) {
        if (!enforced || !script || !BUILD_OR_GIT.matcher(script).find()) return null
        return ask([tool: 'execute', workingDir: workingDir, script: script.take(200)] as Map<String, Object>)
    }

    protected String ask(Map<String, Object> args) {
        try {
            String sid = null
            try { sid = telemetryService?.readActiveSessionId() } catch (Exception ignored) { }
            Map<String, Object> answer = (contextServerClient != null && sid) ?
                contextServerClient.planGateCheck(args, sid) : null
            if (answer == null) {
                unavailable.incrementAndGet()
                return null
            }
            if (answer.get('allow') != false) return null
            return refusal(answer)
        } catch (Exception e) {
            unavailable.incrementAndGet()
            log.debug('plan-gate ask failed (pass-through): {}', e.message)
            return null
        }
    }

    static String refusal(Map<String, Object> answer) {
        StringBuilder sb = new StringBuilder()
        sb.append("PLAN-GATE: first mutating call on '").append(answer.get('component'))
          .append("' this session. The corpus already holds this for it:\n")
        ((answer.get('practices') ?: []) as List).each { Object o ->
            Map p = o as Map
            sb.append('  #').append(p.get('id')).append(' [').append(p.get('valence') ?: '').append('] ')
              .append(p.get('title')).append(' -- ').append(p.get('summary') ?: '').append('\n')
        }
        sb.append((answer.get('retry') ?: 'Read these, then repeat the same call unchanged -- a retry is never refused.') as String)
        return sb.toString()
    }
}