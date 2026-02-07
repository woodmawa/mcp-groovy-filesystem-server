package com.softwood.mcp.script

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.model.CommandResult
import com.softwood.mcp.service.FileReadService
import com.softwood.mcp.service.FileWriteService
import com.softwood.mcp.service.FileQueryService
import com.softwood.mcp.service.FileMetadataService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.ScriptExecutor

/**
 * Secure base script for MCP Groovy script execution
 * Provides whitelisted access to file operations, PowerShell, Bash, Git, Gradle
 * REFACTORED: Uses decomposed file services instead of monolithic FileSystemService
 */
abstract class SecureMcpScript extends Script {

    // Injected services (set by GroovyScriptService)
    FileReadService fileReadService
    FileWriteService fileWriteService
    FileQueryService fileQueryService
    FileMetadataService fileMetadataService
    PathService pathService
    ScriptExecutor scriptExecutor
    CommandWhitelistConfig whitelistConfig
    com.softwood.mcp.service.GitHubService githubService

    String getWorkingDir() {
        binding.getVariable('workingDir') as String
    }

    private static String sanitize(String text) {
        if (!text) return text
        return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
    }

    /**
     * Resolve path against working directory if relative
     */
    private String resolvePath(String path) {
        if (!path) return path
        if (path.matches('^[A-Za-z]:[\\\\/].*') || path.startsWith('/')) {
            return path
        }
        return new File(workingDir, path).canonicalPath
    }

    /**
     * Get a File object with path resolved against working directory
     */
    File file(String path) {
        if (!path) return null
        File f = new File(path)
        if (f.isAbsolute()) return f
        return new File(workingDir, path).canonicalFile
    }

    // ========================================================================
    // File Read Operations (via FileReadService)
    // ========================================================================

    def readFile(String path, String encoding = 'UTF-8') {
        fileReadService.readFile(resolvePath(path), encoding)
    }

    def readFileRange(String path, int startLine = 1, int maxLines = 100) {
        fileReadService.readFileRange(resolvePath(path), startLine, maxLines)
    }

    def grepFile(String path, String pattern, int maxMatches = 100) {
        fileReadService.grepFile(resolvePath(path), pattern, maxMatches)
    }

    def tailFile(String path, int lines = 50) {
        fileReadService.tailFile(resolvePath(path), lines)
    }

    def headFile(String path, int lines = 50) {
        fileReadService.headFile(resolvePath(path), lines)
    }

    def countLines(String path) {
        fileReadService.countLines(resolvePath(path))
    }

    def readMultipleFiles(List<String> paths) {
        fileReadService.readMultipleFiles(paths.collect { resolvePath(it) })
    }

    // ========================================================================
    // File Write Operations (via FileWriteService)
    // ========================================================================

    def writeFile(String path, String content, Map options = [:]) {
        String encoding = options.encoding ?: 'UTF-8'
        boolean backup = options.backup ?: false
        fileWriteService.writeFile(resolvePath(path), content, encoding, backup)
    }

    def replaceInFile(String path, String oldText, String newText, Map options = [:]) {
        String encoding = options.encoding ?: 'UTF-8'
        boolean backup = options.backup ?: false
        fileWriteService.replaceInFile(resolvePath(path), oldText, newText, encoding, backup)
    }

    def appendToFile(String path, String content) {
        fileWriteService.appendToFile(resolvePath(path), content)
    }

    def copyFile(String source, String dest, boolean overwrite = false) {
        fileWriteService.copyFile(resolvePath(source), resolvePath(dest), overwrite)
    }

    def moveFile(String source, String dest, boolean overwrite = false) {
        fileWriteService.moveFile(resolvePath(source), resolvePath(dest), overwrite)
    }

    def deleteFile(String path, boolean recursive = false) {
        fileWriteService.deleteFile(resolvePath(path), recursive)
    }

    def createDirectory(String path) {
        fileWriteService.createDirectory(resolvePath(path))
    }

    // ========================================================================
    // File Query Operations (via FileQueryService)
    // ========================================================================

    def listFiles(String path, Map options = [:]) {
        String pattern = options.pattern
        boolean recursive = options.recursive ?: false
        fileQueryService.listDirectory(resolvePath(path), pattern, recursive)
    }

    def searchFiles(String directory, String contentPattern, String filePattern = null) {
        fileQueryService.searchFiles(resolvePath(directory), contentPattern, filePattern ?: '.*')
    }

    def findFilesByName(String pattern, String directory = null, int maxDepth = 5, int maxResults = 100) {
        String searchDir = directory ? resolvePath(directory) : fileMetadataService.getProjectRoot()
        fileQueryService.findFilesByName(pattern, searchDir, maxDepth, maxResults)
    }

    def getDirectoryTree(String path, List<String> excludePatterns = []) {
        fileQueryService.getDirectoryTree(resolvePath(path), excludePatterns)
    }

    def listFilesWithSizes(String path, String sortBy = 'name') {
        fileQueryService.listDirectoryWithSizes(resolvePath(path), sortBy)
    }

    // ========================================================================
    // File Metadata Operations (via FileMetadataService)
    // ========================================================================

    def fileExists(String path) {
        fileMetadataService.fileExists(resolvePath(path))
    }

    def getFileInfo(String path) {
        fileMetadataService.getFileInfo(resolvePath(path))
    }

    def getFileSummary(String path) {
        fileMetadataService.getFileSummary(resolvePath(path))
    }

    // ========================================================================
    // Path Operations
    // ========================================================================

