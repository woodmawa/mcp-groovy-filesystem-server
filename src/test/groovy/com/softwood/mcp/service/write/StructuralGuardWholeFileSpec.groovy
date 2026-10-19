package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileWriteService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Tag
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path

/**
 * FS 0.9.108 (Will, 2026-10-19 14:28; chain 43e2fdb5): the brace/paren guard judges the WHOLE FILE.
 *
 * <p>Will asked whether the brace calculation is structurally correct. Probed live on 0.9.107 (obs 12961): the guard
 * compared the RAW brace counts of the removed text and the new text, consulted its literal-aware strip only when those
 * disagreed, and never looked at the file. Two corruptions passed and one correct edit was refused:</p>
 * <ul>
 *   <li>F1 -- a real closing brace swapped for one inside a string: raw counts equal, so the strip never ran.</li>
 *   <li>F2 -- a closing brace moved above its opener: net counts equal; order was never checked.</li>
 *   <li>F3 -- a Groovy slashy regex containing a brace: the stripper did not know slashy strings.</li>
 * </ul>
 * <p>The guard now lexes the original and the updated file (comments, quotes, triple quotes, GStrings, Java char
 * literals, Groovy slashy and dollar-slashy strings) and refuses an edit that changes a final depth or takes the depth
 * below zero where the original did not. Asserted through file_write -- the layer that decides -- and on the file on
 * disk.</p>
 *
 * WF-1  replace: a real } swapped for one inside a string is refused, file untouched (RED on 0.9.107)
 * WF-2  replace: a closing brace moved above its opener is refused (RED)
 * WF-3  replace: a slashy regex holding a { is allowed (RED: refused)
 * WF-4  patch: F1 through the line-range path is refused (RED)
 * WF-5  multi_replace: F2 through the multi path is refused (RED)
 * WF-6  CONTROL: a genuinely orphaned brace is refused
 * WF-7  CONTROL: a brace inside an ordinary string is allowed
 * WF-8  replace: an edit inside a SQL text block is allowed (RED on 0.9.107: refused -- a fourth defect, found by this
 *       spec: a snippet with no quote characters had its string content read as code)
 * WF-9  CONTROL: a file already unbalanced still takes an unrelated edit (never made worse, never blocked)
 * WF-10 CONTROL: allowStructuralEdit still lets a deliberate repair through
 */
@Title('FS 0.9.108 -- the brace/paren guard judges the whole file')
@Tag('integration')
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class StructuralGuardWholeFileSpec extends Specification {

    @Autowired FileWriteService fileWriteService

    @TempDir Path tempDir

    static final String BASE = '''class A {
    void m() {
        if (x) {
            y()
        }
    }
}
'''

    private Map setupFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [action: 'write', path: f.absolutePath, content: text], 'setup')
        Map parsed = new JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: parsed.file_content_hash as String, file: f]
    }

    private McpResponse replace(Map f, String oldText, String newText, Map extra = [:]) {
        fileWriteService.handleToolCall('file_write', [action: 'replace', path: f.path,
            options: ([oldText: oldText, newText: newText, expectedHash: f.hash] + extra)], 'test')
    }

    private static boolean refused(McpResponse r) { r.result?.isError == true }

    def 'WF-1: a real closing brace swapped for one inside a string is refused'() {
        given:
        def f = setupFile('F1.groovy', BASE)

        when:
        McpResponse r = replace(f, '            y()\n        }', '            y()\n            log.info("}")')

        then:
        refused(r)
        (f.file as File).text == BASE
    }

    // A brace moved above its opener INSIDE a nesting still balances (method m closes early, the if-block balances
    // against the next brace) -- delimiter-wise that is a method split, which WF-11 allows. What the delimiters can
    // catch is a brace closed before anything opens it: depth below zero where the original never went.
    static final String TOP = 'void m() {\n    y()\n}\n'

    def 'WF-2: a closing brace moved above its opener is refused'() {
        given:
        def f = setupFile('F2.groovy', TOP)

        when:
        McpResponse r = replace(f, 'void m() {\n    y()\n}', '}\nvoid m() {\n    y()')

        then:
        refused(r)
        (f.file as File).text == TOP
    }

    def 'WF-11 (control): splitting a method -- a balanced re-nesting -- is allowed'() {
        given:
        def f = setupFile('F11.groovy', 'class S {\n    void m() {\n        a()\n        b()\n    }\n}\n')

        when:
        McpResponse r = replace(f, '        a()\n        b()', '        a()\n    }\n    void n() {\n        b()')

        then:
        !refused(r)
    }

    def 'WF-3: a slashy regex holding a brace is allowed'() {
        given:
        def f = setupFile('F3.groovy', 'class P {\n    int p = 1\n}\n')

        when:
        McpResponse r = replace(f, '    int p = 1', '    def p = ~/\\{/')

        then:
        !refused(r)
        (f.file as File).text == 'class P {\n    def p = ~/\\{/\n}\n'
    }

    def 'WF-4: the same F1 edit through patch is refused'() {
        given:
        def f = setupFile('F4.groovy', BASE)

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [action: 'patch', path: f.path, options: [
            expectedHash: f.hash, replacements: [[startLine: 5, endLine: 5, newText: '            log.info("}")']]]], 'test')

        then:
        refused(r)
        (f.file as File).text == BASE
    }

    def 'WF-5: the F2 edit through multi_replace is refused'() {
        given:
        def f = setupFile('F5.groovy', TOP)

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [action: 'multi_replace', path: f.path, options: [
            expectedHash: f.hash, replacements: [[oldText: 'void m() {\n    y()\n}',
                                                  newText: '}\nvoid m() {\n    y()']]]], 'test')

        then:
        refused(r)
        (f.file as File).text == TOP
    }

    def 'WF-6: CONTROL -- a genuinely orphaned brace is refused'() {
        given:
        def f = setupFile('C1.groovy', BASE)

        when:
        McpResponse r = replace(f, '        if (x) {\n            y()\n        }', '            y()\n        }')

        then:
        refused(r)
    }

    def 'WF-7: CONTROL -- a brace inside an ordinary string is allowed'() {
        given:
        def f = setupFile('C2.groovy', BASE)

        when:
        McpResponse r = replace(f, '            y()', '            log.info("{")')

        then:
        !refused(r)
    }

    def 'WF-8: an edit inside a SQL text block is allowed'() {
        given:
        String src = 'class Q {\n    String s = """\n        SELECT x FROM t WHERE a IN (1, 2)\n    """\n}\n'
        def f = setupFile('C3.groovy', src)

        when:
        McpResponse r = replace(f, 'WHERE a IN (1, 2)', 'WHERE a = 1 AND (b')

        then: 'the unbalanced paren is inside the string, so the code is untouched'
        !refused(r)
    }

    def 'WF-9: CONTROL -- an already unbalanced file still takes an unrelated edit'() {
        given:
        String broken = 'class B {\n    void m() {\n        int a = 1\n    }\n'
        def f = setupFile('C4.groovy', broken)

        when:
        McpResponse r = replace(f, '        int a = 1', '        int a = 2')

        then:
        !refused(r)
    }

    def 'WF-10: CONTROL -- allowStructuralEdit lets a deliberate repair through'() {
        given:
        def f = setupFile('C5.groovy', BASE + '// STRAY\n}\n')

        when: 'oldText is unique (obs: the first fixture matched twice and replace refused it as non-unique)'
        McpResponse r = replace(f, '// STRAY\n}\n', '// STRAY\n', [allowStructuralEdit: true])

        then:
        !refused(r)
    }
}
