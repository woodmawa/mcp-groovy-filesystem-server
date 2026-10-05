package com.softwood.mcp.service.read

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * FS 0.9.67 -- value-review-phase3 item 3, CAPTURE (decision 272).
 *
 * A relative response-size threshold needs the window a range call ASKED for and the lines it
 * RETURNED; telemetry recorded neither. This derives both for one call so they ride on the
 * tool_call_telemetry row (req_start_line, req_max_lines, lines_returned). CS records the other half,
 * the span a locate reported, on its own locate rows.
 *
 * Runs on the McpController hot path (practice #260): no I/O and no JSON parse of the response -- a
 * range response can be tens of KB. Anchored regexes on the serialised text find the top-level keys;
 * file content inside the response is a JSON string, so its quotes arrive escaped and cannot match.
 */
@CompileStatic
class RangeTelemetry {

    static final int DEFAULT_MAX_LINES = 100

    private static final Pattern HELD     = ~/"(?:unchanged|cached)"\s*:\s*true/
    private static final Pattern LINES    = ~/"lines"\s*:\s*(\d+)/
    private static final Pattern START    = ~/"startLine"\s*:\s*(\d+)/
    private static final Pattern END_LINE = ~/"endLine"\s*:\s*(\d+)/

    /** Null unless this is file_read action=range. */
    static Map<String, Object> detail(String toolName, Map args, String responseText) {
        if (toolName != 'file_read' || args?.get('action') != 'range') return null
        Map opts = args.get('options') instanceof Map ? (Map) args.get('options') : [:]
        Integer start = toInt(opts.get('startLine'))
        int s = start != null && start > 0 ? start : 1
        Integer max = toInt(opts.get('maxLines'))
        Integer end = toInt(opts.get('endLine'))
        int req = max != null && max > 0 ? max : (end != null && end >= s ? end - s + 1 : DEFAULT_MAX_LINES)

        Map<String, Object> out = new LinkedHashMap<String, Object>()
        out.put('req_start_line', (Object) s)
        out.put('req_max_lines', (Object) req)
        out.put('lines_returned', (Object) linesReturned(responseText))
        return out
    }

    /** Lines actually sent: 0 for a held window, null when the response says nothing (an error). */
    static Integer linesReturned(String text) {
        if (!text) return null
        if (HELD.matcher(text).find()) return 0
        Matcher l = LINES.matcher(text)
        if (l.find()) return Integer.valueOf(l.group(1))
        Matcher a = START.matcher(text)
        Matcher b = END_LINE.matcher(text)
        if (a.find() && b.find()) {
            int n = Integer.parseInt(b.group(1)) - Integer.parseInt(a.group(1)) + 1
            return n > 0 ? n : 0
        }
        return null
    }

    private static Integer toInt(Object o) {
        if (o == null) return null
        if (o instanceof Number) return ((Number) o).intValue()
        try { return Integer.valueOf(o.toString().trim()) } catch (Exception ignored) { return null }
    }
}
