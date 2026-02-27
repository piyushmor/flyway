# Architecture

**Analysis Date:** 2026-02-27

## Pattern Overview

**Overall:** Plugin-based layered architecture with dual execution paths (Legacy JDBC and Native Connectors)

**Key Characteristics:**
- All extensibility uses `Plugin` interface + Java `ServiceLoader` for runtime discovery
- Two parallel migration execution paths: Legacy (JDBC-centric) and Native Connectors (NC, preview)
- The `Flyway` public API dispatches to either path based on `FLYWAY_NATIVE_CONNECTORS` env var and plugin availability
- Commands/verbs are first-class plugins - each verb (migrate, info, clean, etc.) is a separate `VerbExtension` plugin registered via `META-INF/services`
- Priority-based plugin ordering: `Plugin.getPriority()` determines which plugin wins when multiple match

## Layers

**Public API Layer:**
- Purpose: User-facing entry point for all Flyway operations
- Location: `flyway-core/src/main/java/org/flywaydb/core/` and `org/flywaydb/core/api/`
- Contains: `Flyway`, `FlywayExecutor`, `Configuration` interface, output DTOs
- Depends on: Core internal layer
- Used by: CLI, Maven plugin, Gradle plugin, Spring Boot autoconfigure, programmatic API users

**Core Internal Layer:**
- Purpose: Legacy JDBC-based migration execution engine
- Location: `flyway-core/src/main/java/org/flywaydb/core/internal/`
- Contains: `DbMigrate`, `DbInfo`, `DbClean`, `DbValidate`, `DbRepair`, `DbBaseline`, `SchemaHistory`, `CompositeMigrationResolver`
- Depends on: Database abstraction layer, JDBC utilities
- Used by: `FlywayExecutor.Command` functional interface

**Database Abstraction Layer (Legacy):**
- Purpose: Database-specific SQL, connection, and schema operations for legacy JDBC path
- Location: `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/`
- Contains: Abstract `Database<C>`, `Connection<D>`, `Schema<D,T>`, `Table`, `Function`, `SchemaObject`
- Depends on: JDBC (`java.sql`), `JdbcTemplate`
- Used by: Core internal command layer

**Database Adapter Modules (Legacy):**
- Purpose: Concrete per-database implementations of `Database`, `Connection`, `Schema`, `Table`, `DatabaseType`, `Parser`
- Location: `flyway-database/flyway-{dbname}/` (e.g., `flyway-database-postgresql/`, `flyway-mysql/`, `flyway-sqlserver/`)
- Contains: e.g., `PostgreSQLDatabase`, `PostgreSQLDatabaseType`, `MySQLDatabase`, `SQLServerDatabase`
- Registered via: `META-INF/services/org.flywaydb.core.extensibility.Plugin`
- Depends on: `flyway-core` database base classes
- Used by: `DatabaseTypeRegister` via `PluginRegister`

**Native Connectors (NC) Interface Layer:**
- Purpose: Defines contracts for the new NC execution path, keeping NC decoupled from core
- Location: `flyway-core/src/main/java/org/flywaydb/core/internal/nc/`
- Contains: `NativeConnectorsDatabase<T>` sealed interface, `AbstractNativeConnectorsDatabase<T>`, `AbstractNativeConnectorsHybridDatabase<T>`, `Executor<T,DB>`, `Reader`, `ConnectionType` enum, `NativeConnectorsSupport`, `SchemaHistoryModel`, `SchemaHistoryItem`, `MetaData`, `DatabaseVersion`
- Depends on: Core API only
- Used by: NC implementation modules

**Native Connectors Core Implementation:**
- Purpose: Concrete NC base classes and orchestration
- Location: `flyway-nc/flyway-nc-core/src/main/java/org/flywaydb/nc/`
- Contains: `NativeConnectorsJdbc` (JDBC-based NC), `NativeConnectorsNonJdbc` (non-JDBC NC), `NativeConnectorsHybrid<T,U,V>`, `NativeConnectorsSupportImpl`, `NativeConnectorsDatabasePluginResolverImpl`, `PreparationContext`, executors (`JdbcExecutor`, `ApiExecutor`, `ExecutableExecutor`), readers (`JdbcReader`, `NonJdbcReader`), scanners
- Depends on: NC interface layer, core API
- Used by: NC verb extensions, NC database connectors

**NC Verb Extensions:**
- Purpose: Implement `VerbExtension` for each command in the NC path
- Location: `flyway-nc/flyway-verb-{verb}/` (migrate, info, validate, clean, repair, baseline, schemas, testConnection)
- Contains: e.g., `MigrateVerbExtension`, `InfoVerbExtension`, `ValidateVerbExtension`
- Registered via: `META-INF/services/org.flywaydb.core.extensibility.Plugin`
- Depends on: `flyway-nc-core`, core API
- Used by: `Flyway` public API (dispatched when NC is active)

