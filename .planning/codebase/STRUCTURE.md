# Codebase Structure

**Analysis Date:** 2026-02-27

## Directory Layout

```
flyway/                              # Root Maven multi-module project
├── flyway-core/                     # Core engine, public API, legacy JDBC path
├── flyway-nc/                       # Native Connectors execution path (preview)
│   ├── flyway-nc-core/              # NC base classes, orchestration, PreparationContext
│   ├── flyway-nc-callbacks/         # NC callback handler
│   ├── flyway-nc-scanners/          # Migration file scanners for NC path
│   ├── flyway-experimental-sqlite/  # SQLite NC connector (JDBC-based)
│   ├── flyway-verb-migrate/         # NC migrate command
│   ├── flyway-verb-info/            # NC info command
│   ├── flyway-verb-validate/        # NC validate command
│   ├── flyway-verb-clean/           # NC clean command
│   ├── flyway-verb-repair/          # NC repair command
│   ├── flyway-verb-baseline/        # NC baseline command
│   ├── flyway-verb-schemas/         # NC schemas command
│   └── flyway-verb-testConnection/  # NC testConnection command
├── flyway-database/                 # Per-database JDBC adapters and NC connectors
│   ├── flyway-database-postgresql/  # PostgreSQL + CockroachDB (legacy JDBC)
│   ├── flyway-mysql/                # MySQL + MariaDB (legacy JDBC)
│   ├── flyway-sqlserver/            # SQL Server + Synapse + Babelfish (legacy JDBC)
│   ├── flyway-database-oracle/      # Oracle (legacy JDBC)
│   ├── flyway-database-db2/         # IBM DB2 (legacy JDBC)
│   ├── flyway-database-redshift/    # Amazon Redshift (legacy JDBC)
│   ├── flyway-gcp-bigquery/         # GCP BigQuery (legacy JDBC)
│   ├── flyway-gcp-spanner/          # GCP Spanner (legacy JDBC)
│   ├── flyway-database-snowflake/   # Snowflake (legacy JDBC)
│   ├── flyway-database-cassandra/   # Cassandra (legacy JDBC)
│   ├── flyway-database-nc-mongodb/  # MongoDB NC connector (non-JDBC)
│   ├── flyway-database-nc-couchbase/# Couchbase NC connector (non-JDBC)
│   ├── flyway-database-sybasease/   # Sybase ASE (legacy JDBC)
│   ├── flyway-database-saphana/     # SAP HANA (legacy JDBC)
│   ├── flyway-database-hsqldb/      # HSQLDB (legacy JDBC)
│   ├── flyway-database-derby/       # Apache Derby (legacy JDBC)
│   ├── flyway-database-informix/    # Informix (legacy JDBC)
│   ├── flyway-firebird/             # Firebird (legacy JDBC)
│   └── flyway-singlestore/          # SingleStore (legacy JDBC)
├── flyway-commandline/              # CLI entry point and configuration management
├── flyway-plugins/                  # Build tool plugins
│   ├── flyway-maven-plugin/         # Maven plugin mojos
│   └── flyway-gradle-plugin/        # Gradle plugin tasks
├── flyway-locations/                # Extra migration source location handlers
│   └── flyway-locations-s3/         # AWS S3 location handler
├── flyway-reports/                  # HTML/JSON report generation
├── flyway-command/                  # Additional command extensions
│   └── flyway-command-test-connection/
├── flyway-shades/                   # Shaded/repackaged dependencies
├── docs/                            # Developer documentation (markdown)
│   └── planning/                    # Planning documents
├── documentation/                   # User-facing documentation
└── pom.xml                          # Root POM, version: 12.0.2
```

## Directory Purposes

