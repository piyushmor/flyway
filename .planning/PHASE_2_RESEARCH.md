# Phase 2: R2DBC Database Adapters - Research

**Researched:** 2026-02-27
**Domain:** Flyway Native Connectors R2DBC database-specific adapter implementation
**Confidence:** HIGH (all findings based on direct source inspection of this repo)

---

## Summary

Phase 2 implements three database-specific R2DBC adapters (`flyway-database-nc-r2dbc-postgresql`,
`flyway-database-nc-r2dbc-mysql`, `flyway-database-nc-r2dbc-h2`) that extend the `NativeConnectorsR2dbc`
abstract base class completed in Phase 1.

The research reveals that Phase 2 is largely mechanical, following the existing MongoDB and Couchbase
Native Connectors patterns precisely. The adapter structure is well-understood: each module is a small
Maven project in `flyway-database/`, registers one class in `META-INF/services`, extends
`NativeConnectorsR2dbc`, and provides a `createConnectionFactory()` implementation plus a
`getParser()` BiFunction that returns the existing JDBC parser from the corresponding JDBC module.

**Critical finding:** There is a **mandatory Phase 2 blocker** that was not in the Phase 1 scope:
`ConnectionType.R2DBC` has no registered `Executor<T, DB>` or `Reader<T>` in the plugin system.
The `ExecutorFactory.getExecutor()` uses `connectionType == ConnectionType.JDBC`, `API`, or
`EXECUTABLE` but NOT `R2DBC`. Without an `R2dbcExecutor` plugin registered in
`META-INF/services` of `flyway-nc-core` (or a new `flyway-r2dbc-core`), any R2DBC database adapter
will fail at runtime with "No executor found for connection type: R2DBC". This work belongs in Phase 2.

**Primary recommendation:** Implement adapters in this order: H2 (testing scaffold), PostgreSQL
(reference implementation), MySQL. Each adapter is ~150-200 lines. Concurrently implement
`R2dbcExecutor` plugin and `R2dbcReader` plugin in `flyway-r2dbc-core` — these are prerequisites
for any adapter to function.

---

## 1. Module Structure Pattern

### Existing JDBC Adapter Structure

```
flyway-database/flyway-database-postgresql/
├── pom.xml                                     # depends on flyway-core only + lombok
├── src/main/java/org/flywaydb/database/
│   ├── postgresql/
│   │   ├── PostgreSQLDatabase.java             # extends Database<PostgreSQLConnection>
│   │   ├── PostgreSQLDatabaseType.java         # extends BaseDatabaseType (Plugin)
│   │   ├── PostgreSQLConnection.java
│   │   ├── PostgreSQLParser.java               # extends Parser (connection-agnostic)
│   │   ├── PostgreSQLSchema.java
│   │   ├── PostgreSQLTable.java
│   │   ├── PostgreSQLConfigurationExtension.java
│   │   ├── PostgreSQLAdvisoryLockTemplate.java
│   │   ├── PostgreSQLCopyParsedStatement.java
│   │   └── PostgreSQLType.java
│   └── cockroachdb/
│       └── Cockroach*.java (co-located)
└── src/main/resources/META-INF/services/
    └── org.flywaydb.core.extensibility.Plugin  # lists DatabaseType + ConfigExtension classes
```

**Plugin registration file contents:**
```
org.flywaydb.database.cockroachdb.CockroachDBDatabaseType
org.flywaydb.database.postgresql.PostgreSQLDatabaseType
org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
```

Key observation: JDBC modules declare `DatabaseType` plugins. JDBC modules have NO dependency on
`flyway-nc-core`. The parser lives in the same JDBC module.

### Native Connectors (Non-JDBC) Adapter Structure

Both MongoDB and Couchbase follow an identical minimal structure:

```
flyway-database/flyway-database-nc-mongodb/
├── pom.xml                                     # depends on flyway-core + flyway-nc-core + driver SDK
├── src/main/java/org/flywaydb/database/nc/mongodb/
│   ├── MongoDBDatabase.java                    # extends NativeConnectorsNonJdbc (THE only class)
│   └── MongoshCredential.java                  # helper record
└── src/main/resources/META-INF/services/
    └── org.flywaydb.core.extensibility.Plugin  # one line: MongoDBDatabase
```

**Plugin registration file contents:**
```
org.flywaydb.database.nc.mongodb.MongoDBDatabase
```

**Key observation:** NC adapters declare a single `NativeConnectorsDatabase` plugin. Much simpler
than JDBC adapters.

### Proposed R2DBC Adapter Structure

R2DBC adapters mirror the NC non-JDBC pattern exactly:

```
flyway-database/flyway-database-nc-r2dbc-postgresql/
├── pom.xml                                     # flyway-core + flyway-nc-core + flyway-r2dbc-core + r2dbc driver
├── src/main/java/org/flywaydb/database/nc/r2dbc/postgresql/
│   └── PostgreSQLR2dbcDatabase.java            # extends NativeConnectorsR2dbc (THE only class)
└── src/main/resources/META-INF/services/
    └── org.flywaydb.core.extensibility.Plugin  # one line: PostgreSQLR2dbcDatabase

flyway-database/flyway-database-nc-r2dbc-mysql/
├── pom.xml                                     # flyway-core + flyway-nc-core + flyway-r2dbc-core + r2dbc driver
├── src/main/java/org/flywaydb/database/nc/r2dbc/mysql/
│   └── MySQLR2dbcDatabase.java
└── src/main/resources/META-INF/services/
    └── org.flywaydb.core.extensibility.Plugin

flyway-database/flyway-database-nc-r2dbc-h2/
├── pom.xml
├── src/main/java/org/flywaydb/database/nc/r2dbc/h2/
│   └── H2R2dbcDatabase.java
└── src/main/resources/META-INF/services/
    └── org.flywaydb.core.extensibility.Plugin
```

**Naming rationale:** `flyway-database-nc-r2dbc-{dbname}` follows the established NC naming convention
(`flyway-database-nc-mongodb`, `flyway-database-nc-couchbase`). All adapters go in `flyway-database/`.

