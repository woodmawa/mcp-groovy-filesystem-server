package com.softwood.mcp.service.write

import groovy.transform.CompileStatic

/**
 * FS 0.9.108 (Will, 2026-10-19; chain 43e2fdb5) -- the lexer StructuralGuard's class comment deferred to "Phase 4".
 *
 * <p>Reads a whole source file and reports, for braces and for parens, the depth it ends at and the lowest depth it
 * reaches, counting only delimiters that are CODE. Skipped as text: line and block comments; '...' and "..." strings
 * (a GString's <code>${...}</code> is followed to its matching close, nested quotes included); ''' and """ text blocks;
 * Java/Kotlin char literals (they are single-quoted); and, in Groovy only, slashy /.../ and dollar-slashy $/.../$
 * strings.</p>
 *
 * <p>Slashy versus division is decided the way the Groovy grammar does it in practice: a slash starts a string after an
 * operator, an opening bracket, a comma, the start of a line or a keyword such as return; after an identifier, a number,
 * a closing bracket or a string it is division.</p>
 *
 * <p>Answers null when it cannot read the text with confidence -- a comment, text block, string or slashy string that
 * never closes, or a single-line string that meets a newline. The caller then falls back to the snippet check.
 * Pinned by CodeDelimiterScannerSpec and StructuralGuardWholeFileSpec.</p>
 */
@CompileStatic
final class CodeDelimiterScanner {

    /** Index of each value in {@link #profile}'s answer. */
    static final int BRACE_FINAL = 0, BRACE_MIN = 1, PAREN_FINAL = 2, PAREN_MIN = 3

    /** Words after which a slash opens a slashy string rather than dividing. */
    private static final Set<String> SLASHY_AFTER_WORDS = (['return', 'case', 'assert', 'in', 'yield', 'throw'] as Set<String>).asImmutable()

    private CodeDelimiterScanner() {}

