package com.softwood.mcp.service

import groovy.transform.CompileStatic

import java.util.regex.Pattern

/**
 * Caller-session phase 1 (brief 2026-10-21, D1) -- the session a CHAT named on its own call.
 *
 * <p>Desktop runs ONE stdio process set for every chat in every project, and no chat identity travels on a call, so
 * this process's claim names whichever chat claimed last. A chat now passes {@code callerSession} (the id its
 * session-bootstrap returned) on its calls. {@code McpController} reads and strips it before the unread-argument guard
 * and any handler, checks its shape, and holds it here for that call only, on the request thread.
 * {@link FilesystemTelemetryService#readActiveSessionId} answers it first, so PLAN-GATE, the ontology gate, the read
 * ledger and every call to CS made on the request thread follow it; the call's telemetry row is filed under it with
 * session_source arg and the claim beside it. Absent, nothing changes (phase 1).</p>
 *
 * <p>FS does not own sessions_index: it checks SHAPE here (practice #38) and leaves existence and closure to CS, whose
 * telemetry write files a row for an unknown or closed session as such.</p>
 *
 * <p>FS 0.9.121 (X86 P3-4): work handed to another thread carries it. A ThreadLocal does not cross to a worker, so
 * an async CS call queued by a chat's call (re-index, invalidate, range cache, file registry) used to resolve the
 * session on the writer thread and send the PROCESS CLAIM -- another chat's, with one shared stdio set. {@link #carrying}
 * wraps an executor so each task runs with the session that was held when it was submitted, and nothing else
 * (ContextServerClientCallerHeaderSpec CH-4/CH-5). Async execute jobs read it at submit (ExecuteJobVisibilitySpec JV-1b).</p>
 */
@CompileStatic
final class CallerSession {

    /** The argument every chat call may carry. Same name on CS, FS and AW. */
    static final String ARG = 'callerSession'

    /** A session id's shape: what session-bootstrap mints (2026-10-21-09-48) with room for other ids, at most 64 chars. */
    static final Pattern SHAPE = Pattern.compile('^[A-Za-z0-9][A-Za-z0-9:._-]{0,63}$')

    /** What tools/list says about the argument. Optional until phase 2 requires it. */
    static final String DESCRIPTION =
        'The session id session-bootstrap returned to THIS chat. Pass it on every call: it decides which session the ' +
        'call is attributed to, whichever chat last claimed this process. Optional for now (without it the process ' +
        'claim decides).'

    private static final ThreadLocal<String> HELD = new ThreadLocal<String>()

    private CallerSession() { throw new UnsupportedOperationException('static holder') }

    static boolean wellFormed(String v) {
        return v != null && SHAPE.matcher(v).matches()
    }

    /** Blank and 'unknown' are absent. Returns the value previously held, so the caller can restore it. */
    static String setArg(String sessionId) {
        String previous = HELD.get()
        String v = sessionId?.trim()
        if (v && v != 'unknown') HELD.set(v) else HELD.remove()
        return previous
    }

    /** The session the current call's chat named, or null. */
    static String currentArg() {
        return HELD.get()
    }

    static void clear() {
        HELD.remove()
    }

    /** An executor whose tasks run holding the session that was held on the SUBMITTING thread (or none). */
    static java.util.concurrent.ExecutorService carrying(java.util.concurrent.ExecutorService delegate) {
        return new CarryingExecutor(delegate)
    }

    /** Captures at execute() -- which AbstractExecutorService.submit calls on the submitting thread -- and restores after. */
    static final class CarryingExecutor extends java.util.concurrent.AbstractExecutorService {
        private final java.util.concurrent.ExecutorService delegate

        CarryingExecutor(java.util.concurrent.ExecutorService delegate) { this.delegate = delegate }

        @Override
        void execute(Runnable task) {
            // X86 P3-3: the call's ATTRIBUTION -- its arg, else the session a flow node declared on this thread. The
            // header lives in AwCallContext, which does not cross threads either; with the claim no longer a fallback,
            // a flow node's queued CS work would otherwise go out unattributed.
            final String sid = currentArg() ?: AwCallContext.session()
            delegate.execute({ ->
                String previous = setArg(sid)
                try { task.run() }
                finally { setArg(previous) }
            } as Runnable)
        }

        @Override void shutdown() { delegate.shutdown() }
        @Override List<Runnable> shutdownNow() { return delegate.shutdownNow() }
        @Override boolean isShutdown() { return delegate.isShutdown() }
        @Override boolean isTerminated() { return delegate.isTerminated() }
        @Override boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return delegate.awaitTermination(timeout, unit)
        }
    }

    /** One property added to a tool definition's input schema. Copies; never changes the source map. */
    static Map<String, Object> withArg(Map<String, Object> toolDef) {
        Map<String, Object> schema = toolDef?.get('inputSchema') as Map<String, Object>
        if (schema == null) return toolDef
        Map<String, Object> props = new LinkedHashMap<String, Object>((schema.get('properties') ?: [:]) as Map<String, Object>)
        props.put(ARG, (Object) [type: 'string', description: DESCRIPTION])
        Map<String, Object> newSchema = new LinkedHashMap<String, Object>(schema)
        newSchema.put('properties', (Object) props)
        Map<String, Object> out = new LinkedHashMap<String, Object>(toolDef)
        out.put('inputSchema', (Object) newSchema)
        return out
    }
}
