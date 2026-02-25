package com.softwood.mcp.service

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.promise.Promise
import com.softwood.mcp.promise.Promises
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * ToolsService — handles the tools tool.
 *
 * actions: git | gradle | mvn | npm | project_scan | stats
 *
 * Wraps common developer toolchain commands as parameterised sub-commands.
 * All commands run via ProcessBuilder in the configured working directory.
 *
 * v0.0.7 — Phase 3 Execution + Tools
 */
@Service
@Slf4j
@CompileStatic
class ToolsService extends AbstractFileService implements ToolHandler {

    @Autowired
    ChunkBufferService chunkBufferService

    @Autowired
    UsageTracker usageTracker

    @Value('${mcp.script.max-execution-time-seconds:60}')
    int maxExecutionTimeSeconds

    ToolsService(PathService pathService) {
        super(pathService)
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : 'tools',
            description: '''\
Developer toolchain. Actions:
- git(subcommand, args[], options.workingDir, options.message): status|log|diff|add|commit|push|pull|branch|stash|clone|fetch|checkout|merge|show|tag|remote|reset|revert. IMPORTANT: commit requires options.message or process hangs.
- gradle(subcommand, args[], options.workingDir, options.timeout): build|test|clean|compileGroovy|compileJava|bootRun|bootJar|jar|dependencies|tasks|check|assemble|publish|wrapper
- mvn(subcommand, args[]): package|test|clean|install|verify|compile|dependency:tree
- npm(subcommand, args[]): install|build|test|run|start|lint|audit
- project_scan(options.workingDir): structure + git + build system in one call
- stats: JVM memory, chunk buffer, allowed dirs. options.period: today|week|month|all''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action    : [type: 'string', enum: ['git', 'gradle', 'mvn', 'npm', 'project_scan', 'stats'],
                                 description: 'Toolchain to invoke'],
                    subcommand: [type: 'string', description: 'Sub-command or task (required for git/gradle/mvn/npm)'],
                    args      : [type: 'array', items: [type: 'string'],
                                 description: 'Additional arguments passed after the subcommand'],
                    options   : [type: 'object',
                                 description: 'workingDir (string, defaults to active project root), timeout (int seconds), message (string, required for git commit)',
                                 properties: [
                                     workingDir: [type: 'string'],
                                     timeout   : [type: 'integer'],
                                     message   : [type: 'string'],
                                     period    : [type: 'string', description: 'Stats period: today|week|month|all (default: today)']
                                 ]]
                ],
                required  : ['action']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == 'tools' }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action     = arguments.action as String
            String subcommand = arguments.subcommand as String ?: ''
            List<String> args = (arguments.args as List<String>) ?: []
            Map<String, Object> options = (arguments.options as Map<String, Object>) ?: [:] as Map<String, Object>

            String workingDir = options.workingDir as String ?: activeProjectRoot ?: allowedDirectories[0]
            workingDir = pathService.normalizePath(workingDir)
            if (!isPathAllowed(workingDir)) {
                throw new SecurityException("Working directory not in allowed list: ${sanitize(workingDir)}")
            }

            int timeout = (options.timeout as Integer) ?: maxExecutionTimeSeconds

            switch (action) {
                case 'git'         : return doGit(subcommand, args, workingDir, timeout, options, requestId)
                case 'gradle'      : return doGradle(subcommand, args, workingDir, timeout, requestId)
                case 'mvn'         : return doMvn(subcommand, args, workingDir, timeout, requestId)
                case 'npm'         : return doNpm(subcommand, args, workingDir, timeout, requestId)
                case 'project_scan': return doProjectScan(workingDir, requestId)
                case 'stats'       : return doStats(requestId, options)
                default:
                    return McpResponse.error(requestId, -32602, "Unknown tools action: ${action}")
            }
        } catch (SecurityException e) {
            return McpResponse.error(requestId, -32603, "Security error: ${sanitize(e.message)}")
        } catch (Exception e) {
            log.error("tools error: {}", sanitize(e.message))
            return McpResponse.error(requestId, -32603, sanitize(e.message))
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private McpResponse doGit(String subcommand, List<String> args, String workingDir,
                               int timeout, Map<String, Object> options, Object requestId) {
        List<String> allowed = ['status', 'log', 'diff', 'add', 'commit', 'push', 'pull',
                                'branch', 'stash', 'clone', 'fetch', 'checkout', 'merge',
                                'show', 'tag', 'remote', 'reset', 'revert']
        if (!subcommand) return McpResponse.error(requestId, -32602, "subcommand required for git. Allowed: ${allowed.join(', ')}")
        if (!(subcommand in allowed)) return McpResponse.error(requestId, -32602, "git subcommand '${subcommand}' not allowed. Allowed: ${allowed.join(', ')}")

        List<String> cmd = ['git', subcommand]

        // Commit MUST have a message - without it git opens an editor which hangs in headless mode
        if (subcommand == 'commit') {
            if (!options.message) {
                return McpResponse.error(requestId, -32602,
                    'git commit requires options.message - omitting it causes the process to hang waiting for an editor')
            }
            cmd += ['-m', options.message as String]
        }

        cmd.addAll(args)
        return runTool(cmd, workingDir, timeout, "git ${subcommand}", requestId)
    }

    private McpResponse doGradle(String subcommand, List<String> args, String workingDir,
                                  int timeout, Object requestId) {
        List<String> allowed = ['build', 'test', 'clean', 'compileGroovy', 'compileJava',
                                'bootRun', 'bootJar', 'jar', 'dependencies', 'tasks',
                                'check', 'assemble', 'publish', 'wrapper']
        if (!subcommand) return McpResponse.error(requestId, -32602, "subcommand required for gradle. Allowed: ${allowed.join(', ')}")
        if (!(subcommand in allowed)) return McpResponse.error(requestId, -32602, "gradle task '${subcommand}' not allowed. Allowed: ${allowed.join(', ')}")

        // Use gradlew wrapper if available, fall back to system gradle
        boolean isWindows = System.getProperty('os.name').toLowerCase().contains('windows')
        File wrapperFile  = new File(workingDir, isWindows ? 'gradlew.bat' : 'gradlew')
        String gradleCmd  = wrapperFile.exists() ? wrapperFile.absolutePath : 'gradle'

        List<String> cmd = [gradleCmd, subcommand, '--no-daemon'] + args
        return runTool(cmd, workingDir, timeout, "gradle ${subcommand}", requestId)
    }

    private McpResponse doMvn(String subcommand, List<String> args, String workingDir,
                               int timeout, Object requestId) {
        List<String> allowed = ['package', 'test', 'clean', 'install', 'verify', 'compile',
                                'dependency:tree', 'dependency:resolve', 'help:effective-pom']
        if (!subcommand) return McpResponse.error(requestId, -32602, "subcommand required for mvn. Allowed: ${allowed.join(', ')}")
        if (!(subcommand in allowed)) return McpResponse.error(requestId, -32602, "mvn goal '${subcommand}' not allowed. Allowed: ${allowed.join(', ')}")

        List<String> cmd = ['mvn', subcommand] + args
        return runTool(cmd, workingDir, timeout, "mvn ${subcommand}", requestId)
    }

    private McpResponse doNpm(String subcommand, List<String> args, String workingDir,
                               int timeout, Object requestId) {
        List<String> allowed = ['install', 'build', 'test', 'run', 'start', 'lint',
                                'audit', 'outdated', 'list']
        if (!subcommand) return McpResponse.error(requestId, -32602, "subcommand required for npm. Allowed: ${allowed.join(', ')}")
        if (!(subcommand in allowed)) return McpResponse.error(requestId, -32602, "npm command '${subcommand}' not allowed. Allowed: ${allowed.join(', ')}")

        List<String> cmd = ['npm', subcommand] + args
        return runTool(cmd, workingDir, timeout, "npm ${subcommand}", requestId)
    }

    private McpResponse doProjectScan(String workingDir, Object requestId) {
        Map<String, Object> scan = [:] as Map<String, Object>
        scan.workingDir = workingDir
        File dir = new File(workingDir)

        // Parallel: git status + git branch + directory scan all independent I/O
        Promise<Map<String, Object>> gitStatusP = Promises.async({ ->
            try {
                Map<String, Object> r = runToolRaw(['git', 'status', '--short'], workingDir, 10)
                return [status: r.stdout as String] as Map<String, Object>
            } catch (Exception e) {
                return [status: 'not a git repo or git not available'] as Map<String, Object>
            }
        } as Callable<Map<String, Object>>)

        Promise<Map<String, Object>> gitBranchP = Promises.async({ ->
            try {
                Map<String, Object> r = runToolRaw(['git', 'rev-parse', '--abbrev-ref', 'HEAD'], workingDir, 5)
                return [branch: (r.stdout as String)?.trim()] as Map<String, Object>
            } catch (Exception e) {
                return [branch: 'unknown'] as Map<String, Object>
            }
        } as Callable<Map<String, Object>>)

        Promise<Map<String, Object>> dirScanP = Promises.async({ ->
            File[] children = dir.listFiles()
            List<String> keyFiles = ['build.gradle', 'pom.xml', 'package.json', 'settings.gradle',
                                     'Dockerfile', 'README.md', '.gitignore', 'application.yml',
                                     'application.properties']
            return [
                buildSystem   : detectBuildSystem(dir),
                fileCount     : children ? children.count { File f -> f.isFile() } : 0,
                dirCount      : children ? children.count { File f -> f.isDirectory() } : 0,
                topLevelItems : children ? children.collect { File f -> f.name }.take(30).sort() : [],
                keyFilesPresent: keyFiles.findAll { String f -> new File(dir, f).exists() }
            ] as Map<String, Object>
        } as Callable<Map<String, Object>>)

        // Await all three in parallel
        List<Map<String, Object>> results = Promises.all([gitStatusP, gitBranchP, dirScanP]).get(15, TimeUnit.SECONDS)
        scan.gitStatus       = results[0].status
        scan.gitBranch       = results[1].branch
        scan.putAll(results[2])

        log.info("project_scan complete for {} (parallel)", workingDir)
        scan.put('action', 'project_scan')
        return textResponse(requestId, scan)
    }

    private McpResponse doStats(Object requestId) {
        return doStats(requestId, [:] as Map<String, Object>)
    }

    private McpResponse doStats(Object requestId, Map<String, Object> options) {
        Runtime rt   = Runtime.runtime
        long total   = rt.totalMemory()
        long free    = rt.freeMemory()
        long used    = total - free
        long max     = rt.maxMemory()

        String period = options.period as String ?: 'today'

        return textResponse(requestId, [
            action        : 'stats',
            serverVersion : com.softwood.mcp.McpGroovyFileSystemServerApplication.package?.implementationVersion ?: 'dev',
            jvm: [
                usedMemoryMb : used.intdiv(1024 * 1024),
                totalMemoryMb: total.intdiv(1024 * 1024),
                maxMemoryMb  : max.intdiv(1024 * 1024),
                availableProc: rt.availableProcessors()
            ],
            chunkBuffer   : chunkBufferService.getStats(),
            usage         : usageTracker.getStats(period),
            allowedDirs   : allowedDirectories,
            projectRoot   : getProjectRoot()
        ])
    }

    // -----------------------------------------------------------------------
    // Process runner
    // -----------------------------------------------------------------------

    private McpResponse runTool(List<String> cmd, String workingDir, int timeout,
                                String label, Object requestId) {
        Map<String, Object> result = runToolRaw(cmd, workingDir, timeout)
        log.info("tools {}: exitCode={}, duration={}ms", label, result.exitCode, result.durationMs)
        return textResponse(requestId, [
            action    : label,
            success   : (result.exitCode as int) == 0,
            exitCode  : result.exitCode,
            stdout    : (result.stdout as String)?.take(50000),
            stderr    : (result.stderr as String)?.take(10000),
            durationMs: result.durationMs
        ])
    }

    private Map<String, Object> runToolRaw(List<String> cmd, String workingDir, int timeout,
                                            Map<String, String> envOverrides = null) {
        long start = System.currentTimeMillis()
        Process process = null
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
            pb.directory(new File(workingDir))
            pb.redirectErrorStream(false)
            if (envOverrides) pb.environment().putAll(envOverrides)
            process = pb.start()

            StringBuilder stdout = new StringBuilder()
            StringBuilder stderr = new StringBuilder()

            Thread stdoutThread = Thread.ofVirtual().start({
                process.inputStream.eachLine { String line -> stdout.append(sanitize(line)).append('\n') }
            })
            Thread stderrThread = Thread.ofVirtual().start({
                process.errorStream.eachLine { String line -> stderr.append(sanitize(line)).append('\n') }
            })

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS)
            // Join with remaining budget rather than hardcoded 2s to avoid truncating large output
            long elapsedMs = System.currentTimeMillis() - start
            long remainingMs = Math.max(500L, (timeout * 1000L) - elapsedMs)
            stdoutThread.join(remainingMs)
            stderrThread.join(remainingMs)

            long durationMs = System.currentTimeMillis() - start

            if (!finished) {
                process.destroyForcibly()
                return [exitCode: -1, stdout: '', stderr: "Timed out after ${timeout}s", durationMs: durationMs] as Map<String, Object>
            }

            return [exitCode: process.exitValue(), stdout: stdout.toString(), stderr: stderr.toString(), durationMs: durationMs] as Map<String, Object>
        } catch (Exception e) {
            process?.destroyForcibly()
            long durationMs = System.currentTimeMillis() - start
            return [exitCode: -1, stdout: '', stderr: sanitize(e.message), durationMs: durationMs] as Map<String, Object>
        }
    }

    private static String detectBuildSystem(File dir) {
        if (new File(dir, 'build.gradle').exists() || new File(dir, 'build.gradle.kts').exists()) return 'gradle'
        if (new File(dir, 'pom.xml').exists()) return 'maven'
        if (new File(dir, 'package.json').exists()) return 'npm'
        if (new File(dir, 'Makefile').exists()) return 'make'
        return 'unknown'
    }
}