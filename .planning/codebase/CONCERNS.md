# Codebase Concerns

**Analysis Date:** 2026-02-27
**Focus:** R2DBC readiness, synchronous assumptions, connection handling, technical debt

---

## R2DBC Readiness Gaps

### Entire Connection Layer Assumes `java.sql.Connection`

- **Issue:** The entire internal connection model is built on `java.sql.Connection`. The base `Connection` class, all `Database` subclasses, `JdbcTemplate`, `SchemaHistory`, `MigrationExecutor.Context`, `JavaMigration.Context`, and `FlywayExecutor` all hardcode JDBC types.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Connection.java` — wraps `java.sql.Connection` directly, forces autoCommit=true in constructor
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Database.java` — holds `rawMainJdbcConnection`, `jdbcMetaData`, `JdbcConnectionFactory`
  - `flyway-core/src/main/java/org/flywaydb/core/internal/jdbc/JdbcTemplate.java` — all query/update methods take `java.sql.Connection`
  - `flyway-core/src/main/java/org/flywaydb/core/api/executor/Context.java` — `getConnection()` returns `java.sql.Connection`
  - `flyway-core/src/main/java/org/flywaydb/core/api/migration/Context.java` — same, exposes `java.sql.Connection` to user-written migrations
  - `flyway-core/src/main/java/org/flywaydb/core/api/configuration/Configuration.java` — `getDataSource()` returns `javax.sql.DataSource`
- **Impact:** R2DBC cannot implement these contracts; any R2DBC implementation must bypass this entire layer or introduce a parallel execution path.
- **Fix approach:** R2DBC integration must use the Native Connectors (NC) architecture (`flyway-core/src/main/java/org/flywaydb/core/internal/nc/`) which provides an alternative non-JDBC plugin pathway. The NC path's `NativeConnectorsDatabase` interface in `flyway-core/src/main/java/org/flywaydb/core/internal/nc/NativeConnectorsDatabase.java` replaces the JDBC `Database`/`Connection` hierarchy.

### `JavaMigration.Context` Exposes JDBC Connection to Users

- **Issue:** User-written Java migrations receive a `Context` object where `getConnection()` returns `java.sql.Connection`. In R2DBC mode, this will return `null` (as noted in existing planning docs at `docs/planning/R2DBC_SPRING_BOOT_INTEGRATION.md` section 5.4).
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/api/migration/Context.java`
  - `flyway-core/src/main/java/org/flywaydb/core/api/migration/JavaMigration.java`
- **Impact:** Existing Java migrations that call `context.getConnection()` will break silently (null return) or throw NPE when running under R2DBC. No migration path exists for them.
- **Fix approach:** A new `R2dbcContext` interface or a separate `JavaMigrationR2dbc` interface returning `io.r2dbc.spi.Connection` is needed. Users must be told to implement a different interface for R2DBC Java migrations.

### Schema History Table Creation Uses `Thread.sleep` Retry Loop

- **Issue:** `JdbcTableSchemaHistory.create()` has an infinite retry loop with `Thread.sleep(1000)` when the schema history table creation fails due to a race (e.g., concurrent Flyway instances at startup).
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/schemahistory/JdbcTableSchemaHistory.java` — line 134
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/InsertRowLock.java` — line 72
- **Impact:** `Thread.sleep` blocks the calling thread for 1-second intervals. In a reactive environment (Project Reactor, Netty event loop), this would block the I/O thread. R2DBC's NC equivalent must use non-blocking retry (e.g., `Mono.delay()`).
- **Fix approach:** The NC schema history implementation (in `flyway-core/src/main/java/org/flywaydb/core/internal/nc/schemahistory/`) must not use `Thread.sleep`.

### `InsertRowLock` Uses `ScheduledExecutorService` with Blocking JDBC Calls

- **Issue:** `InsertRowLock` (used by several databases for distributed locking) starts a background `ScheduledExecutorService` thread that periodically calls `jdbcTemplate.executeStatement()` to refresh the lock row. This assumes blocking JDBC.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/InsertRowLock.java`
- **Impact:** The lock-refresh mechanism cannot be trivially adapted to R2DBC. Database-specific locking strategies (PostgreSQL advisory locks, MySQL named locks) must each be re-implemented using their R2DBC equivalents.
- **Fix approach:** Each R2DBC database plugin must implement its own locking mechanism (e.g., `pg_advisory_lock` via R2DBC statement, not via `InsertRowLock`).

