package com.softwood.mcp.service

import groovy.transform.CompileStatic

import java.util.regex.Pattern

/**
 * FS 0.9.120 -- files FS never reads, writes, searches or lets a script name: today only the user-home
 * {@code .gradle/gradle.properties}, which holds the signing and Sonatype credentials.
 *
 * <p>On 2026-11-04 a chat read that file with {@code Get-Content} to check one JDK line; the whole file went into an FS
 * job's output, AW stored it, and a later progress call printed the password (rotated the same day). A project's own
 * {@code gradle.properties} is a different file and is not matched. Gradle still reads this one -- the guard is on what
 * FS hands back to a chat, not on the build. SecretFileGuardSpec.</p>
 */
@CompileStatic
final class SecretFiles {

    private SecretFiles() {}

    /** The path, in any separator or case, ends in {@code .gradle/gradle.properties}. */
    private static final Pattern SECRET_PATH = Pattern.compile('(?i)(^|[\\\\/])\\.gradle[\\\\/]+gradle\\.properties$')

    /** A script mentions it by a path -- {@code ~/.gradle/gradle.properties}, {@code $env:USERPROFILE\.gradle\...}. */
    private static final Pattern SECRET_IN_SCRIPT = Pattern.compile('(?i)\\.gradle[\\\\/]+gradle\\.properties')

    private static final Pattern BARE_NAME = Pattern.compile('(?i)gradle\\.properties')
    private static final Pattern GRADLE_DIR = Pattern.compile('(?i)(^|[\\\\/])\\.gradle[\\\\/]*$')

    static final String REASON = 'the user-home .gradle/gradle.properties holds signing and Sonatype credentials; ' +
        'FS never reads, writes, searches or scripts against it. Gradle still reads it. To check one setting, ask Will.'

    static boolean isSecret(String path) {
        if (path == null || path.trim().isEmpty()) return false
        return SECRET_PATH.matcher(path.trim()).find()
    }

    /** Null when the script may run; otherwise why not. */
    static String scriptRefusal(String script, String workingDir) {
        if (!script) return null
        if (SECRET_IN_SCRIPT.matcher(script).find()) return REASON
        if (workingDir && GRADLE_DIR.matcher(workingDir.trim()).find() && BARE_NAME.matcher(script).find()) return REASON
        return null
    }
}
