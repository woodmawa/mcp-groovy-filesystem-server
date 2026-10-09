package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Unroll

/**
 * WorktreeRoots.nameOf(path): which worktree a path belongs to -- the name of the directory directly under the
 * worktree root -- or null when the path is not inside one. For tagging telemetry rows by worktree later.
 *
 * Written by Claude as the judge for the first live code-harness task (agents-working-together WP3, 2026-10-09):
 * a local coder implements nameOf in WorktreeRoots.groovy; this spec is the verdict.
 */
class WorktreeRootsNameSpec extends Specification {

    WorktreeRoots roots = new WorktreeRoots(root: 'C:/tmp/wt-root')

    @Unroll
    def 'WN-1: nameOf(#path) is #expected'() {
        expect:
        roots.nameOf(path) == expected

        where:
        path                                                  | expected
        'C:/tmp/wt-root/fs-abc12345/src/main/groovy/A.groovy' | 'fs-abc12345'
        'C:/tmp/wt-root/fs-abc12345'                          | 'fs-abc12345'
        'C:\\tmp\\wt-root\\aw-99\\build.gradle'               | 'aw-99'
        'c:/TMP/WT-ROOT/Mixed-Case/x.txt'                     | 'Mixed-Case'
        'C:/tmp/wt-root/a/../b/c.txt'                         | 'b'
    }

    @Unroll
    def 'WN-2: nameOf(#path) is null -- not inside a worktree'() {
        expect:
        roots.nameOf(path) == null

        where:
        path << ['C:/tmp/wt-root', 'C:/tmp/wt-root/', 'C:/tmp/wt-root-other/x/y.txt', 'C:/tmp/elsewhere/a.txt',
                 'C:/tmp/wt-root/a/../../escaped/x.txt', '', '   ', null]
    }

    def 'WN-3: nameOf agrees with contains -- a name exactly when the path is inside and below the root'() {
        expect:
        roots.contains('C:/tmp/wt-root/n1/f.txt') && roots.nameOf('C:/tmp/wt-root/n1/f.txt') == 'n1'
        roots.contains('C:/tmp/wt-root') && roots.nameOf('C:/tmp/wt-root') == null
        !roots.contains('C:/tmp/other/n1/f.txt') && roots.nameOf('C:/tmp/other/n1/f.txt') == null
    }
}
