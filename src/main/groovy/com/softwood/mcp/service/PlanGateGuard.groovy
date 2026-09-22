package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * FS 0.9.37 C1 -- PLAN-GATE at the two mutating entry points FS owns: file_write (every action that
 * writes) and execute when the script runs gradlew or git. CS decides (it owns the corpus and the
 * per-session record); this class only asks, and turns a refusal into a tool error that names the
 * practices and says a retry passes.
 *
 * Every uncertain path passes: no CS, no claimed session, a timeout, a malformed answer. A gate that
 * blocks on its own dependency is the W17 shape. Those passes are counted as 'unavailable' here,
 * because CS cannot record its own absence.
 *
 * Pinned by PlanGateGuardSpec.
 */
@Service
@Slf4j
@CompileStatic
class PlanGateGuard {

    @Autowired(required = false) ContextServerClient contextServerClient
    @Autowired(required = false) FilesystemTelemetryService telemetryService

    @Value('${mcp.filesystem.plan-gate.enforced:true}')
    boolean enforced = true

    /** Cheap pre-filter only: a script that never mentions either word is never parsed. */
    static final Pattern BUILD_OR_GIT = Pattern.compile('(?i)(\\bgradlew|\\bgit\\b)')

    /** Here-string / heredoc bodies are data, not commands. */
    static final Pattern PS_HERE_STRING = Pattern.compile('(?s)@([\'"])\\r?\\n.*?\\r?\\n\\1@')
    static final Pattern SH_HEREDOC     = Pattern.compile('(?s)<<-?\\s*([\'"]?)(\\w+)\\1[^\\n]*\\n.*?\\n\\s*\\2(?=\\s|$)')

    static final Set<String> GIT_READ_ONLY = (['status', 'log', 'diff', 'show', 'rev-parse', 'ls-files', 'ls-remote',
        'describe', 'blame', 'grep', 'shortlog', 'cat-file', 'rev-list', 'reflog', 'help', 'version'] as Set<String>).asImmutable()
    static final Set<String> GRADLE_READ_ONLY = (['tasks', 'help', 'dependencies', 'properties', 'projects'] as Set<String>).asImmutable()
    static final Set<String> GIT_VALUE_OPTS = (['-C', '-c', '--git-dir', '--work-tree'] as Set<String>).asImmutable()
    static final Set<String> GRADLE_VALUE_OPTS = (['-p', '--project-dir', '-x', '--exclude-task', '-b', '--build-file', '-D'] as Set<String>).asImmutable()
    static final Set<String> CD_COMMANDS = (['cd', 'set-location', 'sl', 'chdir', 'pushd', 'push-location'] as Set<String>).asImmutable()

    /** One build/git invocation found in statement position. */
    static class GatedCommand {
        String kind
        String command
        String dir
    }
    static final Set<String> NON_MUTATING_WRITE_ACTIONS = (['abort_write', 'chunk_status'] as Set<String>).asImmutable()

    final AtomicInteger unavailable = new AtomicInteger()

    /**
     * @param intent  FS 0.9.45 WP-2b -- what this write is FOR, in the caller's own words.
     *
     * <p>Until now only execute carried an intent, so a file_write was gated on the COMPONENT alone:
     * the gate answered "what does the corpus know about ContextWriteActionRouter" when the question
     * was "what does it know about the thing I am about to do to it", and the component is the one
     * part of that the caller could not have got wrong. CS 1.0.96 WP-3c added the selection on
     * intent and the fallback to the session's last stated intent; this is the parameter that makes
     * the stated half reachable. Optional: without one the gate falls back exactly as before.</p>
     *
     * @return a refusal message, or null to proceed.
     */
    String checkWrite(String action, String path, String planAck = null, String intent = null) {
        if (!enforced || !path || NON_MUTATING_WRITE_ACTIONS.contains(action)) return null
        // FS 0.9.52: only a write that is a PLAN is gated. On 2026-09-22 a scratch alpha.txt in
        // claude-sync drew three CS migration practices, hello.txt drew a sandbox-tool practice,
        // and ARC-STATE.md drew the ontology-first rule -- 24 of 43 judged shows were 'n', and the
        // wrong half was almost entirely files that are not part of any codebase. A plan lives in
        // a repository; a note, a probe file or a brief does not.
        if (!isPlannedArtefact(path)) return null
        Map<String, Object> args = [tool: 'file_write', path: path] as Map<String, Object>
        // FS 0.9.44 WP-2a: the judgement DT gives at the retry, passed straight through to CS.
        if (planAck) args.put('planAck', planAck)
        if (intent)  args.put('intent', intent)
        return ask(args)
    }

    /** Extensions that are never a plan, wherever they live. */
    static final Set<String> SCRATCH_EXTENSIONS =
        ['txt', 'log', 'csv', 'tsv', 'tmp', 'bak', 'out', 'backup', 'orig'] as Set<String>

