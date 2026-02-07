package com.softwood.mcp.controller

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.service.*
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Integration tests for McpController
 * REFACTORED: Uses new ToolHandler-based sub-services
 */
class McpControllerSpec extends Specification {

    @TempDir
    Path tempDir

    McpController controller
    PathService pathService
    FileReadService fileReadService
    FileWriteService fileWriteService
    FileQueryService fileQueryService
    FileMetadataService fileMetadataService
    GroovyScriptService groovyScriptService
    ScriptExecutor scriptExecutor
    ScriptSecurityService securityService
    AuditService auditService
    CommandWhitelistConfig whitelistConfig
    GitHubService githubService

    def setup() {
        pathService = new PathService()
        pathService.activeProjectRoot = tempDir.toString()

        auditService = new AuditService()
        scriptExecutor = new ScriptExecutor(auditService)
        securityService = new ScriptSecurityService()

        whitelistConfig = new CommandWhitelistConfig()
        whitelistConfig.powershellAllowed = ['.*']
        whitelistConfig.powershellBlocked = []
        whitelistConfig.bashAllowed = ['.*']
        whitelistConfig.bashBlocked = []

        githubService = Mock(GitHubService)

        // Create sub-services
        fileReadService = new FileReadService(pathService)
        fileWriteService = new FileWriteService(pathService)
        fileQueryService = new FileQueryService(pathService)
        fileMetadataService = new FileMetadataService(pathService)

        // Configure all sub-services (they inherit from AbstractFileService)
        [fileReadService, fileWriteService, fileQueryService, fileMetadataService].each { svc ->
            svc.allowedDirectoriesString = tempDir.toString()
            svc.initAllowedDirs()
            svc.enableWrite = true
            svc.maxListResults = 100
            svc.maxSearchResults = 50
            svc.maxSearchMatchesPerFile = 10
            svc.maxTreeDepth = 5
            svc.maxTreeFiles = 200
            svc.maxReadMultiple = 10
            svc.maxLineLength = 1000
            svc.maxResponseSizeKb = 100
            svc.maxFileSizeMb = 10
            svc.activeProjectRoot = tempDir.toString()
        }

        groovyScriptService = new GroovyScriptService(
                fileReadService, fileWriteService, fileQueryService, fileMetadataService,
                pathService, scriptExecutor, securityService, auditService,
                whitelistConfig, githubService
        )

        // Controller now takes List<ToolHandler> + GroovyScriptService
        List<ToolHandler> handlers = [fileReadService, fileWriteService, fileQueryService, fileMetadataService]
        controller = new McpController(handlers, groovyScriptService)
    }

    def "should handle initialize request"() {
        given: "an initialize request"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "initialize",
                params: [protocolVersion: "2024-11-05"]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "response is correct"
        response.id == "1"
        response.result.protocolVersion == "2024-11-05"
        response.result.serverInfo.name == "mcp-groovy-filesystem-server"
    }

    def "should handle tools/list request"() {
        given: "a tools/list request"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/list",
                params: [:]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "core tools are present"
        response.result.tools.size() >= 10

        // From FileReadService
        response.result.tools*.name.contains("readFile")
        response.result.tools*.name.contains("headFile")
        response.result.tools*.name.contains("tailFile")
        response.result.tools*.name.contains("grepFile")

        // From FileWriteService
        response.result.tools*.name.contains("writeFile")
        response.result.tools*.name.contains("replaceInFile")
        response.result.tools*.name.contains("appendToFile")
        response.result.tools*.name.contains("deleteFile")
        response.result.tools*.name.contains("copyFile")
        response.result.tools*.name.contains("moveFile")

        // From FileQueryService
        response.result.tools*.name.contains("listDirectory")
        response.result.tools*.name.contains("listChildrenOnly")
        response.result.tools*.name.contains("searchFiles")
        response.result.tools*.name.contains("findFilesByName")

        // From FileMetadataService
        response.result.tools*.name.contains("fileExists")
        response.result.tools*.name.contains("getFileInfo")
        response.result.tools*.name.contains("getProjectRoot")
        response.result.tools*.name.contains("getAllowedDirectories")
        response.result.tools*.name.contains("normalizePath")

        // Script execution (handled directly)
        response.result.tools*.name.contains("executeGroovyScript")
    }

