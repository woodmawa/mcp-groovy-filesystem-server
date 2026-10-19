package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

/**
 * StructuralGuard -- pre-write code integrity checks for .groovy/.java/.kt/.kts files.
 *
 * FS 0.9.0 / PR 1.3  Resolves D5 (dead post-write brace check eliminated) and D8
 * (non-code false positives on brace/paren counts).
 *
 * FS 0.9.6 / fix #142  Adds {@code allowStructuralEdit} bypass to
 * {@link #checkAll} so callers repairing an orphaned brace (caused by a prior
 * bad {@code action=append} on a code file) can suppress the brace/paren delta
 * reject without bypassing the box-drawing guard.
 * See also: FileWriteService {@code options.allowStructuralEdit},
 * {@code options.suppressCodeAppendWarning}.
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StructuralGuard)

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
        // FS 0.9.108 (F1, chain 43e2fdb5): the literal-aware counts decide whenever both snippets can be stripped.
        // Raw counts used to decide first, so a code brace swapped for one inside a string ('}' -> "}") matched on
        // raw counts and the strip never ran. Raw counts decide only when a snippet cannot be stripped.
        Boolean stripped = strippedDeltaVerdict(removedContent, newText, '{', '}')
        if (stripped != null) { if (stripped) return null }
        else if (removedDelta == newDelta) return null

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
        // FS 0.9.108 (F1): as checkBraceDelta -- stripped counts decide when both snippets can be stripped.
        Boolean strippedParen = strippedDeltaVerdict(removedContent, newText, '(', ')')
        if (strippedParen != null) { if (strippedParen) return null }
        else if (removedDelta == newDelta) return null

        // FS 0.9.62: the same literal/comment strip the brace check uses. Until now the paren check
        // stripped nothing ('too risky for triple-quote context'), so removing code that held a
        // string like 'recordPracticeUse(practices,' was refused (CS chain 2eb2555e) and the only way
        // through was allowStructuralEdit, which switches off the checks that are right as well.
        // The strip is now comment-aware, which is what made it safe to share (SG-L4).

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
    /**
     * Run all three guards in sequence.  Returns the first error encountered, or null.
     * ONLY runs guards for code files.  Non-code files always return null.
     *
     * <h3>allowStructuralEdit</h3>
     * When {@code true}, {@link #checkBraceDelta} and {@link #checkParenDelta} are
     * skipped (brace/paren mismatch is logged as WARN but does not block the write).
     * Use when intentionally repairing an orphaned brace left by a prior bad append.
     * {@link #checkBareBoxDrawing} is <em>never</em> bypassed -- it guards against
     * corrupted AI output and has no legitimate bypass case.
     *
     * @param removedContent    exact text being removed (for brace/paren delta)
     * @param newText           replacement text
     * @param updatedContent    full updated file content (for box-drawing check)
     * @param filePath          used to decide whether to apply the guard
     * @param allowStructuralEdit when true, skip brace and paren delta checks (FS 0.9.6)
     * @return                  error string if a guard fires, null if OK
     */
    static String checkAll(String removedContent, String newText,
                            String updatedContent, String filePath,
                            boolean allowStructuralEdit = false) {
        if (!isCodeFile(filePath)) return null
        if (!allowStructuralEdit) {
            String err = checkBraceDelta(removedContent, newText, filePath)
            if (err) return err
            err = checkParenDelta(removedContent, newText, filePath)
            if (err) return err
        } else {
            // Bypass brace/paren delta but log the mismatch so the deviation is observable.
            String braceDiag = checkBraceDelta(removedContent, newText, filePath)
            if (braceDiag) log.warn('StructuralGuard bypassed (allowStructuralEdit=true): {}', braceDiag)
        }
        return checkBareBoxDrawing(updatedContent, filePath)
    }

    // -----------------------------------------------------------------------
    // checkEdit -- the whole-file guard (FS 0.9.108)
    // -----------------------------------------------------------------------

    /**
     * FS 0.9.108 (Will, 2026-10-19; chain 43e2fdb5): judge an edit by the WHOLE FILE before and after it.
     *
     * <p>The snippet guard compared the delimiter counts of the removed text and the new text. Probed live on 0.9.107 it
     * let two corruptions through -- a code brace swapped for one inside a string, and a closing brace moved above its
     * opener -- and refused two correct edits: a Groovy slashy regex holding a brace, and an edit inside a SQL text
     * block whose snippet held no quote. {@link CodeDelimiterScanner} reads both files as code, and an edit is refused
     * when it changes a final brace or paren depth, or takes a depth below zero where the original did not. A file that
     * was already unbalanced is not blocked from an unrelated edit, only from getting worse.</p>
     *
     * <p>When either file cannot be lexed with confidence, the snippet checks decide, as before. allowStructuralEdit
     * skips the delimiter checks; the bare box-drawing check always runs.</p>
     */
    static String checkEdit(String original, String updated, String removedContent, String newText,
                            String filePath, boolean allowStructuralEdit) {
        if (!isCodeFile(filePath)) return null
        if (!allowStructuralEdit) {
            String err = wholeFileLexable(original, updated, filePath)
                ? wholeFileError(original, updated, filePath)
                : (checkBraceDelta(removedContent, newText, filePath) ?: checkParenDelta(removedContent, newText, filePath))
            if (err) return err
        } else {
            String diag = wholeFileLexable(original, updated, filePath) ? wholeFileError(original, updated, filePath)
                                                                         : checkBraceDelta(removedContent, newText, filePath)
            if (diag) log.warn('StructuralGuard bypassed (allowStructuralEdit=true): {}', diag)
        }
        return checkBareBoxDrawing(updated, filePath)
    }

    /** True when both files lex with confidence, so {@link #wholeFileError} can decide. */
    static boolean wholeFileLexable(String original, String updated, String filePath) {
        boolean groovy = isGroovy(filePath)
        return CodeDelimiterScanner.profile(original, groovy) != null && CodeDelimiterScanner.profile(updated, groovy) != null
    }

    /** The whole-file verdict: an error, or null. Call only when {@link #wholeFileLexable}. */
    static String wholeFileError(String original, String updated, String filePath) {
        boolean groovy = isGroovy(filePath)
        int[] a = CodeDelimiterScanner.profile(original, groovy)
        int[] b = CodeDelimiterScanner.profile(updated, groovy)
        if (a == null || b == null) return null
        String fname = filePath.tokenize('/\\').last()
        return depthVerdict('brace', '{', '}', a[CodeDelimiterScanner.BRACE_FINAL], a[CodeDelimiterScanner.BRACE_MIN],
                            b[CodeDelimiterScanner.BRACE_FINAL], b[CodeDelimiterScanner.BRACE_MIN], fname) ?:
               depthVerdict('paren', '(', ')', a[CodeDelimiterScanner.PAREN_FINAL], a[CodeDelimiterScanner.PAREN_MIN],
                            b[CodeDelimiterScanner.PAREN_FINAL], b[CodeDelimiterScanner.PAREN_MIN], fname)
    }

    private static String depthVerdict(String kind, String open, String close, int aFinal, int aMin,
                                       int bFinal, int bMin, String fname) {
        if (bFinal != aFinal) {
            return (kind + ' structure mismatch on ' + fname + ': this edit changes the file\'s net ' + kind + ' depth from ' +
                    aFinal + ' to ' + bFinal + ', counting only code (comments and strings excluded) -- a ' +
                    (bFinal < aFinal ? "'" + close + "' too many or an '" + open + "' lost" : "'" + open + "' too many or a '" + close + "' lost") +
                    '. Make the new text close every block it opens. File NOT modified.').toString()
        }
        if (bMin < 0 && bMin < aMin) {
            return (kind + ' structure mismatch on ' + fname + ': this edit closes a ' + kind + " before it is opened (a '" +
                    close + "' now comes ahead of its '" + open + "'; depth reaches " + bMin + ').' +
                    ' The net count is unchanged but the nesting is not. File NOT modified.').toString()
        }
        return null
    }

    private static boolean isGroovy(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith('.groovy')
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
     * Strip-then-recount check.
     *
     * Blanks string literals and comments in both removedContent and newText, then re-checks whether the
     * delta of the given open/close chars is balanced in what remains.
     *
     * Returns true if the stripped delta is balanced (suppress the error).
     * Returns false if it is still imbalanced, OR if either snippet could not be stripped with confidence
     * (fire the error on the raw counts).
     *
     * <p>FS 0.9.62: "could not be stripped with confidence" is the whole safety of sharing this with the
     * paren check. A patched range can START or END inside a literal -- CT-80 replaces the line that
     * closes a triple-quoted string opened three lines earlier. Read in isolation, that closing delimiter
     * looks like an opener, and blanking "everything after it" hides the very paren the patch drops. So a
     * snippet whose literals or block comments do not all close inside it is not stripped at all.</p>
     *
     * This is not a full lexer -- it misses slashy strings. Phase 4 will replace it with CodeDelimiterScanner.
     */
    /** FS 0.9.108: TRUE balanced, FALSE unbalanced, NULL when either snippet cannot be stripped with confidence. */
    private static Boolean strippedDeltaVerdict(String removed, String newText, String open, String close) {
        String strippedRemoved = stripLiteralsAndComments(removed)
        String strippedNew     = stripLiteralsAndComments(newText)
        if (strippedRemoved == null || strippedNew == null) return null
        int rDelta = strippedRemoved.count(open) - strippedRemoved.count(close)
        int nDelta = strippedNew.count(open)     - strippedNew.count(close)
        return Boolean.valueOf(rDelta == nDelta)
    }

    /**
     * Blank out string-literal content AND comments, in one left-to-right pass, so what remains is code.
     * Handles: '''...''', """...""", '...', "...", line comments and block comments (not slashy
     * strings). Blanked text becomes spaces, newlines are kept, so length and line structure survive.
     *
     * <p>FS 0.9.62: comments are handled in the SAME pass as strings, because each can contain the other's
     * opener. A string-only pass reads the apostrophe in {@code // don't} as an opening quote and blanks
     * the real code after it (SG-L4); a comment-first pass reads the {@code //} in {@code "http://x"} as a
     * comment and blanks the rest of the line (SG-L6). Whichever opener comes first wins.</p>
     *
     * @return the stripped text, or {@code null} when the snippet cannot be read with confidence: a
     *         triple-quoted string or block comment that does not close inside it, or a single-line
     *         string that meets a newline or the end first (the snippet began mid-literal, or a stray
     *         quote was misread as one). The caller then falls back to raw counts.
     */
    private static String stripLiteralsAndComments(String code) {
        if (!code) return code
        StringBuilder sb = new StringBuilder(code.length())
        int i = 0
        int len = code.length()
        while (i < len) {
            char c = code.charAt(i)
            // Comments, checked before strings at the same position: '//' or '/*' cannot start a string.
            if (c == '/' && i + 1 < len) {
                char n = code.charAt(i + 1)
                if (n == '/') {
                    while (i < len && code.charAt(i) != '\n') { sb.append(' '); i++ }
                    continue
                }
                if (n == '*') {
                    int end = code.indexOf('*/', i + 2)
                    if (end < 0) return null
                    int stop = end + 2
                    while (i < stop) { sb.append(code.charAt(i) == '\n' ? '\n' : ' '); i++ }
                    continue
                }
            }
            // Triple-quoted strings first (longer delimiter wins)
            if (i + 2 < len) {
                String triple = code.substring(i, i + 3)
                if (triple == "'''" || triple == '"""') {
                    String delim = triple
                    int end = code.indexOf(delim, i + 3)
                    if (end < 0) return null
                    sb.append(delim)
                    i += 3
                    while (i < end) { sb.append(code.charAt(i) == '\n' ? '\n' : ' '); i++ }
                    sb.append(delim)
                    i += 3
                    continue
                }
            }
            // Single-quoted or double-quoted: single-line in Groovy, so a newline before the close
            // means the snippet began mid-literal or a stray quote was misread -- not strippable.
            if (c == '\'' || c == '"') {
                sb.append(c)
                i++
                boolean closed = false
                while (i < len) {
                    char sc = code.charAt(i)
                    if (sc == '\\') {
                        sb.append(' '); sb.append(' ')
                        i += 2
                        continue
                    }
                    if (sc == '\n') return null
                    if (sc == c) { sb.append(sc); i++; closed = true; break }
                    sb.append(' ')
                    i++
                }
                if (!closed) return null
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
