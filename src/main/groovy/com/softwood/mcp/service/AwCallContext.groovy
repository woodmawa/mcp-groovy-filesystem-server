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

    private static final ThreadLocal<String[]> CURRENT = new ThreadLocal<String[]>()

    static void set(String runId, String nodeId) {
        String r = runId?.trim()
        String n = nodeId?.trim()
        if (r && n) CURRENT.set([r, n] as String[]) else CURRENT.remove()
    }

    static void clear() { CURRENT.remove() }

    static String run()  { CURRENT.get()?.getAt(0) }

    static String node() { CURRENT.get()?.getAt(1) }

    private AwCallContext() { throw new UnsupportedOperationException('static holder') }
}
