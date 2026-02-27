# Flyway R2DBC - Spring Boot Integration Guide

**Quick Reference for Spring Boot R2DBC Auto-Configuration**

---

## Overview

This document details how Flyway R2DBC will integrate with Spring Boot applications, providing seamless database migration support for reactive applications using Spring WebFlux and Spring Data R2DBC.

---

## 1. Dependencies

### 1.1 Maven

```xml
<!-- Spring Boot Starter (includes auto-configuration) -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-spring-boot-r2dbc-starter</artifactId>
    <version>${flyway.version}</version>
</dependency>

<!-- Database-specific R2DBC module (choose one) -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-r2dbc-postgresql</artifactId>
    <version>${flyway.version}</version>
</dependency>

<!-- R2DBC Driver (provided by Spring Boot, but explicit for clarity) -->
<dependency>
    <groupId>io.r2dbc</groupId>
    <artifactId>r2dbc-postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 1.2 Gradle

```groovy
implementation 'org.flywaydb:flyway-spring-boot-r2dbc-starter:${flywayVersion}'
implementation 'org.flywaydb:flyway-r2dbc-postgresql:${flywayVersion}'
runtimeOnly 'io.r2dbc:r2dbc-postgresql'
```

---

## 2. Configuration

### 2.1 Minimal Configuration (application.yml)

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/mydb
    username: user
    password: secret

  flyway:
    locations: classpath:db/migration
```

Flyway will automatically:
- Detect the R2DBC URL pattern
- Use Spring's R2DBC `ConnectionFactory`
- Run migrations at application startup

### 2.2 Full Configuration Reference

```yaml
spring:
  # R2DBC Connection Configuration
  r2dbc:
    url: r2dbc:pool:postgresql://localhost:5432/mydb
    username: ${DB_USERNAME:user}
    password: ${DB_PASSWORD:secret}
    pool:
      enabled: true
      initial-size: 5
      max-size: 20
      max-idle-time: 30m
      max-acquire-time: 10s
      validation-query: SELECT 1

  # Flyway Configuration  
  flyway:
    # Enable/disable Flyway (default: true)
    enabled: true
    
    # Migration locations
    locations:
      - classpath:db/migration
      - classpath:db/specific/${spring.profiles.active}
    
    # Schemas to manage
    schemas:
      - public
      - app_schema
    
    # Schema history table name
    table: flyway_schema_history
    
    # Baseline configuration
    baseline-on-migrate: false
    baseline-version: 1
    baseline-description: '<< Flyway Baseline >>'
    
    # Validation
    validate-on-migrate: true
    validate-migration-naming: false
    
    # Execution options
    out-of-order: false
    ignore-migration-patterns: '*:pending'
    
    # Clean (DANGER - disabled by default)
    clean-disabled: true
    
    # R2DBC-specific configuration
    r2dbc:
      # Enable R2DBC mode (auto-detected, but can force)
      enabled: true
      
      # Timeout for blocking R2DBC operations
      blocking-timeout: 5m
      
      # Use separate connection for migrations (recommended)
      create-separate-connection: true
```

### 2.3 Profile-Based Configuration

```yaml
# application.yml (base)
spring:
  flyway:
    locations: classpath:db/migration

---
# application-dev.yml
spring:
  r2dbc:
    url: r2dbc:h2:mem:///devdb
  flyway:
    clean-disabled: false
    baseline-on-migrate: true

---
# application-prod.yml  
spring:
  r2dbc:
    url: r2dbc:pool:postgresql://${DB_HOST}:5432/${DB_NAME}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  flyway:
    clean-disabled: true
    validate-on-migrate: true
```

---

## 3. Auto-Configuration Behavior

### 3.1 Detection Logic

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Startup                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │ Is spring.flyway.enabled=true?│
              └───────────────────────────────┘
                     │                │
                    Yes               No
                     │                │
                     ▼                ▼
    ┌────────────────────────┐     [Skip Flyway]
    │ ConnectionFactory bean │
    │ present?               │
    └────────────────────────┘
           │          │
          Yes         No
           │          │
           ▼          ▼
    ┌────────────┐  ┌─────────────────────┐
    │ R2DBC URL  │  │ JDBC DataSource     │
    │ detected?  │  │ present?            │
    └────────────┘  └─────────────────────┘
        │    │           │          │
       Yes   No         Yes         No
        │    │           │          │
        ▼    │           ▼          ▼
    [Use     │      [Use JDBC   [Configuration
    R2DBC]   │       Flyway]     Error]
             │
             ▼
    [Use provided
    ConnectionFactory]
