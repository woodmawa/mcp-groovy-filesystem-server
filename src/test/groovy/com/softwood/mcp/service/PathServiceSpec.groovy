package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Tests for PathService - Windows/WSL path conversion
 */
class PathServiceSpec extends Specification {
    
    PathService pathService
    
    def setup() {
        pathService = new PathService()
    }
    
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
    
    def "should normalize Windows paths"() {
        given: "a Windows path with backslashes"
        def path = "C:\\Users\\test\\Documents\\file.txt"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(path)
        
        then: "backslashes are converted to forward slashes"
        normalized == "C:/Users/test/Documents/file.txt"
    }
    
    def "should normalize WSL paths"() {
        given: "a WSL path"
        def path = "/mnt/c/Users/test/file.txt"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(path)
        
        then: "WSL path is converted to Windows format"
        normalized == "C:/Users/test/file.txt"
    }
    
    def "should get path representations"() {
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
    
    def "should handle relative paths"() {
        given: "a relative path"
        def path = "folder/file.txt"
        
        when: "normalizing"
        def normalized = pathService.normalizePath(path)
        
        then: "path is normalized"
        normalized == "folder/file.txt"
    }
    
    def "should handle paths with spaces"() {
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
}
