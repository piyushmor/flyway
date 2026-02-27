# Coding Conventions

**Analysis Date:** 2026-02-27

## File Header

Every Java source file begins with an Apache License 2.0 header, automatically managed by `license-maven-plugin`. The header uses a standard block format:

```java
/*-
 * ========================LICENSE_START=================================
 * flyway-core
 * ========================================================================
 * Copyright (C) 2010 - 2026 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 * =========================LICENSE_END==================================
 */
```

The `process-sources` Maven phase regenerates these headers automatically. Do not skip this step.

## Naming Patterns

**Files:**
- Classes: `PascalCase` — `H2Database.java`, `ConfigUtils.java`, `PluginRegister.java`
- Database-specific classes: `{Database}{Role}` — `H2Database`, `H2Schema`, `H2Connection`, `H2Table`, `H2Parser`
- Interface stubs (proprietaryStubs package): `{Feature}CommandExtensionStub` — `CheckCommandExtensionStub.java`
- Configuration model classes: `{Domain}Model` — `EnvironmentModel.java`, `FlywayModel.java`, `ConfigurationModel.java`
- Exception classes: `Flyway{Context}Exception` — `FlywayException`, `FlywaySqlException`, `FlywayDbUpgradeRequiredException`

**Packages:**
- Public API: `org.flywaydb.core.api.*`
- Internal (private API, no guarantees): `org.flywaydb.core.internal.*`
- Extensibility hooks: `org.flywaydb.core.extensibility.*`
- Database-specific: `org.flywaydb.database.{dbname}.*` (in separate modules)
- `package-info.java` files in `internal.*` packages explicitly state: "Private API. No compatibility guarantees provided."

**Methods:**
- Public API methods: `camelCase` — `migrate()`, `validate()`, `ensureSupported()`
- Abstract do-methods (template pattern): `do{Operation}` — `doExists()`, `doEmpty()`, `doGetConnection()`, `doGetCurrentUser()`
- Factory/static methods: `camelCase` — `getLog()`, `fromVersion()`

**Constants:**
- `SCREAMING_SNAKE_CASE` for `static final` fields — `APPLICATION_NAME`, `DEFAULT_USER`, `DUMMY_SCRIPT_NAME`
- Config key constants in utility classes: `SCREAMING_SNAKE_CASE` strings — `BASELINE_DESCRIPTION`, `CONNECT_RETRIES`
- Constants for database hosting environments in `DatabaseConstants.java`: `DATABASE_HOSTING_{ENVIRONMENT}`

**Variables:**
- Local variables: `camelCase` — `bucketName`, `sqlState`, `connectionString`
- `final var` used for local variable type inference, especially for closeable resources and chained method results

## Java Features Used

**Java Version:** 17 (set in `maven-compiler-plugin` with `<release>17</release>`)

**Records** — used for simple immutable data carriers:
```java
// flyway-core/src/main/java/org/flywaydb/core/internal/jdbc/Result.java
public record Result(long updateCount, List<String> columns, List<List<String>> data, String sql){}

// flyway-core/src/main/java/org/flywaydb/core/internal/nc/schemahistory/SchemaHistoryItem.java
// (uses @Value/@Builder instead - both styles exist)
```

**Sealed interfaces** — used for extensibility contracts with controlled implementations:
```java
// flyway-core/src/main/java/org/flywaydb/core/internal/nc/NativeConnectorsDatabase.java
public sealed interface NativeConnectorsDatabase<T> extends Plugin, AutoCloseable permits
    AbstractNativeConnectorsDatabase,
    AbstractNativeConnectorsHybridDatabase { ... }
```

**Pattern matching instanceof** — used consistently throughout:
```java
// flyway-core/src/main/java/org/flywaydb/core/internal/jdbc/JdbcTemplate.java
} else if (parameterValue instanceof final Integer integerValue) {
} else if (parameterValue instanceof final Boolean booleanValue) {
} else if (parameterValue instanceof final String stringValue) {

// flyway-core/src/main/java/org/flywaydb/core/internal/exception/FlywaySqlException.java
if (!(dataSource instanceof final DriverDataSource driverDataSource)) {
    return "";
}
```

**Switch expressions** — used in log framework selection:
```java
// flyway-core/src/main/java/org/flywaydb/core/api/logging/LogFactory.java
return new MultiLogCreator(Arrays.stream(configuration.getLoggers()).map(logger -> switch (logger) {
    case "auto" -> autoDetectLogCreator(classLoader, fallbackLogCreator);
    case "maven", "console" -> fallbackLogCreator;
    case "slf4j" -> ClassUtils.instantiate(Slf4jLogCreator.class.getName(), classLoader);
    default -> ClassUtils.instantiate(logger, classLoader);
}).collect(Collectors.toList()));
```

**Text blocks / `final var`** — used for local variable inference:
```java
final var verb = configuration.getPluginRegister()...;
final var processBuilder = new ProcessBuilder(command);
```

