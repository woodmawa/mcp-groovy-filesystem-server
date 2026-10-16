package com.softwood.mcp.service

import spock.lang.Specification

/**
 * WS10-C item 4 (decision 375, Will 2026-10-16) -- FS runs only allowlisted gradle tasks, so the test tiers in
 * gradle/test-tiers.gradle (CS, AW, FS) are reachable through `tools action=gradle` -- and so through the
 * gradle-test-summary flow -- only if the allowlist names them.
 *
 *   TA-1  testFast and checkTiering are allowed
 *   TA-2  CONTROL an unlisted task is still refused, by name
 *   TA-3  a multi-task subcommand is checked token by token: only the unlisted token is refused
 *   TA-4  CONTROL the release-gate task `test` stays allowed
 */
class ToolsGradleAllowlistSpec extends Specification {

    def 'TA-1 the tier tasks are allowed'() {
        expect:
        ToolsService.rejectedGradleTasks(task) == []

        where:
        task << ['testFast', 'checkTiering', 'test checkTiering']
    }

    def 'TA-2 CONTROL an unlisted task is refused by name'() {
        expect:
        ToolsService.rejectedGradleTasks('deleteEverything') == ['deleteEverything']
    }

    def 'TA-3 a multi-task subcommand is checked token by token'() {
        expect:
        ToolsService.rejectedGradleTasks('testFast  bogusTask clean') == ['bogusTask']
    }

    def 'TA-4 CONTROL test stays allowed'() {
        expect:
        ToolsService.rejectedGradleTasks('test') == []
    }
}