```

### 3.2 Auto-Configuration Order

1. **R2dbcAutoConfiguration** - Creates `ConnectionFactory`
2. **FlywayR2dbcAutoConfiguration** - Creates `Flyway` bean
3. **FlywayMigrationInitializer** - Runs migrations (highest precedence)
4. **R2dbcRepositoriesAutoConfiguration** - Sets up repositories

This ensures migrations complete before any repository operations.

---

## 4. Customization

### 4.1 FlywayConfigurationCustomizer

```java
@Configuration
public class FlywayConfig {
    
    @Bean
    public FlywayConfigurationCustomizer flywayCustomizer() {
        return configuration -> {
            configuration
                .loggers("slf4j")
                .callbacks(new AuditCallback())
                .resolvers(new CustomMigrationResolver());
        };
    }
}
```

### 4.2 Custom Migration Strategy

```java
@Bean
public FlywayMigrationStrategy migrationStrategy() {
    return flyway -> {
        // Custom pre-migration logic
        log.info("Starting database migration...");
        
        // Validate first
        flyway.validate();
        
        // Then migrate
        MigrateResult result = flyway.migrate();
        
        // Custom post-migration logic
        log.info("Applied {} migrations", result.migrationsExecuted);
    };
}
```

### 4.3 Conditional Migration

```java
@Bean
@ConditionalOnProperty(name = "app.db.migrate", havingValue = "true")
public FlywayMigrationStrategy conditionalMigration() {
    return Flyway::migrate;
}
```

### 4.4 Custom ConnectionFactory for Flyway

```java
@Configuration
public class FlywayConnectionConfig {
    
    /**
     * Provide a separate ConnectionFactory for Flyway migrations.
     * Useful when you want different pool settings for migrations.
     */
    @Bean
    @FlywayConnectionFactory  // Qualifier annotation
    public ConnectionFactory flywayConnectionFactory() {
        return ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(DRIVER, "postgresql")
                .option(HOST, "localhost")
                .option(PORT, 5432)
                .option(DATABASE, "mydb")
                .option(USER, "flyway_admin")  // Different user for migrations
                .option(PASSWORD, "admin_secret")
                .build()
        );
    }
}
```

---

## 5. Migration Patterns

### 5.1 Standard SQL Migration

```sql
-- V1__Create_initial_schema.sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
```

### 5.2 Repeatable Migration

```sql
-- R__Create_views.sql
CREATE OR REPLACE VIEW active_users AS
SELECT id, username, email, created_at
FROM users
WHERE deleted_at IS NULL;
```

### 5.3 Undo Migration (Teams/Enterprise)

```sql
-- U1__Create_initial_schema.sql
DROP TABLE IF EXISTS users CASCADE;
```

### 5.4 Java Migration (Limited R2DBC Support)

```java
/**
 * Note: Java migrations have limited support in R2DBC mode.
 * context.getConnection() returns null.
 * Prefer SQL migrations for R2DBC compatibility.
 */
public class V2__Seed_data implements JavaMigration {
    
    @Override
    public void migrate(Context context) {
        // For complex logic that can't be done in SQL,
        // inject your own R2DBC client
        throw new UnsupportedOperationException(
            "Use SQL migrations for R2DBC. Java migrations not fully supported."
        );
    }
}
```

---

## 6. Testing

### 6.1 Integration Test with @SpringBootTest

```java
@SpringBootTest
@Testcontainers
class DatabaseMigrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
    
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> 
            "r2dbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName()
            )
        );
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }
    
    @Autowired
    private Flyway flyway;
    
    @Autowired
    private DatabaseClient databaseClient;
    
    @Test
    void shouldApplyAllMigrations() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSizeGreaterThan(0);
    }
    
    @Test
    void shouldHaveCreatedTables() {
        Long count = databaseClient
            .sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'users'")
            .map(row -> row.get(0, Long.class))
            .first()
            .block();
        
        assertThat(count).isEqualTo(1L);
    }
}
```

### 6.2 Test Slice (@DataR2dbcTest)

```java
@DataR2dbcTest
@AutoConfigureFlyway  // Custom test annotation
@Testcontainers
class UserRepositoryTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
    
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // ... same as above
    }
    
    @Autowired
    private UserRepository userRepository;
    
    @Test
    void shouldFindUserByUsername() {
        // Migration V2 seeds a test user
        StepVerifier.create(userRepository.findByUsername("testuser"))
            .assertNext(user -> {
                assertThat(user.getEmail()).isEqualTo("test@example.com");
            })
            .verifyComplete();
    }
}
```

### 6.3 Test with H2 (In-Memory)

```yaml
# application-test.yml
spring:
  r2dbc:
    url: r2dbc:h2:mem:///testdb;DB_CLOSE_DELAY=-1
  flyway:
    locations: 
      - classpath:db/migration
      - classpath:db/testdata
    clean-disabled: false