**Stream API** — used throughout for collections processing with `Collectors.toList()` and `.stream().filter().map()` chains.

**Immutable collections** — `List.of()`, `Map.of()`, `Map.ofEntries()` used for static collections and default values.

## Lombok Usage

Lombok (`1.18.38`) is used extensively. Key annotations:

| Annotation | Usage |
|---|---|
| `@CustomLog` | **Mandatory for all logging** (see Logging section) |
| `@Getter` / `@Setter` | Field accessors — preferred over manual getters/setters |
| `@NoArgsConstructor(access = AccessLevel.PRIVATE)` | Utility classes that should not be instantiated |
| `@RequiredArgsConstructor` | Constructor injection for services/commands |
| `@AllArgsConstructor` | Full-argument constructors for data classes |
| `@Value` | Immutable value objects (makes all fields `final`) |
| `@Builder(toBuilder = true)` | Builder pattern, often paired with `@Value` |
| `@SneakyThrows` | Used sparingly when checked exceptions cannot be declared |
| `@Synchronized` | Thread-safe method locking |
| `@ExtensionMethod` | Extends existing types with static utility methods |

`lombok.config` configures `@CustomLog` to use Flyway's own `LogFactory.getLog(TYPE)` and disables all standard Lombok log annotations.

Example `@Value` + `@Builder` (preferred for immutable data):
```java
// flyway-core/src/main/java/org/flywaydb/core/internal/nc/schemahistory/SchemaHistoryItem.java
@Value
@Builder(toBuilder = true)
public class SchemaHistoryItem {
    int installedRank;
    String version;
    ...
}
```

Example `@RequiredArgsConstructor` for DI:
```java
// flyway-command/flyway-command-test-connection/src/main/java/.../TestConnectionCommandExtension.java
@RequiredArgsConstructor
public class TestConnectionCommandExtension implements CommandExtension<TestConnectionResult> {
    private final StandardInEnvironmentModelProvider modelProvider;
```

## Logging

**Pattern:** Every class that needs logging must use `@CustomLog` from Lombok.

The `lombok.config` file enforces:
- `lombok.log.fieldName = LOG` — all log fields are named `LOG`
- `lombok.log.custom.declaration = org.flywaydb.core.api.logging.Log org.flywaydb.core.api.logging.LogFactory.getLog(TYPE)`
- All other Lombok log annotations (`@Slf4j`, `@Log4j2`, etc.) are **banned** (flagged as errors)

**Usage pattern:**
```java
@CustomLog
public class H2Schema extends Schema<H2Database, H2Table> {
    // LOG field is injected by Lombok

    // Info for significant user-visible events
    LOG.info("Creating schema " + this + " ...");

    // Warn for non-critical issues or deprecated usage
    LOG.warn("Support for " + databaseType + " is provided only on a community-led basis...");

    // Debug for detailed diagnostic information
    LOG.debug("Found Amazon S3 resource: " + bucketName);

    // Error for failures, optionally with exception
    LOG.error("Unable to drop DOMAIN objects in schema " + database.quote(name));
    LOG.error(e.getMessage(), e);  // When exception context is needed
}
```

**Log interface:** `org.flywaydb.core.api.logging.Log` (`flyway-core/src/main/java/org/flywaydb/core/api/logging/Log.java`) defines: `debug()`, `info()`, `warn()`, `error(String)`, `error(String, Exception)`, `notice()`.

**Framework detection:** `LogFactory` auto-detects SLF4J → Log4j2 → Apache Commons → Java Util Logging at startup. Supports SLF4J, Log4j2, Apache Commons Logging, and custom loggers via configuration.

**Log level check:** Use `LogFactory.isDebugEnabled()` before constructing expensive debug messages. The `Log.isDebugEnabled()` instance method is `@Deprecated` — use the factory method instead.

## Error Handling

**Philosophy:** Checked `SQLException` from JDBC operations is always caught and re-thrown as a Flyway unchecked exception. All public-facing exceptions extend `FlywayException extends RuntimeException`.

**Exception hierarchy:**
- `org.flywaydb.core.api.FlywayException` — base unchecked exception; carries `ErrorCode`
- `org.flywaydb.core.internal.exception.FlywaySqlException` — wraps `SQLException`; includes SQL state and error code
- `org.flywaydb.core.internal.exception.FlywayDbUpgradeRequiredException` — database version too old
- `org.flywaydb.core.internal.exception.FlywayMigrateException` — migration failure
- `org.flywaydb.core.api.exception.FlywayValidateException` — validate command failure
- `org.flywaydb.core.extensibility.FlywayExpiredLicenseKeyException`, `FlywayInvalidLicenseKeyException`
- `org.flywaydb.core.extensibility.FlywayTrialExpiredException`

**JDBC error wrapping pattern** (used in base classes throughout `flyway-core`):
```java
public boolean exists() {
    try {
        return doExists();
    } catch (SQLException e) {
        throw new FlywaySqlException("Unable to check whether schema " + this + " exists", e);
    }
}
protected abstract boolean doExists() throws SQLException;
```

