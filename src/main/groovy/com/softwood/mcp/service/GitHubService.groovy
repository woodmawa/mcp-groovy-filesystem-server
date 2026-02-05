package com.softwood.mcp.service

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Service

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * GitHub API integration service
 * Requires GITHUB_API_KEY or GITHUB_TOKEN environment variable
 */
@Service
@Slf4j
@CompileStatic
class GitHubService {

    private final String apiToken
    private final HttpClient httpClient
    private static final String GITHUB_API_BASE = "https://api.github.com"

    GitHubService() {
        // Check for GitHub API token in environment
        this.apiToken = System.getenv('GITHUB_API_KEY') ?: System.getenv('GITHUB_TOKEN')
        this.httpClient = HttpClient.newHttpClient()
        
        if (apiToken) {
            log.info("GitHub API token found - GitHub integration enabled")
        } else {
            log.warn("No GITHUB_API_KEY or GITHUB_TOKEN found - GitHub API disabled")
        }
    }

    /**
     * Check if GitHub API is available
     */
    boolean isAvailable() {
        return apiToken != null && !apiToken.isEmpty()
    }

    /**
     * Make authenticated request to GitHub API
     */
    private Map<String, Object> makeRequest(String method, String endpoint, Map<String, Object> body = null) {
        if (!isAvailable()) {
            throw new IllegalStateException("GitHub API not available - set GITHUB_API_KEY or GITHUB_TOKEN")
        }

        try {
            def requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("${GITHUB_API_BASE}${endpoint}"))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer ${apiToken}")
                .header("X-GitHub-Api-Version", "2022-11-28")

            if (method == 'GET') {
                requestBuilder.GET()
            } else if (method == 'POST' && body) {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
                requestBuilder.header("Content-Type", "application/json")
            } else if (method == 'PATCH' && body) {
                requestBuilder.method('PATCH', HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
                requestBuilder.header("Content-Type", "application/json")
            }

            def response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() >= 400) {
                throw new RuntimeException("GitHub API error: ${response.statusCode()} - ${response.body()}")
            }

            return new JsonSlurper().parseText(response.body()) as Map<String, Object>
        } catch (Exception e) {
            log.error("GitHub API request failed: ${e.message}")
            throw e
        }
    }

    /**
     * Get authenticated user info
     */
    Map<String, Object> getUser() {
        makeRequest('GET', '/user')
    }

    /**
     * List repositories for authenticated user
     */
    List<Map<String, Object>> listRepos(String visibility = 'all', int perPage = 30) {
        def result = makeRequest('GET', "/user/repos?visibility=${visibility}&per_page=${perPage}".toString())
        return result as List<Map<String, Object>>
    }

    /**
     * Get repository info
     */
    Map<String, Object> getRepo(String owner, String repo) {
        makeRequest('GET', "/repos/${owner}/${repo}".toString())
    }

    /**
     * Create a pull request
     */
    Map<String, Object> createPullRequest(String owner, String repo, String title, String body, String head, String base = 'main') {
        Map<String, Object> requestBody = [
            title: title,
            body: body,
            head: head,
            base: base
        ]
        makeRequest('POST', "/repos/${owner}/${repo}/pulls".toString(), requestBody)
    }

    /**
     * List pull requests
     */
    List<Map<String, Object>> listPullRequests(String owner, String repo, String state = 'open') {
        def result = makeRequest('GET', "/repos/${owner}/${repo}/pulls?state=${state}".toString())
        return result as List<Map<String, Object>>
    }

    /**
     * Create an issue
     */
    Map<String, Object> createIssue(String owner, String repo, String title, String body, List<String> labels = []) {
        Map<String, Object> requestBody = [
            title: title,
            body: body,
            labels: labels
        ]
        makeRequest('POST', "/repos/${owner}/${repo}/issues".toString(), requestBody)
    }

    /**
     * List issues
     */
    List<Map<String, Object>> listIssues(String owner, String repo, String state = 'open') {
        def result = makeRequest('GET', "/repos/${owner}/${repo}/issues?state=${state}".toString())
        return result as List<Map<String, Object>>
    }

    /**
     * Get file contents from repository
     */
    Map<String, Object> getFileContents(String owner, String repo, String path, String ref = 'main') {
        makeRequest('GET', "/repos/${owner}/${repo}/contents/${path}?ref=${ref}".toString())
    }

    /**
     * Create or update file in repository
     */
    Map<String, Object> createOrUpdateFile(String owner, String repo, String path, String message, String content, String sha = null, String branch = 'main') {
        Map<String, Object> requestBody = [
            message: message,
            content: content.bytes.encodeBase64().toString(),
            branch: branch
        ]
        if (sha) {
            requestBody.sha = sha
        }
        makeRequest('PUT', "/repos/${owner}/${repo}/contents/${path}".toString(), requestBody)
    }
}