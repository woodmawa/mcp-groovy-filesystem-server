package com.softwood.mcp.service

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap

/**
 * ServerRegistry — centralised port/process authority for HTTP MCP companion servers.
 *
 * Single source of truth for whether a server on a given port is live, and whether
 * THIS session owns the process. Prevents server_lifecycle ensure from killing a
 * live server just because managedProcesses doesn't have it (cross-session scenario).
 *
 * Key operations:
 *   checkPort(port)         — TCP socket check only (fast, ~1ms)
 *   pingMcp(port)           — HTTP MCP initialize handshake (definitive liveness)
 *   adopt(name, port)       — register a pre-existing process we did NOT start
 *   register(name, proc)    — register a process WE started
 *   unregister(name)        — remove from registry (after stop)
 *   sessionId(port)         — retrieve cached MCP session ID for port
 *   putSessionId(port, sid) — cache MCP session ID for port
 *   clearSessionId(port)    — evict cached session ID (force re-handshake)
 *   isOwned(name)           — true if WE started this process this session
 *
 * Decision logic for ensure:
 *   1. checkPort → not listening → start fresh
 *   2. checkPort → listening + isOwned → already running, skip
 *   3. checkPort → listening + NOT owned → pingMcp
 *        a. pingMcp OK  → adopt (cache sessionId), skip start
 *        b. pingMcp fail → we don't own it and it's not responding; kill by port, then start
 */
@Component
@Slf4j
@CompileStatic
class ServerRegistry {

    /** name → Process (only processes WE started this session) */
    private final ConcurrentHashMap<String, Process> ownedProcesses = new ConcurrentHashMap<>()

    /** name → port (for reverse lookup) */
    private final ConcurrentHashMap<String, Integer> nameToPort = new ConcurrentHashMap<>()

    /** port → MCP session ID (cached after first initialize handshake) */
    private final ConcurrentHashMap<Integer, String> sessionCache = new ConcurrentHashMap<>()

    /** port → name (adopted servers we did NOT start but verified as healthy) */
    private final ConcurrentHashMap<Integer, String> adoptedPorts = new ConcurrentHashMap<>()

    // ─── TCP port check ─────────────────────────────────────────────────────

    /**
     * Fast TCP socket check. Returns true if something is listening on port.
     * Does NOT verify the listener is a healthy MCP server.
     */
    boolean checkPort(int port) {
        try {
            new Socket('localhost', port).withCloseable { true }
        } catch (IOException ignored) {
            false
        }
    }

    // ─── MCP HTTP liveness check ─────────────────────────────────────────────

    /**
     * Send MCP initialize to the server on port. Returns the session ID if the
     * server responds correctly, or null if the server is unreachable/unhealthy.
     *
     * Side-effect: caches the session ID in sessionCache on success.
     */
    String pingMcp(int port) {
        try {
            String body = JsonOutput.toJson([
                jsonrpc: '2.0', id: 0, method: 'initialize',
                params : [protocolVersion: '2025-03-26', capabilities: [:],
                          clientInfo: [name: 'ServerRegistry', version: '1.0']]
            ])
            HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:${port}/mcp").openConnection()
            conn.requestMethod = 'POST'
            conn.setRequestProperty('Content-Type', 'application/json')
            conn.connectTimeout = 3000
            conn.readTimeout    = 5000
            conn.doOutput       = true
            conn.outputStream.withWriter('UTF-8') { it.write(body) }

            String sessionId = conn.getHeaderField('Mcp-Session-Id')
            int code = conn.responseCode
            try { conn.inputStream.text } catch (ignored) {}  // drain

            if (code == 200 && sessionId) {
                sessionCache.put(port, sessionId)
                log.info('ServerRegistry: pingMcp port {} OK — sessionId={}', port, sessionId)
                return sessionId
            } else {
                log.warn('ServerRegistry: pingMcp port {} returned HTTP {} sessionId={}', port, code, sessionId)
                return null
            }
        } catch (Exception e) {
            log.warn('ServerRegistry: pingMcp port {} failed: {}', port, e.message)
            return null
        }
    }

    // ─── Registration ─────────────────────────────────────────────────────────

    /**
     * Register a process WE started. Marks it as owned by this session.
     */
    void register(String name, int port, Process proc) {
        ownedProcesses.put(name, proc)
        nameToPort.put(name, port)
        log.debug('ServerRegistry: registered owned process {} on port {} pid={}', name, port, proc.pid())
    }

    /**
     * Adopt a server we did NOT start — it was already running when we checked.
     * Calling this prevents ensure from killing it.
     */
    void adopt(String name, int port) {
        adoptedPorts.put(port, name)
        nameToPort.put(name, port)
        log.info('ServerRegistry: adopted pre-existing server {} on port {}', name, port)
    }

    /**
     * Remove all registry entries for a server (call after stop).
     */
    void unregister(String name) {
        Integer port = nameToPort.remove(name)
        ownedProcesses.remove(name)
        if (port != null) {
            adoptedPorts.remove(port)
            sessionCache.remove(port)
        }
        log.debug('ServerRegistry: unregistered {}', name)
    }

    // ─── Session ID cache ──────────────────────────────────────────────────────

    String sessionId(int port) {
        sessionCache.get(port)
    }

    void putSessionId(int port, String sid) {
        sessionCache.put(port, sid)
    }

    void clearSessionId(int port) {
        sessionCache.remove(port)
        log.debug('ServerRegistry: cleared sessionId for port {}', port)
    }

    // ─── Ownership queries ────────────────────────────────────────────────────

    /** True if WE started this process this session AND the process handle is still alive. */
    boolean isOwned(String name) {
        Process proc = ownedProcesses.get(name)
        proc != null && proc.alive
    }

    /** True if we adopted this port (pre-existing healthy server). */
    boolean isAdopted(int port) {
        adoptedPorts.containsKey(port)
    }

    /**
     * True if the server is known to this registry (owned OR adopted).
     * Use this to decide whether to skip killStalePid logic.
     */
    boolean isKnown(String name, int port) {
        isOwned(name) || isAdopted(port)
    }

    /** Expose owned processes map for writeRuntimeState serialisation. */
    Map<String, Process> getOwnedProcesses() {
        return Collections.unmodifiableMap(ownedProcesses)
    }

    /** Expose all registered ports + names for status reporting. */
    Map<Integer, String> getPortMap() {
        Map<Integer, String> result = new LinkedHashMap<>()
        nameToPort.each { String name, int port -> result.put(port, name) }
        return result
    }

    /**
     * Clear only owned processes (call after @PreDestroy kill loop).
     * Deliberately leaves adoptedPorts and sessionCache intact so status
     * reporting still works during the shutdown log flush.
     */
    void clearOwned() {
        ownedProcesses.clear()
        log.debug('ServerRegistry: owned processes cleared')
    }

    /**
     * Destroy all owned processes AND clear all state. Full reset.
     * Only called if you want a complete registry wipe (e.g. reload).
     */
    void destroyAll() {
        ownedProcesses.each { String name, Process proc ->
            try {
                if (proc.alive) {
                    log.info('ServerRegistry: destroying owned process {} pid={}', name, proc.pid())
                    proc.destroyForcibly()
                }
            } catch (Exception e) {
                log.warn('ServerRegistry: error destroying {}: {}', name, e.message)
            }
        }
        ownedProcesses.clear()
        nameToPort.clear()
        adoptedPorts.clear()
        sessionCache.clear()
    }
}
