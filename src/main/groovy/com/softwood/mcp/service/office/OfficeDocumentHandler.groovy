package com.softwood.mcp.service.office

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.write.WriteUtils
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
import java.nio.file.Paths
import java.util.function.Predicate
import org.softwood.dag.task.xlsx.QueryPlan
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
 *
 * Plans (FS 0.11.4) -- the full GCU office model, bound to GCU's typed plans by {@link OfficePlanBinder}:
 *   workbookPlan     (xlsx write; options.action GENERATE | APPEND | TRANSFORM, options.data for deferredRows)
 *   documentPlan     (docx write; options.templatePath optional)
 *   presentationPlan (pptx write)
 *   queryPlan        (xlsx read: sheet, range, columns, format, keyColumn, coercions, where)
 * The reference is file_read action=help topic=office. A field that is unknown, or that GCU cannot apply, is refused
 * by name and nothing is written.
 *
 * FS 0.11.4: every path -- the call's own, a template and any image a plan names -- must be inside the allowed
 * directories. Before 0.11.4 read_office and write_office skipped that check, unlike every other FS action.
 */
@Component
@Slf4j
class OfficeDocumentHandler {

    @Autowired
    PathService pathService

    // =========================================================================
    // READ dispatch
    // =========================================================================

    /** @param allowed the calling service's allowed-directories check (AbstractFileService.isPathAllowed) */
    McpResponse readOffice(String path, Map<String, Object> options, Object requestId, Predicate<String> allowed) {
        String format = resolveFormat(path, options)
        try {
            String validated = pathService.normalizePath(path)
            if (!allowed.test(validated)) {
                return McpResponse.toolError(requestId, sanitize("read_office: path not allowed: ${validated}"))
            }
            File file = new File(validated)
            if (!file.exists()) {
                return McpResponse.toolError(requestId, "read_office: file not found: ${validated}")
            }
            OfficePlanBinder binder = binderFor(allowed)
            switch (format) {
                case 'xlsx': return readXlsx(file, options, requestId, binder)
                case 'docx': return readDocx(file, options, requestId)
                case 'pptx': return readPptx(file, options, requestId)
                default:
                    return McpResponse.toolError(requestId, "read_office: unsupported format '${format}'. Use xlsx|docx|pptx or set options.format.")
            }
        } catch (IllegalArgumentException e) {
            // a plan FS or GCU refused by name: the caller's to fix, not a server fault
            log.warn("read_office refused on ${path}: ${e.message}")
            return McpResponse.toolError(requestId, sanitize("read_office refused: ${e.message}"))
        } catch (Exception e) {
            log.error("read_office error on ${path}: ${e.message}", e)
            return McpResponse.toolError(requestId, sanitize("read_office failed: ${e.message}"))
        }
    }

    // =========================================================================
    // WRITE dispatch
    // =========================================================================

    /** @param allowed the calling service's allowed-directories check (AbstractFileService.isPathAllowed) */
    McpResponse writeOffice(String path, Map<String, Object> options, Object requestId, Predicate<String> allowed) {
        String format = resolveFormat(path, options)
        try {
            String validated = pathService.normalizePath(path)
            // checked before anything is created: a refused path must not even leave a directory behind
            if (!allowed.test(validated)) {
                return McpResponse.toolError(requestId, sanitize("write_office: path not allowed: ${validated}"))
            }
            OfficePlanBinder binder = binderFor(allowed)
            new File(validated).parentFile?.mkdirs()
            switch (format) {
                case 'xlsx': return writeXlsx(validated, options, requestId, binder)
                case 'docx': return writeDocx(validated, options, requestId, binder)
                case 'pptx': return writePptx(validated, options, requestId, binder)
                default:
                    return McpResponse.toolError(requestId, "write_office: unsupported format '${format}'. Use xlsx|docx|pptx or set options.format.")
            }
        } catch (IllegalArgumentException e) {
            // a plan FS or GCU refused by name: the caller's to fix, not a server fault
            log.warn("write_office refused on ${path}: ${e.message}")
            return McpResponse.toolError(requestId, sanitize("write_office refused: ${e.message}"))
        } catch (Exception e) {
            log.error("write_office error on ${path}: ${e.message}", e)
            return McpResponse.toolError(requestId, sanitize("write_office failed: ${e.message}"))
        }
    }

    private OfficePlanBinder binderFor(Predicate<String> allowed) {
        new OfficePlanBinder(allowed, { String p -> pathService.normalizePath(p) })
    }

    // =========================================================================
    // XLSX read
    // =========================================================================

