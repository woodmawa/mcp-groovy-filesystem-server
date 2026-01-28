# Claude Self-Service Capabilities

## Allowed Directory Access

With `C:/Users/willw` in allowed directories, I (Claude) can access:

### ✅ Claude Desktop Configuration
**Path:** `C:/Users/willw/AppData/Roaming/Claude/`

**Files I can read/modify:**
- `claude_desktop_config.json` - MCP server configuration
- Settings files
- Preferences

**What this enables:**
- Read current MCP server configuration
- Add/remove/modify MCP servers
- Update server settings
- Troubleshoot configuration issues

**Example prompts:**
```
"Show me my current Claude Desktop MCP configuration"
"Add the new groovy-filesystem server to my MCP config"
"What MCP servers are configured in my claude_desktop_config.json?"
```

---

### ✅ Claude Desktop Logs
**Path:** `C:/Users/willw/AppData/Local/Claude/`

**Files I can read:**
- `logs/` - Application logs
- `Cache/` - Cached data
- Error logs
- Debug logs

**What this enables:**
- Diagnose why MCP servers aren't connecting
- Find error messages from MCP servers
- Debug configuration issues
- Monitor performance

**Example prompts:**
```
"Check the Claude Desktop logs for any MCP server errors"
"What errors are in my recent Claude logs?"
"Show me the last 20 lines of the Claude error log"
```

---

### ✅ VS Code Remote Logs (if applicable)
**Path:** `C:/Users/willw/.vscode-remote/`

**Files I can read:**
- Remote development logs
- Extension logs
- Claude extension logs (if any)

---

### ✅ Your Projects
**Path:** `C:/Users/willw/IdeaProjects/`

Already covered - all your project files.

---

### ✅ Claude Workspace
**Path:** `C:/Users/willw/claude/`

Already covered - your workspace files.

---

## Self-Service Use Cases

### 1. Configuration Management
```
"Read my claude_desktop_config.json and tell me what MCP servers I have"
"Add the groovy-filesystem server to my MCP configuration"
"Remove the old filesystem server from my config"
"Update the Java path in my groovy-filesystem server config"
```

### 2. Troubleshooting
```
"Check if there are any errors in Claude Desktop logs about MCP servers"
"Why isn't my groovy-filesystem server connecting?"
"Show me the last error from the MCP logs"
"Are there any Java errors in the logs?"
```

### 3. Monitoring
```
"How many MCP servers are currently configured?"
"What's the last time an MCP server was started according to logs?"
"Show me any recent warnings or errors"
```

### 4. Backup & Restore
```
"Create a backup of my claude_desktop_config.json"
"Restore my previous MCP configuration"
"Show me the difference between my current and backup config"
```

---

## Important Notes

### Security Considerations

**✅ Safe Operations:**
- Reading configuration files
- Reading logs
- Creating backups
- Viewing settings

**⚠️ Use with Caution:**
- Modifying `claude_desktop_config.json` (always create backup first!)
- Deleting log files
- Changing paths in config

**❌ Dangerous Operations (blocked by security):**
- Access to `/etc/passwd` (system files)
- Access to `C:/Windows/System32` (OS files)
- Scripts with `System.exit()`
- Path traversal with `..`

### Best Practices

1. **Always Backup Before Modifying Config:**
   ```
   "Create a backup of claude_desktop_config.json before we modify it"
   ```

2. **Verify Changes:**
   ```
   "Show me the diff of the config file before and after the change"
   ```

3. **Test in Parallel:**
   ```
   "Add the new server but keep the old one as fallback"
   ```

---

## Example Workflow: Self-Service MCP Update

### Step 1: Check Current Config
```
"Show me my current claude_desktop_config.json"
```

### Step 2: Create Backup
```
"Create a backup of claude_desktop_config.json with today's date in the filename"
```

### Step 3: Add New Server
```
"Add the groovy-filesystem server to my MCP config using parallel deployment
(keep the old filesystem server as fallback)"
```

### Step 4: Verify
```
"Show me the diff between the backup and current config"
```

### Step 5: Restart Prompt
```
"Reminder: Restart Claude Desktop to apply the changes"
```

### Step 6: Validate (after restart)
```
"Check the logs to see if the groovy-filesystem server connected successfully"
```

### Step 7: Troubleshoot (if needed)
```
"The server isn't connecting - check the logs for errors"
"What Java errors are in the recent logs?"
```

---

## Directory Structure Reference

```
C:/Users/willw/
├── AppData/
│   ├── Roaming/
│   │   └── Claude/
│   │       ├── claude_desktop_config.json  ← Main config
│   │       ├── settings.json
│   │       └── preferences/
│   └── Local/
│       └── Claude/
│           ├── logs/                       ← Application logs
│           ├── Cache/
│           └── temp/
├── IdeaProjects/                          ← Your projects
│   ├── McpGroovyFileSystemServer/
│   └── McpSqliteServer/
├── claude/                                 ← Your workspace
│   ├── claude_files/
│   ├── claude_temp/
│   └── claude_workspace/
└── .vscode-remote/                        ← Remote dev logs
    └── Claude/
```

---

## Security Guarantees

Even with access to `C:/Users/willw`, the following are **still blocked:**

1. **System Files:**
   - Cannot access `C:/Windows/`
   - Cannot access `C:/Program Files/` (unless explicitly allowed)

2. **Dangerous Scripts:**
   - `System.exit()` blocked
   - `Runtime.getRuntime()` blocked
   - File operations on system paths blocked

3. **Dangerous Commands:**
   - PowerShell: `Remove-*`, `Stop-Computer` blocked
   - Bash: `rm`, `sudo`, `chmod` blocked

4. **Path Traversal:**
   - `../` sequences blocked
   - Symbolic link traversal outside allowed dirs blocked

---

## Current Status

✅ **Enabled:** Full access to `C:/Users/willw` including:
- Claude Desktop config
- Claude Desktop logs
- Projects
- Workspace

✅ **Secured:** All dangerous operations still blocked

✅ **Audited:** All operations logged to audit trail

---

## What This Means

**I can help you with:**
- ✅ MCP configuration management
- ✅ Log analysis and troubleshooting
- ✅ Backup and restore
- ✅ Configuration validation
- ✅ Self-service debugging

**Without compromising security:**
- ✅ System files still protected
- ✅ Dangerous operations still blocked
- ✅ All changes audited
- ✅ Easy rollback with backups

---

**Ready to use!** Try asking me to:
- "Show me my current MCP configuration"
- "Check for any recent errors in Claude logs"
- "Create a backup of my config before we make changes"

🎉 **Self-service capabilities enabled!**