**NC Database Connectors:**
- Purpose: Concrete NC implementations for specific databases (both JDBC and non-JDBC)
- Location: `flyway-database/flyway-database-nc-{dbname}/` and `flyway-nc/flyway-experimental-{dbname}/`
- Contains: `MongoDBDatabase` (extends `NativeConnectorsNonJdbc`), `CouchbaseDatabase` (extends `NativeConnectorsNonJdbc`), `ExperimentalSqlite` (extends `NativeConnectorsJdbc`)
- Registered via: `META-INF/services/org.flywaydb.core.extensibility.Plugin`
- Depends on: `flyway-nc-core` base classes, database-specific SDKs
- Used by: NC path via URL-based `NativeConnectorsDatabasePluginResolverImpl`

**Plugin System:**
- Purpose: Dynamic discovery and management of all extensible components
- Location: `flyway-core/src/main/java/org/flywaydb/core/internal/plugin/PluginRegister.java`
- Contains: `PluginRegister` (loads via `ServiceLoader<Plugin>`), priority-based selection
- Depends on: Java `ServiceLoader`
- Used by: `Configuration` (exposes `getPluginRegister()`), all layers

## Data Flow

**Legacy Migration Flow (JDBC path):**

1. `Flyway.migrate()` calls `NativeConnectorsModeUtils.canUseNativeConnectors()` - returns false
2. `FlywayExecutor.execute(Command)` establishes `JdbcConnectionFactory`, creates `Database` via `DatabaseTypeRegister`
3. `SchemaHistoryFactory` creates `JdbcTableSchemaHistory` backed by the schema history table
4. `CompositeMigrationResolver` scans locations for SQL scripts (`SqlMigrationResolver`) and Java classes (`ScanningJavaMigrationResolver`)
5. `DbMigrate.migrate()` compares resolved migrations vs applied migrations from `SchemaHistory`
6. For each pending migration: `CallbackExecutor` fires `BEFORE_EACH_MIGRATE`, then `MigrationExecutor.execute(Context)` runs the SQL or Java migration
7. `SchemaHistory` records the result (success or failure) in the schema history table

**Native Connectors Migration Flow:**

1. `Flyway.migrate()` calls `NativeConnectorsModeUtils.canUseNativeConnectors()` - returns true
2. `VerbExtension` for "migrate" is found via `PluginRegister` (registered as `MigrateVerbExtension`)
3. `MigrateVerbExtension.executeVerb()` calls `PreparationContext.get()` which:
   - Calls `VerbUtils.getExperimentalDatabase()` → `NativeConnectorsDatabasePluginResolverImpl.resolve(url)` to find the right `NativeConnectorsDatabase` by URL match
   - Scans migration locations via `MigrationScannerManager` (using `FileSystemSqlMigrationScanner` / `ClasspathSqlMigrationScanner`)
   - Loads `SchemaHistoryModel` from the database
   - Computes `MigrationInfo[]` by comparing resources vs schema history
4. `MigratorFactory.getMigrator(database)` selects `JdbcMigrator`, `ApiMigrator`, `ExecutableMigrator`, or `HybridMigrator` based on `ConnectionType`
5. Migrator creates `MigrationExecutionGroup` list (respecting transaction grouping)
6. For each group: `CallbackManager` fires `BEFORE_EACH_MIGRATE`, `Executor.execute()` runs the statements, schema history is updated

**NC Database Resolution:**

1. All `NativeConnectorsDatabase` implementations registered in `META-INF/services`
2. `NativeConnectorsDatabasePluginResolverImpl.resolve(url)` calls `supportsUrl(url)` on each
3. Returns `DatabaseSupport(isSupported, priority)` - highest priority wins when multiple match
4. Selected database is initialized via `initialize(environment, configuration)`

**State Management:**
- `PreparationContext` is a singleton `Plugin` in the `PluginRegister` - serves as a per-operation cache for the NC path
- `SchemaHistoryModel` is an in-memory snapshot of the schema history table
- `Configuration` is immutable - changes create new `Flyway` instances

## Key Abstractions

**`Plugin` Interface:**
- Purpose: Marker interface for all extensible components
- Location: `flyway-core/src/main/java/org/flywaydb/core/extensibility/Plugin.java`
- Pattern: `ServiceLoader`-based registry; `getPriority()` for conflict resolution; `isLicensed()` for tier-gating

**`NativeConnectorsDatabase<T>` Sealed Interface:**
- Purpose: Core contract for NC database implementations; `T` is the execution unit type (e.g., `String` for JDBC SQL, `NonJdbcExecutorExecutionUnit` for API-based)
- Location: `flyway-core/src/main/java/org/flywaydb/core/internal/nc/NativeConnectorsDatabase.java`
- Pattern: Sealed with `permits AbstractNativeConnectorsDatabase, AbstractNativeConnectorsHybridDatabase`; operations include `initialize`, `doExecute`, `getSchemaHistoryModel`, schema management

