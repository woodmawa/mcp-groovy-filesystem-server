package com.softwood.mcp.service

import com.softwood.mcp.ProcessIdentity
import groovy.transform.CompileDynamic
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.TimeUnit

import static org.awaitility.Awaitility.await

/**
 * FS 0.9.114 -- connection-neutral claims (Will, 2026-10-21; CS 1.1.113 ClaimAdoptionSpec is the same rule).
 *
 * <p>The chat's tool route moved to a second stdio process set mid-chat; its FS process was still claimed to the
 * session closed by session-end, and had to be re-claimed by hand. The call already names its session
 * (callerSession), so an FS chat process adopts it -- through claimSession, the same write as server_lifecycle
 * claim_session -- when it holds no claim or holds one on a session a real session-end closed. Never over another
 * OPEN session's claim; never on the companion.</p>
 * <ul>
 *   <li>FCA-1 (RED on 0.9.113): unclaimed -> the persisted claim row names the caller's session and its group.</li>
 *   <li>FCA-2 (RED on 0.9.113): claimed to a closed session -> adopted.</li>
 *   <li>FCA-3 CONTROL: claimed to another OPEN session -> not taken.</li>
 *   <li>FCA-4 CONTROL: a closed callerSession is never adopted.</li>
 *   <li>FCA-5 CONTROL: the companion never adopts.</li>
 *   <li>FCA-6 (RED on 0.9.113): the controller's queued entry (practice #260: no I/O on the hot path) adopts off the
 *       request thread.</li>
 * </ul>
 */
@Title('FS 0.9.114 -- connection-neutral claims: adopt the open callerSession')
@CompileDynamic
class FsClaimAdoptionSpec extends Specification {

    @TempDir
    Path tmp

    static final String OPEN_SID   = '2026-10-21-16-57'
    static final String CLOSED_SID = '2026-10-21-14-20'
    static final String OTHER_OPEN = '2026-10-21-17-30'
    static final String GROUP      = 'mcp-servers'

    String dbPath
    FilesystemTelemetryService svc

    def setup() {
        dbPath = tmp.resolve('adopt-test.db').toString().replace((char) 92, (char) 47)
        exec('''CREATE TABLE session_claims (
                    owner_key TEXT PRIMARY KEY, server TEXT NOT NULL, session_id TEXT NOT NULL DEFAULT '',
                    group_id TEXT, pid INTEGER, jvm_started_at TEXT,
                    claimed_at TEXT NOT NULL DEFAULT (datetime('now')), last_seen_at TEXT NOT NULL DEFAULT (datetime('now')))''')
        exec('''CREATE TABLE session_claim_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, owner_key TEXT, server TEXT, session_id TEXT, group_id TEXT,
                    pid INTEGER, jvm_started_at TEXT, source TEXT, claimed_at TEXT, released_at TEXT, transport TEXT)''')
        exec('CREATE TABLE sessions_index (session_id TEXT PRIMARY KEY, created_at TEXT, session_end_version TEXT)')
        exec('CREATE TABLE session_group_bindings (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, group_id TEXT, status TEXT)')
        exec("INSERT INTO sessions_index VALUES ('${OPEN_SID}', '2026-10-21 15:57:55', NULL)")
        exec("INSERT INTO sessions_index VALUES ('${OTHER_OPEN}', '2026-10-21 16:30:00', NULL)")
        exec("INSERT INTO sessions_index VALUES ('${CLOSED_SID}', '2026-10-21 13:20:43', '5.58')")
        exec("INSERT INTO session_group_bindings (session_id, group_id, status) VALUES ('${OPEN_SID}', '${GROUP}', 'bound')")

        svc = new FilesystemTelemetryService()
        svc.dbPath = dbPath
        svc.companionOverride = Boolean.FALSE
    }

    private void exec(String sql) {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try { conn.createStatement().execute(sql) } finally { conn.close() }
    }

    private Map<String, Object> ownClaim() {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:${dbPath}")
        try {
            ResultSet rs = conn.createStatement().executeQuery(
                "SELECT session_id, group_id FROM session_claims WHERE owner_key = '${ProcessIdentity.OWNER_KEY}'")
            Map<String, Object> out = rs.next() ? [session_id: rs.getString(1), group_id: rs.getString(2)] : null
            rs.close()
            return out
        } finally { conn.close() }
    }

    def 'FCA-1 (RED on 0.9.113): an unclaimed FS chat process adopts the open callerSession, with its group'() {
        when:
        Map<String, Object> r = svc.adoptCallerSession(OPEN_SID)

        then:
        ownClaim().session_id == OPEN_SID
        ownClaim().group_id   == GROUP
        r.status == 'adopted'
    }

    def 'FCA-2 (RED on 0.9.113): an FS process still claimed to a closed session adopts the open one'() {
        given:
        svc.claimSession(CLOSED_SID, GROUP)

        when:
        svc.adoptCallerSession(OPEN_SID)

        then:
        ownClaim().session_id == OPEN_SID
    }

    def 'FCA-3 CONTROL: a claim on another OPEN session is never taken'() {
        given:
        svc.claimSession(OTHER_OPEN, GROUP)

        when:
        Map<String, Object> r = svc.adoptCallerSession(OPEN_SID)

        then:
        r == null
        ownClaim().session_id == OTHER_OPEN
    }

    def 'FCA-4 CONTROL: a closed callerSession is never adopted'() {
        when:
        Map<String, Object> r = svc.adoptCallerSession(CLOSED_SID)

        then:
        r == null
        ownClaim() == null
    }

    def 'FCA-6 (RED on 0.9.113): the queued entry the controller calls adopts off the request thread'() {
        when:
        svc.adoptCallerSessionAsync(OPEN_SID)

        then:
        await().atMost(5, TimeUnit.SECONDS).until { ownClaim()?.session_id == OPEN_SID }
    }

    def 'FCA-5 CONTROL: the companion never adopts'() {
        given:
        svc.companionOverride = Boolean.TRUE

        when:
        Map<String, Object> r = svc.adoptCallerSession(OPEN_SID)

        then:
        r == null
        ownClaim() == null
    }
}
