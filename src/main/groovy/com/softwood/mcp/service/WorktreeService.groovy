package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.write.FileContentWriter
import com.softwood.mcp.service.write.FileReplaceService
import com.softwood.mcp.service.write.WriteUtils
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * FS 0.9.78 -- the {@code worktree} tool: a private git worktree for a code harness, and the only way into one.
 *
 * <p>AW's local coder agent (agents-working-together WP3) changes code in a worktree while a test run judges it.
 * AW decides WHAT may be changed (its targets, the protected spec); FS does every file and git operation, so the
 * allowed-directories rule, the hash check, the structural guard and telemetry apply to an agent's edit exactly as
 * they do to Claude's. What does not apply inside a worktree is the Claude-facing machinery ({@link WorktreeRoots}).</p>
 *
 * <p>Actions: {@code create | read | edit | diff | hashes | apply | remove}. A worktree is always a direct child of
 * the worktree root; a path that is not is refused. {@code edit} is one exact, unique replacement through
 * {@link FileReplaceService} (an empty {@code find} creates a file). {@code apply} is the one action that writes to
 * the real repository: a guarded copy -- each changed file is copied only if the main tree's copy is still
 * byte-identical to what the worktree started from, every file is checked before any is written -- followed by the
 * same after-write step any FS write gets, so the ontology sees the change.</p>
 *
 * <p>An answer the caller should act on -- an edit that did not match, an apply that conflicts -- is a normal result
 * carrying {@code refused} and {@code reason}. A tool error is kept for a call that is wrong: a missing parameter,
 * a path outside the allowed directories or outside the worktree.</p>
 */
@Service
@Slf4j
@CompileStatic
class WorktreeService extends AbstractFileService implements ToolHandler {

    static final String TOOL = 'worktree'
    static final List<String> ACTIONS = ['create', 'read', 'edit', 'diff', 'hashes', 'apply', 'remove'].asImmutable()

    static final String EDIT_REFUSED = 'WORKTREE_EDIT_REFUSED'
    static final String FIND_REQUIRED = 'WORKTREE_FIND_REQUIRED'
    static final String FIND_NOT_FOUND = 'WORKTREE_FIND_NOT_FOUND'
    static final String FIND_AMBIGUOUS = 'WORKTREE_FIND_AMBIGUOUS'
    static final String READ_REFUSED = 'WORKTREE_READ_REFUSED'
    static final String APPLY_CONFLICT = 'WORKTREE_APPLY_CONFLICT'
    /** The original hash of a file the agent created. */
    static final String NEW_FILE = 'new'
    static final int MAX_READ_LINES = 200
    static final long MAX_BYTES = 2L * 1024L * 1024L

    static final String DESCRIPTION = '''\
A private git worktree for an agent's code harness (AW runs a local coder in one while a test judges its change).
Actions: create|read|edit|diff|hashes|apply|remove. A worktree is a direct child of the worktree root; paths inside it are relative.
- create(repoDir, name, baseRef=HEAD, specFiles[]): git worktree add --detach, then copy the named files in from the main tree. Returns worktree, base, specHashes.
- read(worktree, file, startLine, endLine): exact lines, at most 200 a call. Returns file, startLine, endLine, totalLines, lines, cut.
- edit(worktree, file, find, replace): one replacement; find must match exactly once. An empty find creates the file. Hash check and structural guard apply. Returns ok, file, created, originalSha -- or refused + reason.
- diff(worktree, files[]): unified diff of those files, new files included.
- hashes(worktree, files[]): SHA-256 of each file, null when missing.
- apply(worktree, repoDir, edited{file: originalSha|new}): copy the changed files onto the main tree, only if each is unchanged there since the worktree was made. Nothing is written on a conflict. Does not commit.
- remove(worktree, repoDir): git worktree remove.
No ontology gate, plan gate or re-index applies inside a worktree; apply re-indexes what it changes in the main tree.'''

