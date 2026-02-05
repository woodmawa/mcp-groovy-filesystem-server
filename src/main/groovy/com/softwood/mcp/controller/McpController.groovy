package com.softwood.mcp.controller

import com.softwood.mcp.model.McpRequest
import com.softwood.mcp.model.McpResponse
import com.softwood.mcp.service.FileSystemService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.GroovyScriptService
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@Slf4j
@CompileStatic
class McpController {

    private final FileSystemService fileSystemService
    private final PathService pathService
    private final GroovyScriptService groovyScriptService

    McpController(FileSystemService fileSystemService, PathService pathService, GroovyScriptService groovyScriptService) {
        this.fileSystemService = fileSystemService
        this.pathService = pathService
        this.groovyScriptService = groovyScriptService
    }

    /**
     * Sanitize string by removing control characters (except newlines and tabs)
     * CRITICAL: Prevents JSON serialization errors in exception messages
     */
    private static String sanitize(String text) {
        if (!text) return text
        return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
    }

    @PostMapping("/")
    McpResponse handleRequest(@RequestBody McpRequest request) {
        try {
            return dispatch(request)
        } catch (Exception e) {
            log.error("Error handling request", e)
            return McpResponse.error(request.id, -32603, sanitize("Internal error: ${e.message}") as String)
        }
    }

    /**
     * Dispatch MCP requests
     */
    private McpResponse dispatch(McpRequest request) {
        if (request.id == null) {
            log.debug("Received notification: {}", request.method)
            return null
        }

        switch (request.method) {
            case "initialize":
                return handleInitialize(request)

            case "tools/list":
                return handleToolsList(request)

            case "tools/call":
                return handleToolsCall(request)

            default:
                return McpResponse.error(
                        request.id,
                        -32601,
                        "Unknown method: ${request.method}" as String
                )
        }
    }

    /**
     * Handle initialize request
     */
    private McpResponse handleInitialize(McpRequest request) {
        def clientVersion = request.params.protocolVersion
        def capabilities = request.params.capabilities ?: [:]

        return McpResponse.success(request.id, [
                protocolVersion: clientVersion ?: "2024-11-05",
                capabilities: [
                        tools: [:]
                ],
                serverInfo: [
                        name: "mcp-groovy-filesystem-server",
                        version: "1.0.0"
                ]
        ])
    }

