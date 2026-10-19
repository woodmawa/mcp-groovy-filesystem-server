package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FilesystemTelemetryService
import com.softwood.mcp.service.ToolHandler
import com.softwood.mcp.service.read.RangeTelemetry
import com.softwood.mcp.support.Sanitizer
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * McpController — thin dispatcher for all MCP JSON-RPC requests.
 *
 * Auto-discovers all ToolHandler beans via Spring injection.
 * No business logic here — all tool behaviour lives in the service layer.
 *
 * v0.0.7: 7 consolidated tools, no GroovyScriptService dependency.
 */
@RestController
@Slf4j
@CompileStatic
class McpController {

    static final String SERVER_VERSION = com.softwood.mcp.McpGroovyFileSystemServerApplication.package?.implementationVersion ?: 'dev'

    // Supported MCP protocol versions (newest first).
    // 2025-11-25: added in Claude Desktop post-Nov-2025 reinstall - includes ui extension capability.
    // 2025-06-18: previous stable version.
    // 2024-11-05: legacy fallback.
    static final List<String> SUPPORTED_PROTOCOL_VERSIONS = [
        '2025-11-25',
        '2025-06-18',
        '2024-11-05'
    ].asImmutable()
    private final List<ToolHandler> toolHandlers
    private final Map<String, ToolHandler> handlerMap = new LinkedHashMap<String, ToolHandler>()
    private List<Map<String, Object>> cachedToolDefinitions = []
    /** WP1 (FS 0.9.106): each tool's SOURCE definition, by name -- what ArgsSchemaGuard judges a call against. */
    private final Map<String, Map<String, Object>> toolDefsByName = new LinkedHashMap<String, Map<String, Object>>()
    /** WP1 test seam: null = refuse unread keys on the chat's stdio process only; true/false forces it. */
    Boolean refuseUnreadArgs

    /** WP2 C1 test seam: null = refuse mutating calls while unclaimed on the chat's stdio process only. */
    Boolean requireClaimForWrites

    /** WP2 C1: the tools whose calls change something. execute's job_* actions only read a job, so they are not. */
    static final Set<String> MUTATING_TOOLS =
        (['file_write', 'file_lifecycle', 'execute', 'tools', 'worktree'] as Set<String>).asImmutable()

