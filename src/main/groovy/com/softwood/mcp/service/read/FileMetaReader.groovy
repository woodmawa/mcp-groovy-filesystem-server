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
        if (!compareTo) return McpResponse.error(requestId, -32602, 'options.compareTo required for diff')
        String normA = validateFilePath(path)
        String normB = validateFilePath(compareTo)

        long sizeAKb = Files.size(Paths.get(normA)).intdiv(1024)
        long sizeBKb = Files.size(Paths.get(normB)).intdiv(1024)
        if (sizeAKb > readChunkThresholdKb || sizeBKb > readChunkThresholdKb) {
            return McpResponse.error(requestId, -32602,
                ("diff: one or both files exceed ${readChunkThresholdKb}KB threshold (${sizeAKb}KB vs ${sizeBKb}KB). Use grep or range for targeted comparison." as String))
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
    McpResponse doList(String path, Object requestId) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")

        Path dirPath = Paths.get(normalized)
        if (!Files.exists(dirPath)) {
            return McpResponse.error(requestId, -32602, "Path not found: ${sanitize(normalized)}")
        }
        if (!Files.isDirectory(dirPath)) {
            return McpResponse.error(requestId, -32602, "Not a directory: ${sanitize(normalized)}")
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

        return textResponse(requestId, ([action: 'list', path: normalized,
                                         entries: entries, count: entries.size()] as Map<String, Object>))
    }
}
