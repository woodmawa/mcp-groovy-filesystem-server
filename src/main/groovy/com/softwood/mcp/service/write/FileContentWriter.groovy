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
        log.debug('doWrite: entry path={} options={}', path, options?.keySet())
        String normalized = normalizeAndCheckPath(path)
        String encoding   = options.encoding as String ?: 'UTF-8'
        boolean backup    = options.get('backup')  ? Boolean.valueOf(options.get('backup').toString())  : false
        boolean mkdirs    = options.get('mkdirs') != null ? Boolean.valueOf(options.get('mkdirs').toString()) : true
        String body       = content ?: ''

        Path target = Paths.get(normalized)
        // createDirectories is also called inside WriteUtils.atomicWrite for reliability on Windows.
        // Keeping it here too so log.debug confirms the intent before the write.
        Path parentDir = target.parent
        if (mkdirs && parentDir != null) {
            try {
                Files.createDirectories(parentDir)
                log.debug('doWrite: ensured parent dirs for {}', parentDir)
            } catch (IOException e) {
                log.error('doWrite: failed to create parent directories for {}: {}', parentDir, e.message)
                return textResponse(requestId, [success: false, error: "Failed to create parent directories: ${e.message}"] as Map<String, Object>)
            }
        }
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
        boolean mkdirs    = options.get('mkdirs') != null ? Boolean.valueOf(options.get('mkdirs').toString()) : true
        byte[] bytes      = (content ?: '').getBytes(encoding)

        Path target = Paths.get(normalized)
        if (mkdirs && target.parent) {
            try {
                Files.createDirectories(target.parent)
            } catch (IOException e) {
                log.error('doAppend: failed to create parent directories for {}: {}', target.parent, e.message)
                return textResponse(requestId, [success: false, error: "Failed to create parent directories: ${e.message}"] as Map<String, Object>)
            }
        }

        new java.io.RandomAccessFile(normalized, 'rw').withCloseable { java.io.RandomAccessFile raf ->
            raf.channel.lock().withCloseable {
                raf.seek(raf.length())
                raf.write(bytes)
            }
        }
        log.debug("append: {} bytes -> {} (locked)", bytes.length, normalized)

        String hash = WriteUtils.fileHash(target)
        // FS 0.9.6 / fix #142: soft warning when appending to a code file.
        // Append has no structural safety -- it can orphan braces that StructuralGuard
        // will then block every repair attempt on. Advisory only; suppressible.
        boolean suppressWarn = options.containsKey('suppressCodeAppendWarning')
                                ? (options.suppressCodeAppendWarning as boolean) : false
        String codeAppendWarning = (!suppressWarn && StructuralGuard.isCodeFile(normalized))
            ? 'action=append on a code file may corrupt brace structure. ' +
              'Prefer action=replace or server_transform add_method. ' +
              'Set options.suppressCodeAppendWarning=true to suppress this warning.'
            : null
        if (codeAppendWarning) log.warn('FileContentWriter.doAppend: code file append on {}', normalized)
        if (isWriteCompact(options)) {
            Map<String, Object> resp = [success: true, content_hash: hash, file_content_hash: hash]
            if (codeAppendWarning) resp.code_append_warning = codeAppendWarning
            return textResponse(requestId, resp)
        }
        Map<String, Object> resp = [
            action: 'append', path: normalized,
            appended: bytes.length, success: true,
            content_hash: hash, file_content_hash: hash
        ]
        if (codeAppendWarning) resp.code_append_warning = codeAppendWarning
        return textResponse(requestId, resp)
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
