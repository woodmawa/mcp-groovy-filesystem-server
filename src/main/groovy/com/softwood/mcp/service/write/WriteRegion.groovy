package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

/**
 * FS 0.9.127 -- read efficiency (a) and (b), handoff 2026-11-07. FS 0.9.128 -- (c), brief 2026-11-07 item 4.
 *
 * <p>What one edit changed, worked out from the file's lines before and after it. The unchanged prefix and suffix are
 * found first; the middle between them is aligned line by line (longest common subsequence), so an edit made of
 * several hunks -- a multi_replace or patch touching places far apart -- is several hunks here too. Each hunk is
 * returned as its own region (its changed lines plus {@link #CONTEXT_LINES} either side); hunks whose regions touch
 * are merged into one. The regions together are cut at {@link #MAX_REGION_LINES}.</p>
 *
 * <p>FS 0.9.127 returned one span, prefix to suffix: on 2026-11-07 three edits about 230 lines apart filled the cap
 * with unchanged lines between them, and the last edit was never returned. The unchanged lines between hunks are
 * now neither sent nor lost: {@link #carry} maps what the chat held at the old content through the alignment, so
 * held lines between hunks stay held at the new hash.</p>
 *
 * <p>A single region keeps the 0.9.127 response shape exactly. A middle too large to align (more than
 * {@link #MAX_ALIGN_CELLS} line pairs) falls back to one span, as before. Pure: no I/O.</p>
 */
@CompileStatic
class WriteRegion {

    static final int CONTEXT_LINES    = 3
    static final int MAX_REGION_LINES = 80
    /** Line pairs the middle may hold and still be aligned: 2,000 x 2,000. Past it, one span (the 0.9.127 answer). */
    static final long MAX_ALIGN_CELLS = 4_000_000L

    final int oldLineCount
    final int newLineCount
    /** Leading lines identical before and after. */
    final int prefix
    /** Trailing lines identical before and after (never overlapping the prefix). */
    final int suffix
    /** First changed line in the new file (1-based). */
    final int changedStart
    /** Last changed line in the new file; {@code changedStart - 1} when the edit only deleted. */
    final int changedEnd
    /** First and last line of the regions returned: 1-based, inclusive; endLine < startLine when there are none. */
    final int startLine
    final int endLine
    final boolean truncated
    /** The lines of the FIRST region (the whole answer when there is one region). */
    final List<String> lines
    /** One per returned region, in file order. */
    final List<Region> regions
    /** Hunks that did not fit under the cap, as 'a-b' labels -- named in region_note. */
    final List<String> notReturned

    /** old line (1-based) -> new line (1-based) for every line the edit left alone; 0 = changed or deleted. */
    private final int[] oldToNew

    @CompileStatic
    static class Region {
        int startLine, endLine
        String changed
        List<String> lines
        boolean cut
    }

    @CompileStatic
    private static class Hunk {
        int newStart       // first new line of the hunk (1-based); where a deletion sits when nothing was added
        int newEnd         // last new line added; newStart - 1 when the hunk only deleted
        int deleted        // old lines the hunk removed
        String label() { newEnd >= newStart ? (newStart + '-' + newEnd) : ('deleted ' + deleted + ' at ' + newStart) }
    }

