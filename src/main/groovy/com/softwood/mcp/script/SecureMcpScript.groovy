package com.softwood.mcp.script

import com.softwood.mcp.config.CommandWhitelistConfig
import com.softwood.mcp.model.CommandResult
import com.softwood.mcp.service.FileSystemService
import com.softwood.mcp.service.PathService
import com.softwood.mcp.service.ScriptExecutor

/**
 * Secure base script for MCP Groovy script execution
 * Provides whitelisted access to:
 * - File operations (via FileSystemService)
 * - PowerShell commands (whitelisted)
 * - Bash commands (whitelisted)
 * - Git operations
 * - Gradle operations
 *
 * Enhanced with typed CommandResult return values and configurable whitelists
 */
abstract class SecureMcpScript extends Script {

    // Injected services (set by GroovyScriptService) - properly typed
    FileSystemService fileSystemService
    PathService pathService
    ScriptExecutor scriptExecutor
    CommandWhitelistConfig whitelistConfig
    com.softwood.mcp.service.GitHubService githubService

    // Working directory for script execution
    String getWorkingDir() {
        binding.getVariable('workingDir') as String
    }

    /**
     * Sanitize string by removing control characters (except newlines and tabs)
     * Ensures clean exception messages for JSON serialization
     */
    private static String sanitize(String text) {
        if (!text) return text
        // Remove control characters except \n (10) and \t (9)
        return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
    }

    /**
     * Resolve path against working directory if relative
     * Absolute paths are returned unchanged
     */
    private String resolvePath(String path) {
        if (!path) return path
        
        // Check if already absolute (Windows: C:/ or Linux: /)
        if (path.matches('^[A-Za-z]:[\\\\/].*') || path.startsWith('/')) {
            return path
        }
        
        // Relative path - resolve against workingDir
        return new File(workingDir, path).canonicalPath
    }

    // ========================================================================
    // File Operations (delegated to FileSystemService)
    // ========================================================================

    def readFile(String path, String encoding = 'UTF-8') {
        fileSystemService.readFile(resolvePath(path), encoding)
    }

    def writeFile(String path, String content, Map options = [:]) {
        String encoding = options.encoding ?: 'UTF-8'
        boolean backup = options.backup ?: false
        fileSystemService.writeFile(resolvePath(path), content, encoding, backup)
    }

    def listFiles(String path, Map options = [:]) {
        String pattern = options.pattern
        boolean recursive = options.recursive ?: false
        fileSystemService.listDirectory(resolvePath(path), pattern, recursive)
    }

    def searchFiles(String directory, String contentPattern, String filePattern = null) {
        fileSystemService.searchFiles(resolvePath(directory), contentPattern, filePattern ?: '.*')
    }

    def copyFile(String source, String dest, boolean overwrite = false) {
        fileSystemService.copyFile(resolvePath(source), resolvePath(dest), overwrite)
    }

    def moveFile(String source, String dest, boolean overwrite = false) {
        fileSystemService.moveFile(resolvePath(source), resolvePath(dest), overwrite)
    }

    def deleteFile(String path, boolean recursive = false) {
        fileSystemService.deleteFile(resolvePath(path), recursive)
    }

    def createDirectory(String path) {
        fileSystemService.createDirectory(resolvePath(path))
    }

    def readMultipleFiles(List<String> paths) {
        fileSystemService.readMultipleFiles(paths.collect { resolvePath(it) })
    }

    def getFileInfo(String path) {
        fileSystemService.getFileInfo(resolvePath(path))
    }

    def listFilesWithSizes(String path, String sortBy = 'name') {
        fileSystemService.listDirectoryWithSizes(resolvePath(path), sortBy)
    }

    def getDirectoryTree(String path, List<String> excludePatterns = []) {
        fileSystemService.getDirectoryTree(resolvePath(path), excludePatterns)
    }

    // ========================================================================


    //  NEW TOKEN-OPTIMIZED FILE OPERATIONS
    def grepFile(String path, String pattern, int maxMatches = 100) {
        fileSystemService.grepFile(resolvePath(path), pattern, maxMatches)
    }

    def tailFile(String path, int lines = 50) {
        fileSystemService.tailFile(resolvePath(path), lines)
    }

    def headFile(String path, int lines = 50) {
        fileSystemService.headFile(resolvePath(path), lines)
    }

    def fileExists(String path) {
        fileSystemService.fileExists(resolvePath(path))
    }

    def countLines(String path) {
        fileSystemService.countLines(resolvePath(path))
    }

    def findFilesByName(String pattern, String directory = null, int maxDepth = 5, int maxResults = 100) {
        String searchDir = directory ? resolvePath(directory) : fileSystemService.getProjectRoot()
        fileSystemService.findFilesByName(pattern, searchDir, maxDepth, maxResults)
    }

    def getFileSummary(String path) {
        fileSystemService.getFileSummary(resolvePath(path))
    }

        // ========================================================================
    // Path Operations
    // ========================================================================

    String toWslPath(String windowsPath) {
        pathService.convertWindowsToWsl(windowsPath)
    }

