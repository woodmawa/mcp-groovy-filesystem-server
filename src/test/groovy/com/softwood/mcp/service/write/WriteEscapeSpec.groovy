package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileWriteService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * WE &mdash; write-path escape handling. FS 0.9.14, observation 10102.
 *
 * <p>FS 0.9.7 CT-82 added Java-style unescaping to {@code doWrite} because Claude's tool-call
 * serialiser sends a newline as the two-character sequence, and without it every multi-line
 * source write landed as one line of literal escapes. That reason is real and this spec keeps it
 * (WE-1). The defect is the last step of it.
 *
 * <p>The sequence protects a doubled backslash behind a sentinel, unescapes, then restores the
 * sentinel to a <em>doubled</em> backslash. Standard unescaping restores it to a single one.
 * Because it does not, the doubled backslash is not an escape hatch: it comes back doubled, so
 * <strong>no input can produce a literal backslash-t, backslash-n or backslash-r</strong>. Any
 * source file legitimately containing one &mdash; a Windows path in a Python raw string, a regex,
 * a Java or Groovy string literal &mdash; is corrupted on write, and the call returns
 * {@code success: true} with a content hash of the corrupted result.
 *
 * <p>Measured on FS 0.9.13 by writing each candidate sequence and reading the bytes back:
 * backslash-t became a TAB, backslash-r and backslash-n became line breaks, a doubled backslash
 * survived doubled, and backslash-quote and backslash-d were untouched. It cost three failed
 * patch attempts in session 2026-08-28-12-37 before being probed rather than inferred.
 *
 * <p>{@code options.raw=true} already existed as an opt-out and is asserted here (WE-4), because
 * it is absent from the tool description &mdash; which is why it went unused through all three of
 * those attempts.
 *
 * <p>This spec contains no literal backslash anywhere: every sequence is built from character
 * codes. That is not fastidiousness &mdash; the tool under test is the tool that writes this
 * file, so a literal backslash here would be rewritten before the compiler ever saw it.
 *
 * @since FS 0.9.14
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class WriteEscapeSpec extends Specification {

    @Autowired FileWriteService fileWriteService

    @TempDir Path tempDir

    /** One backslash, built from its code point so this source file carries none. */
    static final String BS  = ((char) 92).toString()
    static final String LF  = ((char) 10).toString()
    static final String TAB = ((char) 9).toString()
    static final String CR  = ((char) 13).toString()

    // ------------------------------------------------------------------ helpers

    private String writeAndReadBack(String name, String content, Map extraOptions = [:]) {
        File f = tempDir.resolve(name).toFile()
        Map args = [action: 'write', path: f.absolutePath, content: content]
        if (extraOptions) args.options = extraOptions
        McpResponse r = fileWriteService.handleToolCall('file_write', args, 'we-spec')
        assert r.result != null : "write failed: ${r.error?.message}"
        return f.getText('UTF-8')
    }

    // =========================================================================
    // WE-1 -- the reason CT-82 exists, preserved
    // =========================================================================

    def 'WE-1: a lone backslash-n still becomes a real newline'() {
        when: 'the shape CT-82 was written for -- a multi-line source write'
        String onDisk = writeAndReadBack('we1.txt', 'line one' + BS + 'n' + 'line two')

        then: 'two lines, not one line containing a literal escape'
        onDisk == 'line one' + LF + 'line two'
    }

    def 'WE-1b: backslash-t and backslash-r are unescaped too'() {
        when:
        String onDisk = writeAndReadBack('we1b.txt', 'a' + BS + 't' + 'b' + BS + 'r' + 'c')

        then: '''The tab survives. The carriage return does NOT, and should not: after
                 unescaping, WriteUtils.shouldNormaliseLf rewrites CR to LF for text targets.
                 Asserting CR here was this spec being wrong, not the product.'''
        onDisk == 'a' + TAB + 'b' + LF + 'c'
    }

    // =========================================================================
    // WE-2 -- the defect: the sentinel restore doubles the backslash
    // =========================================================================

    def 'WE-2: a doubled backslash restores to ONE backslash, not two'() {
        when: 'the caller escapes a backslash the standard way'
        String onDisk = writeAndReadBack('we2.txt', 'G' + BS + BS + 'H')

        then: '''On 0.9.13 this produced two backslashes, because the sentinel was restored to
                 a doubled backslash rather than a single one. That is what removes the escape
                 hatch: doubling does not get you out, it just gets you back where you started.'''
        onDisk == 'G' + BS + 'H'
    }

    def 'WE-3: therefore a doubled backslash before t writes a literal backslash-t'() {
        when: '''The whole point. On 0.9.13, backslash-t gave a TAB and doubling it gave two
                 backslashes then t -- so there was NO input that produced this two-character
                 sequence, which is what corrupted the patch scripts three times.'''
        String onDisk = writeAndReadBack('we3.txt', 'x' + BS + BS + 't' + 'y')

        then:
        onDisk == 'x' + BS + 't' + 'y'
        onDisk.contains(TAB) == false
    }

    def 'WE-3b: a Windows path in a Python raw string survives'() {
        given: '''The exact content that failed in session 2026-08-28-12-37: a raw-string path
                  whose segments begin with t and r. It arrived with a TAB and a line break in it
                  and threw SyntaxError on a line that had been written correctly.'''
        String path = 'C:' + BS + BS + 'Users' + BS + BS + 'willw' + BS + BS + 'tools' + BS + BS + 'router.groovy'

        when:
        String onDisk = writeAndReadBack('we3b.py', 'P = r"' + path + '"')

        then: 'single backslashes, no tab, no stray line break'
        onDisk == 'P = r"C:' + BS + 'Users' + BS + 'willw' + BS + 'tools' + BS + 'router.groovy"'
        onDisk.contains(TAB) == false
        onDisk.contains(LF) == false
    }

    // =========================================================================
    // WE-4 -- the opt-out that already existed and nobody knew about
    // =========================================================================

    def 'WE-4: options.raw=true preserves the content verbatim'() {
        when:
        String onDisk = writeAndReadBack('we4.txt', 'a' + BS + 'n' + 'b' + BS + BS + 'c', [raw: true])

        then: 'nothing is interpreted -- every character survives as sent'
        onDisk == 'a' + BS + 'n' + 'b' + BS + BS + 'c'
    }

    // =========================================================================
    // WE-5 -- sequences that were always left alone stay left alone
    // =========================================================================

    def 'WE-5: an unrecognised escape is not touched'() {
        when: 'backslash-d is not a Java whitespace escape and never was rewritten'
        String onDisk = writeAndReadBack('we5.txt', 'K' + BS + 'd' + 'L')

        then:
        onDisk == 'K' + BS + 'd' + 'L'
    }
}
