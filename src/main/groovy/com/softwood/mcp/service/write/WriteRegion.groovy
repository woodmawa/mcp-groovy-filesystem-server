package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

/**
 * FS 0.9.127 -- read efficiency (a) and (b), handoff 2026-11-07.
 *
 * <p>What one edit changed, worked out from the file's lines before and after it: the unchanged prefix and suffix,
 * the changed span in the NEW file, the region a write response returns (the changed span plus
 * {@link #CONTEXT_LINES} lines either side, cut at {@link #MAX_REGION_LINES}), and how an interval the chat held at
 * the old content maps onto the new one ({@link #carry}).</p>
 *
 * <p>One span, prefix to suffix: a multi_replace with two edits far apart returns everything between them. That
 * is deliberate -- the lines between are sent once in the region (or, past the cut, on the next read) rather than
 * the region being several pieces the ledger and the caller have to reassemble. Pure: no I/O.</p>
 */
@CompileStatic
class WriteRegion {

    static final int CONTEXT_LINES    = 3
    static final int MAX_REGION_LINES = 80

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
    /** The region returned and recorded as served: 1-based, inclusive; endLine < startLine when the file is empty. */
    final int startLine
    final int endLine
    final boolean truncated
    final List<String> lines

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
        int rs = Math.max(1, changedStart - context)
        int re = Math.min(newLineCount, Math.max(changedEnd, changedStart - 1) + context)
        boolean cut = re - rs + 1 > cap
        if (cut) re = rs + cap - 1
        startLine = rs
        endLine = re
        truncated = cut
        lines = re >= rs ? new ArrayList<String>(newL.subList(rs - 1, re)) : new ArrayList<String>()
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
        if (onlyDeleted()) {
            int gone = oldLineCount - newLineCount
            return gone > 0 ? ('deleted ' + gone + ' at ' + changedStart) : 'none'
        }
        return changedStart + '-' + changedEnd
    }

    /** The region as it is recorded on the ledger, or empty when there is nothing to record. */
    List<List<Integer>> regionInterval() {
        return endLine >= startLine ? [[startLine, endLine]] as List<List<Integer>> : new ArrayList<List<Integer>>()
    }

    /**
     * (b) The intervals held at the old content, mapped onto the new: the part inside the unchanged prefix keeps its
     * numbers, the part inside the unchanged suffix moves by the line delta, and the changed middle is dropped --
     * the chat never saw those lines at this content unless the region (recorded separately) gave them to it.
     */
    List<List<Integer>> carry(List<List<Integer>> held) {
        List<List<Integer>> out = new ArrayList<List<Integer>>()
        int delta = newLineCount - oldLineCount
        int tailStart = oldLineCount - suffix + 1
        for (List<Integer> iv : (held ?: [] as List<List<Integer>>)) {
            if (iv == null || iv.size() != 2 || iv[0] == null || iv[1] == null) continue
            int a = iv[0]
            int b = Math.min(iv[1], oldLineCount)
            if (b < a) continue
            if (a <= prefix) out << ([a, Math.min(b, prefix)] as List<Integer>)
            if (suffix > 0 && b >= tailStart) out << ([Math.max(a, tailStart) + delta, b + delta] as List<Integer>)
        }
        return out
    }

    Map<String, Object> toResponse(String newHash) {
        Map<String, Object> m = new LinkedHashMap<String, Object>()
        m.put('startLine', (Object) startLine)
        m.put('endLine', (Object) endLine)
        m.put('lines', (Object) lines.size())
        m.put('changed', (Object) changedLabel())
        m.put('content', (Object) lines.join('\n'))
        m.put('region_truncated', (Object) truncated)
        if (truncated) {
            m.put('region_note', (Object) ('Changed lines run past the ' + MAX_REGION_LINES + '-line cap: lines ' +
                startLine + '-' + endLine + ' are here, ' + (endLine + 1) + '-' + Math.min(newLineCount, changedEnd + CONTEXT_LINES) +
                ' are not. A range read of them is sent only what is missing.'))
        }
        m.put('file_content_hash', (Object) newHash)
        return m
    }
}