---

## 2. Critical Gap: Missing Executor and Reader for R2DBC ConnectionType

**This is the single biggest risk in Phase 2.**

The NC execution pipeline works as follows:

1. `ExecutorFactory.getExecutor()` iterates registered `Executor<T, DB>` plugins
2. It calls `canExecute(connectionType)` to find a matching executor
3. The `connectionType` comes from `database.getDatabaseMetaData().connectionType()`
4. `NativeConnectorsR2dbc.initialize()` sets `connectionType = ConnectionType.R2DBC`

Current registered executors (`flyway-nc-core` META-INF/services):
- `ApiExecutor` — handles `ConnectionType.API` only
- `JdbcExecutor` — handles `ConnectionType.JDBC` only
- `ExecutableExecutor` — handles `ConnectionType.EXECUTABLE` only

**There is NO executor that handles `ConnectionType.R2DBC`.**

Similarly, for Readers:
- `JdbcReader.canRead()` — returns `connectionType == ConnectionType.JDBC`
- `NonJdbcReader.canRead()` — returns `connectionType == ConnectionType.EXECUTABLE || API`

**There is NO reader that handles `ConnectionType.R2DBC`.**

### Resolution

Two new plugins must be registered in `flyway-r2dbc-core`'s `META-INF/services`:

**`R2dbcExecutorPlugin`** — implements `Executor<String, NativeConnectorsR2dbc>`:
- `canExecute(ConnectionType)` returns `connectionType == ConnectionType.R2DBC`
- `execute()` delegates to `database.doExecute(executionUnit.getSql(), outputQueryResults)`
- Handles batch logic analogous to `JdbcExecutor`

**`R2dbcReaderPlugin`** — implements `Reader<SqlStatement>`:
- `canRead(ConnectionType)` returns `connectionType == ConnectionType.R2DBC`
- `read()` identical to `JdbcReader.read()` — uses `database.getParser()` to parse SQL
- R2DBC uses SQL statements exactly like JDBC; the JdbcReader logic applies verbatim

These belong in `flyway-r2dbc-core` (not per-adapter modules) since they are database-agnostic.

---

## 3. NativeConnectorsR2dbc — Methods Each Adapter Must Override

From reading `NativeConnectorsDatabase` interface and `NativeConnectorsR2dbc` abstract class,
each adapter MUST implement:

| Method | Required | Notes |
|--------|----------|-------|
| `supportsUrl(String url)` | YES | Check `r2dbc:postgresql:`, `r2dbc:h2:`, etc. |
| `supportedVerbs()` | YES | Copy from NC pattern: info, migrate, clean, validate, etc. |
| `getDatabaseType()` | YES | Returns "PostgreSQL", "MySQL", "H2" |
| `createConnectionFactory(env, config)` | YES | The core database-specific method |
| `getDatabaseMetaData()` | YES | Query DB version via R2DBC |
| `createSchemaHistoryTable(config)` | YES | CREATE TABLE SQL via R2DBC |
| `schemaHistoryTableExists(tableName)` | YES | CHECK SQL via R2DBC |
| `getSchemaHistoryModel(tableName)` | YES | SELECT SQL via R2DBC |
| `appendSchemaHistoryItem(item, table)` | YES | INSERT SQL via R2DBC |
| `getParser()` | YES | Return `BiFunction` wrapping existing JDBC parser |
| `isSchemaEmpty(schema)` | YES | Query schema contents |
| `isSchemaExists(schema)` | YES | Query information_schema or similar |
| `createSchemas(schemas...)` | YES | CREATE SCHEMA SQL |
| `doCleanSchema(schema)` | YES | DROP/TRUNCATE all objects |
| `doDropSchema(schema)` | YES | DROP SCHEMA |
| `startTransaction()` | YES | R2DBC `connection.beginTransaction()` |
| `commitTransaction()` | YES | R2DBC `connection.commitTransaction()` |
| `rollbackTransaction()` | YES | R2DBC `connection.rollbackTransaction()` |
| `removeFailedSchemaHistoryItems(table)` | YES | DELETE WHERE success=false |
| `updateSchemaHistoryItem(item, table)` | YES | UPDATE SQL |
| `getCurrentUser()` | YES | Can return null or query |
| `isClosed()` | YES | Inherited from NativeConnectorsR2dbc |
| `isOnByDefault(config)` | SHOULD | Return true |
| `getDefaultSchema(config)` | SHOULD | Override per-DB default |

Methods that NativeConnectorsR2dbc already implements (not needing override):
- `initialize()` — calls `createConnectionFactory()` then sets up
- `doExecute()` — executes SQL via R2DBC connection
- `doExecuteBatch()` — batch execution
- `canCreateJdbcDataSource()` — returns false
- `supportsBatch()` — returns true
- `supportsTransactions()` — returns true
- `close()` — closes R2DBC connection

---

## 4. Parser Reuse Strategy

**PostgreSQL R2DBC adapter:**
```java
@Override
public BiFunction<Configuration, ParsingContext, Parser> getParser() {
    return PostgreSQLParser::new;
}
```
Dependency: `flyway-database-nc-r2dbc-postgresql` depends on `flyway-database-postgresql`.
`PostgreSQLParser` has no JDBC runtime dependency — it only depends on `flyway-core`'s
`Parser` base class, `Configuration`, `ParsingContext`, `PeekingReader`, etc.

**MySQL R2DBC adapter:**
```java
@Override
public BiFunction<Configuration, ParsingContext, Parser> getParser() {
    return MySQLParser::new;
}
```
Dependency: `flyway-database-nc-r2dbc-mysql` depends on `flyway-mysql`.
`MySQLParser` similarly has no JDBC runtime dependency.

**H2 R2DBC adapter:**
H2 JDBC parser lives in `flyway-core` itself (not a separate module), at
`org.flywaydb.core.internal.database.h2.H2Parser` (to be confirmed — H2 may be embedded in core).
No cross-module dependency needed.

