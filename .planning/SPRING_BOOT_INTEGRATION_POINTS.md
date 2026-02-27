# Spring Boot Integration Points for R2DBC Support

**Status**: Planning Document for Phase 3
**Repositories Involved**:
- **This repo** (flyway): Core R2DBC support
- **Spring Boot repo** (https://github.com/spring-projects/spring-boot): Auto-configuration

---

## Repository Responsibilities

### Flyway OSS Repository (flyway/flyway)

**What this repo provides** (Phases 1-4):

1. **Core R2DBC Module** (Phase 1 - ✅ DONE)
   - `flyway-nc/flyway-r2dbc-core` - Blocking R2DBC abstraction
   - `NativeConnectorsR2dbc` base class
   - `R2dbcExecutor` for blocking operations

2. **Database-Specific R2DBC Adapters** (Phase 2 - PLANNED)
   - `flyway-database-nc-r2dbc-postgresql`
   - `flyway-database-nc-r2dbc-mysql`
   - `flyway-database-nc-r2dbc-h2`
   - Extends `NativeConnectorsR2dbc` for each database
   - Registered via `META-INF/services` (Plugin interface)

3. **Extension Points** (Already exists)
   - `NativeConnectorsDatabase<T>` sealed interface
   - `Plugin` interface via ServiceLoader
   - `ConnectionType` enum (now includes `R2DBC`)
   - Existing parser reuse mechanism

### Spring Boot Repository (spring-projects/spring-boot)

**What Spring Boot provides** (Phase 3 - REQUIRES CHANGES):

1. **Existing Flyway Auto-Configuration** (Must be updated)
   ```java
   @Configuration(proxyBeanMethods = false)
   @ConditionalOnClass(Flyway.class)
   @ConditionalOnBean(DataSource.class)
   @EnableConfigurationProperties(FlywayProperties.class)
   public class FlywayAutoConfiguration { }
   ```

2. **New R2DBC-Aware Auto-Configuration** (Must be added)
   ```java
   @Configuration(proxyBeanMethods = false)
   @ConditionalOnClass(Flyway.class)
   @ConditionalOnBean(ConnectionFactory.class)
   @ConditionalOnMissingBean(Flyway.class)
   @EnableConfigurationProperties(FlywayProperties.class)
   public class FlywayR2dbcAutoConfiguration { }
   ```

3. **Updated FlywayProperties** (Must be extended)
   - Add `r2dbc.enabled` property
   - Add `r2dbc.blocking-timeout` property
   - Add `connection-type` explicit property for forcing JDBC/R2DBC

4. **Updated Boot Starter** (`spring-boot-starter-flyway`)
   - Update dependencies to include `flyway-r2dbc-core`
   - Update database-specific modules conditionally

---

## Phase 3 Implementation Plan

### What Happens in Flyway Repo

**Minimal Spring Boot Integration Code** (in Flyway):
- No need to modify this repo for Phase 3
- Phase 3 focuses on Spring Boot repo changes
- Flyway repo just needs to ensure:
  - `NativeConnectorsR2dbc` is properly exported
  - `ConnectionType.R2DBC` is visible to Spring Boot
  - Plugin registration works via ServiceLoader

### What Happens in Spring Boot Repo

**Phase 3a: Update Existing Auto-Configuration**

File: `spring-boot-project/spring-boot-starters/spring-boot-starter-flyway/src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayAutoConfiguration.java`

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Flyway.class)
@ConditionalOnBean(DataSource.class)  // JDBC path
@EnableConfigurationProperties(FlywayProperties.class)
public class FlywayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Flyway.class)
    public Flyway flyway(FlywayProperties properties, DataSource dataSource) {
        // Existing JDBC logic - UNCHANGED
        return createFlywayFromDataSource(properties, dataSource);
    }
}
```

**Changes Required**: NONE - Keep existing logic intact (backward compatible)

---

### Phase 3b: Add New R2DBC Auto-Configuration

File: `spring-boot-project/spring-boot-starters/spring-boot-starter-flyway/src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayR2dbcAutoConfiguration.java` (NEW)

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Flyway.class)
@ConditionalOnClass(name = "io.r2dbc.spi.ConnectionFactory")
@ConditionalOnBean(ConnectionFactory.class)
@ConditionalOnMissingBean(Flyway.class)  // Don't override JDBC config
@AutoConfigureBefore(R2dbcRepositoriesAutoConfiguration.class)
@EnableConfigurationProperties(FlywayProperties.class)
public class FlywayR2dbcAutoConfiguration {

    @Bean
    public Flyway flyway(
            FlywayProperties properties,
            ConnectionFactory connectionFactory,
            ObjectProvider<FlywayConfigurationCustomizer> customizers) {

        // ONLY if JDBC not configured or explicitly chosen R2DBC
        if (!isJdbcConfigured() || isR2dbcExplicitlyChosen(properties)) {
            return createFlywayFromR2dbc(properties, connectionFactory, customizers);
        }
        return null;  // Let JDBC config handle it
    }

    private boolean isJdbcConfigured() {
        // Check if DataSource bean exists
    }

    private boolean isR2dbcExplicitlyChosen(FlywayProperties properties) {
        // Check if connection-type property = "r2dbc"
    }

    private Flyway createFlywayFromR2dbc(
            FlywayProperties properties,
            ConnectionFactory connectionFactory,
            ObjectProvider<FlywayConfigurationCustomizer> customizers) {
        // Create Configuration with R2DBC connection factory
        // Use existing Flyway.configure() API
    }
}
```

