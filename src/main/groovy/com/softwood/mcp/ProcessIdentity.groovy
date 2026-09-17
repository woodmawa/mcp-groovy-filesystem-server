package com.softwood.mcp

import groovy.transform.CompileStatic

import java.lang.management.ManagementFactory
import java.time.Instant

/**
 * FS 0.9.17 WP-5 -- the identity of THIS filesystem-server process, minted once at JVM startup.
 *
 * <p>Deliberately a mirror of CS's {@code com.woodmawa.mcp.context.service.ProcessIdentity}, down
 * to the key shape, because both write rows into the same {@code session_claims} table and CS's
 * reaper decides liveness for all of them. Two servers, one bookkeeping.
 *
 * <p>One stdio JVM is started per MCP client connection, so <b>the process is the chat</b>. That is
 * the only discriminator the client gives away for free: all 2,103 rows in
 * {@code mcp_initialize_events} carry no {@code _meta} at all, so nothing can be inferred from the
 * protocol. A claim that arrives on this process's own pipe is proof of which chat it serves.
 * Nothing here is derived from timing, pid ordering or process trees.
 *
 * @since FS 0.9.17
 */
@CompileStatic
final class ProcessIdentity {

    /** Written to {@code session_claims.server}. Distinguishes these rows from CS's and AW's. */
    static final String SERVER = 'fs'

    /** OS process id of this JVM. */
    static final long PID = ManagementFactory.runtimeMXBean.pid

    /** JVM start time, ISO-8601 UTC. Distinguishes a restart from the process it replaced. */
    static final String JVM_STARTED_AT =
            Instant.ofEpochMilli(ManagementFactory.runtimeMXBean.startTime).toString()

    /**
     * The primary key of this process's row in {@code session_claims}. Minted once, never shared:
     * a process reads and writes this row and no other.
     */
    static final String OWNER_KEY =
            SERVER + '-' + PID + '-' + ManagementFactory.runtimeMXBean.startTime +
            '-' + UUID.randomUUID().toString().substring(0, 8)

    /** FS 0.9.38 (CS chain 63e4d174): the jar version THIS JVM loaded; 'dev' when run from classes. */
    static final String VERSION = ProcessIdentity.package?.implementationVersion ?: 'dev'

    /** This server's row in {@code server_versions}. */
    static final String REGISTRY_NAME = 'filesystem'

    /**
     * FS 0.9.38 -- stamp the version this process RUNS onto its own claim row, and into
     * server_versions unless a process that started later already has. Install writes
     * installed_version; only a running process may say what is live.
     *
     * @return false when the schema is not migrated yet (or anything else failed); never throws,
     *         so it can never cost the claim it follows
     */
    static boolean stampRunningVersion(java.sql.Connection conn) {
        try {
            java.sql.PreparedStatement a = conn.prepareStatement('UPDATE session_claims SET server_version = ? WHERE owner_key = ?')
            a.setString(1, VERSION); a.setString(2, OWNER_KEY)
            a.executeUpdate(); a.close()
            java.sql.PreparedStatement b = conn.prepareStatement(
                "UPDATE server_versions SET version = ?, started_at = ?, updated_at = datetime('now') " +
                'WHERE server_name = ? AND (started_at IS NULL OR started_at <= ?)')
            b.setString(1, VERSION); b.setString(2, JVM_STARTED_AT); b.setString(3, REGISTRY_NAME); b.setString(4, JVM_STARTED_AT)
            b.executeUpdate(); b.close()
            return true
        } catch (Exception ignored) {
            return false
        }
    }

    private ProcessIdentity() { throw new UnsupportedOperationException('static holder') }
}
