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
 * Tool-audit defects found 2026-09-25 (session 2026-09-25-09-19).
 *
 * F1 -- file_lifecycle create type=file created the missing parent even with mkdirs=false.
 * F2 -- file_search declared a `recursive` option that nothing read.
 * F3 -- server_lifecycle stop read `arguments.force` without declaring it.
 *
 * Asserted at the handler the caller reaches, and for F1 on the filesystem afterwards.
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS tool-audit defects F1-F3')
class FsToolAuditDefectsSpec extends Specification {

    @Autowired FileLifecycleService lifecycle
    @Autowired FileSearchService search
    @Autowired ServerLifecycleService serverLifecycle

    @TempDir Path tempDir

    private static String payloadOf(McpResponse r) {
        def content = r?.result?.content
        return content ? (content[0].text as String) : ''
    }

    private McpResponse create(String path, Map options) {
        return lifecycle.handleToolCall('file_lifecycle', [
            action : 'create',
            path   : path,
            options: options
        ] as Map<String, Object>, 'audit-create')
    }

    private static Map schemaProps(List<Map<String, Object>> defs, String tool) {
        Map d = defs.find { it.get('name') == tool }
        return (d.get('inputSchema') as Map).get('properties') as Map
    }

    def 'F1: create type=file with mkdirs=false and a missing parent refuses and creates nothing'() {
        given:
        Path parent = tempDir.resolve('missing/deeper')
        Path target = parent.resolve('new.txt')

        when:
        String out = payloadOf(create(target.toString(), [type: 'file', mkdirs: false]))

        then:
        !Files.exists(target)
        !Files.exists(parent)
        !Files.exists(tempDir.resolve('missing'))
        out.toLowerCase().contains('parent')
    }

    def 'F1b: create type=file with mkdirs omitted still creates the parent (documented default true)'() {
        given:
        Path target = tempDir.resolve('made/by/default/new.txt')

        when:
        create(target.toString(), [type: 'file'])

        then:
        Files.isRegularFile(target)
    }

    def 'F2: the served file_search schema has no recursive option'() {
        when:
        Map props = schemaProps(search.getToolDefinitions(), 'file_search')
        Map optionProps = (props.get('options') as Map).get('properties') as Map
        String optionText = (props.get('options') as Map).get('description') as String

        then:
        !optionProps.containsKey('recursive')
        !optionText.contains('recursive')
    }

    def 'F3: the served server_lifecycle schema declares force'() {
        when:
        Map props = schemaProps(serverLifecycle.getToolDefinitions(), 'server_lifecycle')

        then:
        props.containsKey('force')
        (props.get('force') as Map).get('type') == 'boolean'
    }
}