**Barriers to parser reuse:**
- None. Parsers depend only on `flyway-core`'s parser framework. They do not use JDBC.
- Cross-module dependency (R2DBC adapter → JDBC module) is acceptable — same pattern as NC modules
  depending on `flyway-nc-core` while being in `flyway-database/`.

---

## 5. ConnectionFactory Pattern

### PostgreSQL R2DBC
```java
// Source: pgjdbc/r2dbc-postgresql, org.postgresql:r2dbc-postgresql:1.1.1.RELEASE
@Override
protected ConnectionFactory createConnectionFactory(
        ResolvedEnvironment environment,
        Configuration configuration) {

    // URL format: r2dbc:postgresql://user:password@host:port/database
    // OR use ConnectionFactoryOptions builder
    ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(environment.getUrl())
        .mutate()
        .option(ConnectionFactoryOptions.USER, environment.getUser())
        .option(ConnectionFactoryOptions.PASSWORD, environment.getPassword())
        .build();
    return ConnectionFactories.get(options);
}
```
Maven: `org.postgresql:r2dbc-postgresql:1.1.1.RELEASE`

### MySQL R2DBC
```java
// Source: asyncer-io/r2dbc-mysql, io.asyncer:r2dbc-mysql:1.4.1
@Override
protected ConnectionFactory createConnectionFactory(
        ResolvedEnvironment environment,
        Configuration configuration) {

    // URL format: r2dbc:mysql://user:password@host:port/database
    ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(environment.getUrl())
        .mutate()
        .option(ConnectionFactoryOptions.USER, environment.getUser())
        .option(ConnectionFactoryOptions.PASSWORD, environment.getPassword())
        .build();
    return ConnectionFactories.get(options);
}
```
Maven: `io.asyncer:r2dbc-mysql:1.4.1`
Note: The MySQL R2DBC driver moved from `dev.miku:r2dbc-mysql` (inactive) to `io.asyncer:r2dbc-mysql`.
Always use `io.asyncer` coordinates.

### H2 R2DBC
```java
// Source: r2dbc/r2dbc-h2, io.r2dbc:r2dbc-h2:1.1.0.RELEASE
@Override
protected ConnectionFactory createConnectionFactory(
        ResolvedEnvironment environment,
        Configuration configuration) {

    // URL format: r2dbc:h2:mem:///databasename or r2dbc:h2:file://./path/to/db
    ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(environment.getUrl())
        .mutate()
        .build();
    return ConnectionFactories.get(options);
}
```
Maven: `io.r2dbc:r2dbc-h2:1.1.0.RELEASE`

---

## 6. URL Detection Pattern

Each adapter's `supportsUrl()` checks the R2DBC URL prefix:

```java
// PostgreSQL adapter
@Override
public DatabaseSupport supportsUrl(String url) {
    if (url.startsWith("r2dbc:postgresql:") || url.startsWith("r2dbc:pool:postgresql:")) {
        return new DatabaseSupport(true, 1);
    }
    return new DatabaseSupport(false, 0);
}

// MySQL adapter
@Override
public DatabaseSupport supportsUrl(String url) {
    if (url.startsWith("r2dbc:mysql:")) {
        return new DatabaseSupport(true, 1);
    }
    return new DatabaseSupport(false, 0);
}

// H2 adapter
@Override
public DatabaseSupport supportsUrl(String url) {
    if (url.startsWith("r2dbc:h2:")) {
        return new DatabaseSupport(true, 1);
    }
    return new DatabaseSupport(false, 0);
}
```

---

## 7. Schema History Table SQL (Per Database)

R2DBC adapters must implement `createSchemaHistoryTable()` with database-specific SQL:

**PostgreSQL:**
```sql
CREATE TABLE "public"."flyway_schema_history" (
    "installed_rank" INT NOT NULL,
    "version" VARCHAR(50),
    "description" VARCHAR(200) NOT NULL,
    "type" VARCHAR(20) NOT NULL,
    "script" VARCHAR(1000) NOT NULL,
    "checksum" INTEGER,
    "installed_by" VARCHAR(100) NOT NULL,
    "installed_on" TIMESTAMP NOT NULL DEFAULT now(),
    "execution_time" INTEGER NOT NULL,
    "success" BOOLEAN NOT NULL,
    CONSTRAINT "flyway_schema_history_pk" PRIMARY KEY ("installed_rank")
);
CREATE INDEX "flyway_schema_history_s_idx" ON "public"."flyway_schema_history" ("success");
```

Note: The NC path uses a simplified DDL compared to `PostgreSQLDatabase.getRawCreateScript()` which
includes optional tablespace. The R2DBC adapter should match the PostgreSQL NC schema structure,
not the legacy JDBC one. The important thing for Constraint 3 (seamless switching) is that
both JDBC and R2DBC paths read the SAME table. The DDL must be identical in column names and
types to what the JDBC adapter creates.