### `RetryStrategy` Uses Static Mutable Fields

- **Issue:** `RetryStrategy.numberOfRetries` and `RetryStrategy.unlimitedRetries` are `private static` mutable fields. Setting them via `RetryStrategy.setNumberOfRetries()` changes behaviour globally across all concurrent Flyway instances in the same JVM.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/strategy/RetryStrategy.java` — lines 35–36
- **Impact:** In multi-tenant reactive applications running multiple Flyway instances simultaneously, one instance's configuration can corrupt the retry count of another. Also makes testing non-deterministic.
- **Fix approach:** Make `numberOfRetries` and `unlimitedRetries` instance fields. Pass `lockRetryCount` from `Configuration` at construction time instead of via static setter.

---

## Tech Debt

### `ClassicConfiguration.getDataSource()` Marked "TODO: This needs work"

- **Issue:** The `getDataSource()` method contains a comment `// TODO: This needs work` (line 245) because it silently creates a new `DriverDataSource` when a URL is present, but skips creation when Native Connectors mode is active (`NativeConnectorsModeUtils.canCreateDataSource(this)`). The interaction between "modern" toml-based config and "classic" config is inconsistent.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/api/configuration/ClassicConfiguration.java` — line 245
- **Impact:** When integrating R2DBC, the `getDataSource()` entry point may return `null` or return a JDBC `DataSource` when an R2DBC `ConnectionFactory` was intended. Configuration routing logic for R2DBC requires careful placement here.
- **Fix approach:** Once the NC/R2DBC pathway is established, a clear gating condition for R2DBC URLs (e.g., `url.startsWith("r2dbc:")`) must be added and the TODO resolved.

### `ResolvedEnvironment.nativeUrl` is a Placeholder

- **Issue:** `ResolvedEnvironment` contains a field `private String nativeUrl; //TODO - not implemented, placeholder only.` This field was intended for native (non-JDBC) connection strings but has never been implemented.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/models/ResolvedEnvironment.java` — line 46
- **Impact:** R2DBC URL handling (e.g., `r2dbc:postgresql://...`) has no designated field in the resolved environment model. It would currently land in the regular `url` field, which is also used by JDBC. This creates ambiguity — both JDBC and R2DBC URL schemes are stored in the same field.
- **Fix approach:** Implement `nativeUrl` as the field for R2DBC and non-JDBC connection strings, remove the TODO, and update `NativeConnectorsDatabase.supportsUrl()` to read from `nativeUrl`.

### Configuration Model Split (Classic vs. Modern)

- **Issue:** There are two parallel configuration models: the old `ClassicConfiguration` (2272 lines, `flyway-core/src/main/java/org/flywaydb/core/api/configuration/ClassicConfiguration.java`) and a "modern" TOML-based model (`ConfigurationModel`, `FlywayModel`, `EnvironmentModel`). `Configuration.getModernConfig()` is marked `@apiNote Currently under development and not recommended for use`. The two systems co-exist with bridging logic throughout `ClassicConfiguration`.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/api/configuration/ClassicConfiguration.java` — 2272 lines
  - `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/models/ConfigurationModel.java`
  - `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/models/FlywayModel.java`
  - `flyway-core/src/main/java/org/flywaydb/core/api/configuration/Configuration.java` — line 43–45
- **Impact:** Any new configuration key (e.g., R2DBC connection factory, R2DBC blocking timeout) must be added to both systems. Property resolution path is non-obvious for new contributors.
- **Fix approach:** New configuration additions should target the modern model first. Do not add R2DBC configuration to `ClassicConfiguration` directly; use a `ConfigurationExtension` (as done for `PostgreSQLConfigurationExtension`).

### Configuration Merge Logic is Incomplete

- **Issue:** Two TODO comments in `FlywayModel.java` (line 166) and `FlywayEnvironmentModel.java` (line 155) mark merge logic as needing "more granular merge" for `propertyResolvers`. Currently the last-wins strategy is used for the entire list rather than merging individual resolvers.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/models/FlywayModel.java` — line 166
  - `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/models/FlywayEnvironmentModel.java` — line 155
