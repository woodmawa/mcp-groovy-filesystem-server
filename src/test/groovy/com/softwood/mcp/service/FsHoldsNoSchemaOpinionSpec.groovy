package com.softwood.mcp.service

import groovy.transform.CompileDynamic
import spock.lang.Specification

import java.sql.Connection
import java.sql.ResultSet

/**
 * FS 0.9.32 -- R2. FS writes rows into CS's database; it does not decide its shape.
 *
 * <p><b>RED on 0.9.31, every case.</b></p>
 *
 * <h3>What was still there</h3>
 *
 * <p>{@code FilesystemTelemetryService.init()} created {@code tool_call_telemetry}, three indexes
 * on it, and ran an ALTER-ADD-COLUMN loop for {@code action}, {@code path_hash} and
 * {@code outcome} -- for a table FS has not written a row into since <b>0.9.29</b>, when
 * {@code recordToolCall} started forwarding through {@code ContextServerClient}. 0.9.29's own
 * comment set the condition for removing it: "the ordering hazard that motivated it does not need
 * solving -- it disappears once FS stops writing these rows directly at all, which is what this
 * list is now the last remnant of." That condition was met three releases ago and nothing was
 * watching, which is why it is a spec now rather than a comment again.</p>
 *
 * <p>{@code ensurePendingReindexTable} was the one still doing harm. FS's shape omitted
 * {@code source}; CS's two creators had it. On a fresh database the winner of the startup race
 * decided the schema, and CS's queue handler carried a CREATE, an ALTER and a CREATE UNIQUE INDEX
 * -- each wrapped in a swallow -- to cope with losing. CS 1.0.65 makes SqliteSchemaManager the
 * single owner.</p>
 *
 * <h3>What is deliberately NOT removed</h3>
 *
 * <p>FSO-3. The {@code INSERT} into {@code pending_reindex} stays. Its only caller is the catch
 * block in {@code ContextServerClient.reindexFileAsync} -- the fallback for CS HTTP being
 * unreachable. Routing it through a CS action would make it a no-op in the one circumstance it
 * exists for. Writing a row into a table you did not create is not the breach; deciding its
 * shape is, and that is what has gone.</p>
 *
 * @since FS 0.9.32
 */
@CompileDynamic
class FsHoldsNoSchemaOpinionSpec extends Specification {

    static final String MAIN = 'src/main/groovy/com/softwood/mcp/service/'

    private static String read(String name) {
        File f = new File(MAIN + name)
        assert f.exists() : "expected ${MAIN}${name} -- run tests from project root"
        return f.text
    }

    /** Whole-line comments stripped, so prose describing the old DDL cannot mask its return. */
    private static String code(String name) {
        return read(name).split(/\n/)
                         .findAll { String l -> !(l.trim().startsWith('//') || l.trim().startsWith('*')) }
                         .join('\n')
    }

    def 'FSO-1: FilesystemTelemetryService issues no DDL of any kind'() {
        given:
        String src = code('FilesystemTelemetryService.groovy')

        expect: "CS owns both tables this class touches; a server's database is scoped to it"
        !(src =~ /CREATE TABLE/)
        !(src =~ /CREATE INDEX/)
        !(src =~ /CREATE UNIQUE INDEX/)
        !(src =~ /ALTER TABLE/)
    }

    def 'FSO-2: ensurePendingReindexTable is gone, not merely unused'() {
        expect: 'an unreachable DDL method is still a schema opinion waiting to be called again'
        !code('FilesystemTelemetryService.groovy').contains('ensurePendingReindexTable')

        and: 'the name survives only in the comment that explains the removal, which is the point'
        read('FilesystemTelemetryService.groovy').contains('ensurePendingReindexTable')
    }

    def 'FSO-3: the pending_reindex INSERT survives, and only on the HTTP-unreachable path'() {
        given:
        String telemetry = read('FilesystemTelemetryService.groovy')
        String client = read('ContextServerClient.groovy')

        expect: 'the write itself is still there -- it is the fallback, and must stay reachable'
        telemetry.contains('INSERT OR IGNORE INTO pending_reindex')

        and: 'and it is called from the catch block, not the happy path'
        int tryIdx = client.indexOf('void reindexFileAsync')
        int catchIdx = client.indexOf('} catch (Exception e) {', tryIdx)
        int callIdx = client.indexOf('queueReindexAsync', tryIdx)
        tryIdx >= 0 && catchIdx > tryIdx && callIdx > catchIdx
    }

    def 'FSO-4: the connections carry the contention setting every other writer has'() {
        given: 'a real database, opened the way init() opens it'
        File tempDb = File.createTempFile('fs_pragma_spec_', '.db')
        tempDb.deleteOnExit()
        FilesystemTelemetryService svc = new FilesystemTelemetryService()
        svc.dbPath = tempDb.absolutePath

        when:
        svc.init()
        Connection conn = svc.dbConn
        int timeout = -1
        if (conn != null) {
            ResultSet rs = conn.createStatement().executeQuery('PRAGMA busy_timeout')
            rs.next()
            timeout = rs.getInt(1)
            rs.close()
        }

        then: 'UsageTracker has used 10000 since FIX-1; these connections used SQLite default zero'
        conn != null
        timeout == 10000

        cleanup: "hardening, not a fix -- 119 FS logs carry zero SQLITE_BUSY, and the four write-through failures on record are all from a Test worker thread"
        svc.shutdown()
        tempDb.delete()
    }
}
