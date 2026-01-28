# Learnings for McpSqliteServer (SQL DBA Master)

Lessons learned from building McpGroovyFileSystemServer that should be applied to the SQL DBA Master project.

---

## 🔒 Security Enhancements to Add

### 1. Input Validation Service
Create `SqlSecurityService.groovy`:

```groovy
@Service
class SqlSecurityService {
    
    // SQL Injection Prevention
    private static final List<String> DANGEROUS_SQL_PATTERNS = [
        'DROP TABLE',
        'DROP DATABASE',
        'TRUNCATE',
        'DELETE FROM.*WHERE.*1=1',
        '--',           // SQL comments
        ';.*DROP',      // Chained drops
        'EXEC',
        'EXECUTE',
        'xp_cmdshell',  // SQL Server command execution
        'INTO OUTFILE', // MySQL file operations
        'LOAD_FILE'
    ]
    
    // System table/schema protection
    private static final List<String> PROTECTED_TABLES = [
        'sqlite_master',
        'sqlite_sequence',
        'information_schema',
        'sys.',
        'mysql.',
        'pg_catalog.'
    ]
    
    void validateQuery(String sql) {
        // Check for dangerous patterns
        // Check for system table access
        // Validate query length
        // Check for excessive wildcards
    }
}
```

### 2. Query Size Limits
```yaml
mcp:
  sql:
    max-query-length: 50000  # 50KB
    max-result-rows: 10000   # Prevent huge result sets
    max-execution-time-seconds: 30
    enable-dangerous-pattern-check: true
```

### 3. Audit Logging
Add `AuditService` (same pattern as filesystem server):
- Log all queries executed
- Log schema modifications (CREATE, ALTER, DROP)
- Log failed queries with reason
- Sanitize connection strings (remove passwords)

---

## 🎯 Type Safety Improvements

### 1. Create Typed Result Objects

**QueryResult.groovy:**
```groovy
@Immutable
@CompileStatic
class QueryResult {
    List<Map<String, Object>> rows
    List<String> columns
    int rowCount
    long durationMs
    boolean success
    String error
    
    static QueryResult success(List rows, List columns, long durationMs) {
        new QueryResult(
            rows: rows,
            columns: columns,
            rowCount: rows.size(),
            durationMs: durationMs,
            success: true,
            error: null
        )
    }
    
    static QueryResult failure(String error, long durationMs) {
        new QueryResult(
            rows: [],
            columns: [],
            rowCount: 0,
            durationMs: durationMs,
            success: false,
            error: error
        )
    }
}
```

**SchemaResult.groovy:**
```groovy
@Immutable
@CompileStatic
class SchemaResult {
    List<TableInfo> tables
    List<String> tableNames
    long durationMs
    boolean success
}

class TableInfo {
    String name
    List<ColumnInfo> columns
    List<String> indexes
    String schema
}
```

### 2. Replace Maps with Types
```groovy
// Before (current)
Map<String, Object> executeQuery(String sql)

// After (enhanced)
QueryResult executeQuery(String sql)
```

---

## 📊 Resource Control

### 1. Add ResourceControlService
Same pattern as filesystem server:
- Virtual Threads for concurrent queries
- Memory limits per query
- Connection pool limits
- Query timeout enforcement

```groovy
@Service
class SqlResourceControlService {
    
    @Value('${mcp.sql.max-concurrent-queries:5}')
    int maxConcurrentQueries
    
    @Value('${mcp.sql.max-connections:10}')
    int maxConnections
    
    @Value('${mcp.sql.query-timeout-seconds:30}')
    int queryTimeoutSeconds
    
    <T> T executeWithLimits(String queryId, Callable<T> query) {
        // Execute with timeout
        // Track active queries
        // Enforce connection limits
    }
}
```

---

## 🧪 Testing Improvements

### 1. Add Security Tests
```groovy
class SqlSecurityServiceSpec extends Specification {
    
    def "should reject SQL injection attempts"() {
        given: "malicious SQL"
        def sql = "SELECT * FROM users WHERE id = 1 OR 1=1"
        
        when: "validating"
        securityService.validateQuery(sql)
        
        then: "security exception thrown"
        thrown(SecurityException)
    }
    
    def "should reject DROP TABLE"() {
        given: "DROP TABLE query"
        def sql = "DROP TABLE users"
        
        when: "validating"
        securityService.validateQuery(sql)
        
        then: "security exception thrown"
        def e = thrown(SecurityException)
        e.message.contains("DROP TABLE")
    }
    
    def "should reject system table access"() {
        given: "query accessing sqlite_master"
        def sql = "DELETE FROM sqlite_master"
        
        when: "validating"
        securityService.validateQuery(sql)
        
        then: "security exception thrown"
        thrown(SecurityException)
    }
}
```