    /**
     * FS 0.9.52: a write is a plan when the file sits inside a git repository (a {@code .git}
     * directory within twelve levels up) and its extension is not scratch. Static and pure so it
     * can be asserted without CS.
     */
    static boolean isPlannedArtefact(String path) {
        if (!path) return false
        String name = new File(path).name
        int dot = name.lastIndexOf('.')
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : ''
        if (SCRATCH_EXTENSIONS.contains(ext)) return false
        File dir = new File(path).absoluteFile.parentFile
        int hops = 0
        while (dir != null && hops < 12) {
            if (new File(dir, '.git').exists()) return true
            dir = dir.parentFile
            hops++
        }
        return false
    }

    /**
     * FS 0.9.38 -- only a command in STATEMENT position is gated, attributed to the directory it really
     * runs in (cd / Set-Location / git -C / a pathed gradlew), and read-only git or gradle housekeeping
     * is not gated at all. 0.9.37 scanned the whole script, so a PowerShell edit whose string content
     * mentioned git was refused and attributed to the default workingDir. Every distinct repo in the
     * script is asked about in one pass, so a single retry clears them all.
     *
     * @return a refusal message, or null to proceed.
     */
    String checkExecute(String script, String workingDir, String intent = null, String planAck = null) {
        if (!enforced || !script || !BUILD_OR_GIT.matcher(script).find()) return null
        List<GatedCommand> found = findGatedCommands(script, workingDir)
        Set<String> seen = new LinkedHashSet<String>()
        List<String> refusals = []
        for (GatedCommand g : found) {
            if (!seen.add(g.kind + '|' + g.dir)) continue
            Map<String, Object> args = [tool: 'execute', workingDir: g.dir, kind: g.kind, command: g.command,
                                        script: script.take(200)] as Map<String, Object>
            if (intent) args.put('intent', intent)
            if (planAck) args.put('planAck', planAck)
            String r = ask(args)
            if (r) refusals << r
        }
        return refusals ? refusals.join('\n') : null
    }

    static List<GatedCommand> findGatedCommands(String script, String workingDir) {
        String cleaned = SH_HEREDOC.matcher(PS_HERE_STRING.matcher(script).replaceAll("''")).replaceAll('')
        List<GatedCommand> out = []
        scan(cleaned, normDir(workingDir), out, 0)
        return out
    }

    private static void scan(String text, String startDir, List<GatedCommand> out, int depth) {
        if (depth > 2) return
        String dir = startDir
        for (List<String> st : statements(text)) {
            List<String> t = new ArrayList<String>(st)
            if (t.size() >= 2 && t[0].startsWith('$') && t[1] == '=') t = t.drop(2)
            while (t && (t[0] == '.' || t[0] == '&')) t = t.drop(1)
            if (!t) continue
            String name = baseName(t[0])
            if (name in ['cmd', 'powershell', 'pwsh', 'bash', 'sh']) {
                List<String> rest = t.drop(1).findAll { String x -> !x.startsWith('/') && !x.startsWith('-') }
                if (rest) scan(rest.join(' '), dir, out, depth + 1)
                continue
            }
            if (CD_COMMANDS.contains(name)) {
                String arg = t.drop(1).find { String x -> !x.startsWith('-') && !(x ==~ /(?i)\/d/) }
                if (arg) dir = resolve(dir, arg)
                continue
            }
            if (name == 'git') {
                String runDir = dir
                String sub = null
                int subIdx = -1
                for (int i = 1; i < t.size(); i++) {
                    String x = t[i]
                    if (x == '-C' && i + 1 < t.size()) { runDir = resolve(dir, t[i + 1]); i++; continue }
                    if (GIT_VALUE_OPTS.contains(x)) { i++; continue }
                    if (x.startsWith('-')) continue
                    sub = x.toLowerCase(); subIdx = i; break
                }
                if (sub == null || isReadOnlyGit(sub, t.drop(subIdx + 1))) continue
                out << new GatedCommand(kind: 'git', dir: runDir, command: (['git'] + t.drop(1)).join(' '))
                continue
            }
            if (name == 'gradlew') {
                String runDir = hasPath(t[0]) ? resolve(dir, parentOf(t[0])) : dir
                List<String> tasks = []
                for (int i = 1; i < t.size(); i++) {
                    String x = t[i]
                    if ((x == '-p' || x == '--project-dir') && i + 1 < t.size()) { runDir = resolve(dir, t[i + 1]); i++; continue }
                    if (GRADLE_VALUE_OPTS.contains(x)) { i++; continue }
                    if (x.startsWith('-')) continue
                    tasks << x
                }
                if (!tasks || tasks.every { String k -> GRADLE_READ_ONLY.contains(k.toLowerCase()) }) continue
                out << new GatedCommand(kind: 'gradle', dir: runDir, command: (['gradlew'] + t.drop(1)).join(' '))
            }
        }
    }

    /** FS 0.9.39: listing forms of subcommands whose bare name also writes. */
    static final Set<String> LIST_FLAGS = (['-l', '--list', '-a', '--all', '-r', '--remotes', '-v', '-vv',
        '--verbose', '--show-current', '--get', '--get-all', '--list', '--contains', '--merged', '--no-merged'] as Set<String>).asImmutable()

