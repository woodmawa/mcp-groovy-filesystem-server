package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS-GS -- execute action=groovy runs inside a real sandbox.
 *
 * Measured 2026-09-22 (session 2026-09-22-13-03): a groovy script read C:/Windows/win.ini, a relative
 * `new File('alpha.txt')` resolved against the Claude app folder rather than options.workingDir,
 * and options.timeout was never applied -- `SecurityService.executeWithTimeout` existed and
 * doGroovy did not call it. Will's decision: protect action=groovy within a secure sandbox that
 * still supports what Claude routinely writes.
 *
 * The mechanism is a CompilationCustomizer that rewrites `new File(...)`, `Paths.get(...)` and
 * `Path.of(...)` into the DSL's `file(...)` / `path(...)` helpers, which resolve relative paths
 * against workingDir and refuse anything outside the allowed directories. Raw stream constructors
 * that take a path are refused at compile time with a message naming the helpers. So the natural
 * script keeps working (GS-7 is the control) and the escapes stop (GS-2..4, GS-8).
 *
 * Every case dispatches through handleToolCall on the Spring fixture (FS-EXEC-5 lesson).
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('ExecuteService -- FS-GS the groovy sandbox')
class ExecuteServiceGroovySandboxSpec extends Specification {

    @Autowired ExecuteService executeService

    @TempDir Path tempDir

    private static String payloadOf(McpResponse r) {
        def content = r?.result?.content
        return content ? (content[0].text as String) : ''
    }

    private String groovy(String script, Map extraOptions = [:]) {
        Map<String, Object> options = ([workingDir: tempDir.toString()] + extraOptions) as Map<String, Object>
        return payloadOf(executeService.handleToolCall('execute', [
            action : 'groovy',
            script : script,
            options: options
        ] as Map<String, Object>, 'groovy-sandbox'))
    }

    def 'FS-GS-1: a relative new File resolves against workingDir, not the JVM cwd'() {
        when:
        String out = groovy("new File('rel.txt').text = 'landed'; return 'ok'")

        then:
        out.contains('ok')
        Files.exists(tempDir.resolve('rel.txt'))
        tempDir.resolve('rel.txt').text == 'landed'
    }

    def 'FS-GS-2: new File on an absolute path outside the allowed directories is refused'() {
        when:
        String out = groovy("return new File('C:/Windows/win.ini').text.take(5)")

        then:
        !out.contains('16-bit')
        out.toLowerCase().contains('not in the allowed')
    }

    def 'FS-GS-3: Paths.get outside the allowed directories is refused'() {
        when:
        String out = groovy("import java.nio.file.*; return Files.readString(Paths.get('C:/Windows/win.ini')).take(5)")

        then:
        !out.contains('16-bit')
        out.toLowerCase().contains('not in the allowed')
    }

    def 'FS-GS-4: a .. escape from workingDir is refused'() {
        when:
        String out = groovy("return new File(workingDir, '../../../../../../Windows/win.ini').text.take(5)")

        then:
        !out.contains('16-bit')
        out.toLowerCase().contains('not in the allowed')
    }

    def 'FS-GS-5: options.timeout is applied'() {
        when:
        long t0 = System.currentTimeMillis()
        String out = groovy("Thread.sleep(6000); return 'finished'", [timeout: 1])
        long elapsed = System.currentTimeMillis() - t0

        then:
        !out.contains('finished')
        out.toLowerCase().contains('timed out')
        elapsed < 5000
    }

    def 'FS-GS-6: the DSL bash() helper survives a double-quoted phrase with whitespace'() {
        when:
        String out = groovy("return bash('echo \"one two\"; echo \"three four\"').stdout")

        then:
        out.contains('one two')
        out.contains('three four')
    }

    def 'FS-GS-7 (control): the ordinary shapes keep working inside workingDir'() {
        when:
        String out = groovy('''
            new File(workingDir, 'a.txt').text = 'alpha'
            writeText('b.txt', 'beta')
            def p = java.nio.file.Paths.get(workingDir, 'c.txt')
            java.nio.file.Files.writeString(p, 'gamma')
            new File(workingDir, 'sub').mkdirs()
            return [readText('a.txt'), new File(workingDir, 'b.txt').text, new File('c.txt').text, listDir('.').sort()]
        ''')

        then:
        out.contains('alpha')
        out.contains('beta')
        out.contains('gamma')
        out.contains('sub')
        Files.isDirectory(tempDir.resolve('sub'))
    }

    def 'FS-GS-8: a raw stream constructor on a path is refused at compile time, naming the helper'() {
        when:
        String out = groovy("new FileOutputStream('C:/Windows/nope.txt').close(); return 'wrote'")

        then: 'refused at compile time -- the error quotes the source line, so assert on the refusal, not on the word'
        out.contains('"success":false')
        out.contains('SANDBOX')
        out.contains('file(')
        !Files.exists(Path.of('C:/Windows/nope.txt'))
    }
}
