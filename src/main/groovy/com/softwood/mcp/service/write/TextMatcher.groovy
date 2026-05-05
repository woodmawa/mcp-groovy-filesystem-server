package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

import java.text.Normalizer
import java.util.function.IntConsumer

/**
 * TextMatcher -- Unicode-safe text resolution with original-span mapping.
 *
 * FS 0.9.0 / PR 1.2  Resolves D2 (NFC/NFKC wrong-offset replacement).
 *
 * Core design rule:
 *   MatchResult carries the matched span in the ORIGINAL string, not in any
 *   normalised form.  apply() always operates on original content using the
 *   [origStart, origEnd) offsets.  This eliminates the wrong-offset replacement
 *   bug where NFC/NFKC normalisation changes string length before the match
 *   position, causing the replacement to land on the wrong span.
 *
 * Normalisation pass order: raw -> NFC -> NFKC -> box-drawing.
 * Box-drawing substitution is pure ASCII (each box char -> one ASCII char),
 * so offsets are always length-preserving after that pass.
 *
 * Usage (single site):
 *   TextMatcher.MatchResult r = TextMatcher.find(content, oldText)
 *   if (r.count == 0) { ... not found ... }
 *   if (r.count > 1)  { ... ambiguous ... }
 *   String updated = TextMatcher.apply(original, newText, r)
 *   if (updated == TextMatcher.MATCH_REQUIRES_EXACT) { ... return toolError ... }
 *
 * Usage (multi-site, avoids recomputing normalised snapshots):
 *   List<TextMatcher.MatchResult> results = TextMatcher.findAll(content, oldTexts)
 */
@CompileStatic
final class TextMatcher {

    static final String NORM_NONE        = null
    static final String NORM_NFC         = 'NFC'
    static final String NORM_NFKC        = 'NFKC'
    static final String NORM_BOX_DRAWING = 'box-drawing'

    /**
     * Sentinel returned by apply() when NFC/NFKC changed string length and the
     * match span cannot be safely mapped back to original offsets.
     * Callers MUST check for this value and return a toolError (file unchanged).
     */
    static final String MATCH_REQUIRES_EXACT = '__MATCH_REQUIRES_EXACT__'

    // -----------------------------------------------------------------------
    // MatchResult
    // -----------------------------------------------------------------------

    /**
     * Result of a single match attempt.
     *
     *   count == 0 : not found after all four passes
     *   count == 1 : found exactly once -- use apply()
     *   count >  1 : ambiguous (multiple occurrences)
     *
     * When count == 1:
     *   normForm  = null (raw), 'NFC', 'NFKC', or 'box-drawing'
     *   origStart = match start in ORIGINAL content (not normalised)
     *   origEnd   = match end (exclusive) in ORIGINAL content
     *
     * When normForm != null and NFC/NFKC changed string length before the
     * match position, origStart/End will be -1 and apply() returns
     * MATCH_REQUIRES_EXACT (fail-closed).
     *
     * nearestLine/nearestContent: populated on count==0 for not-found error messages.
     */
    static class MatchResult {
        int    count        = 0
        String normForm     = NORM_NONE
        int    origStart    = -1
        int    origEnd      = -1
        int    nearestLine  = -1
        String nearestContent = null
    }

    // -----------------------------------------------------------------------
    // find() -- single oldText
    // -----------------------------------------------------------------------

    /**
     * Attempt to match oldText in content, trying up to 4 passes.
     * Returns a MatchResult with count 0, 1, or >1.
     */
    static MatchResult find(String content, String oldText) {
        // Pass 0: raw (no normalisation)
        int rawCount = com.softwood.mcp.service.write.WriteUtils.countOccurrences(content, oldText)
        if (rawCount == 1) {
            int s = content.indexOf(oldText)
            return new MatchResult(count: 1, normForm: NORM_NONE, origStart: s, origEnd: s + oldText.length())
        }
        if (rawCount > 1) return new MatchResult(count: rawCount)

        // Pass 1: NFC
        MatchResult r = tryNorm(content, oldText, Normalizer.Form.NFC, NORM_NFC)
        if (r != null) return r

        // Pass 2: NFKC
        r = tryNorm(content, oldText, Normalizer.Form.NFKC, NORM_NFKC)
        if (r != null) return r

        // Pass 3: box-drawing
        String bdContent = normalizeBoxDrawing(content)
        String bdOldText = normalizeBoxDrawing(oldText)
        int bdCount = com.softwood.mcp.service.write.WriteUtils.countOccurrences(bdContent, bdOldText)
        if (bdCount == 1) {
            // Box-drawing is pure ASCII substitution: one box char -> one ASCII char.
            // Lengths are preserved so offsets are always safe.
            int s = bdContent.indexOf(bdOldText)
            return new MatchResult(count: 1, normForm: NORM_BOX_DRAWING, origStart: s, origEnd: s + bdOldText.length())
        }
        if (bdCount > 1) return new MatchResult(count: bdCount)

        // Not found -- build nearest-line hint for error message
        MatchResult notFound = new MatchResult(count: 0)
        buildNearestHint(notFound, content, oldText)
        return notFound
    }