**Order**: Must be processed AFTER `FlywayAutoConfiguration` but BEFORE `R2dbcRepositoriesAutoConfiguration`

---

### Phase 3c: Update FlywayProperties

File: `spring-boot-project/spring-boot-starters/spring-boot-starter-flyway/src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayProperties.java`

```java
@ConfigurationProperties(prefix = "spring.flyway")
public class FlywayProperties {

    // Existing properties - UNCHANGED
    private String url;
    private String user;
    private String password;
    // ... etc

    // NEW: R2DBC support
    private R2dbc r2dbc = new R2dbc();

    // NEW: Explicit connection type selection
    private String connectionType;  // "jdbc" or "r2dbc"

    public static class R2dbc {
        private boolean enabled = true;
        private Duration blockingTimeout = Duration.ofMinutes(5);

        // getters/setters
    }
}
```

**Configuration Example**:
```yaml
spring:
  flyway:
    connection-type: r2dbc  # Explicit: force R2DBC even if JDBC available
    locations: classpath:db/migration
    r2dbc:
      enabled: true
      blocking-timeout: 10m
```

---

### Phase 3d: Update Starter Dependencies

File: `spring-boot-project/spring-boot-starters/spring-boot-starter-flyway/pom.xml`

```xml
<dependencies>
    <!-- Core Flyway -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
        <version>${flyway.version}</version>
    </dependency>

    <!-- NEW: R2DBC Core Support -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-r2dbc-core</artifactId>
        <version>${flyway.version}</version>
        <optional>true</optional>  <!-- Only if user wants R2DBC -->
    </dependency>

    <!-- NEW: Database-specific R2DBC adapters (optional, per-database) -->
    <!-- Users add these explicitly if needed -->

    <!-- Existing JDBC dependencies - UNCHANGED -->
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-jdbc</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- NEW: R2DBC support -->
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-spi</artifactId>
        <version>${r2dbc.version}</version>
        <optional>true</optional>
    </dependency>

    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

---

## Auto-Configuration Order (Critical for Backward Compatibility)

**Spring Boot Bean Initialization Order**:

```
1. DataSourceAutoConfiguration
   └─> Creates DataSource bean (if spring.datasource.* configured)

2. R2dbcAutoConfiguration
   └─> Creates ConnectionFactory bean (if spring.r2dbc.* configured)

3. FlywayAutoConfiguration (EXISTING)
   └─> Creates Flyway bean from DataSource (if present)
   └─> PRIORITY: HIGHEST - runs first

4. FlywayR2dbcAutoConfiguration (NEW)
   └─> Creates Flyway bean from ConnectionFactory (if present)
   └─> PRIORITY: LOWER - runs second (after JDBC checked)
   └─> @ConditionalOnMissingBean(Flyway.class) prevents double-creation

