package com.softwood.mcp.support

import groovy.transform.CompileStatic

/**
 * FS 0.9.68 (chain 2e0ad21b) -- gradle arguments made safe for the java launcher.
 *
 * On Windows, gradlew.bat starts GradleWrapperMain with `java -classpath ... Main %*`, and the java
 * launcher glob-expands every argument containing * or ? against the files in its working
 * directory -- case-insensitively, quoted or not -- whenever at least one file matches. A pattern
 * that matches nothing passes through untouched, which is why the defect was silent and
 * input-dependent: `--tests *TelemetryExclusionSpec` worked, `--tests *Telemetry*` in the CS repo
 * reached gradle as `--tests ontology-telemetry-dashboard-brief.md`.
 *
 * Measured 2026-10-05 (Java 25): cmd and the .bat hop do NOT expand; the launcher does; quoting the
 * value does not stop it. Gradle 9.3 accepts the folded form `--tests=<pattern>` as the filter, and
 * no file is named `--tests=...`, so folding the option and its value into one token is the fix.
 * It is applied on every OS: one behaviour, and the folded form is valid everywhere.
 */
@CompileStatic
final class GradleArgs {

    /** Options whose VALUE is a test-filter pattern and may carry * or ?. */
    static final List<String> FOLDED_OPTIONS = ['--tests'].asImmutable()

    private GradleArgs() {}

    /**
     * `--tests X` becomes `--tests=X`; everything else, including a trailing `--tests` with no value, is kept
     * as given and in order.
     *
     * FS 0.9.89 T3: a value holding several patterns -- separated by spaces or commas, which a gradle filter
     * pattern never contains -- becomes one folded filter per pattern, so `gradle-test-summary tests="*A* *B*"`
     * runs both specs in one gradle call. An already-folded `--tests=A,B` is expanded the same way.
     */
    static List<String> launcherSafe(List<String> args) {
        List<String> out = []
        if (!args) return out
        int i = 0
        while (i < args.size()) {
            String a = args[i]
            if ((a in FOLDED_OPTIONS) && i + 1 < args.size()) {
                patterns(args[i + 1]).each { String p -> out << (a + '=' + p) }
                i += 2
            } else if (FOLDED_OPTIONS.any { String o -> a.startsWith(o + '=') }) {
                int eq = a.indexOf('=')
                String opt = a.substring(0, eq)
                patterns(a.substring(eq + 1)).each { String p -> out << (opt + '=' + p) }
                i++
            } else {
                out << a
                i++
            }
        }
        return out
    }

    /** The patterns in one filter value: split on spaces and commas, blanks dropped; never empty. */
    static List<String> patterns(String value) {
        List<String> ps = (value ?: '').split(/[\s,]+/).findAll { String s -> s } as List<String>
        return ps ?: [value ?: '']
    }
}
