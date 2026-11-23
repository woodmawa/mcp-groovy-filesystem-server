package com.softwood.mcp.service.office

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * OfficeXlsxRoundTripSpec -- FS 0.11.3.
 *
 * write_office / read_office for .xlsx through the tool route a caller uses
 * (FileWriteService / FileReadService -> OfficeDocumentHandler). FS had no office spec at all.
 *
 * The defect (found live 2026-11-23, session 2026-11-23-11-14): a write_office to an xlsx that
 * already exists failed with "The part /docProps/app.xml failed to be saved in the stream".
 * OfficeDocumentHandler opened the existing workbook with new XSSFWorkbook(File), which keeps the
 * file open as a lazily-read zip package, then wrote the workbook back to that same path while it
 * was still open. The read path opened files the same way.
 */
@SpringBootTest
@ActiveProfiles('test')
class OfficeXlsxRoundTripSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    private McpResponse writeXlsx(String path, Map options) {
        fileWriteService.handleToolCall('file_write', [action: 'write_office', path: path, options: options], 'xlsx-write')
    }

    private McpResponse readXlsx(String path, Map options = [:]) {
        fileReadService.handleToolCall('file_read', [action: 'read_office', path: path, options: options], 'xlsx-read')
    }

    private static Map body(McpResponse r) {
        new JsonSlurper().parseText(r.result.content[0].text as String) as Map
    }

    private static boolean isError(McpResponse r) {
        r.result?.isError == true
    }

    private static String sha256(Path p) {
        MessageDigest.getInstance('SHA-256').digest(Files.readAllBytes(p)).encodeHex().toString()
    }

    def "a new xlsx is written and read back"() {
        given:
        String path = tempDir.resolve('new.xlsx').toString()

        when:
        McpResponse w = writeXlsx(path, [sheet: 'Data', headers: ['name', 'n'], rows: [['a', 1], ['b', 2]]])
        McpResponse r = readXlsx(path, [sheet: 'Data'])

        then:
        !isError(w)
        body(w).rowsWritten == 2
        !isError(r)
        body(r).headers == ['name', 'n']
        body(r).data*.name == ['a', 'b']
    }

    def "writing to an existing xlsx replaces that sheet and leaves no temporary file"() {
        given:
        Path file = tempDir.resolve('existing.xlsx')
        String path = file.toString()
        assert !isError(writeXlsx(path, [sheet: 'Data', headers: ['v'], rows: [['first']]]))

        when:
        McpResponse w = writeXlsx(path, [sheet: 'Data', headers: ['v'], rows: [['second'], ['third']]])
        McpResponse r = readXlsx(path, [sheet: 'Data'])

        then:
        !isError(w)
        body(w).rowsWritten == 2
        !isError(r)
        body(r).data*.v == ['second', 'third']
        Files.list(tempDir).withCloseable { s -> s.map { it.fileName.toString() }.toList() } == ['existing.xlsx']
    }

    def "a second sheet written to an existing workbook keeps the first"() {
        given:
        String path = tempDir.resolve('two-sheets.xlsx').toString()
        assert !isError(writeXlsx(path, [sheet: 'One', headers: ['k'], rows: [['one']]]))

        when:
        McpResponse w = writeXlsx(path, [sheet: 'Two', headers: ['k'], rows: [['two']]])

        then:
        !isError(w)
        body(readXlsx(path, [sheet: 'One'])).data*.k == ['one']
        body(readXlsx(path, [sheet: 'Two'])).data*.k == ['two']
    }

    def "reading an xlsx does not change the file and does not hold it open"() {
        given:
        Path file = tempDir.resolve('read-only.xlsx')
        String path = file.toString()
        assert !isError(writeXlsx(path, [sheet: 'Data', headers: ['v'], rows: [['x']]]))
        String before = sha256(file)

        when:
        McpResponse r = readXlsx(path, [sheet: 'Data'])
        String after = sha256(file)
        boolean deleted = Files.deleteIfExists(file)

        then:
        !isError(r)
        after == before
        deleted
    }
}
