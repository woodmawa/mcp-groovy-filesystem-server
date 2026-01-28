package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Tests for FileSystemService
 */
class FileSystemServiceSpec extends Specification {
    
    @TempDir
    Path tempDir
    
    FileSystemService fileSystemService
    PathService pathService
    
    def setup() {
        pathService = new PathService()
        fileSystemService = new FileSystemService(pathService)
        
        // Configure allowed directories to include temp dir
        fileSystemService.allowedDirectoriesString = tempDir.toString()
        fileSystemService.init()
        fileSystemService.enableWrite = true
    }
    
    def "should read file contents"() {
        given: "a test file with content"
        def testFile = tempDir.resolve("test.txt").toFile()
        testFile.text = "Hello World"
        
        when: "reading the file"
        def content = fileSystemService.readFile(testFile.absolutePath)
        
        then: "content is returned"
        content == "Hello World"
    }
    
    def "should write file contents"() {
        given: "a file path"
        def testFile = tempDir.resolve("output.txt")
        
        when: "writing content"
        def result = fileSystemService.writeFile(testFile.toString(), "Test Content", "UTF-8", false)
        
        then: "file is created with content"
        result.path != null
        result.size == 12
        testFile.toFile().text == "Test Content"
    }
    
    def "should create backup when writing"() {
        given: "an existing file"
        def testFile = tempDir.resolve("existing.txt").toFile()
        testFile.text = "Original Content"
        
        when: "writing with backup enabled"
        fileSystemService.writeFile(testFile.absolutePath, "New Content", "UTF-8", true)
        
        then: "backup file is created"
        new File(testFile.absolutePath + ".backup").exists()
        new File(testFile.absolutePath + ".backup").text == "Original Content"
        testFile.text == "New Content"
    }
    
    def "should list directory contents"() {
        given: "a directory with files"
        tempDir.resolve("file1.txt").toFile().text = "content1"
        tempDir.resolve("file2.groovy").toFile().text = "content2"
        tempDir.resolve("subdir").toFile().mkdir()
        
        when: "listing directory"
        def files = fileSystemService.listDirectory(tempDir.toString(), null, false)
        
        then: "all files are listed"
        files.size() == 3
        files.any { it.name == "file1.txt" }
        files.any { it.name == "file2.groovy" }
        files.any { it.name == "subdir" }
    }
    
    def "should filter files by pattern"() {
        given: "files with different extensions"
        tempDir.resolve("test.groovy").toFile().text = "groovy"
        tempDir.resolve("test.txt").toFile().text = "text"
        tempDir.resolve("test.java").toFile().text = "java"
        
        when: "listing with pattern"
        def files = fileSystemService.listDirectory(tempDir.toString(), ".*\\.groovy\$", false)
        
        then: "only matching files are returned"
        files.size() == 1
        files[0].name == "test.groovy"
    }
    
    def "should search file contents"() {
        given: "files with searchable content"
        tempDir.resolve("file1.groovy").toFile().text = "class MyClass { def foo() {} }"
        tempDir.resolve("file2.groovy").toFile().text = "class Other { def bar() {} }"
        tempDir.resolve("file3.txt").toFile().text = "class MyClass in text"
        
        when: "searching for pattern"
        def results = fileSystemService.searchFiles(tempDir.toString(), "MyClass", ".*\\.groovy\$")
        
        then: "matching files are found"
        results.size() == 1
        results[0].name == "file1.groovy"  // Changed from filename to name
        results[0].matches.size() > 0
    }
    
    def "should copy file"() {
        given: "a source file"
        def source = tempDir.resolve("source.txt").toFile()
        source.text = "Copy me"
        def dest = tempDir.resolve("dest.txt").toString()
        
        when: "copying file"
        def result = fileSystemService.copyFile(source.absolutePath, dest, false)
        
        then: "file is copied"
        result.source != null
        result.destination != null
        new File(dest).text == "Copy me"
        source.exists() // Original still exists
    }
    
    def "should move file"() {
        given: "a source file"
        def source = tempDir.resolve("source.txt").toFile()
        source.text = "Move me"
        def dest = tempDir.resolve("dest.txt").toString()
        
        when: "moving file"
        def result = fileSystemService.moveFile(source.absolutePath, dest, false)
        
        then: "file is moved"
        result.source != null
        result.destination != null
        new File(dest).text == "Move me"
        !source.exists() // Original is gone
    }
    
    def "should delete file"() {
        given: "a file to delete"
        def file = tempDir.resolve("delete-me.txt").toFile()
        file.text = "Delete this"
        
        when: "deleting file"
        def result = fileSystemService.deleteFile(file.absolutePath, false)
        
        then: "file is deleted"
        result.path != null
        result.deleted == true
        !file.exists()
    }
    
    def "should create directory"() {
        given: "a directory path"
        def newDir = tempDir.resolve("new/nested/directory").toString()
        
        when: "creating directory"
        def result = fileSystemService.createDirectory(newDir)
        
        then: "directory is created"
        result.path != null
        result.created == true
        new File(newDir).exists()
        new File(newDir).isDirectory()
    }
    
    def "should reject access outside allowed directories"() {
        given: "a path outside allowed directories"
        def outsidePath = "C:/Windows/System32/config"
        
        when: "attempting to read"
        fileSystemService.readFile(outsidePath)
        
        then: "security exception is thrown"
        thrown(SecurityException)
    }
    
    def "should check if path is allowed"() {
        expect: "path checking works correctly"
        fileSystemService.isPathAllowed(tempDir.toString()) == true
        fileSystemService.isPathAllowed("C:/Windows/System32") == false
    }
}
