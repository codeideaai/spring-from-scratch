package io.github.codeideaai.library;

import java.sql.Connection;
import java.sql.SQLException;

/** One local transaction per thread; nesting is deliberately rejected. */
public final class Transactions {
  @FunctionalInterface
  public interface Source {
    Connection open() throws SQLException;
  }

  @FunctionalInterface
  public interface Work<T> {
    T run() throws Exception;
  }

  @FunctionalInterface
  public interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  private final Source source;
  private final ThreadLocal<Connection> current = new ThreadLocal<>();

  public Transactions(Source source) {
    this.source = source;
  }

  public boolean active() {
    return current.get() != null;
  }

  public <T> T connection(SqlWork<T> work) throws SQLException {
    Connection shared = current.get();
    // A statement borrows the transaction connection; only its owner may close it.
    if (shared != null) return work.run(shared);
    try (Connection connection = source.open()) {
      return work.run(connection);
    }
  }

  public <T> T run(Work<T> work) throws Exception {
    if (active()) throw new IllegalStateException("nested transaction");
    // The application uses fresh DriverManager connections, not a pooled connection lease.
    try (Connection connection = source.open()) {
      if (!connection.getAutoCommit())
        throw new IllegalStateException("transaction already active");
      connection.setAutoCommit(false);
      current.set(connection);
      try {
        T result = work.run();
        connection.commit();
        return result;
      } catch (Exception | Error failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      } finally {
        // Worker threads can be reused. Never leave a closed connection attached to one.
        current.remove();
      }
    }
  }
}
