# R2DBC Implementation Strategy for Flyway

**Status**: Design Phase (Mapping Codebase)
**Updated**: February 27, 2026

## Core Constraints

This implementation adheres to **4 critical constraints** defined during project planning:

1. **JDBC & R2DBC Treated Equally (with JDBC Default)** - Both first-class citizens, but JDBC is default
2. **R2DBC Database-Agnostic** - No PostgreSQL/MySQL-specific code in core
3. **Seamless JDBC↔R2DBC Switching** - Users can switch without re-running migrations
4. **Full Backward Compatibility** - All existing JDBC functionality preserved, zero breaking changes

---

## Design Principle: JDBC + R2DBC Parity

Both connection types must be **first-class citizens** in Flyway, not JDBC-primary with R2DBC as an add-on.

### Architecture (Constraint 1)
```
┌─────────────────────────────────────────────────┐
│ Flyway Core (Connection-Agnostic)              │
│ - Migration execution                          │
│ - Schema history management                    │
│ - SQL parsing                                  │
└──────────┬──────────────────────────┬──────────┘
           │                          │
    ┌──────▼────────┐        ┌──────▼────────┐
    │ JDBC Adapter  │        │ R2DBC Adapter │
    │ (Default)     │        │ (Opt-in)      │
    └───────────────┘        └───────────────┘
```

## Backward Compatibility: Default to JDBC (Constraint 1 & 4)

**Behavior** (Constraint 1: JDBC Default):
1. If `spring.datasource.*` is configured → use JDBC (existing Flyway behavior)
2. If `spring.r2dbc.*` is configured → use R2DBC
3. If both → prefer JDBC, log warning
4. Explicit `spring.flyway.connection-type` property overrides auto-detection

**Backward Compatibility** (Constraint 4):
- Existing deployments continue using JDBC unchanged
- No breaking changes to public APIs
- No modifications to JDBC execution paths
- Users don't need to update unless explicitly opting into R2DBC

## Implementation Architecture

### 1. Connection Factory Abstraction Layer

**Current Reality**: Flyway 12.0.2 uses Native Connectors (NC) for non-JDBC databases (MongoDB, Spanner, etc.)

**Strategy**: Extend NC architecture to support R2DBC as a connection type (not just databases)

```java
// Existing
public sealed interface NativeConnectorsDatabase<T>
    extends Plugin, AutoCloseable permits
        AbstractNativeConnectorsDatabase,
        AbstractNativeConnectorsHybridDatabase { }

// New: R2DBC as a ConnectionType
public enum ConnectionType {
    JDBC,           // Existing
    EXECUTABLE,     // For API-based (MongoDB, etc.)
    API,            // For REST APIs
    R2DBC           // NEW: Reactive connections
}
```

### 2. Module Structure (Constraint 2: Database-Agnostic)

**Important**: R2DBC is database-agnostic. Core modules must not contain database-specific logic.

**Phase 1 - Completed** (Constraint 2 ensured):
```
flyway-nc/
└── flyway-r2dbc-core/                # Core R2DBC abstraction (database-agnostic) ✅
    ├── NativeConnectorsR2dbc.java     # Abstract base (NO database logic)
    ├── R2dbcExecutor.java             # Mono.block() execution strategy
    └── pom.xml                        # Dependencies: r2dbc-spi, reactor-core ONLY
```

**Phase 2 - Planned** (Database-specific adapters - each in separate module):
```
flyway-database/
├── flyway-database-nc-r2dbc-postgresql/  # PostgreSQL adapter (extends NativeConnectorsR2dbc)
├── flyway-database-nc-r2dbc-mysql/      # MySQL adapter
├── flyway-database-nc-r2dbc-mariadb/    # MariaDB adapter
└── flyway-database-nc-r2dbc-h2/         # H2 adapter
```

Each adapter must:
- Extend `NativeConnectorsR2dbc` (from core)
- Implement `createConnectionFactory()` with database-specific logic ONLY
- Reuse existing parser/validator from JDBC adapter (e.g., PostgresqlParser)
- Use identical schema history table structure as JDBC counterpart

**Phase 3 - Planned** (Spring Boot integration):
```
spring-boot-project/spring-boot-starters/spring-boot-starter-flyway/
├── FlywayAutoConfiguration           # Existing JDBC config - NO CHANGES (Constraint 4)
├── FlywayR2dbcAutoConfiguration      # NEW R2DBC config (only if JDBC not present)
├── FlywayProperties                  # Updated with connection-type property
└── R2dbcConnectionDetector           # Connection type detection logic
```

**Modules created in existing structures:**
- `flyway-nc/flyway-r2dbc-core/` - New core R2DBC module (extends flyway-nc-core)
- `flyway-database/flyway-database-nc-r2dbc-{dbname}/` - Database-specific modules (mirror MongoDB/Couchbase pattern)
- `flyway-spring-boot/flyway-spring-boot-r2dbc-starter/` - New Spring Boot starter

### 3. Constraint Validation Gates (For Each Phase)