**Template method pattern for error isolation:** Internal `do{Operation}()` methods declare `throws SQLException`; public wrappers convert to `FlywaySqlException`.

**Error codes:** `CoreErrorCode` enum defines standard error codes (e.g. `ERROR`, `DB_CONNECTION`). Always use an `ErrorCode` when creating `FlywayException` if the cause category is known.

**`@SneakyThrows`:** Used sparingly in utility methods where checked exceptions are impossible at runtime (e.g. reflection invocations). Avoid using in new code without justification.

## Import Organization

**Order (standard Java convention):**
1. Static imports (grouped with a blank line before regular imports)
2. Third-party imports: `com.fasterxml.jackson.*`, `lombok.*`
3. Internal framework imports: `org.flywaydb.*` (typically grouped by sub-package)
4. Standard Java imports: `java.*`, `javax.*`

**Wildcard imports:** Used sparingly; seen in `org.flywaydb.core.api.output.*` and `java.sql.*` in older code. Prefer explicit imports in new code.

## Code Style

**Formatting:** No `checkstyle` or `spotless` plugin is present; formatting is not strictly enforced by tooling. The existing codebase uses 4-space indentation, Oracle/Google Java style braces.

**Final keyword:**
- `final` on fields when immutable (strongly preferred)
- `final` on local variables: used extensively in newer code (`final String bucketName = ...`)
- `final` parameters: used where added intentionally, not uniformly enforced

**Null handling:**
- `@Nullable` and `@NotNull` annotations are used sparingly (only `@Nullable` seen once in `TelemetrySpan.java`)
- Null guards via `MergeUtils.merge(a, b)` for config merging: returns `b` if non-null, else `a`
- `Objects.requireNonNull()` used in utility class internals
- `StringUtils.hasText(str)` used for non-null AND non-empty string checks
- `Optional` used in newer code for chaining but not universally applied

## Common Patterns

**Plugin/SPI system:** Flyway uses Java `ServiceLoader` for all extensibility. Implementations register in `META-INF/services/org.flywaydb.core.extensibility.Plugin`. All pluggable implementations implement `Plugin`:

```java
// All database types, command extensions, resolvers, etc. implement Plugin
public interface Plugin extends Comparable<Plugin> {
    default boolean isLicensed(final Configuration configuration) { return true; }
    default int getPriority() { return 0; } // higher = preferred
}
```

**Stub pattern:** Proprietary features that are open-sourced as stubs:
- `flyway-core/src/main/java/org/flywaydb/core/internal/proprietaryStubs/` — contains stubs that log warnings or throw unsupported operation exceptions when proprietary features are invoked in OSS mode.

**Configuration model merging:** Config models (TOML/env/properties) implement `merge(OtherModel)` that produces a new merged instance. Override (later config) wins over base (earlier config):
```java
// flyway-core/src/main/java/org/flywaydb/core/internal/configuration/models/EnvironmentModel.java
public EnvironmentModel merge(EnvironmentModel otherPojo) {
    EnvironmentModel result = new EnvironmentModel();
    result.url = MergeUtils.merge(url, otherPojo.url);
    ...
}
```

**Abstract template classes:** Database implementations follow a strict template pattern with abstract base classes:
- `Database<C extends Connection>` in `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Database.java`
- `Schema<D extends Database, T extends Table>` in `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Schema.java`
- `Connection` in `flyway-core/src/main/java/org/flywaydb/core/internal/database/base/Connection.java`
- Concrete implementations (e.g., `H2Database`, `H2Schema`, `H2Connection`) override specific behavior.

**`@Deprecated` with `@deprecated` Javadoc:** Deprecated methods always include a Javadoc `@deprecated` comment pointing to the replacement:
```java
/**
 * @deprecated Use {@link #getExact(Class)} instead.
 */
@Deprecated
public <T extends Plugin> T getPlugin(final Class<T> clazz) {
    return getExact(clazz);
}
```

**Utility classes:** Use `@NoArgsConstructor(access = AccessLevel.PRIVATE)` to prevent instantiation:
```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class StringUtils { ... }

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ConfigUtils { ... }
```

**Immutability preference:** New data classes strongly prefer `@Value` + `@Builder` over mutable `@Getter`/`@Setter`. Older configuration models use mutable `@Getter`/`@Setter` with explicit merge methods.

## Comments and Documentation

**Javadoc:** Used on public API classes and methods. Internal classes may have brief Javadoc or none. Parameters documented with `@param`, return values with `@return`, thrown exceptions with `@throws`.

**Inline comments:** Used for non-obvious logic. SQL queries often have comments explaining intent. License guard and version-check logic is commented.

**`package-info.java`:** Present in all major packages under `internal.*`. Always contains: "Private API. No compatibility guarantees provided."

---

*Convention analysis: 2026-02-27*
