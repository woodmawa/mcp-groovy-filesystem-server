package com.softwood.mcp.service.office

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import groovy.transform.CompileDynamic
import org.softwood.dag.task.docx.DocumentPlan
import org.softwood.dag.task.pptx.PresentationPlan
import org.softwood.dag.task.xlsx.QueryPlan
import org.softwood.dag.task.xlsx.SpreadsheetCell
import org.softwood.dag.task.xlsx.SpreadsheetRow
import org.softwood.dag.task.xlsx.WorkbookPlan

import java.time.LocalDate
import java.util.function.Predicate

/**
 * OfficePlanBinder -- FS 0.11.4.
 *
 * <p>Turns the JSON-shaped maps a caller sends as {@code workbookPlan}, {@code documentPlan},
 * {@code presentationPlan} and {@code queryPlan} into GCU's typed plan objects. Before 0.11.4 the raw maps were
 * handed to the GCU adapters, which expect typed plans, so every plan call failed (a nested sheet arrived as a
 * LinkedHashMap where a SheetPlan was needed).</p>
 *
 * <p>Binding is by Jackson with unknown fields refused: a misspelt or unsupported key comes back naming the key, the
 * place in the plan and the fields that are known, and nothing is written. GCU's own adapters (2.0.1) then refuse,
 * by name, any field they cannot apply.</p>
 *
 * <p>Paths inside a plan ({@code templatePath}, cell and shape {@code imagePath}) must be inside FS's allowed
 * directories, like the call's own path; URLs are refused, so a plan cannot make FS read outside them or fetch from
 * the network.</p>
 *
 * <p>A query takes no code: {@code rowPredicate} (a Groovy closure) is refused, and {@code where} -- column to value,
 * or column to a list of values -- is the filter. Coercions name a type from a fixed list; no class is loaded from
 * the caller's input.</p>
 */
@CompileDynamic
class OfficePlanBinder {

    /** Hide Groovy's metaClass from Jackson, so it is neither bound nor listed among a refused key's known fields. */
    @JsonIgnoreProperties(['metaClass'])
    static abstract class NoMetaClass {}

    static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .addMixIn(GroovyObject, NoMetaClass)

    static final Set<String> QUERY_KEYS    = ['sheet', 'sheetName', 'range', 'rangeRef', 'columns', 'format', 'keyColumn', 'coercions', 'where'] as Set
    static final Set<String> QUERY_FORMATS = ['list_of_maps', 'list_of_lists', 'map_by_key'] as Set
    static final Map<String, Class> COERCIONS = [Double: Double, Integer: Integer, Boolean: Boolean, LocalDate: LocalDate, String: String]

    private final Predicate<String> allowed
    private final Closure<String> normalise

    /**
     * @param allowed   true for a normalised path inside FS's allowed directories
     * @param normalise FS's path normalisation (WSL / relative / slashes)
     */
    OfficePlanBinder(Predicate<String> allowed, Closure<String> normalise) {
        this.allowed   = allowed
        this.normalise = normalise
    }

    WorkbookPlan workbookPlan(Object raw) {
        WorkbookPlan p = bind(raw, WorkbookPlan, 'workbookPlan')
        if (p.templatePath) p.templatePath = checkedPath(p.templatePath, 'workbookPlan.templatePath')
        p.sheets.eachWithIndex { sp, int i ->
            sp.rows?.eachWithIndex { r, int j ->
                r.cells?.eachWithIndex { c, int k ->
                    if (c.imagePath) c.imagePath = checkedPath(c.imagePath, "workbookPlan.sheets[${i}].rows[${j}].cells[${k}].imagePath")
                }
            }
        }
        return p
    }

    DocumentPlan documentPlan(Object raw) {
        bind(raw, DocumentPlan, 'documentPlan')
    }

    PresentationPlan presentationPlan(Object raw) {
        PresentationPlan p = bind(raw, PresentationPlan, 'presentationPlan')
        p.slides.eachWithIndex { s, int i ->
            s.shapes?.eachWithIndex { sh, int j ->
                if (sh.imagePath) sh.imagePath = checkedPath(sh.imagePath, "presentationPlan.slides[${i}].shapes[${j}].imagePath")
            }
        }
        return p
    }

    /** A template path for write_office (docx and pptx take it as an option, not in the plan). */
    String templatePath(Object raw, String where) {
        raw == null ? null : checkedPath(raw.toString(), where)
    }

