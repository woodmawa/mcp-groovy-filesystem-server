package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

/**
 * StructureCache - session-scoped, lastModified-keyed cache for file structure results
 * and file content hashes.
 *
 * Cache key = normalizedPath.
 * Cache entry is invalidated when file's lastModified timestamp changes.
 *
 * Since this is a Spring singleton (same lifetime as the server process / session),
 * the cache naturally covers the whole conversation session.
 * Entries are cheap (just a List<Map> + 12-char hash) but we cap at MAX_ENTRIES and do
 * simple LRU-style eviction to avoid unbounded growth on very large codebases.
 *
 * v1.0.0 - initial
 * v1.1.0 - added fileHash cache (path+lastModified keyed) to avoid re-scanning on every
 *           read/grep/checksum call. Hash stored in CacheEntry alongside structure result.
 *           getHash() computes hash lazily on first request for a path, returns cached on
 *           subsequent calls until file is modified or invalidated.
 */
@Component
@Slf4j
@CompileStatic
class StructureCache {

    private static final int MAX_ENTRIES = 500

    @Autowired
    AstStructureScanner scanner

    // path -> CacheEntry  (holds both structure result AND hash)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>()
    // Per-path locks — only threads competing for the SAME file block each other.
    // Prevents duplicate (wasted) computation when two threads miss the cache simultaneously.
    private final ConcurrentHashMap<String, Object> computeLocks = new ConcurrentHashMap<>()
    // Access-order tracking for LRU eviction (LinkedHashMap under a simple lock)
    private final LinkedHashMap<String, Long> accessOrder = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_ENTRIES
        }
    }
    private final Object lruLock = new Object()

    // -----------------------------------------------------------------------
    // Public API - structure
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
        if (entry != null && entry.lastModified == currentModified && entry.result != null) {
            touchLru(normalizedPath, currentModified)
            log.debug("Structure cache HIT: {}", normalizedPath)
            Map<String, Object> hit = new LinkedHashMap<>(entry.result)
            hit.put('cached', (Object) true)
            hit.put('cacheKey', (Object) normalizedPath)
            return hit
        }

        // Cache miss or stale — acquire per-path lock so only one thread computes for this file.
        // Threads competing for different files are unaffected.
        Object pathLock = computeLocks.computeIfAbsent(normalizedPath) { new Object() }
        synchronized (pathLock) {
            // Double-check: another thread may have populated the cache while we waited
            CacheEntry recheck = cache.get(normalizedPath)
            if (recheck != null && recheck.lastModified == currentModified && recheck.result != null) {
                touchLru(normalizedPath, currentModified)
                log.debug("Structure cache HIT (after lock): {}", normalizedPath)
                Map<String, Object> hit2 = new LinkedHashMap<>(recheck.result)
                hit2.put('cached', (Object) true)
                hit2.put('cacheKey', (Object) normalizedPath)
                return hit2
            }

            log.debug("Structure cache MISS: {}", normalizedPath)
            Map<String, Object> result = scanner.scan(file)

            // Store in cache - preserve any existing hash if file hasn't changed
            evictIfNeeded()
            CacheEntry existing = cache.get(normalizedPath)
            String preservedHash = (existing != null && existing.lastModified == currentModified) ? existing.hash : null
            cache.put(normalizedPath, new CacheEntry(lastModified: currentModified, result: result, hash: preservedHash))
            touchLru(normalizedPath, currentModified)

            Map<String, Object> miss = new LinkedHashMap<>(result)
            miss.put('cached', (Object) false)
            miss.put('cacheKey', (Object) normalizedPath)
            return miss
        }
    }

    // -----------------------------------------------------------------------
    // Public API - hash
    // -----------------------------------------------------------------------

    /**
     * Get the 12-char SHA-256 hash for a file, using cache if still valid.
     * Computes lazily on first call; returns cached value on subsequent calls
     * until the file is modified or invalidated.
     *
     * @param normalizedPath  absolute normalised path string
     * @return  12-char hex hash, or null on error
     */
    String getHash(String normalizedPath) {
        File file = new File(normalizedPath)
        long currentModified = file.lastModified()

        CacheEntry entry = cache.get(normalizedPath)
        if (entry != null && entry.lastModified == currentModified && entry.hash != null) {
            log.debug("Hash cache HIT: {}", normalizedPath)
            touchLru(normalizedPath, currentModified)
            return entry.hash
        }

        // Acquire per-path lock — prevents duplicate hash computation for the same file.
        Object pathLock = computeLocks.computeIfAbsent(normalizedPath) { new Object() }
        synchronized (pathLock) {
            // Double-check after acquiring lock
            CacheEntry recheck = cache.get(normalizedPath)
            if (recheck != null && recheck.lastModified == currentModified && recheck.hash != null) {
                touchLru(normalizedPath, currentModified)
                log.debug("Hash cache HIT (after lock): {}", normalizedPath)
                return recheck.hash
            }

            log.debug("Hash cache MISS: {}", normalizedPath)
            String hash = computeHash(file)

            // Store - preserve existing structure result if present and still valid
            evictIfNeeded()
            CacheEntry existing = cache.get(normalizedPath)
            Map<String, Object> preservedResult = (existing != null && existing.lastModified == currentModified) ? existing.result : null
            cache.put(normalizedPath, new CacheEntry(lastModified: currentModified, result: preservedResult, hash: hash))
            touchLru(normalizedPath, currentModified)

            return hash
        }
    }

    // -----------------------------------------------------------------------
    // Public API - invalidation
    // -----------------------------------------------------------------------

    /**
     * Explicitly invalidate a path (call after any write operation on that file).
     * Clears both structure and hash entries.
     */
    void invalidate(String normalizedPath) {
        cache.remove(normalizedPath)
        synchronized (lruLock) {
            accessOrder.remove(normalizedPath)
        }
        log.debug("Structure+hash cache invalidated: {}", normalizedPath)
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
        log.info("Structure+hash cache cleared ({} entries)", size)
    }

    /** Current number of entries in cache. */
    int size() { cache.size() }

    /** Stats map for diagnostics. */
    Map<String, Object> stats() {
        int withStructure = cache.values().count { CacheEntry e -> e.result != null } as int
        int withHash      = cache.values().count { CacheEntry e -> e.hash != null } as int
        [
            entries      : cache.size(),
            maxEntries   : MAX_ENTRIES,
            withStructure: withStructure,
            withHash     : withHash,
            paths        : cache.keySet().toList()
        ] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private static String computeHash(File file) {
        try {
            def md = java.security.MessageDigest.getInstance('SHA-256')
            file.withInputStream { InputStream is ->
                byte[] buf = new byte[8192]
                int read
                while ((read = is.read(buf)) != -1) md.update(buf, 0, read)
            }
            return (md.digest().encodeHex().toString() as String)[0..11]
        } catch (Exception e) {
            return null
        }
    }

    private void touchLru(String path, long ts) {
        synchronized (lruLock) {
            accessOrder.put(path, ts)
            if (accessOrder.size() < cache.size()) {
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
        Map<String, Object> result   // null if only hash has been requested so far
        String hash                  // null if only structure has been requested so far
    }
}
