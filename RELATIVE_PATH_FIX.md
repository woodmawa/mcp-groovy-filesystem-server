# Relative Path Resolution Fix

**Date:** February 6, 2026  
**Issue:** executeGroovyScript ignores workingDirectory parameter when scripts use `new File('relative/path')`  
**Status:** FIXED

---

## Problem

When scripts use `new File('relative/path')` directly, Java resolves the path against the JVM's current working directory (Claude Desktop's install directory), NOT the `workingDirectory` parameter passed to executeGroovyScript.

### Example Bug:
```groovy
// Script executed with workingDirectory: "C:/Users/willw/IdeaProjects/my-project"
def srcDir = 'src/main/groovy'
new File(srcDir).eachFileRecurse { ... }  // ❌ Resolves to Claude Desktop app dir!
```

**Error:** `FileNotFoundException: C:\Users\willw\AppData\Local\AnthropicClaude\app-1.1.2128\src\main\groovy`

---

## Solution

Added `file(String path)` helper to script binding that automatically resolves relative paths against workingDirectory.

### Fixed Code:
```groovy
// Option 1: Use the file() helper (RECOMMENDED)
def srcDir = 'src/main/groovy'
file(srcDir).eachFileRecurse { ... }  // ✅ Resolves correctly!

// Option 2: Use workingDir variable manually
new File(workingDir, 'src/main/groovy').eachFileRecurse { ... }  // ✅ Also works

// Option 3: Use absolute paths
new File('C:/Users/willw/IdeaProjects/my-project/src/main/groovy').eachFileRecurse { ... }  // ✅ Always works
```

---

## Implementation Details

**File:** `GroovyScriptService.groovy` (line ~102-112)

```groovy
// Helper closure to resolve paths against workingDir
def resolveFile = { String path ->
    if (!path) return null
    
    File f = new File(path)
    // If already absolute, return as-is
    if (f.isAbsolute()) {
        return f
    }
    
    // Relative path - resolve against workingDir
    return new File(workingDir, path).canonicalFile
}

// Create binding with file helper
def binding = new Binding([
    workingDir: workingDir,
    scriptOutput: scriptOutput,
    file: resolveFile  // NEW: Helper for resolving File objects
])
```

---

## API Documentation

### file(String path) Helper

**Available in:** All executeGroovyScript calls  
**Purpose:** Resolve relative paths against workingDirectory parameter  
**Returns:** `java.io.File` object with correctly resolved path

**Behavior:**
- **Absolute paths:** Returned unchanged (e.g., `C:/path/to/file` → `C:/path/to/file`)
- **Relative paths:** Resolved against workingDirectory (e.g., `src/main` → `workingDir/src/main`)
- **Null/empty:** Returns null

**Examples:**

```groovy
// List files in relative directory
file('src/main/groovy').eachFileRecurse { f ->
    if (f.name.endsWith('.groovy')) {
        println f.absolutePath
    }
}

// Read relative file
def content = file('build.gradle').text

// Create relative directory
file('target/output').mkdirs()

// Check if relative file exists
if (file('README.md').exists()) {
    println "Found README"
}
```

---

## Migration Guide

### Before (Broken):
```groovy
// ❌ Uses JVM's current directory (Claude Desktop app dir)
def findFiles(dir) {
    def results = []
    new File(dir).eachFileRecurse { file ->
        results << file.absolutePath
    }
    return results
}

findFiles('src/main/groovy')  // FAILS
```

### After (Fixed):
```groovy
// ✅ Uses workingDirectory parameter
def findFiles(dir) {
    def results = []
    file(dir).eachFileRecurse { f ->  // Use file() helper
        results << f.absolutePath
    }
    return results
}

findFiles('src/main/groovy')  // WORKS
```

---

## Testing

### Test Script:
```groovy
// Execute with workingDirectory: "C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server"

// Test 1: List relative directory
println "Test 1: List src/main/groovy"
file('src/main/groovy').eachDir { dir ->
    println "  ${dir.name}"
}

// Test 2: Read relative file
println "\nTest 2: Read build.gradle"
def lines = file('build.gradle').readLines().take(5)
lines.each { println "  $it" }

// Test 3: Check file existence
println "\nTest 3: Check README.md"
println "  Exists: ${file('README.md').exists()}"

// Test 4: Absolute path (should work unchanged)
println "\nTest 4: Absolute path"
def absPath = "C:/Users/willw/IdeaProjects/mcp-groovy-filesystem-server/src"
println "  ${file(absPath).absolutePath}"

println "\n✅ All tests completed"
```

**Expected Output:**
```
Test 1: List src/main/groovy
  groovy

Test 2: Read build.gradle
  plugins {
      id 'groovy'
      ...
  }

Test 3: Check README.md
  Exists: true

Test 4: Absolute path
  C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server\src

✅ All tests completed
```

---

## Rebuild Instructions

1. **Build the JAR:**
```bash
cd C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server
gradlew clean build
```

2. **Verify JAR location:**
```bash
dir build\libs\mcp-groovy-filesystem-server-*.jar
```

3. **Restart Claude Desktop** to load new JAR

4. **Test the fix:**
```groovy
executeGroovyScript(
    workingDirectory: "C:/Users/willw/IdeaProjects/mcp-llm-orchestrator",
    script: '''
        println "Working in: $workingDir"
        file('src/main/groovy').eachDir { dir ->
            println "Found: ${dir.name}"
        }
    '''
)
```

---

## Related Issues

- **Bug #2:** Relative path resolution in executeGroovyScript
- **Context Session:** 2026-02-06-10-23
- **Dead End:** Using `new File('relative/path')` without file() helper

---

## Notes

- The `file()` helper is ONLY available in executeGroovyScript, not in other MCP tools
- SecureMcpScript base class already has `resolvePath()` method for its own helpers (readFile, writeFile, etc.)
- This fix allows scripts to use familiar Java File APIs with correct path resolution
