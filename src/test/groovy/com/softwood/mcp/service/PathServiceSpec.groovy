package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Tests for PathService - Windows/WSL/Linux cross-platform path conversion
 * v0.0.4: Added comprehensive Linux absolute path handling tests
 */
class PathServiceSpec extends Specification {
    
    PathService pathService
    
    def setup() {
        pathService = new PathService()
        // Configure test workspace roots
        pathService.activeProjectRoot = "C:/Users/test/project"
        pathService.claudeWorkspaceRoot = "C:/Users/test/claude-workspace"
    }
    
    // ========================================================================
    // WINDOWS <-> WSL CONVERSION TESTS (Original v0.0.3 tests)
    // ========================================================================
    
    @Unroll
    def "should convert Windows path '#windowsPath' to WSL path '#expectedWsl'"() {
        when: "converting to WSL"
        def wslPath = pathService.convertWindowsToWsl(windowsPath)
        
        then: "correct WSL path is returned"
        wslPath == expectedWsl
        
        where:
        windowsPath                           || expectedWsl
        "C:\\Users\\test\\file.txt"          || "/mnt/c/Users/test/file.txt"
        "C:/Users/test/file.txt"             || "/mnt/c/Users/test/file.txt"
        "D:\\projects\\myproject"            || "/mnt/d/projects/myproject"
        "E:/data/files"                      || "/mnt/e/data/files"
    }
    
    @Unroll
    def "should convert WSL path '#wslPath' to Windows path '#expectedWindows'"() {
        when: "converting to Windows"
        def windowsPath = pathService.convertWslToWindows(wslPath)
        
        then: "correct Windows path is returned"
        windowsPath == expectedWindows
        
        where:
        wslPath                              || expectedWindows
        "/mnt/c/Users/test/file.txt"        || "C:/Users/test/file.txt"
        "/mnt/d/projects/myproject"         || "D:/projects/myproject"
        "/mnt/e/data/files"                 || "E:/data/files"
    }
    
    def "should normalize Windows paths with backslashes"() {
        given: "a Windows path with backslashes"
        def path = "C:\\Users\\test\\Documents\\file.txt"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(path)
        
        then: "backslashes are converted to forward slashes"
        normalized == "C:/Users/test/Documents/file.txt"
    }
    
    def "should normalize WSL mount paths to Windows"() {
        given: "a WSL mount path"
        def path = "/mnt/c/Users/test/file.txt"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(path)
        
        then: "WSL path is converted to Windows format"
        normalized == "C:/Users/test/file.txt"
    }
    
    def "should get path representations for all formats"() {
        given: "a Windows path"
        def path = "C:\\Users\\test\\file.txt"
        
        when: "getting representations"
        def representations = pathService.getPathRepresentations(path)
        
        then: "both formats are returned"
        representations.original == "C:\\Users\\test\\file.txt"
        representations.normalized == "C:/Users/test/file.txt"
        representations.wsl == "/mnt/c/Users/test/file.txt"
        representations.windows == "C:/Users/test/file.txt"
    }
    
    def "should handle paths with spaces in conversion"() {
        given: "a path with spaces"
        def path = "C:/Users/John Doe/My Documents/file.txt"
        
        when: "converting to WSL"
        def wslPath = pathService.convertWindowsToWsl(path)
        
        then: "spaces are preserved"
        wslPath == "/mnt/c/Users/John Doe/My Documents/file.txt"
    }
    
    def "should handle paths with special characters"() {
        given: "a path with special characters"
        def path = "C:/Users/test/file (1).txt"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(path)
        
        then: "special characters are preserved"
        normalized == "C:/Users/test/file (1).txt"
    }
    
    // ========================================================================
    // v0.0.4 NEW TESTS: LINUX ABSOLUTE PATH HANDLING
    // ========================================================================
    
    @Unroll
    def "v0.0.4: should map Linux path '#linuxPath' to workspace with expected fragment '#expectedFragment'"() {
        when: "normalizing a Linux absolute path"
        def normalized = pathService.normalizePath(linuxPath)
        
        then: "path is mapped to workspace root"
        normalized.startsWith("C:/Users/test/claude-workspace")
        normalized.contains(expectedFragment)
        
        where:
        linuxPath                              || expectedFragment
        "/home/claude/report.md"              || "report.md"
        "/home/claude/docs/file.md"           || "docs/file.md"
        "/tmp/data.txt"                       || "tmp/data.txt"
        "/var/log/app.log"                    || "var/log/app.log"
        "/workspace/project/src/Main.java"   || "project/src/Main.java"
    }
    