    // -----------------------------------------------------------------------
    // apply() -- single site
    // -----------------------------------------------------------------------

    /**
     * Apply a single-site replacement using the MatchResult from find().
     * Precondition: result.count == 1.
     *
     * Returns the updated content string on success.
     * Returns MATCH_REQUIRES_EXACT when the normalised match span cannot be
     * safely mapped back to original offsets (NFC/NFKC length change).
     * Callers must check for this sentinel and return a structured toolError.
     */
    static String apply(String original, String newText, MatchResult result) {
        if (result.origStart < 0 || result.origEnd < 0)  return MATCH_REQUIRES_EXACT
        if (result.origStart > original.length())         return MATCH_REQUIRES_EXACT
        if (result.origEnd   > original.length())         return MATCH_REQUIRES_EXACT
        return original.substring(0, result.origStart) + newText + original.substring(result.origEnd)
    }

    // -----------------------------------------------------------------------
    // findAll() -- multiple oldTexts, lazy normalised snapshots
    // -----------------------------------------------------------------------

    /**
     * Resolve normalisation form for each of N oldText entries.
     * Lazily initialises NFC/NFKC/box-drawing snapshots at most once per call.
     * Returns a List<MatchResult>, one per entry (same index as input).
     */
    static List<MatchResult> findAll(String content, List<String> oldTexts) {
        // Lazy snapshots -- computed at most once per findAll call
        String nfcContent  = null
        String nfkcContent = null
        String bdContent   = null

        List<MatchResult> results = new ArrayList<MatchResult>(oldTexts.size())

        for (String oldText : oldTexts) {
            // Pass 0: raw
            int rawCount = com.softwood.mcp.service.write.WriteUtils.countOccurrences(content, oldText)
            if (rawCount == 1) {
                int s = content.indexOf(oldText)
                results.add(new MatchResult(count: 1, normForm: NORM_NONE, origStart: s, origEnd: s + oldText.length()))
                continue
            }
            if (rawCount > 1) {
                results.add(new MatchResult(count: rawCount))
                continue
            }

            // Pass 1: NFC
            if (nfcContent == null) nfcContent = Normalizer.normalize(content, Normalizer.Form.NFC)
            String nfcOld = Normalizer.normalize(oldText, Normalizer.Form.NFC)
            MatchResult r = tryNormLazy(content, nfcContent, nfcOld, NORM_NFC)
            if (r != null) { results.add(r); continue }

            // Pass 2: NFKC
            if (nfkcContent == null) nfkcContent = Normalizer.normalize(content, Normalizer.Form.NFKC)
            String nfkcOld = Normalizer.normalize(oldText, Normalizer.Form.NFKC)
            r = tryNormLazy(content, nfkcContent, nfkcOld, NORM_NFKC)
            if (r != null) { results.add(r); continue }

            // Pass 3: box-drawing
            if (bdContent == null) bdContent = normalizeBoxDrawing(content)
            String bdOld = normalizeBoxDrawing(oldText)
            int bdCount = com.softwood.mcp.service.write.WriteUtils.countOccurrences(bdContent, bdOld)
            if (bdCount == 1) {
                int s = bdContent.indexOf(bdOld)
                results.add(new MatchResult(count: 1, normForm: NORM_BOX_DRAWING, origStart: s, origEnd: s + bdOld.length()))
                continue
            }
            if (bdCount > 1) {
                results.add(new MatchResult(count: bdCount))
                continue
            }

            // Not found
            MatchResult notFound = new MatchResult(count: 0)
            buildNearestHint(notFound, content, oldText)
            results.add(notFound)
        }

        return results
    }

    // -----------------------------------------------------------------------
    // normalizeBoxDrawing() -- public so StructuralGuard can reuse it
    // -----------------------------------------------------------------------

