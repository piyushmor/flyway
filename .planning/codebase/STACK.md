# Technology Stack

**Analysis Date:** 2026-02-27

## Languages

**Primary:**
- Java 17 - All production source code; compile target set to Java 17 in root `pom.xml` (`<release>17</release>`) and individual modules (`maven.compiler.source/target = 17`)

**Secondary:**
- Groovy 2.4.7 - Gradle plugin build only (`flyway-plugins/flyway-gradle-plugin/pom.xml`, provided scope)

## Runtime

**Environment:**
- JVM: OpenJDK 21 LTS (Docker base image: `eclipse-temurin:21-jre-noble` in `flyway-docker/dockerfiles/base/Dockerfile`)
- Bundled JRE for CLI assemblies: OpenJDK 25.0.1 (Adoptium HotSpot), bundled per platform at build time via `flyway-commandline/pom.xml`

**Package Manager:**
- Maven 3.9.6 (pinned via Maven Wrapper)
- Wrapper config: `.mvn/wrapper/maven-wrapper.properties`
- Lockfile: Not present (Maven does not use a lockfile by default)

## Frameworks

**Core:**
- No application framework — Flyway is a standalone library/tool
- Spring JDBC 5.3.19 — version pinned in `pom.xml` properties (`version.springjdbc`), used as an optional integration target (Spring `DataSource` support in `ClassicConfiguration`)
- OSGi 4.3.1 — Optional runtime support for OSGi containers (Eclipse Equinox 3.15.200, `org.eclipse.platform:org.eclipse.equinox.common` 3.10.600)

**SQL Parsing:**
- Custom hand-written SQL parser: `flyway-core/src/main/java/org/flywaydb/core/internal/parser/` — no ANTLR grammar files are compiled; ANTLR 4.13.2 is only listed as a version property in `pom.xml` with no actual artifact dependency declared in any module POM

**Build/Dev:**
- Maven 3.9.6 (build system, via wrapper `mvnw`)
- Lombok 1.18.38 — annotation processor for boilerplate reduction; annotation processor configured in root `pom.xml`; custom log adapter via `lombok.config` (routes `@CustomLog` to `org.flywaydb.core.api.logging.LogFactory`)
- Apache Felix `maven-bundle-plugin` 5.1.8 — OSGi manifest generation for `flyway-locations-s3`
- `maven-assembly-plugin` 3.5.0 — builds platform-specific CLI distribution archives (Windows, Linux, macOS x64/arm64, no-JRE)
- `maven-source-plugin` 3.3.1 — attaches source JARs on every build
- `versions-maven-plugin` 2.7 — version management
- `license-maven-plugin` 2.4.0 — enforces Apache 2.0 file headers on all source files
- `central-publishing-maven-plugin` 0.7.0 — Sonatype Central publishing
- `maven-gpg-plugin` 3.2.8 — artifact signing

**Testing:**
- JUnit Jupiter 6.0.1 (`version.junit` property in root `pom.xml`)
- Testcontainers 1.21.4 — integration test database containers (PostgreSQL, CockroachDB, DB2, MariaDB, and generic JDBC)
- Mockito 5.18.0
- Hamcrest 2.2
- MockServer 5.15.0 (`mockserver-junit-jupiter`, `mockserver-client-java`) — HTTP API mocking
- System Stubs 2.1.8 (`uk.org.webcompere`) — environment variable/system property stubbing in JUnit tests
- Byte Buddy Agent 1.17.6 — runtime agent support for Mockito

## Key Dependencies

**Critical:**
- `jackson-databind` 2.19.1 — core serialization/deserialization; JSON input/output, configuration parsing
- `jackson-dataformat-toml` 2.19.1 — TOML configuration file parsing (`flyway.toml`)
- `jackson-dataformat-xml` 2.19.1 — XML output in CLI module
- `jackson-datatype-jsr310` 2.19.1 — Java 8 time type support
- `commons-text` 1.10.0 — string placeholder substitution in migration scripts
- `commons-lang3` 3.18.0 — general Java utility functions
- `gson` 2.10.1 — secondary JSON serialization (used in commandline output)
- P6Spy 3.9.1 — JDBC proxy driver; used internally in `H2DatabaseType`, `SQLiteDatabaseType`, `TestContainersDatabaseType`, `FirebirdDatabaseType`, and `RedshiftDatabaseType` for JDBC URL detection

