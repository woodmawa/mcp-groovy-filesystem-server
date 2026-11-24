package com.softwood.mcp.service.office

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import groovy.json.JsonSlurper
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.ss.usermodel.DataValidationConstraint
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFAutoShape
import org.apache.poi.xslf.usermodel.XSLFTextBox
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * OfficePlanIntegrationSpec -- FS 0.11.4.
 *
 * write_office / read_office driven with the JSON-shaped plans a caller sends, through the tool route
 * (FileWriteService / FileReadService -> OfficeDocumentHandler -> OfficePlanBinder -> GCU 2.0.1 adapters), and
 * every file checked by opening it with POI -- not by asking FS what it wrote.
 *
 * Defects this pins (found 2026-11-23, session 2026-11-23-11-14):
 *  - every plan call failed: the raw JSON map reached a GCU adapter that expects typed plans;
 *  - read_office and write_office skipped the allowed-directories check every other FS action makes, and the plan
 *    paths (template, image) were not checked at all;
 *  - docx page setup threw NoClassDefFoundError under poi-ooxml-lite;
 *  - readDocx / readPptx left the file's stream open; legacy docx / pptx writes were not atomic.
 */
@SpringBootTest
@ActiveProfiles('test')
class OfficePlanIntegrationSpec extends Specification {

    @Autowired FileWriteService fileWriteService
    @Autowired FileReadService  fileReadService

    @TempDir Path tempDir

    // ------------------------------------------------------------------ helpers

    private McpResponse write(String path, Map options) {
        fileWriteService.handleToolCall('file_write', [action: 'write_office', path: path, options: options], 'office-write')
    }

    private McpResponse read(String path, Map options = [:]) {
        fileReadService.handleToolCall('file_read', [action: 'read_office', path: path, options: options], 'office-read')
    }

    private static String text(McpResponse r) { r.result.content[0].text as String }

    private static Map body(McpResponse r) { new JsonSlurper().parseText(text(r)) as Map }

    private static boolean isError(McpResponse r) { r.result?.isError == true }

    private String p(String name) { tempDir.resolve(name).toString() }

    private static XSSFWorkbook openXlsx(String path) { new File(path).withInputStream { new XSSFWorkbook(it) } }

    private static XWPFDocument openDocx(String path) { new File(path).withInputStream { new XWPFDocument(it) } }

    private static XMLSlideShow openPptx(String path) { new File(path).withInputStream { new XMLSlideShow(it) } }

    /** A directory that is not under any allowed directory and does not exist. */
    private static String outside(String name) { "C:/fs-office-not-allowed-${UUID.randomUUID()}/${name}".toString() }