**`flyway-core/`:**
- Purpose: The foundation. Contains the public API, plugin system, legacy JDBC execution engine, and all NC interfaces
- Key packages:
  - `org.flywaydb.core` — `Flyway`, `FlywayExecutor`, `ProgressLogger`
  - `org.flywaydb.core.api` — `Configuration`, `MigrationInfo`, `MigrationVersion`, `MigrationState`, output DTOs, callback API
  - `org.flywaydb.core.api.configuration` — `ClassicConfiguration`, `FluentConfiguration`
  - `org.flywaydb.core.extensibility` — `Plugin`, `VerbExtension`, `CommandExtension`, `ConfigurationExtension`, `MigrationType`, licensing
  - `org.flywaydb.core.internal.command` — `DbMigrate`, `DbInfo`, `DbValidate`, `DbClean`, `DbRepair`, `DbBaseline`
  - `org.flywaydb.core.internal.database.base` — `Database<C>`, `Connection<D>`, `Schema<D,T>`, `Table`, `DatabaseType`
  - `org.flywaydb.core.internal.database.h2` — H2 built-in adapter
  - `org.flywaydb.core.internal.database.sqlite` — SQLite built-in adapter
  - `org.flywaydb.core.internal.nc` — NC interfaces: `NativeConnectorsDatabase<T>`, `AbstractNativeConnectorsDatabase`, `Executor`, `Reader`, `ConnectionType`, schema history model
  - `org.flywaydb.core.internal.plugin` — `PluginRegister`
  - `org.flywaydb.core.internal.resolver` — `CompositeMigrationResolver`, SQL/Java/script resolvers
  - `org.flywaydb.core.internal.schemahistory` — `SchemaHistory`, `JdbcTableSchemaHistory`
  - `org.flywaydb.core.internal.scanner` — Classpath and filesystem migration scanners
  - `org.flywaydb.core.internal.parser` — `Parser` abstract class for SQL dialect parsing
  - `org.flywaydb.core.internal.sqlscript` — `SqlScript`, `SqlStatement`, `SqlScriptFactory`
  - `org.flywaydb.core.internal.configuration` — `ConfigUtils`, TOML config, `ResolvedEnvironment`, `PropertyResolver`
  - `org.flywaydb.core.internal.proprietaryStubs` — Open-source stubs for proprietary features

**`flyway-nc/flyway-nc-core/`:**
- Purpose: NC path implementation classes that sit above the NC interfaces in `flyway-core`
- Key files:
  - `org.flywaydb.nc.NativeConnectorsJdbc` — Abstract base for JDBC-based NC databases
  - `org.flywaydb.nc.NativeConnectorsNonJdbc` — Abstract base for API/native protocol NC databases
  - `org.flywaydb.nc.NativeConnectorsHybrid<T,U,V>` — For databases needing both JDBC and non-JDBC connections
  - `org.flywaydb.nc.NativeConnectorsSupportImpl` — `NativeConnectorsSupport` implementation, gateway to NC path
  - `org.flywaydb.nc.NativeConnectorsDatabasePluginResolverImpl` — URL-based NC database selection
  - `org.flywaydb.nc.preparation.PreparationContext` — Per-operation initialization context (cached in `PluginRegister`)
  - `org.flywaydb.nc.executors.ExecutorFactory` — Selects `Executor` by `ConnectionType`
  - `org.flywaydb.nc.executors.JdbcExecutor`, `ApiExecutor`, `ExecutableExecutor` — Concrete executors
  - `org.flywaydb.nc.readers.JdbcReader`, `NonJdbcReader` — Parse migration files for each connection type
  - `org.flywaydb.nc.migration.CoreMigrationTypeResolver`, `BaselineMigrationTypeResolver` — Determine migration type from resource metadata

**`flyway-nc/flyway-verb-migrate/`:**
- Purpose: NC migrate command
- Key file: `org.flywaydb.verb.migrate.MigrateVerbExtension` — full migrate orchestration
- Sub-classes: `JdbcMigrator`, `ApiMigrator`, `ExecutableMigrator`, `HybridMigrator`, `MigratorFactory`

**`flyway-database/flyway-database-postgresql/`:**
- Purpose: PostgreSQL and CockroachDB legacy JDBC adapters
- Key files: `PostgreSQLDatabaseType`, `PostgreSQLDatabase`, `PostgreSQLConnection`, `PostgreSQLSchema`, `PostgreSQLParser`
- Registration: `src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`

**`flyway-database/flyway-database-nc-mongodb/`:**
- Purpose: MongoDB NC connector (non-JDBC, uses MongoDB Java driver)
- Key file: `org.flywaydb.database.nc.mongodb.MongoDBDatabase` extends `NativeConnectorsNonJdbc` (transitively)

**`flyway-database/flyway-database-nc-couchbase/`:**
- Purpose: Couchbase NC connector (non-JDBC, uses Couchbase Java SDK)
- Key file: `org.flywaydb.database.nc.couchbase.CouchbaseDatabase`