    private WriteRegion(List<String> oldL, List<String> newL, int context, int cap) {
        oldLineCount = oldL.size()
        newLineCount = newL.size()
        int min = Math.min(oldLineCount, newLineCount)
        int p = 0
        while (p < min && oldL[p] == newL[p]) p++
        int s = 0
        while (s < min - p && oldL[oldLineCount - 1 - s] == newL[newLineCount - 1 - s]) s++
        prefix = p
        suffix = s
        changedStart = p + 1
        changedEnd = newLineCount - s

        oldToNew = new int[oldLineCount + 1]
        for (int i = 1; i <= p; i++) oldToNew[i] = i
        for (int i = 0; i < s; i++) oldToNew[oldLineCount - i] = newLineCount - i

        List<Hunk> hunks = alignMiddle(oldL, newL, p, s)

        // Merge hunks whose regions (hunk +/- context) touch or overlap.
        List<int[]> spans = new ArrayList<int[]>()       // [regionStart, regionEnd, firstHunkIdx, lastHunkIdx]
        for (int h = 0; h < hunks.size(); h++) {
            Hunk k = hunks[h]
            int rs = Math.max(1, k.newStart - context)
            int re = Math.min(newLineCount, Math.max(k.newEnd, k.newStart - 1) + context)
            if (!spans.isEmpty() && rs <= spans[-1][1] + 1) {
                spans[-1][1] = Math.max(spans[-1][1], re)
                spans[-1][3] = h
            } else {
                spans << ([rs, re, h, h] as int[])
            }
        }

        List<Region> out = new ArrayList<Region>()
        List<String> left = new ArrayList<String>()
        int budget = cap
        boolean cut = false
        for (int[] sp : spans) {
            Hunk first = hunks[sp[2]], last = hunks[sp[3]]
            String label = sp[2] == sp[3] ? first.label() :
                (first.newStart + '-' + Math.max(last.newEnd, last.newStart - 1))
            if (budget <= 0) { left << label; cut = true; continue }
            int rs = sp[0], re = sp[1]
            boolean thisCut = re - rs + 1 > budget
            if (thisCut) { re = rs + budget - 1; cut = true }
            Region r = new Region(startLine: rs, endLine: re, changed: label, cut: thisCut,
                lines: re >= rs ? new ArrayList<String>(newL.subList(rs - 1, re)) : new ArrayList<String>())
            out << r
            budget -= r.lines.size()
        }
        regions = out
        notReturned = left
        truncated = cut
        startLine = out ? out[0].startLine : 1
        endLine = out ? out[-1].endLine : 0
        lines = out ? out[0].lines : new ArrayList<String>()
    }

