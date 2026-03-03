package com.softwood.mcp.service

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * ChunkBufferService — manages chunked write and read sessions.
 *
 * Problem: stdio transport has a ~700 KB JSON-RPC payload limit.
 * Solution: split large content into ≤400 KB chunks keyed by sessionId.
 *
 * Write flow:
 *   1. Claude calls file_write action=chunk_write with sessionId, chunkIndex, totalChunks, content
 *   2. All chunks are buffered here (ConcurrentSkipListMap preserves order)
 *   3. Claude calls file_write action=finalise_write — chunks are reassembled and written to disk
 *
 * Read flow (large files):
 *   1. file_read returns sessionId + totalChunks when file exceeds threshold
 *   2. Claude calls file_read action=chunk_read with sessionId + chunkIndex to page through content
 *   3. Claude calls file_read action=finalise_read to signal done and free buffer
 *
 * Sessions expire after configuredTtlMinutes (default 30) if not finalised.
 *
 * v0.0.7 — Phase 1 Foundation
 */
@Service
@Slf4j
@CompileStatic
class ChunkBufferService {

    /** Maximum chunk size (bytes) — leave headroom under 700 KB transport limit */
    static final int MAX_CHUNK_BYTES = 400 * 1024  // 400 KB

    /** Session TTL — auto-expire abandoned sessions */
    static final long SESSION_TTL_MINUTES = 30L

    // sessionId -> ordered map of chunkIndex -> chunk content
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Integer, String>> writeSessions =
        new ConcurrentHashMap<>()

    // sessionId -> ordered map of chunkIndex -> chunk content (pre-chunked file reads)
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<Integer, String>> readSessions =
        new ConcurrentHashMap<>()

    // sessionId -> expiry timestamp (millis)
    private final ConcurrentHashMap<String, Long> sessionExpiry = new ConcurrentHashMap<>()

    private ScheduledExecutorService cleanupScheduler

