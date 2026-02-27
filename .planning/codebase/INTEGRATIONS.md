# External Integrations

**Analysis Date:** 2026-02-27

## Database Connectivity

Flyway uses **JDBC** as the primary database connectivity mechanism for all relational and most columnar databases. Non-relational databases (MongoDB, Couchbase) use native drivers through the **Native Connectors** subsystem (`flyway-nc/`).

### JDBC-Based Database Modules

Each database module lives under `flyway-database/` and follows a consistent pattern: `XxxDatabaseType`, `XxxDatabase`, `XxxConnection`, `XxxParser`, `XxxSchema`, `XxxTable`.

| Database | Module | Driver Dependency |
|---|---|---|
| PostgreSQL | `flyway-database/flyway-database-postgresql/` | `org.postgresql:postgresql:42.7.2` (optional) |
| CockroachDB | Shares PostgreSQL module | Same as PostgreSQL |
| SQL Server | `flyway-database/flyway-sqlserver/` | `com.microsoft.sqlserver:mssql-jdbc:12.10.2.jre11` |
| Oracle | `flyway-database/flyway-database-oracle/` | `com.oracle.database.jdbc:ojdbc11:21.18.0.0` |
| MySQL | `flyway-database/flyway-mysql/` | `com.mysql:mysql-connector-j:8.0.24` |
| MariaDB | `flyway-database/flyway-mysql/` | `org.mariadb.jdbc:mariadb-java-client:2.7.13` |
| IBM DB2 | `flyway-database/flyway-database-db2/` | `com.ibm.db2:jcc:11.5.0.0` |
| Redshift | `flyway-database/flyway-database-redshift/` | `com.amazon.redshift:redshift-jdbc42:2.1.0.32` (from S3 Maven repo) |
| Google BigQuery | `flyway-database/flyway-gcp-bigquery/` | BigQuery Simba JDBC driver (bundled separately) |
| Google Spanner | `flyway-database/flyway-gcp-spanner/` | `com.google.cloud:google-cloud-spanner-jdbc:2.34.1` |
| Snowflake | `flyway-database/flyway-database-snowflake/` | `net.snowflake:snowflake-jdbc:3.27.0` |
| SAP HANA | `flyway-database/flyway-database-saphana/` | SAP HANA JDBC driver (version `2.6.30`) |
| Cassandra | `flyway-database/flyway-database-cassandra/` | `com.ing.data:cassandra-jdbc-wrapper:4.13.0` |
| Firebird | `flyway-database/flyway-firebird/` | `org.firebirdsql.jdbc:jaybird-jdk18:3.0.10` |
| Informix | `flyway-database/flyway-database-informix/` | `com.ibm.informix:jdbc:4.50.3` |
| Derby | `flyway-database/flyway-database-derby/` | `org.apache.derby:derby:10.16.1.1` |
| HSQLDB | `flyway-database/flyway-database-hsqldb/` | `org.hsqldb:hsqldb:2.7.2` |
| H2 | Built into `flyway-core/` | `com.h2database:h2:2.3.232` |
| SQLite | Built into `flyway-core/` | `org.xerial:sqlite-jdbc:3.50.3.0` |
| Sybase ASE | `flyway-database/flyway-database-sybasease/` | `net.sourceforge.jtds:jtds:1.3.1` |
| SingleStore | `flyway-database/flyway-singlestore/` | `com.singlestore:singlestore-jdbc-client:1.2.8` |
| Apache Ignite | Community DB support module (from `flyway-community-db-support` GitHub Packages) | Apache Ignite 2.13.0 |
| TiDB | Community DB support | MySQL-compatible |
| YugabyteDB | Community DB support | PostgreSQL-compatible |
| ClickHouse | Community DB support | ClickHouse JDBC |
| OceanBase | Community DB support | OceanBase JDBC |
| Databricks | CLI assembly: `com.databricks:databricks-jdbc:3.0.1` | Databricks JDBC |
| DuckDB | Community DB support | DuckDB JDBC |

### Native Connector Database Modules (Non-JDBC)

These modules live under `flyway-database/` but use native drivers via the `flyway-nc/` subsystem:

