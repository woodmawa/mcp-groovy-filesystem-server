package com.softwood.mcp.service

import com.softwood.mcp.ProcessIdentity
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * FS 0.9.17 WP-5 -- the filesystem server must answer for the chat it is actually serving.
 *
 * <p><b>RED on 0.9.16, deliberately.</b>
 *
 * <p>CS 1.0.23/1.0.24 moved the context server off the singleton {@code active_session} row and
 * onto per-process claims. FS did not move, so it still resolves every session id from
 * {@link FilesystemTelemetryService#readActiveSessionId()}, which reads
 * {@code SELECT session_id FROM active_session ORDER BY id DESC LIMIT 1} -- one row for the whole
 * machine. Measured 2026-09-03: five FS JVMs were running against that one row.
 *
 * <p>The consequence is narrower than CS's but the same shape. Every FS tool call's telemetry, and
 * every range-cache read and write through {@code ContextServerClient}, is attributed to whichever
 * chat bootstrapped most recently. With two chats open, one chat's file reads are filed under the
 * other's session -- and because the range cache is keyed by session id, they also miss, which is
 * how {@code real_kh_pct} was seen sitting near 15% for reasons nothing explained.
 *
 * <p>The rule is the one CS already proved:
 * <ol>
 *   <li>the in-process claim -- authoritative, no TTL;</li>
 *   <li>if unclaimed, this process's own row in {@code session_claims};</li>
 *   <li>otherwise UNBOUND -- never the newest global session.</li>
 * </ol>
 *
 * <p>Step 3 is the one that matters and the one that is easy to soften. FS telemetry already
 * tolerates a null session by recording {@code 'unknown'}, so the temptation is to fall back to
 * {@code active_session} "just to get a value". That fallback IS the defect: a wrong session id is
 * worse than an absent one, because it is indistinguishable from correct data downstream.
 *
 * @since FS 0.9.17 (WP-5)
 */
@CompileDynamic
class FsSessionClaimSpec extends Specification {

    @TempDir
    Path tmp

    String dbPath

    def setup() {
        dbPath = tmp.resolve('claims-test.db').toString().replace((char) 92, (char) 47)
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            conn.createStatement().with { st ->
                st.execute('''CREATE TABLE active_session (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    session_id TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL DEFAULT (datetime('now')))''')
                st.execute('''CREATE TABLE session_claims (
                    owner_key      TEXT PRIMARY KEY,
                    server         TEXT NOT NULL,
                    session_id     TEXT NOT NULL DEFAULT '',
                    group_id       TEXT,
                    pid            INTEGER,
                    jvm_started_at TEXT,
                    claimed_at     TEXT NOT NULL DEFAULT (datetime('now')),
                    last_seen_at   TEXT NOT NULL DEFAULT (datetime('now')))''')
                st.close()
            }
        } finally {
            conn.close()
        }
    }

    /** What some other chat's bootstrap left behind. Informational; never adopted. */
    private void singletonHolds(String sessionId) {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            conn.createStatement().execute(
                "INSERT OR REPLACE INTO active_session (id, session_id) VALUES (1, '${sessionId}')")
        } finally {
            conn.close()
        }
    }

    private FilesystemTelemetryService serviceForOwnProcess() {
        FilesystemTelemetryService svc = new FilesystemTelemetryService()
        svc.dbPath = dbPath
        return svc
    }

    // -------------------------------------------------------------------------
    // 1. The identity this server has and never shares
    // -------------------------------------------------------------------------

    def "FS mints an owner key naming itself, not the context server"() {
        expect:
        ProcessIdentity.SERVER == 'fs'
        ProcessIdentity.OWNER_KEY.startsWith('fs-')
        ProcessIdentity.PID > 0
    }

    // -------------------------------------------------------------------------
    // 2. The rule
    // -------------------------------------------------------------------------

    def "a claimed FS process answers for its own chat, whatever the singleton says"() {
        given: 'this FS process is claimed for the chat it serves'
        FilesystemTelemetryService svc = serviceForOwnProcess()
        svc.claimSession('2026-09-04-09-00', 'mcp-servers')

        and: 'another chat bootstraps and takes the machine-wide row'
        singletonHolds('2026-09-04-09-30')

        expect: 'invisible here'
        svc.readActiveSessionId() == '2026-09-04-09-00'
    }

    def "an unclaimed FS process does NOT adopt the newest global session"() {
        given: 'this process was never claimed, and another chat owns the singleton'
        FilesystemTelemetryService svc = serviceForOwnProcess()
        singletonHolds('2026-09-04-09-30')

        expect: 'unbound -- a wrong session id is worse than an absent one'
        svc.readActiveSessionId() == null
    }

    def "an unclaimed process recovers its own claim row when the pointer is lost"() {
        given: 'the row this process wrote at claim time survives a cleared in-memory pointer'
        FilesystemTelemetryService svc = serviceForOwnProcess()
        svc.claimSession('2026-09-04-09-00', 'mcp-servers')

        when: 'a fresh service object over the same database and the same owner_key'
        FilesystemTelemetryService reborn = serviceForOwnProcess()

        then:
        reborn.readActiveSessionId() == '2026-09-04-09-00'
    }

    def "releasing leaves the process unbound rather than holding a dead session"() {
        given:
        FilesystemTelemetryService svc = serviceForOwnProcess()
        svc.claimSession('2026-09-04-09-00', 'mcp-servers')
        singletonHolds('2026-09-04-09-30')

        when:
        svc.releaseClaim()

        then:
        svc.readActiveSessionId() == null
    }

    // -------------------------------------------------------------------------
    // 3. The claim is written where CS can see it
    // -------------------------------------------------------------------------

    def "the claim row is written with server='fs' so CS's reaper can see and reap it"() {
        given:
        FilesystemTelemetryService svc = serviceForOwnProcess()

        when:
        svc.claimSession('2026-09-04-09-00', 'mcp-servers')

        then: 'one shared table, one liveness rule -- FS does not get its own bookkeeping'
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            def rs = conn.createStatement().executeQuery(
                "SELECT server, session_id, group_id, pid FROM session_claims " +
                "WHERE owner_key = '${ProcessIdentity.OWNER_KEY}'")
            assert rs.next()
            assert rs.getString('server') == 'fs'
            assert rs.getString('session_id') == '2026-09-04-09-00'
            assert rs.getString('group_id') == 'mcp-servers'
            assert rs.getLong('pid') == ProcessIdentity.PID
            rs.close()
        } finally {
            conn.close()
        }
        true
    }
}
