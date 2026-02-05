package com.softwood.mcp.service

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.controller.McpController
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Tests for FileSystemService
 *  INCLUDES TESTS FOR NEW BOUNDED METHODS
 */
class FileSystemServiceSpec extends Specification {

    @TempDir
    Path tempDir

    FileSystemService fileSystemService
    PathService pathService

    //  ADD THESE
    AuditService auditService
    ScriptExecutor scriptExecutor
    ScriptSecurityService securityService
    CommandWhitelistConfig whitelistConfig
    GitHubService githubService
    GroovyScriptService groovyScriptService
    McpController controller   // if you use it in tests

    def setup() {
        pathService = new PathService()
        auditService = new AuditService()
        scriptExecutor = new ScriptExecutor(auditService)
        securityService = new ScriptSecurityService()

        // Create mock whitelist config with permissive patterns for testing
        whitelistConfig = new CommandWhitelistConfig()
        whitelistConfig.powershellAllowed = ['.*']  // Allow all in tests
        whitelistConfig.powershellBlocked = []
        whitelistConfig.bashAllowed = ['.*']  // Allow all in tests
        whitelistConfig.bashBlocked = []
        
        // Mock GitHubService
        githubService = Mock(GitHubService)

        fileSystemService = new FileSystemService(pathService)
        fileSystemService.allowedDirectoriesString = tempDir.toString()
        fileSystemService.init()
        fileSystemService.enableWrite = true

        //  FIX: SET BOUNDED LIMITS FOR TESTS (prevents 0-limit failures)
        fileSystemService.maxListResults = 100
        fileSystemService.maxSearchResults = 50
        fileSystemService.maxSearchMatchesPerFile = 10
        fileSystemService.maxTreeDepth = 5
        fileSystemService.maxTreeFiles = 200
        fileSystemService.maxReadMultiple = 10
        fileSystemService.maxLineLength = 1000
        fileSystemService.maxResponseSizeKb = 100
        fileSystemService.maxFileSizeMb = 10
        fileSystemService.activeProjectRoot = tempDir.toString()

        groovyScriptService = new GroovyScriptService(
                fileSystemService,
                pathService,
                scriptExecutor,
                securityService,
                auditService,
                whitelistConfig,
                githubService
        )

        controller = new McpController(
                fileSystemService,
                pathService,
                groovyScriptService
        )
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

    //  NEW TEST
    def "should read file range with line limits"() {
        given: "a file with multiple lines"
        def testFile = tempDir.resolve("multiline.txt").toFile()
        testFile.text = (1..200).collect { "Line $it" }.join('\n')

        when: "reading a specific range"
        def result = fileSystemService.readFileRange(testFile.absolutePath, 10, 20)

        then: "only requested lines are returned"
        result.startLine == 10
        result.actualLines == 20
        result.lines.size() == 20
        result.lines[0].contains("Line 10")
        result.truncated == true  // More lines exist
    }

    //  NEW TEST
    def "should enforce max line length in readFileRange"() {
        given: "a file with very long lines"
        def testFile = tempDir.resolve("longlines.txt").toFile()
        testFile.text = "a" * 2000  // Single line with 2000 chars

        when: "reading the file"
        def result = fileSystemService.readFileRange(testFile.absolutePath, 1, 10)

        then: "line is truncated"
        result.lines[0].length() <= 1020  // maxLineLength + "... (truncated)"
        result.lines[0].contains("(truncated)")
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

    //  NEW TEST
    def "should list only immediate children with listChildrenOnly"() {
        given: "a directory with nested structure"
        tempDir.resolve("file1.txt").toFile().text = "content1"
        tempDir.resolve("file2.txt").toFile().text = "content2"
        def subdir = tempDir.resolve("subdir").toFile()
        subdir.mkdir()
        new File(subdir, "nested.txt").text = "nested content"

        when: "listing children only"
        def files = fileSystemService.listChildrenOnly(tempDir.toString(), null, -1)

        then: "only immediate children are returned"
        files.size() == 3
        files.any { it.name == "file1.txt" }
        files.any { it.name == "subdir" }
        !files.any { it.name == "nested.txt" }  // Nested file NOT included
    }

    //  NEW TEST
    def "should enforce max results in listChildrenOnly"() {
        given: "a directory with many files"
        (1..150).each { i ->
            tempDir.resolve("file${i}.txt").toFile().text = "content"
        }

        when: "listing with limit"
        def files = fileSystemService.listChildrenOnly(tempDir.toString(), null, 50)

        then: "only max results are returned"
        files.size() == 50
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
        results[0].name == "file1.groovy"
        results[0].matches.size() > 0
    }

    //  NEW TEST
    def "should enforce max search matches per file"() {
        given: "a file with many matches"
        def lines = (1..50).collect { "MyClass appears here" }.join('\n')
        tempDir.resolve("manymatches.groovy").toFile().text = lines

        when: "searching"
        def results = fileSystemService.searchFiles(tempDir.toString(), "MyClass", ".*\\.groovy\$")

        then: "only max matches per file are returned"
        results.size() == 1
        results[0].matches.size() == 10  // maxSearchMatchesPerFile
        results[0].truncatedMatches == true
    }

    //  NEW TEST
    def "should search in project root with searchInProject"() {
        given: "files in project root"
        tempDir.resolve("App.groovy").toFile().text = "class App { def main() {} }"
        tempDir.resolve("Test.groovy").toFile().text = "class Test { def test() {} }"

        when: "searching in project"
        def results = fileSystemService.searchInProject("class", ".*\\.groovy\$", -1)

        then: "files are found in project root"
        results.size() == 2
    }

    //  NEW TEST
    def "should get project root"() {
        when: "getting project root"
        def root = fileSystemService.getProjectRoot()

        then: "active project root is returned"
        root == tempDir.toString().replace('\\', '/')
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

    //  NEW TEST
    def "should enforce max results in readMultipleFiles"() {
        given: "many files"
        (1..20).each { i ->
            tempDir.resolve("file${i}.txt").toFile().text = "content $i"
        }
        def paths = (1..20).collect { tempDir.resolve("file${it}.txt").toString() }

        when: "reading multiple files"
        def results = fileSystemService.readMultipleFiles(paths)

        then: "only max allowed files are read"
        results.size() == 10  // maxReadMultiple
    }
}