    /**
     * [braceFinal, braceMin, parenFinal, parenMin] over the code of {@code text}, or null when it cannot be lexed.
     * @param groovySyntax true for .groovy: slashy and dollar-slashy strings exist
     */
    static int[] profile(String text, boolean groovySyntax) {
        if (text == null) return null
        int braceDepth = 0, braceMin = 0, parenDepth = 0, parenMin = 0
        int len = text.length()
        int i = 0
        char prevSig = (char) '\n'       // last significant code character; newline = a statement may start
        String prevWord = ''
        while (i < len) {
            char c = text.charAt(i)
            if (c == ('/' as char) && i + 1 < len) {
                char n = text.charAt(i + 1)
                if (n == ('/' as char)) {
                    int e = text.indexOf('\n', i)
                    i = e < 0 ? len : e
                    continue
                }
                if (n == ('*' as char)) {
                    int e = text.indexOf('*/', i + 2)
                    if (e < 0) return null
                    i = e + 2
                    continue
                }
                if (groovySyntax && slashyMayStart(prevSig, prevWord)) {
                    int e = endOfSlashy(text, i + 1)
                    if (e < 0) return null
                    i = e
                    prevSig = (char) '"'; prevWord = ''
                    continue
                }
            }
            if (groovySyntax && c == ('$' as char) && i + 1 < len && text.charAt(i + 1) == ('/' as char)) {
                int e = endOfDollarSlashy(text, i + 2)
                if (e < 0) return null
                i = e
                prevSig = (char) '"'; prevWord = ''
                continue
            }
            if (c == ('"' as char) || c == ('\'' as char)) {
                boolean triple = i + 2 < len && text.charAt(i + 1) == c && text.charAt(i + 2) == c
                int e = triple ? endOfTriple(text, i + 3, c) : endOfSingle(text, i + 1, c)
                if (e < 0) return null
                i = e
                prevSig = (char) '"'; prevWord = ''
                continue
            }
            if (Character.isJavaIdentifierStart(c)) {
                int s = i
                while (i < len && Character.isJavaIdentifierPart(text.charAt(i))) i++
                prevWord = text.substring(s, i)
                prevSig = text.charAt(i - 1)
                continue
            }
            if (Character.isDigit(c)) {
                while (i < len && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == ('.' as char) ||
                                   text.charAt(i) == ('_' as char))) i++
                prevSig = (char) '0'; prevWord = ''
                continue
            }
            if (c == ('{' as char)) braceDepth++
            else if (c == ('}' as char)) { braceDepth--; if (braceDepth < braceMin) braceMin = braceDepth }
            else if (c == ('(' as char)) parenDepth++
            else if (c == (')' as char)) { parenDepth--; if (parenDepth < parenMin) parenMin = parenDepth }
            if (c == ('\n' as char)) { prevSig = c; prevWord = '' }
            else if (!Character.isWhitespace(c)) { prevSig = c; prevWord = '' }
            i++
        }
        return [braceDepth, braceMin, parenDepth, parenMin] as int[]
    }

    /** A slash opens a slashy string unless what precedes it can be divided. */
    private static boolean slashyMayStart(char prevSig, String prevWord) {
        if (prevWord && SLASHY_AFTER_WORDS.contains(prevWord)) return true
        if (Character.isJavaIdentifierPart(prevSig)) return false
        return !(prevSig == (')' as char) || prevSig == (']' as char) || prevSig == ('}' as char) ||
                 prevSig == ('"' as char) || prevSig == ('.' as char))
    }

    /** Index just past the closing slash of a slashy string whose body starts at {@code i}, or -1. */
    private static int endOfSlashy(String t, int i) {
        int len = t.length()
        while (i < len) {
            char c = t.charAt(i)
            if (c == ('\\' as char) && i + 1 < len && t.charAt(i + 1) == ('/' as char)) { i += 2; continue }
            if (c == ('/' as char)) return i + 1
            i++
        }
        return -1
    }

    /** Index just past the closing /$ of a dollar-slashy string whose body starts at {@code i}, or -1. */
    private static int endOfDollarSlashy(String t, int i) {
        int len = t.length()
        while (i < len) {
            if (t.startsWith('$$', i) || t.startsWith('$/', i)) { i += 2; continue }
            if (t.startsWith('/$', i)) return i + 2
            i++
        }
        return -1
    }

    /** Index just past the closing quote of a single-line string whose body starts at {@code i}, or -1. */
    private static int endOfSingle(String t, int i, char q) {
        int len = t.length()
        while (i < len) {
            char c = t.charAt(i)
            if (c == ('\\' as char)) { i += 2; continue }
            if (c == ('\n' as char)) return -1
            if (c == q) return i + 1
            if (q == ('"' as char) && c == ('$' as char) && i + 1 < len && t.charAt(i + 1) == ('{' as char)) {
                i = skipInterpolation(t, i + 2)
                if (i < 0) return -1
                continue
            }
            i++
        }
        return -1
    }

    /** Index just past the closing triple quote of a text block whose body starts at {@code i}, or -1. */
    private static int endOfTriple(String t, int i, char q) {
        int len = t.length()
        while (i < len) {
            char c = t.charAt(i)
            if (c == ('\\' as char)) { i += 2; continue }
            if (c == q && i + 2 < len && t.charAt(i + 1) == q && t.charAt(i + 2) == q) {
                // A text block may end in its own quote character: """a"""" ends at the LAST three.
                int e = i + 3
                while (e < len && t.charAt(e) == q) e++
                return e
            }
            if (q == ('"' as char) && c == ('$' as char) && i + 1 < len && t.charAt(i + 1) == ('{' as char)) {
                i = skipInterpolation(t, i + 2)
                if (i < 0) return -1
                continue
            }
            i++
        }
        return -1
    }

    /** Index just past the } closing a GString interpolation whose body starts at {@code i}, or -1. */
    private static int skipInterpolation(String t, int i) {
        int len = t.length()
        int depth = 1
        while (i < len) {
            char c = t.charAt(i)
            if (c == ('"' as char) || c == ('\'' as char)) {
                int e = endOfSingle(t, i + 1, c)
                if (e < 0) return -1
                i = e
                continue
            }
            if (c == ('{' as char)) depth++
            else if (c == ('}' as char)) { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return -1
    }
}
