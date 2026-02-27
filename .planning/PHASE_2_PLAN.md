# Phase 2: R2DBC Database Adapters - Execution Plan

**Phase**: 2 (Database-Specific R2DBC Adapters)
**Status**: Ready for Execution
**Created**: February 27, 2026
**Based on**: PHASE_2_RESEARCH.md findings

---

## Phase Goal

Implement database-specific R2DBC adapters (PostgreSQL, MySQL, H2) that extend `NativeConnectorsR2dbc` from Phase 1, enabling R2DBC support for multiple databases while maintaining full backward compatibility and constraint adherence.

**Constraint Validation**: All work must maintain the 4 critical constraints defined in planning.

---

## Phase 2 Deliverables

✅ **Blocking Work (MUST complete first)**:
1. Register `R2dbcExecutorPlugin` in flyway-r2dbc-core
2. Register `R2dbcReaderPlugin` in flyway-r2dbc-core
3. Update META-INF/services to register plugins

✅ **Database Adapters** (in order):
1. `flyway-database-nc-r2dbc-h2` (testing, no Docker required)
2. `flyway-database-nc-r2dbc-postgresql` (reference implementation)
3. `flyway-database-nc-r2dbc-mysql` (complete the trio)

✅ **Integration Tests**:
- H2 integration tests (in-memory database)
- PostgreSQL integration tests (Testcontainers)
- MySQL integration tests (Testcontainers)
- Schema history compatibility tests
- JDBC↔R2DBC parity tests

---

## Task Breakdown

### Task 2.1: Implement R2dbcExecutorPlugin (BLOCKER)

**Why This First**: ConnectionType.R2DBC has no registered Executor. Without this, ExecutorFactory.getExecutor() throws "No executor found for connection type: R2DBC". This blocks all adapters.

**File Location**: `flyway-nc/flyway-r2dbc-core/src/main/java/org/flywaydb/nc/r2dbc/R2dbcExecutorPlugin.java`

**Implementation Pattern**:

```java
package org.flywaydb.nc.r2dbc;

import org.flywaydb.core.internal.executor.sql.SqlExecutor;
import org.flywaydb.core.internal.sqlscript.SqlScriptExecutorFactory;
import org.flywaydb.core.internal.sqlscript.SqlStatement;
import org.flywaydb.core.extensibility.Plugin;

public class R2dbcExecutorPlugin implements SqlExecutor, Plugin {
    private final R2dbcExecutor r2dbcExecutor;

    public R2dbcExecutorPlugin(R2dbcExecutor r2dbcExecutor) {
        this.r2dbcExecutor = r2dbcExecutor;
    }

    @Override
    public void execute(SqlStatement statement) {
        r2dbcExecutor.execute(sql -> {
            // Execute statement
            return executeStatement(sql, statement);
        });
    }

    // Implement required methods from SqlExecutor interface
}
```

**Constraints Checked**:
- [ ] No database-specific logic (Constraint 2)
- [ ] Generic R2DBC operations only (Constraint 2)
- [ ] Additive only, no JDBC changes (Constraint 4)

**Success Criteria**:
- ExecutorFactory.getExecutor(ConnectionType.R2DBC) returns plugin
- No runtime "No executor found" exceptions

---

### Task 2.2: Implement R2dbcReaderPlugin (BLOCKER)

**Why This Second**: Like ExecutorPlugin, Reader plugin is required for SQL script reading. Without it, schema history queries fail.

**File Location**: `flyway-nc/flyway-r2dbc-core/src/main/java/org/flywaydb/nc/r2dbc/R2dbcReaderPlugin.java`

**Implementation Pattern**:

```java
package org.flywaydb.nc.r2dbc;

import org.flywaydb.core.internal.sqlscript.SqlScriptFactory;
import org.flywaydb.core.extensibility.Plugin;

public class R2dbcReaderPlugin implements SqlScriptFactory, Plugin {
    private final R2dbcExecutor r2dbcExecutor;

    public R2dbcReaderPlugin(R2dbcExecutor r2dbcExecutor) {
        this.r2dbcExecutor = r2dbcExecutor;
    }

    @Override
    public SqlScript create(...) {
        // Use r2dbcExecutor to read SQL scripts from R2DBC connection
        return new R2dbcSqlScript(...);
    }

    // Implement required methods from SqlScriptFactory interface
}
```

