package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title
import spock.lang.Unroll

import java.nio.file.Path
import java.security.MessageDigest

/**
 * B5 (optimised-learning ARC-STATE; Will 2026-10-19: "LF everywhere"). The three edit paths disagreed on
 * the same CRLF file: replace and patch write LF for WriteUtils.LF_EXTENSIONS (WriteContext.toBytes and
 * FilePatchService), while multi_replace restored CRLF unconditionally. One policy now: a CRLF file of an
 * LF extension is written LF by every edit; any other CRLF file keeps CRLF.
 *
 * Through handleToolCall on the Spring fixture; asserted on the bytes on disk.
 *
 * LE-1: replace / multi_replace / patch on a CRLF .groovy file leave no CR (multi_replace RED on 0.9.102)
 * LE-2: CONTROL -- replace / multi_replace / patch on a CRLF .bat file keep CRLF on every line
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('file_write -- one line-ending policy for replace, multi_replace and patch')
class LineEndingPolicySpec extends Specification {

    @Autowired FileWriteService writeService

    @TempDir Path dir

    static final String CRLF_BODY = 'class A {\r\n    int one = 1\r\n    int two = 2\r\n}\r\n'

    private static String hash12(Path p) {
        MessageDigest.getInstance('SHA-256').digest(p.bytes).encodeHex().toString().take(12)
    }

    private String call(String action, Path p, Map options) {
        McpResponse r = writeService.handleToolCall('file_write',
            [action: action, path: p.toString(), options: options + [expectedHash: hash12(p)]] as Map<String, Object>, 'le')
        def content = r?.result?.content
        return content ? (content[0].text as String) : (r?.error?.toString() ?: '')
    }

    private static Map optionsFor(String action) {
        switch (action) {
            case 'replace'       : return [oldText: 'int one = 1', newText: 'int one = 11']
            case 'multi_replace' : return [replacements: [[oldText: 'int one = 1', newText: 'int one = 11'],
                                                          [oldText: 'int two = 2', newText: 'int two = 22']]]
            case 'patch'         : return [replacements: [[startLine: 2, endLine: 2, newText: '    int one = 11']]]
        }
        throw new IllegalArgumentException(action)
    }

    @Unroll
    def 'LE-1: #action on a CRLF .groovy file writes LF throughout'() {
        given:
        Path f = dir.resolve("A_${action}.groovy"); f.bytes = CRLF_BODY.getBytes('UTF-8')

        when:
        String out = call(action, f, optionsFor(action))

        then:
        out.contains('"success":true')
        f.text.contains('int one = 11')
        !f.text.contains('\r')

        where:
        action << ['replace', 'multi_replace', 'patch']
    }

    @Unroll
    def 'LE-2: CONTROL -- #action on a CRLF .bat file keeps CRLF on every line'() {
        given:
        Path f = dir.resolve("a_${action}.bat")
        f.bytes = 'rem int one = 1\r\nrem int two = 2\r\necho done\r\n'.getBytes('UTF-8')
        Map opts = action == 'patch' ? [replacements: [[startLine: 1, endLine: 1, newText: 'rem int one = 11']]]
                 : action == 'replace' ? [oldText: 'int one = 1', newText: 'int one = 11']
                 : [replacements: [[oldText: 'int one = 1', newText: 'int one = 11'], [oldText: 'int two = 2', newText: 'int two = 22']]]

        when:
        String out = call(action, f, opts)

        then:
        out.contains('"success":true')
        f.text.contains('int one = 11')
        f.text.split('\n', -1).dropRight(1).every { String line -> line.endsWith('\r') }

        where:
        action << ['replace', 'multi_replace', 'patch']
    }
}
