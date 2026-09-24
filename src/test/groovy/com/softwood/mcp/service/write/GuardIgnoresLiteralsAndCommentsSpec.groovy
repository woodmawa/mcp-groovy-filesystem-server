package com.softwood.mcp.service.write

import spock.lang.Specification

/**
 * FS 0.9.62 -- the brace/paren guard counts CODE, not text inside string literals or comments.
 *
 * <p>Found live 2026-09-24 (CS chain 2eb2555e): retiring two spec methods whose code contained the string
 * literal {@code 'recordPracticeUse(practices,'} was refused as "paren structure mismatch" -- the unbalanced
 * {@code (} was inside quotes. The paren check never applied the string-strip the brace check has had since
 * PR 1.3 ("too risky for triple-quote context"), so every such edit needed {@code allowStructuralEdit=true},
 * which also switches off the checks that ARE right.</p>
 *
 * <p>The existing stripper was not safe to reuse as it stood: it knows strings but not comments, so an
 * apostrophe in {@code // don't} opens a phantom string that swallows the real code after it and hides a
 * genuine unclosed call. SG-L4 is that case; it passes today only because the paren check strips nothing,
 * and it is the case a naive fix would break. SG-L1 also asserts through {@code checkAll}, the entry point the
 * patch/replace services call -- the layer that decides.</p>
 */
class GuardIgnoresLiteralsAndCommentsSpec extends Specification {

    static final String F = 'C:/tmp/Example.groovy'

    def 'SG-L1: an unbalanced paren inside a single-quoted string is not structure (the live case)'() {
        given: 'a removed spec method whose only unbalanced paren is inside a string literal'
        String removed = "    def 'RR-2'() {\n        assert !block.contains('recordPracticeUse(practices,'),\n            'msg'\n        true\n    }\n"
        String replacement = '    // RR-2 retired\n'

        expect:
        StructuralGuard.checkParenDelta(removed, replacement, F) == null
        StructuralGuard.checkAll(removed, replacement, replacement, F) == null
    }

    def 'SG-L2: an unbalanced paren inside a line comment or block comment is not structure'() {
        expect:
        StructuralGuard.checkParenDelta('x = 1  // see (above\n', 'x = 1\n', F) == null
        StructuralGuard.checkParenDelta('/* note: (a */\nx = 1\n', 'x = 1\n', F) == null
    }

    def 'SG-L3: an unbalanced paren inside a triple-quoted string is not structure'() {
        expect:
        StructuralGuard.checkParenDelta("String sql = '''SELECT (a\n FROM t'''\n", 'String sql = null\n', F) == null
    }

    def 'SG-L4: CONTROL -- an apostrophe in a comment must not hide a real unclosed call after it'() {
        given: "a comment containing don't, then code that genuinely opens a call it never closes"
        String removed = "// don't touch\nfoo(\n"
        String replacement = '\n'

        expect: 'the real imbalance is still caught'
        StructuralGuard.checkParenDelta(removed, replacement, F) != null
    }

    def 'SG-L5: CONTROL -- a genuinely unclosed call in code is still refused'() {
        expect:
        StructuralGuard.checkParenDelta('foo(a, b)\n', 'foo(a, b\n', F) != null
        StructuralGuard.checkBraceDelta('if (x) {\n  y()\n}\n', 'if (x) {\n  y()\n', F) != null
    }

    def 'SG-L6: a URL in a string is not mistaken for a comment that hides the rest of the line'() {
        expect: 'the // inside the string is text; the unclosed call after it is real'
        StructuralGuard.checkParenDelta('call("http://x")\n', 'call("http://x"\n', F) != null
    }

    def 'SG-L8: CONTROL -- a range that starts INSIDE a multi-line string is not stripped (the CT-80 shape)'() {
        given: 'the patched line closes a triple-quoted string opened on an earlier line, then the call'
        String removed = '            WHERE id = ?""")' + '\n'
        String replacement = '            WHERE id = ?"""' + '\n'

        expect: 'the stripper cannot know the quote state, so the real dropped ) is still caught'
        StructuralGuard.checkParenDelta(removed, replacement, F) != null
    }

    def 'SG-L7: braces inside a comment are not structure either'() {
        expect:
        StructuralGuard.checkBraceDelta('x = 1 // was: if (y) {\n', 'x = 1\n', F) == null
    }
}
