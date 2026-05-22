package com.softwood.mcp.controller

import com.softwood.mcp.model.McpResponse
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import spock.lang.Specification

/**
 * TelemetryOutcomeSpec — BUILD-16B TDD contracts.
 *
 * <p>Locks in the {@link McpController#extractOutcome} behaviour that drives
 * {@code tool_call_telemetry.outcome='unchanged'} for cache-hit reads. The feature
 * stopped writing {@code outcome='unchanged'} between 2026-05-20 and 2026-05-22
 * due to a deployed-jar gap; it was confirmed working in FS 0.9.4 via a live probe
 * on 2026-05-22.  These tests ensure the text-pattern match cannot regress silently.</p>
 *
 * <h3>Contracts</h3>
 * <ul>
 *   <li>CT-16B-1 — {@code extractOutcome} returns {@code "unchanged"} when the response
 *       text contains {@code "unchanged":true} (explicit-hash cache-hit format).</li>
 *   <li>CT-16B-2 — {@code extractOutcome} returns {@code "unchanged"} when the response
 *       text contains {@code "unchanged":true} AND {@code "_auto_kh":true}
 *       (auto-lookup cache-hit format).</li>
 *   <li>CT-16B-3 — {@code extractOutcome} returns {@code "success"} for a normal
 *       content response (regression guard: must not be confused with unchanged).</li>
 *   <li>CT-16B-4 — {@code extractOutcome} returns {@code "error"} for a tool-level
 *       error response (isError=true in result map).</li>
 *   <li>CT-16B-5 — {@code extractOutcome} returns {@code "truncated"} when response
 *       text contains {@code _truncated} field.</li>
 * </ul>
 *
 * <p>All tests use responses constructed via {@link groovy.json.JsonOutput#toJson} \u2014
 * the same serialiser used by {@link com.softwood.mcp.service.AbstractFileService#textResponse}
 * \u2014 so the text pattern matches what the real code produces.</p>
 *
 * @see McpController#extractOutcome(McpResponse)
 */
@CompileDynamic
class TelemetryOutcomeSpec extends Specification {

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Build an McpResponse equivalent to AbstractFileService.textResponse(id, map).
     * Serialises the payload map to JSON text exactly as the real code does so the
     * pattern checks in extractOutcome work against realistic content.
     */
    private static McpResponse buildTextResponse(Map<String, Object> payload) {
        Map<String, Object> enriched = new LinkedHashMap<>(payload)
        enriched['_tok'] = (int)(JsonOutput.toJson(payload).length() / 4)
        String text = JsonOutput.toJson(enriched)
        McpResponse.success(1, [content: [[type: 'text', text: text]]])
    }

    // =========================================================================
    // CT-16B-1 — explicit-hash cache-hit
    // =========================================================================

    def 'CT-16B-1: extractOutcome returns unchanged for explicit-hash cache-hit response'() {
        given: 'a response produced by the hash-gate HIT (explicit knownHash) path'
        McpResponse resp = buildTextResponse([
            unchanged        : true,
            file_content_hash: 'abc123def456',
            _note            : 'File unchanged since last read - reuse content from previous response.'
        ])

        expect:
        McpController.extractOutcome(resp) == 'unchanged'
    }

    // =========================================================================
    // CT-16B-2 — auto-lookup cache-hit
    // =========================================================================

    def 'CT-16B-2: extractOutcome returns unchanged for auto-lookup cache-hit response'() {
        given: 'a response produced by the hash-gate HIT (auto-lookup) path'
        McpResponse resp = buildTextResponse([
            unchanged        : true,
            file_content_hash: 'abc123def456',
            _auto_kh         : true,
            _note            : 'File unchanged (auto-detected from session hash cache).'
        ])

        expect:
        McpController.extractOutcome(resp) == 'unchanged'
    }

    // =========================================================================
    // CT-16B-3 — normal content read
    // =========================================================================

    def 'CT-16B-3: extractOutcome returns success for a normal content response'() {
        given: 'a normal file-read response with real content'
        McpResponse resp = buildTextResponse([
            content: 'class Foo { void bar() {} }',
            file_content_hash: 'deadbeef1234'
        ])

        expect:
        McpController.extractOutcome(resp) == 'success'
    }

    // =========================================================================
    // CT-16B-4 — tool-level error
    // =========================================================================

    def 'CT-16B-4: extractOutcome returns error for a toolError response'() {
        given: 'a tool-level error response'
        McpResponse resp = McpResponse.toolError(1, 'File not found: /nonexistent')

        expect:
        McpController.extractOutcome(resp) == 'error'
    }

    // =========================================================================
    // CT-16B-5 — truncated response
    // =========================================================================

    def 'CT-16B-5: extractOutcome returns truncated when response carries _truncated flag'() {
        given: 'a response that was capped by the partial-read guard'
        McpResponse resp = buildTextResponse([
            content    : 'partial content...',
            _truncated : true,
            _truncatedNote: 'Response truncated at 12000 chars.'
        ])

        expect:
        McpController.extractOutcome(resp) == 'truncated'
    }
}
