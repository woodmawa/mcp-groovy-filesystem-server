package com.softwood.mcp.service

import com.softwood.mcp.service.read.LocateEvidenceRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path

/**
 * FS 0.9.54 GE -- a file_read grep or multi_grep hit is locate evidence.
 *
 * N11 (0.9.43) exempted grep from ONTOLOGY-GATE because a match names the file and the line -- what
 * locate returns -- but only file_search recorded its hits. On 2026-09-22 a grep found a method and the
 * get_method on that same file was refused; WP5d-gate-blocks-after-a-search-hit read 14. Found by
 * grepping for a caller of recordHit (practice #3502): there was none on the file_read side.
 *
 * Asserted on the registry the gate consults, keyed on the session the gate reads, through
 * handleToolCall on the Spring fixture.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FileReadService -- GE grep hits are locate evidence')
class GrepLocateEvidenceSpec extends Specification {

    @Autowired FileReadService fileReadService
    @Autowired LocateEvidenceRegistry registry
    @Autowired PathService pathService
    @Autowired(required = false) FilesystemTelemetryService telemetryService

    @TempDir Path dir

    def setup() { registry.clear() }

    private String sid() { telemetryService?.readActiveSessionId() }

    private void read(Map<String, Object> args) {
        fileReadService.handleToolCall('file_read', args, 'ge')
    }

    def 'GE-1: a grep that matches records the file'() {
        given:
        Path f = dir.resolve('Found.groovy'); f.text = 'class Found {\n  void target() {}\n}\n'

        when:
        read([action: 'grep', path: f.toString(), options: [pattern: 'target', allowNoLocate: true]] as Map<String, Object>)

        then:
        registry.isSatisfied(sid(), pathService.normalizePath(f.toString()))
    }

    def 'GE-2 (control): a grep with no match records nothing'() {
        given:
        Path f = dir.resolve('Missed.groovy'); f.text = 'class Missed {}\n'

        when:
        read([action: 'grep', path: f.toString(), options: [pattern: 'nowhere-to-be-found', allowNoLocate: true]] as Map<String, Object>)

        then:
        !registry.isSatisfied(sid(), pathService.normalizePath(f.toString()))
    }

    def 'GE-3: multi_grep records exactly the files that matched'() {
        given:
        Path hit  = dir.resolve('Hit.groovy');  hit.text  = 'def needle = 1\n'
        Path miss = dir.resolve('Miss.groovy'); miss.text = 'def hay = 1\n'

        when:
        read([action: 'multi_grep', options: [pattern: 'needle', paths: [hit.toString(), miss.toString()]]] as Map<String, Object>)

        then:
        registry.isSatisfied(sid(), pathService.normalizePath(hit.toString()))
        !registry.isSatisfied(sid(), pathService.normalizePath(miss.toString()))
    }
}