    String toWslPath(String windowsPath) { pathService.convertWindowsToWsl(windowsPath) }
    String toWindowsPath(String wslPath) { pathService.convertWslToWindows(wslPath) }
    String normalizePath(String path) { pathService.normalizePath(path) }
    Map<String, String> getPathInfo(String path) { pathService.getPathRepresentations(path) }

    // ========================================================================
    // Git Operations - Returns CommandResult
    // ========================================================================

    CommandResult git(String... args) { executeCommand('git', args.toList()) }
    CommandResult gitStatus() { git('status') }
    CommandResult gitAdd(String... paths) { git(['add'] + paths.toList() as String[]) }
    CommandResult gitCommit(String message) { git('commit', '-m', message) }
    CommandResult gitPush(String remote = 'origin', String branch = null) {
        branch ? git('push', remote, branch) : git('push', remote)
    }
    CommandResult gitPull(String remote = 'origin', String branch = null) {
        branch ? git('pull', remote, branch) : git('pull', remote)
    }
    CommandResult gitClone(String url, String directory = null) {
        directory ? git('clone', url, directory) : git('clone', url)
    }
    CommandResult gitBranch(String name = null, boolean delete = false) {
        name == null ? git('branch') : (delete ? git('branch', '-d', name) : git('branch', name))
    }
    CommandResult gitCheckout(String branch, boolean createNew = false) {
        createNew ? git('checkout', '-b', branch) : git('checkout', branch)
    }
    CommandResult gitLog(int count = 10) { git('log', '--pretty=format:%h %s', '-n', count.toString()) }
    CommandResult gitDiff(boolean cached = false) { cached ? git('diff', '--cached') : git('diff') }
    String getCurrentBranch() { git('rev-parse', '--abbrev-ref', 'HEAD').stdout.trim() }
    boolean isWorkingDirectoryClean() { git('status', '--porcelain').stdout.trim().isEmpty() }

    // ========================================================================
    // GitHub API Operations
    // ========================================================================

    boolean isGitHubAvailable() { githubService?.isAvailable() ?: false }
    Map<String, Object> githubGetUser() { githubService.getUser() }
    List<Map<String, Object>> githubListRepos(String visibility = 'all') { githubService.listRepos(visibility, 30) }

    Map<String, Object> githubGetRepo(String repo) {
        def parts = repo.split('/')
        if (parts.length == 2) return githubService.getRepo(parts[0], parts[1])
        def user = githubGetUser()
        return githubService.getRepo(user.login as String, repo)
    }

    Map<String, Object> githubCreatePR(String repo, String title, String body, String head, String base = 'main') {
        def parts = repo.split('/')
        if (parts.length != 2) throw new IllegalArgumentException('Repo format must be "owner/repo"')
        githubService.createPullRequest(parts[0], parts[1], title, body, head, base)
    }

    List<Map<String, Object>> githubListPRs(String repo, String state = 'open') {
        def parts = repo.split('/')
        if (parts.length != 2) throw new IllegalArgumentException('Repo format must be "owner/repo"')
        githubService.listPullRequests(parts[0], parts[1], state)
    }

    Map<String, Object> githubCreateIssue(String repo, String title, String body, List<String> labels = []) {
        def parts = repo.split('/')
        if (parts.length != 2) throw new IllegalArgumentException('Repo format must be "owner/repo"')
        githubService.createIssue(parts[0], parts[1], title, body, labels)
    }

    List<Map<String, Object>> githubListIssues(String repo, String state = 'open') {
        def parts = repo.split('/')
        if (parts.length != 2) throw new IllegalArgumentException('Repo format must be "owner/repo"')
        githubService.listIssues(parts[0], parts[1], state)
    }

    Map<String, Object> githubGetFile(String repo, String path, String ref = 'main') {
        def parts = repo.split('/')
        if (parts.length != 2) throw new IllegalArgumentException('Repo format must be "owner/repo"')
        githubService.getFileContents(parts[0], parts[1], path, ref)
    }

    // ========================================================================
    // Gradle Operations
    // ========================================================================

    CommandResult gradle(String... args) {
        List<String> cmdArgs = ['cmd', '/c', 'gradlew.bat'] + args.toList()
        executeCommand(cmdArgs[0], cmdArgs.drop(1))
    }

    CommandResult gradlew(String... args) { gradle(args) }

    // ========================================================================
    // PowerShell / Bash (Whitelisted)
    // ========================================================================

    CommandResult powershell(String command) {
        if (!whitelistConfig.isPowershellAllowed(command)) {
            throw new SecurityException("PowerShell command not whitelisted: ${sanitize(command.take(50))}")
        }
        scriptExecutor.executePowerShell(command, workingDir)
    }

    CommandResult ps(String command) { powershell(command) }

    CommandResult bash(String command) {
        if (!whitelistConfig.isBashAllowed(command)) {
            throw new SecurityException("Bash command not whitelisted: ${sanitize(command.take(50))}")
        }
        scriptExecutor.executeBash(command, workingDir)
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private CommandResult executeCommand(String executable, List args) {
        scriptExecutor.executeCommand(executable, args, workingDir)
    }

    void println(Object message) {
        def output = binding.getVariable('scriptOutput') as List
        output.add(message.toString())
    }

    void print(Object message) {
        def output = binding.getVariable('scriptOutput') as List
        if (output.isEmpty()) {
            output.add(message.toString())
        } else {
            String lastItem = output.last() as String
            output.set(output.size() - 1, lastItem + message.toString())
        }
    }
}