| Database | Module | Driver |
|---|---|---|
| MongoDB | `flyway-database/flyway-database-nc-mongodb/` | `org.mongodb:mongodb-driver-sync:5.6.1` |
| Couchbase | `flyway-database/flyway-database-nc-couchbase/` | `com.couchbase.client:java-client:3.9.2` |

Native Connectors infrastructure: `flyway-nc/flyway-nc-core/` (`NativeConnectorsJdbc.java`, `NativeConnectorsDatabasePluginResolver.java`, `NativeConnectorsProcessRunner.java`)

### TestContainers Integration

`flyway-core/src/main/java/org/flywaydb/core/internal/database/base/TestContainersDatabaseType.java` — a special `DatabaseType` that detects TestContainers JDBC URLs (`tc:` prefix) and automatically starts Docker containers for integration testing. Requires `org.testcontainers:junit-jupiter:1.21.4` on the classpath.

## Migration Script Storage

### AWS S3
- Module: `flyway-locations/flyway-locations-s3/`
- SDK: `software.amazon.awssdk:s3:2.32.22`
- Entry point: `flyway-locations/flyway-locations-s3/src/main/java/org/flywaydb/locations/s3/AwsS3Scanner.java`
- URL scheme: `s3://bucket/prefix`
- Auth: Follows AWS SDK default credential chain (environment, instance profile, etc.)

### Google Cloud Storage
- Detection: `flyway-core/src/main/java/org/flywaydb/core/internal/util/FeatureDetector.java`
- SDK: `com.google.cloud:google-cloud-storage:2.60.0`
- Used for GCS-hosted migration script locations in proprietary/enterprise builds
- Auth: Google Application Default Credentials

## Secrets Management

### AWS Secrets Manager
- Module: Shaded JAR at `flyway-shades/shades/aws-secretsmanager-jdbc/`
- SDK: `com.amazonaws.secretsmanager:aws-secretsmanager-jdbc:2.0.2` (shaded to avoid classpath conflicts)
- Configuration extension interface: `flyway-core/src/main/java/org/flywaydb/core/internal/configuration/extensions/SecretsManagerConfigurationExtension.java`
- AWS SDK: `software.amazon.awssdk:s3:2.32.22` (shared SDK module)
- Provides JDBC URL wrapping so credentials are fetched at connection time

### Google Cloud Secret Manager
- SDK: `com.google.cloud:google-cloud-secretmanager:2.81.0`
- Proto API: `com.google.api.grpc:proto-google-cloud-secretmanager-v1:2.81.0`
- Used in proprietary builds for resolving database credentials from GCP Secret Manager

### Azure Key Vault / MSAL
- `com.azure:azure-identity:1.15.4` — Azure Identity library (runtime dep in CLI assembly)
- `com.microsoft.azure:msal4j:1.20.0` — Microsoft Authentication Library for Java
- `com.microsoft.azure:msal4j-persistence-extension:1.3.0` — persistent token cache
- Auth exception stub: `flyway-core/src/main/java/org/flywaydb/core/internal/exception/sqlExceptions/FlywaySqlNoDriversForInteractiveAuthException.java`

## Authentication

### Database-Specific Auth

**PostgreSQL `.pgpass` file:**
- `flyway-core/src/main/java/org/flywaydb/core/internal/authentication/postgres/PgpassFileReader.java`

**MySQL options file (`~/.my.cnf`):**
- `flyway-database/flyway-mysql/src/main/java/org/flywaydb/authentication/mysql/MySQLOptionFileReader.java`

**Oracle Wallet (PKI):**
- `com.oracle.database.security:oraclepki:21.18.0.0`
- `com.oracle.database.security:osdt_cert:21.18.0.0`
- `com.oracle.database.security:osdt_core:21.18.0.0`

**SQL Server / Azure AD:**
- SQL Server Integrated Authentication: bundled `mssql-jdbc_auth.dll` (x86 and x64) in `flyway-commandline/src/main/assembly/drivers/mssql/`
- Azure AD token: `azure-identity` + `msal4j` runtime dependencies provide Azure AD auth for SQL Server

