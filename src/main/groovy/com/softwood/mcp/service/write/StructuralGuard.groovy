package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

/**
 * StructuralGuard -- pre-write code integrity checks for .groovy/.java/.kt/.kts files.
 *
 * FS 0.9.0 / PR 1.3  Resolves D5 (dead post-write brace check eliminated) and D8
 * (non-code false positives on brace/paren counts).
 *
 * Design rules:
 *  - ALL guards are PRE-WRITE hard rejects. No advisory warnings are ever returned
 *    from this class. If a guard fires, the file is NOT written.
 *  - Guards only apply to code files (.groovy, .java, .kt, .kts). All other file
 *    types (.md, .sql, .yml, .txt etc.) are silently passed through.
 *  - The conservative string-strip heuristic suppresses false positives from SQL
 *    text blocks and GString literals before firing the brace/paren error. It is not
 *    a full lexer -- it will miss some edge cases (slashy strings, nested quotes) but
 *    eliminates the most common false positive patterns. The full CodeDelimiterScanner
 *    lexer is deferred to Phase 4.
 *  - No brace_warning field is ever included in any MCP response after this refactor.
 *    If check fires: toolError, file unchanged. If check passes: silent success.
 */
@CompileStatic
final class StructuralGuard {

    // -----------------------------------------------------------------------
    // checkBraceDelta
    // -----------------------------------------------------------------------

    /**
     * For .groovy/.java/.kt/.kts: verify that the brace delta of removedContent
     * equals the brace delta of newText (CT-14/CT-15).
     *
     * A mismatch means the replacement structurally corrupts the file -- e.g. removing
     * 'if (x) {\n  body' (net +1 open) and replacing with flat content (delta 0) orphans
     * the closing brace in the surrounding scope.
     *
     * Uses the conservative string-strip heuristic before firing to suppress false
     * positives from SQL text blocks and multi-line string literals.
     *
     * @param removedContent  exact text being removed (LF-normalised)
     * @param newText         replacement text (LF-normalised)
     * @param filePath        used to decide whether to apply the guard
     * @return                error string if guard fires, null if OK
     */
    static String checkBraceDelta(String removedContent, String newText, String filePath) {
        if (!isCodeFile(filePath)) return null
        int removedOpen  = removedContent.count('{')
        int removedClose = removedContent.count('}')
        int newOpen      = newText.count('{')
        int newClose     = newText.count('}')
        int removedDelta = removedOpen  - removedClose
        int newDelta     = newOpen      - newClose
        if (removedDelta == newDelta) return null

        // Conservative string-strip: remove string literal content before re-checking.
        // Prevents false positives on SQL WHERE clauses, GString expressions etc.
        if (strippedDeltaBalanced(removedContent, newText, '{', '}')) return null

        String fname = filePath.tokenize('/\\').last()
        return ('brace structure mismatch on ' + fname + ': ' +
            'removed section has brace delta ' + removedDelta +
            ' (opens=' + removedOpen + ' closes=' + removedClose + ')' +
            ' but newText has brace delta ' + newDelta +
            ' (opens=' + newOpen + ' closes=' + newClose + '). ' +
            'Extend the replacement range to include all closing braces for blocks it opens, ' +
            'or ensure newText closes every block it opens. File NOT modified.')
    }

    // -----------------------------------------------------------------------
    // checkParenDelta
    // -----------------------------------------------------------------------

    /**
     * For .groovy/.java/.kt/.kts: verify paren delta (CT-80/CT-81).
     *
     * Only fires when |delta difference| >= 1. String-strip heuristic applied
     * before firing to suppress SQL-literal false positives.
     *
     * @param removedContent  exact text being removed
     * @param newText         replacement text
     * @param filePath        used to decide whether to apply the guard
     * @return                error string if guard fires, null if OK
     */
    static String checkParenDelta(String removedContent, String newText, String filePath) {
        if (!isCodeFile(filePath)) return null
        int removedOpen  = removedContent.count('(')
        int removedClose = removedContent.count(')')
        int newOpen      = newText.count('(')
        int newClose     = newText.count(')')
        int removedDelta = removedOpen  - removedClose
        int newDelta     = newOpen      - newClose
        if (removedDelta == newDelta) return null
        int diff = removedDelta - newDelta
        if (Math.abs(diff) < 1) return null

        // String-strip heuristic NOT applied to paren checks -- too risky for
        // triple-quote context spanning multiple lines (Phase 4: CodeDelimiterScanner).
        // The paren guard fires whenever |delta| >= 1 on code files.
        String fname = filePath.tokenize('/\\').last()
        return ('paren structure mismatch on ' + fname + ': ' +
            'removed section has paren delta ' + removedDelta +
            ' (opens=' + removedOpen + ' closes=' + removedClose + ')' +
            ' but newText has paren delta ' + newDelta +
            ' (opens=' + newOpen + ' closes=' + newClose + '). ' +
            'Ensure newText closes every method call or GString it opens. File NOT modified.')
    }

    // -----------------------------------------------------------------------
    // checkBareBoxDrawing
    // -----------------------------------------------------------------------