    static boolean isReadOnlyGit(String sub, List<String> rest) {
        if (GIT_READ_ONLY.contains(sub)) return true
        List<String> positional = rest.findAll { String x -> !x.startsWith('-') }
        boolean listing = rest.any { String x -> LIST_FLAGS.contains(x) }
        switch (sub) {
            case 'tag':      return !rest || listing
            case 'branch':   return !rest || (listing && !rest.any { it in ['-d', '-D', '-m', '-M', '-c', '-C', '--delete', '--move', '--copy'] })
            case 'remote':   return !positional || (positional.size() >= 1 && positional[0] in ['show', 'get-url'])
            case 'stash':    return positional && positional[0] in ['list', 'show']
            case 'config':   return listing
            case 'worktree': return positional && positional[0] == 'list'
            default:         return false
        }
    }

    /**
     * Statements of a PowerShell / bash / cmd script as token lists. Splits on newline ; | & { } ( )
     * outside quotes; quote characters are removed and their content kept inside one token; a # at a
     * token start comments out the rest of the line. Backslash is NOT an escape (Windows paths);
     * backtick is.
     */
    static List<List<String>> statements(String text) {
        List<List<String>> out = []
        List<String> cur = []
        StringBuilder tok = new StringBuilder()
        boolean inTok = false
        char quote = (char) 0
        int n = text.length()
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i)
            if (quote != (char) 0) {
                if (ch == quote) quote = (char) 0
                else if (ch == (char) '`' && i + 1 < n) { tok.append(text.charAt(++i)) }
                else tok.append(ch)
                continue
            }
            if (ch == (char) '\'' || ch == (char) '"') { quote = ch; inTok = true; continue }
            if (ch == (char) '`' && i + 1 < n) { tok.append(text.charAt(++i)); inTok = true; continue }
            if (ch == (char) '#' && !inTok) {
                while (i + 1 < n && text.charAt(i + 1) != (char) '\n') i++
                continue
            }
            boolean sep = '\n;|&{}()'.indexOf((int) ch) >= 0
            if (sep || Character.isWhitespace(ch)) {
                if (inTok) { cur << tok.toString(); tok.setLength(0); inTok = false }
                if (sep && cur) { out << cur; cur = [] }
                continue
            }
            tok.append(ch); inTok = true
        }
        if (inTok) cur << tok.toString()
        if (cur) out << cur
        return out
    }

    static String baseName(String token) {
        String s = token.replace('\\', '/')
        s = s.substring(s.lastIndexOf('/') + 1).toLowerCase()
        return s.replaceAll(/\.(exe|bat|cmd|ps1)$/, '')
    }

    private static boolean hasPath(String token) { token.contains('/') || token.contains('\\') }

    private static String parentOf(String token) {
        String s = token.replace('\\', '/')
        return s.substring(0, s.lastIndexOf('/'))
    }

    static String normDir(String d) { (d ?: '').replace('\\', '/').replaceAll('/+$', '') }

    static String resolve(String base, String arg) {
        // FS 0.9.39: a shell variable cannot be resolved here, and taking it literally named the
        // component '$r'. The statement runs somewhere we cannot know, so keep the current dir.
        if (arg.contains('$') || arg.contains('%') || arg.contains('`')) return base
        String a = normDir(arg)
        if (!a || a == '.') return base
        boolean abs = a ==~ /^[A-Za-z]:\/.*/ || a ==~ /^[A-Za-z]:$/ || a.startsWith('/')
        String joined = abs ? a : (base ? base + '/' + a : a)
        List<String> parts = []
        for (String p : joined.split('/')) {
            if (p == '.' || (p.isEmpty() && parts)) continue
            if (p == '..') { if (parts.size() > 1) parts.remove(parts.size() - 1); continue }
            parts << p
        }
        return parts.join('/')
    }

    protected String ask(Map<String, Object> args) {
        try {
            String sid = null
            try { sid = telemetryService?.readActiveSessionId() } catch (Exception ignored) { }
            Map<String, Object> answer = (contextServerClient != null && sid) ?
                contextServerClient.planGateCheck(args, sid) : null
            if (answer == null) {
                unavailable.incrementAndGet()
                return null
            }
            if (answer.get('allow') != false) return null
            return refusal(answer)
        } catch (Exception e) {
            unavailable.incrementAndGet()
            log.debug('plan-gate ask failed (pass-through): {}', e.message)
            return null
        }
    }

    static String refusal(Map<String, Object> answer) {
        StringBuilder sb = new StringBuilder()
        sb.append("PLAN-GATE: first mutating call on '").append(answer.get('component'))
          .append("' this session. The corpus already holds this for it:\n")
        ((answer.get('practices') ?: []) as List).each { Object o ->
            Map p = o as Map
            sb.append('  #').append(p.get('id')).append(' [').append(p.get('valence') ?: '').append('] ')
              .append(p.get('title')).append(' -- ').append(p.get('summary') ?: '').append('\n')
        }
        sb.append((answer.get('retry') ?: 'Read these, then repeat the same call unchanged -- a retry is never refused.') as String)
        return sb.toString()
    }
}