    String toWindowsPath(String wslPath) {
        pathService.convertWslToWindows(wslPath)
    }

    String normalizePath(String path) {
        pathService.normalizePath(path)
    }

    Map<String, String> getPathInfo(String path) {
        pathService.getPathRepresentations(path)
    }

    // ========================================================================
    // Git Operations - Returns CommandResult
    // ========================================================================

    CommandResult git(String... args) {
        executeCommand('git', args.toList())
    }

    /**
     * Git status - shows working tree status
     */
    CommandResult gitStatus() {
        git('status')
    }

    /**
     * Git add - stage files for commit
     */
    CommandResult gitAdd(String... paths) {
        git(['add'] + paths.toList() as String[])
    }

    /**
     * Git commit - commit staged changes
     */
    CommandResult gitCommit(String message) {
        git('commit', '-m', message)
    }

    /**
     * Git push - push commits to remote
     */
    CommandResult gitPush(String remote = 'origin', String branch = null) {
        if (branch) {
            git('push', remote, branch)
        } else {
            git('push', remote)
        }
    }

    /**
     * Git pull - fetch and merge from remote
     */
    CommandResult gitPull(String remote = 'origin', String branch = null) {
        if (branch) {
            git('pull', remote, branch)
        } else {
            git('pull', remote)
        }
    }

    /**
     * Git clone - clone a repository
     */
    CommandResult gitClone(String url, String directory = null) {
        if (directory) {
            git('clone', url, directory)
        } else {
            git('clone', url)
        }
    }

    /**
     * Git branch - list, create, or delete branches
     */
    CommandResult gitBranch(String name = null, boolean delete = false) {
        if (name == null) {
            git('branch')
        } else if (delete) {
            git('branch', '-d', name)
        } else {
            git('branch', name)
        }
    }

    /**
     * Git checkout - switch branches or restore files
     */
    CommandResult gitCheckout(String branch, boolean createNew = false) {
        if (createNew) {
            git('checkout', '-b', branch)
        } else {
            git('checkout', branch)
        }
    }

    /**
     * Git log - show commit history
     */
    CommandResult gitLog(int count = 10) {
        git('log', '--pretty=format:%h %s', '-n', count.toString())
    }

    /**
     * Git diff - show changes
     */
    CommandResult gitDiff(boolean cached = false) {
        if (cached) {
            git('diff', '--cached')
        } else {
            git('diff')
        }
    }

    /**
     * Get current git branch name
     */
    String getCurrentBranch() {
        def result = git('rev-parse', '--abbrev-ref', 'HEAD')
        return result.stdout.trim()
    }

    /**
     * Check if working directory is clean
     */
    boolean isWorkingDirectoryClean() {
        def result = git('status', '--porcelain')
        return result.stdout.trim().isEmpty()
    }

    // ========================================================================
    // GitHub API Operations - Requires GITHUB_API_KEY or GITHUB_TOKEN
    // ========================================================================

    /**
     * Check if GitHub API is available
     */
    boolean isGitHubAvailable() {
        return githubService?.isAvailable() ?: false
    }

    /**
     * Get authenticated GitHub user
     */
    Map<String, Object> githubGetUser() {
        githubService.getUser()
    }

    /**
     * List repositories
     */
    List<Map<String, Object>> githubListRepos(String visibility = 'all') {
        githubService.listRepos(visibility, 30)
    }

    /**
     * Get repository info
     * @param repo Format: "owner/repo" or just "repo" (uses current user)
     */
    Map<String, Object> githubGetRepo(String repo) {
        def parts = repo.split('/')
        if (parts.length == 2) {
            return githubService.getRepo(parts[0], parts[1])
        } else {
            def user = githubGetUser()
            return githubService.getRepo(user.login as String, repo)
        }
    }

    /**
     * Create a pull request
     * @param repo Format: "owner/repo"
     * @param title PR title
     * @param body PR description
     * @param head Source branch
     * @param base Target branch (default: main)
     */
    Map<String, Object> githubCreatePR(String repo, String title, String body, String head, String base = 'main') {
        def parts = repo.split('/')
        if (parts.length != 2) {
            throw new IllegalArgumentException('Repo format must be "owner/repo"')
        }
        githubService.createPullRequest(parts[0], parts[1], title, body, head, base)
    }

    /**
     * List pull requests
     * @param repo Format: "owner/repo"
     * @param state State: open, closed, all (default: open)
     */
    List<Map<String, Object>> githubListPRs(String repo, String state = 'open') {
        def parts = repo.split('/')
        if (parts.length != 2) {
            throw new IllegalArgumentException('Repo format must be "owner/repo"')
        }
        githubService.listPullRequests(parts[0], parts[1], state)
    }

    /**
     * Create an issue
     * @param repo Format: "owner/repo"
     * @param title Issue title
     * @param body Issue description
     * @param labels List of label names
     */
    Map<String, Object> githubCreateIssue(String repo, String title, String body, List<String> labels = []) {
        def parts = repo.split('/')
        if (parts.length != 2) {
            throw new IllegalArgumentException('Repo format must be "owner/repo"')
        }
        githubService.createIssue(parts[0], parts[1], title, body, labels)
    }

