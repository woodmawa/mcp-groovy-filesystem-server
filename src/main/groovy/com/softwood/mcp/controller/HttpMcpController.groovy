package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import jakarta.servlet.http.HttpServletRequest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * HTTP transport endpoint for the filesystem server.
 *
 * Implements MCP spec 2025-03-26 Streamable HTTP transport:
 *   POST /mcp  — JSON-RPC dispatch. On 'initialize': issues Mcp-Session-Id header.
 *                Subsequent requests must include Mcp-Session-Id or receive 400.
 *   GET  /mcp  — Opens SSE stream on the unified endpoint for clients that want
 *                server-push. Claude Desktop/CC uses POST-only; GET provided for
 *                spec compliance and future streaming tool responses.
 *
 * Legacy SSE transport (GET /sse + POST /message) kept in McpSseController for
 * backward compat during transition. Do not remove until Desktop bug #31864 fixed
 * and Streamable HTTP confirmed working end-to-end.
 *
 * Protocol negotiation is CLIENT-DRIVEN: client POSTs InitializeRequest to /mcp;
 * success = Streamable HTTP; 4xx = fall back to legacy SSE. No server logic needed.
 *
 * Origin validation: null origin allowed (Claude Desktop/CC don't send one for
 * localhost servers). Non-localhost origins rejected with 403 to prevent DNS rebinding.
 *
 * Always registered when web context is active. STDIO safety guaranteed by
 * web-application-type=none in the stdio Spring profile.
 *
 * v0.8.5: Added Streamable HTTP transport — replaces SSE-only transport as primary
 * HTTP channel for Claude Desktop 1.2+ and Claude Code.
 */
@RestController
@Slf4j
@CompileStatic
class HttpMcpController {

    private static final long SSE_TIMEOUT_MS     = 30 * 60 * 1000L
    private static final long HEARTBEAT_INTERVAL = 30L

    private static final Set<String> TRUSTED_ORIGINS = [
        'http://localhost', 'https://localhost',
        'http://127.0.0.1', 'https://127.0.0.1',
        'http://[::1]',     'https://[::1]'
    ] as Set<String>

    @Autowired McpController mcpController

    /** All known Streamable HTTP session IDs (POST-only + SSE) */
    private final Set<String> knownSessions = Collections.newSetFromMap(new ConcurrentHashMap<>())
    /** Active SSE emitters for GET /mcp streaming clients: sessionId -> SseEmitter */
    private final Map<String, SseEmitter> sseEmitters = new ConcurrentHashMap<>()

    private final ScheduledExecutorService heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor { Runnable r ->
            Thread t = new Thread(r, 'mcp-streamable-heartbeat')
            t.daemon = true
            t
        }

    HttpMcpController() {
        heartbeatScheduler.scheduleAtFixedRate({
            List<String> dead = []
            sseEmitters.each { String sid, SseEmitter emitter ->
                try {
                    emitter.send(SseEmitter.event().comment('ping'))
                } catch (Exception ignored) {
                    dead << sid
                }
            }
            dead.each { sseEmitters.remove(it) }
        } as Runnable, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS)
    }

    // -------------------------------------------------------------------------
    // POST /mcp  — Streamable HTTP primary channel
    // -------------------------------------------------------------------------

    @PostMapping(value = '/mcp',
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<McpResponse> handleRequest(
            @RequestBody McpRequest request,
            @RequestHeader(value = 'Mcp-Session-Id', required = false) String sessionId,
            HttpServletRequest servletRequest) {

        ResponseEntity<McpResponse> originDenied = checkOrigin(servletRequest)
        if (originDenied) return originDenied

        boolean isInitialize = (request.method == 'initialize')

        if (isInitialize) {
            String newSessionId = UUID.randomUUID().toString()
            log.info('Streamable HTTP: initialize — issuing session {}', newSessionId)
            knownSessions.add(newSessionId)
            McpResponse response = mcpController.handleRequest(request)
            return ResponseEntity.ok()
                .header('Mcp-Session-Id', newSessionId)
                .body(response)
        }

        if (!sessionId) {
            log.debug('Streamable HTTP: missing Mcp-Session-Id on {} request', request.method)
            return ResponseEntity.badRequest()
                .body(McpResponse.error(request.id, -32600, 'Missing Mcp-Session-Id header'))
        }
        if (!knownSessions.contains(sessionId)) {
            log.debug('Streamable HTTP: unknown Mcp-Session-Id {} on {} request', sessionId, request.method)
            return ResponseEntity.badRequest()
                .body(McpResponse.error(request.id, -32600, 'Unknown Mcp-Session-Id'))
        }

        log.debug('Streamable HTTP POST: method={} session={}', request.method, sessionId)
        McpResponse response = mcpController.handleRequest(request)
        return ResponseEntity.ok()
            .header('Mcp-Session-Id', sessionId)
            .body(response)
    }

    // -------------------------------------------------------------------------
    // GET /mcp  — Streamable HTTP SSE channel (spec compliance + future streaming)
    // -------------------------------------------------------------------------

    @GetMapping(value = '/mcp', produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter openStream(
            @RequestHeader(value = 'Mcp-Session-Id', required = false) String sessionId,
            HttpServletRequest servletRequest) {

        String origin = servletRequest.getHeader('Origin')
        if (origin != null && !isTrustedOrigin(origin)) {
            log.warn('Streamable HTTP GET: rejected Origin={}', origin)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origin not permitted: ${origin}")
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS)

        if (sessionId && knownSessions.contains(sessionId)) {
            sseEmitters.put(sessionId, emitter)
            log.info('Streamable HTTP: GET stream attached to existing session {}', sessionId)
        } else {
            String newSessionId = UUID.randomUUID().toString()
            knownSessions.add(newSessionId)
            sseEmitters.put(newSessionId, emitter)
            log.info('Streamable HTTP: GET stream opened, new session {}', newSessionId)
            try {
                emitter.send(SseEmitter.event()
                    .name('session')
                    .data(newSessionId))
            } catch (Exception e) {
                log.error('Streamable HTTP: failed to send session event: {}', e.message)
                knownSessions.remove(newSessionId)
                sseEmitters.remove(newSessionId)
                emitter.completeWithError(e)
                return emitter
            }
        }

        emitter.onCompletion {
            sseEmitters.entrySet().removeIf { it.value.is(emitter) }
        }
        emitter.onTimeout {
            log.debug('Streamable HTTP: SSE stream timed out')
            sseEmitters.entrySet().removeIf { it.value.is(emitter) }
        }
        emitter.onError { Throwable e ->
            log.debug('Streamable HTTP: SSE stream error: {}', e.message)
            sseEmitters.entrySet().removeIf { it.value.is(emitter) }
        }

        return emitter
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<McpResponse> checkOrigin(HttpServletRequest request) {
        String origin = request.getHeader('Origin')
        if (origin != null && !isTrustedOrigin(origin)) {
            log.warn('Streamable HTTP POST: rejected Origin={}', origin)
            return ResponseEntity.status(403)
                .body(McpResponse.error(null, -32600, "Origin not permitted: ${origin}"))
        }
        return null
    }

    private boolean isTrustedOrigin(String origin) {
        String normalised = origin?.endsWith('/') ? origin[0..-2] : origin
        if (!normalised) return true
        return TRUSTED_ORIGINS.any { normalised.startsWith(it) }
    }
}