    def "v0.0.4: should strip /home/claude prefix for cleaner paths"() {
        given: "a typical Claude.ai path"
        def claudePath = "/home/claude/my-document.md"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(claudePath)
        
        then: "path is mapped directly without redundant home/claude"
        normalized == "C:/Users/test/claude-workspace/my-document.md"
        !normalized.contains("home/claude")
    }
    
    def "v0.0.4: should preserve nested directory structure from Linux paths"() {
        given: "a deeply nested Linux path"
        def complexPath = "/home/claude/projects/2024/client-work/reports/final-v3.md"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(complexPath)
        
        then: "full structure is preserved in workspace"
        normalized == "C:/Users/test/claude-workspace/projects/2024/client-work/reports/final-v3.md"
    }
    
    def "v0.0.4: should fallback to project root when no workspace configured"() {
        given: "no workspace root configured"
        pathService.claudeWorkspaceRoot = null
        def linuxPath = "/home/claude/file.md"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(linuxPath)
        
        then: "path is mapped to project root instead"
        normalized.startsWith("C:/Users/test/project")
        normalized.contains("file.md")
    }
    
    def "v0.0.4: should throw exception when no root configured at all"() {
        given: "neither workspace nor project root configured"
        pathService.claudeWorkspaceRoot = null
        pathService.activeProjectRoot = null
        def linuxPath = "/home/claude/file.md"
        
        when: "normalizing"
        pathService.normalizePath(linuxPath)
        
        then: "exception is thrown with helpful message"
        def ex = thrown(IllegalStateException)
        ex.message.contains("no workspace root configured")
        ex.message.contains("claude-workspace-root")
    }
    
    @Unroll
    def "v0.0.4: path priority test - '#testCase' resolves correctly"() {
        when: "normalizing path with specific type"
        def normalized = pathService.normalizePath(inputPath)
        
        then: "correct handling based on priority"
        normalized.startsWith(expectedPrefix)
        
        where:
        testCase                        | inputPath                          || expectedPrefix
        "WSL mount (priority 1)"       | "/mnt/c/Users/test/file.txt"       || "C:"
        "Linux absolute (priority 2)"  | "/home/claude/file.md"             || "C:/Users/test/claude-workspace"
        "Relative (priority 3)"        | "src/Main.java"                    || "C:/Users/test/project"
        "Windows absolute (priority 4)"| "D:/data/file.txt"                 || "D:"
    }
    
    def "v0.0.4: should handle relative paths unchanged (priority 3)"() {
        given: "a relative path"
        def path = "docs/README.md"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(path)
        
        then: "path is resolved against project root"
        normalized.startsWith("C:/Users/test/project")
        normalized.endsWith("docs/README.md")
    }
    
    @Unroll
    def "v0.0.4: common Linux paths '#linuxPath' should be detected and mapped"() {
        when: "normalizing common Linux absolute paths"
        def normalized = pathService.normalizePath(linuxPath)
        
        then: "all are properly mapped to workspace"
        normalized.startsWith("C:/Users/test/claude-workspace")
        
        where:
        linuxPath << [
            "/home/user/file.txt",
            "/tmp/cache.dat",
            "/var/log/system.log",
            "/opt/app/config.yml",
            "/usr/local/bin/script.sh",
            "/workspace/data.json"
        ]
    }
    
    def "v0.0.4: integration test - full Linux to Windows workflow"() {
        given: "Claude.ai creates a file with Linux path"
        def claudePath = "/home/claude/session-notes.md"
        
        when: "path is normalized by the service"
        def windowsPath = pathService.normalizePath(claudePath)
        
        then: "it maps to a valid Windows path in workspace"
        windowsPath == "C:/Users/test/claude-workspace/session-notes.md"
        
        and: "the path can be converted to WSL format"
        def wslPath = pathService.convertWindowsToWsl(windowsPath)
        wslPath.startsWith("/mnt/c/")
    }
    
    @Unroll
    def "v0.0.4: edge cases '#description' handled gracefully"() {
        when: "handling edge case"
        def result = pathService.normalizePath(path)
        
        then: "handled correctly without exceptions"
        result != null || path == null
        
        where:
        description              | path
        "Null path"             | null
        "Empty path"            | ""
        "Just filename"         | "file.txt"
        "Hidden file"           | "/home/claude/.config"
        "Root directory"        | "/"
    }
    
    def "v0.0.4: WSL mounts should take priority over Linux absolute path detection"() {
        given: "a WSL mount path that could be confused with Linux path"
        def wslMount = "/mnt/c/home/claude/file.md"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(wslMount)
        
        then: "WSL conversion takes priority (converts to Windows C: drive)"
        normalized.startsWith("C:/home/claude/")
        !normalized.contains("/mnt/")
        !normalized.contains("claude-workspace")
    }
}
