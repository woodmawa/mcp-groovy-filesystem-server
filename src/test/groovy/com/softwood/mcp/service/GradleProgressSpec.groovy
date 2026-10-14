package com.softwood.mcp.service

import spock.lang.Specification

/**
 * FS 0.9.95 -- live progress inside a long gradle call (HANDOFF-2026-10-14-live-step-progress, G1).
 *
 * gradle-test-summary's run-gradle node is ONE synchronous FS call; for a whole suite progress showed only
 * "run-gradle, 0/3" for up to 15 minutes. FS now runs test tasks with an init script that prints one marker line
 * per finished test, counts them as the output streams, and posts record_node_progress to AW for the node that is
 * waiting -- at most every MIN_INTERVAL_MS, plus a final post at the end.
 *
 * CONTRACT:
 *   GP-1  the first finished test posts at once; further tests inside the interval do not; past it they do, with
 *         done / failed / current (simple class name); finish() posts the final count
 *   GP-2  non-marker output, and a marker with an unknown result type, count for nothing and post nothing
 *   GP-3  the init script prints the marker the parser reads (one source of truth for the format)
 */
class GradleProgressSpec extends Specification {

    long now = 1_000_000L
    List<Map> posts = []

    private GradleProgress progress() {
        new GradleProgress('run-77', 'run-gradle', 'gradle test', { Map m -> posts << m }, { now })
    }

    def 'GP-1 first test posts at once, then at most one post per interval, then a final post'() {
        given:
        GradleProgress g = progress()

        when:
        g.onLine('> Task :test')
        g.onLine('##AWTEST|SUCCESS|com.softwood.mcp.service.AlphaSpec|a works')
        now += 1000
        g.onLine('##AWTEST|SUCCESS|com.softwood.mcp.service.AlphaSpec|b works')
        now += GradleProgress.MIN_INTERVAL_MS
        g.onLine('##AWTEST|FAILURE|com.softwood.mcp.service.BetaSpec|c breaks')
        g.finish()

        then:
        posts.size() == 3
        posts[0].runId == 'run-77'
        posts[0].nodeId == 'run-gradle'
        posts[0].detail == 'gradle test'
        posts[0].done == 1
        posts[0].failed == 0
        posts[0].current == 'AlphaSpec'
        posts[1].done == 3
        posts[1].failed == 1
        posts[1].current == 'BetaSpec'
        posts[2].done == 3
    }

    def 'GP-2 noise and unknown result types count for nothing'() {
        given:
        GradleProgress g = progress()

        when:
        g.onLine('BUILD SUCCESSFUL in 12s')
        g.onLine('##AWTEST|WEIRD|com.x.Spec|t')
        g.onLine('##AWTEST|SUCCESS')
        g.onLine(null)
        g.finish()

        then:
        posts.isEmpty()
    }

    def 'GP-3 the init script prints the marker the parser reads'() {
        expect:
        GradleProgress.INIT_SCRIPT.contains(GradleProgress.MARKER)
        GradleProgress.INIT_SCRIPT.contains('afterTest')
        GradleProgress.initScriptFile().text == GradleProgress.INIT_SCRIPT
    }
}