**Phase 1 Validation** (Core Infrastructure - ✅ COMPLETED):
- [ ] NativeConnectorsR2dbc has NO database-specific code (Constraint 2)
- [ ] R2dbcExecutor is purely generic/reactive (Constraint 2)
- [ ] ConnectionType.R2DBC is additive-only enum (Constraint 4)
- [ ] Zero modifications to JDBC code paths (Constraint 4)
- [ ] Schema history table structure reviewed for compatibility (Constraint 3)

**Phase 2 Validation** (Database Adapters - BEFORE APPROVAL):
- [ ] PostgreSQL adapter: connection factory creation ONLY is database-specific
- [ ] PostgreSQL adapter: reuses PostgresqlParser (Constraint 2)
- [ ] PostgreSQL adapter: uses identical schema history table (Constraint 3)
- [ ] MySQL adapter: follows same pattern as PostgreSQL
- [ ] H2 adapter: follows same pattern as PostgreSQL
- [ ] All adapters extend NativeConnectorsR2dbc (not duplicating core)
- [ ] Integration tests verify seamless JDBC↔R2DBC switching (Constraint 3)
- [ ] Integration tests verify JDBC functionality unchanged (Constraint 4)

**Phase 3 Validation** (Spring Boot Integration - BEFORE APPROVAL):
- [ ] Existing FlywayAutoConfiguration NOT modified (Constraint 4)
- [ ] New FlywayR2dbcAutoConfiguration only activates if JDBC not present (Constraint 1)
- [ ] Bean ordering enforces JDBC preference when both present (Constraint 1)
- [ ] Connection type detection logic properly precedenced (Constraint 1)
- [ ] FlywayProperties.connectionType property functional (Constraint 1)
- [ ] Auto-detection from classpath works (Constraint 1)
- [ ] Spring Boot tests verify all configuration scenarios (Constraints 1, 3)

**Phase 4 Validation** (Testing & Documentation - BEFORE RELEASE):
- [ ] E2E tests verify JDBC and R2DBC produce identical results (Constraint 3)
- [ ] E2E tests verify JDBC↔R2DBC switching works without re-running migrations (Constraint 3)
- [ ] Performance tests show zero regression in JDBC path (Constraint 4)
- [ ] Documentation covers all 4 constraints clearly
- [ ] Release notes highlight that R2DBC is new, JDBC is unchanged default

---

### 4. Auto-Detection Logic (Spring Boot - Constraint 1)

**Smart Defaults**:

```java
public class FlywayR2dbcAutoConfiguration {
    @ConditionalOnMissingBean(Flyway.class)
    @ConditionalOnBean(ConnectionFactory.class)
    Flyway flywayR2dbc(
        ConnectionFactory connectionFactory,
        FlywayProperties properties
    ) {
        // ONLY if:
        // 1. spring.r2dbc.* is configured, OR
        // 2. spring.datasource NOT configured
        if (isR2dbcOnly() || !isJdbcConfigured()) {
            return createR2dbcFlyway(connectionFactory);
        }
        // else: JDBC will handle it (higher priority)
        return null;
    }
}
```

**Priority Order**:
1. Explicit property: `spring.flyway.connection-type=r2dbc|jdbc`
2. Bean presence: If only `ConnectionFactory` exists → R2DBC
3. Both present: JDBC wins (backward compat)
4. Neither: Fail with clear error message

### 4. Core Execution Strategy: Mono.block()

**Challenge**: Flyway is synchronous; R2DBC is reactive
**Solution**: Blocking adapter using Project Reactor (already in spring-boot-starter-webflux)

```java
public class R2dbcExecutor {
    private final ConnectionFactory factory;
    private Duration blockingTimeout = Duration.ofMinutes(5);

    public <T> T execute(Function<Connection, Publisher<T>> operation) {
        return Mono.from(factory.create())
            .flatMap(conn -> Mono.from(operation.apply(conn)))
            .block(blockingTimeout);  // Convert reactive → sync
    }
}
```

**Timeout Configurable Via**:
```yaml
spring:
  flyway:
    r2dbc:
      blocking-timeout: 10m
```

### 5. Parser & Schema History Reuse (Constraint 2 & 3)

**Constraint Enforcement**:
- **Constraint 2** (Database-Agnostic): No database logic duplication
- **Constraint 3** (Seamless Switching): Identical schema history enables switching

**Phase 2 Implementation Pattern** (for each R2DBC adapter):

```java
// flyway-database-postgresql has:
// - PostgresqlParser.class (SQL parsing - connection-agnostic)
// - PostgresqlDatabase.class (schema history table structure)

// Phase 2: flyway-database-nc-r2dbc-postgresql
public class PostgresqlR2dbcConnectors extends NativeConnectorsR2dbc {

    // ONLY database-specific: connection factory
    @Override
    protected ConnectionFactory createConnectionFactory(
        ResolvedEnvironment environment,
        Configuration configuration) {
        // PostgreSQL-specific driver configuration ONLY
        return ConnectionFactories.get("r2dbc:postgresql://...");
    }

    // REUSE: Parser (connection-agnostic, same for JDBC and R2DBC)
    public Parser getParser(Configuration config) {
        return new PostgresqlParser();  // Same class as JDBC PostgreSQL adapter
    }

    // REUSE: Schema history (identical table structure for JDBC and R2DBC)
    // Use identical table: flyway_schema_history (same columns, same structure)
}
```

