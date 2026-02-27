# Phase 3: Spring Boot Repository Changes

**Status**: Planning document for Phase 3 implementation
**Local Setup**: Both repos cloned in `/Users/pmor/IdeaProjects/OSS/`
- Flyway repo: `flyway/`
- Spring Boot repo: `spring-boot/`

**Repository Structure**:
```
/Users/pmor/IdeaProjects/OSS/
├── flyway/                    (This repository - Phase 1-2 done)
└── spring-boot/               (Spring Boot repository - Phase 3 work)
    └── spring-boot-project/
        └── spring-boot-starters/
            └── spring-boot-starter-flyway/
                ├── pom.xml
                └── src/main/java/org/springframework/boot/autoconfigure/flyway/
```

---

## Phase 3 Work: Spring Boot Repository Changes

Phase 3 requires changes in the Spring Boot repository to integrate the R2DBC support from Flyway.

### Location of Changes

Primary directory:
```
spring-boot/spring-boot-project/spring-boot-starters/spring-boot-starter-flyway/
```

### Tasks in Phase 3

#### Task 3.1: Keep Existing JDBC Auto-Configuration Unchanged

**File**: `src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayAutoConfiguration.java`

**Action**: REVIEW ONLY - Do NOT modify
```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Flyway.class)
@ConditionalOnBean(DataSource.class)  // JDBC path - keep unchanged
@EnableConfigurationProperties(FlywayProperties.class)
public class FlywayAutoConfiguration {
    // Existing JDBC logic - LEAVE UNTOUCHED
}
```

**Why**: Maintains 100% backward compatibility

#### Task 3.2: Create New R2DBC Auto-Configuration

**File**: `src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayR2dbcAutoConfiguration.java` (NEW)

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Flyway.class)
@ConditionalOnClass(name = "io.r2dbc.spi.ConnectionFactory")
@ConditionalOnBean(ConnectionFactory.class)
@ConditionalOnMissingBean(Flyway.class)  // Don't override JDBC
@AutoConfigureBefore(R2dbcRepositoriesAutoConfiguration.class)
@EnableConfigurationProperties(FlywayProperties.class)
public class FlywayR2dbcAutoConfiguration {

    @Bean
    public Flyway flyway(
            FlywayProperties properties,
            ConnectionFactory connectionFactory,
            ObjectProvider<FlywayConfigurationCustomizer> customizers,
            ObjectProvider<FlywayMigrationStrategy> migrationStrategy) {

        // Detection: Should we use R2DBC?
        if (!shouldUseR2dbc(properties)) {
            return null;  // Let JDBC config handle it
        }

        // Create Configuration for R2DBC
        Configuration config = createR2dbcConfiguration(
            properties, connectionFactory, customizers);

        Flyway flyway = new Flyway(config);

        // Apply migration strategy if provided
        migrationStrategy.ifAvailable(strategy -> strategy.migrate(flyway));

        return flyway;
    }

    private boolean shouldUseR2dbc(FlywayProperties properties) {
        // 1. Explicit property takes precedence
        String connectionType = properties.getConnectionType();
        if ("jdbc".equalsIgnoreCase(connectionType)) {
            return false;  // Force JDBC
        }
        if ("r2dbc".equalsIgnoreCase(connectionType)) {
            return true;  // Force R2DBC
        }

        // 2. If both JDBC and R2DBC available, prefer JDBC (default behavior)
        // This method is only called if DataSource is NOT present
        // So we can safely use R2DBC here
        return true;
    }

    private Configuration createR2dbcConfiguration(
            FlywayProperties properties,
            ConnectionFactory connectionFactory,
            ObjectProvider<FlywayConfigurationCustomizer> customizers) {

        // Create Configuration with R2DBC connection factory
        Configuration config = Flyway.configure()
            .dataSource(new R2dbcDataSourceAdapter(connectionFactory))
            // OR: Use a custom method to set R2DBC connection factory
            .locations(properties.getLocations())
            .baselineOnMigrate(properties.isBaselineOnMigrate())
            // ... other properties
            .build();

        // Apply customizers
        customizers.forEach(customizer -> customizer.customize(config));

        return config;
    }
}
```

**Key Points**:
- Only activates if JDBC config not already created
- Uses `@ConditionalOnMissingBean(Flyway.class)` to prevent double creation
- Runs BEFORE `R2dbcRepositoriesAutoConfiguration` (migrations before repositories)

#### Task 3.3: Update FlywayProperties

**File**: `src/main/java/org/springframework/boot/autoconfigure/flyway/FlywayProperties.java`

**Add these fields**:
```java
@ConfigurationProperties(prefix = "spring.flyway")
public class FlywayProperties {

