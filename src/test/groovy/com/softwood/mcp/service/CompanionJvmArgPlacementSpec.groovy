package com.softwood.mcp.service

import groovy.transform.CompileDynamic
import spock.lang.Specification

/**
 * FS 0.9.31 -- W17: a configuration key named {@code jvmArgs} that did not set JVM args.
 *
 * <p>{@code ServerLifecycleService.startServer} built its companion command line as
 * {@code java ... -jar <jar>} and then appended the server's {@code jvmArgs}. Everything after
 * {@code -jar <jar>} is a PROGRAM argument, so every {@code -D} in that array was handed to
 * {@code main(String[])} and dropped. Not one of them has ever set a system property on a
 * companion process -- not {@code mcp.filesystem.allowed-directories}, not
 * {@code mcp.usage.db-path}, not {@code mcp.shared.db-path}. Spring Boot does bind program
 * arguments, but only in {@code --key=value} form, so the {@code -D} spelling used throughout
 * {@code mcp-http-servers.json} binds nothing that way either.</p>
 *
 * <p>Found on 2026-09-11 while arming {@code aw.shaper.strictGlobals} on the AW companion for W4
 * step two: the flag was plainly there in the live process command line, sitting after the jar,
 * doing nothing. The config read as applied and the process read as configured; only the argument
 * ORDER said otherwise, and nothing was looking at the order.</p>
 *
 * <h3>Why this is asserted over the source text</h3>
 * <p>The command is assembled inside {@code startServer}, which then spawns a process, and there
 * is no seam that returns the argument list. Adding one purely for this test would be the better
 * answer; asserting the order over code lines is the cheaper one that can ship today, and it is
 * the same technique {@code OntologyGateCoverageSpec} uses for the dispatch gate. Whole-line
 * comments are stripped first -- this release's own comment block discusses {@code -jar} and
 * {@code jvmArgs} at length, and a raw-source check would be satisfied by that prose, which is
 * exactly how CS 1.0.55 red-built itself.</p>
 *
 * @since FS 0.9.31
 */
@CompileDynamic
class CompanionJvmArgPlacementSpec extends Specification {

    private static final String NL = Character.toString((char) 10)

    private static final File LIFECYCLE_SRC = new File(
        'src/main/groovy/com/softwood/mcp/service/ServerLifecycleService.groovy')

    /** Source with whole-line comments removed; a comment must never satisfy an order check. */
    private static String codeOnly(File src) {
        return src.text.readLines()
                  .findAll { String line ->
                      String t = line.trim()
                      !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*'))
                  }
                  .join(NL)
    }

    def 'CJA-1: companion jvmArgs are added BEFORE -jar, where the JVM can still read them'() {
        given:
        String code = codeOnly(LIFECYCLE_SRC)
        int argsAt = code.indexOf('cmd.addAll(extraArgs')
        int jarAt  = code.indexOf("cmd.add('-jar')")

        expect: 'both anchors present -- a missing one must fail, not silently compare against -1'
        argsAt >= 0
        jarAt >= 0

        and: 'and the extra args go on first'
        assert argsAt < jarAt,
            'jvmArgs appended after -jar are program arguments, not JVM options. Every -D in ' +
            'mcp-http-servers.json was silently discarded while the config read as applied.'
        true
    }

    def 'CJA-2: the jar path is the LAST element, so nothing can be appended past it again'() {
        given:
        String code = codeOnly(LIFECYCLE_SRC)
        int jarPathAt = code.indexOf('cmd.add(jarPath)')
        int builderAt = code.indexOf('new ProcessBuilder(cmd)')

        expect:
        jarPathAt >= 0
        builderAt >= 0

        and: 'nothing is added to cmd between the jar path and the process being built'
        assert !code.substring(jarPathAt + 'cmd.add(jarPath)'.length(), builderAt).contains('cmd.add'),
            'anything added after the jar path is a program argument again -- this is the ' +
            'regression guard, because the defect was an append in exactly that gap'
        true
    }
}