**Why This Works** (Constraint 3):
- Parser is connection-agnostic (parses SQL regardless of JDBC vs R2DBC)
- Schema history table is identical (enables seamless JDBC↔R2DBC switching)
- Same SQL migration scripts work on both paths
- Users can query schema_history from either JDBC or R2DBC connections

### 6. Spring Boot Configuration Precedence

```yaml
# application.yml
spring:
  datasource:  # If present → use JDBC (takes precedence)
    url: jdbc:postgresql://...
    username: user
    password: pass

  # r2dbc ignored if datasource present
  r2dbc:
    url: r2dbc:postgresql://...

  flyway:
    locations: classpath:db/migration
    # connection-type auto-detected: JDBC (because datasource exists)
```

```yaml
# application-reactive.yml (profile-based)
spring:
  # datasource omitted
  r2dbc:
    url: r2dbc:postgresql://...
    username: user
    password: pass

  flyway:
    locations: classpath:db/migration
    # connection-type auto-detected: R2DBC
```

## Phase Breakdown

### Phase 1: Core R2DBC Infrastructure (database-agnostic foundations)
- [ ] `flyway-nc/flyway-r2dbc-core` module created
  - `NativeConnectorsR2dbc` abstract base class (extends NativeConnectorsJdbc pattern)
  - `R2dbcExecutor` (Mono.block() blocking strategy with configurable timeout)
  - `R2dbcConnection` wrapper for io.r2dbc.Connection
  - `R2dbcConnectionFactory` wrapper abstraction
  - `R2dbcSchemaHistory` generic schema history (database-agnostic, URL-based parsing)
  - Generic DDL/DML execution methods (no database-specific SQL)
  - Support for statement batching (R2DBC generic)
- [ ] Logging framework (Flyway's @CustomLog pattern)
- [ ] Unit tests for blocking execution, timeout behavior, error handling
- [ ] NO database-specific code in core module (PostgreSQL, MySQL specifics deferred to Phase 2)

### Phase 2: Database-Specific Adapters
- [ ] `flyway-r2dbc-postgresql` (reference implementation)
  - Reuse PostgresqlParser
  - Implement schema history
  - Quote identifier logic
  - Batch execution
- [ ] `flyway-r2dbc-mysql`
- [ ] `flyway-r2dbc-h2` (for testing)
- [ ] Integration tests per database

### Phase 3: Spring Boot Auto-Configuration (in Spring Boot repo)
**NOTE**: Changes required in https://github.com/spring-projects/spring-boot

This Flyway repo provides:
- `NativeConnectorsR2dbc` abstract base (already in Phase 1)
- `ConnectionType.R2DBC` enum (already in Phase 1)
- R2DBC database adapters (Phase 2)
- Integration via existing Plugin interface (already available)

Spring Boot repo must add:
- [ ] New `FlywayR2dbcAutoConfiguration` class
- [ ] Update `FlywayProperties` with connection-type and r2dbc config
- [ ] Update `spring-boot-starter-flyway` dependencies
- [ ] Implement `R2dbcConnectionDetector` for smart defaults
- [ ] Ensure proper bean ordering (@AutoConfigureBefore)
- [ ] Configuration properties validation
- [ ] Tests for JDBC/R2DBC precedence

**Backward Compatibility**:
- Keep existing `FlywayAutoConfiguration` UNCHANGED
- JDBC config has higher priority (runs first)
- R2DBC only activates if JDBC not present
- Explicit `connection-type` property overrides auto-detection

### Phase 4: Testing & Documentation
- [ ] E2E Spring Boot tests (Testcontainers)
- [ ] Migration compatibility tests (SQL works on both)
- [ ] User migration guide (JDBC → R2DBC)
- [ ] Error messages & troubleshooting
- [ ] Release notes

## Success Criteria

✓ R2DBC and JDBC have feature parity for core operations
✓ JDBC remains default (zero breaking changes)
✓ R2DBC is opt-in (explicit configuration switches mode)
✓ SQL migration scripts work identically on both
✓ No code duplication (parser/schema history reuse)
✓ Spring Boot auto-detection works seamlessly
✓ Clear error messages when misconfigured

## Known Constraints & Decisions

| Constraint | Why | Impact |
|-----------|-----|--------|
| Mono.block() | Flyway is sync, R2DBC is reactive | 5-min default timeout, configurable |
| Parser reuse | Avoid duplication | Database-specific modules depend on existing database modules |
| JDBC priority | Backward compatibility | Both present → JDBC wins |
| No Java migrations | Complexity, SQL sufficient | Document limitation, provide workarounds |
| Transaction model | R2DBC implicit | Map to Flyway transaction semantics |

---

*Next Step: Analyze codebase mapping (.planning/codebase/) to identify integration points*