    @PostConstruct
    void init() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
        cleanupScheduler.scheduleAtFixedRate(this.&sweepExpiredSessions, 5, 5, TimeUnit.MINUTES)
        log.info("ChunkBufferService initialised — max chunk size: ${MAX_CHUNK_BYTES / 1024}KB, TTL: ${SESSION_TTL_MINUTES}min")
    }

    @PreDestroy
    void shutdown() {
        cleanupScheduler?.shutdown()
    }

    // -----------------------------------------------------------------------
    // Write session API
    // -----------------------------------------------------------------------

    /**
     * Accept a single chunk for a write session.
     * Returns the number of chunks received so far.
     */
    int receiveWriteChunk(String sessionId, int chunkIndex, String content) {
        refreshExpiry(sessionId)
        ConcurrentSkipListMap<Integer, String> chunks = writeSessions.computeIfAbsent(sessionId) {
            new ConcurrentSkipListMap<Integer, String>()
        }
        chunks.put(chunkIndex, content)
        log.debug("Write chunk received: session={}, index={}, size={}B", sessionId, chunkIndex, content?.length() ?: 0)
        return chunks.size()
    }

    /**
     * S-I4 FIX: Return ordered chunks for streaming write — never joins into a single String.
     * Caller iterates chunks and writes each directly to a BufferedWriter.
     * Removes the session on return. Throws on missing session or count mismatch.
     */
    Collection<String> getWriteChunksAndRelease(String sessionId, int expectedTotal) {
        ConcurrentSkipListMap<Integer, String> chunks = writeSessions.remove(sessionId)
        sessionExpiry.remove(sessionId)

        if (!chunks) {
            throw new IllegalStateException("No write session found for sessionId='${sessionId}'")
        }
        if (chunks.size() != expectedTotal) {
            throw new IllegalStateException(
                "Write session '${sessionId}': expected ${expectedTotal} chunks, got ${chunks.size()}"
            )
        }
        // ConcurrentSkipListMap.values() is sorted by key — chunks returned in index order
        log.info("Write session '{}' chunks retrieved for streaming — {} chunks", sessionId, expectedTotal)
        return chunks.values()
    }

    /** @deprecated Use getWriteChunksAndRelease() for zero-join streaming write. */
    @Deprecated
    String finaliseWrite(String sessionId, int expectedTotal) {
        return getWriteChunksAndRelease(sessionId, expectedTotal).join('')
    }


    /**
     * Abort and discard a write session.
     */
    void abortWriteSession(String sessionId) {
        writeSessions.remove(sessionId)
        sessionExpiry.remove(sessionId)
        log.debug("Write session '{}' aborted", sessionId)
    }

    // -----------------------------------------------------------------------
    // Read session API
    // -----------------------------------------------------------------------

    /**
     * Store pre-chunked file content for paged retrieval.
     * Returns the sessionId and total chunk count.
     */
    Map<String, Object> createReadSession(String sessionId, String fullContent) {
        List<String> chunks = splitIntoChunks(fullContent)
        ConcurrentSkipListMap<Integer, String> chunkMap = new ConcurrentSkipListMap<>()
        chunks.eachWithIndex { String chunk, int i -> chunkMap.put(i, chunk) }

        readSessions.put(sessionId, chunkMap)
        refreshExpiry(sessionId)

        log.info("Read session '{}' created — {} chunks for {}B content", sessionId, chunks.size(), fullContent.length())
        return [sessionId: sessionId, totalChunks: chunks.size(), chunkSize: MAX_CHUNK_BYTES] as Map<String, Object>
    }

    /**
     * S-I1 FIX: Store a single chunk for a streamed read session (called per-chunk during streaming).
     * Used by FileReadService.streamFileToChunks() to avoid loading the full file into memory.
     */
    void storeReadChunk(String sessionId, int chunkIndex, String content) {
        ConcurrentSkipListMap<Integer, String> chunkMap = readSessions.computeIfAbsent(sessionId) {
            new ConcurrentSkipListMap<Integer, String>()
        }
        chunkMap.put(chunkIndex, content)
        log.debug("Streamed read chunk stored: session={}, index={}, size={}B", sessionId, chunkIndex, content?.length() ?: 0)
    }

    /**
     * S-I1 FIX: Register TTL for a session whose chunks were stored via storeReadChunk().
     * Must be called after all chunks have been stored to activate expiry tracking.
     */
    void registerStreamedReadSession(String sessionId, int totalChunks) {
        refreshExpiry(sessionId)
        log.info("Streamed read session '{}' registered  {} chunks", sessionId, totalChunks)
    }

    /**
     * Retrieve a single chunk from a read session.
     */
    String getReadChunk(String sessionId, int chunkIndex) {
        ConcurrentSkipListMap<Integer, String> chunks = readSessions.get(sessionId)
        if (!chunks) {
            throw new IllegalStateException("No read session found for sessionId='${sessionId}'")
        }
        String chunk = chunks.get(chunkIndex)
        if (chunk == null) {
            throw new IllegalArgumentException(
                "Read session '${sessionId}': chunk ${chunkIndex} not found (total: ${chunks.size()})"
            )
        }
        refreshExpiry(sessionId)
        return chunk
    }

    /**
     * Discard a read session after the caller has consumed all chunks.
     */
    void finaliseRead(String sessionId) {
        readSessions.remove(sessionId)
        sessionExpiry.remove(sessionId)
        log.debug("Read session '{}' finalised and freed", sessionId)
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Split content into chunks of MAX_CHUNK_BYTES.
     * Splits on UTF-16 char count (approx — JSON encoding will be slightly larger).
     */
    static List<String> splitIntoChunks(String content) {
        if (!content) return ['']

        List<String> chunks = []
        int total = content.length()
        int start = 0

        while (start < total) {
            int end = Math.min(start + MAX_CHUNK_BYTES, total)
            chunks << content.substring(start, end)
            start = end
        }

        return chunks
    }

    /**
     * Check whether content exceeds the chunk threshold.
     */
    static boolean needsChunking(String content) {
        return content && content.length() > MAX_CHUNK_BYTES
    }

    /**
     * Generate a unique session ID.
     */
    static String newSessionId() {
        return "chunk-${UUID.randomUUID().toString().replace('-', '').substring(0, 12)}"
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    Map<String, Object> getStats() {
        return [
            activeWriteSessions: writeSessions.size(),
            activeReadSessions: readSessions.size(),
            maxChunkBytes: MAX_CHUNK_BYTES,
            sessionTtlMinutes: SESSION_TTL_MINUTES
        ] as Map<String, Object>
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    private void refreshExpiry(String sessionId) {
        sessionExpiry.put(sessionId, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(SESSION_TTL_MINUTES))
    }

    private void sweepExpiredSessions() {
        long now = System.currentTimeMillis()
        int swept = 0

        List<String> expired = sessionExpiry.findAll { k, v -> v < now }.keySet().toList()
        expired.each { String sid ->
            writeSessions.remove(sid)
            readSessions.remove(sid)
            sessionExpiry.remove(sid)
            swept++
        }

        if (swept > 0) {
            log.info("ChunkBufferService: swept {} expired sessions", swept)
        }
    }
}
