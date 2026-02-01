# MCP Server Robustness: Lessons Learned

## Executive Summary

After extensive debugging and enhancement of the mcp-groovy-filesystem-server, we've identified and resolved critical issues around character encoding, error handling, and STDIO communication. This document captures the learnings for application to other MCP servers (sqlite-server, context-server).

## The Problem We Solved

### Initial Symptoms
1. "Exceeded max compaction" errors in Claude Desktop client
2. "Failed to call tool" generic error messages
3. "We couldn't connect to Claude" connection errors
4. Server crashes when encountering edge cases

### Root Causes
1. **Character Encoding Issues**: Control characters in file paths and error messages
2. **Insufficient Error Handling**: Exceptions escaping without proper sanitization
3. **STDIO Communication Failures**: Malformed JSON responses
4. **Configuration Issues**: Wrong JAR paths, missing debug configuration

## Critical Principles for MCP Server Development

### 1. Defense in Depth - Multiple Layers of Error Handling

**Every MCP server must have error handling at ALL layers:**

```
┌─────────────────────────────────────────┐
│  Layer 4: STDIO Server (StdioMcpServer) │
│  - Catch all Throwables                 │
│  - Sanitize all JSON output             │
│  - Validate JSON before sending         │
│  - Hardcoded fallback error responses   │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│  Layer 3: Controller (McpController)    │
│  - Specific exception types             │
│  - Proper error codes                   │
│  - Sanitize all responses               │
│  - Top-level Throwable catch            │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│  Layer 2: Service Layer (Services)      │
│  - Try-catch around operations          │
│  - Sanitize error messages              │
│  - Proper resource cleanup              │
│  - Sanitize all return values           │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│  Layer 1: Data Layer (Models/DTOs)      │
│  - Sanitization utilities               │
│  - Recursive object sanitization        │
│  - Safe defaults                        │
└─────────────────────────────────────────┘
```

### 2. String Sanitization is CRITICAL

**Every string that goes into a JSON response MUST be sanitized.**

#### The Sanitization Pattern

```groovy
private static String sanitize(String text) {
    if (!text) return text
    try {
        // Remove control characters except \n (10) and \t (9)
        String cleaned = text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F-\x9F]/, '')
        
        // Remove any remaining non-printable characters
        cleaned = cleaned.replaceAll(/[^\p{Print}\p{Space}]/, '')
        
        return cleaned
    } catch (Exception e) {
        log.warn("Error sanitizing text")
        return "[sanitization error]"
    }
}
```

#### Recursive Object Sanitization

```groovy
private static Object sanitizeObject(Object obj) {
    try {
        if (obj == null) return null
        else if (obj instanceof String) return sanitize((String) obj)
        else if (obj instanceof Map) {
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
        return "[object sanitization error]"
    }
}
```

**Where to Apply Sanitization:**
- ✅ All error messages
- ✅ All log messages
- ✅ File paths from filesystem
- ✅ User input
- ✅ Exception messages
- ✅ Before JSON serialization
- ✅ After JSON serialization (final check)

### 3. JSON-RPC Error Code Standards

Use specific error codes to help clients distinguish error types:

| Code | Type | Use Case |
|------|------|----------|
| -32700 | Parse error | Invalid JSON received from client |
| -32600 | Invalid request | JSON valid but request malformed |
| -32601 | Method not found | Unknown method/tool name |
| -32602 | Invalid params | Invalid or missing parameters |
| -32001 | Security error | Path not allowed, permission denied |
| -32002 | File not found | Resource doesn't exist |
| -32003 | Database error | SQLite-specific errors |
| -32603 | Internal error | Generic server error |

### 4. STDIO Communication Must Be Bulletproof

**The STDIO layer is your last line of defense. It MUST NOT fail.**

```groovy
private void sendJsonRpcError(String requestId, int code, String message) {
    try {
        // Attempt 1: Proper error response
        String safeId = sanitize(requestId ?: "unknown")
        String safeMessage = sanitize(message ?: "Unknown error")
        
        def errorResponse = [
            jsonrpc: "2.0",
            id: safeId,
            error: [code: code, message: safeMessage]
        ]
        
        String json = objectMapper.writeValueAsString(errorResponse)
        json = sanitize(json)  // Final validation
        
        System.out.println(json)
        System.out.flush()
        
    } catch (Throwable t) {
        // Attempt 2: Hardcoded minimal error
        try {
            System.out.println('{"jsonrpc":"2.0","id":"error","error":{"code":-32603,"message":"Critical error"}}')
            System.out.flush()
        } catch (Exception e2) {
            // Attempt 3: At this point, we can't do anything
            debugLog("CRITICAL: All error response attempts failed")
        }
    }
}
```

### 5. Debug Mode is Essential

**Always implement comprehensive debug logging:**

