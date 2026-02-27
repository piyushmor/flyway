/*-
 * ========================LICENSE_START=================================
 * flyway-r2dbc-core
 * ========================================================================
 * Copyright (C) 2010 - 2026 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.nc.r2dbc;

import static org.flywaydb.core.internal.logging.PreviewFeatureWarning.NATIVE_CONNECTORS;
import static org.flywaydb.core.internal.logging.PreviewFeatureWarning.logPreviewFeature;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.nc.AbstractNativeConnectorsDatabase;
import org.flywaydb.core.internal.nc.ConnectionType;
import org.flywaydb.core.internal.nc.DatabaseVersionImpl;
import org.flywaydb.core.internal.nc.MetaData;
import org.flywaydb.core.internal.nc.schemahistory.SchemaHistoryItem;
import org.flywaydb.core.internal.nc.schemahistory.SchemaHistoryModel;
import org.flywaydb.core.internal.configuration.models.ResolvedEnvironment;
import reactor.core.publisher.Mono;

/**
 * Abstract base class for R2DBC-based Native Connectors (database-agnostic).
 * <p>
 * This class provides:
 * - Blocking adapter using Mono.block() (reactive → synchronous conversion)
 * - Generic R2DBC connection management
 * - Statement execution with configurable timeout
 * - Batch operation support
 * <p>
 * Database-specific implementations (PostgreSQL, MySQL, etc.) extend this to provide:
 * - Database-specific SQL for schema history table management
 * - Database-specific metadata extraction
 * - Database-specific parsing via existing Flyway parsers
 */
public abstract class NativeConnectorsR2dbc extends AbstractNativeConnectorsDatabase<String> {

  protected ConnectionFactory connectionFactory;
  protected Connection connection;
  protected Duration blockingTimeout = Duration.ofMinutes(5);

  @Override
  public void initialize(final ResolvedEnvironment environment, final Configuration configuration) {
    logPreviewFeature(NATIVE_CONNECTORS + " R2DBC for " + getDatabaseType());

    // Initialize the R2DBC connection factory
    connectionFactory = createConnectionFactory(environment, configuration);

    // Get blocking timeout from environment if available
    // TODO: Add r2dbc.blockingTimeout property support when Configuration API provides access
    blockingTimeout = Duration.ofMinutes(5);

    // Establish initial connection to get metadata
    establishConnection();

    currentSchema = getDefaultSchema(configuration);
    connectionType = ConnectionType.R2DBC;
    metaData = getDatabaseMetaData();
  }

  /**
   * Create and configure the R2DBC ConnectionFactory.
   * Database-specific implementations must override to provide their connection setup.
   */
  protected abstract ConnectionFactory createConnectionFactory(
      ResolvedEnvironment environment,
      Configuration configuration);

  /**
   * Establish a connection for initialization and metadata queries.
   */
  protected void establishConnection() {
    try {
      connection = Mono.from(connectionFactory.create())
          .block(blockingTimeout);
      if (connection == null) {
        throw new FlywayException("Failed to establish R2DBC connection: connection was null");
      }
    } catch (Exception e) {
      throw new FlywayException("Failed to establish R2DBC connection", e);
    }
  }

  /**
   * Parse duration string (e.g., "5m", "30s", "1h"). Used for configurable blocking timeout.
   */
  protected Duration parseDuration(String duration) {
    if (duration == null || duration.isEmpty()) {
      return Duration.ofMinutes(5);
    }
    if (duration.endsWith("m")) {
      return Duration.ofMinutes(Long.parseLong(duration.substring(0, duration.length() - 1)));
    } else if (duration.endsWith("s")) {
      return Duration.ofSeconds(Long.parseLong(duration.substring(0, duration.length() - 1)));
    } else if (duration.endsWith("h")) {
      return Duration.ofHours(Long.parseLong(duration.substring(0, duration.length() - 1)));
    } else {
      return Duration.ofMinutes(Long.parseLong(duration));
    }
  }

  @Override
  public boolean canCreateJdbcDataSource() {
    return false;
  }

  @Override
  public boolean supportsBatch() {
    return true;
  }

  @Override
  public boolean supportsTransactions() {
    return true;
  }

  @Override
  public void doExecute(final String executionUnit, final boolean outputQueryResults) {
    try {
      Connection conn = Mono.from(connectionFactory.create())
          .block(blockingTimeout);
      if (conn == null) {
        throw new FlywayException("Failed to get R2DBC connection for execution");
      }

      try {
        Statement statement = conn.createStatement(executionUnit);
        Mono.from(statement.execute())
            .flatMapMany(Result::getRowsUpdated)
            .collectList()
            .block(blockingTimeout);
      } finally {
        Mono.from(conn.close()).block(blockingTimeout);
      }
    } catch (Exception e) {
      throw new FlywayException("Failed to execute migration: " + executionUnit, e);
    }
  }

  @Override
  public void doExecuteBatch() {
    if (batch.isEmpty()) {
      return;
    }

    try {
      Connection conn = Mono.from(connectionFactory.create())
          .block(blockingTimeout);
      if (conn == null) {
        throw new FlywayException("Failed to get R2DBC connection for batch execution");
      }

      try {
        for (String sql : batch) {
          Statement statement = conn.createStatement(sql);
          Mono.from(statement.execute())
              .flatMapMany(Result::getRowsUpdated)
              .collectList()
              .block(blockingTimeout);
        }
        batch.clear();
      } finally {
        Mono.from(conn.close()).block(blockingTimeout);
      }
    } catch (Exception e) {
      throw new FlywayException("Failed to execute batch migration", e);
    }
  }

  @Override
  public void close() {
    if (connection != null) {
      try {
        Mono.from(connection.close()).block(blockingTimeout);
      } catch (Exception ignored) {
        // Connection close errors are not critical
      }
    }
  }

  @Override
  public MetaData getDatabaseMetaData() {
    // Database-specific implementations should override for actual metadata
    return new MetaData("R2DBC", "R2DBC", new DatabaseVersionImpl("0.0.0"));
  }

  @Override
  public SchemaHistoryModel getSchemaHistoryModel(String tableName) {
    // Return empty schema history model for now
    // Database-specific implementations should override to populate with actual data
    return new SchemaHistoryModel();
  }

  /**
   * Database-specific implementations should override for their particular schema.
   */
  @Override
  protected String getDefaultSchema(Configuration configuration) {
    return "public";
  }
}
