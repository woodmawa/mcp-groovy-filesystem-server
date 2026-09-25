package com.softwood.mcp.service

import com.softwood.mcp.ProcessIdentity
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier

/**
 * Align kit 2026-09-25 -- ONE source for every FS tool's top-level description, served live.
 *
 * <p>The source is CS help_sections, key {@code tool_desc_<tool>} (file_write in verbose description
 * mode reads {@code tool_desc_file_write_verbose}), fetched through {@link ContextServerClient#getHelpSection}
 * -- the same context_read scope=help call FileReadService has used for tool_desc_file_read since
 * 0.8.70. Parameter and enum text stays in source: it changes only with code.</p>
 *
 * <ul>
 *   <li><b>Served live</b>: {@link #describe} caches each row for {@link #ttlMs} (45 s). On expiry it
 *       re-reads the row. A failed or empty read serves the LAST GOOD row text, else the SOURCE default
 *       the handler declares -- never an empty description, never an exception out of tools/list. A
 *       failed read is itself cached for the TTL so a down CS costs at most one attempt per tool per TTL.</li>
 *   <li><b>Announced</b>: a daemon poll ({@link #pollMs}, 60 s; first run 5 s after start) re-reads every
 *       row regardless of TTL, and when the hash of the served set changes it runs the change listeners
 *       (StdioMcpServer writes notifications/tools/list_changed; HttpMcpController pushes it on open SSE
 *       streams).</li>
 *   <li><b>Measured</b>: the same poll reports the served set to CS -- context_lifecycle
 *       action=record_tool_descriptions {server:'fs', owner_key, transport, descriptions:[{tool, hash}]}
 *       -- at the first poll and whenever the set changes. A refusal (CS down, or a CS that does not
 *       know the action yet) is logged and retried on the next poll; it never touches tools/list.</li>
 * </ul>
 */
@Service
@Slf4j
@CompileStatic
class ToolDescriptionRegistry {

    static final String SERVER = 'fs'
    static final String REPORT_ACTION = 'record_tool_descriptions'
    static final String SECTION_PREFIX = 'tool_desc_'

    @Autowired(required = false)
    ContextServerClient contextServerClient

    /** 'stdio' in the per-chat MCPB process, 'http' in the shared companion. */
    @Value('${mcp.mode:http}')
    String transportMode = 'http'

    /** Same switch FileWriteService uses to choose its compact or verbose text. */
    @Value('${mcp.tools.description-mode:compact}')
    String descriptionMode = 'compact'

    @Value('${mcp.tool-descriptions.ttl-ms:45000}')
    long ttlMs = 45_000L

    @Value('${mcp.tool-descriptions.poll-ms:60000}')
    long pollMs = 60_000L

    @Value('${mcp.tool-descriptions.poll-enabled:true}')
    boolean pollEnabled = true

    /** Test seam: the clock the TTL is measured on. */
    LongSupplier clock = { -> System.currentTimeMillis() } as LongSupplier

    private static final class Entry {
        final String text          // null = the last read failed and nothing good was ever read
        final long fetchedAt
        Entry(String text, long fetchedAt) { this.text = text; this.fetchedAt = fetchedAt }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<String, Entry>()
    /** tool -> source DEFAULT text, in tools/list order. Registered by McpController. */
    private final Map<String, String> defaults = Collections.synchronizedMap(new LinkedHashMap<String, String>())
    private final List<Runnable> listeners = new CopyOnWriteArrayList<Runnable>()
    private volatile Map<String, String> lastPolledHashes = null
    private volatile Map<String, String> lastReportedHashes = null
    private volatile boolean refusalLogged = false
    private ScheduledExecutorService poller

    void setContextServerClient(ContextServerClient c) { this.contextServerClient = c }

    static String sectionKeyFor(String tool, String mode) {
        if (tool == 'file_write' && mode == 'verbose') return SECTION_PREFIX + 'file_write_verbose'
        return SECTION_PREFIX + tool
    }

    /** 12-hex SHA-256 of the exact description string (UTF-8) -- the unit CS stores and compares. */
    static String hash12(String s) {
        byte[] d = MessageDigest.getInstance('SHA-256').digest((s ?: '').getBytes(StandardCharsets.UTF_8))
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < 6; i++) sb.append(String.format('%02x', d[i] & 0xff))
        return sb.toString()
    }

    String transport() { transportMode == 'stdio' ? 'stdio' : 'http' }

    void registerDefault(String tool, String text) {
        if (tool && text) defaults.put(tool, text)
    }

