package com.softwood.mcp.script

import com.softwood.mcp.service.PathService
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit

/**
 * SecureMcpScript — base class for Groovy scripts executed via ExecuteService.
 *
 * Provides a safe DSL: git, gradle, bash, powershell, cmd, file helpers.
 * Dangerous reflective APIs (GroovyShell, Class.forName, etc.) are blocked
 * by SecurityService before the script reaches this class.
 *
 * Script output is captured via the scriptOutput binding variable.
 *
 * v0.0.7 — cleaned up, removed old service dependencies
 */
@Slf4j
abstract class SecureMcpScript extends Script {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60

    // -----------------------------------------------------------------------
    // Binding helpers
    // -----------------------------------------------------------------------

    String getWorkingDir() {
        binding.hasVariable('workingDir') ? binding.getVariable('workingDir') as String : System.getProperty('user.dir')
    }

    List<String> getArgs() {
        binding.hasVariable('args') ? binding.getVariable('args') as List<String> : []
    }

    private List<String> getScriptOutput() {
        if (!binding.hasVariable('scriptOutput')) {
            binding.setVariable('scriptOutput', [])
        }
        return binding.getVariable('scriptOutput') as List<String>
    }

    // -----------------------------------------------------------------------
    // Output capture — override println/print so output is returned to caller
    // -----------------------------------------------------------------------

    void println(Object message) {
        scriptOutput.add(message?.toString() ?: 'null')
    }

    void print(Object message) {
        List<String> out = scriptOutput
        String s = message?.toString() ?: ''
        if (out.isEmpty()) { out.add(s) } else { out.set(out.size() - 1, out.last() + s) }
    }

    // -----------------------------------------------------------------------
    // File helpers
    // -----------------------------------------------------------------------

    File file(String path) {
        if (!path) return null
        File f = new File(path)
        return f.isAbsolute() ? f : new File(workingDir, path).canonicalFile
    }

    String readText(String path, String encoding = 'UTF-8') {
        file(path).getText(encoding)
    }

    void writeText(String path, String content, String encoding = 'UTF-8') {
        file(path).setText(content, encoding)
    }

    void appendText(String path, String content, String encoding = 'UTF-8') {
        file(path).append(content, encoding)
    }

    boolean fileExists(String path) { file(path).exists() }

    List<String> listDir(String path) {
        // Use Java NIO to avoid Groovy GDK phantom Windows reserved names (NUL, CON, etc.)
        File d = file(path)
        if (!d.isDirectory()) return []
        List<String> names = []
        java.nio.file.Files.newDirectoryStream(d.toPath()).withCloseable { stream ->
            stream.each { java.nio.file.Path entry ->
                String name = entry.fileName.toString()
                // Filter Windows reserved device names
                String upper = name.toUpperCase(Locale.ROOT)
                boolean reserved = ['CON','PRN','AUX','NUL','COM1','COM2','COM3','COM4','COM5',
                                    'COM6','COM7','COM8','COM9','LPT1','LPT2','LPT3','LPT4',
                                    'LPT5','LPT6','LPT7','LPT8','LPT9'].any {
                    upper == it || upper.startsWith("${it}.")
                }
                if (!reserved) names << name
            }
        }
        return names.sort()
    }

    // -----------------------------------------------------------------------
    // Shell execution helpers
    // -----------------------------------------------------------------------

    /** Run a git sub-command in workingDir */
    Map<String, Object> git(String... args) {
        runCmd(['git'] + args.toList())
    }

    /** Common git shortcuts */
    Map<String, Object> gitStatus()                          { git('status', '--short') }
    Map<String, Object> gitAdd(String... paths)              { git(['add'] + paths.toList() as String[]) }
    Map<String, Object> gitCommit(String message)            { git('commit', '-m', message) }
    Map<String, Object> gitPush(String remote = 'origin')   { git('push', remote) }
    Map<String, Object> gitPull(String remote = 'origin')   { git('pull', remote) }
    Map<String, Object> gitLog(int n = 10)                  { git('log', '--pretty=format:%h %s', '-n', n.toString()) }
    String gitBranch()                                       { git('rev-parse', '--abbrev-ref', 'HEAD').stdout?.trim() }

    /** Run a gradle wrapper task */
    Map<String, Object> gradle(String... args) {
        boolean windows = System.getProperty('os.name').toLowerCase().contains('windows')
        String wrapper  = new File(workingDir, windows ? 'gradlew.bat' : 'gradlew').exists()
            ? (windows ? 'gradlew.bat' : './gradlew') : 'gradle'
        runCmd([wrapper] + args.toList() + ['--no-daemon'])
    }

    /** Run a PowerShell command */
    Map<String, Object> powershell(String command) {
        runCmd(['powershell', '-NoProfile', '-NonInteractive', '-Command', command])
    }
    Map<String, Object> ps(String command) { powershell(command) }

    /** Run a bash command */
    Map<String, Object> bash(String command) {
        runCmd(['bash', '-c', command])
    }

    /** Run a cmd /c command (Windows) */
    Map<String, Object> cmd(String command) {
        runCmd(['cmd', '/c', command])
    }

    /**
     * Core process runner — shared by all shell helpers.
     * Returns [exitCode, stdout, stderr, success, durationMs].
     */
    Map<String, Object> runCmd(List<String> cmd, int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS) {
        long start = System.currentTimeMillis()
        Process process = null
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
            pb.directory(new File(workingDir))
            pb.redirectErrorStream(false)
            process = pb.start()

            StringBuilder stdout = new StringBuilder()
            StringBuilder stderr = new StringBuilder()

            Thread t1 = Thread.ofVirtual().start({ process.inputStream.eachLine { stdout.append(it).append('\n') } })
            Thread t2 = Thread.ofVirtual().start({ process.errorStream.eachLine { stderr.append(it).append('\n') } })

            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            t1.join(1000); t2.join(1000)

            long ms = System.currentTimeMillis() - start
            if (!done) { process.destroyForcibly() }

            int exitCode = done ? process.exitValue() : -1
            return [exitCode: exitCode, stdout: stdout.toString(), stderr: stderr.toString(),
                    success: exitCode == 0, durationMs: ms]
        } catch (Exception e) {
            process?.destroyForcibly()
            long ms = System.currentTimeMillis() - start
            return [exitCode: -1, stdout: '', stderr: e.message, success: false, durationMs: ms]
        }
    }

    // -----------------------------------------------------------------------
    // Path helpers
    // -----------------------------------------------------------------------

    String normalizePath(String path) {
        path?.replace('\\', '/')
    }

    String toWslPath(String winPath) {
        if (!winPath) return winPath
        String n = winPath.replace('\\', '/')
        if (n.matches('^[A-Za-z]:/.*')) {
            return "/mnt/${n[0].toLowerCase()}${n.substring(2)}"
        }
        return n
    }
}