**MySQL:**
```sql
CREATE TABLE `flyway_schema_history` (
    `installed_rank` INT NOT NULL,
    `version` VARCHAR(50),
    `description` VARCHAR(200) NOT NULL,
    `type` VARCHAR(20) NOT NULL,
    `script` VARCHAR(1000) NOT NULL,
    `checksum` INT,
    `installed_by` VARCHAR(100) NOT NULL,
    `installed_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `execution_time` INT NOT NULL,
    `success` TINYINT NOT NULL,
    PRIMARY KEY (`installed_rank`)
);
```

**H2:**
```sql
CREATE TABLE "PUBLIC"."flyway_schema_history" (
    "installed_rank" INT NOT NULL,
    "version" VARCHAR(50),
    "description" VARCHAR(200) NOT NULL,
    "type" VARCHAR(20) NOT NULL,
    "script" VARCHAR(1000) NOT NULL,
    "checksum" INT,
    "installed_by" VARCHAR(100) NOT NULL,
    "installed_on" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP(),
    "execution_time" INT NOT NULL,
    "success" BOOLEAN NOT NULL,
    PRIMARY KEY ("installed_rank")
);
```

---

## 8. ServiceLoader Registration

Each adapter module needs **one** `META-INF/services` file:

`src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`

Contents (one class per line):
```
org.flywaydb.database.nc.r2dbc.postgresql.PostgreSQLR2dbcDatabase
```

Additionally, `flyway-r2dbc-core` must be updated to register the two new plugins:

`flyway-nc/flyway-r2dbc-core/src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`
```
org.flywaydb.nc.r2dbc.R2dbcExecutorPlugin
org.flywaydb.nc.r2dbc.R2dbcReaderPlugin
```

Note: `flyway-r2dbc-core` does NOT currently have a `META-INF/services` file — it must be created.

---

## 9. pom.xml Structure for Each Adapter

Example for PostgreSQL adapter (MySQL and H2 follow same pattern):

```xml
<project>
  <parent>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-parent</artifactId>
    <version>12.0.2</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>

  <artifactId>flyway-database-nc-r2dbc-postgresql</artifactId>
  <name>${project.artifactId}</name>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <!-- Flyway core -->
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
      <version>${project.parent.version}</version>
    </dependency>
    <!-- NC core infrastructure -->
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-nc-core</artifactId>
      <version>${project.parent.version}</version>
    </dependency>
    <!-- R2DBC core (Phase 1 - contains NativeConnectorsR2dbc base) -->
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-r2dbc-core</artifactId>
      <version>${project.parent.version}</version>
    </dependency>
    <!-- Existing JDBC module (for parser reuse only - runtime optional) -->
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
      <version>${project.parent.version}</version>
      <optional>true</optional>
    </dependency>
    <!-- R2DBC PostgreSQL driver -->
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>r2dbc-postgresql</artifactId>
      <version>1.1.1.RELEASE</version>
    </dependency>
    <!-- Lombok -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
    </dependency>
  </dependencies>

  <build>
    <!-- Standard resources + license copy, identical to NC mongodb pattern -->
    ...
  </build>
</project>
```

**MySQL dependencies:**
- `flyway-mysql` (for `MySQLParser`)
- `io.asyncer:r2dbc-mysql:1.4.1`

**H2 dependencies:**
- No JDBC module needed (H2 parser is in `flyway-core`)
- `io.r2dbc:r2dbc-h2:1.1.0.RELEASE`

---

## 10. Parent POM Registration

Both `flyway-database/pom.xml` and `flyway-nc/pom.xml` need new module entries.

**flyway-database/pom.xml** — add modules:
```xml
<module>flyway-database-nc-r2dbc-postgresql</module>
<module>flyway-database-nc-r2dbc-mysql</module>
<module>flyway-database-nc-r2dbc-h2</module>
```

**flyway-nc/pom.xml** — no changes needed (r2dbc-core already added in Phase 1).

R2DBC driver versions must be added to the **root pom.xml** `<properties>` section:
```xml
<version.r2dbc-postgresql>1.1.1.RELEASE</version.r2dbc-postgresql>
<version.r2dbc-mysql>1.4.1</version.r2dbc-mysql>
<version.r2dbc-h2>1.1.0.RELEASE</version.r2dbc-h2>
```

And to the `<dependencyManagement>` section:
```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>r2dbc-postgresql</artifactId>
  <version>${version.r2dbc-postgresql}</version>
</dependency>
<dependency>
  <groupId>io.asyncer</groupId>
  <artifactId>r2dbc-mysql</artifactId>
  <version>${version.r2dbc-mysql}</version>
</dependency>
<dependency>
  <groupId>io.r2dbc</groupId>
  <artifactId>r2dbc-h2</artifactId>
  <version>${version.r2dbc-h2}</version>
