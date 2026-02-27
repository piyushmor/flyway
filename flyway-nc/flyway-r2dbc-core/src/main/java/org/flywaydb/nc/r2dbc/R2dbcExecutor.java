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

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.util.function.Function;
import org.flywaydb.core.api.FlywayException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Executor utility for R2DBC operations with blocking semantics.
 * <p>
 * Converts R2DBC's reactive (non-blocking) API to Flyway's synchronous execution model
 * using Mono.block() with a configurable timeout.
 * <p>
 * Usage:
 * <pre>
 * R2dbcExecutor executor = new R2dbcExecutor(connectionFactory, Duration.ofMinutes(5));
 * Result result = executor.execute(connection -> {
 *   Statement stmt = connection.createStatement("SELECT 1");
 *   return stmt.execute();
 * });
 * </pre>
 */
public class R2dbcExecutor {

  private final ConnectionFactory connectionFactory;
  private final Duration blockingTimeout;

  /**
   * Create an R2DBC executor with the given connection factory and timeout.
   *
   * @param connectionFactory the R2DBC connection factory
   * @param blockingTimeout the timeout for blocking operations
   */
  public R2dbcExecutor(ConnectionFactory connectionFactory, Duration blockingTimeout) {
    this.connectionFactory = connectionFactory;
    this.blockingTimeout = blockingTimeout != null ? blockingTimeout : Duration.ofMinutes(5);
  }

  /**
   * Execute an operation using a blocking R2DBC connection.
   * <p>
   * The provided function receives a Connection and returns a Publisher<T> (typically a Mono).
   * The result is extracted by blocking on that Publisher.
   *
   * @param operation the operation to perform (receives Connection, returns Publisher)
   * @param <T> the type of result
   * @return the result of the operation
   * @throws FlywayException if the operation fails
   */
  public <T> T execute(Function<Connection, Publisher<T>> operation) {
    try {
      Connection connection = Mono.from(connectionFactory.create())
          .block(blockingTimeout);
      if (connection == null) {
        throw new FlywayException("Failed to acquire R2DBC connection: returned null");
      }

      try {
        return Mono.from(operation.apply(connection))
            .block(blockingTimeout);
      } finally {
        Mono.from(connection.close()).block(blockingTimeout);
      }
    } catch (Exception e) {
      if (e instanceof FlywayException) {
        throw (FlywayException) e;
      }
      throw new FlywayException("R2DBC operation failed", e);
    }
  }

  /**
   * Execute an operation with a pre-existing connection (no auto-close).
   * Used when the connection is managed externally.
   *
   * @param connection the R2DBC connection to use
   * @param operation the operation to perform
   * @param <T> the type of result
   * @return the result of the operation
   */
  public <T> T executeWithConnection(Connection connection, Function<Connection, Publisher<T>> operation) {
    try {
      return Mono.from(operation.apply(connection))
          .block(blockingTimeout);
    } catch (Exception e) {
      if (e instanceof FlywayException) {
        throw (FlywayException) e;
      }
      throw new FlywayException("R2DBC operation failed", e);
    }
  }

  /**
   * Get the blocking timeout duration.
   */
  public Duration getBlockingTimeout() {
    return blockingTimeout;
  }
}
