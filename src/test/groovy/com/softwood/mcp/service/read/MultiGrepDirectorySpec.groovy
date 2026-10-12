package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FS 0.9.80 SB-5 / S6 item 1 (session todo, obs 12291) -- multi_grep given a directory is a named refusal, not a
 * success reading "0 matches".
 *
 * <p>2026-10-09: a multi_grep over a directory answered inside a success envelope with totalMatches 0 and a per-path
 * 'Path is not a file' tucked into results -- which reads exactly like "no matches". MG-1 a directory among the paths
 * refuses the call by name, with the route that does search a directory. MG-2 CONTROL: files only search as before.</p>
 */
@SpringBootTest
@ActiveProfiles('test')
class MultiGrepDirectorySpec extends Specification {

    @Autowired FileReadService fileReadService

    @TempDir Path tempDir

    private static String text(McpResponse r) { ((r.result as Map).content as List)[0].text as String }

    def "MG-1: a directory among the paths refuses the call by name"() {
        given:
        File dir = tempDir.resolve('src').toFile(); dir.mkdirs()
        File f = tempDir.resolve('a.groovy').toFile(); f.text = 'class A { def needle }'

        when:
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: 'multi_grep', options: [paths: [f.absolutePath, dir.absolutePath], pattern: 'needle']], 1)

        then:
        (r.result as Map).isError == true
        text(r).contains('MULTI_GREP_DIRECTORY')
        text(r).contains(dir.name)
        text(r).contains('file_search')
    }

    def "MG-2 CONTROL: files only search as before"() {
        given:
        File f = tempDir.resolve('b.groovy').toFile(); f.text = 'class B { def needle }'

        when:
        McpResponse r = fileReadService.handleToolCall('file_read',
            [action: 'multi_grep', options: [paths: [f.absolutePath], pattern: 'needle']], 1)

        then:
        (r.result as Map).isError != true
        text(r).contains('matchingFiles')
    }
}