```groovy
private static final boolean DEBUG = System.getenv("MCP_DEBUG") != null

private static void debugLog(String message) {
    if (DEBUG) {
        try {
            String timestamp = java.time.LocalTime.now().toString()
            System.err.println("[${timestamp}] MCP: ${message}")
            System.err.flush()
        } catch (Exception e) {
            // Even logging can fail - handle gracefully
        }
    }
}
```

**What to Log:**
- Server startup/shutdown
- Each request received (with truncated content)
- Request parsing results
- Tool execution start
- Success/error responses
- JSON serialization issues
- Exception details

**Where Debug Logs Go:**
- STDERR (never STDOUT - that's for protocol)
- Claude Desktop captures this to: `AppData/Roaming/Claude/logs/mcp-server-{name}.log`

### 6. Configuration Management

**Critical Configuration Elements:**

```json
{
  "mcpServers": {
    "server-name": {
      "command": "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe",
      "args": [
        "--enable-native-access=ALL-UNNAMED",  // For Panama/FFI
        "-Dspring.profiles.active=stdio",       // Activate STDIO profile
        "-Dmcp.mode=stdio",                     // Set MCP mode
        "-DMCP_DEBUG=1",                        // Enable debug (optional)
        "-jar",
        "CORRECT/PATH/TO/ACTUAL/JAR/FILE.jar"  // ⚠️ CRITICAL: Correct path!
      ],
      "env": {
        "MCP_DEBUG": "1"  // Alternative way to enable debug
      }
    }
  }
}
```

**Common Configuration Mistakes:**
❌ Wrong JAR path (old location, wrong name)
❌ Missing `-Dspring.profiles.active=stdio`
❌ Missing `-Dmcp.mode=stdio`
❌ Forgetting to rebuild after code changes
❌ Not restarting Claude Desktop after config changes

## Specific Issues and Solutions

### Issue 1: "Exceeded max compaction" Error

**Cause:** Control characters in JSON responses causing Claude Desktop client to fail parsing.

**Solution:**
1. Sanitize all strings before adding to response
2. Sanitize entire response object recursively before serialization
3. Validate JSON output doesn't contain control characters
4. Apply final sanitization to JSON string if needed

### Issue 2: "Failed to call tool" Generic Error

**Cause:** The Claude Desktop client receives an error response but doesn't display the detailed message to users.

**Reality:** This is actually **correct behavior**! The server IS returning proper error responses with detailed messages. The client chooses to show a generic message in the UI for security/UX reasons.

**Verification:** Check the logs at `AppData/Roaming/Claude/logs/mcp-server-{name}.log` to see the actual error messages.

**Solution:** No fix needed - this is working as designed. Developers should check logs for details.

### Issue 3: "We couldn't connect to Claude" Error

**Cause:**
1. JAR file path is wrong (file doesn't exist)
2. Server crashes on startup
3. Server process terminates unexpectedly

**Solution:**
1. Verify JAR path is correct
2. Check server startup logs
3. Ensure all dependencies are available
4. Add comprehensive error handling to prevent crashes

### Issue 4: File Operations with Special Characters

**Cause:** File paths and names containing control characters, UTF-8 invalid sequences, or special characters.

**Solution:**
1. Sanitize file paths before using them
2. Sanitize file names before returning them
3. Filter out Windows reserved names (NUL, CON, PRN, etc.)
4. Wrap file operations in try-catch with sanitized error messages
5. Use proper stream cleanup (try-finally)

## Implementation Checklist for New MCP Servers

### Phase 1: Core Sanitization (Essential)
- [ ] Implement `sanitize(String)` method in all layers
- [ ] Implement `sanitizeObject(Object)` method
- [ ] Apply sanitization to all error messages
- [ ] Apply sanitization to all log messages
- [ ] Apply sanitization to all user-facing strings

### Phase 2: Error Handling (Critical)
- [ ] Service layer: Try-catch around all operations
- [ ] Controller layer: Specific exception types with error codes
- [ ] STDIO layer: Multiple fallback levels
- [ ] Top-level: Catch Throwable (not just Exception)
- [ ] Ensure no unhandled exceptions can escape

### Phase 3: STDIO Communication (Required)
- [ ] Validate JSON before sending
- [ ] Sanitize entire response before serialization
- [ ] Final validation after serialization
- [ ] Hardcoded fallback error responses
- [ ] Proper flushing after each write

### Phase 4: Resource Management (Important)
- [ ] Proper stream cleanup (try-finally)
- [ ] Database connection cleanup
- [ ] File handle cleanup
- [ ] Thread pool shutdown
- [ ] Timeout handling

### Phase 5: Debug Support (Helpful)
- [ ] Implement debug logging via MCP_DEBUG env var
- [ ] Log to STDERR (not STDOUT)
- [ ] Log request/response summaries
- [ ] Log exception details
- [ ] Include timestamps

### Phase 6: Configuration (Setup)
- [ ] Correct JAR path in claude_desktop_config.json
- [ ] Spring profile: `-Dspring.profiles.active=stdio`
- [ ] MCP mode: `-Dmcp.mode=stdio`
- [ ] Optional: Enable debug mode
- [ ] Verify configuration after changes

## Testing Strategy

### Test Cases Every MCP Server Must Pass

1. **Happy Path Tests**
    - ✅ Normal operations work correctly
    - ✅ Valid input produces valid output
    - ✅ Successful operations return properly formatted results

2. **Error Path Tests**
    - ✅ Invalid input returns proper error (not crash)
    - ✅ Missing resources return 404-equivalent error
    - ✅ Unauthorized access returns security error
    - ✅ Malformed requests return parse error

3. **Edge Case Tests**
    - ✅ Empty strings handled gracefully
    - ✅ Null values handled gracefully
    - ✅ Very long strings don't cause issues
    - ✅ Special characters in input sanitized
    - ✅ Control characters in data sanitized

4. **Resource Tests**
    - ✅ Large files handled correctly
    - ✅ Many concurrent requests handled
    - ✅ Timeout conditions handled
    - ✅ Resource cleanup verified

5. **Integration Tests**
    - ✅ Works with Claude Desktop client
    - ✅ Debug logging captured correctly
    - ✅ Configuration changes take effect
    - ✅ Restart/reconnect works properly

## SQLite Server Specific Considerations

### Additional Sanitization Needs
- SQL query results may contain control characters
- BLOB data must be handled carefully
- NULL values in database vs JSON null
- Column names may have special characters

### Error Codes
- -32003: Database error (locked, corrupt, etc.)
- -32004: SQL syntax error
- -32005: Constraint violation

### Resource Management
- Database connections must be closed
- Prepared statements must be cleaned up
- Transaction rollback on errors
- WAL mode journal cleanup

## Context Server Specific Considerations

### Additional Sanitization Needs
- User-provided context may contain any characters
- Historical data may have legacy encoding issues
- JSON within JSON (nested context)
- Markdown formatting preservation vs sanitization

### Error Codes
- -32006: Context not found
- -32007: Context storage full
- -32008: Invalid context format

### Resource Management
- Memory management for context history
- File-based storage cleanup
- Index management
- Cache invalidation

## Tools and Utilities

### Log Analysis Commands

```bash
# View last 50 lines of MCP server log
tail -50 "C:/Users/willw/AppData/Roaming/Claude/logs/mcp-server-groovy-filesystem.log"

# Search for errors
grep "error" "C:/Users/willw/AppData/Roaming/Claude/logs/mcp-server-groovy-filesystem.log"

# Search for specific request ID
grep "id\":2" "C:/Users/willw/AppData/Roaming/Claude/logs/mcp-server-groovy-filesystem.log"

# View debug output
grep "MCP:" "C:/Users/willw/AppData/Roaming/Claude/logs/mcp-server-groovy-filesystem.log"
```

### Testing Scripts

```groovy
// Test sanitization
def testStrings = [
    "normal text",
    "text\x00with\x00nulls",
    "text\nwith\nnewlines",
    "text\twith\ttabs",
    "text\x1Bwith\x1Bescapes"
]

testStrings.each { input ->
    def output = sanitize(input)
    println "Input:  ${input.inspect()}"
    println "Output: ${output.inspect()}"
    println "Valid:  ${!output.find(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/)}"
    println ""
}
```

## Key Takeaways

1. **Never trust any input** - User input, file paths, database results, everything must be sanitized
2. **Multiple layers of defense** - If one layer fails, others catch it
3. **Sanitize early and often** - Before storage, after retrieval, before sending
4. **Error handling is not optional** - Every operation must have error handling
5. **Logging is your friend** - You can't debug what you can't see
6. **Test the error paths** - Happy path is easy, edge cases are where bugs hide
7. **Configuration matters** - Wrong config = doesn't work, period
8. **The client hides details** - Check logs for actual error messages

## Success Metrics

A robust MCP server should:
- ✅ Never crash (always return a response, even if it's an error)
- ✅ Never send invalid JSON
- ✅ Never send control characters in JSON
- ✅ Always clean up resources
- ✅ Always provide meaningful error messages (in logs)
- ✅ Always use appropriate error codes
- ✅ Survive edge cases and malicious input
- ✅ Provide debug information when enabled

## Next Steps

1. **Apply to SQLite Server**
    - Add sanitization layer
    - Enhance error handling
    - Add debug logging
    - Test with edge cases

2. **Apply to Context Server**
    - Add sanitization layer
    - Enhance error handling
    - Add debug logging
    - Test with edge cases

3. **Create Shared Library**
    - Extract common sanitization code
    - Extract common error handling patterns
    - Create MCP server base class
    - Standardize debug logging

4. **Document Best Practices**
    - Create MCP server development guide
    - Create troubleshooting guide
    - Create testing checklist
    - Share learnings with team

---

**Version:** 1.0
**Date:** 2026-02-01
**Author:** Based on mcp-groovy-filesystem-server debugging session
**Status:** Production-Ready Lessons