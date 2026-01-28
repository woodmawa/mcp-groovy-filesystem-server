# MCP Server Test Prompts

Copy these prompts into Claude Desktop after updating your config.

---

## 1. Verify Both Servers Are Running

```
What MCP servers are currently connected? List their names and tools.
```

**Expected:** 
- `filesystem-legacy` with ~6 tools
- `groovy-filesystem` with 10 tools

---

## 2. Test Basic File Operations (Compare Both)

### Test with Old Server
```
Using the filesystem-legacy server, can you read the file 
C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer\README.md
and tell me what it's about?
```

### Test with New Server
```
Using the groovy-filesystem server, can you read the file 
C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer\README.md
and tell me what it's about?
```

**Expected:** Both should return the same content

---

## 3. Test NEW Capability: Groovy Script Execution

```
Using the groovy-filesystem server, execute a Groovy script that:
1. Lists all .groovy files in C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer\src\main\groovy
2. Counts how many there are
3. Prints the result

Working directory: C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer
```

**Expected:** Script executes and shows file count

---

## 4. Test PowerShell Integration

```
Using the groovy-filesystem server, execute a Groovy script that runs a PowerShell command to get the current Java version (java -version).

Working directory: C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer
```

**Expected:** Java version displayed

---

## 5. Test Git Integration

```
Using the groovy-filesystem server, execute a Groovy script that:
1. Runs 'git status' in the McpGroovyFileSystemServer project
2. Shows me if there are any uncommitted changes

Working directory: C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer
```

**Expected:** Git status displayed

---

## 6. Test Security Validation

```
Using the groovy-filesystem server, execute a Groovy script that contains:
System.exit(0)

Working directory: C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer
```

**Expected:** Script should be **REJECTED** with security error mentioning "dangerous pattern"

---

## 7. Test Path Conversion

```
Using the groovy-filesystem server, normalize this Windows path and show me both Windows and WSL representations:
C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer
```

**Expected:** 
- Windows: `C:/Users/willw/IdeaProjects/McpGroovyFileSystemServer`
- WSL: `/mnt/c/Users/willw/IdeaProjects/McpGroovyFileSystemServer`

---

## 8. Test File Search

```
Using the groovy-filesystem server, search for files in 
C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer\src\main\groovy
that contain the text "AuditService"
```

**Expected:** List of files containing "AuditService"

---

## 9. Complex Automation Test

```
Using the groovy-filesystem server, execute a Groovy script that:
1. Runs 'git status --short' to see modified files
2. Lists all .groovy files in src/main/groovy/com/softwood/mcp/service
3. For each service file, counts the number of lines
4. Prints a summary table

Working directory: C:\Users\willw\IdeaProjects\McpGroovyFileSystemServer
```

**Expected:** Comprehensive report with file sizes

---

## 10. Performance Comparison

### Measure Old Server
```
Time how long it takes to list all files in C:\Users\willw\IdeaProjects 
using the filesystem-legacy server.
```

### Measure New Server
```
Time how long it takes to list all files in C:\Users\willw\IdeaProjects 
using the groovy-filesystem server.
```

**Compare:** Should be similar (within 10-20%)

---

## Success Criteria

✅ Both servers respond to basic commands
✅ New server can execute Groovy scripts
✅ Security validation blocks dangerous patterns
✅ PowerShell/Git integration works
✅ Path conversion works correctly
✅ Performance is acceptable
✅ No crashes or errors

---

## If Tests Pass

You're ready to use the new server! Consider:
1. Using new server for all new tasks
2. Keeping old server for 1-2 weeks as backup
3. Removing old server once confident

---

## If Tests Fail

1. Check the error message
2. Look at test reports: `build\reports\tests\test\index.html`
3. Check for security violations in audit logs
4. Report the issue with details
5. Fall back to old server

---

**Quick Copy/Paste Test:**

```
Hey Claude! I just set up a new MCP server. Can you:
1. List all MCP servers currently connected
2. Using groovy-filesystem, execute a script that prints "Hello from Groovy!"
3. Tell me if it worked
```

This will quickly validate everything is working!
