package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Tests for decomposed file services: FileReadService, FileWriteService, FileQueryService, FileMetadataService
 * Replaces old FileSystemServiceSpec
 */
class FileServicesSpec extends Specification {

    @TempDir
    Path tempDir

    PathService pathService
    FileReadService fileReadService
    FileWriteService fileWriteService
    FileQueryService fileQueryService
    FileMetadataService fileMetadataService

    def setup() {
        pathService = new PathService()
        pathService.activeProjectRoot = tempDir.toString()

        fileReadService = new FileReadService(pathService)
        fileWriteService = new FileWriteService(pathService)
        fileQueryService = new FileQueryService(pathService)
        fileMetadataService = new FileMetadataService(pathService)

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
    }

    // ========================================================================
    // FileReadService tests
    // ========================================================================

    def "should read file contents"() {
        given: "a test file with content"
        def testFile = tempDir.resolve("test.txt").toFile()
        testFile.text = "Hello World"

        when: "reading the file"
        def content = fileReadService.readFile(testFile.absolutePath)

        then: "content is returned"
        content == "Hello World"
    }

    def "should read file range with line limits"() {
        given: "a file with multiple lines"
        def testFile = tempDir.resolve("multiline.txt").toFile()
        testFile.text = (1..200).collect { "Line $it" }.join('\n')

        when: "reading a specific range"
        def result = fileReadService.readFileRange(testFile.absolutePath, 10, 20)

        then: "only requested lines are returned"
        result.startLine == 10
        result.actualLines == 20
        result.lines.size() == 20
        result.lines[0].contains("Line 10")
        result.truncated == true
    }

    def "should enforce max line length in readFileRange"() {
        given: "a file with very long lines"
        def testFile = tempDir.resolve("longlines.txt").toFile()
        testFile.text = "a" * 2000

        when: "reading the file"
        def result = fileReadService.readFileRange(testFile.absolutePath, 1, 10)

        then: "line is truncated"
        result.lines[0].length() <= 1020
        result.lines[0].contains("(truncated)")
    }

    def "should enforce max results in readMultipleFiles"() {
        given: "many files"
        (1..20).each { i ->
            tempDir.resolve("file${i}.txt").toFile().text = "content $i"
        }
        def paths = (1..20).collect { tempDir.resolve("file${it}.txt").toString() }

        when: "reading multiple files"
        def results = fileReadService.readMultipleFiles(paths)

        then: "only max allowed files are read"
        results.size() == 10
    }

    // ========================================================================
    // FileWriteService tests
    // ========================================================================

    def "should write file contents"() {
        given: "a file path"
        def testFile = tempDir.resolve("output.txt")

        when: "writing content"
        def result = fileWriteService.writeFile(testFile.toString(), "Test Content", "UTF-8", false)

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
        fileWriteService.writeFile(testFile.absolutePath, "New Content", "UTF-8", true)

        then: "backup file is created"
        new File(testFile.absolutePath + ".backup").exists()
        new File(testFile.absolutePath + ".backup").text == "Original Content"
        testFile.text == "New Content"
    }

    def "should replace unique text in file"() {
        given: "a file with content"
        def testFile = tempDir.resolve("replace.txt").toFile()
        testFile.text = "Hello World, this is a test"

        when: "replacing text"
        def result = fileWriteService.replaceInFile(testFile.absolutePath, "World", "Groovy")

        then: "text is replaced"
        result.replacements == 1
        testFile.text == "Hello Groovy, this is a test"
    }

    def "should fail replacing non-unique text"() {
        given: "a file with duplicate text"
        def testFile = tempDir.resolve("dupes.txt").toFile()
        testFile.text = "foo bar foo baz"

        when: "replacing non-unique text"
        fileWriteService.replaceInFile(testFile.absolutePath, "foo", "replaced")

        then: "exception is thrown"
        thrown(IllegalArgumentException)
    }

    def "should append to file"() {
        given: "an existing file"
        def testFile = tempDir.resolve("append.txt").toFile()
        testFile.text = "Line 1\n"

        when: "appending content"
        fileWriteService.appendToFile(testFile.absolutePath, "Line 2\n")

        then: "content is appended"
        testFile.text == "Line 1\nLine 2\n"
    }

    def "should copy file"() {
        given: "a source file"
        def source = tempDir.resolve("source.txt").toFile()
        source.text = "Copy me"
        def dest = tempDir.resolve("dest.txt").toString()

        when: "copying file"
        def result = fileWriteService.copyFile(source.absolutePath, dest, false)

        then: "file is copied"
        result.source != null
        result.destination != null
        new File(dest).text == "Copy me"
        source.exists()
    }

    def "should move file"() {
        given: "a source file"
        def source = tempDir.resolve("source.txt").toFile()
        source.text = "Move me"
        def dest = tempDir.resolve("dest.txt").toString()

        when: "moving file"
        def result = fileWriteService.moveFile(source.absolutePath, dest, false)

        then: "file is moved"
        result.source != null
        result.destination != null
        new File(dest).text == "Move me"
        !source.exists()
    }