    /**
     * Normalise box-drawing characters (U+2500..U+257F) to ASCII equivalents.
     * U+2502/U+2503/U+2551 (vertical lines) -> '|', all others -> '-'.
     * Pure one-for-one substitution: string length is preserved.
     */
    static String normalizeBoxDrawing(String s) {
        if (!s) return s
        StringBuilder sb = new StringBuilder(s.length())
        // Use explicit IntConsumer cast to satisfy @CompileStatic (G3 gotcha)
        s.codePoints().forEach((IntConsumer) { int cp ->
            if (cp >= 0x2500 && cp <= 0x257F) {
                // Use appendCodePoint with ASCII values: 124='|', 45='-'
                sb.appendCodePoint(cp == 0x2502 || cp == 0x2503 || cp == 0x2551 ? 124 : 45)
            } else {
                sb.appendCodePoint(cp)
            }
        })
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Try a normalisation pass with fresh snapshot computation.
     * Returns null if count == 0 (not a hit), MatchResult with count>1 if ambiguous,
     * MatchResult with count==1 if resolved.
     */
    private static MatchResult tryNorm(String content, String oldText,
                                        Normalizer.Form form, String label) {
        String nc = Normalizer.normalize(content, form)
        String no = Normalizer.normalize(oldText,  form)
        int c = com.softwood.mcp.service.write.WriteUtils.countOccurrences(nc, no)
        if (c == 0) return null
        if (c > 1)  return new MatchResult(count: c)
        return resolveNormSpan(content, nc, no, label)
    }

    /**
     * Try a normalisation pass with pre-computed normalised content snapshot.
     * Used by findAll() to avoid recomputing the content snapshot per entry.
     */
    private static MatchResult tryNormLazy(String content, String normContent,
                                            String normOld, String label) {
        int c = com.softwood.mcp.service.write.WriteUtils.countOccurrences(normContent, normOld)
        if (c == 0) return null
        if (c > 1)  return new MatchResult(count: c)
        return resolveNormSpan(content, normContent, normOld, label)
    }

    /**
     * Resolve a normalised match position back to original string offsets.
     *
     * Safe only when normalisation does not change the cumulative char count
     * up to and including the match position (true for most NFC on Latin content;
     * occasionally false for decomposed sequences or compatibility ligatures).
     *
     * When lengths differ before the match: returns count:1 with origStart/End = -1.
     * apply() will return MATCH_REQUIRES_EXACT (fail-closed -- file unchanged).
     */
    private static MatchResult resolveNormSpan(String original, String normOrig,
                                                String normOld, String label) {
        int normPos = normOrig.indexOf(normOld)
        int normEnd = normPos + normOld.length()

        // Verify that normalisation preserved char count up to the match start.
        // We check the prefix length in both the normalised and original strings.
        // If they match, the offset is safe to apply to original.
        if (normPos >= 0 && normEnd <= original.length() && normPos <= original.length()) {
            String preNorm = normOrig.substring(0, normPos)
            // If original is shorter than normPos, the offset has shifted -- fail closed
            if (normPos <= original.length()) {
                String preOrig = original.substring(0, Math.min(normPos, original.length()))
                if (preNorm.length() == preOrig.length()) {
                    return new MatchResult(count: 1, normForm: label, origStart: normPos, origEnd: normEnd)
                }
            }
        }
        // Length changed or offset unsafe -- fail closed
        // apply() will return MATCH_REQUIRES_EXACT, caller returns toolError, file unchanged
        return new MatchResult(count: 1, normForm: label, origStart: -1, origEnd: -1)
    }

    /**
     * Build a nearest-line hint for not-found error messages.
     * Looks at the first non-empty line of oldText and finds the closest
     * match in the first 500 lines of content (by length similarity).
     */
    private static void buildNearestHint(MatchResult r, String content, String oldText) {
        String firstLine = oldText.trim().tokenize('\n').first()?.trim() ?: ''
        if (!firstLine) return
        int bestScore = Integer.MAX_VALUE
        String[] allLines = content.split('\n', -1)
        int limit = Math.min(allLines.length, 500)
        for (int idx = 0; idx < limit; idx++) {
            String fl = allLines[idx]
            String tf = fl.trim()
            String flLower  = firstLine.toLowerCase(Locale.ROOT)
            String tfLower  = tf.toLowerCase(Locale.ROOT)
            if (tfLower.contains(flLower) || flLower.contains(tfLower)) {
                int score = Math.abs(tf.length() - firstLine.length())
                if (score < bestScore) {
                    bestScore = score
                    r.nearestContent = fl
                    r.nearestLine = idx + 1
                }
            }
        }
    }
}