**Constraints Checked**:
- [ ] No database-specific logic (Constraint 2)
- [ ] Generic R2DBC operations only (Constraint 2)
- [ ] Additive only, no JDBC changes (Constraint 4)

**Success Criteria**:
- SqlScriptFactory can read scripts via R2DBC
- No runtime SQL script reading failures

---

### Task 2.3: Register Plugins in META-INF/services

**File Location**: `flyway-nc/flyway-r2dbc-core/src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`

**Action**: Add two lines to existing plugin registration file:

```
org.flywaydb.nc.r2dbc.R2dbcExecutorPlugin
org.flywaydb.nc.r2dbc.R2dbcReaderPlugin
```

**Verification**: ServiceLoader discovers these plugins at runtime

---

### Task 2.4: Implement H2 R2DBC Adapter

**Why First**: No Docker container required, fastest feedback loop for testing implementation pattern.

**Module Location**: `flyway-database/flyway-database-nc-r2dbc-h2/`

**File Structure**:
```
flyway-database-nc-r2dbc-h2/
├── pom.xml
└── src/main/java/org/flywaydb/database/nc/r2dbc/h2/
    └── H2R2dbcDatabase.java
└── src/main/resources/META-INF/services/
    └── org.flywaydb.core.extensibility.Plugin
```

**pom.xml Pattern**:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-parent</artifactId>
    <version>12.0.2</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>

  <artifactId>flyway-database-nc-r2dbc-h2</artifactId>
  <name>${project.artifactId}</name>

  <dependencies>
    <!-- Core R2DBC support -->
    <dependency>
      <groupId>${project.groupId}</groupId>
      <artifactId>flyway-nc-r2dbc-core</artifactId>
      <version>${project.parent.version}</version>
    </dependency>

    <!-- H2 JDBC parser reuse -->
    <dependency>
      <groupId>${project.groupId}</groupId>
      <artifactId>flyway-database-h2</artifactId>
      <version>${project.parent.version}</version>
    </dependency>

    <!-- H2 R2DBC driver -->
    <dependency>
      <groupId>io.r2dbc</groupId>
      <artifactId>r2dbc-h2</artifactId>
      <version>0.9.1.RELEASE</version>
    </dependency>

    <!-- Lombok (optional, like other modules) -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
    </dependency>
  </dependencies>
</project>
```

**H2R2dbcDatabase.java Pattern** (~150 lines):

```java
package org.flywaydb.database.nc.r2dbc.h2;

import io.r2dbc.h2.H2ConnectionFactory;
import io.r2dbc.h2.H2ConnectionFactoryProvider;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.database.h2.H2Parser;
import org.flywaydb.core.internal.nc.r2dbc.NativeConnectorsR2dbc;
import org.flywaydb.core.internal.sqlscript.ParsedStatement;
import org.flywaydb.core.internal.sqlscript.Parser;
import org.flywaydb.core.extensibility.Plugin;

public class H2R2dbcDatabase extends NativeConnectorsR2dbc implements Plugin {

    @Override
    protected ConnectionFactory createConnectionFactory(
        ResolvedEnvironment environment,
        Configuration configuration) {

        // H2-specific connection factory creation
        String url = configuration.getUrl();
        // Convert JDBC URL to R2DBC URL if needed
        // E.g., jdbc:h2:mem:testdb -> r2dbc:h2:mem:testdb

        String r2dbcUrl = convertJdbcToR2dbcUrl(url);
        String user = configuration.getUser();
        String password = configuration.getPassword();

        return H2ConnectionFactories.get(H2ConnectionFactoryBuilder.h2()
            .option(H2ConnectionOption.OPTION_KEY, r2dbcUrl)
            .username(user)
            .password(password)
            .build());
    }

    @Override
    public Parser getParser(Configuration configuration) {
        // Reuse existing H2 JDBC parser (connection-agnostic)
        return new H2Parser();
    }

