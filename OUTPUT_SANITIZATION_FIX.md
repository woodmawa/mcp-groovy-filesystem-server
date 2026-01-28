# Output Sanitization Fix

## Issue
Warning appeared in Claude client about non-whitespace characters, likely from PowerShell output containing control characters.

## Root Cause
PowerShell and other command outputs may contain:
- Control characters (0x00-0x1F, 0x7F)
- ANSI escape sequences
- Carriage returns (\r)
- Other non-printable characters

These can cause JSON parsing issues or display warnings in the MCP client.

## Solution Applied

### ScriptExecutor.groovy
Added `sanitizeOutput()` method that:
- Removes control characters except newlines (\n) and tabs (\t)
- Keeps printable ASCII (32-126)
- Preserves basic whitespace

```groovy
private String sanitizeOutput(String text) {
    if (!text) return text
    
    // Remove control characters except \n (10) and \t (9)
    // Keep printable ASCII (32-126) and common whitespace
    return text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]/, '')
}
```

Applied in `captureOutput()` method:
```groovy
process.inputStream.eachLine { String line -> 
    stdout.append(sanitizeOutput(line)).append('\n') 
}
```

## What Gets Removed
- Null bytes (0x00)
- Bell characters (0x07)
- Backspace (0x08)
- Vertical tabs (0x0B)
- Form feeds (0x0C)
- Carriage returns (0x0D) - replaced by \n
- Escape sequences (0x1B)
- Delete (0x7F)

## What Gets Kept
- Newlines (\n, 0x0A) ✅
- Tabs (\t, 0x09) ✅
- Spaces and printable ASCII (32-126) ✅

## Testing

After rebuilding, test with:
```
Using groovy-filesystem, execute a Groovy script that runs PowerShell 'Get-Process | Select-Object -First 5'
```

Should see clean output without warnings.

## Files Modified
- `src/main/groovy/com/softwood/mcp/service/ScriptExecutor.groovy`

## Next Steps
1. Rebuild: `.\gradlew.bat clean build`
2. Restart Claude Desktop
3. Test PowerShell commands
4. Verify no more warnings

---

**Date:** January 28, 2026
**Status:** Fixed, awaiting rebuild and test
**Priority:** Medium (cosmetic issue, not breaking)
