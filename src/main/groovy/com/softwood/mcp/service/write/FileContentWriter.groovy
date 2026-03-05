package com.softwood.mcp.service.write

import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.AbstractFileService
import com.softwood.mcp.service.PathService
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * FileContentWriter - handles write and append actions.
 *
 * v0.7.44 - extracted from FileWriteService as part of write/ subpackage split.
 */
@Service
@Slf4j
@CompileStatic
class FileContentWriter extends AbstractFileService {

    FileContentWriter(PathService pathService) {
        super(pathService)
    }

    McpResponse doWrite(String path, String content, Map<String, Object> options, Object requestId) {
        String normalized = normalizeAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean backup    = options.backup as boolean ?: false
        boolean mkdirs    = options.mkdirs as boolean ?: true
        String body       = content ?: ''

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)
        if (backup && Files.exists(target)) WriteUtils.makeBackup(target)

        String finalBody = WriteUtils.shouldNormaliseLf(target)
            ? body.replace('\r\n', '\n').replace('\r', '\n')
            : body
        WriteUtils.atomicWrite(target, finalBody.getBytes(encoding))
        log.info("write: {} bytes -> {} (atomic, endings: {})", finalBody.length(), normalized,
            WriteUtils.shouldNormaliseLf(target) ? 'LF' : 'preserved')

        String hash = WriteUtils.fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash, file_content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'write', path: normalized,
            size: body.length(), success: true,
            content_hash: hash, file_content_hash: hash
        ])
    }

    McpResponse doAppend(String path, String content, Map<String, Object> options, Object requestId) {
        String normalized = normalizeAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean mkdirs    = options.mkdirs as boolean ?: true
        byte[] bytes      = (content ?: '').getBytes(encoding)

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) Files.createDirectories(target.parent)

        new java.io.RandomAccessFile(normalized, 'rw').withCloseable { java.io.RandomAccessFile raf ->
            raf.channel.lock().withCloseable {
                raf.seek(raf.length())
                raf.write(bytes)
            }
        }
        log.debug("append: {} bytes -> {} (locked)", bytes.length, normalized)

        String hash = WriteUtils.fileHash(target)
        if (isWriteCompact(options)) {
            return textResponse(requestId, [success: true, content_hash: hash, file_content_hash: hash])
        }
        return textResponse(requestId, [
            action: 'append', path: normalized,
            appended: bytes.length, success: true,
            content_hash: hash, file_content_hash: hash
        ])
    }

    // -----------------------------------------------------------------------
    // Helper - kept private to this service
    // -----------------------------------------------------------------------

    private String normalizeAndCheckPath(String path) {
        String normalized = pathService.normalizePath(path)
        if (!isPathAllowed(normalized)) throw new SecurityException("Path not allowed: ${sanitize(normalized)}")
        return normalized
    }
}
