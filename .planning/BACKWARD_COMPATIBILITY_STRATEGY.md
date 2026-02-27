# Backward Compatibility Strategy for R2DBC Support

**Status**: Strategy Document for Phase 1+
**Criticality**: ESSENTIAL - Zero tolerance for breaking changes
**Date**: February 27, 2026

---

## Principle: R2DBC is ADDITIVE ONLY

R2DBC support must be added without modifying, breaking, or degrading any existing Flyway functionality. Every existing production migration and deployment must continue to work unchanged.

---

## Core Guardrails

### 1. Public API Stability

**Protected Interfaces/Classes (No Changes)**:
- `Flyway` - public entry point
- `Configuration`, `ClassicConfiguration`, `FluentConfiguration`
- `MigrationInfo`, `MigrationVersion`, `MigrationState`
- `MigrateResult`, `InfoResult`, `ValidateResult`, etc. (output DTOs)
- `Callback`, `JavaMigration`, `JavaMigrationProvider` interfaces
- `Plugin`, `VerbExtension`, `DatabaseType` interfaces

**Rule**: Any change to public API signatures must be additive only (new methods, new parameters with defaults, new enum values).

### 2. Enum Addition Safety

**Change Made**: Added `R2DBC` to `ConnectionType` enum
```java
public enum ConnectionType {
    JDBC,        // existing
    EXECUTABLE,  // existing
    API,         // existing
    R2DBC        // NEW
}
```

**Why Safe**:
- Switch/case statements that don't handle R2DBC will compile
- Existing code comparing `== ConnectionType.JDBC` continues working
- New code can explicitly check for R2DBC if needed

**Potential Issues & Mitigations**:
- ❌ If code uses `enum.values()` for UI/iteration → add to changelog
- ✓ Document new enum value in release notes
- ✓ Add comments explaining when R2DBC appears

### 3. JDBC Execution Path (ZERO Changes)

**Protected Paths**:
- Legacy JDBC execution in `flyway-core/src/main/java/org/flywaydb/core/internal/command/DbMigrate.java`
- JDBC database adapters (`flyway-database-postgresql`, `flyway-database-mysql`, etc.)
- JDBC schema history table management
- JDBC connection pooling and lifecycle
- JDBC retry logic, locking, transactions

**Rule**: Do NOT modify any JDBC code. R2DBC uses NEW code paths only.

### 4. Default Behavior Unchanged

**Current Default**: When both `spring.datasource` and `spring.r2dbc` are configured → **JDBC wins**

```yaml
spring:
  datasource:  # JDBC config
    url: jdbc:postgresql://...
  r2dbc:       # R2DBC config (ignored if datasource present)
    url: r2dbc:postgresql://...
  flyway:
    # Still uses JDBC path - BACKWARD COMPATIBLE
```

**Rule**: No existing configuration should automatically switch to R2DBC path. Opt-in only.

### 5. Schema History Table Compatibility

**Current Schema History Table** (JDBC):
- Table name: `flyway_schema_history` (configurable)
- Columns: `installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`
- Structure: Identical across all databases

**R2DBC Requirement**:
- ✓ Use SAME table structure (no new columns, no schema changes)
- ✓ Write/read same format as JDBC
- ✓ Same SQL for querying history
- ✓ Enables seamless JDBC↔R2DBC switching

**Rule**: Schema history is database property, not connection-type property. No duplication.

### 6. SQL Migration Compatibility

**Migration Scripts** (V001__Create_table.sql):
- Must work identically on JDBC and R2DBC paths
- No dialect-specific paths based on connection type
- All existing .sql files continue working unchanged

**Rule**: SQL is the source of truth. Connection type is implementation detail.

---

## Phase-by-Phase Backward Compatibility

### Phase 1: Core R2DBC Infrastructure ✓ COMPLETED

**What Changed**:
- ✓ Added `ConnectionType.R2DBC` enum value
- ✓ New module: `flyway-nc/flyway-r2dbc-core`
- ✓ New classes: `NativeConnectorsR2dbc`, `R2dbcExecutor`

**Backward Compatibility Status**:
- ✓ No changes to existing JDBC code
- ✓ No changes to public APIs
- ✓ No changes to default behavior
- ✓ Enum addition is safe (existing switch statements unaffected)
- ✓ New classes don't affect existing execution paths

**Verification**: `mvn clean test -pl flyway-database-postgresql` should pass unchanged

### Phase 2: Database-Specific R2DBC Adapters (PLANNED)

**What Will Change**:
- New modules: `flyway-database-nc-r2dbc-postgresql`, `flyway-database-nc-r2dbc-mysql`, etc.
- New classes extending `NativeConnectorsR2dbc`
- Registration via `META-INF/services` (additive plugin mechanism)

**Backward Compatibility Rules**:
- ✓ Existing database adapters (`flyway-database-postgresql`, etc.) UNTOUCHED
- ✓ New adapters registered separately via ServiceLoader
- ✓ No changes to existing parsers, schema history logic, or connection management
- ✓ R2DBC adapters reuse existing parsers (composition, not modification)

**Verification**: Existing JDBC database tests must pass

### Phase 3: Spring Boot Auto-Configuration (PLANNED)

**What Will Change**:
- New module: `flyway-spring-boot-r2dbc-starter`
- New auto-configuration class: `FlywayR2dbcAutoConfiguration`
- New detection logic: `R2dbcConnectionDetector`

**Backward Compatibility Rules**:
- ✓ Existing `FlywayAutoConfiguration` untouched
- ✓ New auto-config only activates when R2DBC-specific conditions met
- ✓ Does NOT interfere with existing JDBC auto-config
- ✓ `@ConditionalOnBean` ensures JDBC config has priority
- ✓ Users can explicitly choose via `spring.flyway.connection-type=jdbc` property

