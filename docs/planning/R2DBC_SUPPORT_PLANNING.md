# Flyway R2DBC Support - Technical Planning Document

**Version:** 1.0  
**Date:** February 27, 2026  
**Author:** Planning Document  
**Status:** Draft

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Background & Motivation](#2-background--motivation)
3. [Architecture Analysis](#3-architecture-analysis)
4. [Proposed Solution](#4-proposed-solution)
5. [Module Structure](#5-module-structure)
6. [Implementation Phases](#6-implementation-phases)
7. [API Design](#7-api-design)
8. [Spring Boot Integration](#8-spring-boot-integration)
9. [Database Support Matrix](#9-database-support-matrix)
10. [Testing Strategy](#10-testing-strategy)
11. [Migration Path](#11-migration-path)
12. [Risks & Mitigations](#12-risks--mitigations)
13. [Open Questions](#13-open-questions)
14. [Appendices](#14-appendices)

---

## 1. Executive Summary

### 1.1 Goal
Add R2DBC (Reactive Relational Database Connectivity) support to Flyway, enabling reactive applications to perform database migrations without requiring a JDBC driver.

### 1.2 Key Benefits
- **Reactive Native**: Direct integration with R2DBC `ConnectionFactory` - no need for blocking JDBC drivers
- **Resource Efficiency**: Leverage non-blocking I/O for migration execution in reactive applications
- **Spring WebFlux Compatibility**: Native support for Spring Boot WebFlux applications
- **Reduced Dependencies**: Eliminate JDBC driver requirement for purely reactive applications

### 1.3 Approach
Leverage Flyway's existing **Native Connectors (NC)** architecture, which already provides a plugin-based approach for non-JDBC database connections (e.g., MongoDB). R2DBC will be implemented as a new Native Connector database type.

---

## 2. Background & Motivation

### 2.1 Current State
Flyway currently supports two connection paradigms:

1. **Traditional JDBC Path** (`flyway-core`)
   - Uses `javax.sql.DataSource` and `java.sql.Connection`
   - Tightly coupled to JDBC APIs
   - Blocking/synchronous execution

2. **Native Connectors Path** (`flyway-nc`)
   - Plugin-based architecture for non-JDBC databases
   - Examples: MongoDB (API-based), SQLite (JDBC-based NC)
   - Supports both JDBC and non-JDBC execution strategies

### 2.2 Problem Statement
Reactive applications using R2DBC currently face these challenges:
- Must include both R2DBC and JDBC drivers
- Flyway blocks the event loop during migrations
- Configuration complexity (two connection pools)
- Inconsistent with reactive programming model

### 2.3 Market Demand
- Spring Boot 3.x emphasizes reactive programming with WebFlux
- Major cloud databases support R2DBC (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle)
- Growing adoption of reactive frameworks (Quarkus, Micronaut)

---

## 3. Architecture Analysis

### 3.1 Existing Native Connectors Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              flyway-core                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│  NativeConnectorsDatabase<T>        │  AbstractNativeConnectorsDatabase<T>  │
│  (sealed interface)                 │  (abstract non-sealed class)          │
│                                     │                                        │
│  - supportsUrl(String)              │  - batch: ArrayList<T>                │
│  - initialize(env, config)          │  - metaData: MetaData                 │
│  - doExecute(T, outputResults)      │  - currentSchema: String              │
│  - getDatabaseMetaData()            │  - addToBatch(T)                      │
│  - createSchemaHistoryTable()       │  - getBatchSize()                     │
│  - getSchemaHistoryModel()          │  - getCurrentSchema()                 │
│  - appendSchemaHistoryItem()        │                                        │
│  - startTransaction()               │                                        │
│  - commitTransaction()              │                                        │
│  - rollbackTransaction()            │                                        │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    │                                   │
                    ▼                                   ▼
┌─────────────────────────────────┐   ┌─────────────────────────────────────┐
│        flyway-nc-core           │   │     flyway-database-nc-mongodb      │
├─────────────────────────────────┤   ├─────────────────────────────────────┤
│  NativeConnectorsJdbc           │   │  MongoDBDatabase                    │
│  (extends AbstractNC<String>)   │   │  (extends NativeConnectorsNonJdbc)  │
│                                 │   │                                     │
│  - connection: Connection       │   │  - mongoClient: MongoClient         │
│  - doExecute(String sql)        │   │  - mongoDatabase: MongoDatabase     │
│  - Uses JDBC under the hood     │   │  - Uses MongoDB API directly        │
├─────────────────────────────────┤   └─────────────────────────────────────┘
│  NativeConnectorsNonJdbc        │
│  (extends AbstractNC<NonJdbc>)  │
│                                 │
│  - For non-JDBC connections     │
│  - transactionAsBatch() support │
└─────────────────────────────────┘
```

### 3.2 Key Extension Points

| Interface/Class | Purpose | R2DBC Relevance |
|----------------|---------|-----------------|
| `NativeConnectorsDatabase<T>` | Core interface for all NC databases | R2DBC will implement this |
| `AbstractNativeConnectorsDatabase<T>` | Base implementation with common logic | R2DBC base will extend this |
| `VerbExtension` | Command implementation (migrate, info, etc.) | Reuse existing verb extensions |
| `Plugin` | SPI registration mechanism | Register R2DBC databases |
| `NativeConnectorsModeUtils` | Determines when to use NC vs JDBC | Must recognize R2DBC URLs |

### 3.3 Execution Unit Pattern

Native Connectors use a generic type `T` for execution units:
- **JDBC-based NC**: `T = String` (raw SQL)
- **Non-JDBC NC**: `T = NonJdbcExecutorExecutionUnit` (script + metadata)
- **R2DBC**: `T = R2dbcExecutionUnit` (SQL + binding parameters)

---

## 4. Proposed Solution

### 4.1 High-Level Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          NEW: flyway-r2dbc-core                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  R2dbcExecutionUnit                                                  │   │
│  │  - sql: String                                                       │   │
│  │  - parameters: Map<String, Object> (for prepared statements)         │   │
│  │  - executeInTransaction: boolean                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  NativeConnectorsR2dbc (abstract)                                    │   │
│  │  extends AbstractNativeConnectorsDatabase<R2dbcExecutionUnit>        │   │
│  │                                                                       │   │
│  │  - connectionFactory: io.r2dbc.spi.ConnectionFactory                 │   │
│  │  - connection: io.r2dbc.spi.Connection                               │   │
│  │  - blockingTimeout: Duration (configurable)                          │   │
│  │                                                                       │   │
│  │  + initialize(env, config)     // Create R2DBC connection            │   │
│  │  + doExecute(unit, output)     // Execute via R2DBC                  │   │
│  │  + startTransaction()          // connection.beginTransaction()      │   │
│  │  + commitTransaction()         // connection.commitTransaction()     │   │
│  │  + rollbackTransaction()       // connection.rollbackTransaction()   │   │
│  │  + close()                     // Close R2DBC connection             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  R2dbcConnectionFactoryProvider (interface)                          │   │
│  │  - For custom ConnectionFactory injection (Spring Boot integration)  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
┌───────────────────┐   ┌───────────────────┐   ┌───────────────────┐
│ flyway-r2dbc-     │   │ flyway-r2dbc-     │   │ flyway-r2dbc-     │
│ postgresql        │   │ mysql             │   │ mariadb           │
├───────────────────┤   ├───────────────────┤   ├───────────────────┤
│ R2dbcPostgres     │   │ R2dbcMysql        │   │ R2dbcMariadb      │
│ Database          │   │ Database          │   │ Database          │
│                   │   │                   │   │                   │
│ - URL pattern:    │   │ - URL pattern:    │   │ - URL pattern:    │
│   r2dbc:postgres  │   │   r2dbc:mysql     │   │   r2dbc:mariadb   │
│   r2dbc:postgresql│   │   r2dbc:pool:mysql│   │   r2dbc:pool:maria│
│                   │   │                   │   │                   │
│ - Reuses existing │   │ - Reuses existing │   │ - Reuses existing │
│   PostgreSQL      │   │   MySQL parser    │   │   MariaDB parser  │
│   parser          │   │                   │   │                   │
└───────────────────┘   └───────────────────┘   └───────────────────┘
```

### 4.2 Blocking Strategy

Since Flyway is fundamentally synchronous and R2DBC is reactive, we need a bridging strategy:

```java
/**
 * Blocking wrapper for R2DBC reactive operations.
 * Uses a configurable timeout to prevent indefinite blocking.
 */
public abstract class NativeConnectorsR2dbc extends AbstractNativeConnectorsDatabase<R2dbcExecutionUnit> {
    
    protected Duration blockingTimeout = Duration.ofMinutes(5);
    
    /**
     * Execute a Mono and block for the result.
     * Handles the reactive-to-imperative bridge.
     */
    protected <T> T block(Mono<T> mono) {
        return mono.block(blockingTimeout);
    }
    
    /**
     * Execute a Flux and collect to a list, blocking for completion.
     */
    protected <T> List<T> blockMany(Flux<T> flux) {
        return flux.collectList().block(blockingTimeout);
    }
    
    @Override
    public void doExecute(R2dbcExecutionUnit executionUnit, boolean outputQueryResults) {
        Statement statement = connection.createStatement(executionUnit.getSql());
        
        // Bind any parameters
        executionUnit.getParameters().forEach(statement::bind);
        
        // Execute and block
        List<Result> results = blockMany(Flux.from(statement.execute()));
        
        if (outputQueryResults) {
            processResults(results);
        }
    }
}
```

### 4.3 URL Detection Strategy

R2DBC uses a distinct URL format that makes detection straightforward:

| Protocol | Example URL |
|----------|-------------|
| PostgreSQL | `r2dbc:postgresql://localhost:5432/mydb` |
| MySQL | `r2dbc:mysql://localhost:3306/mydb` |
| MariaDB | `r2dbc:mariadb://localhost:3306/mydb` |
| SQL Server | `r2dbc:mssql://localhost:1433/mydb` |
| Oracle | `r2dbc:oracle://localhost:1521/mydb` |
| H2 | `r2dbc:h2:mem:///testdb` |
| Pool wrapper | `r2dbc:pool:postgresql://localhost/db` |

```java
@Override
public DatabaseSupport supportsUrl(String url) {
    if (url.startsWith("r2dbc:postgresql:") || 
        url.startsWith("r2dbc:postgres:") ||
        url.startsWith("r2dbc:pool:postgresql:") ||
        url.startsWith("r2dbc:pool:postgres:")) {
        return new DatabaseSupport(true, 1);
    }
    return new DatabaseSupport(false, 0);
}
```

---

## 5. Module Structure

### 5.1 New Modules

```
flyway/
├── flyway-r2dbc/                           # NEW: Parent module for R2DBC
│   ├── pom.xml
│   ├── flyway-r2dbc-core/                  # Core R2DBC abstractions
│   │   ├── pom.xml
│   │   └── src/main/java/org/flywaydb/r2dbc/
│   │       ├── NativeConnectorsR2dbc.java
│   │       ├── R2dbcExecutionUnit.java
│   │       ├── R2dbcConnectionFactoryProvider.java
│   │       ├── R2dbcDatabaseMetadata.java
│   │       └── utils/
│   │           ├── R2dbcUtils.java
│   │           └── R2dbcResultProcessor.java
│   │
│   ├── flyway-r2dbc-postgresql/            # PostgreSQL R2DBC support
│   │   ├── pom.xml
│   │   └── src/main/java/org/flywaydb/r2dbc/postgresql/
│   │       └── R2dbcPostgresDatabase.java
│   │
│   ├── flyway-r2dbc-mysql/                 # MySQL R2DBC support
│   │   ├── pom.xml
│   │   └── src/main/java/org/flywaydb/r2dbc/mysql/
│   │       └── R2dbcMysqlDatabase.java
│   │
│   ├── flyway-r2dbc-mariadb/               # MariaDB R2DBC support
│   │   ├── pom.xml
│   │   └── src/main/java/org/flywaydb/r2dbc/mariadb/
│   │       └── R2dbcMariadbDatabase.java
│   │
│   ├── flyway-r2dbc-mssql/                 # SQL Server R2DBC support
│   │   ├── pom.xml
│   │   └── src/main/java/org/flywaydb/r2dbc/mssql/
│   │       └── R2dbcMssqlDatabase.java
│   │
│   ├── flyway-r2dbc-oracle/                # Oracle R2DBC support
│   │   ├── pom.xml
│   │   └── src/main/java/org/flywaydb/r2dbc/oracle/
│   │       └── R2dbcOracleDatabase.java
│   │
│   └── flyway-r2dbc-h2/                    # H2 R2DBC support
│       ├── pom.xml
│       └── src/main/java/org/flywaydb/r2dbc/h2/
│           └── R2dbcH2Database.java
│
├── flyway-spring-boot-r2dbc/               # NEW: Spring Boot R2DBC AutoConfig
│   ├── pom.xml
│   └── src/main/java/org/flywaydb/spring/boot/r2dbc/
│       ├── FlywayR2dbcAutoConfiguration.java
│       ├── FlywayR2dbcProperties.java
│       └── R2dbcConnectionFactoryAdapter.java
```

### 5.2 Module Dependencies

```xml
<!-- flyway-r2dbc-core/pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-nc-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-spi</artifactId>
        <version>${r2dbc-spi.version}</version>
    </dependency>
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
        <version>${reactor.version}</version>
    </dependency>
</dependencies>

<!-- flyway-r2dbc-postgresql/pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-r2dbc-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
        <!-- For parser reuse -->
    </dependency>
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-postgresql</artifactId>
        <version>${r2dbc-postgresql.version}</version>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 5.3 Version Properties

```xml
<!-- Parent pom.xml additions -->
<properties>
    <r2dbc-spi.version>1.0.0.RELEASE</r2dbc-spi.version>
    <r2dbc-postgresql.version>1.0.4.RELEASE</r2dbc-postgresql.version>
    <r2dbc-mysql.version>1.1.0</r2dbc-mysql.version>
    <r2dbc-mariadb.version>1.1.4</r2dbc-mariadb.version>
    <r2dbc-mssql.version>1.0.2.RELEASE</r2dbc-mssql.version>
    <r2dbc-oracle.version>1.2.0</r2dbc-oracle.version>
    <r2dbc-h2.version>1.0.0.RELEASE</r2dbc-h2.version>
    <r2dbc-pool.version>1.0.1.RELEASE</r2dbc-pool.version>
    <reactor.version>3.6.0</reactor.version>
</properties>
```

---

## 6. Implementation Phases

### Phase 1: Core Infrastructure (Week 1-2)

#### 6.1.1 Deliverables
- [ ] `flyway-r2dbc-core` module with Maven configuration
- [ ] `R2dbcExecutionUnit` class
- [ ] `NativeConnectorsR2dbc` abstract base class
- [ ] `R2dbcUtils` utility class for common operations
- [ ] Unit tests for core classes

#### 6.1.2 Key Implementation Details

```java
// R2dbcExecutionUnit.java
@AllArgsConstructor
@Getter
public class R2dbcExecutionUnit {
    private final String sql;
    private final Map<String, Object> parameters;
    private final boolean executeInTransaction;
    
    public R2dbcExecutionUnit(String sql) {
        this(sql, Collections.emptyMap(), true);
    }
}
```

```java
// NativeConnectorsR2dbc.java (partial)
public abstract class NativeConnectorsR2dbc 
    extends AbstractNativeConnectorsDatabase<R2dbcExecutionUnit> {
    
    protected ConnectionFactory connectionFactory;
    protected Connection connection;
    protected Duration blockingTimeout = Duration.ofMinutes(5);
    protected ConnectionType connectionType = ConnectionType.API;
    
    @Override
    public void initialize(ResolvedEnvironment environment, Configuration configuration) {
        logPreviewFeature("R2DBC Native Connectors for " + getDatabaseType());
        
        String url = environment.getUrl();
        ConnectionFactoryOptions options = ConnectionFactoryOptions.parse(url);
        
        // Add credentials if provided separately
        if (environment.getUser() != null) {
            options = ConnectionFactoryOptions.builder()
                .from(options)
                .option(ConnectionFactoryOptions.USER, environment.getUser())
                .option(ConnectionFactoryOptions.PASSWORD, environment.getPassword())
                .build();
        }
        
        connectionFactory = ConnectionFactories.get(options);
        connection = Mono.from(connectionFactory.create()).block(blockingTimeout);
        
        currentSchema = getDefaultSchema(configuration);
        metaData = getDatabaseMetaData();
    }
    
    @Override
    public void doExecute(R2dbcExecutionUnit executionUnit, boolean outputQueryResults) {
        Statement statement = connection.createStatement(executionUnit.getSql());
        
        // Bind parameters if present
        executionUnit.getParameters().forEach((key, value) -> {
            if (value == null) {
                statement.bindNull(key, Object.class);
            } else {
                statement.bind(key, value);
            }
        });
        
        List<? extends Result> results = Flux.from(statement.execute())
            .collectList()
            .block(blockingTimeout);
        
        if (outputQueryResults && results != null) {
            processResults(results);
        }
    }
    
    @Override
    public void startTransaction() {
        Mono.from(connection.beginTransaction()).block(blockingTimeout);
    }
    
    @Override
    public void commitTransaction() {
        Mono.from(connection.commitTransaction()).block(blockingTimeout);
    }
    
    @Override
    public void rollbackTransaction() {
        Mono.from(connection.rollbackTransaction()).block(blockingTimeout);
    }
    
    @Override
    public boolean isClosed() {
        return connection == null || !connection.isAutoCommit(); // R2DBC doesn't have isClosed()
    }
    
    @Override
    public void close() {
        if (connection != null) {
            Mono.from(connection.close()).block(blockingTimeout);
        }
    }
}
```

### Phase 2: PostgreSQL Support (Week 2-3)

#### 6.2.1 Deliverables
- [ ] `flyway-r2dbc-postgresql` module
- [ ] `R2dbcPostgresDatabase` implementation
- [ ] Schema history table operations for PostgreSQL
- [ ] Integration tests with Testcontainers

#### 6.2.2 Key Implementation Details

```java
// R2dbcPostgresDatabase.java
public class R2dbcPostgresDatabase extends NativeConnectorsR2dbc {
    
    @Override
    public DatabaseSupport supportsUrl(String url) {
        if (url.startsWith("r2dbc:postgresql:") || 
            url.startsWith("r2dbc:postgres:") ||
            url.startsWith("r2dbc:pool:postgresql:") ||
            url.startsWith("r2dbc:pool:postgres:")) {
            return new DatabaseSupport(true, 1);
        }
        return new DatabaseSupport(false, 0);
    }
    
    @Override
    public List<String> supportedVerbs() {
        return List.of("info", "validate", "migrate", "clean", "undo", "baseline", "repair", "testConnection");
    }
    
    @Override
    public boolean isOnByDefault(Configuration configuration) {
        // Auto-enable when R2DBC URL is detected
        return true;
    }
    
    @Override
    public String getDatabaseType() {
        return "PostgreSQL";
    }
    
    @Override
    public boolean supportsTransactions() {
        return true;
    }
    
    @Override
    public boolean supportsBatch() {
        return true;
    }
    
    @Override
    public BiFunction<Configuration, ParsingContext, Parser> getParser() {
        // Reuse the existing PostgreSQL parser
        return PostgreSQLParser::new;
    }
    
    @Override
    public void createSchemaHistoryTable(Configuration configuration) {
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
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
                CONSTRAINT "%s_pk" PRIMARY KEY ("installed_rank")
            )
            """.formatted(
                quote(getCurrentSchema(), configuration.getTable()),
                configuration.getTable()
            );
        
        doExecute(new R2dbcExecutionUnit(sql), false);
    }
    
    @Override
    public MetaData getDatabaseMetaData() {
        if (metaData != null) {
            return metaData;
        }
        
        // Query version info via R2DBC
        String versionSql = "SELECT version()";
        Statement stmt = connection.createStatement(versionSql);
        
        String version = Flux.from(stmt.execute())
            .flatMap(result -> result.map((row, meta) -> row.get(0, String.class)))
            .blockFirst(blockingTimeout);
        
        // Parse version string "PostgreSQL 15.2 on ..."
        String productVersion = version.split(" ")[1];
        
        return new MetaData(
            getDatabaseType(),
            "PostgreSQL",
            new DatabaseVersionImpl(productVersion),
            productVersion,
            getCurrentSchema(),
            connectionType
        );
    }
    
    @Override
    public String getCurrentUser() {
        String sql = "SELECT current_user";
        Statement stmt = connection.createStatement(sql);
        
        return Flux.from(stmt.execute())
            .flatMap(result -> result.map((row, meta) -> row.get(0, String.class)))
            .blockFirst(blockingTimeout);
    }
    
    @Override
    public boolean isSchemaExists(String schema) {
        String sql = "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = $1)";
        Statement stmt = connection.createStatement(sql);
        stmt.bind("$1", schema);
        
        return Boolean.TRUE.equals(
            Flux.from(stmt.execute())
                .flatMap(result -> result.map((row, meta) -> row.get(0, Boolean.class)))
                .blockFirst(blockingTimeout)
        );
    }
    
    @Override
    public boolean isSchemaEmpty(String schema) {
        String sql = """
            SELECT NOT EXISTS (
                SELECT 1 FROM information_schema.tables 
                WHERE table_schema = $1 
                AND table_type = 'BASE TABLE'
            )
            """;
        Statement stmt = connection.createStatement(sql);
        stmt.bind("$1", schema);
        
        return Boolean.TRUE.equals(
            Flux.from(stmt.execute())
                .flatMap(result -> result.map((row, meta) -> row.get(0, Boolean.class)))
                .blockFirst(blockingTimeout)
        );
    }
    
    @Override
    public void createSchemas(String... schemas) {
        for (String schema : schemas) {
            String sql = "CREATE SCHEMA IF NOT EXISTS " + doQuote(schema);
            doExecute(new R2dbcExecutionUnit(sql), false);
        }
    }
    
    @Override
    protected String getDefaultSchema(Configuration configuration) {
        String configuredSchema = ConfigUtils.getCalculatedDefaultSchema(configuration);
        if (configuredSchema != null) {
            return configuredSchema;
        }
        
        // Query current schema
        String sql = "SELECT current_schema()";
        Statement stmt = connection.createStatement(sql);
        
        return Flux.from(stmt.execute())
            .flatMap(result -> result.map((row, meta) -> row.get(0, String.class)))
            .blockFirst(blockingTimeout);
    }
}
```

### Phase 3: MySQL/MariaDB Support (Week 3-4)

#### 6.3.1 Deliverables
- [ ] `flyway-r2dbc-mysql` module
- [ ] `flyway-r2dbc-mariadb` module
- [ ] Database-specific schema history implementations
- [ ] Integration tests

### Phase 4: SQL Server & Oracle Support (Week 4-5)

#### 6.4.1 Deliverables
- [ ] `flyway-r2dbc-mssql` module
- [ ] `flyway-r2dbc-oracle` module
- [ ] Database-specific implementations
- [ ] Integration tests

### Phase 5: Spring Boot Integration (Week 5-6)

#### 6.5.1 Deliverables
- [ ] `flyway-spring-boot-r2dbc` auto-configuration module
- [ ] Properties support
- [ ] ConnectionFactory injection
- [ ] Migration ordering (before application startup)
- [ ] Documentation

### Phase 6: H2 Support & Testing (Week 6-7)

#### 6.6.1 Deliverables
- [ ] `flyway-r2dbc-h2` module for testing scenarios
- [ ] Comprehensive test suite
- [ ] Performance benchmarks
- [ ] Documentation updates

---

## 7. API Design

### 7.1 Configuration API

```java
// New configuration method in FluentConfiguration
public FluentConfiguration r2dbcConnectionFactory(ConnectionFactory connectionFactory) {
    // Store ConnectionFactory for R2DBC-based migrations
    this.r2dbcConnectionFactory = connectionFactory;
    return this;
}

// Usage example
Flyway flyway = Flyway.configure()
    .r2dbcConnectionFactory(connectionFactory)
    .locations("classpath:db/migration")
    .load();

flyway.migrate();
```

### 7.2 R2DBC URL Configuration

```java
// URL-based configuration (auto-detects R2DBC)
Flyway flyway = Flyway.configure()
    .dataSource("r2dbc:postgresql://localhost:5432/mydb", "user", "password")
    .load();
```

### 7.3 Programmatic ConnectionFactory

```java
// Custom ConnectionFactory
ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
    .option(DRIVER, "postgresql")
    .option(HOST, "localhost")
    .option(PORT, 5432)
    .option(DATABASE, "mydb")
    .option(USER, "user")
    .option(PASSWORD, "password")
    .build();

ConnectionFactory connectionFactory = ConnectionFactories.get(options);

Flyway flyway = Flyway.configure()
    .r2dbcConnectionFactory(connectionFactory)
    .load();
```

### 7.4 Configuration Properties

```properties
# flyway.properties / flyway.toml
flyway.url=r2dbc:postgresql://localhost:5432/mydb
flyway.user=flyway
flyway.password=secret
flyway.r2dbc.blocking-timeout=5m
flyway.r2dbc.pool.enabled=true
flyway.r2dbc.pool.initial-size=5
flyway.r2dbc.pool.max-size=20
```

---

## 8. Spring Boot Integration

### 8.1 Auto-Configuration Design

```java
@Configuration
@ConditionalOnClass({Flyway.class, ConnectionFactory.class})
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(R2dbcAutoConfiguration.class)
@EnableConfigurationProperties(FlywayR2dbcProperties.class)
public class FlywayR2dbcAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConnectionFactory.class)
    public Flyway flyway(
            ConnectionFactory connectionFactory,
            FlywayR2dbcProperties properties,
            ObjectProvider<FlywayConfigurationCustomizer> customizers) {
        
        FluentConfiguration configuration = Flyway.configure()
            .r2dbcConnectionFactory(connectionFactory)
            .locations(properties.getLocations())
            .baselineOnMigrate(properties.isBaselineOnMigrate())
            .validateOnMigrate(properties.isValidateOnMigrate());
        
        // Apply customizers
        customizers.orderedStream()
            .forEach(customizer -> customizer.customize(configuration));
        
        return configuration.load();
    }
    
    @Bean
    @ConditionalOnMissingBean
    public FlywayMigrationInitializer flywayInitializer(
            Flyway flyway,
            ObjectProvider<FlywayMigrationStrategy> migrationStrategy) {
        return new FlywayMigrationInitializer(flyway, migrationStrategy.getIfAvailable());
    }
}
```

### 8.2 Spring Boot Properties

```java
@ConfigurationProperties(prefix = "spring.flyway.r2dbc")
public class FlywayR2dbcProperties {
    
    /**
     * Whether to use R2DBC for migrations instead of JDBC.
     * Auto-detected when only R2DBC ConnectionFactory is available.
     */
    private boolean enabled = true;
    
    /**
     * Timeout for blocking R2DBC operations.
     */
    private Duration blockingTimeout = Duration.ofMinutes(5);
    
    /**
     * Whether to use connection pooling.
     */
    private boolean poolEnabled = true;
    
    /**
     * Initial pool size.
     */
    private int poolInitialSize = 5;
    
    /**
     * Maximum pool size.
     */
    private int poolMaxSize = 20;
    
    // getters and setters
}
```

### 8.3 Application Configuration Examples

#### 8.3.1 application.yml (Basic)

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/mydb
    username: user
    password: secret
    
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

#### 8.3.2 application.yml (Advanced)

```yaml
spring:
  r2dbc:
    url: r2dbc:pool:postgresql://localhost:5432/mydb?initialSize=5&maxSize=20
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    pool:
      enabled: true
      initial-size: 5
      max-size: 20
      max-idle-time: 30m
    
  flyway:
    enabled: true
    locations: 
      - classpath:db/migration
      - classpath:db/specific/${spring.profiles.active}
    baseline-on-migrate: true
    validate-on-migrate: true
    schemas:
      - public
      - app_schema
    r2dbc:
      blocking-timeout: 10m
```

#### 8.3.3 Java Configuration (Custom ConnectionFactory)

```java
@Configuration
public class FlywayR2dbcConfig {
    
    @Bean
    public ConnectionFactory connectionFactory() {
        return new PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host("localhost")
                .port(5432)
                .database("mydb")
                .username("user")
                .password("secret")
                .build()
        );
    }
    
    @Bean
    public FlywayConfigurationCustomizer flywayCustomizer() {
        return configuration -> configuration
            .callbacks(new MyCallback())
            .resolvers(new MyResolver());
    }
}
```

### 8.4 Migration Ordering

```java
/**
 * Ensures Flyway migrations complete before R2DBC repositories are initialized.
 */
@Component
public class FlywayMigrationInitializer implements SmartInitializingSingleton, Ordered {
    
    private final Flyway flyway;
    private final FlywayMigrationStrategy migrationStrategy;
    
    @Override
    public void afterSingletonsInstantiated() {
        if (migrationStrategy != null) {
            migrationStrategy.migrate(flyway);
        } else {
            flyway.migrate();
        }
    }
    
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // Run before repositories
    }
}
```

### 8.5 WebFlux Integration

```java
@RestController
@RequestMapping("/flyway")
public class FlywayController {
    
    private final Flyway flyway;
    
    @GetMapping("/info")
    public Mono<MigrationInfoService> info() {
        return Mono.fromCallable(flyway::info)
            .subscribeOn(Schedulers.boundedElastic());
    }
    
    @PostMapping("/migrate")
    public Mono<MigrateResult> migrate() {
        return Mono.fromCallable(flyway::migrate)
            .subscribeOn(Schedulers.boundedElastic());
    }
}
```

---

## 9. Database Support Matrix

### 9.1 Initial Release (v1.0)

| Database | R2DBC Driver | Parser Reuse | Priority |
|----------|-------------|--------------|----------|
| PostgreSQL | r2dbc-postgresql | PostgreSQLParser | P0 |
| MySQL | r2dbc-mysql | MySQLParser | P0 |
| MariaDB | r2dbc-mariadb | MariaDBParser | P1 |
| H2 | r2dbc-h2 | H2Parser | P1 (testing) |

### 9.2 Future Releases

| Database | R2DBC Driver | Parser Reuse | Priority |
|----------|-------------|--------------|----------|
| SQL Server | r2dbc-mssql | SQLServerParser | P2 |
| Oracle | r2dbc-oracle | OracleParser | P2 |
| CockroachDB | r2dbc-postgresql | CockroachDBParser | P3 |

### 9.3 Feature Support Matrix

| Feature | PostgreSQL | MySQL | MariaDB | H2 | SQL Server | Oracle |
|---------|------------|-------|---------|-----|------------|--------|
| migrate | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| info | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| validate | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| clean | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| baseline | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| repair | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| undo | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| transactions | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| batching | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

---

## 10. Testing Strategy

### 10.1 Unit Tests

```java
class R2dbcExecutionUnitTest {
    @Test
    void shouldCreateWithSqlOnly() {
        R2dbcExecutionUnit unit = new R2dbcExecutionUnit("SELECT 1");
        
        assertThat(unit.getSql()).isEqualTo("SELECT 1");
        assertThat(unit.getParameters()).isEmpty();
        assertThat(unit.isExecuteInTransaction()).isTrue();
    }
    
    @Test
    void shouldCreateWithParameters() {
        R2dbcExecutionUnit unit = new R2dbcExecutionUnit(
            "SELECT * FROM users WHERE id = $1",
            Map.of("$1", 123),
            false
        );
        
        assertThat(unit.getParameters()).containsEntry("$1", 123);
        assertThat(unit.isExecuteInTransaction()).isFalse();
    }
}
```

### 10.2 Integration Tests with Testcontainers

```java
@Testcontainers
class R2dbcPostgresDatabaseIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");
    
    private R2dbcPostgresDatabase database;
    
    @BeforeEach
    void setUp() {
        String r2dbcUrl = String.format(
            "r2dbc:postgresql://%s:%d/%s",
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getDatabaseName()
        );
        
        Configuration config = Flyway.configure()
            .dataSource(r2dbcUrl, postgres.getUsername(), postgres.getPassword())
            .getConfiguration();
        
        ResolvedEnvironment env = new ResolvedEnvironment();
        env.setUrl(r2dbcUrl);
        env.setUser(postgres.getUsername());
        env.setPassword(postgres.getPassword());
        
        database = new R2dbcPostgresDatabase();
        database.initialize(env, config);
    }
    
    @Test
    void shouldConnectAndQueryVersion() {
        MetaData metaData = database.getDatabaseMetaData();
        
        assertThat(metaData.databaseType()).isEqualTo("PostgreSQL");
        assertThat(metaData.version().getMajor()).isGreaterThanOrEqualTo(15);
    }
    
    @Test
    void shouldCreateSchemaHistoryTable() {
        Configuration config = mock(Configuration.class);
        when(config.getTable()).thenReturn("flyway_schema_history");
        
        database.createSchemaHistoryTable(config);
        
        assertThat(database.schemaHistoryTableExists("flyway_schema_history")).isTrue();
    }
    
    @Test
    void shouldExecuteMigration() {
        R2dbcExecutionUnit unit = new R2dbcExecutionUnit(
            "CREATE TABLE test_table (id INT PRIMARY KEY, name VARCHAR(100))"
        );
        
        database.doExecute(unit, false);
        
        // Verify table exists
        R2dbcExecutionUnit verify = new R2dbcExecutionUnit(
            "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'test_table')"
        );
        // ... assertion
    }
    
    @Test
    void shouldHandleTransactions() {
        database.startTransaction();
        
        database.doExecute(new R2dbcExecutionUnit("CREATE TABLE tx_test (id INT)"), false);
        database.doExecute(new R2dbcExecutionUnit("INSERT INTO tx_test VALUES (1)"), false);
        
        database.rollbackTransaction();
        
        // Table should not exist after rollback
        assertThat(database.isSchemaEmpty("public")).isTrue();
    }
}
```

### 10.3 Spring Boot Integration Tests

```java
@SpringBootTest
@Testcontainers
class FlywayR2dbcAutoConfigurationIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format(
            "r2dbc:postgresql://%s:%d/%s",
            postgres.getHost(),
            postgres.getFirstMappedPort(),
            postgres.getDatabaseName()
        ));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }
    
    @Autowired
    private Flyway flyway;
    
    @Autowired
    private ConnectionFactory connectionFactory;
    
    @Test
    void shouldAutoConfigureFlyway() {
        assertThat(flyway).isNotNull();
    }
    
    @Test
    void shouldRunMigrations() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).isNotEmpty();
    }
}
```

### 10.4 Performance Tests

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcPerformanceTest {
    
    @Test
    void shouldCompleteMigrationsWithinTimeout() {
        // 100 migration files
        long startTime = System.currentTimeMillis();
        
        flyway.migrate();
        
        long duration = System.currentTimeMillis() - startTime;
        assertThat(duration).isLessThan(60_000); // 60 seconds max
    }
    
    @Test
    void shouldHandleLargeMigrationScript() {
        // Single migration with 10,000 INSERT statements
        MigrateResult result = flyway.migrate();
        
        assertThat(result.success).isTrue();
    }
}
```

---

## 11. Migration Path

### 11.1 For Existing JDBC Users

No changes required. Existing JDBC-based Flyway configurations continue to work unchanged.

### 11.2 For New R2DBC Users

1. Add the appropriate R2DBC module dependency:
   ```xml
   <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-r2dbc-postgresql</artifactId>
       <version>${flyway.version}</version>
   </dependency>
   ```

2. Configure with R2DBC URL:
   ```properties
   flyway.url=r2dbc:postgresql://localhost:5432/mydb
   ```

3. That's it! Flyway auto-detects R2DBC URLs.

### 11.3 Migrating from JDBC to R2DBC

1. Change dependency from `flyway-database-postgresql` to `flyway-r2dbc-postgresql`
2. Update URL from `jdbc:postgresql://...` to `r2dbc:postgresql://...`
3. Remove JDBC driver dependency if no longer needed

```diff
- <dependency>
-     <groupId>org.flywaydb</groupId>
-     <artifactId>flyway-database-postgresql</artifactId>
- </dependency>
- <dependency>
-     <groupId>org.postgresql</groupId>
-     <artifactId>postgresql</artifactId>
- </dependency>
+ <dependency>
+     <groupId>org.flywaydb</groupId>
+     <artifactId>flyway-r2dbc-postgresql</artifactId>
+ </dependency>
+ <dependency>
+     <groupId>io.r2dbc</groupId>
+     <artifactId>r2dbc-postgresql</artifactId>
+ </dependency>
```

```diff
# application.properties
- spring.datasource.url=jdbc:postgresql://localhost:5432/mydb
+ spring.r2dbc.url=r2dbc:postgresql://localhost:5432/mydb
```

---

## 12. Risks & Mitigations

### 12.1 Risk Matrix

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| R2DBC driver inconsistencies | Medium | High | Extensive testing, driver version matrix |
| Blocking timeout issues | Medium | Medium | Configurable timeouts, clear documentation |
| Memory leaks from unclosed connections | Low | High | Proper resource management, connection validation |
| Parser incompatibilities | Low | Medium | Reuse existing parsers where possible |
| Spring Boot version compatibility | Medium | Medium | Test against multiple Spring Boot versions |
| Performance degradation | Low | Medium | Benchmarking, optimization phase |

### 12.2 Technical Risks

#### 12.2.1 Reactive to Blocking Bridge
**Risk**: Blocking reactive streams can cause thread pool exhaustion.
**Mitigation**: 
- Use bounded elastic scheduler for blocking operations
- Configurable timeout with sensible defaults
- Clear documentation on threading model

#### 12.2.2 Connection Pool Compatibility
**Risk**: R2DBC pool wrappers may behave differently.
**Mitigation**:
- Test with both pooled and non-pooled connections
- Support `r2dbc:pool:` URL prefix
- Document pool configuration options

#### 12.2.3 Metadata Access
**Risk**: R2DBC has limited metadata API compared to JDBC.
**Mitigation**:
- Query system tables directly for metadata
- Cache metadata where appropriate
- Implement database-specific metadata queries

### 12.3 Compatibility Risks

#### 12.3.1 R2DBC SPI Version
**Risk**: Breaking changes in R2DBC SPI.
**Mitigation**: Target stable R2DBC SPI 1.0.0.RELEASE, monitor for updates.

#### 12.3.2 Spring Boot Compatibility
**Risk**: Auto-configuration conflicts with Spring Boot's own Flyway auto-config.
**Mitigation**: Use `@ConditionalOn*` annotations carefully, test exclusion scenarios.

---

## 13. Open Questions

### 13.1 Design Questions

1. **Q**: Should R2DBC support be opt-in or auto-detected?
   **Recommendation**: Auto-detect based on URL pattern (`r2dbc:*`), with explicit opt-out option.

2. **Q**: How to handle mixed JDBC/R2DBC scenarios (e.g., migration uses JDBC features)?
   **Recommendation**: Clearly document that R2DBC mode only supports R2DBC-compatible operations.

3. **Q**: Should we support reactive migration callbacks?
   **Recommendation**: Phase 2 feature - initial release supports blocking callbacks only.

4. **Q**: How to handle database-specific R2DBC extensions (e.g., PostgreSQL LISTEN/NOTIFY)?
   **Recommendation**: Not supported in initial release, add as database-specific features later.

### 13.2 Implementation Questions

1. **Q**: Where should `flyway-r2dbc-*` modules live in the repository?
   **Options**:
   - Under `flyway-database/` (alongside JDBC modules)
   - New top-level `flyway-r2dbc/` directory
   **Recommendation**: New top-level `flyway-r2dbc/` for cleaner separation.

2. **Q**: Should R2DBC modules depend on JDBC modules for parser reuse?
   **Recommendation**: Yes, parsers are connection-agnostic and can be reused.

3. **Q**: How to handle schema history table for databases without native R2DBC timestamp support?
   **Recommendation**: Use database-specific date/time functions in SQL.

### 13.3 Testing Questions

1. **Q**: Which R2DBC driver versions to test against?
   **Recommendation**: Latest stable version + one previous major version.

2. **Q**: How to test connection pooling behavior?
   **Recommendation**: Use r2dbc-pool in integration tests, verify connection reuse.

---

## 14. Appendices

### 14.1 Glossary

| Term | Definition |
|------|------------|
| R2DBC | Reactive Relational Database Connectivity - reactive API for SQL databases |
| Native Connectors (NC) | Flyway's plugin architecture for non-JDBC databases |
| ConnectionFactory | R2DBC's equivalent of JDBC's DataSource |
| Blocking Bridge | Pattern of converting reactive streams to blocking calls |

### 14.2 References

- [R2DBC Specification](https://r2dbc.io/spec/1.0.0.RELEASE/spec/html/)
- [R2DBC Drivers](https://r2dbc.io/drivers/)
- [Spring Data R2DBC](https://spring.io/projects/spring-data-r2dbc)
- [Flyway Native Connectors](https://documentation.red-gate.com/flyway/)
- [Project Reactor](https://projectreactor.io/)

### 14.3 Code Examples

#### Complete Migration Example

```java
// V1__Create_users_table.sql (works with both JDBC and R2DBC)
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
```

#### Java Migration with R2DBC

```java
// V2__Seed_admin_user.java
public class V2__Seed_admin_user implements JavaMigration {
    
    @Override
    public void migrate(Context context) throws Exception {
        // Note: context.getConnection() returns null for R2DBC
        // Use SQL-based migrations for R2DBC compatibility
    }
    
    // For R2DBC, prefer SQL migrations over Java migrations
}
```

### 14.4 Configuration Reference

#### Complete Configuration Options

```properties
# Core R2DBC Configuration
flyway.url=r2dbc:postgresql://localhost:5432/mydb
flyway.user=flyway_user
flyway.password=secret

# R2DBC-specific Options
flyway.r2dbc.enabled=true
flyway.r2dbc.blocking-timeout=5m
flyway.r2dbc.connection-timeout=30s

# Pool Configuration (when using r2dbc:pool:*)
flyway.r2dbc.pool.enabled=true
flyway.r2dbc.pool.initial-size=5
flyway.r2dbc.pool.max-size=20
flyway.r2dbc.pool.max-idle-time=30m
flyway.r2dbc.pool.validation-query=SELECT 1

# Standard Flyway Options (unchanged)
flyway.locations=classpath:db/migration
flyway.schemas=public,app
flyway.table=flyway_schema_history
flyway.baseline-on-migrate=false
flyway.validate-on-migrate=true
flyway.clean-disabled=true
```

### 14.5 File Structure Template

```
flyway-r2dbc-postgresql/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── org/flywaydb/r2dbc/postgresql/
│   │   │       └── R2dbcPostgresDatabase.java
│   │   └── resources/
│   │       └── META-INF/
│   │           └── services/
│   │               └── org.flywaydb.core.extensibility.Plugin
│   └── test/
│       └── java/
│           └── org/flywaydb/r2dbc/postgresql/
│               ├── R2dbcPostgresDatabaseTest.java
│               └── R2dbcPostgresIntegrationTest.java
```

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2026-02-27 | Planning | Initial draft |

---

**Next Steps:**
1. Review and approve this planning document
2. Create GitHub issues for each implementation phase
3. Set up module structure and CI/CD pipelines
4. Begin Phase 1 implementation
