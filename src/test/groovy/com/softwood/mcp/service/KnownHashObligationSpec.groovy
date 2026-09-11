package com.softwood.mcp.service

import groovy.transform.CompileDynamic
import spock.lang.Specification

/**
 * FS 0.9.33 -- W14. The file_read description states no obligation it cannot measure.
 *
 * <p><b>RED on 0.9.32.</b></p>
 *
 * <h3>What was there</h3>
 *
 * <p>Every FS tool definition, in every session, opened with a KNOWNHASH OBLIGATION block
 * declaring the metric "tracked per session and FAILING in mid-session-audit if under 30%".
 * Measured 2026-09-11 over 30 days: <b>66 eligible repeat reads across 34 sessions, out of
 * 2,406 file_read calls in 161 sessions</b> -- 2.7% of reads are even eligible, and 79% of
 * sessions produce no measurement at all. The metric is NULL on 125 of 159 sessions.</p>
 *
 * <p>The NULL is correct. eligibleReads counts repeat reads of a path already read this
 * session, after two deliberate narrowings, and W13 was STRUCK on the finding that the
 * narrowing is right. CS 1.0.2 RC-C had already written "knownhash_pct cannot serve" in a
 * comment and chosen orientation_tok instead. So the text mandated a threshold on a number
 * absent by design -- not an unreachable guard this time, but an unmeasurable obligation,
 * repeated to every model on every connect.</p>
 *
 * <h3>Why KH-3 and KH-4 exist</h3>
 *
 * <p>Cutting text is easy to overdo. KH-3 keeps the range caveat, which is real: passing a
 * hash to action=range returns unchanged:true INSTEAD of content, and a caller who does not
 * know that loses a read. KH-4 keeps the narrow observation, which fires only when a file
 * already in the StructureCache is re-read without a hash -- a specific, reachable and
 * genuinely wasteful case. The mandate went; the mechanics and the real signal stayed.</p>
 *
 * @since FS 0.9.33
 */
@CompileDynamic
class KnownHashObligationSpec extends Specification {

    static final String SERVICE = 'src/main/groovy/com/softwood/mcp/service/'

    private static String read(String name) {
        File f = new File(SERVICE + name)
        assert f.exists() : "expected " + SERVICE + name + " -- run tests from project root"
        return f.text
    }

    /** The served description only -- not the comment above it, which may discuss the old text. */
    private static List<String> descriptionLines() {
        List<String> all = new File(SERVICE + 'FileReadService.groovy').readLines()
        int s = all.findIndexOf { it.contains('private static final String DEFAULT_DESC') }
        int e = all.findIndexOf { it.contains('MANDATORY: pass as options.expectedHash') }
        assert s >= 0 && e > s : "could not locate the description block"
        return all[s..e]
    }

    def 'KH-1: the description declares no metric and no threshold'() {
        given:
        String desc = descriptionLines().join(String.valueOf((char) 10))

        expect: 'no mandate, because there is no measurement behind it'
        !desc.contains('KNOWNHASH IS MANDATORY')
        !desc.contains('FAILING in mid-session-audit')
        !desc.contains('knownhash_pct')
        !desc.contains('30%')
    }

    def 'KH-2: but it still says what knownHash does and where to get one'() {
        given:
        String desc = descriptionLines().join(String.valueOf((char) 10))

        expect: 'removing a false obligation must not take the useful instruction with it'
        desc.contains('knownHash saves re-sending content you already hold')
        desc.contains('file_content_hash')
        desc.contains('unchanged:true')
        desc.contains('_knownhash_hint')
    }

    def 'KH-3: the range caveat survives -- that one is real'() {
        given:
        String desc = descriptionLines().join(String.valueOf((char) 10))

        expect: 'a hash on action=range returns unchanged:true INSTEAD of content'
        desc.contains('Do NOT pass options.knownHash to action=range')
    }

    def 'KH-4: the narrow signal survives -- it fires on a case that actually happens'() {
        given:
        String client = read('ContextServerClient.groovy')

        expect: 'a cached file re-read without a hash is specific, reachable and wasteful'
        client.contains('writeMissingKnownHashObservationAsync')
        client.contains('KNOWNHASH MISSING')
        client.contains('despite the file being in the session StructureCache')
    }
}
