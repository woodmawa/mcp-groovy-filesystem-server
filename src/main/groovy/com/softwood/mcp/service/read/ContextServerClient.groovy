package com.softwood.mcp.service.read

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ContextServerClient - async HTTP client for persisting file structure to
 * the context server's project knowledge layer.
 *
 * After every doStructure() scan (cache miss), the compact structure result is
 * asynchronously posted to the context server as a project group practice with:
 *   category=file-structure, title="<filename> [<hash>]", groupId from config.
 *
 * On next session (including post-reset), structure_load tool hint tells Claude
 * to read from context server instead of re-scanning the file.
 *
 * The context server URL and group are configurable. If the server is unavailable
 * or returns an error, the failure is logged at DEBUG level and silently ignored -
 * the filesystem server is never blocked by telemetry.
 *
 * v0.7.44 - WI5: automated structure persistence.
 */
@Service
@Slf4j
@CompileStatic
class ContextServerClient {

    @Value('${mcp.context-server.url:http://localhost:8082}')
    String contextServerUrl

    @Value('${mcp.context-server.structure-group-id:mcp-servers}')
    String structureGroupId

    @Value('${mcp.context-server.structure-persist-enabled:true}')
    boolean structurePersistEnabled

    /** Max entries to include in persisted structure JSON (keeps payloads small). */
    private static final int MAX_PERSIST_ENTRIES = 150

    private final ExecutorService asyncWriter = Executors.newSingleThreadExecutor { Runnable r ->
        Thread t = new Thread(r, 'context-server-persist')
        t.daemon = true
        t
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Persist a file's structure to the context server asynchronously.
     * Fire-and-forget: returns immediately, write happens on background thread.
     *
     * @param filePath   absolute normalised path of the scanned file
     * @param fileHash   12-char SHA-256 prefix (from StructureCache.getHash)
     * @param entries    structure entries (each has line, endLine, type, content)
     */
    void persistStructureAsync(String filePath, String fileHash, List<Map<String, Object>> entries) {
        if (!structurePersistEnabled) return

        asyncWriter.submit {
            try {
                doPersist(filePath, fileHash, entries)
            } catch (Exception e) {
                log.debug('ContextServerClient: persist failed (non-fatal): {}', e.message)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private void doPersist(String filePath, String fileHash, List<Map<String, Object>> entries) {
        String filename = new File(filePath).name
        String stem     = filename.contains('.') ? filename.tokenize('.').first() : filename

        // Compact structure: keep line, endLine (if present), type, and truncated content
        List<Map<String, Object>> compact = entries.take(MAX_PERSIST_ENTRIES).collect { Map<String, Object> e ->
            Map<String, Object> m = [line: e.line, type: e.type] as Map<String, Object>
            if (e.endLine) m.endLine = e.endLine
            String c = e.content as String
            if (c) m.content = c.length() > 120 ? c.substring(0, 120) : c
            m
        }

        String description = JsonOutput.toJson([
            hash   : fileHash,
            path   : filePath,
            count  : entries.size(),
            entries: compact
        ])

        // Truncate description to a safe size for the context server payload
        if (description.length() > 4000) {
            description = description.substring(0, 4000) + '...(truncated)'
        }

        Map<String, Object> body = [
            jsonrpc: '2.0',
            method : 'tools/call',
            id     : 1,
            params : [
                name     : 'context_write',
                arguments: [
                    scope      : 'project',
                    type       : 'practice',
                    action     : 'add',
                    groupId    : structureGroupId,
                    category   : 'file-structure',
                    title      : "${stem} [${fileHash}]" as String,
                    description: description,
                    tags       : ['file-structure', stem]
                ]
            ]
        ] as Map<String, Object>

        String jsonBody = JsonOutput.toJson(body)
        URL url = new URL("${contextServerUrl}/")
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        try {
            conn.requestMethod  = 'POST'
            conn.doOutput       = true
            conn.connectTimeout = 3000
            conn.readTimeout    = 5000
            conn.setRequestProperty('Content-Type', 'application/json')
            conn.outputStream.withWriter('UTF-8') { Writer w -> w.write(jsonBody) }

            int status = conn.responseCode
            if (status == 200) {
                log.debug('ContextServerClient: persisted structure for {} [{}]', filename, fileHash)
            } else {
                String respBody = conn.inputStream?.text ?: conn.errorStream?.text ?: ''
                log.debug('ContextServerClient: context server returned {} for {} - {}', status, filename, respBody.take(200))
            }
        } finally {
            conn.disconnect()
        }
    }

    void shutdown() {
        asyncWriter.shutdown()
        try {
            if (!asyncWriter.awaitTermination(3, TimeUnit.SECONDS)) asyncWriter.shutdownNow()
        } catch (InterruptedException e) {
            asyncWriter.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
