package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FS 0.9.68 (chain 2e0ad21b) -- `tools action=gradle` must not let a `--tests` wildcard be
 * glob-expanded against the files in the working directory.
 *
 * Measured 2026-10-05, Java 25 on Windows: cmd and gradlew.bat pass `*Telemetry*` through
 * literally; the java launcher, starting a main class as gradlew.bat starts GradleWrapperMain,
 * expands it against its working directory -- case-insensitively, quoted or not -- whenever a
 * file matches, and leaves it alone when none does. So `--tests *Telemetry*` in the CS repo
 * reached gradle as `--tests ontology-telemetry-dashboard-brief.md`, silently, input-dependent.
 *
 * The fake wrapper here is the real hop: gradlew.bat -> java -classpath . Main %*. Its main prints
 * the argv it was handed, which is exactly what GradleWrapperMain would have been handed. The
 * assertion is on that argv, through the private doGradle the tool dispatches to -- the layer
 * that builds the command -- not on a helper's return value.
 *
 * GG-1  a --tests wildcard that matches a file reaches the JVM main as the pattern, not the file
 * GG-2  CONTROL: a wildcard matching no file reaches it as the pattern too (it always did)
 * GG-3  an already-folded --tests=<pattern> is passed through unchanged
 * GG-4  other args keep their order around the fold
 */
@IgnoreIf({ !os.windows })
class ToolsServiceGradleGlobSpec extends Specification {

    @TempDir
    Path dir

    ToolsService tools = new ToolsService(Mock(PathService))

    def setup() {
        File d = dir.toFile()
        new File(d, 'Args.java').text =
            'public class Args { public static void main(String[] a) { for (String s : a) System.out.println("ARG[" + s + "]"); } }\n'
        Process p = new ProcessBuilder('javac', 'Args.java').directory(d).redirectErrorStream(true).start()
        String out = p.inputStream.text
        assert p.waitFor() == 0 : "javac failed: ${out}"
        new File(d, 'gradlew.bat').text = '@echo off\r\njava -classpath "%~dp0." Args %*\r\n'
        new File(d, 'x-Telemetry-y.md').text = 'probe\n'
    }

    /** The ARG[...] lines the fake wrapper's java main printed, in order. */
    private List<String> argvSeen(List<String> args) {
        McpResponse r = tools.doGradle('test', args, dir.toString(), 30, 1)
        List content = (r.result as Map)?.content as List
        String text = content ? (content[0].text as String) : ''
        return (text =~ /ARG\[([^\]]*)\]/).collect { List<String> m -> m[1] }
    }

    def 'GG-1: a --tests wildcard matching a file reaches the JVM main as the pattern'() {
        when:
        List<String> seen = argvSeen(['--tests', '*Telemetry*'])
        then:
        !seen.any { it.contains('x-Telemetry-y.md') }
        seen.contains('--tests=*Telemetry*')
    }

    def 'GG-2: CONTROL a wildcard matching no file reaches it as the pattern'() {
        when:
        List<String> seen = argvSeen(['--tests', '*NoMatchXyz*'])
        then:
        seen.contains('--tests=*NoMatchXyz*') || (seen.contains('--tests') && seen.contains('*NoMatchXyz*'))
    }

    def 'GG-3: an already-folded --tests=<pattern> is passed through unchanged'() {
        when:
        List<String> seen = argvSeen(['--tests=*Telemetry*'])
        then:
        seen.contains('--tests=*Telemetry*')
        !seen.any { it.contains('x-Telemetry-y.md') }
    }

    def 'GG-4: other args keep their order around the fold'() {
        when:
        List<String> seen = argvSeen(['-q', '--tests', 'com.x.*Telemetry*', '--rerun-tasks'])
        then:
        seen == ['test', '--no-daemon', '-q', '--tests=com.x.*Telemetry*', '--rerun-tasks']
    }
}
