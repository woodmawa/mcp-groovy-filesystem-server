package com.softwood.mcp.service.office

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.PathService
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFSlideLayout
import org.apache.poi.xslf.usermodel.XSLFSlideMaster
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.sl.usermodel.Placeholder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.awt.Dimension
import org.softwood.dag.task.xlsx.XlsxAdapter
import org.softwood.dag.task.xlsx.WorkbookPlan
import org.softwood.dag.task.docx.DocxAdapter
import org.softwood.dag.task.docx.DocumentPlan
import org.softwood.dag.task.pptx.PptxAdapter
import org.softwood.dag.task.pptx.PresentationPlan

/**
 * OfficeDocumentHandler - POI-backed read/write for .xlsx, .docx, .pptx files.
 *
 * Called by FileReadService  via action 'read_office'
 * and by FileWriteService via action 'write_office'.
 *
 * NOT @CompileStatic - POI uses dynamic construction patterns that trip the Groovy
 * static compiler. PathService is used for path normalisation/validation.
 *
 * read_office options:
 *   format       : xlsx | docx | pptx  (required, or inferred from file extension)
 *   sheet        : sheet name for xlsx (default: first sheet)
 *   range        : A1:F20 notation     (xlsx only, optional)
 *   outputFormat : list_of_maps | list_of_lists | map_by_key  (xlsx, default: list_of_maps)
 *   keyColumn    : column name for map_by_key outputFormat
 *
 * write_office options:
 *   format       : xlsx | docx | pptx  (required, or inferred from file extension)
 *   sheet        : sheet name          (xlsx, default: Sheet1)
 *   headers      : List<String>        (xlsx)
 *   rows         : List<List<Object>>  (xlsx - also accepts List<Map>)
 *   content      : Map<String,Object>  (docx: heading -> text or List<String> for bullets)
 *   templatePath : path to .pptx/.docx template (optional)
 *   slides       : List<Map>           (pptx: [{title, content, layout, notes}])
 */
@Component
@Slf4j
class OfficeDocumentHandler {

    @Autowired
    PathService pathService

    // =========================================================================
    // READ dispatch
    // =========================================================================

    McpResponse readOffice(String path, Map<String, Object> options, Object requestId) {
        String format = resolveFormat(path, options)
        try {
            String validated = pathService.normalizePath(path)
            File file = new File(validated)
            if (!file.exists()) {
                return McpResponse.error(requestId, -32602,
                    "read_office: file not found: ${validated}")
            }
            switch (format) {
                case 'xlsx': return readXlsx(file, options, requestId)
                case 'docx': return readDocx(file, options, requestId)
                case 'pptx': return readPptx(file, options, requestId)
                default:
                    return McpResponse.error(requestId, -32602,
                        "read_office: unsupported format '${format}'. Use xlsx|docx|pptx or set options.format.")
            }
        } catch (Exception e) {
            log.error("read_office error on ${path}: ${e.message}", e)
            return McpResponse.error(requestId, -32603, sanitize("read_office failed: ${e.message}"))
        }
    }

    // =========================================================================
    // WRITE dispatch
    // =========================================================================

    McpResponse writeOffice(String path, Map<String, Object> options, Object requestId) {
        String format = resolveFormat(path, options)
        try {
            String validated = pathService.normalizePath(path)
            new File(validated).parentFile?.mkdirs()
            switch (format) {
                case 'xlsx': return writeXlsx(validated, options, requestId)
                case 'docx': return writeDocx(validated, options, requestId)
                case 'pptx': return writePptx(validated, options, requestId)
                default:
                    return McpResponse.error(requestId, -32602,
                        "write_office: unsupported format '${format}'. Use xlsx|docx|pptx or set options.format.")
            }
        } catch (Exception e) {
            log.error("write_office error on ${path}: ${e.message}", e)
            return McpResponse.error(requestId, -32603, sanitize("write_office failed: ${e.message}"))
        }
    }

    // =========================================================================
    // XLSX read
    // =========================================================================