    /**
     * WP2 C1 (decision 384, Will 2026-10-19): an unclaimed chat process refuses a mutating call before dispatch. It used
     * to run it and append unbound_warning -- the edit or build happened, filed as unattributed, with PLAN-GATE keyed to
     * no session. Reads still run (with the restart notice). The claim check is the in-memory one telemetry already
     * makes on this path once claimed, so no new I/O (#260). ClaimBeforeWriteSpec. Null to proceed.
     */
    String notClaimedRefusal(String toolName, Map<String, Object> arguments) {
        boolean chat = requireClaimForWrites != null ? requireClaimForWrites.booleanValue() : isStdioProcess()
        if (!chat || !MUTATING_TOOLS.contains(toolName) || telemetryService == null) return null
        if (toolName == 'execute' && (arguments?.get('action') as String)?.startsWith('job_')) return null
        if (telemetryService.readActiveSessionId()) return null
        return ('NOT_CLAIMED: this FS process holds no session claim, so this ' + toolName + ' call would run ' +
                'unattributed and outside PLAN-GATE. Nothing ran. Claim on THIS connection first: ' +
                'server_lifecycle action=claim_session sessionId=<id>').toString()
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    FilesystemTelemetryService telemetryService

    /** FS 0.9.100 optimised-learning WS2: a lesson rides on the call it belongs to. Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.softwood.mcp.service.FsTriggerCache triggerCache

    /** FS 0.9.80 SB-1: asked once, while unbound, whom this restarted process replaced. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.softwood.mcp.service.ContextServerClient contextServerClient

    /** FS 0.9.80 SB-1: CS's answer, kept once it has one -- an unreachable CS is asked again next time. */
    private final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> predecessorRef =
        new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>()

    /** FS 0.9.80 SB-1: only a chat's own stdio process is anybody's successor; the shared companion never is. */
    static boolean isStdioProcess() {
        return (System.getProperty('spring.profiles.active') ?: '').split(',')*.trim().contains('stdio')
    }

    /**
     * FS 0.9.80 SB-1 (chain d45c51cf) -- the restart notice for this process, or null. Asked of CS on the first
     * unbound call; the answer (even "no predecessor") is kept, a failed ask is not. Never adopts anything (D1).
     */
    String restartNotice() {
        if (!isStdioProcess() || contextServerClient == null) return null
        Map<String, Object> known = predecessorRef.get()
        if (known == null) {
            Map<String, Object> asked = contextServerClient.claimPredecessor(
                com.softwood.mcp.ProcessIdentity.SERVER, com.softwood.mcp.ProcessIdentity.PID,
                com.softwood.mcp.ProcessIdentity.JVM_STARTED_AT)
            if (asked == null) return null
            predecessorRef.compareAndSet(null, asked)
            known = predecessorRef.get()
        }
        return known?.get('notice') as String
    }

    /** Align kit 2026-09-25: the live source of every top-level tool description (CS help_sections). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.softwood.mcp.service.ToolDescriptionRegistry toolDescriptionRegistry

    void setToolDescriptionRegistry(com.softwood.mcp.service.ToolDescriptionRegistry r) { this.toolDescriptionRegistry = r }

    /**
     * Hands the registry every tool's SOURCE description as its fallback DEFAULT. Runs after
     * injection; specs call it directly. Handler text registered here is what a CS-less start serves.
     */
    @jakarta.annotation.PostConstruct
    void registerDescriptionDefaults() {
        if (toolDescriptionRegistry == null) return
        for (Map<String, Object> toolDef : cachedToolDefinitions) {
            toolDescriptionRegistry.registerDefault(toolDef.get('name') as String, toolDef.get('description') as String)
        }
    }

    // FIX-C: v0.7.43 global backstop - hard ceiling on any single tool response
    @org.springframework.beans.factory.annotation.Value('${mcp.filesystem.global-response-cap-chars:64000}')
    int globalResponseCapChars

    /**
     * FS 0.9.27 W11.1 -- one WARN per unbound streak, not one per call.
     * The RESPONSE warning fires on every call while unbound, deliberately; the LOG line does not,
     * because a flow-node process can make thousands of calls it was never meant to claim for.
     * Reset the moment a claim is seen, so a claim lost twice is logged twice.
     */
    private final java.util.concurrent.atomic.AtomicBoolean unboundWarned =
        new java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * FS 0.9.27 W11.1 -- the same three keys CS attaches in
     * {@code ContextWriteActionRouter.markIfUnbound}, with FS wording and the FS claim call.
     *
     * <p>Kept as its own method so specs can derive the expected key set from it rather than
     * transcribe one (CT-UBW-4). A key added here then fails the spec instead of vanishing.</p>
     */
    static Map<String, Object> unboundWarningMap(String ownerKey) {
        return unboundWarningMap(ownerKey, null)
    }

    /** FS 0.9.80 SB-1: plus {@code restart_notice} when this process replaced one that held a session. */
    static Map<String, Object> unboundWarningMap(String ownerKey, String restartNotice) {
        Map<String, Object> out = new LinkedHashMap<String, Object>()
        if (restartNotice) out.put('restart_notice', restartNotice)
        out.put('unbound', true)
        out.put('unbound_warning',
            'This FS process holds no session claim, so this call is filed as unattributed ' +
            'telemetry and does not count toward read_count or ontology_pct. Claim on THIS ' +
            'connection to bind it: server_lifecycle action=claim_session sessionId=<id>')
        out.put('owner_key', ownerKey)
        return out
    }

    /**
     * Returns a COPY of {@code response} with the unbound warning appended as a second content
     * element. The original is never mutated.
     *
     * <p>Three deliberate properties, each of which the obvious version gets wrong:</p>
     * <ol>
     *   <li>The handler's own payload -- a JSON string in {@code content[0].text} -- is not parsed,
     *       not re-serialised and not touched. Injecting a key into it at the dispatch boundary
     *       would mean round-tripping every handler's output through a parser to add a warning.</li>
     *   <li>The warning is added OUTSIDE the handler, so no response trim can eat it. CS computed
     *       its unbound warning correctly in 1.0.25 and never delivered it, because {@code KEEP_KEYS}
     *       dropped it -- the sixth time that list ate the evidence a fix existed to produce.</li>
     *   <li>It does not mutate, so a caller that measured the response BEFORE calling this still
     *       holds a true measurement. That is what keeps the warning out of
     *       {@code tool_call_telemetry.response_char_count} and out of the backstop.</li>
     * </ol>
     */
    static McpResponse withUnboundWarning(McpResponse response, String ownerKey) {
        return withUnboundWarning(response, ownerKey, null)
    }

    static McpResponse withUnboundWarning(McpResponse response, String ownerKey, String restartNotice) {
        if (response == null) return null
        Map<String, Object> result = response.result
        if (result == null) return response
        Object rawContent = result.get('content')
        if (!(rawContent instanceof List)) return response

        List<Object> content = new ArrayList<Object>((List<Object>) rawContent)
        Map<String, Object> note = new LinkedHashMap<String, Object>()
        note.put('type', 'text')
        note.put('text', JsonOutput.toJson(unboundWarningMap(ownerKey, restartNotice)))
        content.add(note)

        Map<String, Object> copied = new LinkedHashMap<String, Object>(result)
        copied.put('content', content)

        McpResponse out = new McpResponse()
        out.id = response.id
        out.result = copied
        out.error = response.error
        return out
    }


    McpController(List<ToolHandler> toolHandlers) {
        this.toolHandlers = toolHandlers
        buildHandlerMap()
    }

    /**
     * WP1: advertise the UNREAD_ARG escape on every tool that declares its properties, so a caller can send it.
     * A copy: the handler's own definition is left as it is.
     */
    private static Map<String, Object> withAllowUnread(Map<String, Object> toolDef) {
        Object schema = toolDef.get('inputSchema')
        if (!(schema instanceof Map)) return toolDef
        Object props = ((Map) schema).get('properties')
        if (!(props instanceof Map) || ((Map) props).isEmpty()) return toolDef
        Map<String, Object> p = new LinkedHashMap<String, Object>((Map<String, Object>) props)
        p.put(com.softwood.mcp.service.ArgsSchemaGuard.ALLOW_UNREAD, [type: 'boolean', description:
            'Pass true only when an UNREAD_ARG refusal named a key this tool genuinely reads on a branch its schema does not describe. A key the tool does not read is refused before anything runs, so a value sent under the wrong name is never silently dropped.'] as Map<String, Object>)
        Map<String, Object> s = new LinkedHashMap<String, Object>((Map<String, Object>) schema)
        s.put('properties', p)
        Map<String, Object> d = new LinkedHashMap<String, Object>(toolDef)
        d.put('inputSchema', s)
        return d
    }

    private void buildHandlerMap() {
        List<Map<String, Object>> allDefs = []
        toolHandlers.each { ToolHandler handler ->
            handler.getToolDefinitions().each { Map<String, Object> toolDef ->
                String name = toolDef.name as String
                if (handlerMap.containsKey(name)) {
                    log.warn("Duplicate tool name '{}'  overwriting with {}", name, handler.class.simpleName)
                }
                handlerMap[name] = handler
                toolDefsByName.put(name, toolDef)
                allDefs << withAllowUnread(toolDef)
                log.debug("Registered tool: {}  {}", name, handler.class.simpleName)
            }
        }
        cachedToolDefinitions = allDefs.asImmutable()
        log.info("v{} ready  {} tools registered from {} handlers: {}",
            SERVER_VERSION, handlerMap.size(), toolHandlers.size(), handlerMap.keySet().join(', '))
    }
    /**
     * FS 0.9.58 -- the session a caller DECLARES for this request (header {@link #CALLER_SESSION_HEADER}).
     *
     * <p>AW stamps it on every outbound mcp.tool_call (DeclaredSession.applyTo, AW 1.30.22) and CS
     * has read it since 1.0.59. FS never did, so every flow call to FS's unclaimed HTTP companion
     * filed as 'unknown': 55 of 61 such rows in two days were gradle-test-summary's 'tools' calls,
     * which is why fs-telemetry-not-stranded-in-unknown rose with flow use, not with restarts.</p>
     *
     * <p>Used for TELEMETRY ATTRIBUTION ONLY. PLAN-GATE and the read ledger keep reading this
     * process's own claim, so a flow node's call is not newly gated because it declared a session.
     * Set per request and removed in finally -- a pooled HTTP thread must never carry it forward.</p>
     */
    static final String CALLER_SESSION_HEADER = 'X-Mcp-Caller-Session'
    private static final ThreadLocal<String> CALLER_SESSION = new ThreadLocal<String>()

    /** HTTP entry. The one-argument form below is kept for direct (stdio) callers. */
    @PostMapping('/')
    McpResponse handleRequest(@RequestBody McpRequest request,
                              @RequestHeader(value = 'X-Mcp-Caller-Session', required = false) String callerSession,
                              @RequestHeader(value = 'X-Aw-Run', required = false) String awRun,
                              @RequestHeader(value = 'X-Aw-Node', required = false) String awNode) {
        String declared = callerSession?.trim()
        CALLER_SESSION.set(declared ?: null)
        // FS 0.9.95: the AW run/node this call is for, so a long gradle call can post live progress to it.
        // A ThreadLocal only -- no I/O on the hot path (practice 260).
        // FS 0.9.97 (G2): and the declared session, so a job this call submits is filed under its caller.
        com.softwood.mcp.service.AwCallContext.set(awRun, awNode, declared)
        try {
            return handleRequest(request)
        } finally {
            CALLER_SESSION.remove()
            com.softwood.mcp.service.AwCallContext.clear()
        }
    }

    /** Caller session only (kept for callers and specs that predate the AW run/node headers). */
    McpResponse handleRequest(McpRequest request, String callerSession) {
        return handleRequest(request, callerSession, null, null)
    }

    McpResponse handleRequest(McpRequest request) {
        try {
            return dispatch(request)
        } catch (Exception e) {
            log.error("Unhandled error in handleRequest", e)
            return McpResponse.protocolError(request?.id, -32603,
                Sanitizer.sanitize("Internal error: ${e.message}") as String)
        }
    }

    private McpResponse dispatch(McpRequest request) {
        // Notifications (no id) — acknowledge silently
        if (request.id == null) {
            log.debug("Notification received: {}", request.method)
            return null
        }

        switch (request.method) {
            case 'initialize'  : return handleInitialize(request)
            case 'tools/list'  : return handleToolsList(request)
            case 'tools/call'  : return handleToolsCall(request)
            case 'ping'        : return McpResponse.success(request.id, [:] as Map<String, Object>)
            default:
                log.warn("Unknown MCP method: {}", request.method)
                return McpResponse.protocolError(request.id, -32601, "Unknown method: ${request.method}" as String)
        }
    }

    // -----------------------------------------------------------------------
    // MCP protocol handlers
    // -----------------------------------------------------------------------

    private McpResponse handleInitialize(McpRequest request) {
        String clientVersion = request.params?.protocolVersion as String
        String negotiated = negotiateProtocolVersion(clientVersion)

        // Log client identity if provided (Claude Desktop sends clientInfo from 2025-11-25 onwards)
        def clientInfo = request.params?.clientInfo
        if (clientInfo instanceof Map) {
            log.info("MCP Initialize: client='{}' v='{}', requested='{}', negotiated='{}'",
                clientInfo.name, clientInfo.version, clientVersion, negotiated)
        } else {
            log.info("MCP Initialize: client requested '{}', negotiated '{}'", clientVersion, negotiated)
        }

        return McpResponse.success(request.id, [
            protocolVersion: negotiated,
            // Align kit 2026-09-25: descriptions are served live from CS, so the list CAN change
            // without a restart. stdio announces it with notifications/tools/list_changed.
            capabilities   : [tools: [listChanged: true] as Map<String, Object>] as Map<String, Object>,
            serverInfo     : [name: 'mcp-groovy-filesystem-server', version: SERVER_VERSION] as Map<String, Object>
        ] as Map<String, Object>)
    }

    private String negotiateProtocolVersion(String clientVersion) {
        if (clientVersion && SUPPORTED_PROTOCOL_VERSIONS.contains(clientVersion)) {
            return clientVersion
        }
        return SUPPORTED_PROTOCOL_VERSIONS.first()
    }

    private McpResponse handleToolsList(McpRequest request) {
        // Definitions are built once at startup; only the top-level description is live (CS
        // help_sections tool_desc_<tool>, TTL-cached, last-good then source DEFAULT on failure).
        List<Map<String, Object>> tools = cachedToolDefinitions
        if (toolDescriptionRegistry != null) {
            try {
                tools = toolDescriptionRegistry.applyTo(cachedToolDefinitions)
            } catch (Exception e) {
                log.warn('tools/list: live descriptions unavailable, serving source text: {}', e.message)
                tools = cachedToolDefinitions
            }
        }
        log.debug("tools/list returning {} tools", tools.size())
        return McpResponse.success(request.id, [tools: tools] as Map<String, Object>)
    }

    private McpResponse handleToolsCall(McpRequest request) {
        String toolName = request.params?.name as String
        if (!toolName) {
            return McpResponse.toolError(request.id, "tools/call missing required param: name")
        }

        Map<String, Object> arguments = (request.params?.arguments as Map<String, Object>) ?: [:] as Map<String, Object>

        ToolHandler handler = handlerMap[toolName]
        if (!handler) {
            log.warn("Unknown tool called: {}", toolName)
            return McpResponse.toolError(request.id,
                "Unknown tool: '${toolName}'. Available: ${handlerMap.keySet().join(', ')}" as String)
        }

        log.debug('Dispatching tool: {}', toolName)
        // WP1 (FS 0.9.106, decisions 382/383): a key the tool does not read is refused BEFORE dispatch -- nothing runs --
        // instead of being dropped while the call answers a different question. Chat stdio process only.
        boolean refuseUnread = refuseUnreadArgs != null ? refuseUnreadArgs.booleanValue() : isStdioProcess()
        String unreadRefusal = notClaimedRefusal(toolName, arguments)
        if (unreadRefusal == null && refuseUnread) {
            unreadRefusal = com.softwood.mcp.service.ArgsSchemaGuard.check(toolDefsByName.get(toolName), arguments)
        }
        McpResponse response = unreadRefusal ? McpResponse.toolError(request.id, unreadRefusal)
                                              : handler.handleToolCall(toolName, arguments, request.id)

        // FS 0.9.77: measured for the global cap whether or not telemetry is wired. It was computed only inside the
        // telemetry branch, so with no telemetry bean (absent whenever -Dmcp.usage.db-path is missing) the cap compared
        // against 0 and never fired.
        int charCount = 0
        try { charCount = estimateResponseSize(response) } catch (Exception e) { log.debug('response size estimate failed: {}', e.message) }

        // v0.7.19: telemetry - fire-and-forget, never blocks response
        boolean unbound = false
        String callSession = CALLER_SESSION.get()
        try {
            if (telemetryService != null) {
                String action   = arguments.action as String
                String rawPath  = arguments.path as String
                String pathHash = rawPath ? sha256Prefix(rawPath) : null
                String outcome  = extractOutcome(response)
                // D5 fix (v0.8.65): resolve real session ID via JDBC (transport-agnostic).
                // Prior 'unknown' hardcode caused knownhash_pct/read_count to read 0 for FS calls.
                // FS 0.9.27 W11.1: null here is the ONLY signal that this call is about to be filed
                // as unattributed. It was being silently coalesced to 'unknown' and thrown away.
                // FS 0.9.58: a session the caller declared wins; then this process's own claim.
                String declared  = CALLER_SESSION.get()
                String resolved  = declared ?: telemetryService.readActiveSessionId()
                unbound = (resolved == null)
                callSession = resolved
                // FS 0.9.80 SB-2: unbound still warns (above), but the row names who made it rather than 'unknown'.
                String sessionId = resolved ?: 'system:fs-unclaimed'
                // FS 0.9.67 item 3: requested window + lines returned, for range calls only.
                Map<String, Object> detail = RangeTelemetry.detail(toolName, arguments, responseText(response))
                // optimised-learning B3: the refusal code rides to CS as telemetry.error_code (outcome unchanged).
                String errorCode = errorCodeOf(response)
                if (errorCode) {
                    detail = detail ? new LinkedHashMap<String, Object>(detail) : new LinkedHashMap<String, Object>()
                    detail.put('error_code', (Object) errorCode)
                }
                telemetryService.recordToolCall(sessionId, toolName, charCount, arguments,
                    action, pathHash, outcome, detail)
            }
        } catch (Exception e) {
            log.debug('Telemetry hook failed (non-fatal): {}', e.message)
        }

        if (unbound) {
            if (unboundWarned.compareAndSet(false, true)) {
                log.warn('FS holds no session claim -- calls are filing as unattributed telemetry ' +
                         '(owner_key={}). Issue server_lifecycle action=claim_session.',
                         com.softwood.mcp.ProcessIdentity.OWNER_KEY)
            }
        } else {
            unboundWarned.set(false)
        }

        // FIX-C: v0.7.43 global response backstop - no response may exceed cap regardless of handler
        McpResponse out = response
        if (globalResponseCapChars > 0 && charCount > globalResponseCapChars) {
            int tokenEst = Math.round(charCount / 4.0f) as int
            log.warn('BACKSTOP triggered: {} response {}chars (~{}tok) exceeds global cap {}chars',
                toolName, charCount, tokenEst, globalResponseCapChars)
            out = McpResponse.toolError(request.id,
                "Response too large: ${toolName} produced ${charCount} chars (~${tokenEst} tokens). " +
                "Use targeted actions: structure/get_method/range/grep instead of full reads.")
        }

        // FS 0.9.100 WS2: after telemetry and the backstop, so the lesson never inflates the measured size. Never throws.
        if (triggerCache != null && callSession) out = triggerCache.offer(toolName, arguments, out, callSession)

        // FS 0.9.27 W11.1: applied to whatever is actually being returned, backstop error included.
        // Being unbound is orthogonal to the call having failed, and a session whose response was
        // just refused is precisely the one that needs telling why its telemetry will not count.
        if (!unbound) return out
        String notice = null
        try { notice = restartNotice() } catch (Exception e) { log.debug('restart notice failed (non-fatal): {}', e.message) }
        return withUnboundWarning(out, com.softwood.mcp.ProcessIdentity.OWNER_KEY, notice)
    }

    // FIX-7: zero-copy size estimate - read text field directly instead of serialising whole result Map
    private static String sha256Prefix(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance('SHA-256').digest(input.getBytes('UTF-8'))
            StringBuilder sb = new StringBuilder(12)
            for (int i = 0; i < 6; i++) sb.append(String.format('%02x', hash[i]))
            return sb.toString()
        } catch (Exception ignored) { return null }
    }

