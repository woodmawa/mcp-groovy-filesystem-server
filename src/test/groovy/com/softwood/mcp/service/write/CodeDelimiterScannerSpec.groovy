package com.softwood.mcp.service.write

import spock.lang.Specification
import spock.lang.Unroll

/**
 * FS 0.9.108 (chain 43e2fdb5): CodeDelimiterScanner counts only code delimiters.
 * profile() = [braceFinal, braceMin, parenFinal, parenMin], or null when the text cannot be lexed with confidence.
 */
class CodeDelimiterScannerSpec extends Specification {

    private static int[] p(String s, boolean groovy = true) { CodeDelimiterScanner.profile(s, groovy) }

    @Unroll
    def "CDS-1 delimiters inside literals and comments are not counted: #label"() {
        expect:
        p(text) as List == [0, 0, 0, 0]

        where:
        label                   | text
        'double-quoted'         | 'def a = "}{)("\n'
        'single-quoted'         | "def a = '}{)('\n"
        'char escape'           | "def a = '\\'}'\n"
        'line comment'          | 'def a = 1 // } ) {\n'
        'block comment'         | 'def a = 1 /* }\n ) */\n'
        'triple double'         | 'def a = """\n }\n ) \n"""\n'
        'triple single'         | "def a = '''\n }\n'''\n"
        'gstring interpolation' | 'def a = "x ${m("}")} y"\n'
        'slashy regex'          | 'def p = ~/\\{/\n'
        'slashy with escape'    | 'def p = ~/a\\/}/\n'
        'dollar slashy'         | 'def p = $/ } ) /$\n'
    }

    def "CDS-2 division is division, not a slashy string"() {
        expect:
        p('int a = (b / c) / (d / 2)\nif (x) { y = a / 2 }\n') as List == [0, 0, 0, 0]
    }

    def "CDS-3 nesting order is tracked: a brace closed before it opens reaches -1"() {
        expect:
        p('}\n{\n')[CodeDelimiterScanner.BRACE_MIN] == -1
        p('}\n{\n')[CodeDelimiterScanner.BRACE_FINAL] == 0
    }

    def "CDS-4 real code delimiters are counted"() {
        expect:
        p('class A {\n  void m() {\n') as List == [2, 0, 0, 0]
        p('foo(bar(\n') as List == [0, 0, 2, 0]
    }

    @Unroll
    def "CDS-5 unterminated literal cannot be lexed: #label"() {
        expect:
        p(text) == null

        where:
        label            | text
        'block comment'  | 'def a = 1 /* never closed\n'
        'triple string'  | 'def a = """\nnever closed\n'
        'single string'  | 'def a = "never closed\n'
    }

    def "CDS-6 slashy syntax is Groovy only: in Java a slash after an operator is not a string"() {
        expect:
        p('int a = b /c;\nint d = (e);\n', false) as List == [0, 0, 0, 0]
    }
}
