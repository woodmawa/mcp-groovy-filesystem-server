package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import groovy.transform.CompileStatic

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Paths

/**
 * WriteContext -- single file load with size cap, strict charset decoding, binary guard.
 *
 * FS 0.9.0 / PR 1.4  Resolves D1 (duplicated load sequence), D3 (normalizeAndCheckPath
 * duplication), D4 (inline hash in FilePatchService), D13 (no size/encoding guard).
 *
 * Usage pattern (replaces 7-line boilerplate in all three write methods):
 *
 *   WriteContext.LoadResult lr = WriteContext.load(path, options, svc, requestId)
 *   if (lr.error) return lr.error
 *   WriteContext ctx = lr.ctx
 *   // ... use ctx.content, ctx.hash, ctx.hasCrLf, ctx.encoding, ctx.normalized ...
 *   McpResponse err = ctx.checkHash(expectedHash, requestId)
 *   if (err) return err
 *
 * WriteContext is NOT a Spring bean -- constructed per-call by WriteContext.load().
 *
 * Max write size is 10MB by default; callers may pass options.maxWriteSizeMb to override
 * (used in tests to probe the guard at a testable size).
 */
@CompileStatic
final class WriteContext {

    // Default max file size for write actions (bytes). 10MB.
    static final long DEFAULT_MAX_WRITE_SIZE_BYTES = 10L * 1024L * 1024L

    final String  normalized   // validated, platform-normalised absolute path
    final String  encoding     // effective encoding ('UTF-8' default)
    final byte[]  rawBytes     // raw bytes (single read -- reuse everywhere)
    final boolean hasCrLf      // true if original file used CRLF
    final String  content      // LF-normalised string for matching
    final String  hash         // 12-char SHA-256 prefix of rawBytes

    private WriteContext(String normalized, String encoding,
                         byte[] rawBytes, boolean hasCrLf,
                         String content, String hash) {
        this.normalized = normalized
        this.encoding   = encoding
        this.rawBytes   = rawBytes
        this.hasCrLf    = hasCrLf
        this.content    = content
        this.hash       = hash
    }

    // -----------------------------------------------------------------------
    // LoadResult -- avoids Tuple2 destructuring issues under @CompileStatic (G1)
    // -----------------------------------------------------------------------

    static class LoadResult {
        final WriteContext ctx
        final McpResponse  error

        private LoadResult(WriteContext ctx, McpResponse error) {
            this.ctx   = ctx
            this.error = error
        }

        static LoadResult ok(WriteContext ctx)          { new LoadResult(ctx,  null) }
        static LoadResult err(McpResponse error)        { new LoadResult(null, error) }
    }

    // -----------------------------------------------------------------------
    // load() -- the single entry point used by all write methods
    // -----------------------------------------------------------------------

    /**
     * Load a WriteContext for the given path.
     *
     * Validates: write enabled, path allowed, file exists, file not a directory,
     *            file size <= maxWriteSizeBytes, encoding supported and valid,
     *            file is not binary (unless forceBinary=true).
     *
     * Returns LoadResult.ok(ctx) on success, LoadResult.err(toolError) on any failure.
     */
    static LoadResult load(String path, Map<String, Object> options,
                           AbstractFileService svc, Object requestId) {
        return load(path, options, svc, requestId, DEFAULT_MAX_WRITE_SIZE_BYTES)
    }