### 2. Add Type Safety Tests
```groovy
def "should return QueryResult with all fields"() {
    when: "executing query"
    QueryResult result = sqlService.executeQuery("SELECT * FROM users")
    
    then: "result has all required fields"
    result.rows != null
    result.columns != null
    result.rowCount >= 0
    result.durationMs >= 0
    result.success == true
}
```

---

## 🔧 Regex Pattern Fixes

### Critical: Avoid Inline Flags in Groovy

**❌ Wrong (causes compilation errors):**
```groovy
sanitized = text.replaceAll(/pattern/i, 'replacement')
```

**✅ Correct:**
```groovy
import java.util.regex.Pattern

private static final Pattern PATTERN = Pattern.compile('(?i)pattern')
sanitized = PATTERN.matcher(text).replaceAll('replacement')
```

### Pattern Examples for SQL

```groovy
// Case-insensitive SQL keywords
private static final Pattern DROP_PATTERN = Pattern.compile('(?i)\\bDROP\\s+TABLE\\b')
private static final Pattern DELETE_PATTERN = Pattern.compile('(?i)\\bDELETE\\s+FROM\\b')
private static final Pattern TRUNCATE_PATTERN = Pattern.compile('(?i)\\bTRUNCATE\\b')

// SQL injection patterns
private static final Pattern OR_1_EQUALS_1 = Pattern.compile('(?i)OR\\s+1\\s*=\\s*1')
private static final Pattern UNION_SELECT = Pattern.compile('(?i)UNION\\s+SELECT')

// Comment patterns
private static final Pattern SQL_COMMENT = Pattern.compile('--.*$', Pattern.MULTILINE)
private static final Pattern MULTI_COMMENT = Pattern.compile('/\\*.*?\\*/', Pattern.DOTALL)
```

---

## 🏗️ Architecture Improvements

### 1. Separate Concerns

**Current Structure (McpSqliteServer):**
```
src/main/groovy/com/softwood/
├── controller/
│   └── McpController.groovy     (handles everything)
└── service/
    └── SqliteService.groovy     (mixed concerns)
```

**Enhanced Structure:**
```
src/main/groovy/com/softwood/
├── controller/
│   └── McpController.groovy     (MCP protocol only)
├── model/
│   ├── QueryResult.groovy       (typed results)
│   ├── SchemaResult.groovy
│   └── ConnectionInfo.groovy
├── service/
│   ├── SqliteService.groovy     (core SQL operations)
│   ├── SqlSecurityService.groovy (validation)
│   ├── AuditService.groovy       (logging)
│   └── ResourceControlService.groovy (limits)
└── config/
    └── SecurityConfig.groovy     (security config)
```

### 2. Service Dependencies

```groovy
@Service
class SqliteService {
    
    private final SqlSecurityService securityService
    private final AuditService auditService
    private final ResourceControlService resourceControl
    
    SqliteService(
        SqlSecurityService securityService,
        AuditService auditService,
        ResourceControlService resourceControl
    ) {
        this.securityService = securityService
        this.auditService = auditService
        this.resourceControl = resourceControl
    }
    
    QueryResult executeQuery(String sql, String database) {
        long startTime = System.currentTimeMillis()
        
        try {
            // Validate security
            securityService.validateQuery(sql)
            
            // Execute with resource limits
            return resourceControl.executeWithLimits("query-${UUID.randomUUID()}", {
                doExecuteQuery(sql, database, startTime)
            })
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            auditService.logQueryExecution(database, sql, false, 0, duration, e.message)
            return QueryResult.failure(e.message, duration)
        }
    }
}
```

---

## 📝 Configuration Enhancements

### application.yml

```yaml
mcp:
  mode: ${MCP_MODE:http}
  
  sql:
    # Database paths
    allowed-databases: C:/data/databases,C:/Users/willw/sqlite
    
    # Query limits
    max-query-length: 50000
    max-result-rows: 10000
    max-execution-time-seconds: 30
    
    # Connection pool
    max-connections: 10
    max-concurrent-queries: 5
    connection-timeout-seconds: 10
    
    # Security
    enable-dangerous-pattern-check: true
    enable-system-table-protection: true
    enable-audit-logging: true
    sanitize-error-messages: true
    
    # Resource limits
    max-memory-mb: 512
    max-threads: 20
```

