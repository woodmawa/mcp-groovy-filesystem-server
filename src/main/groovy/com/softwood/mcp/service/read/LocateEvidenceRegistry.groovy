package com.softwood.mcp.service.read

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

/**
 * FS 0.9.43 N11 (close-the-loop-at-the-gate) -- a search hit is locate evidence.
 *
 * <p>ONTOLOGY-GATE exists so a reader knows WHERE in a file to read before reading it. Only
 * {@code context_read scope=ontology action=locate} satisfied it, so a {@code file_search} or
 * {@code grep} hit -- which already names the file AND the line, the very thing locate returns --
 * did not. Live on 2026-09-17 a session searched for a symbol, got file and line back, and was
 * still refused the read of that exact file twice.</p>
 *
 * <p>This records the paths a search has already pinpointed in THIS process, which is this chat's
 * process, and the gate treats them as located. Memory-only and deliberately so: it is evidence
 * about a conversation in flight, not a fact worth persisting, and a restart should re-earn it.</p>
 *
 * <p>Entries expire so evidence cannot outlive the work it belongs to.</p>
 */
@Slf4j
@Component
@CompileStatic
class LocateEvidenceRegistry {

    /** How long a hit stands in for a locate. Long enough for a read-after-search, not a session. */
    static final long TTL_MS = 45L * 60L * 1000L

    /** Bounded so a repository-wide search cannot grow this without limit. */
    static final int MAX_ENTRIES = 2000

    private final Map<String, Long> hits = new ConcurrentHashMap<String, Long>()

    private static String key(String sessionId, String normalizedPath) {
        return (sessionId ?: 'unknown') + '|' + (normalizedPath ?: '')
    }

    /** Record that a search pinpointed this path for this session. */
    void recordHit(String sessionId, String normalizedPath) {
        if (!normalizedPath) return
        if (hits.size() >= MAX_ENTRIES) sweep()
        if (hits.size() >= MAX_ENTRIES) return
        hits.put(key(sessionId, normalizedPath), System.currentTimeMillis())
    }

    /** Record several paths from one search result. */
    void recordHits(String sessionId, Collection<String> normalizedPaths) {
        (normalizedPaths ?: ([] as List<String>)).each { String p -> recordHit(sessionId, p) }
    }

    /** True when a search in this session has already named this path, within the TTL. */
    boolean isSatisfied(String sessionId, String normalizedPath) {
        Long at = hits.get(key(sessionId, normalizedPath))
        if (at == null) return false
        if (System.currentTimeMillis() - at.longValue() > TTL_MS) {
            hits.remove(key(sessionId, normalizedPath))
            return false
        }
        return true
    }

    /** Test seam and housekeeping: drop everything past its TTL. */
    int sweep() {
        long now = System.currentTimeMillis()
        int before = hits.size()
        hits.entrySet().removeIf { Map.Entry<String, Long> e -> now - e.value.longValue() > TTL_MS }
        return before - hits.size()
    }

    /** Test seam. */
    void clear() { hits.clear() }

    int size() { return hits.size() }
}
