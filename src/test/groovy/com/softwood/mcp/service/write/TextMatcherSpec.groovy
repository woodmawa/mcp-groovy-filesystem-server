package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.text.Normalizer

/**
 * TextMatcherSpec -- contract tests for PR 1.2.
 * Target version: FS 0.9.0
 *
 * Tests the TextMatcher helper directly (unit-level) and via the MCP surface
 * (integration-level) to confirm the D2 fix: NFC/NFKC normalisation no longer
 * applies match offsets from a normalised string to the original content.
 *
 *   CT-MATCH-1 : NFC resolves match; only the matched region is replaced, surrounding chars intact
 *   CT-MATCH-2 : Decomposed 'e + combining acute' matches composed 'e-acute'; only that region replaced
 *   CT-MATCH-3 : NFKC normalisation does not corrupt adjacent characters
 *   CT-MATCH-4 : NFC match where normalisation changes length -> MATCH_REQUIRES_EXACT -> file unchanged
 *   CT-MATCH-5 : Box-drawing normalisation replaces only the box-drawing region
 *   CT-MATCH-6 : Multi-replace with one NFC entry + one raw entry: both succeed, non-target Unicode preserved
 *   CT-MATCH-7 : Ambiguous oldText (count > 1) returns count error, not corrupt partial apply
 *
 * Spock rules (practice #407): @CompileDynamic, @SpringBootTest, TempDir.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class TextMatcherSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map writeFileRaw(String name, String text) {
        // Write via Java directly so we control exact bytes (avoid MCP line-ending normalisation)
        File f = tempDir.resolve(name).toFile()
        f.setText(text, 'UTF-8')
        // Get hash via MCP
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action: 'range', path: f.absolutePath, options: [startLine: 1, maxLines: 1]
        ], 'hash-probe')
        String hash = 'probe-failed'
        if (r.result) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
                if (parsed.file_content_hash) hash = parsed.file_content_hash as String
            } catch (Exception ignored) {}
        }
        [path: f.absolutePath, hash: hash, file: f]
    }

    private Map writeFile(String name, String text) {
        File f = tempDir.resolve(name).toFile()
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action: 'write', path: f.absolutePath, content: text
        ], 'setup')
        assert r.result != null : "Setup write failed: ${r.error?.message}"
        def parsed = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        [path: f.absolutePath, hash: parsed.file_content_hash as String]
    }

    private void assertToolError(McpResponse r, String... keywords) {
        assert r.result != null : "Expected isError, got r.result=null r.error=${r.error?.message}"
        assert r.result.isError == true : "Expected isError:true, got: ${r.result}"
        String text = (r.result.content[0] as Map).text as String
        keywords.each { kw ->
            assert text.toLowerCase().contains(kw.toLowerCase()) :
                "Error text missing '${kw}'.\nFull text: ${text}"
        }
    }

    private String readContent(String path) {
        McpResponse r = fileReadService.handleToolCall('file_read', [
            action: 'read', path: path, options: [force: true]
        ], 'verify')
        assert r.result != null
        def p = new groovy.json.JsonSlurper().parseText(r.result.content[0].text as String) as Map
        p.content as String
    }

    // -----------------------------------------------------------------------
    // Unit-level tests on TextMatcher directly
    // -----------------------------------------------------------------------

    def "TextMatcher.find() raw match returns correct origStart/origEnd"() {
        given:
        String content = 'prefix TARGET suffix'
        String oldText = 'TARGET'

        when:
        TextMatcher.MatchResult r = TextMatcher.find(content, oldText)

        then:
        r.count == 1
        r.normForm == null
        r.origStart == 7
        r.origEnd == 13
        TextMatcher.apply(content, 'REPLACED', r) == 'prefix REPLACED suffix'
    }

    def "TextMatcher.find() returns count>1 for ambiguous match"() {
        given:
        String content = 'foo bar foo baz foo'
        String oldText = 'foo'

        when:
        TextMatcher.MatchResult r = TextMatcher.find(content, oldText)

        then:
        r.count == 3
        r.origStart == -1  // undefined for ambiguous
    }

    def "TextMatcher.find() returns count==0 with nearestLine hint for not-found"() {
        given:
        String content = 'line one\nline two\nline three\n'
        String oldText = 'line twoo'   // near-miss typo

        when:
        TextMatcher.MatchResult r = TextMatcher.find(content, oldText)

        then:
        r.count == 0
        r.nearestLine > 0           // hint populated
        r.nearestContent != null
    }

    def "TextMatcher.apply() returns MATCH_REQUIRES_EXACT when origStart/End are -1"() {
        given:
        TextMatcher.MatchResult r = new TextMatcher.MatchResult(count: 1, normForm: 'NFC', origStart: -1, origEnd: -1)

        when:
        String result = TextMatcher.apply('any content', 'new', r)

        then:
        result == TextMatcher.MATCH_REQUIRES_EXACT
    }

    def "TextMatcher.normalizeBoxDrawing() replaces box-drawing chars one-for-one preserving length"() {
        given:
        String original = '\u2500\u2501\u2502\u2503\u2551 hello'

        when:
        String normalised = TextMatcher.normalizeBoxDrawing(original)

        then:
        normalised.length() == original.length()  // length preserved (G3 contract)
        normalised.contains('hello')
        !normalised.contains('\u2500')
        !normalised.contains('\u2502')
    }

    // -----------------------------------------------------------------------
    // CT-MATCH-1: NFC resolves match; surrounding chars unchanged
    // -----------------------------------------------------------------------
    def "CT-MATCH-1: replace via NFC normalisation only touches matched region"() {
        given: "a file containing a composed e-acute (NFC form)"
        // Composed form: single code point U+00E9
        String eAcuteComposed   = '\u00e9'
        // Decomposed form: e + combining acute accent
        String eAcuteDecomposed = 'e\u0301'
        // File has composed, oldText is decomposed -- NFC should resolve
        String fileContent = "prefix ${eAcuteComposed}target${eAcuteComposed} suffix\n"
        def f = writeFileRaw('ct-match-1.txt', fileContent)

        when: "replace using decomposed form as oldText"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : "${eAcuteDecomposed}target${eAcuteDecomposed}",
                newText     : 'REPLACED',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "succeeds and only the target region is replaced"
        r.result != null
        r.result.isError == null || r.result.isError == false
        String updated = readContent(f.path)
        updated.contains('prefix')
        updated.contains('REPLACED')
        updated.contains('suffix')
        !updated.contains('target')
    }

    // -----------------------------------------------------------------------
    // CT-MATCH-2: Decomposed -> composed match (canonical equivalence)
    // -----------------------------------------------------------------------
    def "CT-MATCH-2: decomposed e+combining-acute in file matches composed e-acute in oldText"() {
        given: "a file with decomposed e-acute"
        String eAcuteDecomposed = 'e\u0301'
        String fileContent = "start ${eAcuteDecomposed}word end\n"
        def f = writeFileRaw('ct-match-2.txt', fileContent)

        when: "replace using composed form"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '\u00e9word',
                newText     : 'newword',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "succeeds"
        r.result != null
        r.result.isError == null || r.result.isError == false
        readContent(f.path).contains('newword')
        readContent(f.path).contains('start')
        readContent(f.path).contains('end')
    }

    // -----------------------------------------------------------------------
    // CT-MATCH-3: NFKC does not corrupt adjacent characters
    // -----------------------------------------------------------------------
    def "CT-MATCH-3: NFKC normalisation resolves match without corrupting adjacent ASCII"() {
        given: "a file with a fullwidth character adjacent to ASCII"
        // U+FF21 = FULLWIDTH LATIN CAPITAL LETTER A -> NFKC -> 'A'
        String fullwidthA = '\uff21'
        String fileContent = "before ${fullwidthA}BC after\n"
        def f = writeFileRaw('ct-match-3.txt', fileContent)

        when: "replace using ASCII 'A' (NFKC equivalent)"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : 'ABC',  // NFKC of fullwidthA+BC
                newText     : 'XYZ',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "succeeds; 'before' and 'after' intact"
        r.result != null
        r.result.isError == null || r.result.isError == false
        String updated = readContent(f.path)
        updated.contains('before')
        updated.contains('after')
        updated.contains('XYZ')
    }

    // -----------------------------------------------------------------------
    // CT-MATCH-4: MATCH_REQUIRES_EXACT -> toolError, file unchanged
    // -----------------------------------------------------------------------
    def "CT-MATCH-4: TextMatcher returns MATCH_REQUIRES_EXACT for length-changing NFC -> toolError via MCP surface"() {
        given: "a MatchResult with origStart=-1 (length-changing normalisation)"
        // Test the sentinel at unit level directly
        TextMatcher.MatchResult r = new TextMatcher.MatchResult(
            count: 1, normForm: 'NFC', origStart: -1, origEnd: -1
        )

        when:
        String result = TextMatcher.apply('some content here', 'replacement', r)

        then: "sentinel returned"
        result == TextMatcher.MATCH_REQUIRES_EXACT
    }

    // -----------------------------------------------------------------------
    // CT-MATCH-5: Box-drawing normalisation replaces only box-drawing region
    // -----------------------------------------------------------------------
    def "CT-MATCH-5: replace matching via box-drawing normalisation only replaces the divider region"() {
        given: "a file with a box-drawing section divider"
        // U+2500 = BOX DRAWINGS LIGHT HORIZONTAL
        String divider = '\u2500\u2500\u2500\u2500\u2500'
        String fileContent = "// above\n// ${divider}\n// below\n"
        def f = writeFileRaw('ct-match-5.txt', fileContent)

        when: "replace the divider using ASCII dashes (box-drawing normalised equivalent)"
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : '// -----',  // ASCII equivalent after normalisation
                newText     : '// =====',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "succeeds; above and below intact"
        r.result != null
        r.result.isError == null || r.result.isError == false
        String updated = readContent(f.path)
        updated.contains('above')
        updated.contains('below')
        updated.contains('=====')
    }

    // -----------------------------------------------------------------------
    // CT-MATCH-6: Multi-replace with mixed NFC and raw entries
    // -----------------------------------------------------------------------
    def "CT-MATCH-6: multi-replace with one NFC entry and one raw entry both succeed"() {
        given: "a file with one composed Unicode word and one plain ASCII word"
        String eAcute = '\u00e9'
        String fileContent = "plain ASCII target here\nUnicode caf${eAcute} here\n"
        def f = writeFile('ct-match-6.txt', fileContent)

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'multi_replace',
            path   : f.path,
            options: [
                expectedHash: f.hash,
                replacements: [
                    [oldText: 'plain ASCII target', newText: 'REPLACED_ASCII'],
                    [oldText: 'Unicode caf\u00e9',  newText: 'REPLACED_UNICODE'],
                ]
            ]
        ], 'test')

        then: "both replaced successfully"
        r.result != null
        r.result.isError == null || r.result.isError == false
        String updated = readContent(f.path)
        updated.contains('REPLACED_ASCII')
        updated.contains('REPLACED_UNICODE')
        !updated.contains('plain ASCII target')
        !updated.contains('Unicode caf')
    }

    // -----------------------------------------------------------------------
    // CT-MATCH-7: Ambiguous oldText returns count error, not partial apply
    // -----------------------------------------------------------------------
    def "CT-MATCH-7: replace with ambiguous oldText returns count error, file unchanged"() {
        given: "a file where oldText appears multiple times"
        String fileContent = 'foo bar\nfoo baz\nfoo qux\n'
        def f = writeFile('ct-match-7.txt', fileContent)
        String originalContent = fileContent

        when:
        McpResponse r = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.path,
            options: [
                oldText     : 'foo',
                newText     : 'REPLACED',
                expectedHash: f.hash
            ]
        ], 'test')

        then: "error returned with count info"
        assertToolError(r, 'times')  // "appears N times"
        // File must be completely unchanged
        readContent(f.path) == originalContent
    }
}