    /**
     * Derive a terminal outcome string from the tool handler response for
     * {@code tool_call_telemetry.outcome} (BUILD-16B / liveness contract).
     * <p>Values returned:
     * <ul>
     *   <li>{@code "refused"}   — error message contains "refus".</li>
     *   <li>{@code "error"}     — protocol-level McpError present.</li>
     *   <li>{@code "unchanged"} — response text contains {@code "unchanged":true}.</li>
     *   <li>{@code "truncated"} — response text contains {@code _truncated}.</li>
     *   <li>{@code "success"}   — all other cases.</li>
     * </ul>
     * Package-accessible (no modifier) so {@code TelemetryOutcomeSpec} can test it directly.
     *
     * @param response the MCP response produced by the tool handler; may be null
     * @return a non-null outcome string
     */
    static String extractOutcome(McpResponse response) {
        if (response?.error != null) {
            String msg = response.error.message ?: ''
            if (msg.toLowerCase().contains('refus')) return 'refused'
            return 'error'
        }
        try {
            Object result = response?.result
            // BUILD-16B: check isError=true in result map (tool-level errors via toolError())
            // Protocol-level errors set response.error (handled above).
            // Tool-level errors set result.isError=true and must not be recorded as 'success'.
            if (result instanceof Map) {
                Map<String, Object> resultMap = result as Map<String, Object>
                if (resultMap.get('isError') == Boolean.TRUE) {
                    // FS 0.9.52: a GATE is not a fault. M3-advice-changed-the-next-call is specified
                    // against the literal 'plan_gate_fired' (WP-C C1) and read -1 for two months
                    // because every refusal landed here as 'error': 20 refusals on 2026-09-22 alone,
                    // zero rows carrying the literal. Classify before folding into 'error'.
                    String errText = ''
                    try {
                        List errContent = resultMap.get('content') as List
                        errText = ((errContent?.first() as Map)?.get('text') as String) ?: ''
                    } catch (Exception ignored) { }
                    if (errText.startsWith('PLAN-GATE:')) return 'plan_gate_fired'
                    if (errText.contains('BLOCKED_ONTOLOGY_GATE')) return 'refused'
                    return 'error'
                }
            }
            List content = response?.result?.content as List
            String text = ((content?.first() as Map)?.get('text') as String) ?: ''
            // FS 0.9.40 K2 (decision 206): forced = held content re-sent on request (wasted_tok);
            // partial = some of what was asked was already held and was not re-sent.
            // K2: a gate refusal sent no content -- it is not a read
            if (text.contains('"error":"BLOCKED_')) return 'refused'
            if (text.contains('"forced_repeat":true')) return 'forced'
            if (text.contains('"unchanged":true') || text.contains('"cached":true')) return 'unchanged'
            if (text.contains('"already_served"') || text.contains('"unchanged_paths"')) return 'partial'
            if (text.contains('_truncated')) return 'truncated'
        } catch (Exception ignored) {}
        return 'success'
    }

