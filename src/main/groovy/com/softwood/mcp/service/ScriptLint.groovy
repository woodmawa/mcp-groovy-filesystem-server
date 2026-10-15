package com.softwood.mcp.service

import groovy.transform.CompileStatic

import java.util.regex.Pattern

/**
 * optimised-learning WS1-D (2026-10-15): look at an execute script BEFORE it runs.
 *
 * <p>REFUSED -- nothing useful could run:</p>
 * <ul>
 *   <li>SLEEP_REFUSED: the first statement waits (sleep N, Start-Sleep, timeout /t, ping -n). A wait is a bridge call
 *       and dies at ~60 s while the work carries on; waiting is one flow_management action=progress, or send_later.</li>
 *   <li>EXECUTE_ACTION_MISMATCH: action=python given one line of shell (`python x.py`, `py -3 x.py`). action=python
 *       runs the script AS Python source, so this is always a SyntaxError with no output.</li>
 * </ul>
 * <p>WARNED -- may be intended: a stray % under action=cmd (a variable sigil: git log --format=%h), and gradle -q with
 * test (hides the per-test FAILED lines).</p>
 *
 * <p>Called in ExecuteService next to securityService.validateScript and BEFORE PLAN-GATE, so a script that cannot do
 * what was meant costs no gate. Pinned by ScriptLintSpec and ExecuteServiceDispatchSpec FS-EXEC-OL-1/2.</p>
 */
@CompileStatic
final class ScriptLint {

    private static final Pattern WAIT = Pattern.compile(
        '(?i)^(sleep\\s+\\d|start-sleep\\b|timeout\\s+/t\\b|ping\\s+(-n|-c)\\s+\\d)')
    private static final Pattern SHELL_FOR_PYTHON = Pattern.compile('(?i)^(python3?|py)(\\s+-\\d)?\\s+\\S+.*$')
    private static final Pattern GRADLE_Q_TEST = Pattern.compile('(?i)gradlew?(\\.bat)?\\b(?=.*\\btest\\b)(?=.*\\s-q\\b)')

    private ScriptLint() {}

    static Map<String, Object> check(String action, String script) {
        List<String> warnings = []
        String refusal = null
        String text = script ?: ''
        String first = firstStatement(text)
        if (first && WAIT.matcher(first).find()) {
            refusal = ('SLEEP_REFUSED: this script starts by waiting (' + first + '). A wait inside a call dies at the ~60 s ' +
                       'bridge deadline while the work carries on. Start the work async and wait with ONE ' +
                       'flow_management action=progress (or send_later a nudge for a long wait). Nothing was run.').toString()
        } else if (action == 'python' && isOneLine(text) && SHELL_FOR_PYTHON.matcher(text.trim()).matches()) {
            refusal = ('EXECUTE_ACTION_MISMATCH: action=python runs the script AS Python source, and this is a shell ' +
                       "command ('" + text.trim() + "'). To run a Python file use action=cmd (or powershell) with that " +
                       'command, or pass the Python code itself. Nothing was run.').toString()
        }
        if (action == 'cmd' && strayPercent(text)) {
            warnings << ('% under action=cmd is a variable sigil (git log --format=%h becomes empty). Double it (%%) or use ' +
                         'action=powershell.').toString()
        }
        if (GRADLE_Q_TEST.matcher(text).find()) {
            warnings << 'gradle -q with test hides the per-test FAILED lines; drop -q, or use the gradle-test-summary flow.'
        }
        return [refusal: (Object) refusal, warnings: (Object) warnings] as Map<String, Object>
    }

    /** The first statement: first non-blank, non-comment line, cut at ; && || or a pipe. */
    static String firstStatement(String text) {
        for (String line : text.readLines()) {
            String l = line.trim()
            if (!l || l.startsWith('#') || l.startsWith('REM ') || l.startsWith('::') || l.startsWith('@echo')) continue
            return l.split(/;|&&|\|\||\|/)[0].trim()
        }
        return null
    }

    private static boolean isOneLine(String text) {
        return text.trim() && text.trim().readLines().size() == 1
    }

    /** True when a % remains after removing %%, %VAR%, %~mods and %N. */
    static boolean strayPercent(String text) {
        if (!text.contains('%')) return false
        String t = text.replace('%%', '')
                       .replaceAll(/%[A-Za-z_][A-Za-z0-9_]*%/, '')
                       .replaceAll(/%~[A-Za-z]*[0-9*]/, '')
                       .replaceAll(/%[0-9*]/, '')
        return t.contains('%')
    }
}