</dependency>
```

---

## 11. Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| r2dbc-spi | 1.0.0.RELEASE | R2DBC API contract | Already in flyway-r2dbc-core (Phase 1) |
| reactor-core | 3.5.10 | Mono.block() blocking strategy | Already in flyway-r2dbc-core (Phase 1) |
| flyway-core | 12.0.2 | Core Flyway API and Plugin interface | Required for all adapters |
| flyway-nc-core | 12.0.2 | NativeConnectorsNonJdbc, executors, readers | Required for NC path |
| flyway-r2dbc-core | 12.0.2 | NativeConnectorsR2dbc base class | Phase 1 artifact |

### Per-Adapter
| Library | Version | Purpose |
|---------|---------|---------|
| org.postgresql:r2dbc-postgresql | 1.1.1.RELEASE | PostgreSQL R2DBC driver |
| io.asyncer:r2dbc-mysql | 1.4.1 | MySQL R2DBC driver (NOT dev.miku — that's abandoned) |
| io.r2dbc:r2dbc-h2 | 1.1.0.RELEASE | H2 R2DBC driver |
| org.projectlombok:lombok | managed | @CustomLog, code generation |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| io.asyncer:r2dbc-mysql | dev.miku:r2dbc-mysql | dev.miku is abandoned/inactive; io.asyncer is the active fork |
| org.postgresql:r2dbc-postgresql | io.r2dbc:r2dbc-postgresql (old) | Old groupId artifact is archived; pgjdbc maintains the new one |
| Separate R2dbcExecutorPlugin module | Add to flyway-r2dbc-core | Adding to r2dbc-core is cleaner — no new module needed |

---

## 12. Architecture Patterns

### Pattern 1: NC Adapter Single-Class Pattern

Every NC adapter (MongoDB, Couchbase) has one main class that:
- Extends the appropriate base (`NativeConnectorsNonJdbc`, `NativeConnectorsJdbc`, or the new `NativeConnectorsR2dbc`)
- Implements `supportsUrl()` — URL prefix matching
- Implements `initialize()` — creates connection
- Implements `doExecute()` — runs SQL/commands
- Implements all schema history methods

R2DBC adapters follow this exactly.

### Pattern 2: R2DBC Execution via R2dbcExecutor (blocking)

All R2DBC execution routes through `Mono.block()`:

```java
// In NativeConnectorsR2dbc.doExecute() (already implemented in Phase 1):
@Override
public void doExecute(String executionUnit, boolean outputQueryResults) {
    Connection conn = Mono.from(connectionFactory.create()).block(blockingTimeout);
    try {
        Mono.from(conn.createStatement(executionUnit).execute())
            .flatMapMany(Result::getRowsUpdated)
            .collectList()
            .block(blockingTimeout);
    } finally {
        Mono.from(conn.close()).block(blockingTimeout);
    }
}
```

Adapters do NOT override `doExecute()` — they inherit it from `NativeConnectorsR2dbc`.

### Pattern 3: SQL-Based Schema History (R2DBC adapters)

Unlike MongoDB (document-based) or Couchbase (N1QL-based), R2DBC adapters use plain SQL for all
schema history operations. The `getSchemaHistoryModel()` implementation follows the same pattern
as `NativeConnectorsJdbc.getSchemaHistoryModel()` but uses R2DBC instead of JDBC:

```java
// Execute SELECT via R2DBC, map rows to SchemaHistoryItem objects
@Override
public SchemaHistoryModel getSchemaHistoryModel(String tableName) {
    // Use this.connection (established in initialize()) or new connection from factory
    String sql = "SELECT installed_rank, version, ... FROM " + quote(getCurrentSchema(), tableName) + " ...";
    List<SchemaHistoryItem> items = Mono.from(connectionFactory.create())
        .flatMapMany(conn -> conn.createStatement(sql).execute())
        .flatMap(result -> result.map((row, meta) -> SchemaHistoryItem.builder()
            .installedRank(row.get("installed_rank", Integer.class))
            ...
            .build()))
        .collectList()
        .block(blockingTimeout);
    return new SchemaHistoryModel(items != null ? items : List.of());
}
```

### Anti-Patterns to Avoid
- **Duplicating SQL statement execution logic:** Adapters should NOT override `doExecute()` unless
  absolutely necessary. The base class handles it.
- **Database-specific code in flyway-r2dbc-core:** All database-specific code belongs in adapter modules.
- **Using JDBC Connection in R2DBC adapter:** R2DBC adapters must NEVER import `java.sql.Connection`.
- **Ignoring the SchemaHistoryModel column types:** The column types must match what the JDBC adapter
  creates — this is critical for Constraint 3 (seamless JDBC↔R2DBC switching).

---

## 13. Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SQL parsing | Custom parser | `PostgreSQLParser`, `MySQLParser` from JDBC modules | Already battle-tested, handles edge cases |
| Schema history model | Custom schema | `SchemaHistoryItem.builder()`, `SchemaHistoryModel` | NC schema model is the standard |
| Blocking reactive | Custom thread tricks | `Mono.block(timeout)` via `R2dbcExecutor` | Already in Phase 1, handles timeouts |
| R2DBC URL parsing | Manual parsing | `ConnectionFactoryOptions.parse(url)` from r2dbc-spi | Standard SPI utility |
| Plugin discovery | Manual class loading | `META-INF/services` + `ServiceLoader` | Flyway's established extension mechanism |

---

## 14. Common Pitfalls

### Pitfall 1: Missing R2dbcExecutorPlugin Registration
**What goes wrong:** Runtime `FlywayException: No executor found for connection type: R2DBC`
**Why it happens:** `ExecutorFactory` uses plugin registry to find executor by `ConnectionType`. No R2DBC executor is registered.
**How to avoid:** Create `R2dbcExecutorPlugin` + `R2dbcReaderPlugin` in `flyway-r2dbc-core`, register both in `META-INF/services`.
**Warning signs:** Any R2DBC test fails with "No executor found" before even connecting to DB.

### Pitfall 2: Wrong MySQL R2DBC GroupId
**What goes wrong:** Build fails because `dev.miku:r2dbc-mysql` is not resolvable or uses old API
**Why it happens:** The original `dev.miku:r2dbc-mysql` project is archived/abandoned
**How to avoid:** Always use `io.asyncer:r2dbc-mysql`
**Warning signs:** Maven can't resolve the dependency or driver API mismatch errors

### Pitfall 3: PostgreSQL R2DBC GroupId Changed
**What goes wrong:** Build fails or old versions pulled in
**Why it happens:** Old `io.r2dbc:r2dbc-postgresql` was maintained by the Spring team; it moved to `org.postgresql:r2dbc-postgresql` (pgjdbc org)
**How to avoid:** Use `org.postgresql:r2dbc-postgresql:1.1.1.RELEASE`
**Warning signs:** Resolution errors or wrong driver version loaded

### Pitfall 4: Schema History Table DDL Mismatch
**What goes wrong:** JDBC↔R2DBC switching fails — constraint 3 violated
**Why it happens:** R2DBC adapter creates table with slightly different column types than JDBC adapter
**How to avoid:** Compare `PostgreSQLDatabase.getRawCreateScript()` with R2DBC adapter's CREATE TABLE SQL. Columns must be identical.
**Warning signs:** Integration test for switching fails with column mismatch or type coercion errors

### Pitfall 5: R2DBC Connection Not Closed
**What goes wrong:** Connection pool exhaustion, resource leaks
**Why it happens:** R2DBC connections are not `Closeable` — require explicit `Mono.from(conn.close()).block()`
**How to avoid:** Always use try/finally pattern in `doExecute()`, or rely on base class implementation
**Warning signs:** Hanging tests, connection pool warnings

### Pitfall 6: Adapter Module Not Added to Parent POM
**What goes wrong:** Module builds in isolation but not as part of the project
**Why it happens:** Forgetting to add `<module>` entry in `flyway-database/pom.xml`
**How to avoid:** Add all three modules to `flyway-database/pom.xml` as part of each module creation
**Warning signs:** `mvn install` at root does not compile new module

### Pitfall 7: Missing META-INF/services File in flyway-r2dbc-core
**What goes wrong:** R2dbcExecutorPlugin and R2dbcReaderPlugin never loaded
**Why it happens:** Phase 1 created the r2dbc-core module without a services file (no plugins were needed in Phase 1)
**How to avoid:** Create the file when adding the new plugins
**Warning signs:** "No executor found" even after implementing R2dbcExecutorPlugin

---

## 15. Code Examples

### Minimal PostgreSQL Adapter Skeleton

```java
// Source: modeled after flyway-database-nc-mongodb/MongoDBDatabase.java + NativeConnectorsR2dbc base
package org.flywaydb.database.nc.r2dbc.postgresql;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.List;
import java.util.function.BiFunction;
import lombok.CustomLog;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.nc.DatabaseSupport;
import org.flywaydb.core.internal.nc.MetaData;
import org.flywaydb.core.internal.nc.schemahistory.SchemaHistoryItem;
import org.flywaydb.core.internal.nc.schemahistory.SchemaHistoryModel;
import org.flywaydb.core.internal.configuration.models.ResolvedEnvironment;
import org.flywaydb.core.internal.parser.Parser;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.database.postgresql.PostgreSQLParser;
import org.flywaydb.nc.r2dbc.NativeConnectorsR2dbc;
import reactor.core.publisher.Mono;

