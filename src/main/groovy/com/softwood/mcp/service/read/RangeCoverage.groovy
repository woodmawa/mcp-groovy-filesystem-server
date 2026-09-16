package com.softwood.mcp.service.read

import groovy.transform.CompileStatic

/**
 * FS 0.9.35 WP-G G4 -- line-interval arithmetic for range de-duplication.
 *
 * <p>Given a requested window and the intervals already served this session for the same file
 * content, answer the smallest single window that contains every line not yet served. A single
 * window rather than several keeps the response shape a range response; a served block strictly
 * inside the answer is re-sent, which is the price of that and is rare in practice (paging reads
 * forward).</p>
 */
@CompileStatic
final class RangeCoverage {

    private RangeCoverage() {}

    /**
     * @return {@code [first, last]} unserved line within {@code [start, end]}, or {@code null} when
     *         every line in the window has already been served
     */
    static List<Integer> uncovered(int start, int end, List<List<Integer>> served) {
        if (end < start) return null
        List<List<Integer>> merged = merge(served)
        Integer first = null
        Integer last = null
        int line = start
        while (line <= end) {
            List<Integer> hit = merged.find { List<Integer> iv -> iv[0] <= line && line <= iv[1] }
            if (hit != null) {
                line = hit[1] + 1
                continue
            }
            if (first == null) first = line
            // extend to the next served interval or the end of the window
            List<Integer> next = merged.find { List<Integer> iv -> iv[0] > line }
            int stop = (next != null && next[0] - 1 < end) ? next[0] - 1 : end
            last = stop
            line = stop + 1
        }
        return first == null ? null : ([first, last] as List<Integer>)
    }

    /** Human-readable merged intervals, e.g. {@code ['1-40', '61-70']}. */
    static List<String> describe(List<List<Integer>> served) {
        return merge(served).collect { List<Integer> iv -> "${iv[0]}-${iv[1]}".toString() }
    }

    static List<List<Integer>> merge(List<List<Integer>> served) {
        List<List<Integer>> sorted = ((served ?: []) as List<List<Integer>>)
            .findAll { List<Integer> iv -> iv != null && iv.size() == 2 && iv[0] != null && iv[1] != null && iv[0] <= iv[1] }
            .collect { List<Integer> iv -> [iv[0] as Integer, iv[1] as Integer] as List<Integer> }
            .sort { List<Integer> iv -> iv[0] }
        List<List<Integer>> out = []
        for (List<Integer> iv : sorted) {
            if (out && iv[0] <= out[-1][1] + 1) {
                out[-1][1] = Math.max(out[-1][1], iv[1])
            } else {
                out << ([iv[0], iv[1]] as List<Integer>)
            }
        }
        return out
    }
}