**`flyway-nc/flyway-experimental-sqlite/`:**
- Purpose: SQLite NC connector (JDBC-based, uses existing SQLite JDBC driver)
- Key file: `org.flywaydb.experimental.sqlite.ExperimentalSqlite` extends `NativeConnectorsJdbc`

**`flyway-commandline/`:**
- Purpose: CLI binary entry point and configuration infrastructure
- Key files:
  - `org.flywaydb.commandline.Main` — `main()` entry, command dispatch, JSON output
  - `org.flywaydb.commandline.configuration.CommandLineArguments` — CLI arg parsing
  - `org.flywaydb.commandline.configuration.ConfigurationManagerImpl` — Loads TOML/properties config
  - `org.flywaydb.commandline.configuration.ModernConfigurationManager` — TOML-based modern config
  - `org.flywaydb.commandline.configuration.LegacyConfigurationManager` — Legacy `.conf` format

**`flyway-plugins/flyway-maven-plugin/`:**
- Key files: `AbstractFlywayMojo` (base), per-command mojos in `org.flywaydb.maven.*`

**`flyway-locations/flyway-locations-s3/`:**
- Purpose: Enables `s3://bucket/path` migration locations
- Key file: `org.flywaydb.locations.s3.AwsS3LocationHandler`

**`flyway-reports/`:**
- Purpose: Generates HTML/JSON operation reports
- Key file: `org.flywaydb.core.internal.reports.ResultReportGenerator`

## Key File Locations

**Entry Points:**
- `flyway-core/src/main/java/org/flywaydb/core/Flyway.java`: Primary public API
- `flyway-commandline/src/main/java/org/flywaydb/commandline/Main.java`: CLI entry point
- `flyway-plugins/flyway-maven-plugin/src/main/java/org/flywaydb/maven/MigrateMojo.java`: Maven migrate goal

**Configuration:**
- `flyway-core/src/main/java/org/flywaydb/core/api/configuration/Configuration.java`: Interface
- `flyway-core/src/main/java/org/flywaydb/core/api/configuration/ClassicConfiguration.java`: Mutable implementation
- `flyway-core/src/main/java/org/flywaydb/core/api/configuration/FluentConfiguration.java`: Builder DSL
- `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/ConfigUtils.java`: Config utilities

**Core Interfaces:**
- `flyway-core/src/main/java/org/flywaydb/core/extensibility/Plugin.java`
- `flyway-core/src/main/java/org/flywaydb/core/extensibility/VerbExtension.java`
- `flyway-core/src/main/java/org/flywaydb/core/internal/nc/NativeConnectorsDatabase.java`
- `flyway-core/src/main/java/org/flywaydb/core/internal/database/DatabaseType.java`
- `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Database.java`
- `flyway-core/src/main/java/org/flywaydb/core/internal/nc/Executor.java`

**Plugin Registry:**
- `flyway-core/src/main/java/org/flywaydb/core/internal/plugin/PluginRegister.java`

**Schema History:**
- `flyway-core/src/main/java/org/flywaydb/core/internal/schemahistory/SchemaHistory.java` (legacy)
- `flyway-core/src/main/java/org/flywaydb/core/internal/nc/schemahistory/SchemaHistoryModel.java` (NC)
- `flyway-core/src/main/java/org/flywaydb/core/internal/nc/schemahistory/SchemaHistoryItem.java` (NC)

**Legacy Migration Execution:**
- `flyway-core/src/main/java/org/flywaydb/core/internal/command/DbMigrate.java`
- `flyway-core/src/main/java/org/flywaydb/core/internal/resolver/CompositeMigrationResolver.java`

**NC Migration Execution:**
- `flyway-nc/flyway-nc-core/src/main/java/org/flywaydb/nc/preparation/PreparationContext.java`
- `flyway-nc/flyway-verb-migrate/src/main/java/org/flywaydb/verb/migrate/MigrateVerbExtension.java`
- `flyway-nc/flyway-verb-migrate/src/main/java/org/flywaydb/verb/migrate/migrators/MigratorFactory.java`

**Testing:**
- `flyway-core/src/test/java/` and per-database module test directories

## Naming Conventions