    // Existing JDBC properties - KEEP UNCHANGED
    private String url;
    private String user;
    private String password;
    // ... etc

    // NEW: R2DBC-specific configuration
    private R2dbc r2dbc = new R2dbc();

    // NEW: Explicit connection type selection (overrides auto-detection)
    private String connectionType;  // "jdbc" or "r2dbc"

    public static class R2dbc {
        /**
         * Enable or disable R2DBC support.
         */
        private boolean enabled = true;

        /**
         * Timeout for blocking R2DBC operations.
         */
        private Duration blockingTimeout = Duration.ofMinutes(5);

        // getters/setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Duration getBlockingTimeout() { return blockingTimeout; }
        public void setBlockingTimeout(Duration blockingTimeout) {
            this.blockingTimeout = blockingTimeout;
        }
    }

    // Existing properties...

    public R2dbc getR2dbc() { return r2dbc; }
    public void setR2dbc(R2dbc r2dbc) { this.r2dbc = r2dbc; }

    public String getConnectionType() { return connectionType; }
    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType;
    }
}
```

**Configuration Examples**:
```yaml
# Force R2DBC explicitly
spring.flyway.connection-type=r2dbc

# Configure R2DBC timeout
spring.flyway.r2dbc.blocking-timeout=10m

# Disable R2DBC (fall back to JDBC)
spring.flyway.r2dbc.enabled=false
```

#### Task 3.4: Create R2dbcConnectionDetector

**File**: `src/main/java/org/springframework/boot/autoconfigure/flyway/R2dbcConnectionDetector.java` (NEW)

```java
public class R2dbcConnectionDetector {

    /**
     * Determine if R2DBC should be used based on configuration and available beans.
     */
    public static boolean shouldUseR2dbc(
            FlywayProperties properties,
            boolean hasDataSource,
            boolean hasConnectionFactory) {

        // 1. Explicit property takes precedence
        String connectionType = properties.getConnectionType();
        if ("jdbc".equalsIgnoreCase(connectionType)) {
            return false;
        }
        if ("r2dbc".equalsIgnoreCase(connectionType)) {
            return true;
        }

        // 2. If both available, JDBC wins (backward compatible)
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

        // 4. Neither available
        throw new IllegalStateException(
            "Neither spring.datasource nor spring.r2dbc is configured. " +
            "At least one must be configured for Flyway to work.");
    }
}
```

#### Task 3.5: Update Starter Dependencies

**File**: `pom.xml`

**Current**: Only JDBC dependencies
**Update to**: Add optional R2DBC dependencies

```xml
<dependencies>
    <!-- Core Flyway (same as before) -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- NEW: R2DBC Core Support (optional, only if user wants R2DBC) -->
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-r2dbc-core</artifactId>
        <version>${flyway.version}</version>
        <optional>true</optional>
    </dependency>

    <!-- NEW: R2DBC SPI (optional) -->
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-spi</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- NEW: Project Reactor (optional, only if R2DBC used) -->
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Existing JDBC dependencies (unchanged) -->
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-jdbc</artifactId>
        <optional>true</optional>
    </dependency>
    <!-- ... other JDBC deps ... -->
