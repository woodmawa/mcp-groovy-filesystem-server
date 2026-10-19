package com.softwood.mcp.service

import groovy.transform.CompileStatic

/**
 * WP1 (optimised-learning, decisions 382/383, Will 2026-10-19): refuse a key the tool does not read, before dispatch.
 *
 * <p>Until FS 0.9.106 every tool dropped an unknown key -- top level or inside {@code options} -- and answered as if it
 * had not been sent. That is the soft-accept the 2026-10-19 assessment found is never noticed under pressure: a
 * success that quietly answered a different question. Only a refusal changes what the caller does next.</p>
 *
 * <p>Schema-derived, not a ledger of reads: file_read iterates its top-level args and copies options by design
 * (FS 0.9.46 promotion), so a recording map would see a bulk read on every call and never refuse anything. A key
 * is accepted when the tool's declared inputSchema has it, or the tool's own code reads it without declaring it
 * ({@link #EXTRA_TOP} / {@link #EXTRA_OPTIONS}: aliases, promotion sources, transform and office plan keys).
 * A schema that declares no properties, or an options object with no properties, is not checked.</p>
 *
 * <p>Accepted gap: a key valid for a different action of the same tool passes. {@code allowUnread=true} is the
 * escape for a key genuinely read on a branch the schema does not describe. Called from McpController on the chat's
 * stdio process only; the shared companion serves flows with fixed args and keeps today's behaviour.</p>
 *
 * <p>Also refuses {@code options.knownHash} on file_read action=range: the key IS read there, and turns the answer
 * into {@code {unchanged:true}} instead of the lines asked for. Pinned by UnreadArgSchemaGuardSpec (AG-1..9).</p>
 */
@CompileStatic
final class ArgsSchemaGuard {

    static final String ERROR_CODE     = 'UNREAD_ARG'
    static final String RANGE_HASH     = 'KNOWNHASH_ON_RANGE'
    static final String ALLOW_UNREAD   = 'allowUnread'

    /**
     * Keys the tool's code reads at the top level that its schema does not declare. Derived 2026-10-19 by scanning
     * each tool's sources; AG-9 re-scans on every build and fails if code reads a key this guard would refuse.
     */
    static final Map<String, Set<String>> EXTRA_TOP = [
        file_write: ['expectedHash', 'newText', 'new_str', 'oldText', 'old_str', 'replacements'] as Set<String>,
    ] as Map<String, Set<String>>

    /** Option keys the tool's code reads that its schema does not declare (same derivation as {@link #EXTRA_TOP}). */
    static final Map<String, Set<String>> EXTRA_OPTIONS = [
        file_read : ['_blocked', 'action', 'content', 'contentPattern', 'documentPlan', 'expectedHash', 'format',
                     'headers', 'keyColumn', 'knownFileHash', 'outputFormat', 'presentationPlan', 'queryPlan', 'range',
                     'rows', 'sheet', 'slides', 'templatePath', 'workbookPlan'] as Set<String>,
        file_write: ['action', 'content', 'documentPlan', 'force', 'forceBinary', 'format', 'fromLine', 'fuzzy',
                     'headers', 'headingDepth', 'headingStyle', 'keyColumn', 'matchIsRegex', 'matchLast',
                     'maxWriteSizeMb', 'new_str', 'old_str', 'outputFormat', 'presentationPlan', 'queryPlan', 'range',
                     'rows', 'sheet', 'slides', 'templatePath', 'workbookPlan'] as Set<String>,
    ] as Map<String, Set<String>>

    /** FS 0.9.46: file_read case-corrects option keys and promotes option keys sent at the top level. */
    static final Set<String> FOLDS_OPTION_KEYS = ['file_read'] as Set<String>

    private ArgsSchemaGuard() {}

