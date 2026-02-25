package com.softwood.mcp.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

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

    // name -> Process (only processes WE started this session)
    private final Map<String, Process> managedProcesses = new ConcurrentHashMap<>()

    ServerLifecycleService(PathService pathService) {
        super(pathService)
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
                    action: [type: 'string', enum: ['start_eager', 'ensure', 'stop', 'status', 'reload'],
                             description: 'Lifecycle action'],
                    name  : [type: 'string',
                             description: 'Server name (filesystem|context|orchestrator|agentic-workflow). Required for ensure/stop a specific server.']
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
                case 'status'     : return doStatus(requestId)
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
            Process proc = managedProcesses.remove(name)
            if (proc) {
                proc.destroy()
                proc.waitFor(5, TimeUnit.SECONDS)
                if (proc.alive) proc.destroyForcibly()
                Map<String, Object> r = new LinkedHashMap<String, Object>()
                r.put('name', name); r.put('stopped', true)
                results << r
                log.info("server_lifecycle: stopped {}", name)
            } else {
                Map<String, Object> r = new LinkedHashMap<String, Object>()
                r.put('name', name); r.put('stopped', false); r.put('reason', 'not managed by this session')
                results << r
            }
        } else {
            // stop all managed
            managedProcesses.each { String n, Process proc ->
                proc.destroy()
                proc.waitFor(5, TimeUnit.SECONDS)
                if (proc.alive) proc.destroyForcibly()
                Map<String, Object> r = new LinkedHashMap<String, Object>()
                r.put('name', n); r.put('stopped', true)
                results << r
                log.info("server_lifecycle: stopped {}", n)
            }
            managedProcesses.clear()
        }

        writeRuntimeState()
        return textResponse(requestId, [action: 'stop', results: results])
    }

    private McpResponse doStatus(Object requestId) {
        Map<String, Object> config = loadConfig()
        List<Map> servers = config.servers as List<Map>
        List<Map<String, Object>> statuses = []

        servers.each { Map server ->
            String serverName = server.name as String
            int port = server.port as int
            boolean portOpen = isPortListening(port)
            boolean managed  = managedProcesses.containsKey(serverName)
            boolean alive    = managed && (managedProcesses[serverName]?.alive ?: false)

            Map<String, Object> status = new LinkedHashMap<String, Object>()
            status.put('name', serverName)
            status.put('port', port)
            status.put('jar', server.jar)
            status.put('startupPolicy', server.startupPolicy ?: 'eager')
            status.put('portListening', portOpen)
            status.put('managedBySession', managed)
            status.put('processAlive', alive)
            status.put('state', portOpen ? 'UP' : 'DOWN')
            statuses << status
        }

        return textResponse(requestId, [action: 'status', servers: statuses])
    }

    private McpResponse doReload(Object requestId) {
        // Just verify config is readable and return its contents
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
        File configFile = new File("${claudeSyncPath}/${CONFIG_FILENAME}")
        if (!configFile.exists()) {
            throw new FileNotFoundException(
                "Server config not found: ${configFile.absolutePath}. Create mcp-http-servers.json in claude-sync.")
        }
        return mapper.readValue(configFile, Map) as Map<String, Object>
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

    private static boolean isPortListening(int port) {
        try {
            new Socket('localhost', port).withCloseable { return true }
        } catch (Exception ignored) {
            return false
        }
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
