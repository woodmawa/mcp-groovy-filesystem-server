package com.softwood.mcp.service.write

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * WriteUtils - static utility methods shared by all write sub-services.
 *
 * No Spring dependencies - pure static helpers for atomic writes, backups,
 * line-ending detection, hashing, and occurrence counting.
 *
 * v0.7.44 - extracted from FileWriteService as part of write/ subpackage split.
 */
@Slf4j
@CompileStatic
class WriteUtils {

    static final Set<String> LF_EXTENSIONS = [
        'groovy', 'java', 'kt', 'kts', 'scala',
        'gradle', 'properties', 'yml', 'yaml', 'toml',
        'xml', 'json', 'md', 'txt', 'sh', 'py',
        'js', 'ts', 'css', 'html', 'sql'
    ] as Set<String>

    /**
     * Returns true for text source files that should always be written with LF.
     * This eliminates Windows CRLF creep for source files across all write actions.
     */
    static boolean shouldNormaliseLf(Path target) {
        String name = target.fileName.toString()
        int dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return LF_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase())
    }

    /**
     * Atomic write: write bytes to a sibling .tmp file then rename atomically.
     * Tries ATOMIC_MOVE first; falls back to REPLACE_EXISTING on unsupported filesystems.
     * Cleans up .tmp on failure so stray temp files are never left behind.
     */
    static void atomicWrite(Path target, byte[] bytes) {
        Path parent = target.parent
        if (parent) {
            Files.createDirectories(parent)
            // On Windows, NTFS directory creation may not be immediately visible
            // even after createDirectories returns. Poll until the directory is
            // actually visible to the filesystem before attempting any write.
            int waited = 0
            while (!Files.exists(parent) && waited < 2000) {
                Thread.sleep(100)
                waited += 100
            }
            if (!Files.exists(parent)) {
                throw new IOException("Directory still does not exist after 2s: ${parent}")
            }
        }
        Path tmp = target.resolveSibling(target.fileName.toString() + '.tmp')
        try {
            // Use legacy IO (FileOutputStream) rather than NIO Files.write —
            // avoids NIO path resolution issues on freshly-created Windows dirs.
            tmp.toFile().withOutputStream { it.write(bytes) }
            moveIntoPlace(tmp, target)
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp) } catch (Exception ignored) {}
            throw e
        }
    }

    /** FS 0.9.25: attempts, and the linear backoff between them. 25+50+...+225 = 1125ms. */
    private static final int  MOVE_ATTEMPTS   = 10
    private static final long MOVE_BACKOFF_MS = 25L

    /**
     * FS 0.9.25 -- the rename, with a bounded retry, because on Windows a sharing violation is
     * usually somebody else's handle and it is gone milliseconds later.
     *
     * <p>This was carried for some time as "the FS suite is flaky": exactly one spec failed per
     * full run and a different one each time, while every one of them passed in isolation. It was
     * never flakiness. {@code Files.move(..., ATOMIC_MOVE)} throws {@code AccessDeniedException}
     * whenever ANY open handle exists on the target -- Defender scanning the freshly created
     * {@code .tmp}, the search indexer, an editor, a sibling thread -- with the message
     * {@code "<tmp> -> <target>"} and a null reason. Reproduced deliberately rather than inferred:
     * hold a {@code RandomAccessFile} on the target and the move fails with exactly that shape;
     * close it and the same move succeeds. The CT-18 failure text carried that shape verbatim.
     *
     * <p>Only {@code AtomicMoveNotSupportedException} was caught, so the transient case propagated
     * as permanent, the caller's {@code catch} deleted the {@code .tmp}, and <b>the write was
     * lost</b> while being reported as a failed write. Under a 350-test suite hammering %TEMP% the
     * odds of hitting one such window per run are high; in a single spec they are nearly nil.
     * That asymmetry is what made a real data-losing defect look like test noise.
     *
     * <p>Bounded on purpose. A retry loop that turned a permanent failure into a hang, or into
     * silence, would be worse than the defect: if the file genuinely cannot be written the caller
     * has to be told, and told soon. {@code NoSuchFileException} is never retried -- a missing
     * source cannot appear -- and the last exception is rethrown unchanged so the caller still
     * sees the real reason.
     */
    private static void moveIntoPlace(Path tmp, Path target) {
        Exception last = null
        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                // A filesystem capability, not contention. Not retryable, and not an error.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (java.nio.file.NoSuchFileException e) {
                throw e
            } catch (java.nio.file.FileSystemException e) {
                last = e
                if (attempt < MOVE_ATTEMPTS - 1) {
                    try { Thread.sleep(MOVE_BACKOFF_MS * (attempt + 1)) }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e }
                }
            }
        }
        throw last
    }

    static void makeBackup(Path path) {
        if (Files.exists(path)) {
            Path backup = Paths.get("${path}.backup")
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 12-char SHA-256 prefix of raw bytes. Used for drift-guard validation. */
    static String computeHash(byte[] bytes) {
        (java.security.MessageDigest.getInstance('SHA-256')
            .digest(bytes).encodeHex().toString() as String)[0..11]
    }

    /** Hash of a file already written to disk (post-write hash for response). */
    static String fileHash(Path p) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance('SHA-256')
            new FileInputStream(p.toFile()).withCloseable { InputStream is ->
                byte[] buf = new byte[8192]
                int read
                while ((read = is.read(buf)) != -1) md.update(buf, 0, read)
            }
            return (md.digest().encodeHex().toString() as String)[0..11]
        } catch (Exception ignored) { return null }
    }

    static int countOccurrences(String text, String target) {
        if (!text || !target) return 0
        int count = 0, idx = 0
        while ((idx = text.indexOf(target, idx)) != -1) { count++; idx++ }
        return count
    }
}