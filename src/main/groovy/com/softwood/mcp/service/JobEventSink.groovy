package com.softwood.mcp.service

import groovy.transform.CompileStatic

/**
 * FS 0.9.92 P3: told when an async job starts ({@code running}) and, exactly once, when it ends
 * ({@code completed|failed|timeout|cancelled}). Called on the job's own threads; an implementation must not block and
 * must not throw -- {@link ExecuteJobRegistry} guards it anyway, because a job never fails over its own telemetry.
 */
@CompileStatic
interface JobEventSink {
    void jobEvent(ExecuteJob job, String status)

    /**
     * FS 0.9.98: null while events are getting through; otherwise what is known about why they are not (reachable,
     * since, dropped, lastError, note). A job answer carries it so a caller learns that AW progress cannot see this
     * work -- found live 2026-10-15, when 11 minutes of dropped events read as quiet:true.
     */
    default Map<String, Object> health() { null }
}
