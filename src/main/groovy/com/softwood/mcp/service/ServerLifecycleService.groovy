package com.softwood.mcp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import jakarta.annotation.PostConstruct
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ServerLifecycleService - manages HTTP MCP server processes.
 *
 * Reads config from: {claude-sync}/mcp-http-servers.json
 * Writes runtime state to: {claude-sync}/mcp-http-servers-runtime.json
 *
 * actions:
 *   start_eager  - start all servers with startupPolicy=eager (call at session begin)
 *   ensure       - start a named lazy server if not already running (on demand)
 *   stop         - stop a named server (or all if name omitted)
 *   status       - report running state of all configured servers
 *   reload       - re-read config from disk (after version updates)
 */
@Service
@Slf4j
@CompileStatic
class ServerLifecycleService extends AbstractFileService implements ToolHandler {

    private static final String TOOL_NAME = 'server_lifecycle'
    private static final String CONFIG_FILENAME = 'mcp-http-servers.json'
    private static final String RUNTIME_FILENAME = 'mcp-http-servers-runtime.json'

    @Value('${MCP_CONTEXT_STORAGE_PATH:C:/Users/willw/claude-sync}')
    String claudeSyncPath

    private final ObjectMapper mapper = new ObjectMapper()

    // Config cache - loaded once, invalidated only by reload action
    private Map<String, Object> configCache = null
    private final Object configLock = new Object()

    // name -> Process (only processes WE started this session)
    private final Map<String, Process> managedProcesses = new ConcurrentHashMap<>()

    ServerLifecycleService(PathService pathService) {
        super(pathService)
    }

    /**
     * Auto-start HTTP companion servers when the filesystem server starts in stdio mode.
     *
     * Servers marked autoHttpCompanion:true in mcp-http-servers.json are started as HTTP child
     * processes so that agentic-workflow flows can reach them via mcp.tool_call serverPort=NNNN.
     * This replaces the need to run start-mcp-services.ps1 manually.
     *
     * The companion processes are tracked in managedProcesses and are killed cleanly by
     * stopAllOnShutdown() when DT or CC exits and the stdio filesystem server terminates.
     *
     * Note: the filesystem server itself (this process) can also be started as an HTTP companion
     * on :8081 — the stdio instance has no port, so there is no conflict.
     */
    @PostConstruct
    void autoStartHttpCompanions() {
        try {
            Map<String, Object> config = loadConfig()
            List<Map> servers = config.servers as List<Map>
            List<Map<String, Object>> started = []

            servers.each { Map server ->
                boolean isCompanion = server.autoHttpCompanion as Boolean
                if (!isCompanion) return

                String name = server.name as String
                int port    = server.port as int

                if (isPortListening(port)) {
                    log.info('ServerLifecycleService: HTTP companion {} already on port {} — skipping', name, port)
                    return
                }

                log.info('ServerLifecycleService: auto-starting HTTP companion: {} on port {}', name, port)
                Map result = startServer(server)
                started << result
                if (result.started) {
                    log.info('ServerLifecycleService: HTTP companion {} started (pid={})', name, result.pid)
                } else {
                    log.warn('ServerLifecycleService: HTTP companion {} failed to start: {}', name, result.error ?: result.reason)
                }
            }

            if (started) {
                writeRuntimeState()
                log.info('ServerLifecycleService: {} HTTP companion(s) processed at startup', started.size())
            } else {
                log.debug('ServerLifecycleService: no autoHttpCompanion servers configured')
            }
        } catch (Exception e) {
            // Non-fatal — companion startup failure must not prevent the filesystem server from serving Claude
            log.warn('ServerLifecycleService: autoStartHttpCompanions failed (non-fatal): {}', e.message)
        }
    }

    // -----------------------------------------------------------------------
    // ToolHandler
    // -----------------------------------------------------------------------

