package com.softwood.mcp.service.read

import groovy.transform.CompileStatic

import java.util.regex.Pattern

/**
 * FS 0.9.127 read efficiency (c): the lines {@code file_read range symbol=Class.method} reads.
 *
 * <p>Order of trust: the CS ontology locate answer, when it names THIS file, its source_line still holds the symbol's
 * name (the index lags an edit by one async reindex) and it gives an end line -- {@code locate}. A good locate with no
 * end line (0 for handleForAction, 2026-11-07) takes FS's own structure bounds for the entry starting there --
 * {@code locate+structure}. A locate that is absent, stale or about another file is set aside and FS's structure is
 * asked by name -- {@code structure}. Neither: null, and the caller refuses SYMBOL_NOT_FOUND.</p>
 *
 * <p>Pure: the file's lines and structure entries are handed in.</p>
 */
@CompileStatic
class SymbolResolver {

    static final Set<String> TYPE_KINDS = ['class', 'interface', 'enum', 'trait', 'record', 'annotation'] as Set<String>

    /** {startLine, endLine, resolved_by} or null. */
    static Map<String, Object> resolve(String symbol, String normalizedPath, Map<String, Object> locate,
                                       Closure<String> normalise, List<String> fileLines, List<Map> structure) {
        if (!symbol?.trim()) return null
        String sym = symbol.trim()
        int dot = sym.lastIndexOf('.')
        String simple = dot >= 0 ? sym.substring(dot + 1) : sym
        String owner = dot >= 0 ? sym.substring(0, dot) : null
        String ownerSimple = owner == null ? null : owner.substring(owner.lastIndexOf('.') + 1)
        if (!simple) return null

        if (locate != null && locate.get('found') == true && sameFile(locate.get('source_file') as String, normalizedPath, normalise)) {
            int sl = toInt(locate.get('source_line'))
            int el = toInt(locate.get('end_line'))
            if (sl > 0 && sl <= fileLines.size() && mentions(fileLines[sl - 1], simple)) {
                if (el >= sl && el <= fileLines.size()) return bounds(sl, el, 'locate')
                Map at = (structure ?: [] as List<Map>).find { Map e -> toInt(e.get('line')) == sl && toInt(e.get('endLine')) >= sl }
                if (at != null) return bounds(sl, toInt(at.get('endLine')), 'locate+structure')
            }
        }

        Map e = byName(structure ?: [] as List<Map>, simple, ownerSimple)
        if (e != null) {
            int sl = toInt(e.get('line'))
            int el = toInt(e.get('endLine'))
            if (sl > 0 && el >= sl) return bounds(sl, el, 'structure')
        }
        return null
    }

    private static Map byName(List<Map> structure, String simple, String ownerSimple) {
        Pattern call = Pattern.compile('\\b' + Pattern.quote(simple) + '\\s*\\(')
        Pattern type = Pattern.compile('\\b(class|interface|enum|trait|record)\\s+' + Pattern.quote(simple) + '\\b')
        List<Map> methods = structure.findAll { Map e ->
            (e.get('type') == 'method' || e.get('type') == 'ctor') && call.matcher(String.valueOf(e.get('content'))).find()
        }
        if (ownerSimple != null) {
            Map owned = methods.find { Map e -> ownerMatches(e.get('owner') as String, ownerSimple) }
            if (owned != null) return owned
        }
        if (!methods.isEmpty()) return methods[0]
        return structure.find { Map e ->
            TYPE_KINDS.contains(String.valueOf(e.get('type'))) && type.matcher(String.valueOf(e.get('content'))).find()
        }
    }

    private static boolean ownerMatches(String owner, String ownerSimple) {
        if (!owner) return false
        String o = owner.substring(owner.lastIndexOf('.') + 1)
        return o == ownerSimple || o.endsWith('$' + ownerSimple)
    }

    private static boolean sameFile(String locateFile, String normalizedPath, Closure<String> normalise) {
        if (!locateFile || !normalizedPath) return false
        String a
        try { a = normalise != null ? normalise.call(locateFile) : locateFile } catch (Exception ignored) { a = locateFile }
        return a.replace('\\', '/').equalsIgnoreCase(normalizedPath.replace('\\', '/'))
    }

    private static boolean mentions(String line, String simple) {
        return line != null && Pattern.compile('\\b' + Pattern.quote(simple) + '\\b').matcher(line).find()
    }

    private static Map<String, Object> bounds(int start, int end, String how) {
        Map<String, Object> m = new LinkedHashMap<String, Object>()
        m.put('startLine', (Object) start)
        m.put('endLine', (Object) end)
        m.put('resolved_by', (Object) how)
        return m
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue()
        try { return o == null ? 0 : Integer.parseInt(o.toString().trim()) } catch (Exception ignored) { return 0 }
    }
}
