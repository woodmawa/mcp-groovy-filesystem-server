package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

/**
 * MultiReplaceValidator -- pre-validate all multi_replace entries before touching the file.
 *
 * FS 0.9.0 / PR 1.5  Resolves D9 (doMultiReplace 241 lines, three validation phases inlined).
 *
 * Extracted from FileReplaceService.doMultiReplace. All three validation phases are now
 * independently testable and the doMultiReplace body is ~75 lines after extraction.
 *
 * Phase A: per-entry presence check (missing oldText / missing newText key)
 * Phase B: TextMatcher resolution -- finds each oldText in snapshot, detects not-found / ambiguous
 * Phase C: overlap checks -- containment (RCA-2a) + suffix/prefix boundary (RCA-2b)
 * Phase D: simulation pass -- entry-makes-entry-unfindable (RCA-2c)
 *
 * Stops accumulating at MAX_ERRORS (10) to prevent runaway output.
 * File is NOT touched regardless of outcome; caller applies only if valid().
 */
@CompileStatic
final class MultiReplaceValidator {

    static final int MAX_ERRORS = 10

    // -----------------------------------------------------------------------
    // ValidationResult
    // -----------------------------------------------------------------------

    static class ValidationResult {
        final List<String>                  errors
        final List<String>                  oldTexts       // LF-normalised oldText per entry
        final List<TextMatcher.MatchResult> matchResults   // one per entry (same index)

        private ValidationResult(List<String> errors,
                                  List<String> oldTexts,
                                  List<TextMatcher.MatchResult> matchResults) {
            this.errors       = errors
            this.oldTexts     = oldTexts
            this.matchResults = matchResults
        }

        boolean valid() { errors.isEmpty() }

        static ValidationResult ofErrors(List<String> errors) {
            new ValidationResult(errors, [], [])
        }

        static ValidationResult ok(List<String> oldTexts,
                                    List<TextMatcher.MatchResult> matchResults) {
            new ValidationResult([], oldTexts, matchResults)
        }
    }

    // -----------------------------------------------------------------------
    // validate() -- single entry point
    // -----------------------------------------------------------------------

    /**
     * Validate all replacement entries against the given snapshot string.
     *
     * @param snapshot      LF-normalised file content (from WriteContext.content)
     * @param replacements  list of replacement maps with 'oldText' and 'newText' keys
     * @return              ValidationResult -- call valid() before applying
     */
    static ValidationResult validate(String snapshot, List<Map<String, Object>> replacements) {
        List<String> errors = new ArrayList<String>()

        // ---- Phase A: per-entry presence check ----
        for (int i = 0; i < replacements.size(); i++) {
            if (errors.size() >= MAX_ERRORS) break
            Map<String, Object> rep = replacements.get(i)
            String ot = (rep.oldText as String)?.replace('\r\n', '\n')?.replace('\r', '\n')
            if (!ot) {
                errors << ('Entry ' + i + ': missing oldText')
                continue
            }
            if (!rep.containsKey('newText')) {
                errors << ('Entry ' + i + ': missing newText -- pass newText:\'\' explicitly if deletion is intended')
            }
        }
        if (!errors.isEmpty()) return ValidationResult.ofErrors(errors)

        // ---- Phase B: TextMatcher resolution ----
        List<String> oldTexts = new ArrayList<String>(replacements.size())
        for (Map<String, Object> rep : replacements) {
            oldTexts.add(((rep.oldText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n'))
        }
        List<TextMatcher.MatchResult> matchResults = TextMatcher.findAll(snapshot, oldTexts)

        for (int i = 0; i < matchResults.size(); i++) {
            if (errors.size() >= MAX_ERRORS) break
            TextMatcher.MatchResult mr = matchResults.get(i)
            if (mr.count == 0) {
                String nearHint = (mr.nearestLine > 0)
                    ? " -- nearest line ${mr.nearestLine}: '${mr.nearestContent?.take(80)}'"
                    : ''
                errors << ("Entry ${i}: oldText not found${nearHint}" as String)
            } else if (mr.count > 1) {
                errors << ("Entry ${i}: oldText appears ${mr.count} times (must be unique). Provide more context." as String)
            }
        }
        if (!errors.isEmpty()) return ValidationResult.ofErrors(errors)

        // ---- Phase C: overlap checks ----

        // RCA-2a: containment -- entry X oldText contains entry Y oldText
        outer:
        for (int x = 0; x < oldTexts.size(); x++) {
            if (errors.size() >= MAX_ERRORS) break
            String ox = oldTexts.get(x)
            for (int y = 0; y < oldTexts.size(); y++) {
                if (x == y) continue
                String oy = oldTexts.get(y)
                if (ox && oy && ox.contains(oy)) {
                    errors << ('Entry ' + x + ' oldText contains entry ' + y +
                        ' oldText -- containment overlap. Fix: merge or use separate calls.')
                    break outer
                }
            }
        }

        // RCA-2b: suffix/prefix boundary overlap
        if (errors.isEmpty()) {
            outerBoundary:
            for (int i2 = 0; i2 < oldTexts.size(); i2++) {
                if (errors.size() >= MAX_ERRORS) break
                String ti = oldTexts.get(i2)
                if (ti.length() < 4) continue
                for (int j = 0; j < oldTexts.size(); j++) {
                    if (i2 == j) continue
                    String tj = oldTexts.get(j)
                    boolean foundOverlap = false
                    int maxLen = Math.min(ti.length(), tj.length())
                    // Only check overlaps of meaningful length (>=4 chars) to avoid
                    // single-char false positives (e.g. both strings end/start with 'a')
                    for (int olen = 4; olen < maxLen; olen++) {
                        if (ti.endsWith(tj.substring(0, olen))) {
                            errors << ('Entry ' + i2 + ' ends with text that starts entry ' + j +
                                ' -- boundary overlap. Fix: merge or use separate calls.')
                            foundOverlap = true
                            break
                        }
                        if (tj.endsWith(ti.substring(0, olen))) {
                            errors << ('Entry ' + j + ' ends with text that starts entry ' + i2 +
                                ' -- boundary overlap. Fix: merge or use separate calls.')
                            foundOverlap = true
                            break
                        }
                    }
                    if (foundOverlap) break outerBoundary
                }
            }
        }
        if (!errors.isEmpty()) return ValidationResult.ofErrors(errors)

        // ---- Phase D: simulation pass (RCA-2c) ----
        // Apply all replacements in order to a copy; detect entry-makes-entry-unfindable.
        String sim = snapshot
        for (int i = 0; i < replacements.size(); i++) {
            if (errors.size() >= MAX_ERRORS) break
            String ot = oldTexts.get(i)
            String nt = ((replacements.get(i).newText as String) ?: '').replace('\r\n', '\n').replace('\r', '\n')
            if (!sim.contains(ot)) {
                errors << ('multi_replace aborted: applying prior entries makes entry ' + i +
                    ' unfindable. Restructure or use separate calls.')
                break
            }
            sim = sim.replace(ot, nt)
        }
        if (!errors.isEmpty()) return ValidationResult.ofErrors(errors)

        return ValidationResult.ok(oldTexts, matchResults)
    }
}