    private McpResponse readXlsx(File file, Map options, Object requestId) {
        // DSL query path — delegate to XlsxAdapter when options.queryPlan is present
        if (options.queryPlan != null) {
            Object qResult = XlsxAdapter.asFn().call('QUERY', [filePath: file.absolutePath, query: options.queryPlan])
            return successJson(requestId, [format: 'xlsx', path: file.absolutePath, data: qResult])
        }

        Workbook wb = new XSSFWorkbook(file)
        try {
            String sheetName   = options.sheet as String
            Sheet sheet        = sheetName ? wb.getSheet(sheetName) : wb.getSheetAt(0)
            if (!sheet) {
                List<String> avail = (0..<wb.numberOfSheets).collect { wb.getSheetName(it as int) }
                return McpResponse.error(requestId, -32602,
                    "read_office xlsx: sheet '${sheetName}' not found. Available: ${avail}")
            }

            String outputFormat      = (options.outputFormat ?: 'list_of_maps') as String
            List<List<Object>> rawRows = extractRows(sheet, options.range as String)

            if (rawRows.isEmpty()) {
                return successJson(requestId, [format: 'xlsx', sheet: sheet.sheetName, data: [], rowCount: 0])
            }

            List<String> headers        = rawRows[0].collect { it?.toString() ?: '' }
            List<List<Object>> dataRows = rawRows.size() > 1 ? rawRows[1..-1] : []

            Object result
            switch (outputFormat) {
                case 'list_of_lists':
                    result = rawRows
                    break
                case 'map_by_key':
                    String keyCol = options.keyColumn as String
                    int keyIdx    = headers.indexOf(keyCol)
                    if (keyIdx < 0) {
                        return McpResponse.error(requestId, -32602,
                            "read_office xlsx map_by_key: keyColumn '${keyCol}' not in headers ${headers}")
                    }
                    result = dataRows.collectEntries { List row ->
                        def key = row.size() > keyIdx ? row[keyIdx] : null
                        [(key): zipRowToMap(headers, row)]
                    }
                    break
                default: // list_of_maps
                    result = dataRows.collect { List row -> zipRowToMap(headers, row) }
            }

            return successJson(requestId, [
                format      : 'xlsx',
                sheet       : sheet.sheetName,
                headers     : headers,
                rowCount    : dataRows.size(),
                outputFormat: outputFormat,
                data        : result
            ])
        } finally {
            wb.close()
        }
    }