- **Impact:** Property resolvers defined in environment-specific config may be silently dropped when configs are merged. For R2DBC, any custom R2DBC URL resolver could be lost.
- **Fix approach:** Implement list-merge semantics (deduplicate by resolver name/type, allow overrides).

### `DatabaseTypeRegister` Uses Static Initialization with `PluginRegister`

- **Issue:** `SORTED_DATABASE_TYPES` is a `private static final` list populated at class-load time by instantiating a fresh `PluginRegister`. This means database types are resolved once at JVM startup regardless of the classpath at that moment.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/DatabaseTypeRegister.java` — lines 42–45
- **Impact:** In OSGi environments or dynamically constructed classpaths (common in Spring Boot layered JARs), R2DBC database plugins may not be discovered if they are loaded after the static initializer runs. Hot-reload / test isolation is also affected.
- **Fix approach:** Lazy-initialize or allow refreshing the registry. Alternatively, pass a `ClassLoader` or `PluginRegister` instance at call sites rather than using the static field.

### Deprecated Methods Left in Public `DatabaseType` Interface

- **Issue:** `DatabaseType.detectUserRequiredByUrl(String url)` and `detectPasswordRequiredByUrl(String url)` are `@Deprecated` but still present in the interface with no replacement documented.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/DatabaseType.java` — lines 230–241
- **Impact:** All database implementations must still implement these deprecated methods. Any new R2DBC `DatabaseType`-equivalent interface inherits this problem.
- **Fix approach:** Remove the deprecated methods and replace with `externalAuthPropertiesRequired(String url, String username, String password)` which is already present.

---

## Native Connectors (NC) Architecture — Fragile Areas

### NC Mode is Controlled via Environment Variable Only

- **Issue:** The only way to enable Native Connectors mode is via the `FLYWAY_NATIVE_CONNECTORS=true` environment variable (checked in `NativeConnectorsModeUtils.isNativeConnectorsTurnedOn()`). There is no programmatic API to activate it from Java configuration or `FluentConfiguration`.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/nc/NativeConnectorsModeUtils.java` — lines 46–52
- **Impact:** Users cannot enable R2DBC mode (which will use NC) from code. Spring Boot auto-configuration will not be able to activate it programmatically. This must be resolved before shipping.
- **Fix approach:** Add a `useNativeConnectors(boolean)` method to `FluentConfiguration`, stored in `ClassicConfiguration` and read by `NativeConnectorsModeUtils`.

### NC Support Check Uses Plugin Lookup with Null-Safe Default

- **Issue:** `NativeConnectorsModeUtils.canCreateDataSource()` returns `true` when `NativeConnectorsSupport` plugin is absent (null check falls through). This means if the NC support plugin is not on the classpath, Flyway silently falls through to JDBC. If only an R2DBC driver is present (no JDBC driver), this can cause confusing failures later.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/nc/NativeConnectorsModeUtils.java` — lines 37–44
- **Impact:** Misleading error messages for users who configure only R2DBC without the NC support plugin.
- **Fix approach:** When the URL is detected as R2DBC (`r2dbc:` prefix), fail fast with a clear error if the NC support plugin is not present.

### NC Verb Modules Are Separate from Core Command Implementations

- **Issue:** `flyway-nc/` contains separate verb implementations for NC databases (migrate, validate, clean, etc. under `flyway-verb-migrate`, `flyway-verb-validate`, etc.). These are parallel implementations to the core `DbMigrate`, `DbValidate` etc. in `flyway-core/src/main/java/org/flywaydb/core/internal/command/`. The two code paths must be kept in sync as core evolves.
- **Files:**
  - `flyway-nc/flyway-verb-migrate/`
  - `flyway-nc/flyway-verb-validate/`
  - `flyway-nc/flyway-verb-clean/`
  - `flyway-core/src/main/java/org/flywaydb/core/internal/command/DbMigrate.java`
