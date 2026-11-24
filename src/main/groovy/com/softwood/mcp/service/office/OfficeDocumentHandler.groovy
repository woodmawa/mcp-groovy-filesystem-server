package com.softwood.mcp.service.office

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.PathService
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.softwood.dag.task.office.OfficePlanBinder
import org.softwood.dag.task.office.OfficeTools
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import java.util.function.Predicate

/**
 * OfficeDocumentHandler -- the read_office / write_office tool actions.
 *
 * <p>FS 0.12.0 (decision 499): FS keeps only the MCP tool concerns -- the allowed-directories check on every path,
 * the format, and the response shape. Everything office is GCU gcu-office's {@link OfficeTools}, the one
 * implementation AW and FS share; FS has no POI code of its own. Before 0.12.0 this class carried its own POI path
 * for plain-map requests and templates beside the GCU plan path, and AW carried a third copy.</p>
 *
 * <pre>
 * read_office  options: format (xlsx|docx|pptx, or from the extension); xlsx: sheet, range, outputFormat
 *              (list_of_maps | list_of_lists | map_by_key), keyColumn, or queryPlan
 * write_office options: format; xlsx: sheet, headers, rows -- or workbookPlan with action GENERATE|APPEND|TRANSFORM
 *              and data; docx: content ({heading: body} or, for order, [{heading, body}]), templatePath -- or
 *              documentPlan; pptx: slides ([{layout, title, content, notes}]), templatePath -- or presentationPlan
 * </pre>
 *
 * <p>Every other option is refused by name and nothing is written. Every path -- the call's own, a template and any
 * image a plan names -- must be inside the allowed directories (OfficePlanBinder with FS's path rule).</p>
 */
@Component
@Slf4j
class OfficeDocumentHandler {

    @Autowired
    PathService pathService

    // =========================================================================
    // READ
    // =========================================================================

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
            Map config = toolOptions(options) + [filePath: file.absolutePath]
            OfficePlanBinder binder = binderFor(allowed)
            switch (format) {
                case 'xlsx':
                    Object r = OfficeTools.xlsx(binder).call('READ', config)
                    if (options?.queryPlan != null) return successJson(requestId, [format: 'xlsx', path: file.absolutePath, data: r])
                    Map m = r as Map
                    Map out = [format: 'xlsx', sheet: m.sheet, headers: m.headers, rowCount: m.rowCount]
                    if (m.headers) out.outputFormat = (options?.outputFormat ?: 'list_of_maps') as String
                    out.data = m.data
                    return successJson(requestId, out)
                case 'docx':
                    return successJson(requestId, [format: 'docx', path: file.absolutePath] + (OfficeTools.docx(binder).call('READ', config) as Map))
                case 'pptx':
                    return successJson(requestId, [format: 'pptx', path: file.absolutePath] + (OfficeTools.pptx(binder).call('READ', config) as Map))
                default:
                    return McpResponse.toolError(requestId, "read_office: unsupported format '${format}'. Use xlsx|docx|pptx or set options.format.")
            }
        } catch (IllegalArgumentException e) {
            // a request GCU refused by name: the caller's to fix, not a server fault
            log.warn("read_office refused on ${path}: ${e.message}")
            return McpResponse.toolError(requestId, sanitize("read_office refused: ${e.message}"))
        } catch (Exception e) {
            log.error("read_office error on ${path}: ${e.message}", e)
            return McpResponse.toolError(requestId, sanitize("read_office failed: ${e.message}"))
        }
    }

    // =========================================================================
    // WRITE
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
            Map config = toolOptions(options)
            config.remove('action')
            config.filePath = validated
            switch (format) {
                case 'xlsx':
                    boolean plan = options?.workbookPlan != null
                    String action = plan ? ((options.action ?: 'GENERATE') as String).toUpperCase() : 'WRITE'
                    if (!plan && options?.action != null) {
                        return McpResponse.toolError(requestId, 'write_office: options.action goes with a workbookPlan (GENERATE, APPEND or TRANSFORM)')
                    }
                    Object r = OfficeTools.xlsx(binder).call(action, config)
                    if (plan) return successJson(requestId, [action: 'write_office', format: 'xlsx', path: validated, result: r])
                    return successJson(requestId, [action: 'write_office', format: 'xlsx', path: validated, sheet: (r as Map).sheet, rowsWritten: (r as Map).rowsWritten])
                case 'docx':
                    Map d = OfficeTools.docx(binder).call('GENERATE', config) as Map
                    return successJson(requestId, options?.documentPlan != null
                        ? [action: 'write_office', format: 'docx', path: validated, result: d]
                        : [action: 'write_office', format: 'docx', path: validated, sections: d.sections])
                case 'pptx':
                    Map pr = OfficeTools.pptx(binder).call('GENERATE', config) as Map
                    return successJson(requestId, options?.presentationPlan != null
                        ? [action: 'write_office', format: 'pptx', path: validated, result: pr]
                        : [action: 'write_office', format: 'pptx', path: validated, slidesWritten: pr.slidesWritten])
                default:
                    return McpResponse.toolError(requestId, "write_office: unsupported format '${format}'. Use xlsx|docx|pptx or set options.format.")
            }
        } catch (IllegalArgumentException e) {
            log.warn("write_office refused on ${path}: ${e.message}")
            return McpResponse.toolError(requestId, sanitize("write_office refused: ${e.message}"))
        } catch (Exception e) {
            log.error("write_office error on ${path}: ${e.message}", e)
            return McpResponse.toolError(requestId, sanitize("write_office failed: ${e.message}"))
        }
    }

    // =========================================================================
    // Tool concerns
    // =========================================================================

    private OfficePlanBinder binderFor(Predicate<String> allowed) {
        new OfficePlanBinder(allowed, { String p -> pathService.normalizePath(p) })
    }

    /** The caller's options as an OfficeTools request: FS's own key (format) and nulls removed. */
    private static Map toolOptions(Map options) {
        Map out = [:]
        (options ?: [:]).each { Object k, Object v -> if (v != null && k != 'format') out[k as String] = v }
        return out
    }

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
