package com.softwood.mcp.service.read

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * FS 0.9.128 -- brief 2026-11-07 item 4 (a) and (b): the member a line sits in, from FS's own structure entries
 * ({@code line}, {@code endLine}, {@code type}, {@code content}, {@code owner} -- AstStructureScanner).
 *
 * <p>Put the cheaper path in the data: a grep hit that says {@code in: CompilerQueue.dispose} makes
 * {@code range symbol=CompilerQueue.dispose} the obvious next call, where 2026-11-07 read lines 98-142 to reach it.
 * Methods and constructors only -- a class or field is not a read target. Pure: the structure is handed in.</p>
 */
@CompileStatic
class EnclosingMember {

    static final Set<String> MEMBER_KINDS = ['method', 'ctor'] as Set<String>
    private static final Pattern NAME = Pattern.compile('([A-Za-z_$][\\w$]*)\\s*\\(')

    /** True for the files AstStructureScanner reads. */
    static boolean isCode(String path) {
        String p = (path ?: '').toLowerCase()
        return p.endsWith('.groovy') || p.endsWith('.java')
    }

    /** The innermost method or constructor containing {@code line}: {name, startLine, endLine}, or null. */
    static Map<String, Object> at(List<Map> structure, int line) {
        Map best = null
        for (Map e : (structure ?: [] as List<Map>)) {
            if (!MEMBER_KINDS.contains(String.valueOf(e.get('type')))) continue
            int s = toInt(e.get('line')), en = toInt(e.get('endLine'))
            if (s <= 0 || en < s || line < s || line > en) continue
            if (best == null || (en - s) < (toInt(best.get('endLine')) - toInt(best.get('line')))) best = e
        }
        if (best == null) return null
        String name = nameOf(best)
        if (!name) return null
        Map<String, Object> m = new LinkedHashMap<String, Object>()
        m.put('name', (Object) name)
        m.put('startLine', (Object) toInt(best.get('line')))
        m.put('endLine', (Object) toInt(best.get('endLine')))
        return m
    }

    /** The member holding every line from {@code start} to {@code end}, or null when the span leaves it. */
    static Map<String, Object> spanning(List<Map> structure, int start, int end) {
        Map<String, Object> m = at(structure, start)
        if (m == null || end < start) return null
        return (end <= (m.get('endLine') as int)) ? m : null
    }

    /** Owner.member -- the form range symbol= takes. */
    static String nameOf(Map e) {
        String owner = e.get('owner') as String
        if (owner) owner = owner.substring(Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$')) + 1)
        Matcher mt = NAME.matcher(String.valueOf(e.get('content')))
        String member = mt.find() ? mt.group(1) : null
        if (String.valueOf(e.get('type')) == 'ctor' && owner) member = owner
        if (!member) return null
        return owner ? (owner + '.' + member) : member
    }

    /** Stamp {@code in} on each match map whose line sits inside a member; returns how many were stamped. */
    static int annotate(List matches, List<Map> structure) {
        if (!matches || !structure) return 0
        int n = 0
        for (Object o : matches) {
            if (!(o instanceof Map)) continue
            Map mm = (Map) o
            int line = toInt(mm.get('line'))
            if (line <= 0) continue
            Map<String, Object> at = at(structure, line)
            if (at != null) { mm.put('in', at.get('name')); n++ }
        }
        return n
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue()
        try { return o == null ? 0 : Integer.parseInt(o.toString().trim()) } catch (Exception ignored) { return 0 }
    }
}