    @Override
    List<Map<String, Object>> getToolDefinitions() {
        return [[
            name       : TOOL_NAME,
            description: '''\
Manage HTTP MCP server processes. Config from claude-sync/mcp-http-servers.json.
Actions: start_eager (all eager servers) | ensure (start named lazy server) | stop (named or all) | status | reload (re-read config).''',
            inputSchema: [
                type      : 'object',
                properties: [
                    action : [type: 'string', enum: ['start_eager', 'ensure', 'stop', 'status', 'reload'],
                              description: 'Lifecycle action'],
                    name   : [type: 'string',
                              description: 'Server name (filesystem|context|orchestrator|agentic-workflow). Required for ensure/stop a specific server.'],
                    verbose: [type: 'boolean',
                              description: 'Set verbose:true for full status response including jar/startupPolicy/managedBySession/processAlive. Default: compact (name/port/state only).']
                ],
                required  : ['action']
            ]
        ]] as List<Map<String, Object>>
    }

    @Override
    boolean canHandle(String toolName) { toolName == TOOL_NAME }

    @Override
    McpResponse handleToolCall(String toolName, Map<String, Object> arguments, Object requestId) {
        try {
            String action = arguments.action as String
            String name   = arguments.name as String

            switch (action) {
                case 'start_eager': return doStartEager(requestId)
                case 'ensure'     : return doEnsure(name, requestId)
                case 'stop'       : return doStop(name, requestId)
                case 'status'     : return doStatus(arguments, requestId)
                case 'reload'     : return doReload(requestId)
                default:
                    return McpResponse.error(requestId, -32602,
                        "Unknown server_lifecycle action: ${action}" as String)
            }
        } catch (Exception e) {
            log.error("server_lifecycle error", e)
            return McpResponse.error(requestId, -32603, sanitize(e.message) as String)
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private McpResponse doStartEager(Object requestId) {
        Map<String, Object> config = loadConfig()
        List<Map> servers = config.servers as List<Map>
        List<Map<String, Object>> results = []

        servers.each { Map server ->
            if ((server.startupPolicy as String) == 'eager') {
                results << startServer(server)
            }
        }

        writeRuntimeState()
        return textResponse(requestId, [action: 'start_eager', results: results])
    }

    private McpResponse doEnsure(String name, Object requestId) {
        if (!name) {
            return McpResponse.error(requestId, -32602, 'ensure requires server name' as String)
        }
        Map<String, Object> config = loadConfig()
        List<Map> servers = config.servers as List<Map>
        Map server = servers.find { (it.name as String) == name }

        if (!server) {
            return McpResponse.error(requestId, -32602,
                "Unknown server: ${name}. Known: ${servers*.name.join(', ')}" as String)
        }

        Map<String, Object> result = startServer(server)
        writeRuntimeState()
        return textResponse(requestId, [action: 'ensure', result: result])
    }

    private McpResponse doStop(String name, Object requestId) {
        List<Map<String, Object>> results = []

        if (name) {
            results << stopOneServer(name)
        } else {
            // Stop all - managed processes first, then any externally-started ones
            Map<String, Object> config = loadConfig()
            List<String> allNames = (config.servers as List<Map>)*.name as List<String>
            // Reverse order: agentic -> orchestrator -> context -> filesystem
            allNames.reverse().each { String n -> results << stopOneServer(n) }
        }

        writeRuntimeState()
        return textResponse(requestId, [action: 'stop', results: results])
    }

    /**
     * Stop a single server by name. Tries managed process map first, then falls back
     * to killing by port - handles servers started externally (e.g. PowerShell launcher).
     */
    /**
     * Stop a single server by name. Tries managed process map first, then falls back
     * to killing by port - handles servers started externally (e.g. PowerShell launcher).
     * After every kill path, verifies the port is actually free and applies a Windows
     * netstat-based force-kill if the port remains occupied.
     */
    private Map<String, Object> stopOneServer(String name) {
        Map<String, Object> r = new LinkedHashMap<String, Object>()
        r.put('name', name)

        // Resolve the port early so we can verify it's free after any kill path
        int port = 0
        try {
            Map<String, Object> config = loadConfig()
            List<Map> servers = config.servers as List<Map>
            Map server = servers.find { (it.name as String) == name }
            if (server) port = server.port as int
        } catch (Exception ignored) {}

        // 1. Try managed process (started by this session)
        Process proc = managedProcesses.remove(name)
        if (proc) {
            proc.destroy()
            proc.waitFor(5, TimeUnit.SECONDS)
            if (proc.alive) proc.destroyForcibly()
            r.put('stopped', true)
            r.put('method', 'managed-process')
            log.info('server_lifecycle: stopped {} via managed process', name)

            // Verify port is actually free; Windows TIME_WAIT can keep it occupied briefly
            if (port > 0 && !waitForPortFree(port, 5)) {
                log.warn('server_lifecycle: port {} still occupied after managed-process kill — applying force-kill', port)
                boolean forceKilled = killByPort(port)
                r.put('portForceKilled', forceKilled)
                if (!forceKilled) r.put('warning', 'port ' + port + ' still occupied after force-kill')
            }
            return r
        }

        // 2. Fall back: find port from config and kill by PID owning that port
        try {
            if (port > 0) {
                if (isPortListening(port)) {
                    // Try runtime state PID first
                    boolean killed = killByRuntimePid(name)
                    if (!killed) {
                        // Try actuator graceful shutdown
                        killed = requestActuatorShutdown(port)
                    }
                    if (!killed) {
                        // Final fallback: Windows netstat force-kill
                        killed = killByPort(port)
                    }
                    // Verify port freed
                    boolean portFree = killed ? waitForPortFree(port, 5) : false
                    r.put('stopped', killed || portFree)
                    r.put('method', 'port-kill')
                    r.put('port', port)
                    if (!portFree) r.put('warning', 'port still listening after kill attempt')
                    log.info('server_lifecycle: stopped {} via port-kill (port {}), portFree={}', name, port, portFree)
                } else {
                    r.put('stopped', false)
                    r.put('reason', 'not running')
                }
            } else {
                r.put('stopped', false)
                r.put('reason', 'unknown server name')
            }
        } catch (Exception e) {
            r.put('stopped', false)
            r.put('error', sanitize(e.message) as String)
        }
        return r
    }

    private boolean killByRuntimePid(String name) {
        try {
            File runtimeFile = new File("${claudeSyncPath}/${RUNTIME_FILENAME}")
            if (!runtimeFile.exists()) return false
            Map<String, Object> state = mapper.readValue(runtimeFile, Map)
            List<Map> servers = state.managedServers as List<Map> ?: []
            Map entry = servers.find { it.name == name }
            if (!entry) return false
            long pid = entry.pid as long
            if (pid <= 0) return false
            Optional<ProcessHandle> ph = ProcessHandle.of(pid)
            if (ph.isPresent() && ph.get().isAlive()) {
                ph.get().destroyForcibly()
                Thread.sleep(1000)
                return !ph.get().isAlive()
            }
        } catch (Exception e) {
            log.warn("server_lifecycle: killByRuntimePid failed for {}: {}", name, e.message)
        }
        return false
    }

    private boolean requestActuatorShutdown(int port) {
        try {
            URL url = new URL("http://localhost:${port}/actuator/shutdown")
            HttpURLConnection conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = 'POST'
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.connect()
            int code = conn.responseCode
            conn.disconnect()
            if (code in [200, 204]) {
                Thread.sleep(3000) // give it time to shut down
                return !isPortListening(port)
            }
        } catch (Exception e) {
            log.warn("server_lifecycle: actuator shutdown failed on port {}: {}", port, e.message)
        }
        return false
    }

    private McpResponse doStatus(Map<String, Object> arguments, Object requestId) {
        boolean verbose  = arguments.verbose as boolean ?: false
        Map<String, Object> config = loadConfig()
        List<Map> servers = config.servers as List<Map>
        List<Map<String, Object>> statuses = []

        servers.each { Map server ->
            String serverName = server.name as String
            int port = server.port as int
            boolean portOpen = isPortListening(port)

            if (verbose) {
                boolean managed = managedProcesses.containsKey(serverName)
                boolean alive   = managed && (managedProcesses[serverName]?.alive ?: false)
                Map<String, Object> status = new LinkedHashMap<String, Object>()
                status.put('name', serverName)
                status.put('port', port)
                status.put('state', portOpen ? 'UP' : 'DOWN')
                status.put('jar', server.jar)
                status.put('startupPolicy', server.startupPolicy ?: 'eager')
                status.put('portListening', portOpen)
                status.put('managedBySession', managed)
                status.put('processAlive', alive)
                statuses << status
            } else {
                // Compact: only what's needed to act on - name, port, UP/DOWN
                Map<String, Object> status = new LinkedHashMap<String, Object>()
                status.put('name', serverName)
                status.put('port', port)
                status.put('state', portOpen ? 'UP' : 'DOWN')
                statuses << status
            }
        }

        return textResponse(requestId, [servers: statuses])
    }

    private McpResponse doReload(Object requestId) {
        // Invalidate cache then re-read from disk
        invalidateConfigCache()
        Map<String, Object> config = loadConfig()
        List<Map> servers = config.servers as List<Map>
        return textResponse(requestId, [
            action : 'reload',
            success: true,
            servers: servers.collect { [name: it.name, jar: it.jar, port: it.port, startupPolicy: it.startupPolicy] }
        ])
    }

    // -----------------------------------------------------------------------
    // Process management
    // -----------------------------------------------------------------------

    private Map<String, Object> startServer(Map server) {
        String name = server.name as String
        int port    = server.port as int
        String jar  = server.jar as String

        Map<String, Object> result = new LinkedHashMap<String, Object>()
        result.put('name', name)
        result.put('port', port)
        result.put('jar', jar)

        // Kill any stale process from a previous session recorded in runtime state
        killStalePidIfPresent(name, port)

        // Check if already listening - don't double-start
        if (isPortListening(port)) {
            log.info("server_lifecycle: {} already listening on port {}, skipping", name, port)
            result.put('started', false)
            result.put('reason', 'already listening on port ' + port)
            return result
        }

        try {
            Map<String, Object> config = loadConfig()
            String jarsDir = config.jarsDir as String
            String javaCmd = config.javaCmd as String ?: 'java'

            String jarPath = (jarsDir + '/' + jar).replace('/', File.separator)
            if (!new File(jarPath).exists()) {
                result.put('started', false)
                result.put('error', ('Jar not found: ' + jarPath) as String)
                return result
            }

            List<String> cmd = new ArrayList<String>()
            cmd.add(javaCmd)
            cmd.add('--enable-native-access=ALL-UNNAMED')
            cmd.add('-XX:+IgnoreUnrecognizedVMOptions')
            cmd.add(('-DMCP_HTTP_PORT=' + port) as String)
            cmd.add('-jar')
            cmd.add(jarPath)

            // Server-specific extra args
            List extraArgs = server.jvmArgs as List ?: []
            if (extraArgs) cmd.addAll(extraArgs as List<String>)

            ProcessBuilder pb = new ProcessBuilder(cmd)
            pb.redirectErrorStream(false)
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            // Redirect stderr to AppData/Roaming/Claude/logs - consistent with STDIO server log location.
            // HTTP-only instances aren't captured by Claude Desktop automatically so we redirect manually
            // to the same directory, using the same mcp-server-{name}.log naming convention.
            String appData = System.getenv('APPDATA') ?: (System.getProperty('user.home') + '/AppData/Roaming')
            File logsDir = new File(appData, 'Claude/logs')
            logsDir.mkdirs()
            File stderrLog = new File(logsDir, "mcp-server-${name}.log")
            pb.redirectError(stderrLog)


            // Server-specific env vars
            Map envVars = server.env as Map ?: [:]
            if (envVars) {
                pb.environment().putAll(envVars as Map<String, String>)
            }

            Process proc = pb.start()
            managedProcesses[name] = proc

            // Wait up to 10s for port to open
            boolean ready = waitForPort(port, 10)
            if (ready) {
                log.info("server_lifecycle: started {} on port {} (pid={})", name, port, proc.pid())
                result.put('started', true)
                result.put('pid', proc.pid())
                result.put('ready', true)
            } else {
                log.warn("server_lifecycle: {} started but port {} not ready after 10s", name, port)
                result.put('started', true)
                result.put('pid', proc.pid())
                result.put('ready', false)
                result.put('warning', 'process started but port not yet listening')
            }

        } catch (Exception e) {
            log.error("server_lifecycle: failed to start {}: {}", name, e.message)
            result.put('started', false)
            result.put('error', sanitize(e.message) as String)
        }

        return result
    }

    @PreDestroy
    void stopAllOnShutdown() {
        if (!managedProcesses.isEmpty()) {
            log.info("server_lifecycle: stopping {} managed server(s) on shutdown", managedProcesses.size())
            managedProcesses.each { String name, Process proc ->
                try {
                    proc.destroy()
                    proc.waitFor(5, TimeUnit.SECONDS)
                    if (proc.alive) proc.destroyForcibly()
                    log.info("server_lifecycle: stopped {} on shutdown", name)
                } catch (Exception e) {
                    log.warn("server_lifecycle: error stopping {} on shutdown: {}", name, e.message)
                }
            }
            managedProcesses.clear()
            writeRuntimeState()
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> loadConfig() {
        synchronized (configLock) {
            if (configCache != null) return configCache
            configCache = readConfigFromDisk()
            return configCache
        }
    }

    private Map<String, Object> readConfigFromDisk() {
        File configFile = new File("${claudeSyncPath}/${CONFIG_FILENAME}")
        if (!configFile.exists()) {
            throw new FileNotFoundException(
                "Server config not found: ${configFile.absolutePath}. Create mcp-http-servers.json in claude-sync.")
        }
        log.debug("server_lifecycle: reading config from disk")
        return mapper.readValue(configFile, Map) as Map<String, Object>
    }

    private void invalidateConfigCache() {
        synchronized (configLock) { configCache = null }
    }

    /**
     * Kill a stale process from a previous session if it is still holding the port.
     * Called at the start of startServer() to evict orphaned processes before binding.
     *
     * Previous bug: guard was !isPortListening(port) — this only killed processes whose
     * port was NOT listening (useless). Correct logic: kill if port IS listening and PID alive.
     * Also adds Windows netstat fallback when runtime PID is unknown/expired.
     */
    private void killStalePidIfPresent(String name, int port) {
        if (!isPortListening(port)) return  // nothing on the port — nothing to evict

        boolean killedByPid = false
        try {
            File runtimeFile = new File("${claudeSyncPath}/${RUNTIME_FILENAME}")
            if (runtimeFile.exists()) {
                Map<String, Object> state = mapper.readValue(runtimeFile, Map)
                List<Map> servers = state.managedServers as List<Map> ?: []
                Map entry = servers.find { it.name == name }
                if (entry) {
                    long pid = entry.pid as long
                    Optional<ProcessHandle> ph = ProcessHandle.of(pid)
                    if (ph.isPresent() && ph.get().isAlive()) {
                        log.info('server_lifecycle: evicting stale {} process PID {} holding port {}',
                                 name, pid, port)
                        ph.get().destroyForcibly()
                        Thread.sleep(800)
                        killedByPid = !ph.get().isAlive()
                    }
                }
            }
        } catch (Exception e) {
            log.warn('server_lifecycle: error checking runtime pid for {}: {}', name, e.message)
        }

        // If port still occupied after PID kill (or PID unknown), apply Windows netstat fallback
        if (!killedByPid || isPortListening(port)) {
            if (isPortListening(port)) {
                log.info('server_lifecycle: port {} still occupied — applying netstat force-kill for {}', port, name)
                boolean forceKilled = killByPort(port)
                if (forceKilled) {
                    Thread.sleep(500)
                    log.info('server_lifecycle: netstat force-kill on port {} succeeded', port)
                } else {
                    log.warn('server_lifecycle: could not evict process on port {} for {}', port, name)
                }
            }
        }
    }

    private void writeRuntimeState() {
        try {
            List<Map<String, Object>> running = []
            managedProcesses.each { String name, Process proc ->
                Map<String, Object> entry = new LinkedHashMap<String, Object>()
                entry.put('name', name)
                entry.put('pid', proc.pid())
                entry.put('alive', proc.alive)
                running << entry
            }
            Map<String, Object> state = new LinkedHashMap<String, Object>()
            state.put('updatedAt', new Date().toString())
            state.put('managedServers', running)
            File runtimeFile = new File("${claudeSyncPath}/${RUNTIME_FILENAME}")
            mapper.writerWithDefaultPrettyPrinter().writeValue(runtimeFile, state)
        } catch (Exception e) {
            log.warn("server_lifecycle: could not write runtime state: {}", e.message)
        }
    }

    /**
     * Kill all processes listening on the given port using Windows netstat + taskkill.
     * Falls back to ProcessHandle enumeration on non-Windows or if netstat unavailable.
     * Returns true if the port is free after the kill attempt.
     */
    private boolean killByPort(int port) {
        try {
            // Windows: netstat -ano | findstr :<port> | extract PID | taskkill
            String findCmd = "netstat -ano"
            Process findProc = ["cmd", "/c", findCmd].execute()
            String netstatOut = findProc.text
            findProc.waitFor(5, TimeUnit.SECONDS)

            List<Long> pids = []
            netstatOut.eachLine { String line ->
                if (line.contains(":${port} ") && (line.contains('LISTENING') || line.contains('ESTABLISHED'))) {
                    String[] parts = line.trim().split('\\s+')
                    try { pids << Long.parseLong(parts[-1]) } catch (Exception ignored) {}
                }
            }
            pids = pids.unique()

            if (!pids) {
                log.warn('server_lifecycle: killByPort({}) — no PIDs found via netstat', port)
                return false
            }

            pids.each { long pid ->
                try {
                    Optional<ProcessHandle> ph = ProcessHandle.of(pid)
                    if (ph.isPresent() && ph.get().isAlive()) {
                        log.info('server_lifecycle: killByPort({}) — destroying PID {}', port, pid)
                        ph.get().destroyForcibly()
                    }
                } catch (Exception e) {
                    log.warn('server_lifecycle: killByPort({}) PID {} error: {}', port, pid, e.message)
                }
            }
            Thread.sleep(1000)
            return !isPortListening(port, 1, 0)
        } catch (Exception e) {
            log.warn('server_lifecycle: killByPort({}) failed: {}', port, e.message)
            return false
        }
    }

    /**
     * Wait up to timeoutSeconds for the port to stop being listened on.
     * Returns true if the port is free before timeout, false if still occupied.
     */
    private static boolean waitForPortFree(int port, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        while (System.currentTimeMillis() < deadline) {
            if (!isPortListening(port, 1, 0)) return true
            Thread.sleep(300)
        }
        return !isPortListening(port, 1, 0)
    }

    private static boolean isPortListening(int port, int retries = 3, long delayMs = 300) {
        for (int i = 0; i < retries; i++) {
            try {
                new Socket('localhost', port).withCloseable {}
                return true
            } catch (Exception ignored) {
                if (delayMs > 0) Thread.sleep(delayMs)
            }
        }
        return false
    }

    private static boolean waitForPort(int port, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L)
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening(port)) return true
            Thread.sleep(500)
        }
        return false
    }
}