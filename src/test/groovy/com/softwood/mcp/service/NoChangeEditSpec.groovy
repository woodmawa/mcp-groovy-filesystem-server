package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path
import java.security.MessageDigest

/**
 * FS 0.9.55 NC -- an edit that changes nothing is refused, not reported as success.
 *
 * Twice on 2026-09-22 a replace whose newText had been pasted over from oldText came back success:true with
 * an unchanged content_hash, and the caller believed a fix had landed. multi_replace also counted an entry
 * it had skipped ('became unfindable -- skipping') as applied. Same class as the empty append (0.9.48) and
 * the bash truncation (0.9.50): success over nothing.
 *
 * Through handleToolCall on the Spring fixture; asserted on the bytes on disk as well as the response.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FileReplaceService -- NC no change is not success')
class NoChangeEditSpec extends Specification {

    @Autowired FileWriteService writeService

    @TempDir Path dir

    private static String hash12(Path p) {
        MessageDigest.getInstance('SHA-256').digest(p.bytes).encodeHex().toString().take(12)
    }

    private String call(String action, Path p, Map options) {
        McpResponse r = writeService.handleToolCall('file_write',
            [action: action, path: p.toString(), options: options + [expectedHash: hash12(p)]] as Map<String, Object>, 'nc')
        def content = r?.result?.content
        return content ? (content[0].text as String) : (r?.error?.toString() ?: '')
    }

    def 'NC-1: replace with newText identical to oldText is refused and the file is untouched'() {
        given:
        Path f = dir.resolve('a.txt'); f.text = 'alpha\nbeta\n'
        String before = hash12(f)

        when:
        String out = call('replace', f, [oldText: 'beta', newText: 'beta'])

        then:
        out.contains('newText is identical to oldText')
        !out.contains('"success":true')
        hash12(f) == before
    }

    def 'NC-2 (control): a real replace still succeeds'() {
        given:
        Path f = dir.resolve('b.txt'); f.text = 'alpha\nbeta\n'

        when:
        String out = call('replace', f, [oldText: 'beta', newText: 'gamma'])

        then:
        out.contains('"success":true')
        f.text == 'alpha\ngamma\n'
    }

    def 'NC-3: multi_replace where every pair is identical is refused and the file is untouched'() {
        given:
        Path f = dir.resolve('c.txt'); f.text = 'one\ntwo\n'
        String before = hash12(f)

        when:
        String out = call('multi_replace', f, [replacements: [[oldText: 'one', newText: 'one'], [oldText: 'two', newText: 'two']]])

        then:
        out.contains('no replacement changed the file')
        hash12(f) == before
    }

    def 'NC-4: multi_replace with one real and one identical pair reports applied 1, skipped 1'() {
        given:
        Path f = dir.resolve('d.txt'); f.text = 'one\ntwo\n'

        when:
        String out = call('multi_replace', f, [replacements: [[oldText: 'one', newText: 'ONE'], [oldText: 'two', newText: 'two']], verbose: true])

        then:
        out.contains('"applied":1')
        out.contains('"skipped":1')
        f.text == 'ONE\ntwo\n'
    }
}
