package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

/**
 * DestructiveChangeGuard -- uniform ratio guard across all write actions + bounded LRU factory.
 *
 * FS 0.9.0 / PR 1.1  Resolves D6 (unbounded LRU maps) and D10 (ratio guard only on replace).
 *
 * Design rules:
 *  - check() is the single entry point for the ratio guard.  All three write actions
 *    (replace, multi_replace, patch) call it before atomicWrite.  server_transform
 *    callers pass their computed removedLength/addedLength.
 *  - Thresholds differ by file type: source-code files use a tighter threshold (500 chars)
 *    because accidental wipes of .groovy/.java files are catastrophic.  Non-code files
 *    (docs, config) use a looser threshold (2000 chars) to avoid false positives on
 *    legitimate large rewrites such as regenerating a README or SQL migration file.
 *  - force=true is the only bypass -- callers pass options.force as boolean.
 *  - boundedLruMap() replaces every plain `Collections.synchronizedMap(new LinkedHashMap())`
 *    in the codebase.  Cap is 200 entries.
 */
@CompileStatic
final class DestructiveChangeGuard {

    static final int  DEFAULT_CODE_THRESHOLD     = 500
    static final int  DEFAULT_NON_CODE_THRESHOLD = 500   // same as code for now; can be loosened in Phase 4
    static final double DEFAULT_RATIO            = 0.20d
    static final int  LRU_CAP                    = 200
    /**
     * FS 0.9.88: a removal under this share of the file is never a truncated full-file rewrite, which is the
     * one failure this guard exists for. The patcher agent (task 98c53eda, 2026-10-13) was refused twice for
     * deleting one dead method from a large class: 684 chars out, 0 in, so 0% < 20%, though the method was a
     * small part of the file.
     */
    static final double SMALL_SHARE              = 0.50d

    /**
     * Check for a destructive-ratio violation.
     *
     * Fires when ALL of:
     *   removedLength > threshold (file-type-aware)
     *   addedLength   < removedLength * ratio
     *   force == false
     *
     * @param action         one of 'replace', 'multi_replace', 'patch', 'server_transform'
     *                       (used only in the error message)
     * @param removedLength  total chars being removed
     * @param addedLength    total chars being added
     * @param force          true to bypass (options.force=true from caller)
     * @param filePath       used to pick code vs non-code threshold
     * @param fileLength     length of the file being changed; when {@code > 0} and the removal is under
     *                       {@link #SMALL_SHARE} of it, the change is allowed -- removing under half a file is a
     *                       deletion, not a truncated rewrite. {@code <= 0} means unknown: the ratio alone decides.
     * @return               error string if guard fires, null if OK
     *
     * FS 0.9.89 T2: the five-argument form (length unknown) is gone -- every production caller passes the
     * length -- and the refusal states what was measured instead of claiming "at least half the file" on a
     * path that had not measured the file at all.
     */
    static String check(String action, int removedLength, int addedLength,
                        boolean force, String filePath, int fileLength) {
        if (force) return null
        if (fileLength > 0 && removedLength < fileLength * SMALL_SHARE) return null
        int threshold = isCodeFile(filePath) ? DEFAULT_CODE_THRESHOLD : DEFAULT_NON_CODE_THRESHOLD
        if (removedLength > threshold && addedLength < (int)(removedLength * DEFAULT_RATIO)) {
            return (
                "DESTRUCTIVE_REPLACE: newText (${addedLength} chars) is less than 20% of " +
                "oldText (${removedLength} chars)" +
                (fileLength > 0 ? " and removes ${(int) Math.round(removedLength * 100.0d / fileLength)}% of the file" : '') +
                '. ' +
                "This typically means a full-file ${action} with truncated newText, which destroys content. " +
                "To rewrite the file use action=write with the full content. " +
                "For a legitimate shrinking ${action}, reduce oldText scope to just the target block. " +
                "To force a large deletion pass options.force=true."
            )
        }
        return null
    }

    /**
     * Bounded LRU map factory.
     *
     * Use everywhere instead of:
     *   Collections.synchronizedMap(new LinkedHashMap())
     *
     * Caps at LRU_CAP (200) entries, evicting the eldest when full.
     * Thread-safe via Collections.synchronizedMap wrapper.
     */
    static <K, V> Map<K, V> boundedLruMap(int maxEntries = LRU_CAP) {
        return Collections.synchronizedMap(
            new LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > maxEntries
                }
            }
        )
    }

    // -----------------------------------------------------------------------
    private static boolean isCodeFile(String path) {
        if (!path) return false
        String lower = path.toLowerCase(Locale.ROOT)
        lower.endsWith('.groovy') || lower.endsWith('.java') ||
        lower.endsWith('.kt')     || lower.endsWith('.kts')
    }
}