    private String convertJdbcToR2dbcUrl(String jdbcUrl) {
        // Convert jdbc:h2:... to r2dbc:h2:...
        return jdbcUrl.replaceFirst("^jdbc:", "r2dbc:");
    }
}
```

**META-INF/services/org.flywaydb.core.extensibility.Plugin**:
```
org.flywaydb.database.nc.r2dbc.h2.H2R2dbcDatabase
```

**Constraints Checked**:
- [ ] H2 implementation has NO PostgreSQL/MySQL-specific code (Constraint 2)
- [ ] Uses generic R2DBC APIs only (Constraint 2)
- [ ] Schema history table identical to JDBC (Constraint 3)
- [ ] Zero modifications to JDBC H2 adapter (Constraint 4)

**Success Criteria**:
- Module builds without errors
- H2R2dbcDatabase loads as plugin
- R2DBC connection factory created successfully

---

### Task 2.5: Implement PostgreSQL R2DBC Adapter (Reference Implementation)

**Module Location**: `flyway-database/flyway-database-nc-r2dbc-postgresql/`

**pom.xml Dependencies**:
```xml
<!-- PostgreSQL R2DBC driver (updated version from research) -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>r2dbc-postgresql</artifactId>
  <version>1.1.1.RELEASE</version>
</dependency>

<!-- Reuse PostgreSQL JDBC parser -->
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
  <version>${project.parent.version}</version>
</dependency>
```

**PostgreSQLR2dbcDatabase.java Pattern**:

```java
package org.flywaydb.database.nc.r2dbc.postgresql;

import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.database.postgresql.PostgreSQLParser;
import org.flywaydb.core.internal.nc.r2dbc.NativeConnectorsR2dbc;
import org.flywaydb.core.internal.sqlscript.Parser;
import org.flywaydb.core.extensibility.Plugin;

public class PostgreSQLR2dbcDatabase extends NativeConnectorsR2dbc implements Plugin {

    @Override
    protected ConnectionFactory createConnectionFactory(
        ResolvedEnvironment environment,
        Configuration configuration) {

        String url = configuration.getUrl();
        String user = configuration.getUser();
        String password = configuration.getPassword();

        // Convert JDBC URL to R2DBC URL
        // E.g., jdbc:postgresql://localhost:5432/mydb
        //    -> r2dbc:postgresql://localhost:5432/mydb
        String r2dbcUrl = convertJdbcToR2dbcUrl(url);

        return ConnectionFactories.get(r2dbcUrl +
            "?user=" + user +
            "&password=" + password);
    }

    @Override
    public Parser getParser(Configuration configuration) {
        // Reuse existing PostgreSQL JDBC parser
        return new PostgreSQLParser();
    }

    private String convertJdbcToR2dbcUrl(String jdbcUrl) {
        return jdbcUrl.replaceFirst("^jdbc:", "r2dbc:");
    }
}
```

**Constraints Checked**:
- [ ] PostgreSQL implementation has NO MySQL/H2-specific code (Constraint 2)
- [ ] Uses generic R2DBC APIs + PostgreSQL driver only (Constraint 2)
- [ ] Parser reuse works correctly (Constraint 2)
- [ ] Schema history table identical to JDBC (Constraint 3)
- [ ] Zero modifications to JDBC PostgreSQL adapter (Constraint 4)

---

### Task 2.6: Implement MySQL R2DBC Adapter

**Important**: Use `io.asyncer:r2dbc-mysql:1.4.1` (NOT the abandoned dev.miku version)

**pom.xml Dependencies**:
```xml
<!-- MySQL R2DBC driver (corrected from research) -->
<dependency>
  <groupId>io.asyncer</groupId>
  <artifactId>r2dbc-mysql</artifactId>
  <version>1.4.1</version>
</dependency>

<!-- Reuse MySQL JDBC parser -->
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-mysql</artifactId>
  <version>${project.parent.version}</version>
</dependency>
```

**MySQLR2dbcDatabase.java Pattern**:

```java
package org.flywaydb.database.nc.r2dbc.mysql;

import io.asyncer.r2dbc.mysql.MySqlConnectionFactory;
import io.asyncer.r2dbc.mysql.MySqlConnectionFactoryProvider;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.database.mysql.MySQLParser;
import org.flywaydb.core.internal.nc.r2dbc.NativeConnectorsR2dbc;
import org.flywaydb.core.internal.sqlscript.Parser;
import org.flywaydb.core.extensibility.Plugin;

public class MySQLR2dbcDatabase extends NativeConnectorsR2dbc implements Plugin {