**Infrastructure:**
- `slf4j-api` 1.7.30 — optional logging facade (users can provide their own binding)
- `commons-logging` 1.2 — optional Apache Commons Logging bridge
- `log4j-api` 2.17.1 — optional Log4j2 API
- `jansi` 1.18 — ANSI color output in CLI (`flyway-commandline`)
- `jboss-vfs` 3.2.15.Final — JBoss VFS classpath scanning support (optional)
- `javax.xml.bind:jaxb-api` 2.3.1 — JAXB API needed on Java 11+ (removed from JDK)
- `jetty-server` 12.0.21 — embedded HTTP server (proprietary features only, not in open-source build)
- `nimbus-jose-jwt` 10.0.2 / `oauth2-oidc-sdk` 11.32 — JWT validation (proprietary licensing layer; version-managed in parent POM but not used in open-source modules)
- `bcpkix-jdk18on` 1.80 — Bouncy Castle crypto (version-managed; used in proprietary licensed features)
- JNA 5.13.0 / `jna-platform` 5.13.0 — MariaDB Unix socket support in CLI assembly
- Netty BOM 4.2.9.Final / `reactor-netty-http` 1.2.8 — reactive networking (used in proprietary R2DBC features; version-managed in parent POM)
- `java-diff-utils` 4.12 — diff generation for report output

**JDBC Drivers (bundled in CLI assembly, optional in library)**
- `postgresql` 42.7.2
- `mssql-jdbc` 12.10.2.jre11
- `ojdbc11` 21.18.0.0
- `db2jcc4` 11.5.0.0 (IBM DB2)
- `derby` + tools + shared 10.16.1.1
- `hsqldb` 2.7.2
- `h2` 2.3.232
- `mariadb-java-client` 2.7.13
- `mysql-connector-j` 8.0.24
- `jaybird-jdk18` 3.0.10 (Firebird)
- `snowflake-jdbc` 3.27.0
- `singlestore-jdbc-client` 1.2.8
- `sqlite-jdbc` 3.50.3.0
- `jtds` 1.3.1 (Sybase ASE legacy)
- `cassandra-jdbc-wrapper` (com.ing.data) 4.13.0
- `google-cloud-spanner-jdbc` 2.34.1
- `databricks-jdbc` 3.0.1 (community DB support, versioned at 10.24.0 parent)
- MongoDB driver sync 5.6.1 (non-JDBC, native connectors path)
- Couchbase `java-client` 3.9.2 (non-JDBC, native connectors path)

## Configuration

**Environment:**
- Configuration via TOML file (`flyway.toml`), Java properties files, environment variables, and programmatic API
- No `.env` file mechanism; environment variable resolution handled by `EnvironmentVariableResolver` in `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/resolvers/EnvironmentVariableResolver.java`
- Secret injection supported via AWS Secrets Manager JDBC driver, GCP Secret Manager, and Azure Key Vault (proprietary feature) — resolved at connection time

**Build:**
- Root `pom.xml` defines all dependency versions as `<properties>`
- Per-module `pom.xml` inherits from `flyway-parent` and declares only needed dependencies
- Build profiles: `sign-artifacts`, `update-file-headers`, `lombok-javadoc`, `build-assemblies`, `build-assemblies-windows/linux/mac/mac-arm64/no-jre`

## Platform Requirements

**Development:**
- Java 17 SDK
- Maven 3.9.6 (or use `./mvnw`)
- Docker (only required for `build-shades` profile to repackage `aws-secretsmanager-jdbc`)

**Production:**
- JRE 21+ when running standalone CLI (bundled in distribution archives)
- JRE 17+ when used as a library
- Docker image: `eclipse-temurin:21-jre-noble` (standard), also Alpine and Azure variants in `flyway-docker/dockerfiles/`

---

*Stack analysis: 2026-02-27*
