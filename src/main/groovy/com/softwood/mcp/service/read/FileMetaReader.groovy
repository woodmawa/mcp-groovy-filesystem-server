package com.softwood.mcp.service.read

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.promise.Promises
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.PathService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.DirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * FileMetaReader - handles info, summary, exists, project_root, allowed_dirs,
 * normalize, diff, checksum actions.
 *
 * v0.7.44 - extracted from FileReadService as part of read/ subpackage split.
 */
@Service
@Slf4j
@CompileStatic
class FileMetaReader extends AbstractFileService {

    @Value('${mcp.filesystem.read-chunk-threshold-kb:60}')
    int readChunkThresholdKb

    FileMetaReader(PathService pathService) {
        super(pathService)
    }

    McpResponse doInfo(String path, Object requestId) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        Path p = Paths.get(normalized)
        Map<String, Object> result = pathToMap(p)
        result.put('action', 'info')
        return textResponse(requestId, result)
    }

    /** Detect language from file extension */
    private static String detectLanguage(String path) {
        String ext = path.contains('.') ? path.substring(path.lastIndexOf('.') + 1).toLowerCase() : ''
        switch (ext) {
            case 'groovy': return 'groovy'
            case 'java'  : return 'java'
            case 'py'    : return 'python'
            case 'js'    : return 'javascript'
            case 'ts'    : return 'typescript'
            case ['kt','kts']: return 'kotlin'
            case 'md'    : return 'markdown'
            case 'json'  : return 'json'
            case ['yml','yaml']: return 'yaml'
            case 'xml'   : return 'xml'
            case 'gradle': return 'groovy/gradle'
            case 'txt'   : return 'text'
            default      : return ext ?: 'unknown'
        }
    }

    /**
     * stat - file metadata without content: path, exists, size, lines, lastModified, language, encoding.
     * Cheaper than info (no symlink/permissions overhead), richer than summary.
     * v0.8.3
     */
    McpResponse doStat(String path, Object requestId) {
        String normalized = validateFilePath(path)
        Path p = Paths.get(normalized)
        if (!Files.exists(p)) {
            return textResponse(requestId, [action: 'stat', path: normalized, exists: false])
        }
        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class)
        boolean isFile = attrs.isRegularFile()
        long lineCount = 0L
        if (isFile) {
            try {
                java.util.stream.Stream<String> ls = Files.lines(p)
                ls.withCloseable { lineCount = it.count() }
            } catch (Exception ignored) {}
        }
        return textResponse(requestId, ([
            action      : 'stat',
            path        : normalized,
            exists      : true,
            type        : isFile ? 'file' : (attrs.isDirectory() ? 'directory' : 'other'),
            size        : attrs.size(),
            lines       : isFile ? (int) lineCount : 0,
            lastModified: new java.util.Date(attrs.lastModifiedTime().toMillis()).toInstant().toString(),
            language    : isFile ? detectLanguage(normalized) : null,
            encoding    : isFile ? 'UTF-8' : null
        ] as Map<String, Object>).findAll { it.value != null } as Map<String, Object>)
    }

    McpResponse doSummary(String path, Object requestId) {
        String normalized = validateFilePath(path)
        Path p = Paths.get(normalized)
        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class)
        long lineCount = 0L
        try {
            java.util.stream.Stream<String> ls = Files.lines(p)
            ls.withCloseable { lineCount = it.count() }
        } catch (Exception ignored) {}
        return textResponse(requestId, [
            action: 'summary', path: normalized,
            size: attrs.size(), lines: (int) lineCount,
            lastModified: attrs.lastModifiedTime().toMillis()
        ])
    }

    McpResponse doExists(String path, Object requestId) {
        String normalized = pathService.normalizePath(path)
        boolean allowed   = isPathAllowed(normalized)
        boolean exists    = allowed && Files.exists(Paths.get(normalized))
        String type       = exists ? (Files.isDirectory(Paths.get(normalized)) ? 'directory' : 'file') : null
        return textResponse(requestId, [action: 'exists', path: normalized, exists: exists, type: type])
    }

    McpResponse doProjectRoot(Object requestId) {
        return textResponse(requestId, [action: 'project_root', projectRoot: getProjectRoot()])
    }

    McpResponse doAllowedDirs(Object requestId) {
        return textResponse(requestId, [action: 'allowed_dirs', allowedDirectories: allowedDirectories])
    }

    McpResponse doNormalize(String path, Object requestId) {
        Map<String, String> representations = pathService.getPathRepresentations(path)
        return textResponse(requestId, [action: 'normalize'] + representations)
    }

    McpResponse doDiff(String path, Map<String, Object> options, Object requestId) {
        String compareTo = options.compareTo as String
        if (!compareTo) return McpResponse.toolError(requestId, 'options.compareTo required for diff')
        String normA = validateFilePath(path)
        String normB = validateFilePath(compareTo)

        long sizeAKb = Files.size(Paths.get(normA)).intdiv(1024)
        long sizeBKb = Files.size(Paths.get(normB)).intdiv(1024)
        if (sizeAKb > readChunkThresholdKb || sizeBKb > readChunkThresholdKb) {
            return McpResponse.toolError(requestId, ("diff: one or both files exceed ${readChunkThresholdKb}KB threshold (${sizeAKb}KB vs ${sizeBKb}KB). Use grep or range for targeted comparison." as String))
        }

        com.softwood.mcp.promise.Promise<List<String>> readA = Promises.async({ -> new File(normA).readLines('UTF-8') } as Callable<List<String>>)
        com.softwood.mcp.promise.Promise<List<String>> readB = Promises.async({ -> new File(normB).readLines('UTF-8') } as Callable<List<String>>)
        List<List<String>> both = Promises.all([readA, readB]).get(30, TimeUnit.SECONDS)
        List<String> linesA = both[0]
        List<String> linesB = both[1]

        List<Map<String, Object>> diffs = []
        int maxA = linesA.size(), maxB = linesB.size()
        int maxLines = Math.max(maxA, maxB)

        for (int i = 0; i < maxLines && diffs.size() < 200; i++) {
            String a = i < maxA ? linesA[i] : null
            String b = i < maxB ? linesB[i] : null
            if (a != b) {
                diffs << ([
                    line: i + 1,
                    a   : a != null ? truncateAndSanitize(a) : '(no line)',
                    b   : b != null ? truncateAndSanitize(b) : '(no line)'
                ] as Map<String, Object>)
            }
        }

        return textResponse(requestId, [
            action   : 'diff',
            pathA    : normA, linesA: maxA,
            pathB    : normB, linesB: maxB,
            diffCount: diffs.size(),
            identical: diffs.isEmpty(),
            diffs    : diffs
        ])
    }

    McpResponse doChecksum(String path, Map<String, Object> options, Object requestId) {
        String normalized = validateFilePath(path)
        String algorithm  = options.algorithm as String ?: 'SHA-256'

        MessageDigest digest = MessageDigest.getInstance(algorithm)
        new File(normalized).withInputStream { InputStream is ->
            byte[] buf = new byte[8192]
            int read
            while ((read = is.read(buf)) != -1) {
                digest.update(buf, 0, read)
            }
        }
        String hex = digest.digest().encodeHex().toString()
        return textResponse(requestId, [action: 'checksum', path: normalized, algorithm: algorithm, checksum: hex])
    }

    // v0.8.1: directory listing - replaces execute powershell Get-ChildItem
    McpResponse doList(String path, Object requestId, Map<String, Object> options = [:]) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")

        Path dirPath = Paths.get(normalized)
        if (!Files.exists(dirPath)) {
            return McpResponse.toolError(requestId, "Path not found: ${sanitize(normalized)}")
        }
        if (!Files.isDirectory(dirPath)) {
            return McpResponse.toolError(requestId, "Not a directory: ${sanitize(normalized)}")
        }

        // Skip Windows reserved device names (create phantom files via GDK)
        Set<String> reserved = (['NUL','CON','PRN','AUX',
            'COM1','COM2','COM3','COM4','COM5','COM6','COM7','COM8','COM9',
            'LPT1','LPT2','LPT3','LPT4','LPT5','LPT6','LPT7','LPT8','LPT9'] as Set<String>)

        List<Map<String, Object>> dirs  = []
        List<Map<String, Object>> files = []
        DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)
        try {
            for (Path child : stream) {
                String name = child.fileName.toString()
                if (reserved.contains(name.toUpperCase(Locale.ROOT))) continue
                boolean isDir = Files.isDirectory(child)
                long size     = isDir ? 0L : Files.size(child)
                long lastMod  = Files.getLastModifiedTime(child).toMillis()
                Map<String, Object> entry = ([name: name, type: (isDir ? 'dir' : 'file') as String,
                                              size: size, lastModified: lastMod] as Map<String, Object>)
                if (isDir) dirs << entry else files << entry
            }
        } finally {
            stream.close()
        }

        Comparator<Map<String, Object>> byName = { Map<String, Object> a, Map<String, Object> b ->
            (a.name as String).compareToIgnoreCase(b.name as String)
        } as Comparator<Map<String, Object>>
        dirs.sort(byName)
        files.sort(byName)
        List<Map<String, Object>> entries = (dirs + files) as List<Map<String, Object>>

        // Compute listing hash over sorted name+type+mtime for cache validation
        String listingHash = computeListingHash(entries)

        // knownHash short-circuit: if caller passes the prior listing_hash and it matches, skip payload
        String knownHash = options?.knownHash as String
        if (knownHash && knownHash == listingHash) {
            log.debug('file_read list hash-gate HIT: {} unchanged ({})', normalized, listingHash)
            return textResponse(requestId, [
                unchanged    : true,
                listing_hash : listingHash,
                count        : entries.size(),
                _note        : 'Directory unchanged since last list — reuse entries from previous response.'
            ] as Map<String, Object>)
        }

        Map<String, Object> resp = [action: 'list', path: normalized,
                                    entries: entries, count: entries.size(),
                                    listing_hash: listingHash] as Map<String, Object>
        return textResponse(requestId, resp)
    }

    /** Compute a stable hash over sorted entry names+types+mtimes for listing cache validation. */
    private static String computeListingHash(List<Map<String, Object>> entries) {
        String key = entries.collect { Map<String, Object> e ->
            "${e.name}|${e.type}|${e.lastModified}"
        }.join('\n')
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance('SHA-256')
            byte[] digest = md.digest(key.bytes)
            return digest.encodeHex().toString().take(12)
        } catch (Exception ignored) {
            return Integer.toHexString(key.hashCode())
        }
    }

    /**
     * help - return section(s) from classpath USAGE.md.
     * options.topic = tool name (file_read|file_write|file_list|file_search|file_lifecycle|execute|server_lifecycle)
     * or 'all' for full document. Defaults to 'all'.
     * v0.8.3
     */
    McpResponse doHelp(Map<String, Object> options, Object requestId) {
        String topic = (options?.topic as String)?.trim()?.toLowerCase() ?: 'all'
        String content
        try {
            InputStream is = getClass().classLoader.getResourceAsStream('USAGE.md')
            if (!is) {
                return McpResponse.toolError(requestId, 'USAGE.md not found in classpath')
            }
            content = is.withCloseable { it.text }
        } catch (Exception e) {
            return McpResponse.toolError(requestId, "Failed to read USAGE.md: ${e.message}")
        }

        if (topic == 'all') {
            return textResponse(requestId, [action: 'help', topic: 'all', content: content])
        }

        // Find the section heading matching the topic (e.g. "## file_read")
        String heading = "## ${topic}"
        int start = content.indexOf(heading)
        if (start < 0) {
            // Try partial match
            String partial = content.split('\n').find { String line ->
                line.startsWith('## ') && line.toLowerCase().contains(topic)
            }
            if (partial) {
                start = content.indexOf(partial)
                heading = partial.trim()
            }
        }
        if (start < 0) {
            List<String> topics = content.split('\n').findAll { it.startsWith('## ') }.collect { it.substring(3).trim() }
            return McpResponse.toolError(requestId, "Topic '${topic}' not found. Available: ${topics.join(', ')}")
        }

        // Extract from this heading to the next same-level heading
        int nextHeading = content.indexOf('\n## ', start + 1)
        String section = nextHeading > 0 ? content.substring(start, nextHeading) : content.substring(start)

        return textResponse(requestId, [action: 'help', topic: topic, content: section.trim()])
    }
}