@CustomLog
public class PostgreSQLR2dbcDatabase extends NativeConnectorsR2dbc {

    @Override
    public DatabaseSupport supportsUrl(String url) {
        if (url.startsWith("r2dbc:postgresql:") || url.startsWith("r2dbc:pool:postgresql:")) {
            return new DatabaseSupport(true, 1);
        }
        return new DatabaseSupport(false, 0);
    }

    @Override
    public boolean isOnByDefault(Configuration configuration) {
        return true;
    }

    @Override
    public List<String> supportedVerbs() {
        return List.of("info", "validate", "migrate", "clean", "baseline", "repair", "testConnection");
    }

    @Override
    public String getDatabaseType() {
        return "PostgreSQL";
    }

    @Override
    protected ConnectionFactory createConnectionFactory(
            ResolvedEnvironment environment, Configuration configuration) {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(environment.getUrl())
            .mutate()
            .option(ConnectionFactoryOptions.USER, environment.getUser())
            .option(ConnectionFactoryOptions.PASSWORD, environment.getPassword())
            .build();
        return ConnectionFactories.get(options);
    }

    @Override
    public BiFunction<Configuration, ParsingContext, Parser> getParser() {
        return PostgreSQLParser::new;
    }

    @Override
    public MetaData getDatabaseMetaData() {
        if (this.metaData != null) return metaData;
        // Query: SELECT version() — return MetaData record
        // ... R2DBC query via connectionFactory
    }

    // createSchemaHistoryTable(), schemaHistoryTableExists(), getSchemaHistoryModel(),
    // appendSchemaHistoryItem(), isSchemaEmpty(), isSchemaExists(), createSchemas(),
    // doCleanSchema(), doDropSchema(), startTransaction(), commitTransaction(),
    // rollbackTransaction(), removeFailedSchemaHistoryItems(), updateSchemaHistoryItem()
    // ... each 5-20 lines of SQL + R2DBC blocking execution
}
```

### R2dbcExecutorPlugin Skeleton

```java
// Source: modeled after JdbcExecutor.java
package org.flywaydb.nc.r2dbc;

import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.nc.ConnectionType;
import org.flywaydb.core.internal.nc.Executor;
import org.flywaydb.core.internal.sqlscript.SqlStatement;

public class R2dbcExecutorPlugin implements Executor<SqlStatement, NativeConnectorsR2dbc> {

    @Override
    public boolean canExecute(ConnectionType connectionType) {
        return connectionType == ConnectionType.R2DBC;
    }

    @Override
    public void execute(NativeConnectorsR2dbc database, SqlStatement executionUnit,
            Configuration configuration) {
        if (configuration.isBatch() && executionUnit.isBatchable()) {
            database.addToBatch(executionUnit.getSql());
            if (database.getBatchSize() >= 100) {
                database.doExecuteBatch();
            }
        } else {
            database.doExecuteBatch(); // flush pending
            database.doExecute(executionUnit.getSql(), configuration.isOutputQueryResults());
        }
    }

    @Override
    public void finishExecution(NativeConnectorsR2dbc database, Configuration configuration) {
        if (configuration.isBatch()) {
            database.doExecuteBatch();
        }
    }

    @Override
    public void appendErrorMessage(SqlStatement executionUnit, StringBuilder builder, boolean debug) {
        builder.append("Line      : ").append(executionUnit.getLineNumber()).append("\n");
    }
}
```

### R2dbcReaderPlugin Skeleton

```java
// Source: identical to JdbcReader.java — R2DBC reads SQL the same way as JDBC
package org.flywaydb.nc.r2dbc;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.nc.ConnectionType;
import org.flywaydb.core.internal.nc.NativeConnectorsDatabase;
import org.flywaydb.core.internal.nc.Reader;
import org.flywaydb.core.internal.parser.Parser;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.sqlscript.SqlScriptMetadata;
import org.flywaydb.core.internal.sqlscript.SqlStatement;

public class R2dbcReaderPlugin implements Reader<SqlStatement> {

    @Override
    public boolean canRead(ConnectionType connectionType) {
        return connectionType == ConnectionType.R2DBC;
    }

