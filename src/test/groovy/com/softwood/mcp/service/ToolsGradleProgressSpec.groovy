package com.softwood.mcp.service

import spock.lang.Specification

/**
 * FS 0.9.95 -- the ToolsService half of live gradle progress (see GradleProgressSpec for the parser).
 *
 * CONTRACT:
 *   TG-1  only test-running task lists (test, build, check) get the progress init script
 *   TG-2  the command carries --init-script <file> before the user's args, and only when asked
 *   TG-3  every stdout line reaches the line listener -- including lines past the 50k capture cap, which a whole
 *         suite's output passes long before the end
 *   TG-4  a gradle call made for an AW node (AwCallContext) streams marker lines into node progress posts; with no
 *         AW context nothing is posted (CONTROL)
 *   TG-5  (0.9.96) marker lines reach the listener but NOT the captured stdout -- captured, a test named after
 *         'compileGroovy' made gradle-test-summary report a green run as COMPILE_FAILED. Ordinary lines are kept
 *         (CONTROL)
 */
class ToolsGradleProgressSpec extends Specification {

    def 'TG-1 only test-running tasks get progress'() {
        expect:
        ToolsService.progressTasks(tasks) == expected

        where:
        tasks                      || expected
        ['test']                   || true
        ['clean', 'build']         || true
        ['check']                  || true
        ['compileGroovy']          || false
        ['packageMcpbThin']        || false
    }

    def 'TG-2 the init script goes in front of the user args, only when asked'() {
        given:
        File init = new File('C:/tmp/x.gradle')

        expect:
        ToolsService.gradleCommand('gradlew.bat', ['test'], ['--tests=*A*'], init) ==
            ['gradlew.bat', 'test', '--no-daemon', '--init-script', init.absolutePath, '--tests=*A*']
        ToolsService.gradleCommand('gradlew.bat', ['test'], ['--tests=*A*'], null) ==
            ['gradlew.bat', 'test', '--no-daemon', '--tests=*A*']
    }

    def 'TG-3 every stdout line reaches the listener, past the capture cap'() {
        given:
        ToolsService t = new ToolsService()
        int lines = 0
        List<String> cmd = ['powershell', '-NoProfile', '-NonInteractive', '-Command',
                            "1..3000 | ForEach-Object { 'line-' + \$_ + '-' + ('x' * 40) }"]

        when:
        Map r = t.runToolRaw(cmd, System.getProperty('java.io.tmpdir'), 60, null, { String l -> lines++ })

        then:
        r.exitCode == 0
        lines == 3000
    }

    def 'TG-4 a gradle-style run for an AW node posts progress; with no AW context it posts nothing'() {
        given:
        List<Map> posted = []
        AwJobEventClient aw = Stub(AwJobEventClient) {
            nodeProgress(_) >> { List a -> posted << (a[0] as Map) }
        }
        ToolsService t = new ToolsService()
        t.awEvents = aw
        List<String> cmd = ['powershell', '-NoProfile', '-NonInteractive', '-Command',
                            "'##AWTEST|SUCCESS|a.b.AlphaSpec|t1'; '##AWTEST|FAILURE|a.b.BetaSpec|t2'"]

        when: 'made for a node'
        AwCallContext.set('run-77', 'run-gradle')
        try {
            t.runWithProgress(cmd, System.getProperty('java.io.tmpdir'), 60, 'gradle test')
        } finally {
            AwCallContext.clear()
        }

        then: 'the first test posts at once, finish posts the total'
        posted.size() == 2
        posted*.runId.unique() == ['run-77']
        posted*.nodeId.unique() == ['run-gradle']
        posted.last().done == 2
        posted.last().failed == 1
        posted.last().current == 'BetaSpec'

        when: 'CONTROL: no AW context'
        posted.clear()
        t.runWithProgress(cmd, System.getProperty('java.io.tmpdir'), 60, 'gradle test')

        then:
        posted.isEmpty()
    }

    def 'TG-5 marker lines reach the listener but never the captured stdout'() {
        given:
        ToolsService t = new ToolsService()
        List<String> heard = []
        List<String> cmd = ['powershell', '-NoProfile', '-NonInteractive', '-Command',
                            "'> Task :test'; '##AWTEST|SUCCESS|a.b.ToolsSpec|tasks: [compileGroovy]'; 'BUILD SUCCESSFUL'"]

        when:
        Map r = t.runToolRaw(cmd, System.getProperty('java.io.tmpdir'), 60, null, { String l -> heard << l })

        then: 'the listener hears all three'
        r.exitCode == 0
        heard.size() == 3
        heard.any { it.startsWith(GradleProgress.MARKER) }

        and: 'the captured output keeps the build lines and drops the marker'
        !(r.stdout as String).contains(GradleProgress.MARKER)
        !(r.stdout as String).contains('compileGroovy')
        (r.stdout as String).contains('> Task :test')
        (r.stdout as String).contains('BUILD SUCCESSFUL')
    }
}