---

## 🎨 Code Quality

### 1. Avoid @CompileStatic on Dynamic Types

**❌ Causes Issues:**
```groovy
@CompileStatic
class MyService {
    Map<String, Object> getData() {
        def map = [:]
        map.key = someObject  // Type error!
        return map
    }
}
```

**✅ Better:**
```groovy
class MyService {
    Map<String, Object> getData() {
        def map = [:]
        map.key = someObject  // Works!
        return map
    }
}
```

**Best:**
```groovy
@CompileStatic
class MyService {
    QueryResult getData() {  // Use typed objects
        return QueryResult.success(rows, columns, duration)
    }
}
```

### 2. Type Math Operations

**❌ Wrong:**
```groovy
@CompileStatic
int calculateHalf(int maxLength) {
    int half = (maxLength - 3) / 2  // BigDecimal error!
    return half
}
```

**✅ Correct:**
```groovy
@CompileStatic
int calculateHalf(int maxLength) {
    int half = (int)((maxLength - 3) / 2)  // Explicit cast
    return half
}
```

---

## 🚀 Performance Optimizations

### 1. Connection Pooling
```groovy
@Service
class ConnectionPoolService {
    
    private final Map<String, DataSource> pools = new ConcurrentHashMap<>()
    
    DataSource getConnection(String databasePath) {
        return pools.computeIfAbsent(databasePath, { path ->
            createPooledDataSource(path)
        })
    }
    
    private DataSource createPooledDataSource(String path) {
        HikariConfig config = new HikariConfig()
        config.jdbcUrl = "jdbc:sqlite:${path}"
        config.maximumPoolSize = 10
        config.minimumIdle = 2
        return new HikariDataSource(config)
    }
}
```

### 2. Query Result Streaming
```groovy
Stream<Map<String, Object>> streamQuery(String sql) {
    // Return results as stream for large datasets
    // Avoid loading entire result set into memory
}
```

---

## 🐛 Common Pitfalls to Avoid

### 1. Test Setup Issues
Always inject ALL dependencies in tests:
```groovy
def setup() {
    auditService = new AuditService()
    securityService = new SqlSecurityService()
    resourceControl = new ResourceControlService()
    
    sqliteService = new SqliteService(
        securityService,
        auditService,
        resourceControl
    )
}
```

### 2. Regex Compilation
Pre-compile patterns at class level:
```groovy
// ✅ Good - compiled once
private static final Pattern DROP = Pattern.compile('(?i)\\bDROP\\b')

// ❌ Bad - recompiled every time
def validate(String sql) {
    if (sql ==~ /(?i)\bDROP\b/) { }  // Recompiles!
}
```

### 3. Division Type Issues
Always cast divisions in @CompileStatic:
```groovy
@CompileStatic
long calculateBytes(long bytes) {
    return (long)(bytes / (1024 * 1024))  // Explicit cast
}
```

---

## 📦 Deliverables for SQL Server

1. **SqlSecurityService.groovy** - Input validation
2. **QueryResult.groovy** - Typed query results
3. **SchemaResult.groovy** - Typed schema results
4. **AuditService.groovy** - Audit logging
5. **ResourceControlService.groovy** - Resource limits
6. **SqlSecurityServiceSpec.groovy** - Security tests
7. **Updated SqliteServiceSpec.groovy** - Type-safe tests
8. **Enhanced application.yml** - Security config

---

## ✅ Checklist for Implementation

- [ ] Add SqlSecurityService with dangerous pattern detection
- [ ] Add typed result objects (QueryResult, SchemaResult)
- [ ] Add AuditService for query logging
- [ ] Add ResourceControlService for limits
- [ ] Update tests with new dependencies
- [ ] Fix regex patterns (use Pattern.compile)
- [ ] Add security tests
- [ ] Update configuration with security settings
- [ ] Document security features
- [ ] Test with malicious SQL

---

## 🎯 Priority Order

1. **High Priority** (Security)
   - SqlSecurityService
   - Dangerous pattern detection
   - System table protection

2. **Medium Priority** (Quality)
   - Typed result objects
   - AuditService
   - Update tests

3. **Low Priority** (Performance)
   - ResourceControlService
   - Connection pooling
   - Query streaming

---

**Estimated Time:** 4-6 hours
**Complexity:** Medium
**Value:** High (security + quality + maintainability)

---

**Status:** Ready to Apply
**Next Project:** McpSqliteServer Enhancement
**Date:** January 28, 2026