- **Impact:** Features added to core `DbMigrate` (e.g., new callback events, new group logic) may not be reflected in the NC migrate verb. R2DBC will use the NC path, so any divergence here means R2DBC and JDBC behave differently.
- **Fix approach:** Audit feature parity between NC verb modules and core command modules before shipping R2DBC support. Consider refactoring shared logic into a base class usable by both.

---

## Locking Mechanisms — Synchronous Assumptions

### Pessimistic Table Lock via `SELECT ... FOR UPDATE`

- **Issue:** PostgreSQL uses `SELECT * FROM <history_table> FOR UPDATE` as its row-locking mechanism inside a transaction (see `PostgreSQLTable.doLock()`). MySQL uses `GET_LOCK()` via `MySQLNamedLockTemplate` with `Thread.sleep(100)` retry. Oracle/DB2 have their own blocking lock patterns. All are blocking JDBC operations.
- **Files:**
  - `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLTable.java` — `doLock()` uses `SELECT ... FOR UPDATE`
  - `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLAdvisoryLockTemplate.java` — advisory lock with `RetryStrategy` (blocking `Thread.sleep`)
  - `flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLNamedLockTemplate.java` — line 85, `Thread.sleep(100L)`
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/InsertRowLock.java` — background thread refreshing the lock row every 5 minutes
- **Impact:** None of these locking strategies can be directly reused by R2DBC implementations. Each database-specific R2DBC plugin must implement its own non-blocking distributed lock. For PostgreSQL R2DBC, `pg_advisory_lock` can be issued as a regular R2DBC statement wrapped in a flat-map chain.
- **Fix approach:** R2DBC NC plugins must implement the `NativeConnectorsDatabase` interface with their own schema history locking that does not use `Thread.sleep` or JDBC statements.

### `TransactionalExecutionTemplate` Blocks on Autocommit and Commit

- **Issue:** `TransactionalExecutionTemplate.execute()` directly calls `connection.setAutoCommit(false)`, `connection.commit()`, and `connection.rollback()` — all blocking JDBC calls. The entire schema history write path passes through this.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/jdbc/TransactionalExecutionTemplate.java`
  - `flyway-core/src/main/java/org/flywaydb/core/internal/jdbc/ExecutionTemplateFactory.java`
- **Impact:** No R2DBC equivalent exists. R2DBC transactions are managed via `io.r2dbc.spi.Connection.beginTransaction()` which returns a `Publisher<Void>`. The R2DBC NC path must implement its own transaction management separate from `ExecutionTemplateFactory`.
- **Fix approach:** The NC verb modules must manage R2DBC transactions directly using the R2DBC connection API. Do not attempt to route through `ExecutionTemplateFactory`.

---

## Schema Management Complexity

### Schema Abstraction Has Inconsistent `getAllSchemas()` Support

- **Issue:** `Database.getAllSchemas()` throws `UnsupportedOperationException` by default (line 502). Not all database implementations override this. During the `clean` command, when `cleanSchemas` iterates all schemas, databases that do not support `getAllSchemas()` will throw at runtime.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Database.java` — line 502
- **Impact:** R2DBC database plugins extending `AbstractNativeConnectorsDatabase` must implement `isSchemaEmpty()`, `isSchemaExists()`, and `createSchemas()` (all required by `NativeConnectorsDatabase`), but `getAllSchemas()` remains in the JDBC layer. Any R2DBC-backed clean operation must provide its own enumeration.
- **Fix approach:** Add a `getSchemas()` or `listSchemas()` method to `NativeConnectorsDatabase` and document it as required for clean support.

### `Schema.allTypes()` Calls `DatabaseMetaData.getUDTs()` Directly

- **Issue:** `Schema.allTypes()` directly uses `database.jdbcMetaData.getUDTs(...)` — a `DatabaseMetaData` call. This is on the core abstract `Schema` class shared by all database implementations.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Schema.java` — lines 160–174
- **Impact:** R2DBC has no `DatabaseMetaData` equivalent. This method cannot be called from an R2DBC context. NC implementations of the clean operation must take a different code path for UDT discovery.
- **Fix approach:** Override at the NC level to query information schema tables directly via R2DBC statements if UDT cleanup is needed.