5. R2dbcRepositoriesAutoConfiguration
   └─> Creates repositories (must happen AFTER migrations)
   └─> @AutoConfigureBefore ensures correct order
```

**Result**: JDBC always wins if both configured (backward compatible)

---

## Detection Logic (Must be in Spring Boot)

```java
public class R2dbcConnectionDetector {

    public static boolean shouldUseR2dbc(
            FlywayProperties properties,
            boolean hasDataSource,
            boolean hasConnectionFactory) {

        // 1. Explicit property takes precedence
        if ("jdbc".equalsIgnoreCase(properties.getConnectionType())) {
            return false;  // Use JDBC
        }
        if ("r2dbc".equalsIgnoreCase(properties.getConnectionType())) {
            return true;  // Use R2DBC
        }

        // 2. If both available, JDBC wins (backward compat)
        if (hasDataSource && hasConnectionFactory) {
            return false;
        }

        // 3. Use whichever is available
        if (hasConnectionFactory) {
            return true;
        }

        if (hasDataSource) {
            return false;
        }

        // 4. Neither available - error
        throw new IllegalStateException(
            "Neither spring.datasource nor spring.r2dbc is configured");
    }
}
```

---

## Configuration Precedence Examples

### Example 1: Both Configured (JDBC Wins)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost/db
  r2dbc:
    url: r2dbc:postgresql://localhost/db
  flyway:
    locations: classpath:db/migration
```
**Result**: Uses JDBC (backward compatible) ✅

### Example 2: Only R2DBC Configured (Uses R2DBC)
```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost/db
  flyway:
    locations: classpath:db/migration
```
**Result**: Uses R2DBC ✅

### Example 3: Explicit Override
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost/db
  r2dbc:
    url: r2dbc:postgresql://localhost/db
  flyway:
    connection-type: r2dbc  # Force R2DBC despite JDBC available
    locations: classpath:db/migration
```
**Result**: Uses R2DBC (explicit override) ✅

---

## Testing Strategy for Spring Boot Integration

**Spring Boot Tests** (in spring-boot repo):

```java
@SpringBootTest
@Testcontainers
class FlywayR2dbcAutoConfigurationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
            () -> "r2dbc:postgresql://...");
        registry.add("spring.r2dbc.username",
            postgres::getUsername);
    }

    @Test
    void flywyayBeanCreatedFromR2dbc(@Autowired Flyway flyway) {
        assertNotNull(flyway);
        assertThat(flyway.migrate()).isSuccessful();
    }

    @Test
    void jdbcTakesPreferenceWhenBothConfigured() {
        // Verify JDBC bean is used, not R2DBC
    }
}
```

---

## Backward Compatibility in Spring Boot

**What Won't Change**:
- ✅ Existing `FlywayAutoConfiguration` logic
- ✅ Existing `FlywayProperties` for JDBC
- ✅ Default bean ordering
- ✅ JDBC takes precedence when both configured

**What Will Be Added**:
- ✅ New `FlywayR2dbcAutoConfiguration`
- ✅ New optional R2DBC properties
- ✅ New optional dependencies
- ✅ New test cases for R2DBC

**Impact on Existing Users**: ZERO - all changes are additive

---

## Summary: Two-Repository Approach

| Concern | Flyway Repo | Spring Boot Repo |
|---------|-------------|------------------|
| **R2DBC Core** | ✅ Provides | ❌ Consumes |
| **Database Adapters** | ✅ Provides | ❌ Consumes |
| **Auto-Configuration** | ❌ None needed | ✅ Implements |
| **Property Binding** | ❌ None | ✅ FlywayProperties |
| **Bean Ordering** | ❌ None | ✅ @AutoConfigureBefore |
| **Detection Logic** | ❌ None | ✅ Implements |
| **Backward Compat** | ✅ Guaranteed | ✅ Guaranteed |

**This approach maintains clean separation of concerns**:
- Flyway repo: Database connectivity logic
- Spring Boot repo: Spring integration and auto-configuration

---

*Phase 3 will require coordinated changes between these two repositories, but both can maintain 100% backward compatibility through careful ordering and conditional bean creation.*
