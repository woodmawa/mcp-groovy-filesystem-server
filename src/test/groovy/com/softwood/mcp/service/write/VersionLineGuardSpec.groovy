package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileWriteService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * VersionLineGuardSpec -- FS 0.9.116, productive-learning round 1b remedy 2b.
 *
 * A platform repo's build.gradle `version = '...'` line is one of several stamps a release sets
 * together (README/CLAUDE titles, baselines, :*-baseline: attributes). Hand-editing just that line
 * leaves the others behind, and verify_contracts catches it later as rework. The bump-baseline flow
 * sets them all at once. So FS refuses a write that changes that line, naming the flow, and writes
 * nothing.
 *
 *   VL-1 : replace changing the version line           -> VERSION_VIA_BUMP_BASELINE, file unchanged
 *   VL-2 : full write changing the version line        -> refused, file unchanged
 *   VL-3 : multi_replace that includes the version line -> refused, file unchanged
 *   VL-4 : CONTROL other edits to the same build.gradle pass
 *   VL-5 : CONTROL same edit outside a platform repo passes
 *   VL-6 : CONTROL creating a new build.gradle passes (nothing to drift from) -- every seed() is one
 *
 * productive-learning R3 (the 2b gap): server_transform's transformers wrote through WriteUtils.atomicWrite directly,
 * never through WriteCommitter, so a transform could still change the line (red on FS 0.9.116).
 *   VL-7 : server_transform replace_between changing the version line   -> refused, file unchanged
 *   VL-8 : server_transform insert_before_match putting a new version first -> refused, file unchanged
 *   VL-9 : CONTROL replace_between elsewhere in a platform build.gradle passes
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class VersionLineGuardSpec extends Specification {

    @Autowired FileWriteService fileWriteService

    @TempDir Path tempDir

    static final String GRADLE = "plugins { id 'groovy' }\n\ngroup = 'com.softwood'\nversion = '1.1.116'\n\nrepositories { mavenCentral() }\n"

    private Map call(Map args) {
        McpResponse r = fileWriteService.handleToolCall('file_write', args, 'vl')
        assert r.result != null : "no result: ${r.error?.message}"
        String text = (r.result.content[0] as Map).text as String
        [isError: r.result.isError == true, text: text]
    }

    private Map seed(String repo) {
        Path dir = tempDir.resolve(repo)
        Files.createDirectories(dir)
        String p = dir.resolve('build.gradle').toString()
        Map r = call([action: 'write', path: p, content: GRADLE, options: [raw: true]])
        assert !r.isError : "VL-6 seed write refused: ${r.text}"
        def parsed = new groovy.json.JsonSlurper().parseText(r.text) as Map
        [path: p, hash: parsed.file_content_hash as String]
    }

    private static String body(String p) { new String(Files.readAllBytes(Path.of(p)), 'UTF-8') }

    def "VL-1 replace changing the version line is refused and writes nothing"() {
        given:
        Map f = seed('mcp-groovy-context-server')

        when:
        Map r = call([action: 'replace', path: f.path,
                      options: [oldText: "version = '1.1.116'", newText: "version = '1.1.117'", expectedHash: f.hash]])

        then:
        r.isError
        r.text.contains('VERSION_VIA_BUMP_BASELINE')
        r.text.contains('bump-baseline')
        r.text.contains('context-server')
        body(f.path).contains("version = '1.1.116'")
    }

    def "VL-2 full write changing the version line is refused and writes nothing"() {
        given:
        Map f = seed('mcp-groovy-filesystem-server')

        when:
        Map r = call([action: 'write', path: f.path, content: GRADLE.replace("'1.1.116'", "'1.1.117'"), options: [raw: true]])

        then:
        r.isError
        r.text.contains('VERSION_VIA_BUMP_BASELINE')
        r.text.contains('filesystem')
        body(f.path).contains("version = '1.1.116'")
    }

    def "VL-3 multi_replace carrying the version line is refused and writes nothing"() {
        given:
        Map f = seed('mcp-agentic-workflow')

        when:
        Map r = call([action: 'multi_replace', path: f.path, options: [expectedHash: f.hash, replacements: [
                [oldText: "group = 'com.softwood'", newText: "group = 'com.softwood.mcp'"],
                [oldText: "version = '1.1.116'", newText: "version = '1.1.117'"]]]])

        then:
        r.isError
        r.text.contains('VERSION_VIA_BUMP_BASELINE')
        r.text.contains('agentic-workflow')
        body(f.path) == GRADLE
    }

    def "VL-4 CONTROL other edits to a platform build.gradle pass"() {
        given:
        Map f = seed('mcp-groovy-context-server')

        when:
        Map r = call([action: 'replace', path: f.path,
                      options: [oldText: "group = 'com.softwood'", newText: "group = 'com.softwood.mcp'", expectedHash: f.hash]])

        then:
        !r.isError
        body(f.path).contains("group = 'com.softwood.mcp'")
        body(f.path).contains("version = '1.1.116'")
    }

    def "VL-5 CONTROL the same version edit outside a platform repo passes"() {
        given:
        Map f = seed('some-other-app')

        when:
        Map r = call([action: 'replace', path: f.path,
                      options: [oldText: "version = '1.1.116'", newText: "version = '1.1.117'", expectedHash: f.hash]])

        then:
        !r.isError
        body(f.path).contains("version = '1.1.117'")
    }

    def "VL-7 server_transform replace_between changing the version line is refused and writes nothing"() {
        given:
        Map f = seed('mcp-groovy-context-server')

        when:
        Map r = call([action: 'server_transform', path: f.path, options: [transform: 'replace_between', expectedHash: f.hash,
                      startAnchor: "group = 'com.softwood'", endAnchor: 'repositories', newContent: "version = '1.1.117'"]])

        then:
        r.isError
        r.text.contains('VERSION_VIA_BUMP_BASELINE')
        r.text.contains('context-server')
        body(f.path) == GRADLE
    }

    def "VL-8 server_transform insert_before_match putting a new version line first is refused and writes nothing"() {
        given:
        Map f = seed('mcp-agentic-workflow')

        when:
        Map r = call([action: 'server_transform', path: f.path, options: [transform: 'insert_before_match', expectedHash: f.hash,
                      match: "group = 'com.softwood'", content: "version = '9.9.9'"]])

        then:
        r.isError
        r.text.contains('VERSION_VIA_BUMP_BASELINE')
        body(f.path) == GRADLE
    }

    def "VL-9 CONTROL server_transform elsewhere in a platform build.gradle passes"() {
        given:
        Map f = seed('mcp-groovy-filesystem-server')

        when:
        Map r = call([action: 'server_transform', path: f.path, options: [transform: 'replace_between', expectedHash: f.hash,
                      startAnchor: 'plugins', endAnchor: "group = 'com.softwood'", newContent: '// built by FS']])

        then:
        !r.isError
        body(f.path).contains('// built by FS')
        body(f.path).contains("version = '1.1.116'")
    }
}