    /**
     * Handle tools/list - returns available filesystem tools
     *  INCLUDES NEW BOUNDED TOOLS FOR TOKEN OPTIMIZATION
     */
    private McpResponse handleToolsList(McpRequest request) {
        def tools = [
                [
                        name: "readFile",
                        description: "Read complete file contents with encoding support ( may be large, prefer readFileRange for bounded results)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path (Windows or WSL format)"
                                        ],
                                        encoding: [
                                                type: "string",
                                                description: "Character encoding (default: UTF-8)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                //  NEW BOUNDED TOOL
                [
                        name: "readFileRange",
                        description: "Read specific line range from file ( token-optimized, bounded result)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path"
                                        ],
                                        startLine: [
                                                type: "integer",
                                                description: "Starting line number (1-indexed, default: 1)"
                                        ],
                                        maxLines: [
                                                type: "integer",
                                                description: "Maximum lines to return (default: 100)"
                                        ],
                                        encoding: [
                                                type: "string",
                                                description: "Character encoding (default: UTF-8)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "writeFile",
                        description: "Write content to a file with optional backup",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path (Windows or WSL format)"
                                        ],
                                        content: [
                                                type: "string",
                                                description: "Content to write"
                                        ],
                                        encoding: [
                                                type: "string",
                                                description: "Character encoding (default: UTF-8)"
                                        ],
                                        createBackup: [
                                                type: "boolean",
                                                description: "Create .backup file before overwriting"
                                        ]
                                ],
                                required: ["path", "content"]
                        ]
                ],
                //  NEW BOUNDED TOOL
                [
                        name: "listChildrenOnly",
                        description: "List immediate children only ( no recursion, bounded to 100 results, token-optimized)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Directory path"
                                        ],
                                        pattern: [
                                                type: "string",
                                                description: "Filename regex pattern (optional)"
                                        ],
                                        maxResults: [
                                                type: "integer",
                                                description: "Max results (default: 100)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "listDirectory",
                        description: "List files and directories with optional filtering ( max 100 results, may truncate. Prefer listChildrenOnly)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Directory path"
                                        ],
                                        pattern: [
                                                type: "string",
                                                description: "Filename regex pattern (optional)"
                                        ],
                                        recursive: [
                                                type: "boolean",
                                                description: "List recursively ( may hit limits)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                //  NEW BOUNDED TOOL
                [
                        name: "searchInProject",
                        description: "Search in active project root only ( bounded to 50 results, token-optimized)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        contentPattern: [
                                                type: "string",
                                                description: "Regex pattern to search for in file contents"
                                        ],
                                        filePattern: [
                                                type: "string",
                                                description: "Regex pattern for filenames (default: .(groovy|java|gradle)\$)"
                                        ],
                                        maxResults: [
                                                type: "integer",
                                                description: "Max results (default: 50)"
                                        ]
                                ],
                                required: ["contentPattern"]
                        ]
                ],
                [
                        name: "searchFiles",
                        description: "Search file contents using regex patterns ( max 50 results, prefer searchInProject for project scope)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        directory: [
                                                type: "string",
                                                description: "Directory to search in"
                                        ],
                                        contentPattern: [
                                                type: "string",
                                                description: "Regex pattern to search for in file contents"
                                        ],
                                        filePattern: [
                                                type: "string",
                                                description: "Regex pattern for filenames (default: groovy files)"
                                        ]
                                ],
                                required: ["directory", "contentPattern"]
                        ]
                ],
                //  NEW INTROSPECTION TOOL
                [
                        name: "getProjectRoot",
                        description: "Get the active project root directory (preferred scope for operations)",
                        inputSchema: [
                                type: "object",
                                properties: [:],
                                required: []
                        ]
                ],
                [
                        name: "normalizePath",
                        description: "Convert between Windows and WSL path formats",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Path to normalize"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "copyFile",
                        description: "Copy a file to a new location",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        source: [
                                                type: "string",
                                                description: "Source file path"
                                        ],
                                        destination: [
                                                type: "string",
                                                description: "Destination file path"
                                        ],
                                        overwrite: [
                                                type: "boolean",
                                                description: "Overwrite if exists (default: false)"
                                        ]
                                ],
                                required: ["source", "destination"]
                        ]
                ],
                [
                        name: "moveFile",
                        description: "Move or rename a file",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        source: [
                                                type: "string",
                                                description: "Source file path"
                                        ],
                                        destination: [
                                                type: "string",
                                                description: "Destination file path"
                                        ],
                                        overwrite: [
                                                type: "boolean",
                                                description: "Overwrite if exists (default: false)"
                                        ]
                                ],
                                required: ["source", "destination"]
                        ]
                ],
                [
                        name: "deleteFile",
                        description: "Delete a file or directory",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File or directory path"
                                        ],
                                        recursive: [
                                                type: "boolean",
                                                description: "Delete directory recursively"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "createDirectory",
                        description: "Create a directory (including parent directories)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Directory path to create"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "executeGroovyScript",
                        description: "Execute a Groovy script with secure DSL for PowerShell, Bash, Git, and Gradle commands",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        script: [
                                                type: "string",
                                                description: "Groovy script to execute"
                                        ],
                                        workingDirectory: [
                                                type: "string",
                                                description: "Working directory for script execution"
                                        ]
                                ],
                                required: ["script", "workingDirectory"]
                        ]
                ],
                [
                        name: "getAllowedDirectories",
                        description: "Get list of allowed directories accessible for file operations",
                        inputSchema: [
                                type: "object",
                                properties: [:],
                                required: []
                        ]
                ],
                [
                        name: "isSymlinksAllowed",
                        description: "Check if symbolic links are allowed",
                        inputSchema: [
                                type: "object",
                                properties: [:],
                                required: []
                        ]
                ],
                [
                        name: "watchDirectory",
                        description: "Watch a directory for file changes (CREATE, MODIFY, DELETE events)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Directory path to watch"
                                        ],
                                        eventTypes: [
                                                type: "array",
                                                description: "Event types to watch for (default: ['CREATE', 'MODIFY', 'DELETE'])",
                                                items: [type: "string"]
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "pollDirectoryWatch",
                        description: "Poll for directory watch events",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Directory path being watched"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "readMultipleFiles",
                        description: "Read multiple files at once (more efficient than multiple readFile calls, max 10 files)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        paths: [
                                                type: "array",
                                                items: [type: "string"],
                                                description: "Array of file paths to read (max 10)"
                                        ]
                                ],
                                required: ["paths"]
                        ]
                ],
                [
                        name: "getFileInfo",
                        description: "Get detailed file/directory metadata (size, dates, permissions)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File or directory path"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "listDirectoryWithSizes",
                        description: "List directory with file sizes and optional sorting",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Directory path"
                                        ],
                                        sortBy: [
                                                type: "string",
                                                enum: ["name", "size"],
                                                description: "Sort by name or size (default: name)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "getDirectoryTree",
                        description: "Get recursive directory tree structure as JSON ( max depth 5, max 200 files)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "Directory path"
                                        ],
                                        excludePatterns: [
                                                type: "array",
                                                items: [type: "string"],
                                                description: "Regex patterns to exclude (e.g., ['node_modules', '\\.git'])"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                //  NEW TOKEN-OPTIMIZED TOOLS
                [
                        name: "grepFile",
                        description: "Read only matching lines from a file (like grep) ( token-optimized - 90%+ savings)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path"
                                        ],
                                        pattern: [
                                                type: "string",
                                                description: "Regex pattern to search for"
                                        ],
                                        maxMatches: [
                                                type: "integer",
                                                description: "Maximum matches to return (default: 100)"
                                        ],
                                        encoding: [
                                                type: "string",
                                                description: "Character encoding (default: UTF-8)"
                                        ]
                                ],
                                required: ["path", "pattern"]
                        ]
                ],
                [
                        name: "tailFile",
                        description: "Read last N lines of a file ( perfect for logs - 95%+ token savings)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path"
                                        ],
                                        lines: [
                                                type: "integer",
                                                description: "Number of lines from end (default: 50)"
                                        ],
                                        encoding: [
                                                type: "string",
                                                description: "Character encoding (default: UTF-8)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "headFile",
                        description: "Read first N lines of a file ( perfect for previews/headers - 95%+ token savings)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path"
                                        ],
                                        lines: [
                                                type: "integer",
                                                description: "Number of lines from start (default: 50)"
                                        ],
                                        encoding: [
                                                type: "string",
                                                description: "Character encoding (default: UTF-8)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "fileExists",
                        description: "Check if file/directory exists ( no content read - 100% token savings)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File or directory path"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "countLines",
                        description: "Count lines in a file ( no content returned - 99%+ token savings)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path"
                                        ],
                                        encoding: [
                                                type: "string",
                                                description: "Character encoding (default: UTF-8)"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ],
                [
                        name: "findFilesByName",
                        description: "Find files by name pattern ( faster than content search - 80%+ token savings)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        pattern: [
                                                type: "string",
                                                description: "Filename pattern (regex, substring match). Examples: Controller, .*\\.groovy\$, Test.*Spec"
                                        ],
                                        directory: [
                                                type: "string",
                                                description: "Directory to search (default: project root)"
                                        ],
                                        maxDepth: [
                                                type: "integer",
                                                description: "Maximum directory depth (default: 10)"
                                        ],
                                        maxResults: [
                                                type: "integer",
                                                description: "Maximum results (default: 100)"
                                        ]
                                ],
                                required: ["pattern"]
                        ]
                ],
                [
                        name: "getFileSummary",
                        description: "Get file metadata without content ( lines, size, type, dates - 99%+ token savings)",
                        inputSchema: [
                                type: "object",
                                properties: [
                                        path: [
                                                type: "string",
                                                description: "File path"
                                        ]
                                ],
                                required: ["path"]
                        ]
                ]

        ]

        return McpResponse.success(request.id, [tools: tools] as Map<String, Object>)
    }


    /**
     * Handle tools/call - execute filesystem operations
     *  INCLUDES HANDLERS FOR NEW BOUNDED TOOLS
     */
    private McpResponse handleToolsCall(McpRequest request) {
        String toolName = request.params.name as String
        Map<String, Object> arguments = request.params.arguments as Map<String, Object> ?: [:]

        switch (toolName) {
            case "readFile":
                String path = arguments.path as String
                String encoding = arguments.encoding as String ?: "UTF-8"
                String content = fileSystemService.readFile(path, encoding)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: content]]
                ] as Map<String, Object>)

                //  NEW BOUNDED TOOL HANDLER
            case "readFileRange":
                String path = arguments.path as String
                int startLine = (arguments.startLine as Integer) ?: 1
                int maxLines = (arguments.maxLines as Integer) ?: 100
                String encoding = arguments.encoding as String ?: "UTF-8"
                def rangeResult = fileSystemService.readFileRange(path, startLine, maxLines, encoding)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(rangeResult)]]
                ] as Map<String, Object>)

            case "writeFile":
                String path = arguments.path as String
                String content = arguments.content as String
                String encoding = arguments.encoding as String ?: "UTF-8"
                boolean createBackup = arguments.createBackup as Boolean ?: false
                def result = fileSystemService.writeFile(path, content, encoding, createBackup)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(result)]]
                ] as Map<String, Object>)

                //  NEW BOUNDED TOOL HANDLER
            case "listChildrenOnly":
                String path = arguments.path as String
                String pattern = arguments.pattern as String
                int maxResults = (arguments.maxResults as Integer) ?: -1
                def children = fileSystemService.listChildrenOnly(path, pattern, maxResults)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(children)]]
                ] as Map<String, Object>)

            case "listDirectory":
                String path = arguments.path as String
                String pattern = arguments.pattern as String
                boolean recursive = arguments.recursive as Boolean ?: false
                def files = fileSystemService.listDirectory(path, pattern, recursive)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(files)]]
                ] as Map<String, Object>)

                //  NEW BOUNDED TOOL HANDLER
            case "searchInProject":
                String contentPattern = arguments.contentPattern as String
                String filePattern = arguments.filePattern as String ?: '.*\\.(groovy|java|gradle)$'
                int maxResults = (arguments.maxResults as Integer) ?: -1
                def projectResults = fileSystemService.searchInProject(contentPattern, filePattern, maxResults)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(projectResults)]]
                ] as Map<String, Object>)

            case "searchFiles":
                String directory = arguments.directory as String
                String contentPattern = arguments.contentPattern as String
                String filePattern = arguments.filePattern as String ?: '.*\\.groovy$'
                def results = fileSystemService.searchFiles(directory, contentPattern, filePattern)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(results)]]
                ] as Map<String, Object>)

                //  NEW INTROSPECTION TOOL HANDLER
            case "getProjectRoot":
                String projectRoot = fileSystemService.getProjectRoot()
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson([
                                projectRoot: projectRoot,
                                message: "This is the preferred scope for file operations"
                        ])]]
                ] as Map<String, Object>)

            case "normalizePath":
                String path = arguments.path as String
                def pathInfo = pathService.getPathRepresentations(path)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(pathInfo)]]
                ] as Map<String, Object>)

            case "copyFile":
                String source = arguments.source as String
                String destination = arguments.destination as String
                boolean overwrite = arguments.overwrite as Boolean ?: false
                def result = fileSystemService.copyFile(source, destination, overwrite)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(result)]]
                ] as Map<String, Object>)

            case "moveFile":
                String source = arguments.source as String
                String destination = arguments.destination as String
                boolean overwrite = arguments.overwrite as Boolean ?: false
                def result = fileSystemService.moveFile(source, destination, overwrite)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(result)]]
                ] as Map<String, Object>)

            case "deleteFile":
                String path = arguments.path as String
                boolean recursive = arguments.recursive as Boolean ?: false
                def result = fileSystemService.deleteFile(path, recursive)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(result)]]
                ] as Map<String, Object>)

            case "createDirectory":
                String path = arguments.path as String
                def result = fileSystemService.createDirectory(path)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(result)]]
                ] as Map<String, Object>)

            case "executeGroovyScript":
                String script = arguments.script as String
                String workingDirectory = arguments.workingDirectory as String
                def result = groovyScriptService.executeScript(script, workingDirectory)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(result)]]
                ] as Map<String, Object>)

            case "getAllowedDirectories":
                def allowedDirs = fileSystemService.getAllowedDirectories()
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(allowedDirs)]]
                ] as Map<String, Object>)

            case "isSymlinksAllowed":
                def symlinkAllowed = fileSystemService.isSymlinksAllowed()
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson([allowSymlinks: symlinkAllowed])]]
                ] as Map<String, Object>)

            case "watchDirectory":
                String path = arguments.path as String
                List<String> eventTypes = arguments.eventTypes as List<String> ?: ['CREATE', 'MODIFY', 'DELETE']
                def watchResult = fileSystemService.watchDirectory(path, eventTypes)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(watchResult)]]
                ] as Map<String, Object>)

            case "pollDirectoryWatch":
                String path = arguments.path as String
                def pollResult = fileSystemService.pollDirectoryWatch(path)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(pollResult)]]
                ] as Map<String, Object>)

            case "readMultipleFiles":
                List<String> paths = arguments.paths as List<String>
                def results = fileSystemService.readMultipleFiles(paths)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(results)]]
                ] as Map<String, Object>)

            case "getFileInfo":
                String infoPath = arguments.path as String
                def info = fileSystemService.getFileInfo(infoPath)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(info)]]
                ] as Map<String, Object>)

            case "listDirectoryWithSizes":
                String sizePath = arguments.path as String
                String sortBy = (arguments.sortBy as String) ?: 'name'
                def sizeList = fileSystemService.listDirectoryWithSizes(sizePath, sortBy)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(sizeList)]]
                ] as Map<String, Object>)

            case "getDirectoryTree":
                String treePath = arguments.path as String
                List<String> excludes = (arguments.excludePatterns as List<String>) ?: []
                def tree = fileSystemService.getDirectoryTree(treePath, excludes)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(tree)]]
                ] as Map<String, Object>)

            
            case "grepFile":
                String grepPath = arguments.path as String
                String grepPattern = arguments.pattern as String
                int grepMaxMatches = (arguments.maxMatches as Integer) ?: 100
                String grepEncoding = arguments.encoding as String ?: "UTF-8"
                def grepResult = fileSystemService.grepFile(grepPath, grepPattern, grepMaxMatches, grepEncoding)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(grepResult)]]
                ] as Map<String, Object>)

            case "tailFile":
                String tailPath = arguments.path as String
                int tailLines = (arguments.lines as Integer) ?: 50
                String tailEncoding = arguments.encoding as String ?: "UTF-8"
                def tailResult = fileSystemService.tailFile(tailPath, tailLines, tailEncoding)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(tailResult)]]
                ] as Map<String, Object>)

            case "headFile":
                String headPath = arguments.path as String
                int headLines = (arguments.lines as Integer) ?: 50
                String headEncoding = arguments.encoding as String ?: "UTF-8"
                def headResult = fileSystemService.headFile(headPath, headLines, headEncoding)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(headResult)]]
                ] as Map<String, Object>)

            case "fileExists":
                String existsPath = arguments.path as String
                def existsResult = fileSystemService.fileExists(existsPath)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(existsResult)]]
                ] as Map<String, Object>)

            case "countLines":
                String countPath = arguments.path as String
                String countEncoding = arguments.encoding as String ?: "UTF-8"
                def countResult = fileSystemService.countLines(countPath, countEncoding)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(countResult)]]
                ] as Map<String, Object>)

            case "findFilesByName":
                String findPattern = arguments.pattern as String
                String findDirectory = arguments.directory as String
                int findMaxDepth = (arguments.maxDepth as Integer) ?: 5
                int findMaxResults = (arguments.maxResults as Integer) ?: 100
                def findResult = fileSystemService.findFilesByName(findPattern, findDirectory, findMaxDepth, findMaxResults)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(findResult)]]
                ] as Map<String, Object>)

            case "getFileSummary":
                String summaryPath = arguments.path as String
                def summaryResult = fileSystemService.getFileSummary(summaryPath)
                return McpResponse.success(request.id, [
                        content: [[type: "text", text: JsonOutput.toJson(summaryResult)]]
                ] as Map<String, Object>)

default:
                return McpResponse.error(
                        request.id,
                        -32601,
                        "Unknown tool: ${toolName}" as String
                )
        }
    }
}
