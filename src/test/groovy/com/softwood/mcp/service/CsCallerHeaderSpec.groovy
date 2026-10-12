package com.softwood.mcp.service

import spock.lang.Specification

/**
 * FS 0.9.80 SB-2 -- every FS call to CS names who it is for. A claimed process declares its session (FS 0.9.58);
 * an unclaimed one -- startup tool-description fetches, a re-index after a write before the chat has claimed --
 * now names itself instead of sending nothing, so CS files the row under a name rather than 'unknown'.
 *
 * <p>CH-1 claimed: the session goes out, no actor. CH-2 unclaimed: no session, actor system:fs-unclaimed.</p>
 */
class CsCallerHeaderSpec extends Specification {

    private static URLConnection conn() { new URL('http://127.0.0.1:1/mcp').openConnection() }

    def "CH-1: a claimed FS process declares its session and names no actor"() {
        given:
        URLConnection c = conn()

        when:
        ContextServerClient.applyCaller(c, '2026-10-09-10-20')

        then:
        c.getRequestProperty('X-Mcp-Caller-Session') == '2026-10-09-10-20'
        c.getRequestProperty('X-Mcp-Actor') == null
    }

    def "CH-2: an unclaimed FS process sends no session but names itself"() {
        given:
        URLConnection c = conn()

        when:
        ContextServerClient.applyCaller(c, sid)

        then:
        c.getRequestProperty('X-Mcp-Caller-Session') == null
        c.getRequestProperty('X-Mcp-Actor') == 'system:fs-unclaimed'

        where:
        sid << [null, '']
    }
}