    /**
     * For .groovy/.java/.kt/.kts: block writes that would produce lines starting
     * with bare U+2500..U+257F box-drawing characters (FS-T10 / CT-66b/68/69).
     *
     * Checks the entire updated content (after replacement is applied).
     * Section dividers must be inside // comments.
     *
     * @param updatedContent  full file content after replacement (LF-normalised)
     * @param filePath        used to decide whether to apply the guard
     * @return                error string if guard fires, null if OK
     */
    static String checkBareBoxDrawing(String updatedContent, String filePath) {
        if (!isCodeFile(filePath)) return null
        String[] lines = updatedContent.split('\n', -1)
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i]
            int firstNonWs = -1
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j)
                if (c != ' ' && c != '\t') { firstNonWs = j; break }
            }
            if (firstNonWs < 0) continue
            int cp = line.codePointAt(firstNonWs)
            if (cp >= 0x2500 && cp <= 0x257F) {
                return ('Line ' + (i + 1) + ' starts with bare box-drawing character U+' +
                    Integer.toHexString(cp).toUpperCase(Locale.ROOT).padLeft(4, '0') +
                    '. Section dividers must be inside // comments ' +
                    '(e.g. "// \u2500\u2500 Section "). ' +
                    'RECOVERY: ensure newText includes "// " prefix before \u2500 characters. ' +
                    '[bare_box_drawing_hint]')
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // checkAll -- convenience entry point
    // -----------------------------------------------------------------------

    /**
     * Run all three guards in sequence. Returns the first error encountered, or null.
     * ONLY runs guards for code files. Non-code files always return null.
     *
     * @param removedContent  exact text being removed (for brace/paren delta)
     * @param newText         replacement text
     * @param updatedContent  full updated file content (for bare-box check)
     * @param filePath        used to decide whether to apply guards
     * @return                first error string, or null if all guards pass
     */
    static String checkAll(String removedContent, String newText,
                            String updatedContent, String filePath) {
        if (!isCodeFile(filePath)) return null
        String err = checkBraceDelta(removedContent, newText, filePath)
        if (err) return err
        err = checkParenDelta(removedContent, newText, filePath)
        if (err) return err
        return checkBareBoxDrawing(updatedContent, filePath)
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    static boolean isCodeFile(String path) {
        if (!path) return false
        String lower = path.toLowerCase(Locale.ROOT)
        lower.endsWith('.groovy') || lower.endsWith('.java') ||
        lower.endsWith('.kt')     || lower.endsWith('.kts')
    }

    /**
     * Conservative string-strip heuristic.
     *
     * Strips content between string delimiters (single-quoted, double-quoted,
     * triple-quoted) from both removedContent and newText, then re-checks whether
     * the delta of the given open/close chars is balanced in the stripped versions.
     *
     * Returns true if stripped delta is balanced (suppress the error).
     * Returns false if stripped delta is still imbalanced (fire the error).
     *
     * This is not a full lexer -- it misses slashy strings and some nested quote
     * patterns. Phase 4 will replace this with CodeDelimiterScanner.
     */
    private static boolean strippedDeltaBalanced(String removed, String newText,
                                                  String open, String close) {
        String strippedRemoved = stripStringLiterals(removed)
        String strippedNew     = stripStringLiterals(newText)
        int rDelta = strippedRemoved.count(open) - strippedRemoved.count(close)
        int nDelta = strippedNew.count(open)     - strippedNew.count(close)
        return rDelta == nDelta
    }

    /**
     * Remove string literal content (between quotes) from a code snippet.
     * Handles: '''...''', """...""", '...', "..." (not slashy strings).
     * Replaces literal content with spaces to preserve string length and line structure.
     */
    private static String stripStringLiterals(String code) {
        if (!code) return code
        StringBuilder sb = new StringBuilder(code.length())
        int i = 0
        int len = code.length()
        while (i < len) {
            char c = code.charAt(i)
            // Triple-quoted strings first (longer delimiter wins)
            if (i + 2 < len) {
                String triple = code.substring(i, i + 3)
                if (triple == "'''" || triple == '"""') {
                    String delim = triple
                    sb.append(delim)
                    i += 3
                    int end = code.indexOf(delim, i)
                    if (end < 0) {
                        // Unclosed -- consume to end, replacing with spaces
                        while (i < len) { sb.append(' '); i++ }
                    } else {
                        while (i < end) { sb.append(code.charAt(i) == '\n' ? '\n' : ' '); i++ }
                        sb.append(delim)
                        i += 3
                    }
                    continue
                }
            }
            // Single-quoted or double-quoted
            if (c == '\'' || c == '"') {
                sb.append(c)
                i++
                while (i < len) {
                    char sc = code.charAt(i)
                    if (sc == '\\') {
                        sb.append(' '); sb.append(' ')
                        i += 2
                        continue
                    }
                    if (sc == c) { sb.append(sc); i++; break }
                    sb.append(sc == '\n' ? '\n' : ' ')
                    i++
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
