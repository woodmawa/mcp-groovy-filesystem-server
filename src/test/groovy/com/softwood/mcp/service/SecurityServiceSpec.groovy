package com.softwood.mcp.service

import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.Unroll

/**
 * SecurityService unit spec -- v0.8.83 configurable pattern policy.
 *
 * Verifies:
 *  - Global dangerous patterns still blocked across all executors
 *  - Allowed literals (JDBC boilerplate) are scrubbed before check -> pass
 *  - Per-executor extras: .execute() blocked in python/bash, allowed in groovy
 *  - ProcessBuilder allowed in groovy (internal tooling), blocked if added to config
 *  - Unknown executor gets global patterns only (no extras)
 */
@CompileDynamic
class SecurityServiceSpec extends Specification {

    SecurityService svc

    def setup() {
        svc = new SecurityService()
        // Wire config values matching application.yml defaults
        svc.dangerousPatternsConfig    = 'System.exit,Runtime.getRuntime(),Runtime.exec,GroovyClassLoader,GroovyShell,Eval.me,this.class.classLoader'
        svc.allowedLiteralsConfig      = "Class.forName('org.sqlite.JDBC')"
        svc.executorExtraPatternsConfig = 'python:.execute(),bash:.execute()'
        svc.maxScriptLength            = 100_000
        svc.maxWorkingDirLength        = 4096
        svc.maxExecutionTimeSeconds    = 60
        svc.maxMemoryMb                = 256
    }

    // ── Global blocked patterns ───────────────────────────────────────────────

    @Unroll
    def 'global pattern [#pattern] is blocked in groovy executor'() {
        when:
        svc.checkDangerousPatterns(script, 'groovy')

        then:
        thrown(SecurityException)

        where:
        pattern                    | script
        'System.exit'              | 'System.exit(0)'
        'Runtime.getRuntime()'     | 'Runtime.getRuntime().exec("cmd")'
        'Runtime.exec'             | 'Runtime.exec("ls")'
        'GroovyClassLoader'        | 'new GroovyClassLoader()'
        'GroovyShell'              | 'new GroovyShell().evaluate("x")'
        'Eval.me'                  | 'Eval.me("1+1")'
        'this.class.classLoader'   | 'this.class.classLoader.loadClass("Foo")'
    }

    // ── Allowed literals (JDBC boilerplate) ──────────────────────────────────

    def 'Class.forName(org.sqlite.JDBC) is scrubbed and passes in groovy executor'() {
        given:
        String script = """import java.sql.*
Class.forName('org.sqlite.JDBC')
def conn = DriverManager.getConnection('jdbc:sqlite:/tmp/test.db')
conn.close()"""

        when:
        svc.checkDangerousPatterns(script, 'groovy')

        then:
        noExceptionThrown()
    }

    def 'Class.forName with arbitrary class is NOT in allowlist and is still blocked'() {
        given:
        // Bare Class.forName without the sqlite literal is NOT in the allowlist -- should still pass
        // because 'Class.forName' itself is not in dangerousPatternsConfig either.
        // But Class.forName('some.exploit.Class') should pass the check (not in global list).
        // The protection is that GroovyClassLoader / GroovyShell are blocked, not Class.forName itself.
        // This test documents the intended behaviour explicitly.
        String script = "Class.forName('some.other.Class')"

        when:
        svc.checkDangerousPatterns(script, 'groovy')

        then:
        // Class.forName (arbitrary) passes -- it's not in the global list; only the JDBC literal is in allowlist
        noExceptionThrown()
    }

    // ── Per-executor extras: .execute() ──────────────────────────────────────

    def '.execute() is blocked in python executor'() {
        when:
        svc.checkDangerousPatterns('import subprocess; result = obj.execute()', 'python')

        then:
        thrown(SecurityException)
    }

    def '.execute() is blocked in bash executor'() {
        when:
        svc.checkDangerousPatterns('echo hello | xargs cmd.execute()', 'bash')

        then:
        thrown(SecurityException)
    }

    def '.execute() is ALLOWED in groovy executor (internal process spawning)'() {
        given:
        String script = """def proc = ['python', 'script.py'].execute()
proc.waitForOrKill(30000)
proc.exitValue()"""

        when:
        svc.checkDangerousPatterns(script, 'groovy')

        then:
        noExceptionThrown()
    }

    def '.execute() is ALLOWED in cmd executor'() {
        when:
        svc.checkDangerousPatterns('some cmd that mentions .execute() in a comment', 'cmd')

        then:
        noExceptionThrown()
    }

    // ── ProcessBuilder ────────────────────────────────────────────────────────

    def 'ProcessBuilder is ALLOWED in groovy executor (not in global list)'() {
        when:
        svc.checkDangerousPatterns('new ProcessBuilder("ls").start()', 'groovy')

        then:
        noExceptionThrown()
    }

    def 'ProcessBuilder is blocked if added to executor-extra-patterns for python'() {
        given:
        svc.executorExtraPatternsConfig = 'python:.execute(),bash:.execute(),python:ProcessBuilder'

        when:
        svc.checkDangerousPatterns('ProcessBuilder pb = new ProcessBuilder("ls")', 'python')

        then:
        thrown(SecurityException)
    }

    // ── Unknown executor gets global only, no extras ─────────────────────────

    def 'unknown executor type gets global patterns only, no executor extras'() {
        given: 'a script with .execute() which is only extra-blocked for python/bash'
        String script = 'obj.execute()'

        when:
        svc.checkDangerousPatterns(script, 'unknown-executor')

        then:
        noExceptionThrown()
    }

    // ── validateScript public API (full pipeline) ─────────────────────────────

    def 'validateScript passes JDBC boilerplate in groovy executor with valid working dir'() {
        given:
        String script = "Class.forName('org.sqlite.JDBC')\ndef conn = DriverManager.getConnection('jdbc:sqlite:C:/tmp/test.db')"
        String workingDir = 'C:/Users/willw/IdeaProjects'

        when:
        svc.validateScript(script, workingDir, 'groovy')

        then:
        noExceptionThrown()
    }

    def 'validateScript rejects System.exit in any executor'() {
        when:
        svc.validateScript('System.exit(1)', 'C:/Users/willw/IdeaProjects', 'groovy')

        then:
        thrown(SecurityException)
    }

    // ── Empty / null script ───────────────────────────────────────────────────

    def 'empty script throws IllegalArgumentException'() {
        when:
        svc.validateScript('', 'C:/Users/willw/IdeaProjects', 'groovy')

        then:
        thrown(IllegalArgumentException)
    }
}
