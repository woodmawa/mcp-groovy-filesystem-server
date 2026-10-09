package com.softwood.mcp.service

import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.nio.file.Path
import java.nio.file.Paths

/**
 * FS 0.9.78 -- where agent worktrees live, and the one test for "is this path inside one".
 *
 * <p>A worktree is a private copy of a repository that a local coder agent changes under a test harness (AW,
 * agents-working-together WP3). Its files have the same names as the real sources, and three things FS does for
 * Claude's own reads and writes are wrong for them:</p>
 * <ul>
 *   <li>the ontology re-index after a write would index the copy as a second node for the same class;</li>
 *   <li>ONTOLOGY-GATE decides on the file's stem, so a read of the copy would be refused for want of a locate on
 *       the original;</li>
 *   <li>PLAN-GATE treats any file under a {@code .git} as a planned artefact, and a worktree has one.</li>
 * </ul>
 * Those three ask this class and stand down. Everything about file integrity -- allowed directories, the hash
 * check, the structural guard, telemetry -- is unchanged there.
 */
@Component
@CompileStatic
class WorktreeRoots {

    @Value('${mcp.filesystem.worktree-root:C:/Users/willw/claude-sync/aw-worktrees}')
    String root = 'C:/Users/willw/claude-sync/aw-worktrees'

    Path rootPath() {
        Paths.get(root.replace('\\', '/')).toAbsolutePath().normalize()
    }

    /** True when the path is the worktree root or anything under it. Never throws; an unusable path is not inside. */
    boolean contains(String path) {
        if (!path?.trim() || !root?.trim()) { return false }
        try {
            String p = norm(Paths.get(path.trim().replace('\\', '/')).toAbsolutePath().normalize().toString())
            String r = norm(rootPath().toString())
            return p == r || p.startsWith(r + '/')
        } catch (Exception ignored) {
            return false
        }
    }

    /**
     * The name of the worktree a path belongs to: the single directory name directly under the worktree root,
     * in the case the caller wrote it. Null when the path is blank, is the root itself, is outside the root, or
     * cannot be parsed. Never throws.
     */
    String nameOf(String path) {
        if (!path?.trim() || !root?.trim()) { return null }
        try {
            String p = norm(Paths.get(path.trim().replace('\\', '/')).toAbsolutePath().normalize().toString())
            String r = norm(rootPath().toString())
            if (p == r) { return null }
            if (!p.startsWith(r + '/')) { return null }
            String rest = Paths.get(path.trim().replace('\\', '/')).toAbsolutePath().normalize().toString().replace('\\', '/')
            String rRaw = rootPath().toString().replace('\\', '/')
            if (rest.length() <= rRaw.length() + 1) { return null }
            String name = rest.substring(rRaw.length() + 1)
            int slash = name.indexOf('/')
            return slash == -1 ? name : name.substring(0, slash)
        } catch (Exception ignored) {
            return null
        }
    }

    private static String norm(String s) { s.replace('\\', '/').toLowerCase() }
}
