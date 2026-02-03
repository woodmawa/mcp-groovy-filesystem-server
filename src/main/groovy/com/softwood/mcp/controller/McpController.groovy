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
        try {
            // Remove control characters except \n (10) and \t (9)
            // Also remove any UTF-8 invalid sequences and other problematic characters
            String cleaned = text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F-\x9F]/, '')
            
            // Additional safety: replace any remaining non-printable characters
            cleaned = cleaned.replaceAll(/[^\p{Print}\p{Space}]/, '')
            
            return cleaned
        } catch (Exception e) {
            log.warn("Error sanitizing text in McpController")
            // Return safe placeholder if sanitization fails
            return "[sanitization failed]"
        }
    }
    
    /**
     * Sanitize any object for safe JSON serialization
     * Recursively sanitizes maps and lists
     */
    private static Object sanitizeObject(Object obj) {
        try {
            if (obj == null) {
                return null
            } else if (obj instanceof String) {
                return sanitize((String) obj)
            } else if (obj instanceof Map) {
                Map result = [:]
                ((Map) obj).each { k, v ->
                    result[sanitizeObject(k)] = sanitizeObject(v)
                }
                return result
            } else if (obj instanceof List) {
                return ((List) obj).collect { sanitizeObject(it) }
            } else {
                return obj
            }
        } catch (Exception e) {
            log.warn("Error in sanitizeObject")
            return "[object sanitization failed]"
        }
    }
    
    @PostMapping("/")
    McpResponse handleRequest(@RequestBody McpRequest request) {
        try {
            def response = dispatch(request)
            return response
        } catch (Throwable t) {
            // Catch all throwables to ensure we always return a valid response
            log.error("Critical error handling request: ${t.class.name}")
            try {
                return McpResponse.error(
                    request?.id ?: "unknown", 
                    -32603, 
                    sanitize("Internal error: ${t.class.simpleName}: ${t.message}") as String
                )
            } catch (Exception e2) {
                // Last resort - return absolute minimal response
                log.error("Failed to create error response")
                return McpResponse.error("error", -32603, "Critical internal error")
            }
        }
    }
    
    /**
     * Dispatch MCP requests
     */
    private McpResponse dispatch(McpRequest request) {
        // Handle notifications (id=null) - NO RESPONSE
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
                    sanitize("Unknown method: ${request.method}") as String
                )
        }
    }
    
    // Supported MCP protocol versions (newest first)
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = [
        "2025-06-18",
        "2024-11-05"
    ]
    
    /**
     * Handle initialize request with protocol version negotiation
     */
    private McpResponse handleInitialize(McpRequest request) {
        String clientVersion = request.params?.protocolVersion as String
        String negotiatedVersion = negotiateProtocolVersion(clientVersion)
        
        log.info("MCP Initialize: client requested '{}', negotiated '{}'", clientVersion, negotiatedVersion)
        
        return McpResponse.success(request.id, [
            protocolVersion: negotiatedVersion,
            capabilities: [
                tools: [:]
            ],
            serverInfo: [
                name: "mcp-groovy-filesystem-server",
                version: "1.0.2"
            ]
        ])
    }
    
    /**
     * Negotiate the protocol version to use.
     * If client requests a version we support, use it.
     * Otherwise, fall back to our newest supported version.
     */
    private static String negotiateProtocolVersion(String clientVersion) {
        if (clientVersion && SUPPORTED_PROTOCOL_VERSIONS.contains(clientVersion)) {
            return clientVersion
        }
        // Default to newest version we support
        return SUPPORTED_PROTOCOL_VERSIONS.first()
    }
    
    /**
     * Handle tools/list - returns available filesystem tools
     */
    private McpResponse handleToolsList(McpRequest request) {
        def tools = [
            [
                name: "readFile",
                description: "Read complete file contents with encoding support",
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
            [
                name: "listDirectory",
                description: "List files and directories with optional filtering",
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
                            description: "List recursively"
                        ]
                    ],
                    required: ["path"]
                ]
            ],
            [
                name: "searchFiles",
                description: "Search file contents using regex patterns",
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
            ]
        ]
        
        return McpResponse.success(request.id, [tools: tools] as Map<String, Object>)
    }
    
    /**
     * Handle tools/call - execute filesystem operations
     * CRITICAL: All results are sanitized before returning to prevent client errors
     * CRITICAL: All exceptions are caught and converted to proper MCP error responses
     */
    private McpResponse handleToolsCall(McpRequest request) {
        try {
            String toolName = sanitize(request.params.name as String)
            Map<String, Object> arguments = request.params.arguments as Map<String, Object> ?: [:]
            
            log.debug("Executing tool: ${toolName}")
            
            switch (toolName) {
                case "readFile":
                    String path = arguments.path as String
                    String encoding = arguments.encoding as String ?: "UTF-8"
                    String content = fileSystemService.readFile(path, encoding)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(content)]]
                    ] as Map<String, Object>)
                
                case "writeFile":
                    String path = arguments.path as String
                    String content = arguments.content as String
                    String encoding = arguments.encoding as String ?: "UTF-8"
                    boolean createBackup = arguments.createBackup as Boolean ?: false
                    def result = fileSystemService.writeFile(path, content, encoding, createBackup)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(result)))]]
                    ] as Map<String, Object>)
                
                case "listDirectory":
                    String path = arguments.path as String
                    String pattern = arguments.pattern as String
                    boolean recursive = arguments.recursive as Boolean ?: false
                    def files = fileSystemService.listDirectory(path, pattern, recursive)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(files)))]]
                    ] as Map<String, Object>)
                
                case "searchFiles":
                    String directory = arguments.directory as String
                    String contentPattern = arguments.contentPattern as String
                    String filePattern = arguments.filePattern as String ?: '.*\\.groovy$'
                    def results = fileSystemService.searchFiles(directory, contentPattern, filePattern)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(results)))]]
                    ] as Map<String, Object>)
                
                case "normalizePath":
                    String path = arguments.path as String
                    def pathInfo = pathService.getPathRepresentations(path)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(pathInfo)))]]
                    ] as Map<String, Object>)
                
                case "copyFile":
                    String source = arguments.source as String
                    String destination = arguments.destination as String
                    boolean overwrite = arguments.overwrite as Boolean ?: false
                    def result = fileSystemService.copyFile(source, destination, overwrite)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(result)))]]
                    ] as Map<String, Object>)
                
                case "moveFile":
                    String source = arguments.source as String
                    String destination = arguments.destination as String
                    boolean overwrite = arguments.overwrite as Boolean ?: false
                    def result = fileSystemService.moveFile(source, destination, overwrite)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(result)))]]
                    ] as Map<String, Object>)
                
                case "deleteFile":
                    String path = arguments.path as String
                    boolean recursive = arguments.recursive as Boolean ?: false
                    def result = fileSystemService.deleteFile(path, recursive)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(result)))]]
                    ] as Map<String, Object>)
                
                case "createDirectory":
                    String path = arguments.path as String
                    def result = fileSystemService.createDirectory(path)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(result)))]]
                    ] as Map<String, Object>)
                
                case "executeGroovyScript":
                    String script = arguments.script as String
                    String workingDirectory = arguments.workingDirectory as String
                    def result = groovyScriptService.executeScript(script, workingDirectory)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(result)))]]
                    ] as Map<String, Object>)
                
                case "getAllowedDirectories":
                    def allowedDirs = fileSystemService.getAllowedDirectories()
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(allowedDirs)))]]
                    ] as Map<String, Object>)
                
                case "isSymlinksAllowed":
                    def symlinkAllowed = fileSystemService.isSymlinksAllowed()
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson([allowSymlinks: symlinkAllowed]))]]
                    ] as Map<String, Object>)
                
                case "watchDirectory":
                    String path = arguments.path as String
                    List<String> eventTypes = arguments.eventTypes as List<String> ?: ['CREATE', 'MODIFY', 'DELETE']
                    def watchResult = fileSystemService.watchDirectory(path, eventTypes)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(watchResult)))]]
                    ] as Map<String, Object>)
                
                case "pollDirectoryWatch":
                    String path = arguments.path as String
                    def pollResult = fileSystemService.pollDirectoryWatch(path)
                    return McpResponse.success(request.id, [
                        content: [[type: "text", text: sanitize(JsonOutput.toJson(sanitizeObject(pollResult)))]]
                    ] as Map<String, Object>)
                
                default:
                    log.warn("Unknown tool requested: ${toolName}")
                    return McpResponse.error(
                        request.id,
                        -32601,
                        sanitize("Unknown tool: ${toolName}") as String
                    )
            }
        } catch (SecurityException e) {
            log.warn("Security error in tool call: ${sanitize(e.message)}")
            return McpResponse.error(
                request.id,
                -32001,
                sanitize("Security error: ${e.message}") as String
            )
        } catch (FileNotFoundException e) {
            log.warn("File not found in tool call: ${sanitize(e.message)}")
            return McpResponse.error(
                request.id,
                -32002,
                sanitize("File not found: ${e.message}") as String
            )
        } catch (IllegalArgumentException e) {
            log.warn("Invalid argument in tool call: ${sanitize(e.message)}")
            return McpResponse.error(
                request.id,
                -32602,
                sanitize("Invalid argument: ${e.message}") as String
            )
        } catch (Throwable t) {
            // Catch ALL throwables to ensure we always return a response
            log.error("Unexpected error in tool call: ${t.class.simpleName}")
            return McpResponse.error(
                request.id,
                -32603,
                sanitize("${t.class.simpleName}: ${t.message ?: 'Unknown error'}") as String
            )
        }
    }
}