    private McpResponse readXlsx(File file, Map options, Object requestId, OfficePlanBinder binder) {
        // Query path — GCU's QueryExecutor, the JSON plan bound to a QueryPlan (FS 0.11.4: it was passed raw)
        if (options.queryPlan != null) {
            QueryPlan q = binder.queryPlan(options.queryPlan)
            Object qResult = XlsxAdapter.asFn().call('QUERY', [filePath: file.absolutePath, query: q])
            return successJson(requestId, [format: 'xlsx', path: file.absolutePath, data: qResult])
        }

        // FS 0.11.4: loaded from a stream, so the file is not held open while the result is built
        Workbook wb = file.withInputStream { InputStream ins -> new XSSFWorkbook(ins) }
        try {
            String sheetName   = options.sheet as String
            Sheet sheet        = sheetName ? wb.getSheet(sheetName) : wb.getSheetAt(0)
            if (!sheet) {
                List<String> avail = (0..<wb.numberOfSheets).collect { wb.getSheetName(it as int) }
                return McpResponse.toolError(requestId, "read_office xlsx: sheet '${sheetName}' not found. Available: ${avail}")
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
                        return McpResponse.toolError(requestId, "read_office xlsx map_by_key: keyColumn '${keyCol}' not in headers ${headers}")
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

    private McpResponse writeXlsx(String path, Map options, Object requestId, OfficePlanBinder binder) {
        // Plan path — GCU's XlsxAdapter, the JSON plan bound to a WorkbookPlan (FS 0.11.4: it was passed raw)
        if (options.workbookPlan != null) {
            String xlAction = ((options.action ?: 'GENERATE') as String).toUpperCase()
            if (!(xlAction in PLAN_WRITE_ACTIONS)) {
                return McpResponse.toolError(requestId, "write_office: options.action '${xlAction}' with a workbookPlan must be one of ${PLAN_WRITE_ACTIONS}")
            }
            if (options.data != null && !(options.data instanceof Map)) {
                return McpResponse.toolError(requestId, 'write_office: options.data must map each deferredRows dataKey to its rows')
            }
            WorkbookPlan plan = binder.workbookPlan(options.workbookPlan)
            Object wResult = XlsxAdapter.asFn().call(xlAction, [filePath: path, plan: plan, data: (options.data ?: [:]) as Map])
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

        // FS 0.11.3: load an existing workbook into memory from a stream. new XSSFWorkbook(File) keeps
        // the file open as a lazily-read package, so saving back to the same path failed
        // ("/docProps/app.xml failed to be saved"). OfficeXlsxRoundTripSpec.
        File existing = new File(path)
        Workbook wb   = existing.exists() ? existing.withInputStream { InputStream ins -> new XSSFWorkbook(ins) } : new XSSFWorkbook()
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

            ByteArrayOutputStream buf = new ByteArrayOutputStream()
            wb.write(buf)
            WriteUtils.atomicWrite(Paths.get(path), buf.toByteArray())

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
        // FS 0.11.4: the stream is closed once read (it was left open, holding the file on Windows)
        XWPFDocument doc = file.withInputStream { InputStream ins -> new XWPFDocument(ins) }
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

    private McpResponse writeDocx(String path, Map options, Object requestId, OfficePlanBinder binder) {
        String templatePath = binder.templatePath(options.templatePath, 'options.templatePath')

        // Plan path — GCU's DocxAdapter, the JSON plan bound to a DocumentPlan (FS 0.11.4: it was passed raw)
        if (options.documentPlan != null) {
            DocumentPlan plan = binder.documentPlan(options.documentPlan)
            Map config = [filePath: path, documentPlan: plan]
            if (templatePath) config.templatePath = templatePath
            Object dResult = DocxAdapter.asFn().call('GENERATE', config)
            return successJson(requestId, [action: 'write_office', format: 'docx', path: path, result: dResult])
        }

        // Legacy flat-map path
        Map<String, Object> content     = options.content as Map<String, Object>

        XWPFDocument doc = templatePath ?
            new File(templatePath).withInputStream { InputStream ins -> new XWPFDocument(ins) } :
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

            ByteArrayOutputStream buf = new ByteArrayOutputStream()
            doc.write(buf)
            WriteUtils.atomicWrite(Paths.get(path), buf.toByteArray())

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
        // FS 0.11.4: the stream is closed once read (it was left open, holding the file on Windows)
        XMLSlideShow ppt = file.withInputStream { InputStream ins -> new XMLSlideShow(ins) }
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

    private McpResponse writePptx(String path, Map options, Object requestId, OfficePlanBinder binder) {
        String templatePath = binder.templatePath(options.templatePath, 'options.templatePath')

        // Plan path — GCU's PptxAdapter, the JSON plan bound to a PresentationPlan (FS 0.11.4: it was passed raw)
        if (options.presentationPlan != null) {
            if (templatePath) {
                return McpResponse.toolError(requestId, 'write_office: templatePath is not supported with a presentationPlan (GCU PptxAdapter builds a new presentation)')
            }
            PresentationPlan plan = binder.presentationPlan(options.presentationPlan)
            Object pResult = PptxAdapter.asFn().call('GENERATE', [filePath: path, presentationPlan: plan])
            return successJson(requestId, [action: 'write_office', format: 'pptx', path: path, result: pResult])
        }

        // Legacy flat-map path
        List<Map<String, Object>> slides        = options.slides as List<Map<String, Object>>

        XMLSlideShow ppt = templatePath ?
            new File(templatePath).withInputStream { InputStream ins -> new XMLSlideShow(ins) } :
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

            ByteArrayOutputStream buf = new ByteArrayOutputStream()
            ppt.write(buf)
            WriteUtils.atomicWrite(Paths.get(path), buf.toByteArray())

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

    private static final List<String> PLAN_WRITE_ACTIONS = ['GENERATE', 'APPEND', 'TRANSFORM']

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