    @Autowired WorktreeRoots roots
    @Autowired FileReplaceService replaceService
    @Autowired FileContentWriter contentWriter
    /** For the after-write step on apply. Optional so a bare spec can leave it out. */
    @Autowired(required = false) FileWriteService fileWriteService

    long gitTimeoutSeconds = 120L

    WorktreeService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : TOOL,
            description: DESCRIPTION,
            inputSchema: [
                type      : 'object',
                properties: [
                    action   : [type: 'string', enum: ACTIONS, description: 'Operation to perform'],
                    repoDir  : [type: 'string', description: '[create|apply|remove] The repository the worktree belongs to (absolute, an allowed directory)'],
                    name     : [type: 'string', description: '[create] Short name for the worktree directory: letters, digits, - and _ (e.g. the agent task id)'],
                    baseRef  : [type: 'string', description: '[create] Commit or ref to detach at (default HEAD)'],
                    specFiles: [type: 'array', items: [type: 'string'], description: '[create] Files, relative to the repo, copied in from the main tree as they are now (committed or not)'],
                    worktree : [type: 'string', description: '[read|edit|diff|hashes|apply|remove] The worktree path create returned'],
                    file     : [type: 'string', description: '[read|edit] File path relative to the worktree'],
                    startLine: [type: 'integer', description: '[read] First line, 1-indexed (default 1)'],
                    endLine  : [type: 'integer', description: '[read] Last line, inclusive (default: 200 lines from startLine)'],
                    find     : [type: 'string', description: '[edit] The exact text to replace; must match once. Empty creates the file.'],
                    replace  : [type: 'string', description: '[edit] The replacement text (empty deletes the match)'],
                    files    : [type: 'array', items: [type: 'string'], description: '[diff|hashes] Files relative to the worktree'],
                    edited   : [type: 'object', description: '[apply] file -> the originalSha edit returned for it the first time (or "new")']
                ],
                required  : ['action']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == TOOL }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action = arguments.action as String
            switch (action) {
                case 'create': return textResponse(requestId, doCreate(arguments))
                case 'read'  : return textResponse(requestId, doRead(arguments))
                case 'edit'  : return textResponse(requestId, doEdit(arguments, requestId))
                case 'diff'  : return textResponse(requestId, doDiff(arguments))
                case 'hashes': return textResponse(requestId, doHashes(arguments))
                case 'apply' : return textResponse(requestId, doApply(arguments))
                case 'remove': return textResponse(requestId, doRemove(arguments))
                default:
                    return McpResponse.toolError(requestId, "Unknown worktree action: '${action}'. Valid actions: ${ACTIONS.join('|')}." as String)
            }
        } catch (SecurityException e) {
            log.warn('Security violation in worktree: {}', sanitize(e.message))
            return McpResponse.toolError(requestId, "Security error: ${sanitize(e.message)}" as String)
        } catch (IllegalArgumentException e) {
            return McpResponse.toolError(requestId, sanitize(e.message))
        } catch (Exception e) {
            log.error('worktree error: {}', sanitize(e.message))
            return McpResponse.toolError(requestId, sanitize(e.message ?: e.class.simpleName))
        }
    }

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    private Map<String, Object> doCreate(Map<String, Object> a) {
        validateWriteEnabled()
        Path repo = repoOf(a)
        String name = str(a.get('name'))
        if (!name || !(name ==~ /[A-Za-z0-9_-]{1,60}/)) {
            throw new IllegalArgumentException("worktree create requires 'name': letters, digits, - and _, at most 60")
        }
        Path root = roots.rootPath()
        if (!isPathAllowed(pathService.normalizePath(root.toString()))) {
            throw new SecurityException('The worktree root is not in an allowed directory: ' + root)
        }
        Files.createDirectories(root)
        Path wt = root.resolve(repo.fileName.toString() + '-' + name)
        if (Files.exists(wt)) { throw new IllegalArgumentException('worktree directory already exists: ' + slash(wt.toString())) }
        // Every spec file is checked before anything is made, so a bad name leaves no worktree behind.
        list(a.get('specFiles')).each { String rel ->
            Path src = repo.resolve(rel).normalize()
            if (!src.startsWith(repo) || !Files.isRegularFile(src)) {
                throw new IllegalArgumentException('spec file not found in the main tree: ' + rel)
            }
            if (!wt.resolve(rel).normalize().startsWith(wt)) { throw new SecurityException('spec file escapes the worktree: ' + rel) }
        }
        String base = str(a.get('baseRef')) ?: 'HEAD'
        List<String> add = git(repo, ['worktree', 'add', '--detach', wt.toString(), base])
        if (add[0] != '0') { throw new IllegalArgumentException('git worktree add failed: ' + (add[2] ?: add[1]).trim().take(300)) }
        Map<String, Object> hashes = new LinkedHashMap<String, Object>()
        list(a.get('specFiles')).each { String rel ->
            Path src = repo.resolve(rel).normalize()
            Path dst = wt.resolve(rel).normalize()
            Files.createDirectories(dst.parent)
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
            hashes.put(slash(rel), (Object) sha(dst))
        }
        String head = git(wt, ['rev-parse', 'HEAD'])[1].trim()
        log.info('worktree created: {} at {}', wt, head)
        [success: (Object) true, worktree: (Object) slash(wt.toString()), base: (Object) head, specHashes: (Object) hashes] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // read
    // -----------------------------------------------------------------------

    private Map<String, Object> doRead(Map<String, Object> a) {
        Path wt = worktreeOf(a)
        String file = str(a.get('file'))
        Path p = inside(wt, file)
        if (p == null) { return refused(READ_REFUSED, "'${file}' is not a path inside the worktree: give it relative to the worktree root" as String) }
        String rel = slash(wt.relativize(p).toString())
        if (!Files.isRegularFile(p)) { return refused(READ_REFUSED, "'${rel}' does not exist in the worktree" as String) }
        if (Files.size(p) > MAX_BYTES) { return refused(READ_REFUSED, "'${rel}' is larger than ${MAX_BYTES} bytes" as String) }
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8)
        int total = lines.size()
        Integer s = toInt(a.get('startLine'))
        Integer e = toInt(a.get('endLine'))
        int start = s ?: 1
        if (start < 1 || start > Math.max(total, 1) || (e != null && e < start)) {
            return refused(READ_REFUSED, "lines ${start}-${e ?: start} do not exist in '${rel}' (${total} lines)" as String)
        }
        int wanted = e ?: total
        int end = Math.min(Math.min(wanted, total), start + MAX_READ_LINES - 1)
        boolean cut = end < Math.min(wanted, total)
        [file: (Object) rel, startLine: (Object) start, endLine: (Object) end, totalLines: (Object) total,
         lines: (Object) (total == 0 ? [] : new ArrayList<String>(lines.subList(start - 1, end))), cut: (Object) cut] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // edit
    // -----------------------------------------------------------------------

    private Map<String, Object> doEdit(Map<String, Object> a, Object requestId) {
        validateWriteEnabled()
        Path wt = worktreeOf(a)
        String file = str(a.get('file'))
        Path p = inside(wt, file)
        if (p == null) { throw new SecurityException("'${file}' is outside the worktree" as String) }
        String rel = slash(wt.relativize(p).toString())
        if (rel == '.git' || rel.startsWith('.git/')) { throw new SecurityException('the worktree\'s git data is not editable') }
        String find = a.get('find') instanceof String ? (String) a.get('find') : ''
        if (!(a.get('replace') instanceof String)) { throw new IllegalArgumentException("worktree edit requires 'replace' (a string; '' deletes the match)") }
        String replace = (String) a.get('replace')

        if (!Files.exists(p)) {
            if (find) { return refused(FIND_NOT_FOUND, "'${rel}' does not exist; to create it send an empty find" as String) }
            // raw: an agent's text is written as it is, never unescaped.
            McpResponse w = contentWriter.doWrite(p.toString(), replace, [raw: (Object) true, mkdirs: (Object) true] as Map<String, Object>, requestId)
            Map<String, Object> wb = bodyOf(w)
            if (wb.get('isError')) { return refused(EDIT_REFUSED, wb.get('text') as String) }
            return [ok: (Object) true, file: (Object) rel, created: (Object) true, originalSha: (Object) NEW_FILE] as Map<String, Object>
        }
        if (!Files.isRegularFile(p)) { return refused(EDIT_REFUSED, "'${rel}' is not a file" as String) }
        if (!find) { return refused(FIND_REQUIRED, "'${rel}' exists: find must be the exact text to replace" as String) }
        byte[] before = Files.readAllBytes(p)
        String original = shaOf(before)
        McpResponse r = replaceService.doReplace(p.toString(),
                [oldText: (Object) find, newText: (Object) replace, expectedHash: (Object) WriteUtils.computeHash(before)] as Map<String, Object>, requestId)
        Map<String, Object> body = bodyOf(r)
        if (body.get('isError')) {
            String text = body.get('text') as String
            String code = text.contains('not found in file') ? FIND_NOT_FOUND : (text.contains('must be unique') ? FIND_AMBIGUOUS : EDIT_REFUSED)
            String reason = code == FIND_NOT_FOUND ? "find text is not in '${rel}': read the lines again and copy them exactly, whitespace included" as String
                    : code == FIND_AMBIGUOUS ? "find text is in '${rel}' more than once: include more of the surrounding lines so it matches once (${firstLine(text)})" as String
                    : firstLine(text)
            return refused(code, reason)
        }
        [ok: (Object) true, file: (Object) rel, created: (Object) false, originalSha: (Object) original] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // diff, hashes
    // -----------------------------------------------------------------------

    private Map<String, Object> doDiff(Map<String, Object> a) {
        Path wt = worktreeOf(a)
        List<String> files = relFiles(wt, a.get('files'))
        if (!files) { return [diff: (Object) ''] as Map<String, Object> }
        git(wt, ['add', '-N', '--'] + files)          // so a created file shows in the diff
        List<String> d = git(wt, ['diff', '--'] + files)
        if (d[0] != '0') { throw new IllegalArgumentException('git diff failed: ' + (d[2] ?: d[1]).trim().take(300)) }
        [diff: (Object) (d[1] ?: ''), files: (Object) files] as Map<String, Object>
    }

    private Map<String, Object> doHashes(Map<String, Object> a) {
        Path wt = worktreeOf(a)
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        relFiles(wt, a.get('files')).each { String rel ->
            Path p = wt.resolve(rel).normalize()
            out.put(rel, Files.isRegularFile(p) ? (Object) sha(p) : null)
        }
        [hashes: (Object) out] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // apply, remove
    // -----------------------------------------------------------------------

    private Map<String, Object> doApply(Map<String, Object> a) {
        validateWriteEnabled()
        Path wt = worktreeOf(a)
        Path repo = repoOf(a)
        if (!(a.get('edited') instanceof Map) || !(Map) a.get('edited')) {
            throw new IllegalArgumentException("worktree apply requires 'edited': file -> originalSha (or 'new')")
        }
        Map<String, Object> edited = (Map<String, Object>) a.get('edited')
        List<String> conflicts = []
        edited.each { String rel, Object original ->
            Path src = wt.resolve(rel).normalize()
            Path dst = repo.resolve(rel).normalize()
            if (!src.startsWith(wt) || !dst.startsWith(repo)) {
                conflicts << (rel + ': not a path inside the worktree and the repository')
            } else if (!Files.isRegularFile(src)) {
                conflicts << (rel + ': missing from the worktree')
            } else if (original == NEW_FILE) {
                if (Files.exists(dst)) { conflicts << (rel + ': the main tree now has a file of that name') }
            } else if (!Files.isRegularFile(dst)) {
                conflicts << (rel + ': gone from the main tree')
            } else if (sha(dst) != (original as String)) {
                conflicts << (rel + ': differs in the main tree from what the worktree started with (changed since, or uncommitted work)')
            }
        }
        if (conflicts) {
            return [refused: (Object) APPLY_CONFLICT, reason: (Object) ('nothing was applied: ' + conflicts.join('; ')),
                    conflicts: (Object) conflicts] as Map<String, Object>
        }
        List<String> applied = []
        edited.keySet().each { String rel ->
            Path dst = repo.resolve(rel).normalize()
            Files.createDirectories(dst.parent)
            Files.copy(wt.resolve(rel).normalize(), dst, StandardCopyOption.REPLACE_EXISTING)
            applied << rel
            // The main tree changed: the same after-write step any FS write gets (registry, ontology re-index).
            try {
                String np = pathService.normalizePath(dst.toString())
                fileWriteService?.afterWrite(np, WriteUtils.computeHash(Files.readAllBytes(dst)))
            } catch (Exception e) {
                log.debug('worktree apply: after-write step failed for {}: {}', rel, e.message)
            }
        }
        log.info('worktree applied: {} file(s) from {} onto {}', applied.size(), wt, repo)
        [success: (Object) true, applied: (Object) applied] as Map<String, Object>
    }

    private Map<String, Object> doRemove(Map<String, Object> a) {
        validateWriteEnabled()
        Path repo = repoOf(a)
        String given = str(a.get('worktree'))
        if (!given) { throw new IllegalArgumentException("worktree remove requires 'worktree'") }
        Path wt = Paths.get(given.replace('\\', '/')).toAbsolutePath().normalize()
        Path root = roots.rootPath()
        if (wt.parent != root) { throw new SecurityException('not a worktree under ' + slash(root.toString())) }
        if (!Files.exists(wt)) {
            git(repo, ['worktree', 'prune'])
            return [removed: (Object) true, note: (Object) 'already gone'] as Map<String, Object>
        }
        List<String> r = git(repo, ['worktree', 'remove', '--force', wt.toString()])
        if (r[0] == '0' && !Files.exists(wt)) { return [removed: (Object) true] as Map<String, Object> }
        // Something may still hold files open; delete what can be deleted and let git forget the rest.
        wt.toFile().deleteDir()
        git(repo, ['worktree', 'prune'])
        boolean gone = !Files.exists(wt)
        [removed: (Object) gone, note: (Object) (gone ? 'removed by delete after git refused'
                : ('could not be fully removed (files in use?): ' + (r[2] ?: r[1]).trim().take(200)))] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** The repository named by repoDir: an allowed directory holding a .git. */
    private Path repoOf(Map<String, Object> a) {
        String given = str(a.get('repoDir'))
        if (!given) { throw new IllegalArgumentException("worktree ${a.get('action')} requires 'repoDir'" as String) }
        String normalized = pathService.normalizePath(given)
        if (!isPathAllowed(normalized)) { throw new SecurityException("Path not allowed: ${sanitize(normalized)}" as String) }
        Path repo = Paths.get(normalized).toAbsolutePath().normalize()
        if (!Files.exists(repo.resolve('.git'))) { throw new IllegalArgumentException('not a git repository: ' + slash(repo.toString())) }
        if (roots.contains(repo.toString())) { throw new IllegalArgumentException('repoDir is itself a worktree; name the repository it belongs to') }
        repo
    }

    /** The worktree named by the call: an existing direct child of the worktree root. */
    private Path worktreeOf(Map<String, Object> a) {
        String given = str(a.get('worktree'))
        if (!given) { throw new IllegalArgumentException("worktree ${a.get('action')} requires 'worktree'" as String) }
        Path wt = Paths.get(given.replace('\\', '/')).toAbsolutePath().normalize()
        if (wt.parent != roots.rootPath()) { throw new SecurityException('not a worktree under ' + slash(roots.rootPath().toString())) }
        if (!isPathAllowed(pathService.normalizePath(wt.toString()))) { throw new SecurityException("Path not allowed: ${sanitize(wt.toString())}" as String) }
        if (!Files.isDirectory(wt)) { throw new IllegalArgumentException('no such worktree: ' + slash(wt.toString())) }
        wt
    }

    /** The file's path if it lies inside the worktree (relative paths resolve against it), else null. */
    static Path inside(Path wt, String file) {
        if (!file?.trim()) { return null }
        try {
            Path given = Paths.get(file.trim().replace('\\', '/'))
            Path p = (given.absolute ? given : wt.resolve(given)).toAbsolutePath().normalize()
            String f = slash(p.toString()).toLowerCase()
            String w = slash(wt.toString()).toLowerCase()
            return f.startsWith(w + '/') ? p : null
        } catch (Exception ignored) {
            return null
        }
    }

    private static List<String> relFiles(Path wt, Object v) {
        list(v).collect { String f ->
            Path p = inside(wt, f)
            if (p == null) { throw new SecurityException("'${f}' is outside the worktree" as String) }
            slash(wt.relativize(p).toString())
        } as List<String>
    }

    private static Map<String, Object> refused(String code, String reason) {
        [refused: (Object) code, reason: (Object) reason] as Map<String, Object>
    }

    /** {isError, text} of an inner service's response. */
    private static Map<String, Object> bodyOf(McpResponse r) {
        Map result = r?.result instanceof Map ? (Map) r.result : [:]
        Object content = result.get('content')
        Object c0 = content instanceof List && ((List) content) ? ((List) content)[0] : null
        String text = c0 instanceof Map ? ((Map) c0).get('text') as String : (r?.error ? r.error.toString() : '')
        boolean isError = result.get('isError') == true || r?.error != null
        [isError: (Object) isError, text: (Object) (text ?: '')] as Map<String, Object>
    }

    /** The message of an inner refusal: its JSON error field when it has one, else its first line. */
    private static String firstLine(String text) {
        String t = text ?: ''
        if (t.trim().startsWith('{')) {
            try {
                Object o = new JsonSlurper().parseText(t)
                if (o instanceof Map && ((Map) o).get('error')) { t = ((Map) o).get('error') as String }
            } catch (Exception ignored) { }
        }
        (t.readLines() ? t.readLines()[0] : t).take(400)
    }

    static String sha(Path p) { shaOf(Files.readAllBytes(p)) }

    static String shaOf(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }

    static String slash(String s) { s == null ? null : s.replace('\\', '/') }

    static List<String> list(Object v) {
        if (v instanceof List) { return ((List) v).collect { Object o -> o?.toString()?.trim() }.findAll { String s -> s } as List<String> }
        if (v instanceof CharSequence) { return v.toString().tokenize(',')*.trim().findAll { String s -> s } as List<String> }
        [] as List<String>
    }

    private static String str(Object v) {
        String s = v?.toString()?.trim()
        s ? s : null
    }

    private static Integer toInt(Object v) {
        if (v == null) { return null }
        if (v instanceof Number) { return ((Number) v).intValue() }
        try { return Integer.parseInt(v.toString().trim()) } catch (Exception ignored) { return null }
    }

    /** [exit code as text, stdout, stderr]. A git that cannot be started or does not finish is exit -1. */
    protected List<String> git(Path dir, List<String> args) {
        List<String> cmd = ['git', '-C', dir.toString()] + args
        try {
            Process p = new ProcessBuilder(cmd).start()
            StringBuilder out = new StringBuilder()
            StringBuilder err = new StringBuilder()
            Thread readOut = p.consumeProcessOutputStream(out)
            Thread readErr = p.consumeProcessErrorStream(err)
            if (!p.waitFor(gitTimeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return ['-1', out.toString(), 'git did not finish in ' + gitTimeoutSeconds + ' s: ' + args.take(2).join(' ')]
            }
            readOut.join(5000L)
            readErr.join(5000L)
            return [String.valueOf(p.exitValue()), out.toString(), err.toString()]
        } catch (Exception e) {
            return ['-1', '', e.class.simpleName + ': ' + e.message]
        }
    }
}