    @Override
    public Stream<SqlStatement> read(Configuration configuration,
            NativeConnectorsDatabase database, ParsingContext parsingContext,
            LoadableResource loadableResource, SqlScriptMetadata metadata) {
        // Identical to JdbcReader — SQL parsing is connection-type agnostic
        Parser parser = (Parser) database.getParser().apply(configuration, parsingContext);
        Iterable<SqlStatement> iterable = () -> parser.parse(loadableResource, metadata);
        return StreamSupport.stream(iterable.spliterator(), false);
    }
}
```

---

## 16. Recommended Implementation Order

### Step 1: Infrastructure in flyway-r2dbc-core (BLOCKER — do first)
1. Create `R2dbcExecutorPlugin.java` in `flyway-r2dbc-core`
2. Create `R2dbcReaderPlugin.java` in `flyway-r2dbc-core`
3. Create `META-INF/services/org.flywaydb.core.extensibility.Plugin` in `flyway-r2dbc-core`
4. Register both plugins

**Why first:** Without this, no adapter will work at runtime.

### Step 2: H2 Adapter (flyway-database-nc-r2dbc-h2)
1. Create module directory and pom.xml
2. Implement `H2R2dbcDatabase.java`
3. Register in META-INF/services
4. Add to `flyway-database/pom.xml`
5. Add `r2dbc-h2` to root pom.xml dependency management

**Why H2 first:** H2 runs in-memory — no Docker/external DB needed for testing. Fast feedback loop.

### Step 3: PostgreSQL Adapter (flyway-database-nc-r2dbc-postgresql) — Reference Implementation
1. Create module directory and pom.xml
2. Implement `PostgreSQLR2dbcDatabase.java`
3. Register in META-INF/services
4. Add to `flyway-database/pom.xml`
5. Add `r2dbc-postgresql` to root pom.xml dependency management

**Why PostgreSQL second:** Best-documented R2DBC driver. Clearest community examples.

### Step 4: MySQL Adapter (flyway-database-nc-r2dbc-mysql)
1. Create module directory and pom.xml
2. Implement `MySQLR2dbcDatabase.java`
3. Register in META-INF/services
4. Add to `flyway-database/pom.xml`
5. Add `r2dbc-mysql` to root pom.xml dependency management

### Step 5: Integration tests (per-adapter)
Tests live in the private repository, but test design must be specified:
- H2: In-memory, no containers required
- PostgreSQL: Testcontainers `PostgreSQLContainer`
- MySQL: Testcontainers `MySQLContainer`

---

## 17. Risk Assessment

### Risk 1: Missing R2dbcExecutorPlugin (CRITICAL — BLOCKER)
- **Severity:** CRITICAL — Phase 2 cannot function without it
- **Constraint at risk:** All 4 constraints (nothing works)
- **Mitigation:** Implement R2dbcExecutorPlugin and R2dbcReaderPlugin in Step 1

### Risk 2: R2DBC Driver API Differences
- **Severity:** MEDIUM
- **Constraint at risk:** Constraint 3 (seamless switching)
- **Details:** R2DBC drivers may handle NULL values, timestamps, booleans differently than JDBC.
  Schema history item mapping code must handle these carefully.
- **Mitigation:** Test with actual DB, verify all 10 schema history columns round-trip correctly

### Risk 3: r2dbc-spi Version Mismatch
- **Severity:** MEDIUM
- **Details:** `flyway-r2dbc-core` uses r2dbc-spi 1.0.0.RELEASE. If R2DBC drivers require a
  different version, there could be classpath conflicts.
- **Mitigation:** Verify all three drivers (postgresql 1.1.1, mysql 1.4.1, h2 1.1.0) are compatible
  with r2dbc-spi 1.0.0.RELEASE. All should be since they're all post-1.0 final release.

### Risk 4: Parser Cross-Module Dependency
- **Severity:** LOW
- **Details:** R2DBC adapter module depends on JDBC module purely for the parser class.
  This creates a compile-time dependency but no runtime JDBC driver requirement.
- **Mitigation:** Mark JDBC module dependency as `<optional>true</optional>` if possible, or
  accept the full transitive dependency. The parser class itself has no JDBC runtime dependencies.

### Risk 5: NativeConnectorsR2dbc.initialize() Establishes Persistent Connection
- **Severity:** LOW
- **Details:** `NativeConnectorsR2dbc.initialize()` calls `establishConnection()` which blocks to
  get a connection and stores it in `this.connection`. This connection is then used only in `close()`.
  The actual SQL execution creates NEW connections per statement (in `doExecute()`).
  This means a dangling held connection during the entire migration run.
- **Mitigation:** Consider using the held `connection` in `doExecute()` instead of creating new ones
  per statement — this would be more efficient and fix the dangling connection pattern.
  Alternatively, document this as a known limitation of Phase 2.

### Constraint Compliance Check
| Constraint | Phase 2 Risk | Mitigation |
|-----------|-------------|------------|
| 1. JDBC default | LOW | R2DBC adapters don't touch JDBC detection |
| 2. Database-agnostic core | LOW | Each adapter is in its own module |
| 3. Seamless switching | MEDIUM | Schema history DDL must match JDBC exactly |
| 4. Full backward compat | LOW | Additive modules, zero JDBC changes |

---

## 18. Phase 2 Success Criteria

All must be verified before Phase 2 is considered complete:

### Module Completeness
- [ ] `flyway-r2dbc-core` has `R2dbcExecutorPlugin` registered in META-INF/services
- [ ] `flyway-r2dbc-core` has `R2dbcReaderPlugin` registered in META-INF/services
- [ ] `flyway-database-nc-r2dbc-postgresql` module builds successfully
- [ ] `flyway-database-nc-r2dbc-mysql` module builds successfully
- [ ] `flyway-database-nc-r2dbc-h2` module builds successfully
- [ ] All three modules added to `flyway-database/pom.xml`
- [ ] R2DBC driver versions in root pom.xml dependency management

### Constraint Verification
- [ ] Constraint 1: JDBC modules untouched (zero diff in `flyway-database-postgresql`, `flyway-mysql`)
- [ ] Constraint 2: No PostgreSQL/MySQL-specific code in `flyway-r2dbc-core`
- [ ] Constraint 3: Schema history DDL produces identical column structure to JDBC adapters
- [ ] Constraint 4: `mvn verify -pl flyway-database/flyway-database-postgresql` shows no changes

### Functional Verification (integration tests)
- [ ] H2 R2DBC: `flyway migrate` on in-memory H2 via `r2dbc:h2:mem:///testdb`
- [ ] H2 R2DBC: Schema history table created with correct columns
- [ ] PostgreSQL R2DBC: `flyway migrate` via `r2dbc:postgresql://...`
- [ ] MySQL R2DBC: `flyway migrate` via `r2dbc:mysql://...`
- [ ] JDBC↔R2DBC switching: Run JDBC migrations, then switch to R2DBC, run more — history intact
- [ ] R2DBC↔JDBC switching: Run R2DBC migrations, then switch to JDBC, run more — history intact

