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

    /**
     * FS 0.9.47 FS-EXEC-3 -- PROTECTED, and it has to be.
     *
     * <p>This was {@code private} from e259442 (v0.7.1), and it made every Groovy script fail with
     * {@code No such property: scriptOutput for class: Script1} -- including {@code println 'hello'}.
     * {@code println} is declared here, but it dispatches on the RUNTIME class: the user's compiled
     * {@code Script1}, which extends this one. Groovy resolves the unqualified {@code scriptOutput}
     * as a property against that subclass's metaclass, and a private getter on the superclass is not
     * in it -- so the failure reads as a missing property rather than an access violation, which is
     * why it never looked like a visibility problem.</p>
     *
     * <p>Dead for roughly forty minor versions, because no spec ran a Groovy script. FS-EXEC-3 does.</p>
     */
    protected List<String> getScriptOutput() {
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
        return confine(f.isAbsolute() ? f : new File(workingDir, path))
    }

    // -----------------------------------------------------------------------
    // FS 0.9.51 FS-GS -- the sandbox. PathConfinementCustomizer rewrites every
    // `new File(...)`, `Paths.get(...)` and `Path.of(...)` in the script into these helpers,
    // so the natural script shape gains: relative names resolve against workingDir (not the
    // JVM cwd, which is the Claude app folder), and any canonical path outside the allowed
    // directories is refused. The allowed set arrives in the `allowedDirs` binding from
    // doGroovy; with no binding, workingDir alone is allowed.
    // -----------------------------------------------------------------------

    File file(String parent, String child)  { confine(new File(file(parent), child ?: '')) }
    File file(File parent, String child)    { confine(new File(confine(parent), child ?: '')) }
    File file(Object any)                   { any instanceof File ? confine((File) any) : file(any?.toString()) }

    /** Confined replacement for Paths.get(first, more...) / Path.of(first, more...). */
    java.nio.file.Path path(Object first, Object... more) {
        String joined = ([first?.toString()] + (more ? more*.toString() : [])).findAll { it != null }.join(File.separator)
        return file(joined).toPath()
    }

    /** The directories a script may touch, canonicalised. Read from the `__allowedDirs` binding
     *  (doGroovy sets it); the double underscore keeps it out of the way of script variables, and
     *  confine() calls this getter EXPLICITLY -- inside a Script a bare property name resolves to the
     *  binding before the class, which is how the first build compared against the raw list. */
    protected List<String> getAllowedDirs() {
        List<String> dirs = binding.hasVariable('__allowedDirs') ? (binding.getVariable('__allowedDirs') as List<String>) : null
        if (!dirs) dirs = [workingDir]
        return dirs.collect { canonicalKey(new File(it)) }
    }

    private static String canonicalKey(File f) {
        String c = f.canonicalPath.replace('\\', '/')
        return System.getProperty('os.name').toLowerCase().contains('windows') ? c.toLowerCase(Locale.ROOT) : c
    }

    /** Refuse a File whose canonical path is not under an allowed directory; return it canonical otherwise. */
    protected File confine(File f) {
        if (f == null) return null
        File canon = f.canonicalFile
        String key = canonicalKey(canon)
        List<String> allowed = getAllowedDirs()
        for (String dir : allowed) {
            if (key == dir || key.startsWith(dir.endsWith('/') ? dir : dir + '/')) return canon
        }
        throw new SecurityException("SANDBOX: path '${canon}' is not in the allowed directories for this script (${allowed.join(', ')}). Relative names resolve against workingDir.")
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

    /** Run a bash command. FS 0.9.51: base64 through argv, never the script itself -- the same
     *  Windows argv-quoting defect fixed in ExecuteService.doBash (0.9.50) lived here too. */
    Map<String, Object> bash(String command) {
        String body = command.replaceAll('\\r\\n', '\n')
        String b64 = Base64.encoder.encodeToString(body.getBytes('UTF-8'))
        runCmd(['bash', '-c', "echo ${b64} | base64 -d | bash".toString()])
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