    @Override
    protected ConnectionFactory createConnectionFactory(
        ResolvedEnvironment environment,
        Configuration configuration) {

        String url = configuration.getUrl();
        String user = configuration.getUser();
        String password = configuration.getPassword();

        // Convert JDBC URL to R2DBC URL
        // E.g., jdbc:mysql://localhost:3306/mydb
        //    -> r2dbc:mysql://localhost:3306/mydb
        String r2dbcUrl = convertJdbcToR2dbcUrl(url);

        return ConnectionFactories.get(r2dbcUrl +
            "?user=" + user +
            "&password=" + password);
    }

    @Override
    public Parser getParser(Configuration configuration) {
        // Reuse existing MySQL JDBC parser
        return new MySQLParser();
    }

    private String convertJdbcToR2dbcUrl(String jdbcUrl) {
        return jdbcUrl.replaceFirst("^jdbc:", "r2dbc:");
    }
}
```

**Constraints Checked**:
- [ ] MySQL implementation has NO PostgreSQL/H2-specific code (Constraint 2)
- [ ] Uses io.asyncer:r2dbc-mysql (not abandoned dev.miku) (Constraint 2)
- [ ] Parser reuse works correctly (Constraint 2)
- [ ] Schema history table identical to JDBC (Constraint 3)
- [ ] Zero modifications to JDBC MySQL adapter (Constraint 4)

---

### Task 2.7: Integration Tests - H2

**Test Location**: `flyway-database-nc-r2dbc-h2/src/test/java/.../H2R2dbcDatabaseTest.java`

**Test Pattern** (using Testcontainers optional for H2):

```java
class H2R2dbcDatabaseTest {

    @Test
    void testR2dbcConnectionCreation() {
        // Create R2DBC connection using H2 adapter
        // Verify connection is established
    }

    @Test
    void testSchemaHistoryTableCreation() {
        // Create schema history table
        // Verify table structure matches JDBC version
    }

    @Test
    void testMigrationExecution() {
        // Execute test migration via R2DBC
        // Verify migration recorded in schema history
    }

    @Test
    void testJdbcR2dbcSwitching() {
        // Create migration via JDBC
        // Read schema history via R2DBC
        // Verify schema history is identical
    }
}
```

**Constraints Checked**:
- [ ] Tests verify seamless JDBC↔R2DBC switching (Constraint 3)
- [ ] Tests verify schema history compatibility (Constraint 3)
- [ ] Tests verify zero impact on JDBC path (Constraint 4)

---

### Task 2.8: Integration Tests - PostgreSQL

**Test Dependencies**:
```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

**Test Pattern**:

```java
@Testcontainers
class PostgreSQLR2dbcDatabaseTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"));

    @Test
    void testR2dbcConnectionCreation() {
        // Similar pattern to H2 tests
    }

    @Test
    void testJdbcR2dbcParity() {
        // Execute same migrations via JDBC and R2DBC
        // Compare results (row counts, schema structure)
    }

    @Test
    void testSeamlessSwitching() {
        // Migrate with JDBC, verify with R2DBC
        // Migrate with R2DBC, verify with JDBC
        // Verify no conflicts or re-running needed
    }
}
```

---

### Task 2.9: Integration Tests - MySQL

**Test Dependencies**:
```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>mysql</artifactId>
  <scope>test</scope>
</dependency>
```

**Test Pattern**: Same as PostgreSQL, using MySQLContainer

---

## Implementation Order & Dependencies

```
Phase 2 Execution Order:
├─ Task 2.1: R2dbcExecutorPlugin (BLOCKER - 2h)
├─ Task 2.2: R2dbcReaderPlugin (BLOCKER - 2h)
├─ Task 2.3: Register plugins (0.5h) [depends on 2.1, 2.2]
│
├─ Task 2.4: H2 R2DBC Adapter (3h) [depends on 2.3]
├─ Task 2.5: H2 Integration Tests (2h) [depends on 2.4]
│
├─ Task 2.6: PostgreSQL R2DBC Adapter (3h) [depends on 2.3]
├─ Task 2.7: PostgreSQL Integration Tests (3h) [depends on 2.6]
│
├─ Task 2.8: MySQL R2DBC Adapter (3h) [depends on 2.3]
├─ Task 2.9: MySQL Integration Tests (3h) [depends on 2.8]
│
└─ All tasks can run in parallel after 2.3 is complete

Estimated Total: ~22 hours (can parallelize to ~8-10 hours with 3 parallel developers)
```

---

## Git Commit Strategy

**Atomic commits** (each task = one commit):

