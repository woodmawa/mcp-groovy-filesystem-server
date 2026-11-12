package com.softwood.mcp.service

import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FS 0.10.2 -- assessment 2026-11-12 item 5 (Will: park the local model for reading).
 *
 * <p>file_read told every chat, in its description and again in the too-long refusal, to orient in a large file with
 * the AW file-digest flow. In the seven days to 2026-11-12 it was started once. The advert cost every chat that loads
 * file_read about 120 tokens and steered nothing; symbol reads and grep hits that name their member do the job.</p>
 *
 * LP-1  the file_read description built into FS does not advertise file-digest or the local model
 * LP-2  a refused whole-file read still names structure, get_method, range and grep -- and not file-digest
 */
@CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
class LocalModelParkedSpec extends Specification {

    @Autowired FileReadService fileReadService
    @Autowired PathService     pathService

    @TempDir Path tempDir

    def 'LP-1: the built-in file_read description does not advertise file-digest'() {
        given:
        String d = FileReadService.DEFAULT_DESC

        expect:
        d.contains('READ A SYMBOL, NOT A GUESS')
        !d.contains('file-digest')
        !d.toLowerCase().contains('local model')
    }

    def 'LP-2: a refused whole-file read names the targeted actions and not file-digest'() {
        given:
        File f = tempDir.resolve('Big.groovy').toFile()
        f.text = (['class Big {'] + (1..1500).collect { '    int f' + it + ' = ' + it } + ['}']).join('\n')

        when:
        String out = JsonOutput.toJson(fileReadService.handleToolCall('file_read',
            [action: 'read', path: pathService.normalizePath(f.absolutePath)], 'lp2'))

        then:
        out.contains('read refused')
        ['structure(', 'get_method(', 'range(', 'grep('].every { out.contains(it) }
        !out.contains('file-digest')
    }
}