    /** The refusal text (it leads with its code, so McpController.codeFrom files it), or null to dispatch. */
    static String check(Map<String, Object> toolDef, Map<String, Object> args) {
        if (toolDef == null || args == null) return null
        if (truthy(args.get(ALLOW_UNREAD))) return null
        String tool = toolDef.get('name') as String
        Map<String, Object> schema = toolDef.get('inputSchema') instanceof Map ? (Map<String, Object>) toolDef.get('inputSchema') : null
        Map<String, Object> props = schema?.get('properties') instanceof Map ? (Map<String, Object>) schema.get('properties') : null
        Map<String, Object> options = args.get('options') instanceof Map ? (Map<String, Object>) args.get('options') : null

        String rangeHash = rangeKnownHash(tool, args, options)
        if (rangeHash) return rangeHash
        if (props == null || props.isEmpty()) return null

        boolean folds = FOLDS_OPTION_KEYS.contains(tool)
        Set<String> acceptedOpts = acceptedOptions(tool, props)
        Set<String> acceptedTop  = new LinkedHashSet<String>(props.keySet())
        acceptedTop.addAll(EXTRA_TOP.get(tool) ?: Collections.<String>emptySet())
        acceptedTop.add(ALLOW_UNREAD)

        List<String> unread = []
        for (String k : args.keySet()) {
            if (acceptedTop.contains(k)) continue
            if (folds && acceptedOpts != null && containsIgnoreCase(acceptedOpts, k)) continue   // promoted into options
            unread << k
        }
        if (options != null && acceptedOpts != null) {
            for (String k : options.keySet()) {
                if (acceptedOpts.contains(k)) continue
                if (folds && containsIgnoreCase(acceptedOpts, k)) continue                         // case-corrected
                unread << ('options.' + k)
            }
        }
        if (!unread) return null
        return refusal(tool, unread, acceptedTop, acceptedOpts)
    }

    /** Declared option keys plus the tool's undeclared reads; null when options is free-form (not checked). */
    static Set<String> acceptedOptions(String tool, Map<String, Object> props) {
        Object o = props.get('options')
        if (!(o instanceof Map)) return null
        Object p = ((Map) o).get('properties')        // get(), never .properties: that is the metaproperty (#3289)
        if (!(p instanceof Map) || ((Map) p).isEmpty()) return null
        Set<String> out = new LinkedHashSet<String>(((Map<String, Object>) p).keySet())
        out.addAll(EXTRA_OPTIONS.get(tool) ?: Collections.<String>emptySet())
        return out
    }

    private static String rangeKnownHash(String tool, Map<String, Object> args, Map<String, Object> options) {
        if (tool != 'file_read' || (args.get('action') as String) != 'range' || options == null) return null
        if (!options.containsKey('knownHash')) return null
        return (RANGE_HASH + ': file_read action=range does not take options.knownHash -- with it the answer is ' +
                '{unchanged:true} instead of the lines you asked for. Nothing ran. Drop knownHash and repeat the ' +
                'call; repeat reads are already de-duplicated per chat.').toString()
    }

    private static String refusal(String tool, List<String> unread, Set<String> top, Set<String> opts) {
        StringBuilder sb = new StringBuilder(ERROR_CODE).append(': ').append(tool).append(' reads no ')
            .append(unread.join(', ')).append(' -- it would have been accepted and dropped. Nothing ran.')
        List<String> hints = []
        for (String k : unread) {
            boolean inOptions = k.startsWith('options.')
            String bare = inOptions ? k.substring(8) : k
            String near = nearest(bare, inOptions ? (opts ?: Collections.<String>emptySet()) : top, 3)
            if (near == null && !inOptions && opts != null) {
                String asOpt = nearest(bare, opts, 3)
                if (asOpt != null) { hints << (k + ' -> options.' + asOpt); continue }
            }
            if (near == null && inOptions) {
                String asTop = nearest(bare, top, 3)
                if (asTop != null) { hints << (k + ' -> ' + asTop + ' (top level)'); continue }
            }
            if (near != null) hints << (k + ' -> ' + (inOptions ? 'options.' : '') + near)
        }
        if (hints) sb.append(' Did you mean: ').append(hints.join('; ')).append('?')
        else if (opts != null) sb.append(' This tool reads options: ').append(new TreeSet<String>(opts).join(', ')).append('.')
        sb.append(' Send it under a key the tool reads, or pass allowUnread=true if it is genuinely read here.')
        return sb.toString()
    }

    private static boolean truthy(Object v) {
        return v == Boolean.TRUE || 'true'.equalsIgnoreCase(v?.toString())
    }

    private static boolean containsIgnoreCase(Set<String> keys, String k) {
        for (String s : keys) if (s.equalsIgnoreCase(k)) return true
        return false
    }

    /** Smallest case-insensitive edit distance within maxDistance, else null. */
    static String nearest(String target, Collection<String> candidates, int maxDistance) {
        if (!target || !candidates) return null
        String best = null
        int bestD = Integer.MAX_VALUE
        for (String c : candidates) {
            int d = distance(c.toLowerCase(), target.toLowerCase())
            if (d < bestD) { bestD = d; best = c }
        }
        return bestD <= maxDistance ? best : null
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1]
        int[] cur  = new int[b.length() + 1]
        for (int j = 0; j <= b.length(); j++) prev[j] = j
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            int[] t = prev; prev = cur; cur = t
        }
        return prev[b.length()]
    }
}
