package com.softwood.mcp

import spock.lang.Specification

import java.sql.Connection
import java.sql.DriverManager

/**
 * FS 0.9.38 (CS chain 63e4d174) -- this process stamps the version it RUNS, at its claim.
 *
 * PIV-1  the claim row and the registry row take this process's version and start time
 * PIV-2  a registry already stamped by a process that started later is not regressed
 * PIV-3  a database without the new columns (CS not yet migrated) returns false and never throws
 */
class ProcessIdentityVersionStampSpec extends Specification {

    Connection conn

    def setup() {
        Class.forName('org.sqlite.JDBC')
        conn = DriverManager.getConnection('jdbc:sqlite::memory:')
    }

    def cleanup() { conn?.close() }

    private void migrated() {
        conn.createStatement().execute('CREATE TABLE session_claims (owner_key TEXT PRIMARY KEY, server TEXT, server_version TEXT)')
        conn.createStatement().execute('CREATE TABLE server_versions (server_name TEXT PRIMARY KEY, version TEXT, started_at TEXT, updated_at TEXT)')
        conn.createStatement().execute("INSERT INTO session_claims (owner_key, server) VALUES ('${ProcessIdentity.OWNER_KEY}', '${ProcessIdentity.SERVER}')")
    }

    private Object one(String sql) {
        def rs = conn.createStatement().executeQuery(sql)
        try { rs.next() ? rs.getObject(1) : null } finally { rs.close() }
    }

    def 'PIV-1: claim row and registry take this process version'() {
        given:
        migrated()
        conn.createStatement().execute("INSERT INTO server_versions (server_name, version) VALUES ('${ProcessIdentity.REGISTRY_NAME}', 'installed-only')")
        expect:
        ProcessIdentity.stampRunningVersion(conn)
        one("SELECT server_version FROM session_claims") == ProcessIdentity.VERSION
        one("SELECT version FROM server_versions") == ProcessIdentity.VERSION
        one("SELECT started_at FROM server_versions") == ProcessIdentity.JVM_STARTED_AT
    }

    def 'PIV-2: a later-started process already stamped is not regressed'() {
        given:
        migrated()
        conn.createStatement().execute("INSERT INTO server_versions (server_name, version, started_at) VALUES ('${ProcessIdentity.REGISTRY_NAME}', 'newer', '9999-01-01T00:00:00Z')")
        when:
        ProcessIdentity.stampRunningVersion(conn)
        then:
        one("SELECT version FROM server_versions") == 'newer'
    }

    def 'PIV-3: an unmigrated database is a false, not an exception'() {
        given:
        conn.createStatement().execute('CREATE TABLE session_claims (owner_key TEXT PRIMARY KEY, server TEXT)')
        expect:
        !ProcessIdentity.stampRunningVersion(conn)
    }
}