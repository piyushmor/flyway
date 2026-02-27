# R2DBC Implementation Strategy for Flyway

**Status**: Design Phase (Mapping Codebase)
**Updated**: February 27, 2026

## Core Constraint: JDBC + R2DBC Parity

Both connection types must be **first-class citizens** in Flyway, not JDBC-primary with R2DBC as an add-on.

### Design Principle
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

## Backward Compatibility: Default to JDBC

**Behavior**:
1. If `spring.datasource.*` is configured → use JDBC (existing Flyway behavior)
2. If `spring.r2dbc.*` is configured → use R2DBC
3. If both → prefer JDBC, log warning
4. Migration scripts (SQL) work identically on both paths

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

### 2. Module Structure

**Important**: R2DBC is database-agnostic. Core modules must not contain database-specific logic.

```
flyway-nc/
├── flyway-r2dbc-core/                # Core R2DBC abstraction (database-agnostic)
│   ├── NativeConnectorsR2dbc          # Abstract base for all R2DBC connectors
│   ├── R2dbcExecutor                  # Mono.block() execution strategy
│   ├── R2dbcConnection                # Wraps R2DBC Connection
│   ├── R2dbcConnectionFactory         # ConnectionFactory wrapper
│   └── R2dbcSchemaHistory             # Generic schema history model
│
├── flyway-database-nc-r2dbc-postgresql/  # PostgreSQL (extends NativeConnectorsR2dbc)
├── flyway-database-nc-r2dbc-mysql/      # MySQL
├── flyway-database-nc-r2dbc-mariadb/    # MariaDB
└── flyway-database-nc-r2dbc-h2/         # H2

flyway-spring-boot/
└── flyway-spring-boot-r2dbc-starter/    # Spring Boot integration
    ├── FlywayR2dbcAutoConfiguration     # Auto-detect & wire
    └── R2dbcConnectionDetector          # Smart detection logic
```

**Modules created in existing structures:**
- `flyway-nc/flyway-r2dbc-core/` - New core R2DBC module (extends flyway-nc-core)
- `flyway-database/flyway-database-nc-r2dbc-{dbname}/` - Database-specific modules (mirror MongoDB/Couchbase pattern)
- `flyway-spring-boot/flyway-spring-boot-r2dbc-starter/` - New Spring Boot starter

### 3. Auto-Detection Logic (Spring Boot)

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

### 5. Parser & Schema History Reuse

**NO DUPLICATION**: Reuse existing parsers from `flyway-database-postgresql` etc.

```java
// flyway-database-postgresql has:
// - PostgresqlParser.class
// - PostgresqlDatabase.class (schema history logic)

// flyway-r2dbc-postgresql extends/wraps:
public class R2dbcPostgresqlDatabase extends AbstractR2dbcDatabase {
    private final PostgresqlParser parser;
    private final PostgresqlSchemaHistory historyLogic;

    // Reuse parser
    @Override
    public Parser getParser(Configuration config) {
        return parser; // Same parser as JDBC PostgreSQL
    }
}
```

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
