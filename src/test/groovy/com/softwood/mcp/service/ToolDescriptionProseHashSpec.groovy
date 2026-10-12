package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Title

/**
 * FS 0.9.82 R3-3 (Will 2026-10-12: "hash prose only"). The tool-description alignment hash is SHA-256(prose)[0..11]:
 * the text up to a trailing generated "Registered actions:" segment, trailing whitespace removed. A copy of that
 * generated line stored in a CS row went stale when AW added actions and turned tool-descriptions-served-match-source
 * critical. CS and AW carry the same algorithm; GOLDEN is the same constant in all three repos' specs.
 *
 * TDP-1  GOLDEN for the prose, the prose plus a generated tail, the prose plus trailing whitespace
 * TDP-2  a prose change still changes the hash
 * TDP-3  servedHashes -- what FS reports to CS -- is the prose hash of what it serves
 */
@Title('FS tool-description report hashes prose only -- TDP-1..TDP-3')
class ToolDescriptionProseHashSpec extends Specification {

    static final String PROSE = 'Tool probe \u2014 does things.\n\nMETADATA actions:\n  list   query'
    static final String GOLDEN = '83b0094c7df4'

    def 'TDP-1: prose, prose + generated tail, prose + trailing whitespace -> the same GOLDEN hash'() {
        expect:
        ToolDescriptionRegistry.hash12(PROSE) == GOLDEN
        ToolDescriptionRegistry.alignHash12(PROSE) == GOLDEN
        ToolDescriptionRegistry.alignHash12(PROSE + '\n\nRegistered actions: a, b, start') == GOLDEN
        ToolDescriptionRegistry.alignHash12(PROSE + '\n') == GOLDEN
    }

    def 'TDP-2: a prose change still changes the hash'() {
        expect:
        ToolDescriptionRegistry.alignHash12(PROSE.replace('things', 'other things') + '\n\nRegistered actions: a') ==
            '83135240512e'
    }

    def 'TDP-3: servedHashes is the prose hash of the served text'() {
        given: 'no CS, so the registered DEFAULT -- here with a generated tail -- is what is served'
        ToolDescriptionRegistry registry = new ToolDescriptionRegistry()
        registry.registerDefault('probe_tool', PROSE + '\n\nRegistered actions: a, start')

        expect:
        registry.servedHashes()['probe_tool'] == GOLDEN
    }
}
