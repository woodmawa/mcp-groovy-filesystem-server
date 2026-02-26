package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

/**
 * StructureCache - session-scoped, lastModified-keyed cache for file structure results.
 *
 * Cache key = normalizedPath.
 * Cache entry is invalidated when file's lastModified timestamp changes.
 *
 * Since this is a Spring singleton (same lifetime as the server process / session),
 * the cache naturally covers the whole conversation session.
 * Entries are cheap (just a List<Map>) but we cap at MAX_ENTRIES and do
 * simple LRU-style eviction to avoid unbounded growth on very large codebases.
 *
 * v1.0.0 - initial
 */
@Component
@Slf4j
@CompileStatic
class StructureCache {

    private static final int MAX_ENTRIES = 500

    @Autowired
    AstStructureScanner scanner

    // path -> CacheEntry
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>()
    // Access-order tracking for LRU eviction (LinkedHashMap under a simple lock)
    private final LinkedHashMap<String, Long> accessOrder = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_ENTRIES
        }
    }
    private final Object lruLock = new Object()

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Get structure for a file, using cache if still valid.
     * @param normalizedPath  absolute normalised path string
     * @return  map with: ext, structure, scanner, cached (bool), cacheKey
     */
    Map<String, Object> getStructure(String normalizedPath) {
        File file = new File(normalizedPath)
        long currentModified = file.lastModified()

        CacheEntry entry = cache.get(normalizedPath)
        if (entry != null && entry.lastModified == currentModified) {
            touchLru(normalizedPath, currentModified)
            log.debug("Structure cache HIT: {}", normalizedPath)
            Map<String, Object> hit = new LinkedHashMap<>(entry.result)
            hit.put('cached', (Object) true)
            hit.put('cacheKey', (Object) normalizedPath)
            return hit
        }

        // Cache miss or stale - compute
        log.debug("Structure cache MISS: {}", normalizedPath)
        Map<String, Object> result = scanner.scan(file)

        // Store in cache
        evictIfNeeded()
        cache.put(normalizedPath, new CacheEntry(lastModified: currentModified, result: result))
        touchLru(normalizedPath, currentModified)

        Map<String, Object> miss = new LinkedHashMap<>(result)
        miss.put('cached', (Object) false)
        miss.put('cacheKey', (Object) normalizedPath)
        return miss
    }

    /**
     * Explicitly invalidate a path (call after any write operation on that file).
     */
    void invalidate(String normalizedPath) {
        cache.remove(normalizedPath)
        synchronized (lruLock) {
            accessOrder.remove(normalizedPath)
        }
        log.debug("Structure cache invalidated: {}", normalizedPath)
    }

    /**
     * Clear the entire cache (e.g., on server restart or manual flush).
     */
    void clear() {
        int size = cache.size()
        cache.clear()
        synchronized (lruLock) {
            accessOrder.clear()
        }
        log.info("Structure cache cleared ({} entries)", size)
    }

    /** Current number of entries in cache. */
    int size() { cache.size() }

    /** Stats map for diagnostics. */
    Map<String, Object> stats() {
        [
            entries    : cache.size(),
            maxEntries : MAX_ENTRIES,
            paths      : cache.keySet().toList()
        ] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private void touchLru(String path, long ts) {
        synchronized (lruLock) {
            accessOrder.put(path, ts)
            // removeEldestEntry handles eviction from accessOrder
            // but we also need to sync cache eviction
            if (accessOrder.size() < cache.size()) {
                // This shouldn't happen, but guard anyway
                List<String> toRemove = new ArrayList<>(cache.keySet())
                toRemove.removeAll(accessOrder.keySet())
                toRemove.each { String k -> cache.remove(k) }

            }
        }
    }

    private void evictIfNeeded() {
        synchronized (lruLock) {
            while (cache.size() >= MAX_ENTRIES) {
                String oldest = accessOrder.keySet().iterator().next()
                cache.remove(oldest)
                accessOrder.remove(oldest)
                log.debug("Structure cache evicted LRU: {}", oldest)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Inner class
    // -----------------------------------------------------------------------

    private static class CacheEntry {
        long lastModified
        Map<String, Object> result
    }
}
