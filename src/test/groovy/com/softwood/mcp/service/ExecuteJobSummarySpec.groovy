package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path

/**
 * FS 0.9.94: measured live 2026-10-14 16:39 -- AW progress listed an FS async job as
 * "powershell -NoProfile ... -File C:\Users\willw\AppData\Local\Temp\mcp-ps-9602718752786609684.ps1": the shell's
 * argv, which names a temp file and says nothing about the work. The summary is now the script's first non-blank
 * line (plus how many more lines there are); the argv is the fallback when there is no script file.
 */
@Title('ExecuteService -- a job is summarised by what it runs')
class ExecuteJobSummarySpec extends Specification {

    @TempDir Path dir

    private File script(String text) {
        File f = dir.resolve('mcp-ps-123.ps1').toFile()
        f.text = text
        return f
    }

    static final List<String> ARGV = ['powershell', '-NoProfile', '-File', 'C:/Users/willw/AppData/Local/Temp/mcp-ps-123.ps1']

    def 'JS-1: the first non-blank script line, and a count of the rest'() {
        expect:
        ExecuteService.jobSummary(ARGV, script('\n  ./gradlew test --no-daemon\nGet-ChildItem build\n"done"\n')) ==
            './gradlew test --no-daemon (+2 lines)'
    }

    def 'JS-2: a one-line script is just that line'() {
        expect:
        ExecuteService.jobSummary(ARGV, script('Start-Sleep 3; "done"')) == 'Start-Sleep 3; "done"'
    }

    def 'JS-3: a long line is clipped to 120 characters'() {
        expect:
        ExecuteService.jobSummary(ARGV, script('x' * 300)).length() == 120
    }

    def 'JS-4: CONTROL -- no script file, the argv as before'() {
        expect:
        ExecuteService.jobSummary(['cmd', '/c', 'dir'], null) == 'cmd /c dir'
    }
}