    @groovy.transform.CompileDynamic
    static LoadResult load(String path, Map<String, Object> options,
                           AbstractFileService svc, Object requestId,
                           long maxWriteSizeBytes) {

        // 1. Write-enabled guard
        try {
            svc.validateWriteEnabled()
        } catch (SecurityException e) {
            return LoadResult.err(McpResponse.toolError(requestId, e.message))
        }

        // 2. Path validation (normalise + allowed check)
        String normalized
        try {
            normalized = svc.validateFilePath(path)
        } catch (Exception e) {
            return LoadResult.err(McpResponse.toolError(requestId, e.message))
        }

        // 3. Size guard -- BEFORE readAllBytes to avoid OOM on huge files
        long fileSize
        try {
            fileSize = Files.size(Paths.get(normalized))
        } catch (Exception e) {
            return LoadResult.err(McpResponse.toolError(requestId,
                "file_too_large: could not stat file: ${e.message}"))
        }
        if (fileSize > maxWriteSizeBytes) {
            long mb = fileSize.intdiv(1_048_576L)
            long maxMb = maxWriteSizeBytes.intdiv(1_048_576L)
            return LoadResult.err(McpResponse.toolError(requestId,
                "file_too_large: file is ${mb}MB, exceeds max write size ${maxMb}MB. " +
                "Use action=patch for targeted line-range edits on large files."))
        }

        // 4. Encoding validation
        String encoding = (options.encoding as String) ?: 'UTF-8'
        if (!Charset.isSupported(encoding)) {
            return LoadResult.err(McpResponse.toolError(requestId,
                "invalid_encoding: '${encoding}' is not a supported charset."))
        }

        // 5. Read file exactly once
        byte[] rawBytes
        try {
            rawBytes = Files.readAllBytes(Paths.get(normalized))
        } catch (Exception e) {
            return LoadResult.err(McpResponse.toolError(requestId,
                "could not read file: ${e.message}"))
        }

        // 6. Strict decode -- reject malformed sequences rather than silently replacing (D13)
        String raw
        try {
            def dec = Charset.forName(encoding).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            raw = dec.decode(ByteBuffer.wrap(rawBytes)).toString()
        } catch (CharacterCodingException e) {
            return LoadResult.err(McpResponse.toolError(requestId,
                "invalid_encoding: file contains bytes invalid for ${encoding}. " +
                "If editing a binary file pass options.forceBinary:true (not supported for source files)."))
        }

        // 7. Binary-file guard: >5% non-printable bytes (excl. tab/LF/CR) => reject
        //    Overrideable with options.forceBinary:true
        boolean forceBinary = options.forceBinary as boolean ?: false
        if (!forceBinary) {
            int nonPrintable = 0
            // Use explicit for-loop -- byte[].count(Closure) not available under @CompileStatic (G2)
            for (byte b : rawBytes) {
                int i = b & 0xFF
                if (i < 32 && i != 9 && i != 10 && i != 13) nonPrintable++
            }
            if (nonPrintable > rawBytes.length * 0.05) {
                return LoadResult.err(McpResponse.toolError(requestId,
                    "binary_file: file appears to be binary (${nonPrintable} non-printable bytes). " +
                    "Replace/patch/multi_replace operate on text files only. " +
                    "Pass options.forceBinary:true to override."))
            }
        }

        boolean hasCrLf = raw.contains('\r\n')
        String  content  = raw.replace('\r\n', '\n').replace('\r', '\n')
        String  hash     = WriteUtils.computeHash(rawBytes)

        return LoadResult.ok(new WriteContext(normalized, encoding, rawBytes, hasCrLf, content, hash))
    }

    // -----------------------------------------------------------------------
    // checkHash() -- validate expectedHash against loaded hash
    // -----------------------------------------------------------------------

    /**
     * Check that expectedHash matches the hash loaded from disk.
     * expectedHash is mandatory -- absent/blank is a hard error (CT-EH-1).
     *
     * Returns toolError on mismatch or absent hash, null if OK.
     */
    McpResponse checkHash(String expectedHash, Object requestId) {
        if (!expectedHash) {
            return McpResponse.toolError(requestId,
                'options.expectedHash required. ' +
                'Read the target section first (file_read action=range or get_method) and pass ' +
                'the returned file_content_hash as options.expectedHash.')
        }
        if (hash != expectedHash) {
            return McpResponse.toolError(requestId,
                "expectedHash mismatch: file has changed since your last read " +
                "(expected ${expectedHash}, got ${hash}). " +
                "Re-read with action=range or action=get_method to get the current content_hash, then retry.")
        }
        return null
    }

    // -----------------------------------------------------------------------
    // toBytes() -- restore CRLF if needed and encode to byte array
    // -----------------------------------------------------------------------

    /**
     * Convert LF-normalised updated content back to bytes, restoring CRLF if the
     * original file used CRLF and shouldNormaliseLf() says to keep it.
     * Call once -- pass result directly to WriteUtils.atomicWrite().
     */
    byte[] toBytes(String updated) {
        String out = (hasCrLf && !WriteUtils.shouldNormaliseLf(Paths.get(normalized)))
            ? updated.replace('\n', '\r\n')
            : updated
        return out.getBytes(encoding)
    }
}