    def "should delete file"() {
        given: "a file to delete"
        def file = tempDir.resolve("delete-me.txt").toFile()
        file.text = "Delete this"

        when: "deleting file"
        def result = fileWriteService.deleteFile(file.absolutePath, false)

        then: "file is deleted"
        result.path != null
        result.deleted == true
        !file.exists()
    }

    def "should create directory"() {
        given: "a directory path"
        def newDir = tempDir.resolve("new/nested/directory").toString()

        when: "creating directory"
        def result = fileWriteService.createDirectory(newDir)

        then: "directory is created"
        result.path != null
        result.created == true
        new File(newDir).exists()
        new File(newDir).isDirectory()
    }

    // ========================================================================
    // FileQueryService tests
    // ========================================================================

    def "should list directory contents"() {
        given: "a directory with files"
        tempDir.resolve("file1.txt").toFile().text = "content1"
        tempDir.resolve("file2.groovy").toFile().text = "content2"
        tempDir.resolve("subdir").toFile().mkdir()

        when: "listing directory"
        def files = fileQueryService.listDirectory(tempDir.toString(), null, false)

        then: "all files are listed"
        files.size() == 3
        files.any { it.name == "file1.txt" }
        files.any { it.name == "file2.groovy" }
        files.any { it.name == "subdir" }
    }

    def "should list only immediate children with listChildrenOnly"() {
        given: "a directory with nested structure"
        tempDir.resolve("file1.txt").toFile().text = "content1"
        tempDir.resolve("file2.txt").toFile().text = "content2"
        def subdir = tempDir.resolve("subdir").toFile()
        subdir.mkdir()
        new File(subdir, "nested.txt").text = "nested content"

        when: "listing children only"
        def files = fileQueryService.listChildrenOnly(tempDir.toString(), null, -1)

        then: "only immediate children are returned"
        files.size() == 3
        files.any { it.name == "file1.txt" }
        files.any { it.name == "subdir" }
        !files.any { it.name == "nested.txt" }
    }

    def "should enforce max results in listChildrenOnly"() {
        given: "a directory with many files"
        (1..150).each { i ->
            tempDir.resolve("file${i}.txt").toFile().text = "content"
        }

        when: "listing with limit"
        def files = fileQueryService.listChildrenOnly(tempDir.toString(), null, 50)

        then: "only max results are returned"
        files.size() == 50
    }

    def "should filter files by pattern"() {
        given: "files with different extensions"
        tempDir.resolve("test.groovy").toFile().text = "groovy"
        tempDir.resolve("test.txt").toFile().text = "text"
        tempDir.resolve("test.java").toFile().text = "java"

        when: "listing with pattern"
        def files = fileQueryService.listDirectory(tempDir.toString(), ".*\\.groovy\$", false)

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
        def results = fileQueryService.searchFiles(tempDir.toString(), "MyClass", ".*\\.groovy\$")

        then: "matching files are found"
        results.size() == 1
        results[0].name == "file1.groovy"
        results[0].matches.size() > 0
    }

    def "should enforce max search matches per file"() {
        given: "a file with many matches"
        def lines = (1..50).collect { "MyClass appears here" }.join('\n')
        tempDir.resolve("manymatches.groovy").toFile().text = lines

        when: "searching"
        def results = fileQueryService.searchFiles(tempDir.toString(), "MyClass", ".*\\.groovy\$")

        then: "only max matches per file are returned"
        results.size() == 1
        results[0].matches.size() == 10
        results[0].truncatedMatches == true
    }

    def "should search in project root with searchInProject"() {
        given: "files in project root"
        tempDir.resolve("App.groovy").toFile().text = "class App { def main() {} }"
        tempDir.resolve("Test.groovy").toFile().text = "class Test { def test() {} }"

        when: "searching in project"
        def results = fileQueryService.searchInProject("class", ".*\\.groovy\$", -1)

        then: "files are found in project root"
        results.size() == 2
    }

    // ========================================================================
    // FileMetadataService tests
    // ========================================================================

    def "should get project root"() {
        when: "getting project root"
        def root = fileMetadataService.getProjectRoot()

        then: "active project root is returned"
        root == tempDir.toString().replace('\\', '/')
    }

    def "should check if path is allowed"() {
        expect: "path checking works correctly"
        fileMetadataService.isPathAllowed(tempDir.toString()) == true
        fileMetadataService.isPathAllowed("C:/Windows/System32") == false
    }

    def "should reject access outside allowed directories"() {
        given: "a path outside allowed directories"
        def outsidePath = "C:/Windows/System32/config"

        when: "attempting to read"
        fileReadService.readFile(outsidePath)

        then: "security exception is thrown"
        thrown(SecurityException)
    }
}