    private String png() {
        File f = tempDir.resolve('dot.png').toFile()
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), 'png', f)
        f.absolutePath
    }

    private static Map cells(List vals) { [cells: vals.collect { [value: it] }] }

    private static Map peopleBook(Map sheetExtra = [:]) {
        [styles: [hdr: [font: [bold: true]]],
         sheets: [[name: 'People', rows: [cells(['name', 'age']) + [styleRef: 'hdr'], cells(['ann', 31]), cells(['bob', 42])]] + sheetExtra]]
    }

    // ------------------------------------------------------------------ xlsx plans

    def "an xlsx workbookPlan is written with its styles, validation, conditional format and deferred rows"() {
        given:
        String path = p('plan.xlsx')
        Map plan = [styles: [hdr: [font: [bold: true]], hot: [font: [color: '#FF0000']]],
                    sheets: [[name: 'People',
                              rows: [cells(['name', 'age']) + [styleRef: 'hdr']],
                              deferredRows: [[dataKey: 'people', startAt: 2]],
                              columns: [[ref: 'A', width: 20]],
                              freezeRef: 'A2',
                              ranges: [[ref: 'B2:B10', dataValidation: [type: 'integer', operator: 'between', formula1: '0', formula2: '150'],
                                        conditionalFormat: [type: 'cellValue', operator: 'greaterThan', value1: 40, styleRef: 'hot']]]]]]

        when:
        McpResponse w = write(path, [workbookPlan: plan, data: [people: [['ann', 31], ['bob', 42]]]])
        XSSFWorkbook wb = openXlsx(path)
        def s = wb.getSheet('People')

        then:
        !isError(w)
        s.getRow(0).getCell(0).stringCellValue == 'name'
        wb.getFontAt(s.getRow(0).getCell(0).cellStyle.fontIndex).bold
        s.getRow(1).getCell(0).stringCellValue == 'ann'
        s.getRow(2).getCell(1).numericCellValue == 42d
        s.dataValidations.size() == 1
        s.dataValidations[0].validationConstraint.validationType == DataValidationConstraint.ValidationType.INTEGER
        s.sheetConditionalFormatting.numConditionalFormattings == 1
        s.paneInformation?.frozen

        cleanup:
        wb?.close()
    }

    def "0.11.5: a validation-only range leaves no blank rows, so map_by_key reads the plan's data (found live on 0.11.4)"() {
        given:
        String path = p('live.xlsx')
        Map plan = [sheets: [[name: 'People', rows: [cells(['name', 'age'])], deferredRows: [[dataKey: 'people', startAt: 2]],
                              ranges: [[ref: 'B2:B20', dataValidation: [type: 'integer', operator: 'between', formula1: '0', formula2: '150']]]]]]
        assert !isError(write(path, [workbookPlan: plan, data: [people: [['ann', 31], ['bob', 42]]]]))

        when:
        McpResponse all = read(path, [queryPlan: [sheet: 'People']])
        McpResponse keyed = read(path, [queryPlan: [sheet: 'People', format: 'map_by_key', keyColumn: 'name']])

        then:
        !isError(all)
        body(all).data*.name == ['ann', 'bob']
        !isError(keyed)
        (body(keyed).data as Map).keySet() == ['ann', 'bob'] as Set
    }

    def "APPEND adds a sheet and TRANSFORM replaces one, each keeping the rest of the workbook"() {
        given:
        String path = p('book.xlsx')
        assert !isError(write(path, [workbookPlan: peopleBook()]))

        when:
        McpResponse a = write(path, [action: 'APPEND', workbookPlan: [sheets: [[name: 'Extra', rows: [cells(['x'])]]]]])
        McpResponse t = write(path, [action: 'transform', workbookPlan: [sheets: [[name: 'People', rows: [cells(['replaced'])]]]]])
        XSSFWorkbook wb = openXlsx(path)

        then:
        !isError(a)
        !isError(t)
        wb.sheetIterator().collect { it.sheetName } as Set == ['People', 'Extra'] as Set
        wb.getSheet('People').getRow(0).getCell(0).stringCellValue == 'replaced'
        wb.getSheet('People').lastRowNum == 0
        wb.getSheet('Extra').getRow(0).getCell(0).stringCellValue == 'x'

        cleanup:
        wb?.close()
    }

    def "an unknown plan field is refused naming it and the known fields, and nothing is written"() {
        given:
        String path = p('typo.xlsx')

        when:
        McpResponse w = write(path, [workbookPlan: [sheets: [[name: 'S', rowz: []]]]])

        then:
        isError(w)
        text(w).contains('rowz')
        text(w).contains('deferredRows')
        !Files.exists(tempDir.resolve('typo.xlsx'))
    }

    def "a field GCU cannot apply is refused by GCU, and nothing is written"() {
        given:
        String path = p('missing-style.xlsx')

        when:
        McpResponse w = write(path, [workbookPlan: [sheets: [[name: 'S', rows: [cells(['a']) + [styleRef: 'nope']]]]]])

        then:
        isError(w)
        text(w).contains('nope')
        text(w).contains('nothing written')
        !Files.exists(tempDir.resolve('missing-style.xlsx'))
    }

    def "an unknown plan action is refused"() {
        when:
        McpResponse w = write(p('x.xlsx'), [action: 'DELETE', workbookPlan: peopleBook()])

        then:
        isError(w)
        text(w).contains('GENERATE')
    }

    // ------------------------------------------------------------------ allowed directories

    def "write_office outside the allowed directories is refused and leaves no directory behind"() {
        given:
        String path = outside('out.xlsx')

        when:
        McpResponse w = write(path, [sheet: 'S', headers: ['a'], rows: [['1']]])

        then:
        isError(w)
        text(w).contains('not allowed')
        !new File(path).parentFile.exists()
    }

    def "read_office outside the allowed directories is refused"() {
        when:
        McpResponse r = read(outside('in.xlsx'))

        then:
        isError(r)
        text(r).contains('not allowed')
    }

    def "a template or image path outside the allowed directories is refused, and nothing is written"() {
        when:
        McpResponse w = write(p(file), options)

        then:
        isError(w)
        text(w) =~ /not allowed|URLs are not accepted/
        !Files.exists(tempDir.resolve(file))

        where:
        file          | options
        'tpl.docx'    | [templatePath: outside('t.docx'), content: [H: 'x']]
        'tpl2.docx'   | [templatePath: outside('t.docx'), documentPlan: [sections: [[text: 'x']]]]
        'img.xlsx'    | [workbookPlan: [sheets: [[name: 'S', rows: [[cells: [[imagePath: outside('i.png')]]]]]]]]
        'img.pptx'    | [presentationPlan: [slides: [[shapes: [[type: 'IMAGE', imagePath: outside('i.png')]]]]]]
        'url.pptx'    | [presentationPlan: [slides: [[shapes: [[type: 'IMAGE', imagePath: 'http://example.com/i.png']]]]]]
    }

    // ------------------------------------------------------------------ xlsx query

    def "a queryPlan projects, filters with where, coerces and keys rows"() {
        given:
        String path = p('q.xlsx')
        assert !isError(write(path, [workbookPlan: peopleBook()]))

        when:
        Map lists = body(read(path, [queryPlan: [sheet: 'People', columns: ['name'], where: [age: [42, 99]]]]))
        Map keyed = body(read(path, [queryPlan: [sheet: 'People', format: 'map_by_key', keyColumn: 'name', coercions: [age: 'Integer']]]))

        then:
        lists.data == [[name: 'bob']]
        keyed.data.ann.age == 31
        keyed.data.bob.age == 42
    }

    def "a queryPlan with code, an unknown field, an unknown type or an unknown column is refused"() {
        given:
        String path = p('q2.xlsx')
        assert !isError(write(path, [workbookPlan: peopleBook()]))

        when:
        McpResponse r = read(path, [queryPlan: plan])

        then:
        isError(r)
        text(r).contains(named)

        where:
        plan                                                  | named
        [sheet: 'People', rowPredicate: '{ true }']           | 'rowPredicate'
        [sheet: 'People', limit: 5]                           | 'limit'
        [sheet: 'People', coercions: [age: 'java.io.File']]   | 'java.io.File'
        [sheet: 'People', columns: ['agee']]                  | 'agee'
    }

    // ------------------------------------------------------------------ docx

    def "a docx documentPlan writes headings, lists, a table and landscape page setup, and read_office reads it"() {
        given:
        String path = p('doc.docx')
        Map plan = [title: 'Report', properties: [author: 'FS spec'],
                    page: [size: 'A4', orientation: 'landscape'],
                    sections: [[heading: 'Intro', text: 'hello'],
                               [heading: 'Points', headingLevel: 2, bulletList: ['one', 'two'], numberedList: ['first']],
                               [table: [headers: ['k', 'v'], rows: [['a', '1']], headerStyle: [font: [bold: true]]]]]]

        when:
        McpResponse w = write(path, [documentPlan: plan])
        XWPFDocument d = openDocx(path)
        Map r = body(read(path))

        then:
        !isError(w)
        d.getProperties().coreProperties.creator == 'FS spec'
        d.paragraphs.find { it.text == 'Intro' }?.style?.startsWith('Heading')
        d.paragraphs.findAll { it.text in ['one', 'two', 'first'] }.every { it.numID != null }
        d.tables[0].getRow(0).getCell(0).text == 'k'
        d.document.body.sectPr.pgSz.orient.toString() == 'landscape'
        r.sections*.heading.containsAll(['Intro', 'Points'])

        cleanup:
        d?.close()
    }

    def "a legacy docx write over an existing file replaces it, and reading leaves it deletable"() {
        given:
        String path = p('legacy.docx')
        assert !isError(write(path, [content: [First: 'one']]))

        when:
        McpResponse w = write(path, [content: [Second: 'two']])
        Map r = body(read(path))
        boolean deleted = Files.deleteIfExists(tempDir.resolve('legacy.docx'))

        then:
        !isError(w)
        r.sections*.heading == ['Second']
        deleted
        Files.list(tempDir).withCloseable { s -> s.count() } == 0
    }

    // ------------------------------------------------------------------ pptx

    def "a pptx presentationPlan writes notes, shapes and an image, and read_office reads the notes"() {
        given:
        String path = p('deck.pptx')
        Map plan = [title: 'Deck',
                    slides: [[title: 'One', notes: 'say this',
                              shapes: [[type: 'RECTANGLE', text: 'box', x: 0, y: 0, width: 914400, height: 914400],
                                       [type: 'IMAGE', imagePath: png(), x: 914400, y: 0, width: 457200, height: 457200]]]]]

        when:
        McpResponse w = write(path, [presentationPlan: plan])
        XMLSlideShow s = openPptx(path)
        def slide = s.slides[0]
        Map r = body(read(path))

        then:
        !isError(w)
        slide.shapes.any { it instanceof XSLFAutoShape && !(it instanceof XSLFTextBox) && it.text == 'box' }
        slide.shapes.any { it.class.simpleName == 'XSLFPictureShape' }
        s.getNotesSlide(slide).placeholders.find { it.textType == Placeholder.BODY }?.text == 'say this'
        r.slides[0].notes == 'say this'

        cleanup:
        s?.close()
    }

    def "a pptx plan field GCU refuses comes back by name, and nothing is written"() {
        when:
        McpResponse w = write(p('theme.pptx'), [presentationPlan: [defaultLayout: [theme: 'Office Theme'], slides: [[title: 'x']]]])

        then:
        isError(w)
        text(w).contains('theme')
        !Files.exists(tempDir.resolve('theme.pptx'))
    }

    def "a legacy pptx write over an existing file replaces it, and reading leaves it deletable"() {
        given:
        String path = p('legacy.pptx')
        assert !isError(write(path, [slides: [[title: 'A', content: 'a']]]))

        when:
        McpResponse w = write(path, [slides: [[title: 'B', content: 'b'], [title: 'C', content: 'c']]])
        Map r = body(read(path))
        boolean deleted = Files.deleteIfExists(tempDir.resolve('legacy.pptx'))

        then:
        !isError(w)
        r.slideCount == 2
        deleted
        Files.list(tempDir).withCloseable { s -> s.count() } == 0
    }
}