</dependencies>
```

**Why optional**:
- Users who only use JDBC don't need R2DBC dependencies
- Users who use R2DBC need to explicitly add `flyway-r2dbc-core`
- Keeps starter lightweight

#### Task 3.6: Register Auto-Configuration

**File**: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Add new line**:
```
org.springframework.boot.autoconfigure.flyway.FlywayR2dbcAutoConfiguration
```

**Order matters**: This must be AFTER `FlywayAutoConfiguration` to ensure JDBC takes precedence

#### Task 3.7: Add Tests

**New Test Class**: `src/test/java/org/springframework/boot/autoconfigure/flyway/FlywayR2dbcAutoConfigurationTest.java`

```java
@SpringBootTest
@Testcontainers
class FlywayR2dbcAutoConfigurationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url",
            () -> "r2dbc:postgresql://" + postgres.getHost() +
                  ":" + postgres.getFirstMappedPort() + "/" +
                  postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }

    @Test
    void flywyayBeanCreatedFromR2dbc(@Autowired Flyway flyway) {
        assertNotNull(flyway);
        MigrateResult result = flyway.migrate();
        assertTrue(result.success);
    }

    @Test
    void jdbcTakesPreferenceWhenBothConfigured() {
        // Add DataSource config and verify JDBC is used
    }

    @Test
    void explicitConnectionTypeOverridesDefault() {
        // Test connection-type=r2dbc property
    }
}
```

---

## Bean Initialization Order (Critical)

Spring Boot will initialize beans in this order:

```
1. DataSourceAutoConfiguration
   └─> Creates DataSource (if spring.datasource.* configured)

2. R2dbcAutoConfiguration
   └─> Creates ConnectionFactory (if spring.r2dbc.* configured)

3. FlywayAutoConfiguration
   └─> Creates Flyway from DataSource (if present)
   └─> Priority: HIGHEST (runs first)

4. FlywayR2dbcAutoConfiguration
   └─> Creates Flyway from ConnectionFactory (if present)
   └─> @ConditionalOnMissingBean(Flyway.class) prevents duplicate
   └─> @AutoConfigureBefore(R2dbcRepositoriesAutoConfiguration.class)
   └─> Priority: LOWER (runs second)

5. R2dbcRepositoriesAutoConfiguration
   └─> Creates repositories (must happen AFTER migrations)
```

**Result**: If both DataSource and ConnectionFactory exist, JDBC is used ✓

---

## How to Integrate with Flyway Repo

The Spring Boot changes in Phase 3 will use:

From Flyway repo (Phase 1 - already available):
- ✅ `ConnectionType.R2DBC` enum value
- ✅ `NativeConnectorsR2dbc` abstract base class
- ✅ `R2dbcExecutor` for blocking operations

From Flyway repo (Phase 2 - will be available):
- 📋 `flyway-database-nc-r2dbc-postgresql` adapter
- 📋 `flyway-database-nc-r2dbc-mysql` adapter
- 📋 Other database-specific R2DBC adapters

**No changes needed in Flyway repo** - it already provides all integration points!

---

## Testing Strategy for Phase 3

### Unit Tests
- Auto-configuration activation/deactivation
- Property binding
- Connection type detection logic

### Integration Tests
- With Testcontainers for PostgreSQL
- JDBC vs R2DBC selection
- Seamless switching between connection types
- Schema history compatibility

### Backward Compatibility Tests
- Existing JDBC tests must pass unchanged
- No performance regression in JDBC path
- All existing properties still work

---

## Implementation Checklist for Phase 3

- [ ] Review existing `FlywayAutoConfiguration` (no changes)
- [ ] Create new `FlywayR2dbcAutoConfiguration`
- [ ] Update `FlywayProperties` with R2DBC config
- [ ] Create `R2dbcConnectionDetector` utility
- [ ] Update `spring-boot-starter-flyway` pom.xml
- [ ] Register new auto-configuration in `.imports` file
- [ ] Add comprehensive tests
- [ ] Update documentation (README, migration guide)
- [ ] Test backward compatibility
- [ ] Coordinate with Spring Boot maintainers for PR

---

## How to Proceed

1. **Before Phase 2 is complete**: Start analyzing Spring Boot repo structure
2. **When Phase 2 is ready**: Create feature branch in Spring Boot repo
3. **Implement tasks** 3.1-3.7 above
4. **Test thoroughly** with both JDBC and R2DBC
5. **Prepare PR** for Spring Boot maintainers
6. **Coordinate release** with Flyway release schedule

---

## Notes for Phase 3 Implementation

**Key Principle**: All changes must be additive and backward compatible

**JDBC Changes**: ZERO modifications to existing JDBC auto-configuration

**R2DBC Changes**: Only new auto-configuration class + property updates

**Default Behavior**: JDBC is preferred when both configured (maintains backward compatibility)

**User Impact**: Existing users see no changes; new users can opt-in to R2DBC

---

*This document is a detailed guide for Phase 3 implementation. Both repositories are now cloned locally, enabling coordinated development and testing.*