---

## Parser Module Dependencies

### Parser is `java.io.Reader`-based (Synchronous I/O)

- **Issue:** The core `Parser` in `flyway-core/src/main/java/org/flywaydb/core/internal/parser/Parser.java` processes SQL scripts using a chain of `java.io.Reader` wrappers: `BomStrippingReader` → `UnboundedReadAheadReader` → `BufferedReader`. Resource reading uses `LoadableResource.read()` which returns a `java.io.Reader`.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/parser/Parser.java` — `parse()` method, line 112
  - `flyway-core/src/main/java/org/flywaydb/core/internal/parser/UnboundedReadAheadReader.java`
  - `flyway-core/src/main/java/org/flywaydb/core/internal/parser/PeekingReader.java` — 473 lines
- **Impact:** Parser is not async and cannot produce a reactive stream of `SqlStatement`. However, because SQL files are local classpath/filesystem resources (not network I/O), this is acceptable. The parser output (SQL statements) is still blocking String processing, which is fine for CPU-bound work.
- **Recommendation:** R2DBC NC can call the existing parser synchronously on a worker thread (e.g., Reactor's `boundedElastic` scheduler) to produce SQL strings, then execute them reactively. No parser changes required.

### Database-Specific Parsers Are `final` Parse Chains

- **Issue:** Each database parser (e.g., `PostgreSQLParser`, `H2Parser`, `OracleParser`) extends the abstract `Parser`. The Oracle parser alone is 703 lines. These parsers are tightly coupled to their database modules.
- **Files:**
  - `flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLParser.java`
  - `flyway-database/flyway-database-oracle/src/main/java/org/flywaydb/database/oracle/OracleParser.java` — 703 lines
  - `flyway-core/src/main/java/org/flywaydb/core/internal/database/h2/H2Parser.java`
- **Impact:** R2DBC database modules (e.g., `flyway-r2dbc-postgresql`) need to reuse the PostgreSQL parser without depending on the full `flyway-database-postgresql` module (which brings in JDBC PostgreSQL driver dependency). This creates a dependency problem: parser modules should be separated from JDBC-specific modules.
- **Fix approach:** Consider factoring each parser into a separate `flyway-parser-postgresql` module (similar to how the existing planning doc `docs/planning/R2DBC_SUPPORT_PLANNING.md` ADR-003 proposes). R2DBC modules then depend only on parser modules, not the JDBC database modules.

---

## Spring Boot Integration Patterns

### No Existing Spring Boot Auto-Configuration for R2DBC

- **Issue:** There is no Spring Boot auto-configuration (`@ConditionalOnClass`, `@Bean` definitions) in this repository for R2DBC. The planning document at `docs/planning/R2DBC_SPRING_BOOT_INTEGRATION.md` describes a desired `FlywayR2dbcAutoConfiguration` but it does not exist in the codebase. No `flyway-spring-boot-r2dbc-starter` module exists.
- **Files (absent):** No `FlywayR2dbcAutoConfiguration.java`, no `spring.factories` / `AutoConfiguration.imports` for R2DBC
- **Impact:** Spring Boot WebFlux users cannot use Flyway without custom wiring. The path to integration is entirely manual.
- **Fix approach:** Create a new `flyway-spring-boot-r2dbc-starter` module with a `FlywayR2dbcAutoConfiguration` that detects `ConnectionFactory` on the classpath and conditionally configures Flyway.

### Existing Spring Boot Integration (JDBC) is External

- **Issue:** The Flyway Spring Boot auto-configuration exists in the Spring Boot project (`spring-boot-autoconfigure`) as `FlywayAutoConfiguration`, not in this repository. The Flyway project itself has no Spring dependency in core. Changes to Flyway's R2DBC API will need to be reflected in Spring Boot's auto-configuration by upstream contributors.
- **Impact:** The R2DBC Spring Boot integration timeline depends on both this project and the Spring Boot project. Coordination is required.
- **Fix approach:** Provide a clear SPI (well-documented `ConnectionFactory`-accepting constructor or builder method on `Flyway`/`FluentConfiguration`) that Spring Boot can bind to. Document the integration contract early.

---

## Known Bugs / Gotchas

### Schema History Table Creation Race with `Thread.sleep` Can Reach 10 Retries and Throw

- **Issue:** In `JdbcTableSchemaHistory.create()`, if the table creation keeps failing (e.g., due to a persistent race or lack of DDL permissions), after 10 retries it throws a `FlywayException`. However, the error message says "Retrying in 1 sec..." but the actual exception message is not user-friendly.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/schemahistory/JdbcTableSchemaHistory.java` — lines 128–141
- **Impact:** Confusing error messages in concurrent deployment scenarios.

