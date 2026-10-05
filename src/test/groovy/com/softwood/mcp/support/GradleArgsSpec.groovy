package com.softwood.mcp.support

import spock.lang.Specification

/**
 * FS 0.9.68 (chain 2e0ad21b) -- GradleArgs.launcherSafe edges. The behaviour that matters, the argv
 * the JVM main actually receives, is pinned by ToolsServiceGradleGlobSpec on Windows; these pin the
 * fold's edges on every OS.
 *
 * GA-1  --tests X folds to --tests=X, repeatedly, in place
 * GA-2  an already-folded --tests=X, a trailing --tests and unrelated args are untouched
 * GA-3  null and empty give an empty list
 */
class GradleArgsSpec extends Specification {

    def 'GA-1: --tests X folds in place, every occurrence'() {
        expect:
        GradleArgs.launcherSafe(['-q', '--tests', '*A*', '--tests', 'com.x.B', '--rerun-tasks']) ==
            ['-q', '--tests=*A*', '--tests=com.x.B', '--rerun-tasks']
    }

    def 'GA-2: already folded, trailing and unrelated args are untouched'() {
        expect:
        GradleArgs.launcherSafe(['--tests=*A*', '--info']) == ['--tests=*A*', '--info']
        GradleArgs.launcherSafe(['-q', '--tests']) == ['-q', '--tests']
        GradleArgs.launcherSafe(['-x', '*Spec*']) == ['-x', '*Spec*']
    }

    def 'GA-3: null and empty give an empty list'() {
        expect:
        GradleArgs.launcherSafe(null) == []
        GradleArgs.launcherSafe([]) == []
    }
}