**Files:**
- Legacy database adapters: `{DbName}Database.java`, `{DbName}DatabaseType.java`, `{DbName}Connection.java`, `{DbName}Schema.java`, `{DbName}Parser.java`
- NC database connectors: `{DbName}Database.java` in `org.flywaydb.database.nc.{dbname}` or `org.flywaydb.experimental.{dbname}`
- NC verb extensions: `{Verb}VerbExtension.java` in `org.flywaydb.verb.{verb}`
- Command classes (legacy): `Db{Verb}.java` in `org.flywaydb.core.internal.command`
- Output DTOs: `{Verb}Result.java`, `{Verb}Output.java` in `org.flywaydb.core.api.output`

**Packages:**
- `org.flywaydb.core.api.*` — public API (stable)
- `org.flywaydb.core.internal.*` — internal (subject to change)
- `org.flywaydb.core.extensibility.*` — extensibility contracts (semi-stable)
- `org.flywaydb.nc.*` — Native Connectors implementation
- `org.flywaydb.database.{dbname}.*` — legacy database adapters
- `org.flywaydb.database.nc.{dbname}.*` — NC non-JDBC database connectors
- `org.flywaydb.verb.{verb}.*` — NC verb extensions
- `org.flywaydb.scanners.*` — NC migration scanners

## Where to Add New Code

**New Legacy Database Adapter:**
- Create module: `flyway-database/flyway-database-{dbname}/`
- Implement: `{DbName}DatabaseType implements DatabaseType`, `{DbName}Database extends Database<{DbName}Connection>`, `{DbName}Connection extends Connection<{DbName}Database>`, `{DbName}Schema extends Schema<{DbName}Database, {DbName}Table>`, `{DbName}Parser extends Parser`
- Register: `src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`
- Add to: `flyway-database/pom.xml` modules

**New NC JDBC Connector:**
- Create module: `flyway-nc/flyway-experimental-{dbname}/` (preview) or `flyway-database/flyway-database-nc-{dbname}/` (stable)
- Extend: `NativeConnectorsJdbc` (for JDBC-accessible databases)
- Implement: `supportsUrl()`, `supportedVerbs()`, `handlesProductName()`, transaction methods, schema management
- Register: `src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`

**New NC Non-JDBC Connector:**
- Create module: `flyway-database/flyway-database-nc-{dbname}/`
- Extend: `NativeConnectorsNonJdbc` (for protocol-native connections like MongoDB)
- Implement: All `NativeConnectorsDatabase` methods using native SDK
- Register: `src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`

**New NC Verb:**
- Create module: `flyway-nc/flyway-verb-{verb}/`
- Implement: `VerbExtension`, `handlesVerb("{verb}")`, `executeVerb(Configuration)`
- Register: `src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`
- Add dispatch in `Flyway.java` if needed

**New Configuration Extension:**
- Implement: `ConfigurationExtension extends Plugin`
- Register: In relevant module's `META-INF/services/org.flywaydb.core.extensibility.Plugin`
- Access: `configuration.getPluginRegister().getExact(YourExtension.class)`

**New S3/Cloud Storage Location:**
- Pattern: `flyway-locations/flyway-locations-{provider}/`
- Implement: `LocationHandler` (classpath/filesystem equivalent for new storage)
- Register: `META-INF/services/org.flywaydb.core.extensibility.Plugin`

**New Migration Scanner (NC path):**
- Location: `flyway-nc/flyway-nc-scanners/src/main/java/org/flywaydb/scanners/`
- Extend: `BaseSqlMigrationScanner`
- Register: `META-INF/services/org.flywaydb.core.extensibility.Plugin`

## Special Directories

**`flyway-core/src/main/java/org/flywaydb/core/internal/proprietaryStubs/`:**
- Purpose: Open-source no-op stubs for proprietary-only features (check, undo, deploy, diff, model, generate, auth, prepare)
- Generated: No (hand-written stubs)
- Committed: Yes

**`flyway-shades/`:**
- Purpose: Produces shaded JAR with repackaged dependencies to avoid classpath conflicts in the CLI distribution
- Generated: Yes (by Maven shade plugin)
- Committed: Pom only, not the shaded jar

**`flyway-nc/flyway-experimental-sqlite/`:**
- Purpose: Reference implementation of a JDBC-based NC connector; marked as "experimental"
- Note: Demonstrates the minimal implementation surface for a `NativeConnectorsJdbc` subclass

**`flyway-core/src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`:**
- Purpose: ServiceLoader registration for all built-in plugins (database types H2/SQLite, core config extensions, stubs)
- Pattern to follow for every new plugin module

---

*Structure analysis: 2026-02-27*
