package com.softwood.mcp.service

import groovy.transform.CompileStatic

/**
 * FS 0.9.95 -- the AW run and node an incoming call is made FOR, from AW's mcp.tool_call headers X-Aw-Run / X-Aw-Node
 * (AW 1.30.95 FlowCaller). A long call -- gradle test -- uses them to post live progress to the node that is waiting
 * on it (flow_management action=record_node_progress).
 *
 * <p>Set per request by McpController and removed in finally, exactly like the caller session: a pooled HTTP thread
 * must never carry one call's run into the next. Absent on stdio and on calls not made by a flow node, and then
 * nothing reports.</p>
 */
@CompileStatic
final class AwCallContext {

    static final String RUN_HEADER  = 'X-Aw-Run'
    static final String NODE_HEADER = 'X-Aw-Node'
    /** optimised-learning WP3 (decision 389): when the AW run was asked for, epoch ms (AW FlowCaller). RunStartedPlanGateSpec. */
    static final String RUN_STARTED_HEADER = 'X-Aw-Run-Started'

    private static final ThreadLocal<String[]> CURRENT = new ThreadLocal<String[]>()

    static void set(String runId, String nodeId) { set(runId, nodeId, null) }

    /**
     * FS 0.9.97 (G2): also the session the caller declared (X-Mcp-Caller-Session). Run and node count only as a pair;
     * the session stands on its own -- a chat's call declares one with no run. Read by ExecuteJobRegistry.submit on
     * the request thread, so a job is filed under the caller that started it, not under this process's claim.
     */
    static void set(String runId, String nodeId, String callerSession) { set(runId, nodeId, callerSession, null) }

    /**
     * WP3 (decision 389): also the run's start (X-Aw-Run-Started). Kept only with a run/node pair, and only when it
     * parses as a whole number of ms -- anything else is dropped, never guessed.
     */
    static void set(String runId, String nodeId, String callerSession, String runStarted) {
        String r = runId?.trim()
        String n = nodeId?.trim()
        String s = callerSession?.trim()
        if (!(r && n)) { r = null; n = null }
        String t = runStarted?.trim()
        if (!(r && t && t ==~ /\d{1,19}/)) t = null
        if (r || s) CURRENT.set([r, n, s ?: null, t] as String[]) else CURRENT.remove()
    }

    static void clear() { CURRENT.remove() }

    static String run()  { CURRENT.get()?.getAt(0) }

    static String node() { CURRENT.get()?.getAt(1) }

    static String session() { CURRENT.get()?.getAt(2) }

    /** The AW run's start, epoch ms, or null when the call carried none (or carried something unreadable). */
    static Long runStartedAt() {
        String[] c = CURRENT.get()
        String t = (c != null && c.length > 3) ? c[3] : null
        if (!t) return null
        try { return Long.valueOf(t) } catch (NumberFormatException ignored) { return null }
    }

    private AwCallContext() { throw new UnsupportedOperationException('static holder') }
}
