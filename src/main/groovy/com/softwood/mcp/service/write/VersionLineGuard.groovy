package com.softwood.mcp.service.write

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * VersionLineGuard -- FS 0.9.116, productive-learning round 1b remedy 2b (class tool-bypass).
 *
 * A platform repo's build.gradle {@code version = '...'} is one of several stamps a release sets together
 * (README/CLAUDE titles, baseline lines in all three repos, :*-baseline: attributes). The bump-baseline flow sets
 * them all from the file_version_match contracts; hand-editing the build.gradle line leaves the rest behind and
 * verify_contracts finds it later, as rework. Newest instance 2026-10-21, cost got_through.
 *
 * So a write that CHANGES that line, in a build.gradle whose directory is a platform repo (or a worktree of one,
 * named with the repo as prefix), is refused VERSION_VIA_BUMP_BASELINE and nothing is written. Creating a new
 * build.gradle, or any other edit to one, passes. bump-baseline itself writes through CS, not FS, so it is never
 * refused here.
 *
 * FS 0.9.117 (productive-learning R3): server_transform's eight transformers wrote with WriteUtils.atomicWrite
 * directly, never through WriteCommitter, so replace_between / insert_before_match could still change the line.
 * They now write through {@link #writeChecked}, the same check on the same before/after bytes.
 */
@CompileStatic
final class VersionLineGuard {

    /** repo directory name -> bump-baseline serverName */
    static final Map<String, String> PLATFORM_REPOS = [
        'mcp-groovy-context-server'   : 'context-server',
        'mcp-groovy-filesystem-server': 'filesystem',
        'mcp-agentic-workflow'        : 'agentic-workflow'
    ].asImmutable()

    static final Pattern VERSION_LINE = Pattern.compile(/(?m)^\s*version\s*=\s*['"]([^'"\r\n]*)['"]/)

    private VersionLineGuard() {}

    /** The bump-baseline serverName for this path, or null when it is not a platform build.gradle. */
    static String serverFor(String normalizedPath) {
        if (!normalizedPath) return null
        Path p
        try { p = Paths.get(normalizedPath) } catch (Exception ignored) { return null }
        if (p.fileName == null || p.fileName.toString() != 'build.gradle') return null
        String dir = p.parent?.fileName?.toString()
        if (!dir) return null
        String key = PLATFORM_REPOS.keySet().find { String repo -> dir == repo || dir.startsWith(repo + '-') }
        return key ? PLATFORM_REPOS[key] : null
    }

    /**
     * Writes {@code after} to the file unless that changes a platform build.gradle version line.
     * @return the refusal JSON, with NOTHING written; or null once the bytes are written.
     */
    static String writeChecked(String normalizedPath, byte[] after) {
        Path target = Paths.get(normalizedPath)
        byte[] before = Files.exists(target) ? Files.readAllBytes(target) : null
        String refusal = check(normalizedPath, before, after)
        if (refusal != null) return refusal
        WriteUtils.atomicWrite(target, after)
        return null
    }

    static String versionOf(String text) {
        if (text == null) return null
        Matcher m = VERSION_LINE.matcher(text)
        return m.find() ? m.group(1) : null
    }

    /**
     * @return the refusal JSON when the write changes a platform build.gradle version line, else null.
     *         {@code before == null} means the file does not exist yet: nothing to drift from, allowed.
     */
    static String check(String normalizedPath, byte[] before, byte[] after) {
        if (before == null || after == null) return null
        String server = serverFor(normalizedPath)
        if (server == null) return null
        String was = versionOf(new String(before, StandardCharsets.UTF_8))
        if (was == null) return null
        String now = versionOf(new String(after, StandardCharsets.UTF_8))
        if (was == now) return null
        return JsonOutput.toJson([
            success   : false,
            error     : 'VERSION_VIA_BUMP_BASELINE',
            error_code: 'VERSION_VIA_BUMP_BASELINE',
            path      : normalizedPath,
            was       : was,
            now       : now,
            hint      : ("Nothing written. The build.gradle version of a platform repo is set with every other stamp it owns, by " +
                         "flow_management action=start mode=flow templateName=bump-baseline params={serverName:'" + server +
                         "', newVersion:'" + (now ?: '<version>') + "', dryRun:'false'} -- run it with dryRun:'true' first to see " +
                         "what it will change. Re-send this edit without the version line if it carried other changes.").toString()
        ] as Map<String, Object>)
    }
}