**Detection Logic** (Priority Order):
1. If explicit: `spring.flyway.connection-type=jdbc` → use JDBC (existing behavior)
2. If explicit: `spring.flyway.connection-type=r2dbc` → use R2DBC (new)
3. If both `datasource` and `ConnectionFactory` present → use JDBC (backward compat)
4. If only `ConnectionFactory` present → use R2DBC (new)
5. If only `datasource` present → use JDBC (existing)
6. If neither present → error (existing behavior unchanged)

**Verification**: Spring Boot JDBC integration tests must pass unchanged

### Phase 4: Testing & Documentation (PLANNED)

**Backward Compatibility Validation**:
- ✓ Run existing test suites for all databases (JDBC path)
- ✓ Verify schema history compatibility (read/write identical format)
- ✓ Verify migration execution identical SQL works on both
- ✓ Performance regression tests on JDBC path
- ✓ All existing examples in docs must work unchanged

---

## Testing Backward Compatibility

### Unit Test Strategy

```java
// Existing JDBC tests must PASS without modification
@Test
void testJdbcPostgreSqlMigration_UnchangedFromBefore() {
    Configuration config = new ClassicConfiguration()
        .setDataSource("jdbc:postgresql://...", "user", "pass");
    Flyway flyway = new Flyway(config);
    MigrateResult result = flyway.migrate();
    // Should work exactly as before R2DBC support
    assertTrue(result.success);
}
```

### Integration Test Strategy

```java
// JDBC path must produce identical schema history
@Test
void testJdbcAndR2dbcUseIdenticalSchemaHistory() {
    // Migrate with JDBC
    // Read schema_history table
    List<SchemaHistoryItem> jdbcHistory = readSchemaHistory(jdbcConnection);

    // Migrate with R2DBC (same migration scripts)
    // Read schema_history table
    List<SchemaHistoryItem> r2dbcHistory = readSchemaHistory(r2dbcConnection);

    // Must be identical - same table, same structure, same data
    assertEquals(jdbcHistory, r2dbcHistory);
}
```

### Smoke Test Strategy

```bash
# Before R2DBC changes
mvn clean test -pl flyway-database-postgresql

# After R2DBC changes
mvn clean test -pl flyway-database-postgresql
# Same output - no regressions
```

---

## CI/CD Validation

### Pre-Release Checklist

- [ ] All existing JDBC test suites pass (PostgreSQL, MySQL, SQL Server, Oracle, etc.)
- [ ] No performance regression in JDBC path (benchmark tests)
- [ ] Schema history table structure unchanged
- [ ] Migration execution produces identical results (JDBC vs previous version)
- [ ] All public API contracts honored (no signature changes)
- [ ] New enum value documented in release notes
- [ ] No breaking changes to configuration properties
- [ ] Spring Boot integration tests pass for JDBC path
- [ ] CLI tests pass for JDBC databases

### Release Notes Strategy

```markdown
## Version 12.1.0

### New Features
- **R2DBC Support (Preview)**: Flyway now supports R2DBC for reactive applications
  - Enable via `spring.r2dbc` configuration
  - Auto-detected from classpath when appropriate
  - Schema history compatible with JDBC path

### Backward Compatibility
- ✅ All existing JDBC migrations and configurations work unchanged
- ✅ Default behavior: JDBC preferred when both configured
- ✅ No breaking changes to public APIs
- ✅ R2DBC is opt-in feature - no impact on existing users
```

---

## Known Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Enum switch statements break on new value | LOW | Document in release notes, encourage users to add R2DBC case to switches |
| Schema history table structure changes | CRITICAL | Enforce identical structure between JDBC and R2DBC paths via tests |
| JDBC performance regression | CRITICAL | Benchmark tests comparing pre/post R2DBC changes |
| Accidental JDBC code modification | CRITICAL | Code review focusing on JDBC files, CI validation of JDBC tests |
| Spring Boot config precedence unclear | MEDIUM | Clear documentation and examples showing JDBC wins when both present |
| Mixed JDBC/R2DBC migrations fail | MEDIUM | Integration tests verifying seamless switching on same migrations |

---

## What NOT to Do

❌ Modify any JDBC database adapter code
❌ Change JDBC connection pooling or lifecycle
❌ Add new columns to schema history table
❌ Change default connection type from JDBC
❌ Add breaking changes to Configuration interface
❌ Modify existing migration execution logic
❌ Change SQL parsing for JDBC migrations
❌ Remove or rename existing ConnectionType enum values

---

## What IS OK to Do

✅ Add new enum values to ConnectionType
✅ Create new R2DBC-specific classes
✅ Register R2DBC via existing plugin mechanism (ServiceLoader)
✅ Add new optional configuration properties
✅ Create new optional Spring Boot auto-configurations
✅ Reuse existing parsers via composition
✅ Update documentation and examples
✅ Add new integration tests for R2DBC path

---

## Validation Checklist (Each Phase)

- [ ] Existing JDBC test suites pass unchanged
- [ ] No modifications to flyway-core JDBC paths
- [ ] No modifications to existing database adapters
- [ ] No modifications to public API contracts
- [ ] Schema history table format unchanged
- [ ] Default behavior (JDBC preferred) validated
- [ ] Release notes document any new enums/properties
- [ ] Documentation clarifies R2DBC is opt-in
- [ ] Code review confirms no accidental JDBC changes

---

*Backward compatibility is not negotiable. When in doubt, do nothing to existing code.*