    def "should handle readFile tool call"() {
        given: "a test file"
        def testFile = tempDir.resolve("test.txt").toFile()
        testFile.text = "Test Content"

        and: "a readFile request"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [
                        name: "readFile",
                        arguments: [path: testFile.absolutePath]
                ]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "file content is returned"
        response.result.content[0].text == "Test Content"
    }

    def "should handle writeFile tool call"() {
        given: "a file path"
        def testFile = tempDir.resolve("output.txt")

        and: "a writeFile request"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [
                        name: "writeFile",
                        arguments: [
                                path: testFile.toString(),
                                content: "New Content"
                        ]
                ]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "file is created"
        testFile.toFile().text == "New Content"
        response.result.content[0].text.contains('"path"')
    }

    def "should handle listDirectory tool call"() {
        given: "files in directory"
        tempDir.resolve("file1.txt").toFile().text = "content1"
        tempDir.resolve("file2.txt").toFile().text = "content2"

        and: "a listDirectory request"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [
                        name: "listDirectory",
                        arguments: [path: tempDir.toString()]
                ]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "files are listed"
        def resultText = response.result.content[0].text
        resultText.contains("file1.txt")
        resultText.contains("file2.txt")
    }

    def "should handle executeGroovyScript tool call"() {
        given: "a simple script"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [
                        name: "executeGroovyScript",
                        arguments: [
                                script: "println 'Hello from test'",
                                workingDirectory: tempDir.toString()
                        ]
                ]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "script executes"
        def resultText = response.result.content[0].text
        resultText.contains('"success":true')
        resultText.contains("Hello from test")
    }

    def "should handle searchFiles tool call"() {
        given: "files with searchable content"
        tempDir.resolve("test.groovy").toFile().text = "class TestClass {}"
        tempDir.resolve("other.groovy").toFile().text = "class OtherClass {}"

        and: "a searchFiles request"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [
                        name: "searchFiles",
                        arguments: [
                                directory: tempDir.toString(),
                                contentPattern: "TestClass",
                                filePattern: ".*\\.groovy\$"
                        ]
                ]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "matching files are found"
        def resultText = response.result.content[0].text
        resultText.contains("test.groovy")
        !resultText.contains("other.groovy")
    }

    def "should handle normalizePath tool call"() {
        given: "a Windows path"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [
                        name: "normalizePath",
                        arguments: [path: "C:\\Users\\test\\file.txt"]
                ]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "path is normalized"
        def resultText = response.result.content[0].text
        resultText.contains("C:/Users/test/file.txt")
        resultText.contains("/mnt/c/Users/test/file.txt")
    }

    def "should handle copyFile tool call"() {
        given: "a source file"
        def source = tempDir.resolve("source.txt").toFile()
        source.text = "Copy this"
        def dest = tempDir.resolve("dest.txt")

        and: "a copyFile request"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [
                        name: "copyFile",
                        arguments: [
                                source: source.absolutePath,
                                destination: dest.toString()
                        ]
                ]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "file is copied"
        dest.toFile().text == "Copy this"
        response.result.content[0].text.contains('"source"')
    }

    def "should handle errors gracefully"() {
        given: "a request with invalid tool name"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: "1",
                method: "tools/call",
                params: [name: "nonexistentTool", arguments: [:]]
        )

        when: "handling the request"
        def response = controller.handleRequest(request)

        then: "error response is returned"
        response.error != null
        response.error.code == -32601
        response.error.message.contains("Unknown tool")
    }

    def "should handle notifications (no response)"() {
        given: "a notification (id is null)"
        def request = new McpRequest(
                jsonrpc: "2.0",
                id: null,
                method: "notifications/test",
                params: [:]
        )

        when: "handling the notification"
        def response = controller.handleRequest(request)

        then: "no response is returned"
        response == null
    }
}