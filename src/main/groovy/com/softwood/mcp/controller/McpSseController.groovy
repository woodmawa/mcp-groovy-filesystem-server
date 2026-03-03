package com.softwood.mcp.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import jakarta.servlet.http.HttpServletRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * MCP HTTP/SSE transport — required for Claude Desktop 1.1.x HTTP mode.
 *
 * Implements the MCP HTTP+SSE transport spec:
 *   GET  /sse          Client subscribes. Server sends an 'endpoint' event with the POST URL.
 *   POST /message      Client sends JSON-RPC. Server dispatches and pushes response via SSE.
 *
 * Only active when web server is running (http profile). In stdio profile
 * web-application-type=none prevents Tomcat from starting so this is never reachable.
 *
 * Session lifecycle: emitter is kept alive with heartbeat pings every 30s.
 * Emitter removed from map on completion, timeout, or error.
 */
@RestController
@Slf4j
@CompileStatic
class McpSseController {

    private static final long SSE_TIMEOUT_MS     = 30 * 60 * 1000L  // 30 min
    private static final long HEARTBEAT_INTERVAL = 30L               // seconds

    @Autowired
    McpController mcpController

    @Autowired
    ObjectMapper objectMapper

    // sessionId → active emitter
    private final Map<String, SseEmitter> sessions = new ConcurrentHashMap<>()

    private final ScheduledExecutorService heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor { Runnable r ->
            Thread t = new Thread(r, 'mcp-sse-heartbeat')
            t.daemon = true
            t
        }

    McpSseController() {
        // Start heartbeat: sends a comment ping to all active emitters every 30s.
        // Prevents proxies/load-balancers from closing idle SSE connections.
        heartbeatScheduler.scheduleAtFixedRate({
            List<String> dead = []
            sessions.each { String sid, SseEmitter emitter ->
                try {
                    emitter.send(SseEmitter.event().comment('ping'))
                } catch (Exception ignored) {
                    dead << sid
                }
            }
            dead.each { sessions.remove(it) }
        } as Runnable, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS)
    }

    /**
     * SSE subscription endpoint.
     * Claude Desktop connects here first; we send back the endpoint event so it knows
     * where to POST messages.
     */
    @GetMapping(value = '/sse', produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter subscribe(HttpServletRequest request) {
        String sessionId = UUID.randomUUID().toString()
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS)

        sessions[sessionId] = emitter

        emitter.onCompletion { sessions.remove(sessionId) }
        emitter.onTimeout    { sessions.remove(sessionId); log.debug('SSE session timed out: {}', sessionId) }
        emitter.onError      { Throwable e -> sessions.remove(sessionId); log.debug('SSE session error {}: {}', sessionId, e.message) }

        // Build the message POST URL dynamically from the incoming request so it works
        // regardless of port or reverse-proxy prefix.
        String scheme = request.scheme
        String host   = request.serverName
        int    port   = request.serverPort
        String postUrl = "${scheme}://${host}:${port}/message?sessionId=${sessionId}" as String

        // MCP spec: first event must be 'endpoint' with the POST URL as data
        try {
            emitter.send(SseEmitter.event()
                .name('endpoint')
                .data(postUrl))
            log.info('SSE session started: {} → {}', sessionId, postUrl)
        } catch (Exception e) {
            log.error('Failed to send endpoint event for session {}: {}', sessionId, e.message)
            sessions.remove(sessionId)
            emitter.completeWithError(e)
        }

        return emitter
    }

    /**
     * Message POST endpoint.
     * Claude Desktop sends JSON-RPC here; we dispatch and push the response back via SSE.
     */
    @PostMapping('/message')
    ResponseEntity<Void> message(
            @RequestParam String sessionId,
            @RequestBody McpRequest request) {

        SseEmitter emitter = sessions[sessionId]
        if (!emitter) {
            log.warn('POST /message: unknown sessionId {}', sessionId)
            return ResponseEntity.badRequest().build()
        }

        // Dispatch JSON-RPC (reuse same McpController logic as POST /)
        McpResponse response = mcpController.handleRequest(request)

        // Notifications have no id and return null — nothing to send back
        if (response == null) {
            return ResponseEntity.ok().build()
        }

        try {
            String json = objectMapper.writeValueAsString(response)
            emitter.send(SseEmitter.event()
                .name('message')
                .data(json))
        } catch (Exception e) {
            log.error('Failed to send SSE response for session {}: {}', sessionId, e.message)
            sessions.remove(sessionId)
            emitter.completeWithError(e)
            return ResponseEntity.status(500).build()
        }

        return ResponseEntity.ok().build()
    }
}