    /**
     * Align old[p .. oldN-s) with new[p .. newN-s); fill oldToNew for matched lines and return the hunks. A middle
     * past MAX_ALIGN_CELLS is one hunk -- everything between prefix and suffix, the 0.9.127 answer.
     */
    private List<Hunk> alignMiddle(List<String> oldL, List<String> newL, int p, int s) {
        int n = oldLineCount - p - s
        int m = newLineCount - p - s
        List<Hunk> hunks = new ArrayList<Hunk>()
        if (n == 0 && m == 0) return hunks
        if (n == 0 || m == 0 || (long) n * (long) m > MAX_ALIGN_CELLS) {
            hunks << new Hunk(newStart: p + 1, newEnd: p + m, deleted: n)
            return hunks
        }
        int[] a = new int[n], b = new int[m]
        Map<String, Integer> ids = new HashMap<String, Integer>()
        for (int i = 0; i < n; i++) { String t = oldL[p + i]; Integer id = ids.get(t); if (id == null) { id = ids.size(); ids.put(t, id) }; a[i] = id }
        for (int j = 0; j < m; j++) { String t = newL[p + j]; Integer id = ids.get(t); if (id == null) { id = ids.size(); ids.put(t, id) }; b[j] = id }
        // Suffix LCS lengths: L[i][j] = LCS of a[i..], b[j..].
        int[][] L = new int[n + 1][m + 1]
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                L[i][j] = a[i] == b[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1])
            }
        }
        int i = 0, j = 0
        Hunk cur = null
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) {
                oldToNew[p + i + 1] = p + j + 1
                if (cur != null) { hunks << cur; cur = null }
                i++; j++
            } else if (j < m && (i >= n || L[i][j + 1] >= L[i + 1][j])) {
                if (cur == null) cur = new Hunk(newStart: p + j + 1, newEnd: p + j, deleted: 0)
                cur.newEnd = p + j + 1
                j++
            } else {
                if (cur == null) cur = new Hunk(newStart: p + j + 1, newEnd: p + j, deleted: 0)
                cur.deleted++
                i++
            }
        }
        if (cur != null) hunks << cur
        return hunks
    }

    static WriteRegion of(List<String> oldLines, List<String> newLines) {
        return new WriteRegion(oldLines ?: [], newLines ?: [], CONTEXT_LINES, MAX_REGION_LINES)
    }

    static WriteRegion of(List<String> oldLines, List<String> newLines, int context, int cap) {
        return new WriteRegion(oldLines ?: [], newLines ?: [], context, cap)
    }

    /** Lines a file holds, as the read path numbers them: CR LF and LF both end a line; a final newline adds none. */
    static List<String> linesOf(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<String>()
        return text.readLines()
    }

    boolean onlyDeleted() { changedEnd < changedStart }

    /** '10-11', or 'deleted 2 at 10' for an edit that removed lines and added none, or 'none' for no change. */
    String changedLabel() {
        if (regions.size() == 1) return regions[0].changed
        if (regions.isEmpty()) {
            int gone = oldLineCount - newLineCount
            return gone > 0 ? ('deleted ' + gone + ' at ' + changedStart) : 'none'
        }
        return regions.collect { it.changed }.join(', ')
    }

    /** The regions as they are recorded on the ledger, or empty when there is nothing to record. */
    List<List<Integer>> regionInterval() {
        List<List<Integer>> out = new ArrayList<List<Integer>>()
        for (Region r : regions) if (r.endLine >= r.startLine) out << ([r.startLine, r.endLine] as List<Integer>)
        return out
    }

    /**
     * (b) The intervals held at the old content, mapped onto the new through the alignment: every line the edit left
     * alone keeps being held at its new number -- prefix, suffix, and (0.9.128) the unchanged lines between hunks.
     * Changed and deleted lines are dropped; the regions (recorded separately) give the chat the changed ones.
     */
    List<List<Integer>> carry(List<List<Integer>> held) {
        List<List<Integer>> out = new ArrayList<List<Integer>>()
        for (List<Integer> iv : (held ?: [] as List<List<Integer>>)) {
            if (iv == null || iv.size() != 2 || iv[0] == null || iv[1] == null) continue
            int a = Math.max(1, iv[0])
            int b = Math.min(iv[1], oldLineCount)
            int runStart = -1, runEnd = -1
            for (int o = a; o <= b; o++) {
                int nl = oldToNew[o]
                if (nl > 0 && runEnd >= 0 && nl == runEnd + 1) { runEnd = nl; continue }
                if (runStart > 0) out << ([runStart, runEnd] as List<Integer>)
                if (nl > 0) { runStart = nl; runEnd = nl } else { runStart = -1; runEnd = -1 }
            }
            if (runStart > 0) out << ([runStart, runEnd] as List<Integer>)
        }
        return out
    }

    Map<String, Object> toResponse(String newHash) {
        Map<String, Object> m = new LinkedHashMap<String, Object>()
        if (regions.size() <= 1) {
            m.put('startLine', (Object) startLine)
            m.put('endLine', (Object) endLine)
            m.put('lines', (Object) lines.size())
            m.put('changed', (Object) changedLabel())
            m.put('content', (Object) lines.join('\n'))
        } else {
            List<Map<String, Object>> rs = new ArrayList<Map<String, Object>>()
            int total = 0
            for (Region r : regions) {
                Map<String, Object> e = new LinkedHashMap<String, Object>()
                e.put('startLine', (Object) r.startLine)
                e.put('endLine', (Object) r.endLine)
                e.put('changed', (Object) r.changed)
                e.put('content', (Object) r.lines.join('\n'))
                rs << e
                total += r.lines.size()
            }
            m.put('regions', (Object) rs)
            m.put('lines', (Object) total)
            m.put('changed', (Object) changedLabel())
        }
        m.put('region_truncated', (Object) truncated)
        if (truncated) {
            Region lastR = regions ? regions[-1] : null
            String missing = notReturned ? (' Not returned: ' + notReturned.join(', ') + '.') : ''
            m.put('region_note', (Object) ('Changed lines run past the ' + MAX_REGION_LINES + '-line cap' +
                (lastR?.cut ? ': region ' + lastR.startLine + '-' + lastR.endLine + ' was cut at its end.' : '.') + missing +
                ' A range read of them is sent only what is missing.'))
        }
        m.put('file_content_hash', (Object) newHash)
        return m
    }
}