```

---

## 7. Troubleshooting

### 7.1 Common Issues

#### Migration Not Running

```yaml
# Check if Flyway is enabled
spring:
  flyway:
    enabled: true

# Verify locations are correct
logging:
  level:
    org.flywaydb: DEBUG
```

#### Connection Timeout

```yaml
spring:
  flyway:
    r2dbc:
      blocking-timeout: 10m  # Increase for slow migrations
```

#### Schema History Table Conflicts

```yaml
# Use different table name if conflicts with existing setup
spring:
  flyway:
    table: flyway_r2dbc_history
```

### 7.2 Logging Configuration

```yaml
logging:
  level:
    org.flywaydb: DEBUG
    org.flywaydb.core.internal.command: DEBUG
    io.r2dbc: DEBUG
```

### 7.3 Health Indicator

```yaml
management:
  endpoint:
    flyway:
      enabled: true
  health:
    flyway:
      enabled: true
```

```java
// Access via Actuator
// GET /actuator/flyway
{
  "contexts": {
    "application": {
      "flywayBeans": {
        "flyway": {
          "migrations": [
            {
              "type": "SQL",
              "checksum": -1234567890,
              "version": "1",
              "description": "Create initial schema",
              "script": "V1__Create_initial_schema.sql",
              "state": "SUCCESS",
              "installedBy": "flyway",
              "installedOn": "2024-02-27T10:30:00.000Z",
              "installedRank": 1,
              "executionTime": 45
            }
          ]
        }
      }
    }
  }
}
```

---

## 8. Migration from JDBC to R2DBC

### 8.1 Step-by-Step Migration

1. **Update Dependencies**

```diff
<!-- pom.xml -->
- <dependency>
-     <groupId>org.flywaydb</groupId>
-     <artifactId>flyway-core</artifactId>
- </dependency>
- <dependency>
-     <groupId>org.springframework.boot</groupId>
-     <artifactId>spring-boot-starter-data-jpa</artifactId>
- </dependency>
+ <dependency>
+     <groupId>org.flywaydb</groupId>
+     <artifactId>flyway-spring-boot-r2dbc-starter</artifactId>
+ </dependency>
+ <dependency>
+     <groupId>org.flywaydb</groupId>
+     <artifactId>flyway-r2dbc-postgresql</artifactId>
+ </dependency>
+ <dependency>
+     <groupId>org.springframework.boot</groupId>
+     <artifactId>spring-boot-starter-data-r2dbc</artifactId>
+ </dependency>
```

2. **Update Configuration**

```diff
# application.yml
- spring:
-   datasource:
-     url: jdbc:postgresql://localhost:5432/mydb
-     username: user
-     password: secret
+ spring:
+   r2dbc:
+     url: r2dbc:postgresql://localhost:5432/mydb
+     username: user
+     password: secret
```

3. **Keep SQL Migrations As-Is**
   - SQL migrations are compatible between JDBC and R2DBC
   - No changes needed for standard SQL files

4. **Update Java Migrations**
   - Convert Java migrations to SQL if they use `context.getConnection()`
   - Or inject R2DBC client manually

### 8.2 Rollback Plan

Keep JDBC driver available during transition:

```xml
<!-- Temporary: Keep both during transition -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

```yaml
# Feature flag for gradual rollout
spring:
  flyway:
    r2dbc:
      enabled: ${FLYWAY_R2DBC_ENABLED:false}
```

---

## 9. Architecture Decision Records

### ADR-001: Blocking Strategy

**Context**: Flyway is synchronous; R2DBC is reactive.

**Decision**: Use `Mono.block()` with configurable timeout.

**Consequences**:
- Simple integration, no major Flyway refactoring
- Blocking happens on bounded elastic threads
- Timeout prevents indefinite hangs

### ADR-002: Separate ConnectionFactory Option

**Context**: Migrations may need different permissions than application.

**Decision**: Support optional `@FlywayConnectionFactory` qualifier.

**Consequences**:
- Flexibility for admin-level migration connections
- Slightly more complex configuration
- Better security separation

### ADR-003: Parser Reuse

**Context**: Flyway has mature SQL parsers for each database.

**Decision**: R2DBC modules depend on existing parser modules.

**Consequences**:
- No parser duplication
- R2DBC modules are lightweight
- Parser updates benefit both JDBC and R2DBC

---

## Document History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-02-27 | Initial Spring Boot integration guide |
