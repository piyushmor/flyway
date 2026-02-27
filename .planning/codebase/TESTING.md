# Testing Patterns

**Analysis Date:** 2026-02-27

## Overview

This is the open-source (OSS) portion of Flyway. The repository contains **no test source files** (`src/test/java` directories are absent from all modules). Unit tests, integration tests, and end-to-end tests live in a private Redgate repository and are run against this codebase externally.

This document describes:
1. The test infrastructure declared in the build system (dependencies, versions, tools)
2. Testing patterns inferred from production code design
3. Docker-based functional tests present in `flyway-docker/`

## Test Dependencies Declared (pom.xml)

All test-capable dependencies are declared in the parent `pom.xml` at `/Users/pmor/IdeaProjects/OSS/flyway/pom.xml`. They are available but no test code exists in this repo.

**Test Framework:**
- JUnit Jupiter (via Testcontainers' `junit-jupiter` integration): `org.testcontainers:junit-jupiter:1.21.4`
- JUnit version property: `version.junit = 6.0.1` (declared but not directly referenced in a dependency — likely used by private test module)
- Hamcrest: `2.2` (declared as a version property, assertion library)

**Mocking:**
- Mockito: `5.18.0` (`org.mockito` — version declared in `version.mockito`)

**Integration/E2E Test Infrastructure:**
- Testcontainers: `1.21.4` (with modules for PostgreSQL, DB2, MariaDB, CockroachDB)
- `org.testcontainers:junit-jupiter` — JUnit 5 integration
- MockServer: `5.15.0` (`org.mock-server:mockserver-client-java`, `mockserver-junit-jupiter`)
- System Stubs: `2.1.8` (`uk.org.webcompere:system-stubs-core`, `system-stubs-jupiter`) — for env var/system property mocking

**Run Commands:**
```bash
mvn test                    # Run unit tests (skipped - no tests in this repo)
mvn verify                  # Run integration tests
mvn verify -DskipTests      # Build without tests
mvn verify -DskipTests -DskipITs   # Build without any tests
mvn clean install -Plombok-javadoc -DskipTests -DskipITs -ntp  # Build for release
```

## TestContainersDatabaseType

The production code includes `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/TestContainersDatabaseType.java` — a `BaseDatabaseType` implementation with name `"Testcontainers"`. This is a **production** class that enables Flyway to work with Testcontainers-provisioned databases in test environments (and end-user CI pipelines), not a test-only class.

```java
public class TestContainersDatabaseType extends BaseDatabaseType {
    @Override
    public String getName() {
        return "Testcontainers";
    }
}
```

Registered in `META-INF/services/org.flywaydb.core.extensibility.Plugin` so it's discovered via `ServiceLoader`.

## Docker-Based Functional Tests

`flyway-docker/` contains Python scripts for running functional tests against built Docker images.

**Location:** `flyway-docker/scripts/test_images.py`

**What they test:** Docker image variants (alpine, azure, oracle, mongo) running actual Flyway commands (`info`, `migrate`, `clean`, `check -code`, `check -changes`) against real databases.

**Test SQL:** `flyway-docker/test-sql/` contains:
- `V1__table.sql` — SQL migration
- `V1__first.js` — JavaScript migration (for MongoDB)

**Run pattern (from test_images.py):**
```python
flyway_commands = ["info", "migrate", "clean"]
if edition == "redgate":
    flyway_commands += ["check -code", "check -changes"]
```

**Environment requirements:** `MONGO_CONNECTION_DETAILS` must be set for MongoDB tests.

## Design Patterns That Support Testing

Although tests are not in this repo, the production code is designed with testability in mind:

### Template Method Pattern (Isolation of DB-Specific Logic)

```java
// flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Schema.java
public boolean exists() {
    try {
        return doExists();      // abstract — overridden per DB
    } catch (SQLException e) {
        throw new FlywaySqlException("...", e);
    }
}
protected abstract boolean doExists() throws SQLException;
```

This makes it easy to test `doExists()` on a real or in-memory DB, or mock the `JdbcTemplate` at that layer.

### Plugin/SPI Architecture (Mockable Plugins)

`PluginRegister` loads all plugins via `ServiceLoader`. In test configurations, implementations can be substituted by registering test doubles in `META-INF/services/org.flywaydb.core.extensibility.Plugin`.

### Configuration Immutability (Easy Test Setup)

`ClassicConfiguration` takes a `ConfigurationModel` in its constructor; tests can create `ConfigurationModel` directly and pass it in without environment setup.

### JdbcTemplate Abstraction

`flyway-core/src/main/java/org/flywaydb/core/internal/jdbc/JdbcTemplate.java` wraps raw JDBC and can be mocked to avoid real database connections in unit tests. It accepts a `java.sql.Connection` in the constructor.

### Stub Implementations

`flyway-core/src/main/java/org/flywaydb/core/internal/proprietaryStubs/` provides null-object/stub implementations of all proprietary command extensions. These work as test-safe substitutes for proprietary features:
- `CheckCommandExtensionStub`
- `DiffCommandExtensionStub`
- `UndoCommandExtensionStub`
- etc.

### Record Types for Test Data

Simple data types use Java `record` or Lombok `@Value`+`@Builder`, making them easy to construct in test assertions:

```java
// flyway-core/src/main/java/org/flywaydb/core/internal/nc/schemahistory/SchemaHistoryItem.java
SchemaHistoryItem item = SchemaHistoryItem.builder()
    .installedRank(1)
    .version("1")
    .description("First migration")
    .type("SQL")
    .script("V1__init.sql")
    .installedBy("flyway")
    .success(true)
    .build();
```

## Test Infrastructure for Integration Tests

Based on declared dependencies, the private test suite likely uses:

**Testcontainers pattern (inferred):**
```java
@Testcontainers
@ExtendWith(...)
class PostgreSQLMigrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");

    @BeforeEach
    void setUp() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .load()
            .migrate();
    }
}
```

**MockServer pattern (inferred, for HTTP-based integrations):**
```java
@ExtendWith(MockServerExtension.class)
class VaultConfigResolverTest {
    @MockServerClient
    private MockServerClient mockServerClient;
    // configure expectations, then test resolver
}
```

**System Stubs pattern (inferred, for env var injection):**
```java
@ExtendWith(SystemStubsExtension.class)
class ConfigUtilsTest {
    @SystemStub
    private EnvironmentVariables environmentVariables;
    // set env vars and test config loading
}
```

## Build Infrastructure for Tests

**Java version:** 17 (compiler target)

**Maven Surefire:** Not explicitly configured in parent `pom.xml` (no `maven-surefire-plugin` declaration present). JUnit Jupiter tests run via default Surefire discovery when the plugin is on the classpath.

**Profiles:**
- `-DskipTests` — skips unit tests
- `-DskipITs` — skips integration tests
- `-Pbuild-assemblies-{platform}` — builds OS-specific CLI assemblies (tested separately)

## Coverage

**Requirements:** No coverage thresholds enforced in the OSS build configuration. Coverage tooling (JaCoCo, etc.) not present in the parent `pom.xml`.

## Test Naming Conventions (Inferred)

Based on version property `version.junit = 6.0.1` (JUnit Jupiter 6.x is a future/planned version), the test suite likely uses JUnit Jupiter (`@Test`, `@BeforeEach`, `@AfterEach`, `@DisplayName`, `@ParameterizedTest`).

**Expected test file naming:**
- `{ClassUnderTest}Test.java` — unit tests
- `{Feature}IT.java` — integration tests requiring a database
- Tests located in corresponding `src/test/java` structure in the private repo, mirroring `src/main/java` package structure

## What to Know When Adding New Code

1. **No test source in this repo** — test coverage is managed externally. New code added here will be tested in the private repository.

2. **Design for testability:** Follow the existing template method pattern — put JDBC-throwing logic in `protected abstract do*()` methods and wrap with try/catch in public methods.

3. **Use constructor injection** with `@RequiredArgsConstructor` or explicit constructors so dependencies can be injected in tests.

4. **Avoid static state** in new code. `LogFactory` uses static state with `@Synchronized` methods — this is an established pattern but creates test isolation challenges.

5. **Register new Plugin implementations** in the appropriate module's `META-INF/services/org.flywaydb.core.extensibility.Plugin` file.

---

*Testing analysis: 2026-02-27*