**`DatabaseType` Interface (Legacy):**
- Purpose: Factory interface for creating `Database`, `Connection`, `Parser`, `SqlScriptFactory` for a given JDBC URL
- Location: `flyway-core/src/main/java/org/flywaydb/core/internal/database/DatabaseType.java`
- Pattern: Plugin; `handlesDatabaseProductNameAndVersion()` for URL/metadata matching

**`VerbExtension` Interface:**
- Purpose: NC command handler; replaces the `Command` functional interface when NC is active
- Location: `flyway-core/src/main/java/org/flywaydb/core/extensibility/VerbExtension.java`
- Pattern: `handlesVerb(String)` for dispatch; `executeVerb(Configuration)` returns typed result

**`SchemaHistory` (Legacy) / `SchemaHistoryModel` (NC):**
- Purpose: Tracks all applied migrations in the database's schema history table
- Legacy: `flyway-core/src/main/java/org/flywaydb/core/internal/schemahistory/SchemaHistory.java` - uses `JdbcTemplate` directly
- NC: `flyway-core/src/main/java/org/flywaydb/core/internal/nc/schemahistory/SchemaHistoryModel.java` - in-memory model populated by `NativeConnectorsDatabase.getSchemaHistoryModel()`

**`Configuration` Interface:**
- Purpose: Immutable bag of all Flyway settings, exposes `PluginRegister`
- Location: `flyway-core/src/main/java/org/flywaydb/core/api/configuration/Configuration.java`
- Implementations: `ClassicConfiguration` (mutable builder), `FluentConfiguration` (DSL wrapper)

**`Executor<T, DB>` Interface (NC):**
- Purpose: Dispatches execution units to the database; selected by `ConnectionType`
- Location: `flyway-core/src/main/java/org/flywaydb/core/internal/nc/Executor.java`
- Implementations: `JdbcExecutor`, `ApiExecutor`, `ExecutableExecutor` (registered as plugins)

## Entry Points

**Java API:**
- Location: `flyway-core/src/main/java/org/flywaydb/core/Flyway.java`
- Triggers: Direct instantiation via `Flyway.configure().dataSource(...).load()` then `flyway.migrate()`
- Responsibilities: Dispatches to NC path or legacy `FlywayExecutor`; telemetry wrapping

**CLI:**
- Location: `flyway-commandline/src/main/java/org/flywaydb/commandline/Main.java`
- Triggers: `java -jar flyway.jar migrate` (or native binary)
- Responsibilities: Parses CLI args via `CommandLineArguments`, loads config via `ConfigurationManagerImpl`, calls `Flyway` API or `CommandExtension` for proprietary commands

**Maven Plugin:**
- Location: `flyway-plugins/flyway-maven-plugin/src/main/java/org/flywaydb/maven/`
- Entry: `AbstractFlywayMojo` with concrete mojos (`MigrateMojo`, `CleanMojo`, etc.)
- Triggers: `mvn flyway:migrate`

**Gradle Plugin:**
- Location: `flyway-plugins/flyway-gradle-plugin/`
- Triggers: Gradle task execution

**Spring Boot:**
- Not present in this open-source repo; Spring Boot autoconfiguration is handled by `spring-boot-autoconfigure` (external). Flyway integrates via the standard `Flyway` Java API accepting a `DataSource`.

## Error Handling

**Strategy:** Exception-based; `FlywayException` is the unchecked root exception

**Patterns:**
- SQL errors wrapped in `FlywaySqlException` (extends `FlywayException`) with connection details
- Migration failures throw `FlywayMigrateException` with the failed migration's info
- Validation failures throw `FlywayValidateException`
- License violations throw `FlywayInvalidLicenseKeyException` / `FlywayExpiredLicenseKeyException`
- NC path wraps `SQLException` directly into `FlywayException`

## Cross-Cutting Concerns

**Logging:** Custom `Log` interface via `LogFactory`; adapters for console, file, SLF4J, Log4j. CLI uses buffered log that replays once log level is known. NC path uses `@CustomLog` Lombok annotation.

**Validation:** `ConfigurationValidator` validates at startup in both paths; `ResourceNameValidator` checks migration file naming conventions.

**Authentication/Secrets:** `SecretsManagerConfigurationExtension` SPI + `PropertyResolver` implementations (env vars, Vault, GCP Secret Manager, Dapr) resolve credentials at config time.

**Telemetry:** `FlywayTelemetryManager` (optional plugin); `EventTelemetryModel` / `MigrateTelemetryModel` capture per-operation telemetry. `TelemetrySpan` wraps operations.

**Licensing:** `LicenseGuard.isLicensed(configuration, tiers)` gates features to `COMMUNITY`, `TEAMS`, or `ENTERPRISE` tiers. Proprietary features use stub classes in open-source builds (e.g., `CheckCommandExtensionStub`).

---

*Architecture analysis: 2026-02-27*
