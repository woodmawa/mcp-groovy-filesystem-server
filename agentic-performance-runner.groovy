#!/usr/bin/env groovy
/**
 * Agentic Performance Review Runner
 * Two-pass: ASSESS → PLAN, then REVIEW → FEEDBACK
 *
 * Pass 1 (Assess + Plan): Each service file is assessed for memory, streaming,
 *   promise usage, and performance. A plan of improvements is generated.
 *
 * Pass 2 (Review + Feedback): The plan is reviewed against real file content
 *   to validate feasibility, check for missed issues, and produce final
 *   consolidated feedback.
 *
 * Output: agentic-performance-review.md in project root.
 *
 * Usage:
 *   groovy agentic-performance-runner.groovy
 *   (Requires ANTHROPIC_API_KEY env var, or set API_KEY below)
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// ─── Configuration ────────────────────────────────────────────────────────────
String API_KEY    = System.getenv('ANTHROPIC_API_KEY') ?: ''
String MODEL      = 'claude-sonnet-4-20250514'
int    MAX_TOKENS = 4096

String PROJECT_ROOT = new File(getClass().getResource('.')?.toURI()?.path
    ?: System.getProperty('user.dir')).canonicalPath

// Service files to review (relative to project root)
List<String> SERVICE_FILES = [
    'src/main/groovy/com/softwood/mcp/service/AbstractFileService.groovy',
    'src/main/groovy/com/softwood/mcp/service/FileReadService.groovy',
    'src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy',
    'src/main/groovy/com/softwood/mcp/service/FileLifecycleService.groovy',
    'src/main/groovy/com/softwood/mcp/service/FileListService.groovy',
    'src/main/groovy/com/softwood/mcp/service/FileSearchService.groovy',
    'src/main/groovy/com/softwood/mcp/service/ChunkBufferService.groovy',
    'src/main/groovy/com/softwood/mcp/service/StructureCache.groovy',
    'src/main/groovy/com/softwood/mcp/service/UsageTracker.groovy',
    'src/main/groovy/com/softwood/mcp/service/FilesystemTelemetryService.groovy',
    'src/main/groovy/com/softwood/mcp/service/ServerLifecycleService.groovy',
    'src/main/groovy/com/softwood/mcp/service/ToolsService.groovy',
    'src/main/groovy/com/softwood/mcp/promise/Promises.groovy',
    'src/main/groovy/com/softwood/mcp/promise/PromiseImpl.groovy',
    'src/main/groovy/com/softwood/mcp/service/AstStructureScanner.groovy',
]

// ─── Helpers ──────────────────────────────────────────────────────────────────
def callClaude(String apiKey, String model, int maxTokens, List<Map> messages, String systemPrompt = null) {
    def url    = new URL('https://api.anthropic.com/v1/messages')
    def conn   = (HttpURLConnection) url.openConnection()
    conn.requestMethod = 'POST'
    conn.setRequestProperty('Content-Type', 'application/json')
    conn.setRequestProperty('x-api-key', apiKey)
    conn.setRequestProperty('anthropic-version', '2023-06-01')
    conn.doOutput = true
    conn.connectTimeout = 30_000
    conn.readTimeout    = 120_000

    def body = [model: model, max_tokens: maxTokens, messages: messages]
    if (systemPrompt) body.system = systemPrompt

    conn.outputStream.withWriter('UTF-8') { it << JsonOutput.toJson(body) }

    int status = conn.responseCode
    def raw    = (status < 400 ? conn.inputStream : conn.errorStream)
        .withCloseable { it.text }

    def parsed = new JsonSlurper().parseText(raw)
    if (status >= 400) {
        throw new RuntimeException("Claude API error ${status}: ${raw}")
    }
    // Extract all text blocks
    return parsed.content.findAll { it.type == 'text' }*.text.join('\n')
}

def readFile(String relPath) {
    def f = new File(PROJECT_ROOT, relPath)
    if (!f.exists()) return null
    // Truncate very large files to stay within token budget (~600 lines max)
    def lines = f.readLines('UTF-8')
    if (lines.size() > 600) {
        return lines[0..599].join('\n') + "\n\n[... truncated at 600 lines for token budget ...]"
    }
    return lines.join('\n')
}

def slugify(String path) {
    path.replaceAll('[/\\\\.]', '_').replaceAll('__+', '_')
}

// ─── Main ─────────────────────────────────────────────────────────────────────
if (!API_KEY) {
    System.err.println "ERROR: ANTHROPIC_API_KEY environment variable not set."
    System.exit(1)
}

println "=== Agentic Performance Review Runner ==="
println "Project: ${PROJECT_ROOT}"
println "Model:   ${MODEL}"
println "Files:   ${SERVICE_FILES.size()}"
println ""

// ────────────────────────────────────────────────────────────────────────────
// PASS 1: ASSESS + PLAN (one call per service, then a plan synthesis call)
// ────────────────────────────────────────────────────────────────────────────
println "── PASS 1: ASSESS ──────────────────────────────────────────────────────"

String ASSESS_SYSTEM = """\
You are a senior Groovy/Spring Boot performance engineer reviewing an MCP server codebase.
Your job is to assess a single service file for:
1. Memory consumption issues (unnecessary object creation, missing streaming, large in-memory collections)
2. Use of streaming APIs vs bulk loading (Files.readString vs Files.lines etc.)
3. Promise / async usage opportunities (blocking calls that could be Promises.async{})
4. Throughput bottlenecks (synchronised blocks, per-call resource creation, TOCTOU patterns)
5. Any bugs or logical errors that affect performance or correctness

Be concise. Use a numbered finding list. Each finding: severity (HIGH/MEDIUM/LOW), location (line approx), description, recommended fix.
Respond in plain markdown. No preamble.
""".stripIndent()

Map<String, String> assessments = [:]

SERVICE_FILES.each { relPath ->
    String content = readFile(relPath)
    if (!content) {
        println "  SKIP (not found): ${relPath}"
        return
    }
    String shortName = new File(relPath).name
    println "  Assessing: ${shortName}"

    List<Map> msgs = [[
        role: 'user',
        content: """\
File: `${relPath}`

```groovy
${content}
```

Assess this file for memory, streaming, Promise usage, throughput, and correctness issues.
""".stripIndent()
    ]]

    try {
        String assessment = callClaude(API_KEY, MODEL, MAX_TOKENS, msgs, ASSESS_SYSTEM)
        assessments[relPath] = assessment
        println "    → ${assessment.split('\n').size()} lines of findings"
    } catch (Exception e) {
        assessments[relPath] = "ERROR during assessment: ${e.message}"
        println "    ! ERROR: ${e.message}"
    }
    // Brief pause to avoid rate-limiting
    Thread.sleep(500)
}

// ────────────────────────────────────────────────────────────────────────────
// PLAN SYNTHESIS (combine all Pass-1 findings into a prioritised plan)
// ────────────────────────────────────────────────────────────────────────────
println ""
println "── PASS 1: PLAN SYNTHESIS ──────────────────────────────────────────────"

String PLAN_SYSTEM = """\
You are a senior engineering lead. Given per-file assessments, produce a prioritised improvement plan.
Group findings by theme (Memory, Streaming, Promises, Throughput, Correctness).
For each item: ID, theme, severity, affected files, description, proposed fix approach.
At the end add a 'Quick Wins' section (fixes < 30 min each) and a 'Larger Refactors' section.
Respond in plain markdown. No preamble.
""".stripIndent()

StringBuilder allFindings = new StringBuilder()
allFindings << "## Per-file assessment summaries\n\n"
assessments.each { path, finding ->
    allFindings << "### ${new File(path).name}\n\n${finding}\n\n---\n\n"
}

List<Map> planMsgs = [[
    role: 'user',
    content: allFindings.toString() + "\nSynthesize into a prioritised improvement plan."
]]

String plan = ''
try {
    plan = callClaude(API_KEY, MODEL, MAX_TOKENS, planMsgs, PLAN_SYSTEM)
    println "  → Plan generated (${plan.split('\n').size()} lines)"
} catch (Exception e) {
    plan = "ERROR during plan synthesis: ${e.message}"
    println "  ! ERROR: ${e.message}"
}

// ────────────────────────────────────────────────────────────────────────────
// PASS 2: REVIEW (validate the plan, check for missed issues, add stream/promise specifics)
// ────────────────────────────────────────────────────────────────────────────
println ""
println "── PASS 2: REVIEW ──────────────────────────────────────────────────────"

String REVIEW_SYSTEM = """\
You are a critical reviewer. You are given:
1. A prioritised improvement plan for a Groovy MCP server.
2. The source code of the two most performance-sensitive services.

Your job is to:
- Validate each plan item: is the proposed fix feasible and correct for this codebase?
- Identify any important issues the plan MISSED.
- Specifically evaluate: could streaming (Files.lines, BufferedInputStream) replace bulk loads in more places?
- Specifically evaluate: could Promises.async{} / Promises.all() be applied in more places for non-blocking throughput?
- Identify any broken Promise chain patterns (missing .get(), swallowed futures, blocking the MCP thread).
- Produce a REVIEW VERDICT for each plan item: VALIDATE / AMEND / REJECT with one-line rationale.
- Add a MISSED ISSUES section at the end.

Respond in plain markdown. No preamble.
""".stripIndent()

// Feed the plan + the two largest services (FileReadService, FileWriteService)
String readSvc  = readFile('src/main/groovy/com/softwood/mcp/service/FileReadService.groovy')
String writeSvc = readFile('src/main/groovy/com/softwood/mcp/service/FileWriteService.groovy')

List<Map> reviewMsgs = [[
    role: 'user',
    content: """\
## Improvement Plan (Pass 1 output)

${plan}

---

## FileReadService.groovy (excerpt)

```groovy
${readSvc ?: '(not found)'}
```

---

## FileWriteService.groovy (excerpt)

```groovy
${writeSvc ?: '(not found)'}
```

Review the plan against the source. Validate, amend or reject each item. List any missed issues.
""".stripIndent()
]]

String review = ''
try {
    review = callClaude(API_KEY, MODEL, MAX_TOKENS, reviewMsgs, REVIEW_SYSTEM)
    println "  → Review generated (${review.split('\n').size()} lines)"
} catch (Exception e) {
    review = "ERROR during review: ${e.message}"
    println "  ! ERROR: ${e.message}"
}

// ────────────────────────────────────────────────────────────────────────────
// PASS 2: FEEDBACK (final consolidated brief for Claude Code)
// ────────────────────────────────────────────────────────────────────────────
println ""
println "── PASS 2: FEEDBACK ────────────────────────────────────────────────────"

String FEEDBACK_SYSTEM = """\
You are producing a final engineering brief for a developer (Claude Code) who will implement the fixes.
You are given the improvement plan and the review verdict.

Produce a concise, actionable brief:
1. Executive Summary (3-5 sentences)
2. Priority Fix List (table: ID | File | Change | Effort | Impact)
3. Streaming Improvements (specific method signatures and file/line to change)
4. Promise/Async Improvements (specific call sites)
5. Memory Improvements (specific objects/patterns to eliminate)
6. Correctness Fixes (bugs to address first)
7. Version bump notes for 0.8.0 changelog

Keep each section tight. This is input to an automated coding session - be precise.
Respond in plain markdown. No preamble.
""".stripIndent()

List<Map> feedbackMsgs = [[
    role: 'user',
    content: """\
## Pass 1: Improvement Plan
${plan}

---

## Pass 2: Review Verdict
${review}

---

Produce the final consolidated brief for implementation.
""".stripIndent()
]]

String feedback = ''
try {
    feedback = callClaude(API_KEY, MODEL, MAX_TOKENS, feedbackMsgs, FEEDBACK_SYSTEM)
    println "  → Feedback brief generated (${feedback.split('\n').size()} lines)"
} catch (Exception e) {
    feedback = "ERROR during feedback: ${e.message}"
    println "  ! ERROR: ${e.message}"
}

// ────────────────────────────────────────────────────────────────────────────
// WRITE OUTPUT FILE
// ────────────────────────────────────────────────────────────────────────────
println ""
println "── Writing output ──────────────────────────────────────────────────────"

String timestamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss")
StringBuilder output = new StringBuilder()

output << """\
# Agentic Performance Review — mcp-groovy-filesystem-server
> Generated: ${timestamp}  
> Model: ${MODEL}  
> Runner: agentic-performance-runner.groovy (two-pass: assess/plan → review/feedback)

---

## PASS 1 — ASSESS: Per-File Findings

"""

assessments.each { path, finding ->
    output << "### `${new File(path).name}`\n\n${finding}\n\n---\n\n"
}

output << """\
## PASS 1 — PLAN: Prioritised Improvement Plan

${plan}

---

## PASS 2 — REVIEW: Validation & Missed Issues

${review}

---

## PASS 2 — FEEDBACK: Final Brief for Implementation (v0.8.0)

${feedback}

---

*End of agentic performance review.*
"""

def outFile = new File(PROJECT_ROOT, 'agentic-performance-review.md')
outFile.text = output.toString()
println "  Written: ${outFile.canonicalPath} (${outFile.size()} bytes)"
println ""
println "=== Done ==="
