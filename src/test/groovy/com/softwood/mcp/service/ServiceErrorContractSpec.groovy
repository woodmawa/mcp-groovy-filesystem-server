package com.softwood.mcp.service

import spock.lang.Specification

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern

/**
 * ServiceErrorContractSpec -- recursive static analysis contract test (v0.8.52).
 *
 * CONTRACT: no service-layer handler may return a protocol-level MCP error object.
 * Service handlers must use McpResponse.toolError() which produces isError:true content
 * that Claude Desktop renders. Protocol-level errors (McpResponse.protocolError()) are
 * reserved exclusively for McpController and HttpMcpController (protocol dispatch layer).
 *
 * WHY THIS EXISTS:
 *   v0.8.48 claimed to fix this but missed 14 service files (92 call sites) in service
 *   subpackages (write/, read/, office/). The gap was only found by a Python bulk scan in
 *   v0.8.50. v0.8.52 renames error() -> protocolError() so the compiler rejects accidental
 *   use of the old name -- but this spec adds belt-and-braces: it asserts that protocolError()
 *   only appears in controller files, nowhere else.
 *
 * WHAT IS SCANNED:
 *   All .groovy files under src/main/groovy, recursively.
 *   Uses Files.walkFileTree so ALL subdirectories are covered:
 *     service/, service/write/, service/read/, service/office/, service/transform/
 *     model/, controller/, support/, config/ -- everything.
 *
 * CONTROLLER EXCLUSIONS (protocolError is legitimate there):
 *   McpController.groovy, HttpMcpController.groovy
 *
 * TWO PATTERNS CHECKED:
 *   1. McpResponse.error( -- the old removed method name (any occurrence is a compile error
 *      but this catches copy-paste from external sources before compilation)
 *   2. McpResponse.protocolError( in non-controller files -- wrong layer usage
 */
@groovy.transform.CompileDynamic
class ServiceErrorContractSpec extends Specification {

    // Pattern 1: old method name - should not exist anywhere post-rename
    static final Pattern OLD_ERROR_PATTERN = Pattern.compile(
        /McpResponse\.error\s*\(/)

    // Pattern 2: protocolError in service files - only controllers may call this
    static final Pattern PROTOCOL_ERROR_IN_SERVICE = Pattern.compile(
        /McpResponse\.protocolError\s*\(/)

    // Controller files where protocolError IS legitimate
    static final Set<String> CONTROLLER_FILES = [
        'McpController.groovy',
        'HttpMcpController.groovy'
    ] as Set

    // Resolve project root robustly -- walk up from CWD until build.gradle found
    static Path findProjectRoot() {
        Path p = Paths.get('').toAbsolutePath()
        while (p != null) {
            if (p.resolve('build.gradle').toFile().exists() &&
                p.resolve('src/main/groovy').toFile().exists()) {
                return p
            }
            p = p.parent
        }
        throw new IllegalStateException("Cannot find project root (no build.gradle + src/main/groovy)")
    }

    static List<String> scanTree(Path root, Set<String> excludeFilenames, Pattern pattern) {
        List<String> violations = []
        if (!root.toFile().exists()) return violations
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fname = file.fileName.toString()
                if (!fname.endsWith('.groovy') || fname in excludeFilenames) {
                    return FileVisitResult.CONTINUE
                }
                String content = file.toFile().getText('UTF-8')
                content.eachLine { String line, int idx ->
                    if (pattern.matcher(line).find()) {
                        violations << "${file}:${idx + 1}: ${line.trim().take(120)}"
                    }
                }
                return FileVisitResult.CONTINUE
            }
            @Override
            FileVisitResult visitFileFailed(Path file, IOException exc) {
                FileVisitResult.CONTINUE  // skip unreadable files
            }
        })
        return violations
    }

    def "no file anywhere uses McpResponse.error() -- method was renamed to protocolError()"() {
        given: "full recursive scan of src/main/groovy -- no exclusions, error() must not exist"
        Path srcRoot = findProjectRoot().resolve('src/main/groovy')

        when:
        List<String> violations = scanTree(srcRoot, [] as Set, OLD_ERROR_PATTERN)

        then: "zero occurrences -- McpResponse.error() was removed in v0.8.52"
        violations.isEmpty() || {
            violations.each { println "  OLD .error() VIOLATION: $it" }
            false
        }()
    }

    def "McpResponse.protocolError() only appears in controller files -- not in service handlers"() {
        given: "recursive scan of src/main/groovy, excluding legitimate controller files"
        Path srcRoot = findProjectRoot().resolve('src/main/groovy')

        when:
        List<String> violations = scanTree(srcRoot, CONTROLLER_FILES, PROTOCOL_ERROR_IN_SERVICE)

        then: "zero occurrences outside controllers -- service handlers must use toolError()"
        violations.isEmpty() || {
            violations.each { println "  protocolError() IN SERVICE VIOLATION: $it" }
            false
        }()
    }

    def "controller files exist and are the only legitimate users of protocolError()"() {
        given:
        Path srcRoot = findProjectRoot().resolve('src/main/groovy')
        Path controllerDir = srcRoot.resolve('com/softwood/mcp/controller')

        expect: "both controller files present"
        controllerDir.resolve('McpController.groovy').toFile().exists()
        controllerDir.resolve('HttpMcpController.groovy').toFile().exists()

        and: "each controller file actually calls protocolError() -- confirms exclusion is real"
        controllerDir.resolve('McpController.groovy').toFile().text.contains('protocolError(')
        controllerDir.resolve('HttpMcpController.groovy').toFile().text.contains('protocolError(')
    }

    def "src/main/groovy directory is accessible and non-empty for scan"() {
        given:
        Path srcRoot = findProjectRoot().resolve('src/main/groovy')

        expect: "root exists and contains groovy files (guards against CWD drift making scan vacuous)"
        srcRoot.toFile().exists()
        srcRoot.toFile().isDirectory()
        srcRoot.toFile().list().length > 0
    }
}
