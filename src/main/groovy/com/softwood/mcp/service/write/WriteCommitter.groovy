package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Paths

/**
 * WriteCommitter -- final pre-commit drift gate + atomic write.
 *
 * FS 0.9.0 / Phase 2  Resolves D11 (no final pre-commit drift check).
 *
 * The race window: WriteContext.load() reads the file and validates the hash at load time.
 * A second process (Syncthing, IntelliJ indexer, another FS instance) can still modify
 * the file between load() and atomicWrite(). Without this committer, that race is silent.
 *
 * WriteCommitter.commit() closes the window by:
 *   1. Re-reading the file hash immediately before atomicWrite.
 *   2. Comparing against ctx.hash (captured at load time).
 *   3. Returning a structured pre_commit error if changed -- file NOT written.
 *   4. Delegating to WriteUtils.atomicWrite() on success.
 *   5. Computing and returning the post-write hash.
 *
 * All write actions must call WriteCommitter.commit() instead of calling
 * WriteUtils.atomicWrite() directly.
 *
 * Scope for 0.9.0: hash re-read only.
 * Deferred to Phase 4: BasicFileAttributes capture (inode/mtime fast path),
 * POSIX permission preservation, fsync temp file before rename in strict mode.
 */
@CompileStatic
final class WriteCommitter {

    static class CommitResult {
        final String       newHash   // post-write hash (null on error)
        final McpResponse  error     // null on success

        private CommitResult(String newHash, McpResponse error) {
            this.newHash = newHash
            this.error   = error
        }

        static CommitResult ok(String newHash)       { new CommitResult(newHash, null) }
        static CommitResult err(McpResponse error)   { new CommitResult(null, error) }
        boolean succeeded() { error == null }
    }

    /**
     * Final pre-commit drift check + atomic write.
     *
     * @param ctx          WriteContext loaded at the start of the write action
     * @param resultBytes  bytes to write (CRLF-restored if needed, from ctx.toBytes())
     * @param requestId    MCP request ID for error responses
     * @return             CommitResult -- check succeeded() before using newHash
     */
    static CommitResult commit(WriteContext ctx, byte[] resultBytes, Object requestId) {
        // Pre-commit drift re-check: re-read file hash immediately before write.
        // Catches modifications by other processes after WriteContext.load() returned.
        byte[] currentBytes
        try {
            currentBytes = Files.readAllBytes(Paths.get(ctx.normalized))
        } catch (Exception e) {
            return CommitResult.err(McpResponse.toolError(requestId,
                "pre_commit: could not re-read file before write: ${e.message}. File NOT modified."))
        }

        String currentHash = WriteUtils.computeHash(currentBytes)
        if (currentHash != ctx.hash) {
            return CommitResult.err(McpResponse.toolError(requestId,
                groovy.json.JsonOutput.toJson([
                    success     : false,
                    error       : 'expectedHash mismatch: file changed after validation and before commit',
                    expectedHash: ctx.hash,
                    actualHash  : currentHash,
                    phase       : 'pre_commit'
                ])))
        }

        // Atomic write
        try {
            WriteUtils.atomicWrite(Paths.get(ctx.normalized), resultBytes)
        } catch (Exception e) {
            return CommitResult.err(McpResponse.toolError(requestId,
                "pre_commit: atomic write failed: ${e.message}. File may be in an inconsistent state."))
        }

        String newHash = WriteUtils.fileHash(Paths.get(ctx.normalized))
        return CommitResult.ok(newHash)
    }
}