    void addChangeListener(Runnable r) { if (r != null) listeners.add(r) }

    /** The description to serve now: fresh cached row, else a re-read, else last good, else default. */
    String describe(String tool, String defaultText) {
        String fallback = defaultText ?: defaults.get(tool) ?: ''
        long now = clock.getAsLong()
        Entry e = cache.get(tool)
        if (e != null && now - e.fetchedAt < ttlMs) return e.text ?: fallback
        return refresh(tool, e, now) ?: fallback
    }

    /** Reads the row; stores it, or keeps the last good text with a new timestamp. Never throws. */
    private String refresh(String tool, Entry previous, long now) {
        String fetched = null
        try {
            fetched = contextServerClient?.getHelpSection(sectionKeyFor(tool, descriptionMode))
        } catch (Exception ex) {
            log.debug('tool description read failed for {} (serving fallback): {}', tool, ex.message)
        }
        String text = fetched?.trim() ? fetched : previous?.text
        cache.put(tool, new Entry(text, now))
        return text
    }

    /** tools/list: copies of the definitions with the live top-level description. Param text untouched. */
    List<Map<String, Object>> applyTo(List<Map<String, Object>> defs) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(defs.size())
        for (Map<String, Object> toolDef : defs) {
            String name = toolDef.get('name') as String
            Map<String, Object> copy = new LinkedHashMap<String, Object>(toolDef)
            copy.put('description', describe(name, toolDef.get('description') as String))
            out.add(copy)
        }
        return out
    }

    /** What this process serves right now, per tool (reads through the TTL cache). */
    Map<String, String> servedHashes() {
        Map<String, String> out = new LinkedHashMap<String, String>()
        List<String> tools
        synchronized (defaults) { tools = new ArrayList<String>(defaults.keySet()) }
        for (String tool : tools) out.put(tool, hash12(describe(tool, defaults.get(tool))))
        return out
    }

    /** One poll tick: re-read every row, notify on change, report on change. Never throws. */
    void pollOnce() {
        try {
            long now = clock.getAsLong()
            List<String> tools
            synchronized (defaults) { tools = new ArrayList<String>(defaults.keySet()) }
            for (String tool : tools) refresh(tool, cache.get(tool), now)
            Map<String, String> hashes = servedHashes()

            Map<String, String> previous = lastPolledHashes
            lastPolledHashes = hashes
            if (previous != null && previous != hashes) {
                log.info('tool descriptions changed ({}) -- announcing tools/list_changed', changedTools(previous, hashes))
                for (Runnable r : listeners) {
                    try { r.run() } catch (Exception ex) { log.debug('list_changed listener failed: {}', ex.message) }
                }
            }
            report(hashes)
        } catch (Exception ex) {
            log.debug('tool description poll failed (ignored): {}', ex.message)
        }
    }

    private void report(Map<String, String> hashes) {
        if (hashes.isEmpty() || hashes == lastReportedHashes || contextServerClient == null) return
        List<Map<String, String>> payload = []
        hashes.each { String tool, String h -> payload << ([tool: tool, hash: h] as Map<String, String>) }
        boolean ok = false
        try {
            ok = contextServerClient.recordToolDescriptions(SERVER, ProcessIdentity.OWNER_KEY, transport(), payload)
        } catch (Exception ex) {
            log.debug('{} threw (ignored): {}', REPORT_ACTION, ex.message)
        }
        if (ok) {
            lastReportedHashes = hashes
            refusalLogged = false
        } else if (!refusalLogged) {
            refusalLogged = true
            log.info('context_lifecycle action={} not accepted (CS down or action absent) -- ignored, retried each poll',
                REPORT_ACTION)
        }
    }

    private static List<String> changedTools(Map<String, String> a, Map<String, String> b) {
        Set<String> keys = new LinkedHashSet<String>(a.keySet())
        keys.addAll(b.keySet())
        return keys.findAll { String k -> a.get(k) != b.get(k) } as List<String>
    }

    @PostConstruct
    void startPolling() {
        if (!pollEnabled || poller != null) return
        poller = Executors.newSingleThreadScheduledExecutor { Runnable r ->
            Thread t = new Thread(r, 'fs-tooldesc-poll')
            t.daemon = true
            t
        }
        poller.scheduleWithFixedDelay({ -> pollOnce() } as Runnable, Math.min(5_000L, pollMs), pollMs,
            TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    void stopPolling() {
        poller?.shutdownNow()
    }
}
