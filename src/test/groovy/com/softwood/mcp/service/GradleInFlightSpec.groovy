package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

import java.nio.file.Files
import java.security.MessageDigest

/**
 * FS 0.9.126 -- an edit while a gradle run on the same repo is in flight is refused REPO_BUILDING
 * (CS decision 451, class edit-test-same-batch; obs 12955, 13090, 13193).
 *
 * GIF-1  a live marker holds a source path below its workingDir
 * GIF-2  ended: nothing holds
 * GIF-3  a marker whose owner is gone does not hold, and is deleted
 * GIF-4  guarded(): sources and build scripts yes; build output, docs no
 * GIF-5  REAL PATH: file_write replace on src/ is refused REPO_BUILDING while in flight, nothing written
 * GIF-6  REAL PATH: allowWhileBuilding=true writes anyway; and after end() the plain write goes through
 */
@SpringBootTest
@ActiveProfiles('test')
class GradleInFlightSpec extends Specification {

    @Autowired FileWriteService fileWriteService

    private File repo() {
        File r = Files.createTempDirectory('gif-repo').toFile()
        r.deleteOnExit()
        File src = new File(r, 'src/main/groovy/x')
        src.mkdirs()
        return r
    }

    private static String hash12(File f) {
        MessageDigest.getInstance('SHA-256').digest(f.bytes).encodeHex().toString()[0..11]
    }

    def 'GIF-1: a live marker holds a source path below its workingDir'() {
        given:
        File r = repo()
        File m = GradleInFlight.begin(r.absolutePath, 'test')

        when:
        Map h = GradleInFlight.holding(new File(r, 'src/main/groovy/x/Y.groovy').absolutePath)

        then:
        h != null
        h.tasks == 'test'
        h.workingDir == r.absolutePath

        cleanup:
        GradleInFlight.end(m)
    }

    def 'GIF-2: once ended, nothing holds'() {
        given:
        File r = repo()
        File m = GradleInFlight.begin(r.absolutePath, 'test')
        GradleInFlight.end(m)

        expect:
        GradleInFlight.holding(new File(r, 'src/main/groovy/x/Y.groovy').absolutePath) == null
        !m.exists()
    }

    def 'GIF-3: a marker whose owner process is gone does not hold, and is deleted'() {
        given: 'a marker naming a pid that is not running'
        File r = repo()
        File dir = new File(r, GradleInFlight.MARKER_DIR)
        dir.mkdirs()
        File stale = new File(dir, GradleInFlight.MARKER_PREFIX + 'dead.json')
        stale.text = JsonOutput.toJson([pid: Long.MAX_VALUE - 7, jvmStartedAt: '', tasks: 'test',
                                        startedAt: '2026-01-01T00:00:00Z', workingDir: r.absolutePath])

        expect:
        GradleInFlight.holding(new File(r, 'src/main/groovy/x/Y.groovy').absolutePath) == null
        !stale.exists()
    }

    def 'GIF-4: guarded(#path) == #expected'() {
        expect:
        GradleInFlight.guarded(path) == expected

        where:
        path                                         || expected
        'C:/r/src/main/groovy/A.groovy'              || true
        'C:/r/build.gradle'                          || true
        'C:/r/gradle/spec-lint.gradle'               || true
        'C:/r/gradle.properties'                     || true
        'C:/r/build/classes/A.class'                 || false
        'C:/r/CHANGELOG.md'                          || false
        'C:/r/docs/notes.md'                         || false
        null                                         || false
    }

    def 'GIF-5: REAL PATH -- a replace on src/ while gradle is in flight is refused REPO_BUILDING, nothing written'() {
        given:
        File r = repo()
        File f = new File(r, 'src/main/groovy/x/Y.groovy')
        f.text = 'class Y { int v = 1 }\n'
        File m = GradleInFlight.begin(r.absolutePath, 'test --tests YSpec')

        when:
        McpResponse resp = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.absolutePath,
            options: [oldText: 'int v = 1', newText: 'int v = 2', expectedHash: hash12(f)]
        ], 'gif-5')

        then:
        (resp.result as Map).isError == true
        def body = new JsonSlurper().parseText(resp.result.content[0].text as String) as Map
        body.error_code == 'REPO_BUILDING'
        body.tasks == 'test --tests YSpec'
        f.text == 'class Y { int v = 1 }\n'

        cleanup:
        GradleInFlight.end(m)
    }

    def 'GIF-6: REAL PATH -- allowWhileBuilding writes anyway; after the run ends a plain write goes through'() {
        given:
        File r = repo()
        File f = new File(r, 'src/main/groovy/x/Y.groovy')
        f.text = 'class Y { int v = 1 }\n'
        File m = GradleInFlight.begin(r.absolutePath, 'test')

        when: 'override while in flight'
        McpResponse a = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.absolutePath,
            options: [oldText: 'int v = 1', newText: 'int v = 2', expectedHash: hash12(f), allowWhileBuilding: true]
        ], 'gif-6a')

        then:
        (a.result as Map).isError != true
        f.text.contains('int v = 2')

        when: 'the run ends'
        GradleInFlight.end(m)
        McpResponse b = fileWriteService.handleToolCall('file_write', [
            action : 'replace',
            path   : f.absolutePath,
            options: [oldText: 'int v = 2', newText: 'int v = 3', expectedHash: hash12(f)]
        ], 'gif-6b')

        then:
        (b.result as Map).isError != true
        f.text.contains('int v = 3')
    }
}