### Code Quality
- [ ] No `java.sql.*` imports in any R2DBC adapter class
- [ ] All adapters use `@CustomLog` (project logging pattern)
- [ ] Parser returned by `getParser()` is the JDBC parser (not a custom implementation)

---

## 19. Estimated Effort

| Component | Effort Estimate | Notes |
|-----------|----------------|-------|
| R2dbcExecutorPlugin + R2dbcReaderPlugin | 1 day | Small classes, pattern from JdbcExecutor |
| flyway-r2dbc-core META-INF/services | 0.5 days | File creation + verification |
| H2 adapter (full) | 2-3 days | Simplest DB for R2DBC |
| PostgreSQL adapter (full) | 3-4 days | Reference implementation, most complex |
| MySQL adapter (full) | 2-3 days | Similar to PostgreSQL, simpler schema |
| Root pom.xml + parent pom updates | 0.5 days | Dependency management additions |
| Integration test specs | 1-2 days | Test design for private repo |
| Verification (constraint checks) | 1 day | Review and sign-off |
| **Total Phase 2** | **11-15 days** | |

---

## 20. Open Questions for Planning Phase

1. **R2DBC connection per statement vs persistent connection:**
   `NativeConnectorsR2dbc.doExecute()` creates a NEW connection per SQL statement, while also
   holding a persistent `this.connection` from `initialize()`. Should Phase 2 refactor to reuse
   the persistent connection, or accept the per-statement connection pattern?
   Recommendation: Use persistent connection for consistency. R2DBC connections should be reused,
   not created per statement.

2. **isClosed() behavior:**
   `NativeConnectorsR2dbc` does not implement `isClosed()` (base class returns false by default).
   Should adapters track the closed state? The `NativeConnectorsNonJdbc` has an `isClosed` boolean.
   Recommend: Add `isClosed` boolean to `NativeConnectorsR2dbc`.

3. **H2 R2DBC parser source:**
   H2's parser (`H2Parser`) needs to be located. Is it in `flyway-core` itself, or in a separate
   `flyway-database-h2`-equivalent module? Quick check shows `flyway-database-derby` and
   `flyway-database-hsqldb` exist but no `flyway-database-h2`. H2 parser is likely in `flyway-core`
   at `org.flywaydb.core.internal.database.h2.H2Parser` — needs verification.

4. **Transaction semantics for non-transactional PostgreSQL statements:**
   `PostgreSQLParser.detectCanExecuteInTransaction()` returns `false` for statements like
   `CREATE DATABASE`, `CREATE INDEX CONCURRENTLY`, etc. R2DBC adapter must handle
   `ParsedSqlStatement.canExecuteInTransaction == false` correctly — these must NOT be wrapped
   in a transaction. Does `NativeConnectorsR2dbc` handle this? Check the base class behavior.

5. **Schema history table column for JDBC compatibility:**
   PostgreSQL JDBC uses `TIMESTAMP NOT NULL DEFAULT now()` for `installed_on`. R2DBC creates
   the table, so it controls the DDL. Ensure the column definition is byte-for-byte compatible
   so seamless switching works.

6. **MySQL BOOLEAN vs TINYINT:**
   MySQL JDBC adapter uses `TINYINT(1)` for `success`. MySQL R2DBC adapter should use the same.
   Does `io.asyncer:r2dbc-mysql` return `boolean` or `byte` for `TINYINT(1)` columns?

---

## Sources

### Primary (HIGH confidence — direct source inspection)
- `flyway-database/flyway-database-nc-mongodb/` — NC adapter structure pattern
- `flyway-database/flyway-database-nc-couchbase/` — NC adapter structure pattern
- `flyway-nc/flyway-r2dbc-core/` — Phase 1 implementation (NativeConnectorsR2dbc, R2dbcExecutor)
- `flyway-nc/flyway-nc-core/src/main/java/org/flywaydb/nc/executors/` — Executor/Reader gap
- `flyway-nc/flyway-nc-core/src/main/resources/META-INF/services/` — Confirmed no R2DBC entry
- `flyway-core/src/main/java/org/flywaydb/core/internal/nc/` — NativeConnectorsDatabase interface
- `flyway-database/flyway-database-postgresql/` — JDBC module structure + PostgreSQLParser
- `flyway-database/flyway-mysql/` — MySQL module structure
- `.planning/R2DBC_IMPLEMENTATION_STRATEGY.md` — Project design decisions

### Secondary (MEDIUM confidence — official docs via WebFetch)
- `https://github.com/pgjdbc/r2dbc-postgresql` — PostgreSQL driver groupId confirmed: `org.postgresql`
- `https://github.com/asyncer-io/r2dbc-mysql/releases` — MySQL driver: `io.asyncer:r2dbc-mysql:1.4.1`
- `https://github.com/r2dbc/r2dbc-h2/releases` — H2 driver: `io.r2dbc:r2dbc-h2:1.1.0.RELEASE`
- `https://r2dbc.io/drivers/` — Canonical list of supported R2DBC drivers

---

## Metadata

**Confidence breakdown:**
- Module structure pattern: HIGH — direct source inspection
- Missing Executor/Reader: HIGH — confirmed by code inspection, no R2DBC in META-INF/services
- R2DBC driver coordinates: MEDIUM — GitHub releases confirmed, not Maven Central direct lookup
- Effort estimates: MEDIUM — based on similar NC adapter complexity in codebase
- Schema DDL compatibility: MEDIUM — confirmed column names match, exact types need integration testing

**Research date:** 2026-02-27
**Valid until:** 2026-03-27 (R2DBC driver versions stable; ecosystem moving slowly)