    /**
     * List issues
     * @param repo Format: "owner/repo"
     * @param state State: open, closed, all (default: open)
     */
    List<Map<String, Object>> githubListIssues(String repo, String state = 'open') {
        def parts = repo.split('/')
        if (parts.length != 2) {
            throw new IllegalArgumentException('Repo format must be "owner/repo"')
        }
        githubService.listIssues(parts[0], parts[1], state)
    }

    /**
     * Get file contents from repository
     * @param repo Format: "owner/repo"
     * @param path File path in repo
     * @param ref Branch/tag/commit (default: main)
     */
    Map<String, Object> githubGetFile(String repo, String path, String ref = 'main') {
        def parts = repo.split('/')
        if (parts.length != 2) {
            throw new IllegalArgumentException('Repo format must be "owner/repo"')
        }
        githubService.getFileContents(parts[0], parts[1], path, ref)
    }

    // ========================================================================
    // Gradle Operations - Returns CommandResult
    // ========================================================================

    CommandResult gradle(String... args) {
        // Use gradlew.bat through cmd.exe on Windows
        List<String> cmdArgs = ['cmd', '/c', 'gradlew.bat'] + args.toList()
        executeCommand(cmdArgs[0], cmdArgs.drop(1))
    }

    CommandResult gradlew(String... args) {
        // Use gradlew.bat through cmd.exe on Windows
        List<String> cmdArgs = ['cmd', '/c', 'gradlew.bat'] + args.toList()
        executeCommand(cmdArgs[0], cmdArgs.drop(1))
    }

    // ========================================================================
    // PowerShell Operations (Whitelisted) - Returns CommandResult
    // ========================================================================

    CommandResult powershell(String command) {
        if (!whitelistConfig.isPowershellAllowed(command)) {
            throw new SecurityException("PowerShell command not whitelisted: ${sanitize(command.take(50))}")
        }
        executePowerShell(command)
    }

    CommandResult ps(String command) {
        powershell(command)
    }

    private CommandResult executePowerShell(String command) {
        scriptExecutor.executePowerShell(command, workingDir)
    }

    // ========================================================================
    // Bash Operations (Whitelisted) - Returns CommandResult
    // ========================================================================

    CommandResult bash(String command) {
        if (!whitelistConfig.isBashAllowed(command)) {
            throw new SecurityException("Bash command not whitelisted: ${sanitize(command.take(50))}")
        }
        executeBash(command)
    }

    private CommandResult executeBash(String command) {
        scriptExecutor.executeBash(command, workingDir)
    }

    // ========================================================================
    // Generic Command Execution (Used by git, gradle, etc.) - Returns CommandResult
    // ========================================================================

    private CommandResult executeCommand(String executable, List args) {
        scriptExecutor.executeCommand(executable, args, workingDir)
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    void println(Object message) {
        // Capture output for return to MCP
        def output = binding.getVariable('scriptOutput') as List
        output.add(message.toString())
    }

    void print(Object message) {
        def output = binding.getVariable('scriptOutput') as List
        if (output.isEmpty()) {
            output.add(message.toString())
        } else {
            // FIX: Use List.last() instead of [-1] for @CompileStatic
            String lastItem = output.last() as String
            output.set(output.size() - 1, lastItem + message.toString())
        }
    }

    // ========================================================================
    //  Token Optimization Override - Temporarily modify bounded result limits
    // ========================================================================

    /**
     * Execute a closure with temporarily overridden bounded result limits.
     * Limits are restored after the closure completes (even if it throws).
     * 
     * Example:
     * <pre>
     * withOverride(maxListResults: 500, maxSearchResults: 100) {
     *     def files = listFiles('src/main', recursive: true)
     *     def matches = searchFiles('src', 'pattern')
     * }
     * </pre>
     * 
     * Available overrides:
     * - maxListResults: Max files returned by listDirectory (default: 100)
     * - maxSearchResults: Max search matches total (default: 50)
     * - maxSearchMatchesPerFile: Max matches per file in searchFiles (default: 10)
     * - maxTreeDepth: Max directory tree depth (default: 5)
     * - maxTreeFiles: Max total files in directory tree (default: 200)
     * - maxReadMultiple: Max files in readMultipleFiles batch (default: 10)
     * - maxLineLength: Max chars per line (default: 1000)
     * - maxResponseSizeKb: Max response size before warning (default: 100)
     * 
     * @param overrides Map of limit names to override values
     * @param closure Code to execute with overridden limits
     * @return Result of closure execution
     */
    def withOverride(Map<String, Object> overrides, Closure closure) {
        // Store original values
        Map<String, Object> originalValues = [:]
        
        try {
            // Apply overrides and save originals
            overrides.each { String key, Object value ->
                // Store original value from fileSystemService
                originalValues[key] = fileSystemService."$key"
                // Set new value
                fileSystemService."$key" = value
            }
            
            // Execute the closure with overridden limits
            return closure.call()
            
        } finally {
            // Always restore original values
            originalValues.each { String key, Object value ->
                fileSystemService."$key" = value
            }
        }
    }
}