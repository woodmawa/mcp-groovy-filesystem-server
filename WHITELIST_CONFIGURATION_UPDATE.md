# MCP Groovy Filesystem Server - Command Whitelist Configuration Update

## 🎯 What We Accomplished

### 1. Created CommandWhitelistConfig.groovy
**Location:** `src/main/groovy/com/softwood/mcp/config/CommandWhitelistConfig.groovy`

**Purpose:** Spring Boot configuration class that:
- Loads command whitelist patterns from `application.yml` 
- Uses `@ConfigurationProperties(prefix = "mcp.script.whitelist")`
- Compiles regex patterns for performance (lazy initialization)
- Provides validation methods: `isPowershellAllowed()` and `isBashAllowed()`

**Benefits:**
- ✅ Whitelist is now configurable via YAML (no rebuild needed!)
- ✅ Patterns are compiled once for performance
- ✅ Centralized validation logic
- ✅ Easy to add new commands or patterns

### 2. Updated application.yml
**Added Section:** `mcp.script.whitelist`

**New Configuration Structure:**
```yaml
mcp:
  script:
    whitelist:
      powershell-allowed:
        - '^Get-ChildItem.*'
        - '^\\.\\\\gradlew\\.bat.*'  # Gradle wrapper now allowed!
        - '^cd .*;.*'  # Command chaining
        # ... more patterns
        
      powershell-blocked:
        - '.*Remove-Item.*'
        # ... dangerous commands
        
      bash-allowed:
        - '^ls.*'
        - '^\\./ gradlew.*'  # Gradle wrapper
        # ... more patterns
        
      bash-blocked:
        - '.*rm .*'
        # ... dangerous commands
```

**Key Addition:** Gradle wrapper commands are now whitelisted!
- PowerShell standalone: `.\gradlew.bat clean build`
- PowerShell with cd: `cd C:\path\to\project; .\gradlew.bat clean build`
- PowerShell general cd+command: `cd path; any-command`
- Bash: `./gradlew clean build`

### 3. Updated GroovyScriptService.groovy
**Changes:**
- Added `CommandWhitelistConfig` as constructor parameter
- Injected `whitelistConfig` into `SecureMcpScript` instances

**Code:**
```groovy
GroovyScriptService(
    FileSystemService fileSystemService,
    PathService pathService,
    ScriptExecutor scriptExecutor,
    ScriptSecurityService securityService,
    AuditService auditService,
    CommandWhitelistConfig whitelistConfig  // ← NEW!
) {
    // ...
    this.whitelistConfig = whitelistConfig
}
```

### 4. Updated SecureMcpScript.groovy
**Changes:**
- Removed hardcoded `ALLOWED_BASH_PATTERNS` and `BLOCKED_BASH_PATTERNS`
- Removed hardcoded `ALLOWED_POWERSHELL_PATTERNS` and `BLOCKED_POWERSHELL_PATTERNS`
- Removed `isAllowedBash()` and `isAllowedPowerShell()` methods
- Now uses injected `whitelistConfig` for validation

**Before:**
```groovy
private static final List ALLOWED_BASH_PATTERNS = [~/^ls.*/, ...]
private boolean isAllowedBash(String command) { ... }
```

**After:**
```groovy
CommandWhitelistConfig whitelistConfig  // Injected
CommandResult bash(String command) {
    if (!whitelistConfig.isBashAllowed(command)) {
        throw new SecurityException("...")
    }
    executeBash(command)
}
```

## 🚀 How to Use

### To Add New Commands
1. Open `application.yml`
2. Add regex pattern to appropriate section:
   - `powershell-allowed` - for safe PowerShell commands
   - `powershell-blocked` - for dangerous PowerShell commands (takes precedence)
   - `bash-allowed` - for safe Bash commands
   - `bash-blocked` - for dangerous Bash commands (takes precedence)
3. Restart the MCP server
4. **No rebuild required!**

### Example: Allow npm Commands
```yaml
mcp:
  script:
    whitelist:
      powershell-allowed:
        - '^npm .*'  # Allow all npm commands
        
      bash-allowed:
        - '^npm .*'  # Allow all npm commands
```

### Example: Allow Specific Maven Goals
```yaml
mcp:
  script:
    whitelist:
      powershell-allowed:
        - '^mvn clean.*'
        - '^mvn install.*'
        - '^mvn test.*'
```

## 📋 Next Steps

### To Test the Changes:
1. Build the project:
   ```powershell
   cd C:\Users\willw\IdeaProjects\mcp-groovy-filesystem-server
   .\gradlew.bat clean build --no-daemon
   ```

2. Test gradlew execution via MCP:
   ```groovy
   def result = powershell("cd C:\\Users\\willw\\IdeaProjects\\mcp-agentic-workflow; .\\gradlew.bat clean compileGroovy --no-daemon")
   return result
   ```

3. If it works, you can now build projects via MCP!

### To Enable in Claude Desktop:
1. Stop Claude Desktop
2. Rebuild the groovy-filesystem MCP server (step 1 above)
3. Restart Claude Desktop
4. Test with a simple gradle command

## 🔒 Security Notes

**Whitelist Design:**
- Blacklist patterns are checked FIRST (take precedence)
- Only commands matching whitelist patterns are allowed
- Regex patterns provide flexible matching
- Default patterns are conservative (safe by default)

**Adding Commands:**
- Always test patterns carefully
- Consider security implications
- Use specific patterns when possible
- Avoid overly broad patterns like `.*`

## 🎓 Design Benefits

1. **No Rebuild Required** - Edit YAML and restart, that's it!
2. **Centralized** - All whitelist rules in one place
3. **Flexible** - Regex patterns allow precise control
4. **Auditable** - Configuration is version-controlled
5. **Performance** - Patterns compiled once at startup
6. **Type-Safe** - Spring Boot validates configuration

## ✅ Status

- [x] CommandWhitelistConfig created
- [x] application.yml updated with whitelist patterns
- [x] GroovyScriptService updated to inject config
- [x] SecureMcpScript updated to use config
- [x] Gradle wrapper patterns added
- [ ] Build and test (next step!)

## 📝 Files Modified

1. **NEW:** `src/main/groovy/com/softwood/mcp/config/CommandWhitelistConfig.groovy`
2. **MODIFIED:** `src/main/resources/application.yml`
3. **MODIFIED:** `src/main/groovy/com/softwood/mcp/service/GroovyScriptService.groovy`
4. **MODIFIED:** `src/main/groovy/com/softwood/mcp/script/SecureMcpScript.groovy`

---

**Ready to build and test!** 🚀
