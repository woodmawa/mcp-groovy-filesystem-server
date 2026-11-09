package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * FS 0.9.128 -- brief 2026-11-07 item 2: guard only where a flow fully covers the command, and name the flow.
 *
 * <p>Approved AW templates declare {@code covers}: the command shapes they replace (git-release:
 * {@code ^git (commit|tag|push)\b}). FS gets them by ASKING AW (flow_management catalogue rows=true) -- AW owns its
 * tables (decision 311), so neither FS nor CS reads them. A CHAT's
 * execute or tools call whose git/gradle statement -- found by PLAN-GATE's own classifier,
 * {@link PlanGateGuard#findGatedCommands} -- matches one is refused FLOW_COVERS with the flow_management start call,
 * its directory param filled. The same command from a flow node (X-Aw-Node, {@link AwCallContext}) is the flow
 * doing its job and passes. {@code options.flowAck="<why>"} runs it anyway and is counted.</p>
 *
 * <p>Fails open: no AW companion, no answer, a bad regex -- pass. The covers are asked at most once a minute.</p>
 */
@Service
@Slf4j
@CompileStatic
class FlowCoversGuard {

    static final String FLOW_ACK_DESCRIPTION =
        'FS 0.9.128: why this covered command must run by hand rather than through the flow FLOW_COVERS named ' +
        '(e.g. "the flow cannot pass --force"). Runs the command and is recorded.'

    static final long TTL_MS = 60_000L
    /** FS 0.9.129: most covering flows a refusal names (each with its start call). */
    static final int MAX_NAMED = 3
    static final Set<String> DIR_PARAMS = (['repoDir', 'workingDir', 'projectDir'] as Set<String>).asImmutable()

    /** The AW companion client FS already reports job events through -- here it asks AW for the covering flows. */
    @Autowired(required = false) AwJobEventClient awClient

    @Value('${mcp.filesystem.flow-covers.enforced:true}')
    boolean enforced = true

    final AtomicInteger refused = new AtomicInteger()
    final AtomicInteger acked = new AtomicInteger()

    private volatile List<Map<String, Object>> cached
    private volatile long cachedAt

    /** @return a FLOW_COVERS refusal, or null to run the command. */
    String check(String script, String workingDir, String flowAck) {
        if (!enforced || !script || awClient == null) return null
        if (AwCallContext.node() != null) return null
        if (!PlanGateGuard.BUILD_OR_GIT.matcher(script).find()) return null
        List<PlanGateGuard.GatedCommand> cmds
        try { cmds = PlanGateGuard.findGatedCommands(script, workingDir) } catch (Exception ignored) { return null }
        if (!cmds) return null
        List<Map<String, Object>> flows = covers()
        if (!flows) return null
        for (PlanGateGuard.GatedCommand g : cmds) {
            List<Map<String, Object>> hits = matchAll(g.command, flows)
            if (!hits) continue
            if (flowAck?.trim()) {
                acked.incrementAndGet()
                log.info('FLOW_COVERS acked: {} (covered by {}) -- {}', g.command,
                    hits.collect { Map<String, Object> h -> "${h.get('flow')}:${h.get('version')}".toString() }.join(', '), flowAck.trim())
                return null
            }
            refused.incrementAndGet()
            return refusal(g, hits)
        }
        return null
    }

    /** The first covering flow for one normalised command line, or null. */
    static Map<String, Object> match(String command, List<Map<String, Object>> flows) {
        List<Map<String, Object>> all = matchAll(command, flows)
        return all ? all[0] : null
    }

    /**
     * FS 0.9.129: EVERY covering flow, in catalogue order, at most {@link #MAX_NAMED}. Until 0.9.128 only the first
     * was named, so `git push` named git-commit although git-release also covers it (FlowCoversGuardSpec FC-7).
     */
    static List<Map<String, Object>> matchAll(String command, List<Map<String, Object>> flows) {
        List<Map<String, Object>> out = []
        for (Map<String, Object> f : flows) {
            if (out.size() >= MAX_NAMED) break
            for (Object c : ((f.get('covers') ?: []) as List)) {
                try {
                    if (Pattern.compile(c.toString()).matcher(command).find()) { out << f; break }
                } catch (PatternSyntaxException ignored) { }
            }
        }
        return out
    }

    static String refusal(PlanGateGuard.GatedCommand g, Map<String, Object> f) {
        return refusal(g, [f])
    }

    static String refusal(PlanGateGuard.GatedCommand g, List<Map<String, Object>> fs) {
        if (fs.size() == 1) {
            Map<String, Object> f = fs[0]
            String flow = f.get('flow') as String
            String line = (f.get('line') ?: '') as String
            return 'FLOW_COVERS -- not run: `' + g.command + '` is what ' + flow + ':' + f.get('version') + ' does. Run it ' +
                'as the flow: ' + startCall(f, g.dir) + ' -- then read its artifact. Catalogue: ' + line +
                '. If the flow genuinely cannot do this, repeat this call with options.flowAck="<why>".'
        }
        List<String> names = fs.collect { Map<String, Object> f -> (f.get('flow') as String) + ':' + f.get('version') }
        List<String> starts = []
        fs.eachWithIndex { Map<String, Object> f, int i -> starts << ('(' + (i + 1) + ') ' + startCall(f, g.dir)) }
        List<String> lines = fs.collect { Map<String, Object> f -> (f.get('line') ?: '') as String }
        return 'FLOW_COVERS -- not run: `' + g.command + '` is covered by ' + names.join(', ') + '. Run it as the ' +
            'flow that fits: ' + starts.join(' ; ') + ' -- then read its artifact. Catalogue: ' + lines.join(' | ') +
            '. If none of them can do this, repeat this call with options.flowAck="<why>".'
    }

    static String startCall(Map<String, Object> f, String dir) {
        String line = (f.get('line') ?: '') as String
        return 'flow_management action=start mode=flow templateName=' + (f.get('flow') as String) + ' params=' + startParams(line, dir)
    }

    /** The flow's required params, plus any directory param, as {name:"value"} -- the directory filled, the rest named. */
    static String startParams(String line, String dir) {
        int open = line.indexOf('  (')
        int close = open >= 0 ? line.indexOf(')', open) : -1
        List<String> parts = (open >= 0 && close > open) ? line.substring(open + 3, close).tokenize(',')*.trim() : []
        List<String> out = []
        for (String p : parts) {
            if (!p || p == '-') continue
            boolean req = p.contains('*')
            String name = p.replace('*', '').tokenize(':')[0].trim()
            if (DIR_PARAMS.contains(name)) out << (name + ':"' + (dir ?: '<' + name + '>') + '"')
            else if (req) out << (name + ':"<' + name + '>"')
        }
        return '{' + out.join(', ') + '}'
    }

    private List<Map<String, Object>> covers() {
        long now = System.currentTimeMillis()
        if (cached != null && now - cachedAt < TTL_MS) return cached
        List<Map<String, Object>> fresh = null
        try { fresh = awClient.flowCovers() } catch (Exception e) { log.debug('flowCovers failed (fail-open): {}', e.message) }
        if (fresh != null) { cached = fresh; cachedAt = now }
        return fresh ?: (cached ?: [] as List<Map<String, Object>>)
    }
}