### `DriverDataSource` Is Not a Real Connection Pool

- **Issue:** `DriverDataSource` (`flyway-core/src/main/java/org/flywaydb/core/internal/jdbc/DriverDataSource.java`) explicitly comments itself as "YAGNI: The simplest DataSource implementation that works for Flyway" (line 48). It creates a new physical connection on every `getConnection()` call without pooling.
- **Impact:** When Flyway creates multiple connections (main, migration, event connections) during a migrate operation, it opens 2–3 physical connections against the database sequentially. For databases with slow connection establishment (e.g., cloud-hosted Postgres with TLS), this adds measurable latency. Users relying on Flyway without an external connection pool suffer this.
- **Recommendation:** Document clearly that users should provide an external pooled `DataSource` for production use, not rely on `DriverDataSource`.

### `DbMigrate.doMigrateGroup()` Context Exposes Raw JDBC Connection to User Migrations

- **Issue:** The `Context` passed to `migration.getResolvedMigration().getExecutor().execute(context)` in `DbMigrate.doMigrateGroup()` (line 391) provides `connectionUserObjects.getJdbcConnection()` as the raw unwrapped `java.sql.Connection`. Users can call arbitrary JDBC operations (including setting autoCommit, manual commits) that can corrupt Flyway's transaction management.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/command/DbMigrate.java` — lines 341–351
- **Impact:** User migrations that accidentally commit or change autoCommit can cause schema history inconsistencies. No guardrails exist.

---

## Test Coverage Gaps

### No Tests for Concurrent Schema History Table Creation

- **What's not tested:** The race condition retry loop in `JdbcTableSchemaHistory.create()` (10 retries with 1-second sleeps) has no test exercising the concurrent case.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/schemahistory/JdbcTableSchemaHistory.java`
- **Risk:** Deadlock or data corruption when two Flyway instances start simultaneously against an empty database.
- **Priority:** Medium

### No Tests Covering NC (Native Connectors) Mode with JDBC Hybrid

- **What's not tested:** `AbstractNativeConnectorsHybridDatabase` (used by MongoDB, Couchbase) and the NC-JDBC bridge path `NativeConnectorsJdbc` have no unit tests visible in this codebase.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/nc/AbstractNativeConnectorsHybridDatabase.java`
  - `flyway-nc/flyway-nc-core/src/main/java/org/flywaydb/nc/NativeConnectorsJdbc.java`
- **Risk:** R2DBC will follow the NC code path; inadequate NC test coverage means R2DBC behaviour will be undiscovered until integration testing.
- **Priority:** High

### `RetryStrategy` Static Mutable State is Not Isolated in Tests

- **What's not tested:** No test verifies that `RetryStrategy.setNumberOfRetries()` does not leak across test cases. Because it is a `static` field, parallel test execution can produce flaky results.
- **Files:**
  - `flyway-core/src/main/java/org/flywaydb/core/internal/strategy/RetryStrategy.java`
- **Risk:** Test suite flakiness in parallel CI runs.
- **Priority:** Low

---

*Concerns audit: 2026-02-27*