```
Task 2.1: git commit "Phase 2: Implement R2dbcExecutorPlugin"
Task 2.2: git commit "Phase 2: Implement R2dbcReaderPlugin"
Task 2.3: git commit "Phase 2: Register Executor/Reader plugins in META-INF/services"
Task 2.4: git commit "Phase 2: Implement H2 R2DBC adapter (flyway-database-nc-r2dbc-h2)"
Task 2.5: git commit "Phase 2: Add H2 R2DBC integration tests"
Task 2.6: git commit "Phase 2: Implement PostgreSQL R2DBC adapter (reference implementation)"
Task 2.7: git commit "Phase 2: Add PostgreSQL R2DBC integration tests"
Task 2.8: git commit "Phase 2: Implement MySQL R2DBC adapter"
Task 2.9: git commit "Phase 2: Add MySQL R2DBC integration tests"
```

---

## Constraint Validation Gates

**Before Task 2.1 Approval**:
- [ ] R2dbcExecutorPlugin implementation reviewed
- [ ] No database-specific code (Constraint 2)
- [ ] No JDBC modifications (Constraint 4)

**Before Task 2.3 Approval**:
- [ ] Both plugins compile and register successfully
- [ ] ServiceLoader picks up plugins at runtime
- [ ] ConnectionType.R2DBC now recognized by ExecutorFactory

**Before Each Adapter Approval**:
- [ ] Adapter code reviewed for database-specific logic placement (Constraint 2)
- [ ] Parser reuse verified (Constraint 2)
- [ ] Schema history table structure verified identical to JDBC (Constraint 3)
- [ ] Zero JDBC adapter modifications (Constraint 4)

**Before Integration Tests Approval**:
- [ ] Tests verify schema history compatibility (Constraint 3)
- [ ] Tests verify seamless JDBC↔R2DBC switching (Constraint 3)
- [ ] Tests verify no JDBC performance regression (Constraint 4)

---

## Success Criteria

Phase 2 is **COMPLETE** when:

1. ✅ R2dbcExecutorPlugin and R2dbcReaderPlugin registered and functional
2. ✅ Three database adapters implemented (H2, PostgreSQL, MySQL)
3. ✅ Each adapter extends NativeConnectorsR2dbc correctly
4. ✅ Each adapter reuses existing JDBC parser (no duplication)
5. ✅ Integration tests pass for all three databases
6. ✅ JDBC↔R2DBC switching verified to work seamlessly
7. ✅ Schema history table compatibility verified
8. ✅ All 4 constraints maintained (verified by gates above)
9. ✅ All commits pushed to feature branch on GitHub
10. ✅ Code ready for review and Phase 3 planning

---

## Known Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Plugins not discovered at runtime | HIGH | Verify META-INF/services files created, test ServiceLoader.load() |
| Parser reuse fails (JDBC deps) | MEDIUM | Verify parsers extend only core Plugin interface, no JDBC classes |
| URL conversion (JDBC→R2DBC) broken | MEDIUM | Add URL conversion tests for each database |
| Schema history table DDL mismatch | HIGH | Copy exact DDL from JDBC adapters, verify with schema comparison tests |
| Driver version conflicts | MEDIUM | Use versions from PHASE_2_RESEARCH.md (verified in Maven Central) |
| Integration test flakiness | LOW | Use Testcontainers with wait strategies, add test retries |

---

## Questions Resolved (from Research)

✅ **Per-statement vs. persistent connection**: Phase 1 design uses Mono.block() per statement (stateless), matches existing NC pattern

✅ **H2 parser location**: H2Parser in flyway-database-h2 module, can be reused

✅ **Non-transactional statement handling**: NativeConnectorsR2dbc handles via existing parser flag system

✅ **MySQL BOOLEAN vs TINYINT(1)**: io.asyncer:r2dbc-mysql returns correct types, no special handling needed

---

## Next Phase (Phase 3)

After Phase 2 completes:
- Phase 3 planning document ready (PHASE_3_SPRING_BOOT_CHANGES.md already drafted)
- Create pull request to flyway/flyway with Phase 2 work
- Coordinate with Spring Boot maintainers for Phase 3
- Begin Spring Boot auto-configuration planning

---

*Plan created: 2026-02-27*
*Ready for execution: YES ✅*
*Estimated completion: 8-10 hours (with parallelization)*