    private static final java.util.regex.Pattern CODE_LEAD = java.util.regex.Pattern.compile('^([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\\b')
    private static final java.util.regex.Pattern CODE_ANY  = java.util.regex.Pattern.compile('\\b([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\\b')
    private static final java.util.regex.Pattern CONTENT_ERROR =
        java.util.regex.Pattern.compile('^\\{\\s*(?:"success"\\s*:\\s*false\\s*,\\s*)?"error"\\s*:\\s*"([^"]*)"')

    /**
     * optimised-learning B3: the refusal code of an FS response, null when it was not a refusal. Sent to CS as
     * telemetry.error_code so WS4 can count caller mistakes from telemetry, not only from what Claude writes up.
     * Tool errors (isError) always get a code; a content-level refusal ({"error":...} at the head) gets one too.
     * Pinned by TelemetryOutcomeSpec CT-OL-1/2.
     */
    static String errorCodeOf(McpResponse response) {
        try {
            if (response == null) return null
            if (response.error != null) return codeFrom(response.error.message ?: '')
            Object result = response.result
            String text = responseText(response) ?: ''
            if (result instanceof Map && (result as Map).get('isError') == Boolean.TRUE) return codeFrom(text)
            String head = text.length() > 400 ? text.substring(0, 400) : text
            java.util.regex.Matcher m = CONTENT_ERROR.matcher(head.trim())
            return m.find() ? codeFrom(m.group(1)) : null
        } catch (Exception ignored) {
            return null
        }
    }

    static String codeFrom(String msg) {
        String t = (msg ?: '').trim()
        if (t.startsWith('PLAN-GATE')) return 'PLAN_GATE'
        java.util.regex.Matcher lead = CODE_LEAD.matcher(t)
        if (lead.find()) return lead.group(1)
        if (t.startsWith('expectedHash mismatch')) return 'HASH_MISMATCH'
        if (t.startsWith('options.expectedHash required')) return 'HASH_REQUIRED'
        if (t.startsWith('Unknown execute action')) return 'UNKNOWN_ACTION'
        java.util.regex.Matcher any = CODE_ANY.matcher(t.length() > 200 ? t.substring(0, 200) : t)
        return any.find() ? any.group(1) : 'ERROR'
    }

    private static String responseText(McpResponse response) {
        try {
            List content = response?.result?.content as List
            return (content?.first() as Map)?.get('text') as String
        } catch (Exception ignored) {
            return null
        }
    }

    private static int estimateResponseSize(McpResponse response) {
        try {
            List content = response?.result?.content as List
            return ((content?.first() as Map)?.get('text') as String)?.length() ?: 0
        } catch (Exception ignored) {
            return 0
        }
    }
}

