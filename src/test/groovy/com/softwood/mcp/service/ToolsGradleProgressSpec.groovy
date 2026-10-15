package com.softwood.mcp.service

import spock.lang.Specification

/**
 * FS 0.9.95 -- the ToolsService half of live gradle progress (see GradleProgressSpec for the parser).
 *
 * CONTRACT:
 *   TG-1  (0.9.97, was: only test, build, check) EVERY gradle task list made for an AW node gets the init script --
 *         bootJar / packageMcpbThin showed only node age (HANDOFF-2026-10-15 G4); with no AW node, none does
 *   TG-2  the command carries --init-script <file> before the user's args, and only when asked
 *   TG-3  every stdout line reaches the line listener -- including lines past the 50k capture cap, which a whole
 *         suite's output passes long before the end
 *   TG-4  a gradle call made for an AW node (AwCallContext) streams marker lines into node progress posts; with no
 *         AW context nothing is posted (CONTROL)
 *   TG-5  (0.9.96) marker lines reach the listener but NOT the captured stdout -- captured, a test named after
 *         'compileGroovy' made gradle-test-summary report a green run as COMPILE_FAILED. Ordinary lines are kept
 *         (CONTROL)
 *   TG-6  (0.9.97) task markers (##AWTASK| / ##AWTASKS|) are kept out of the captured stdout too
 */
class ToolsGradleProgressSpec extends Specification {

    def 'TG-1 every gradle task list made for an AW node gets progress; without a node, none does'() {
        expect:
        ToolsService.progressFor(tasks, true) == true
        ToolsService.progressFor(tasks, false) == false

        where:
        tasks << [['test'], ['clean', 'build'], ['check'], ['compileGroovy'], ['packageMcpbThin'], ['bootJar']]
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

    def 'TG-7 a project with the configuration cache on gets no init script (its closures would fail that build)'() {
        given:
        File dir = File.createTempDir('tg7', '')
        File props = new File(dir, 'gradle.properties')

        when:
        props.text = text

        then:
        ToolsService.configurationCacheOn(dir.absolutePath) == expected

        cleanup:
        dir.deleteDir()

        where:
        text                                                        || expected
        'org.gradle.configuration-cache=true\n'                     || true
        'org.gradle.jvmargs=-Xmx2g\norg.gradle.configuration-cache = true\n' || true
        'org.gradle.configuration-cache=false\n'                    || false
        '# org.gradle.configuration-cache=true\n'                   || false
        'org.gradle.jvmargs=-Xmx2g\n'                               || false
    }

    def 'TG-7b CONTROL: a directory with no gradle.properties is not configuration-cached'() {
        expect:
        !ToolsService.configurationCacheOn(System.getProperty('java.io.tmpdir') + '/no-such-dir-tg7b')
    }

    def 'TG-6 task markers reach the listener but never the captured stdout'() {
        given:
        ToolsService t = new ToolsService()
        List<String> heard = []
        List<String> cmd = ['powershell', '-NoProfile', '-NonInteractive', '-Command',
                            "'##AWTASKS|12'; '##AWTASK|:stageJarForMcpb'; 'BUILD SUCCESSFUL'"]

        when:
        Map r = t.runToolRaw(cmd, System.getProperty('java.io.tmpdir'), 60, null, { String l -> heard << l })

        then:
        heard.size() == 3
        !(r.stdout as String).contains('##AWTASK')
        (r.stdout as String).contains('BUILD SUCCESSFUL')
    }
}