**External auth file support:**
- `flyway-core/src/main/java/org/flywaydb/core/internal/authentication/ExternalAuthFileReader.java`
- `flyway-core/src/main/java/org/flywaydb/core/internal/authentication/ExternalAuthPropertiesProvider.java`

### Redgate Licensing
- JWT-based licensing via proprietary server (stubs in `flyway-core/src/main/java/org/flywaydb/core/internal/proprietaryStubs/`)
- `FlywayPermit.java`, `EncryptionUtils.java` in `flyway-core/src/main/java/org/flywaydb/core/internal/license/`
- Auth command: `AuthCommandExtensionStub` — open-source stub that raises `FlywayRedgateEditionRequiredException`
- PAT token support: `PATTokenConfigurationExtensionStub`

## Build Plugins Integration

### Maven Plugin
- Module: `flyway-plugins/flyway-maven-plugin/`
- Packaging: `maven-plugin`
- Depends on: `maven-plugin-api:3.9.6`, `maven-plugin-annotations:3.6.0`, `maven-core:3.9.6`
- Provides all Flyway goals (`migrate`, `clean`, `info`, `validate`, `baseline`, `repair`) as Maven mojos

### Gradle Plugin
- Module: `flyway-plugins/flyway-gradle-plugin/`
- Depends on: Gradle 6.1.1 API (`gradle-core`, `gradle-plugins`, `gradle-core-api`, `gradle-logging`, `gradle-base-services`, `gradle-process-services`, `gradle-model-core`) — all `provided` scope
- Plugin IDs registered in `flyway-plugins/flyway-gradle-plugin/src/main/resources/META-INF/gradle-plugins/`:
  - `org.flywaydb.flyway`
  - `org.flywaydb.enterprise.flyway`
  - `org.flywaydb.pro.flyway`
  - `com.redgate.flyway.flyway`

## CI/CD & Deployment

**Hosting:**
- Docker Hub / container registry (Docker images in `flyway-docker/`)
- Maven Central via Sonatype Central Publishing (`central-publishing-maven-plugin:0.7.0`)
- GitHub Packages Maven registry: `https://maven.pkg.github.com/flyway/flyway-community-db-support` for community DB support modules

**CI Pipeline:**
- GitHub Actions: `.github/workflows/build-pr.yml`, `.github/workflows/build-release.yml`
- Jekyll GitHub Pages: `.github/workflows/jekyll-gh-pages.yml`

**Distribution Formats:**
- Platform JRE-bundled archives: `flyway-commandline-{version}-windows.zip`, `-linux.tar.gz`, `-macos.tar.gz`, `-macos-arm64.tar.gz`
- No-JRE archive: `flyway-commandline-{version}.tar.gz`
- Docker: Multiple Dockerfile variants — base (Ubuntu Noble + JRE 21), Alpine, Azure, MongoDB, Oracle — in `flyway-docker/dockerfiles/`

## Telemetry

- Telemetry interface: `flyway-core/src/main/java/org/flywaydb/core/FlywayTelemetryManager.java`
- Open-source stub (no-op): `flyway-core/src/main/java/org/flywaydb/core/internal/NullFlywayTelemetryManager.java`
- Azure Application Insights `3.4.7` is referenced in parent POM version properties but the actual artifact dependency is only wired in proprietary builds
- Telemetry events: `EventTelemetryModel` in `flyway-core/src/main/java/org/flywaydb/core/extensibility/EventTelemetryModel.java`
- The `TelemetrySpan` class in `flyway-core/src/main/java/org/flywaydb/core/TelemetrySpan.java` wraps operations for timing and reporting

## Reporting

- HTML/JSON report generation: `flyway-reports/src/main/java/org/flywaydb/reports/`
- Reports module: `flyway-reports/pom.xml` — depends on `flyway-core` + `jackson-databind`
- HTML report assets embedded in `flyway-reports/src/main/resources/assets/report/`

## Plugin Discovery

All integrations use Java **ServiceLoader** (`META-INF/services/org.flywaydb.core.extensibility.Plugin`) for plugin discovery. Each database module and extension registers its `DatabaseType` or `Plugin` implementation. Entry point: `flyway-core/src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin`.

---

*Integration audit: 2026-02-27*