    QueryPlan queryPlan(Object raw) {
        Map m = requireMap(raw, 'queryPlan')
        List<String> problems = []
        if (m.containsKey('rowPredicate')) {
            problems << 'queryPlan.rowPredicate is not accepted: FS does not compile code from a plan -- use where: {column: value or [values]}'
        }
        m.keySet().each { Object k ->
            if (k != 'rowPredicate' && !QUERY_KEYS.contains(k as String)) problems << "queryPlan: unknown field '${k}' (known: ${QUERY_KEYS.sort().join(', ')})".toString()
        }
        String format = (m.format ?: 'list_of_maps') as String
        if (!QUERY_FORMATS.contains(format)) problems << "queryPlan.format '${format}' is not one of ${QUERY_FORMATS.sort().join(', ')}".toString()
        if (format == 'map_by_key' && !m.keyColumn) problems << 'queryPlan.keyColumn is required for format map_by_key'
        if (m.columns != null && !(m.columns instanceof List)) problems << 'queryPlan.columns must be a list of header names or column letters'
        Map<String, Class> coercions = [:]
        if (m.coercions != null) {
            if (!(m.coercions instanceof Map)) problems << 'queryPlan.coercions must map a column to a type name'
            else (m.coercions as Map).each { Object col, Object type ->
                Class c = COERCIONS[type as String]
                if (c == null) problems << "queryPlan.coercions.${col}: type '${type}' is not one of ${COERCIONS.keySet().sort().join(', ')}".toString()
                else coercions[col as String] = c
            }
        }
        if (m.where != null && !(m.where instanceof Map)) problems << 'queryPlan.where must map a column to a value or a list of values'
        refuse(problems)

        QueryPlan q = new QueryPlan(
            sheetName: (m.sheet ?: m.sheetName) as String,
            rangeRef : (m.range ?: m.rangeRef) as String,
            columns  : ((m.columns ?: []) as List).collect { it as String },
            format   : format,
            keyColumn: m.keyColumn as String,
            coercions: coercions)
        if (m.where) q.rowPredicate = wherePredicate(m.where as Map)
        return q
    }

    /** A row matches when every named column equals its value, or one of its values (numbers compared as numbers). */
    static Closure wherePredicate(Map where) {
        Map<String, Object> w = new LinkedHashMap<>(where)
        return { SpreadsheetRow row ->
            w.every { String col, Object expected ->
                SpreadsheetCell cell = row.getAt(col)
                List wanted = expected instanceof List ? (expected as List) : [expected]
                wanted.any { Object e -> matches(cell, e) }
            }
        }
    }

    private static boolean matches(SpreadsheetCell cell, Object expected) {
        if (expected == null) return cell == null || cell.value == null
        if (cell == null) return false
        if (expected instanceof Number) {
            Double d = null
            try { d = cell.asDouble() } catch (Exception ignored) { }
            return d != null && d == (expected as Number).doubleValue()
        }
        if (expected instanceof Boolean) return cell.asBool() == expected
        return cell.asString() == expected.toString()
    }

    // ------------------------------------------------------------------ internals

    private <T> T bind(Object raw, Class<T> type, String root) {
        Map m = requireMap(raw, root)
        try {
            return MAPPER.convertValue(m, type)
        } catch (IllegalArgumentException e) {
            Throwable c = e.cause
            if (c instanceof UnrecognizedPropertyException) {
                UnrecognizedPropertyException u = (UnrecognizedPropertyException) c
                List known = (u.knownPropertyIds ?: []).collect { it.toString() }.sort()
                throw new OfficePlanException("${root}${pathOf(u, true)}: unknown field '${u.propertyName}' (known: ${known.join(', ')})".toString())
            }
            if (c instanceof JsonMappingException) {
                JsonMappingException j = (JsonMappingException) c
                throw new OfficePlanException("${root}${pathOf(j, false)}: ${j.originalMessage}".toString())
            }
            throw new OfficePlanException("${root}: ${e.message}".toString())
        }
    }

    /** Where in the plan, e.g. {@code .sheets[0].rows[2]}. For an unknown field the last step is that field: left off. */
    private static String pathOf(JsonMappingException e, boolean dropLast) {
        List refs = e.path ?: []
        if (dropLast && refs) refs = refs.subList(0, refs.size() - 1)
        refs.collect { ref -> ref.fieldName != null ? ".${ref.fieldName}" : "[${ref.index}]" }.join('')
    }

    private static Map requireMap(Object raw, String root) {
        if (!(raw instanceof Map)) throw new OfficePlanException("${root} must be a JSON object, got ${raw == null ? 'nothing' : raw.getClass().simpleName}".toString())
        return (Map) raw
    }

    private String checkedPath(String p, String where) {
        if (p ==~ /(?i)^[a-z][a-z0-9+.-]+:\/\/.*/) throw new OfficePlanException("${where}: URLs are not accepted (${p}) -- give a file inside FS's allowed directories".toString())
        String n = normalise.call(p)
        if (!allowed.test(n)) throw new OfficePlanException("${where}: path not allowed: ${n}".toString())
        return n
    }

    private static void refuse(List<String> problems) {
        if (problems) throw new OfficePlanException(problems.join('; '))
    }
}

/** A plan FS cannot bind or will not accept; the message names the field and where it is. Nothing is written. */
@CompileDynamic
class OfficePlanException extends IllegalArgumentException {
    OfficePlanException(String message) { super(message) }
}
