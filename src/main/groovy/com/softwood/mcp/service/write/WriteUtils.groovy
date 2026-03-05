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
        if (parent && !Files.exists(parent)) {
            throw new java.nio.file.NoSuchFileException(
                parent.toString(), null,
                "Parent directory does not exist: ${parent}")
        }
        Path tmp = target.resolveSibling(target.fileName.toString() + '.tmp')
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.ATOMIC_MOVE)
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(tmp) } catch (Exception ignored) {}
            throw e
        }
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