    private List<List<Object>> extractRows(Sheet sheet, String range) {
        int firstRow = sheet.firstRowNum
        int lastRow  = sheet.lastRowNum
        int firstCol = 0
        int lastCol  = -1

        if (range) {
            def m = (range =~ /([A-Z]+)(\d+):([A-Z]+)(\d+)/)
            if (m.find()) {
                firstCol = colLetterToIndex(m.group(1))
                firstRow = (m.group(2) as int) - 1
                lastCol  = colLetterToIndex(m.group(3))
                lastRow  = (m.group(4) as int) - 1
            }
        }

        List<List<Object>> rows = []
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r)
            if (!row) { rows << []; continue }
            int end = lastCol >= 0 ? lastCol : (row.lastCellNum - 1)
            List<Object> cells = []
            for (int c = firstCol; c <= end; c++) {
                cells << cellValue(row.getCell(c))
            }
            rows << cells
        }
        return rows
    }

    private Object cellValue(Cell cell) {
        if (!cell) return null
        switch (cell.cellType) {
            case CellType.STRING:  return cell.stringCellValue
            case CellType.NUMERIC:
                return DateUtil.isCellDateFormatted(cell) ?
                    cell.dateCellValue.toString() : cell.numericCellValue
            case CellType.BOOLEAN: return cell.booleanCellValue
            case CellType.FORMULA:
                return cell.cachedFormulaResultType == CellType.STRING ?
                    cell.stringCellValue : cell.numericCellValue
            case CellType.BLANK:   return null
            default:               return null
        }
    }

    private static Map<String, Object> zipRowToMap(List<String> headers, List<Object> row) {
        Map<String, Object> m = [:]
        headers.eachWithIndex { String h, int i ->
            m[h] = i < row.size() ? row[i] : null
        }
        return m
    }

    private static int colLetterToIndex(String col) {
        col.toUpperCase().inject(0) { int acc, char c ->
            acc * 26 + (c - ('A' as char) + 1)
        } - 1
    }

    // =========================================================================
    // XLSX write
    // =========================================================================

    private McpResponse writeXlsx(String path, Map options, Object requestId) {
        // DSL generate path — delegate to XlsxAdapter when options.workbookPlan is present
        if (options.workbookPlan != null) {
            String xlAction = (options.action ?: 'GENERATE') as String
            Object wResult = XlsxAdapter.asFn().call(xlAction.toUpperCase(), [filePath: path, plan: options.workbookPlan])
            return successJson(requestId, [action: 'write_office', format: 'xlsx', path: path, result: wResult])
        }

        // Legacy flat-map path
        String sheetName = (options.sheet ?: 'Sheet1') as String
        List   headers   = options.headers as List
        List   rows      = options.rows as List

        // Support List<Map> input — infer headers from first map's keys
        List<List<Object>> rowData
        if (rows && !rows.isEmpty() && rows[0] instanceof Map) {
            List<String> hdrs = headers ? headers as List<String> : (rows[0] as Map).keySet().toList()
            headers  = hdrs
            rowData  = rows.collect { r -> hdrs.collect { k -> (r as Map)[k] } }
        } else {
            rowData = rows as List<List<Object>>
        }

        File existing = new File(path)
        Workbook wb   = existing.exists() ? new XSSFWorkbook(existing) : new XSSFWorkbook()
        try {
            int sheetIdx = wb.getSheetIndex(sheetName)
            if (sheetIdx >= 0) wb.removeSheetAt(sheetIdx)

            Sheet sheet  = wb.createSheet(sheetName)
            int   rowNum = 0

            if (headers) {
                Row hRow = sheet.createRow(rowNum++)
                headers.eachWithIndex { h, int i ->
                    hRow.createCell(i).setCellValue(h?.toString() ?: '')
                }
            }

            rowData?.each { List<Object> dataRow ->
                Row row = sheet.createRow(rowNum++)
                dataRow.eachWithIndex { val, int i ->
                    Cell cell = row.createCell(i)
                    if (val instanceof Number)       cell.setCellValue((val as Number).doubleValue())
                    else if (val instanceof Boolean) cell.setCellValue((boolean) val)
                    else if (val != null)            cell.setCellValue(val.toString())
                }
            }

            new File(path).withOutputStream { OutputStream out -> wb.write(out) }

            return successJson(requestId, [
                action     : 'write_office',
                format     : 'xlsx',
                path       : path,
                sheet      : sheetName,
                rowsWritten: rowData?.size() ?: 0
            ])
        } finally {
            wb.close()
        }
    }

    // =========================================================================
    // DOCX read
    // =========================================================================

    private McpResponse readDocx(File file, Map options, Object requestId) {
        XWPFDocument doc = new XWPFDocument(file.newInputStream())
        try {
            List<Map<String, Object>> sections = []
            String currentHeading   = null
            List<String> currentContent = []

            doc.paragraphs.each { XWPFParagraph para ->
                String style = para.style ?: ''
                String text  = para.text  ?: ''

                if (style.toLowerCase().startsWith('heading')) {
                    if (currentHeading != null || !currentContent.isEmpty()) {
                        sections << ([heading: currentHeading ?: '', content: currentContent.join('\n')] as Map<String, Object>)
                    }
                    currentHeading  = text
                    currentContent  = []
                } else if (text.trim()) {
                    currentContent << text
                }
            }
            if (currentHeading != null || !currentContent.isEmpty()) {
                sections << ([heading: currentHeading ?: '', content: currentContent.join('\n')] as Map<String, Object>)
            }

            return successJson(requestId, [
                format        : 'docx',
                path          : file.absolutePath,
                sections      : sections,
                paragraphCount: doc.paragraphs.size()
            ])
        } finally {
            doc.close()
        }
    }

    // =========================================================================
    // DOCX write
    // =========================================================================

    private McpResponse writeDocx(String path, Map options, Object requestId) {
        // DSL generate path — delegate to DocxAdapter when options.documentPlan is present
        if (options.documentPlan != null) {
            Object dResult = DocxAdapter.asFn().call('GENERATE', [filePath: path, plan: options.documentPlan])
            return successJson(requestId, [action: 'write_office', format: 'docx', path: path, result: dResult])
        }

        // Legacy flat-map path
        String templatePath             = options.templatePath as String
        Map<String, Object> content     = options.content as Map<String, Object>

        XWPFDocument doc = templatePath ?
            new XWPFDocument(new File(templatePath).newInputStream()) :
            new XWPFDocument()

        try {
            content?.each { String heading, Object body ->
                // Heading paragraph
                XWPFParagraph hPara = doc.createParagraph()
                hPara.style = 'Heading1'
                XWPFRun hRun = hPara.createRun()
                hRun.setText(heading)
                hRun.bold = true

                // Body — plain string or List<String> as bullet points
                if (body instanceof List) {
                    (body as List).each { item ->
                        XWPFParagraph bPara = doc.createParagraph()
                        bPara.style = 'ListParagraph'
                        XWPFRun bRun = bPara.createRun()
                        bRun.setText("• ${item}")
                    }
                } else {
                    XWPFParagraph bPara = doc.createParagraph()
                    XWPFRun bRun = bPara.createRun()
                    bRun.setText(body?.toString() ?: '')
                }
            }

            new File(path).withOutputStream { OutputStream out -> doc.write(out) }

            return successJson(requestId, [
                action  : 'write_office',
                format  : 'docx',
                path    : path,
                sections: content?.size() ?: 0
            ])
        } finally {
            doc.close()
        }
    }

    // =========================================================================
    // PPTX read
    // =========================================================================

    private McpResponse readPptx(File file, Map options, Object requestId) {
        XMLSlideShow ppt = new XMLSlideShow(file.newInputStream())
        try {
            List<Map<String, Object>> slides = []
            ppt.slides.eachWithIndex { XSLFSlide slide, int idx ->
                String title = ''
                try { title = slide.title ?: '' } catch (Exception ignored) {}

                // Collect body text from all text-capable shapes, excluding the title
                List<String> bodyText = []
                slide.shapes.each { shape ->
                    if (!(shape instanceof XSLFTextShape)) return
                    try {
                        String t = shape.text?.trim()
                        if (t && t != title) bodyText << t
                    } catch (Exception ignored) {}
                }

                // Notes: filter shapes on notes slide to XSLFTextShape, find BODY placeholder
                String notes = ''
                try {
                    List<XSLFTextShape> notePhs = (slide.notes?.shapes ?: [])
                        .findAll { it instanceof XSLFTextShape }
                        .collect { it as XSLFTextShape }
                    XSLFTextShape notesPh = notePhs.find {
                        try { it.placeholderDetails?.placeholder == Placeholder.BODY }
                        catch (Exception e) { false }
                    } ?: notePhs.find { true }
                    notes = notesPh?.text?.trim() ?: ''
                } catch (Exception ignored) {}

                slides << ([slideIndex: idx, title: title, content: bodyText.join('\n'), notes: notes] as Map<String, Object>)
            }

            Dimension size = ppt.pageSize
            return successJson(requestId, [
                format    : 'pptx',
                path      : file.absolutePath,
                slideCount: slides.size(),
                slideSize : [width: size?.width ?: 0, height: size?.height ?: 0],
                slides    : slides
            ])
        } finally {
            ppt.close()
        }
    }

    // =========================================================================
    // PPTX write
    // =========================================================================

    private McpResponse writePptx(String path, Map options, Object requestId) {
        // DSL generate path — delegate to PptxAdapter when options.presentationPlan is present
        if (options.presentationPlan != null) {
            Object pResult = PptxAdapter.asFn().call('GENERATE', [filePath: path, plan: options.presentationPlan])
            return successJson(requestId, [action: 'write_office', format: 'pptx', path: path, result: pResult])
        }

        // Legacy flat-map path
        String templatePath                     = options.templatePath as String
        List<Map<String, Object>> slides        = options.slides as List<Map<String, Object>>

        XMLSlideShow ppt = templatePath ?
            new XMLSlideShow(new File(templatePath).newInputStream()) :
            new XMLSlideShow()

        try {
            XSLFSlideMaster master = ppt.slideMasters[0]

            slides?.each { Map<String, Object> spec ->
                String layoutName = (spec.layout ?: 'TITLE_AND_CONTENT') as String
                XSLFSlideLayout layout = master.slideLayouts.find {
                    it.name?.toUpperCase()?.replace(' ', '_') == layoutName.toUpperCase()
                } ?: master.slideLayouts[0]

                XSLFSlide slide = ppt.createSlide(layout)

                // slide.placeholders can include XSLFAutoShape (no placeholderDetails) — filter to XSLFTextShape only
                List<XSLFTextShape> phs = slide.shapes
                    .findAll { it instanceof XSLFTextShape }
                    .collect { it as XSLFTextShape }

                // Classify each shape: check placeholderDetails safely
                Closure<Placeholder> getType = { XSLFTextShape sh ->
                    try { sh.placeholderDetails?.placeholder } catch (Exception e) { null }
                }

                // Title: TITLE or CENTER_TITLE
                XSLFTextShape titlePh = phs.find { XSLFTextShape sh ->
                    def p = getType(sh)
                    p == Placeholder.TITLE || p == Placeholder.CENTERED_TITLE
                }
                if (titlePh && spec.title) {
                    try { titlePh.setText(spec.title as String) } catch (Exception ignored) {}
                }

                // Body: BODY placeholder, or first non-title text shape
                XSLFTextShape bodyPh = phs.find { XSLFTextShape sh ->
                    getType(sh) == Placeholder.BODY
                } ?: phs.find { it != titlePh }
                if (bodyPh && spec.content) {
                    try {
                        def body = spec.content
                        bodyPh.setText(body instanceof List ? (body as List).join('\n') : body.toString())
                    } catch (Exception ignored) {}
                }

                // Speaker notes
                if (spec.notes) {
                    try {
                        List<XSLFTextShape> notePhs = (slide.notes?.shapes ?: [])
                            .findAll { it instanceof XSLFTextShape }
                            .collect { it as XSLFTextShape }
                        XSLFTextShape notesPh = notePhs.find {
                            getType(it) == Placeholder.BODY
                        } ?: notePhs.find { true }
                        if (notesPh) notesPh.setText(spec.notes as String)
                    } catch (Exception ignored) {}
                }
            }

            new File(path).withOutputStream { OutputStream out -> ppt.write(out) }

            return successJson(requestId, [
                action       : 'write_office',
                format       : 'pptx',
                path         : path,
                slidesWritten: slides?.size() ?: 0
            ])
        } finally {
            ppt.close()
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static String resolveFormat(String path, Map options) {
        if (options?.format) return (options.format as String).toLowerCase()
        String ext = path?.tokenize('.')?.last()?.toLowerCase()
        if (ext in ['xlsx', 'xls'])  return 'xlsx'
        if (ext in ['docx', 'doc'])  return 'docx'
        if (ext in ['pptx', 'ppt'])  return 'pptx'
        return ext ?: 'unknown'
    }

    private static McpResponse successJson(Object requestId, Map payload) {
        McpResponse.success(requestId, [
            content: [[type: 'text', text: JsonOutput.toJson(payload)]]
        ] as Map<String, Object>)
    }

    private static String sanitize(String msg) {
        msg?.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '') ?: ''
    }
}