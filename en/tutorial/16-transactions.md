---
pagetitle: "16 — Connect AOP and JDBC with Transactions"
---

# 16 — Connect AOP and JDBC with Transactions

[中文](../../tutorial/16-用事务串联AOP与JDBC.md) · [Series contents](../README.md)

Transactions require multiple SQL operations to use the same connection. An interceptor calling commit does not guarantee that business SQL used that connection. The template and transaction manager must share an acquisition protocol.

This chapter implements one level of local transaction on the current thread. Transactions owns a ThreadLocal. JdbcTemplate first looks for a bound connection and opens a temporary one only outside a transaction. Nested transactions are explicitly rejected rather than silently reused without propagation rules.

The complete example includes the manager, transaction-aware template and real H2 checks. The next chapter turns execute into AOP advice at Service method boundaries.

## Complete code

Save the entire block as `Demo16.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Share a thread-bound transaction connection and verify commits and rollbacks in a real database.
 */
public class Demo16 {
  public static class Jdbc {
    @FunctionalInterface
    public interface Connections {
      Connection open() throws SQLException;
    }

    @FunctionalInterface
    public interface Work<T> {
      T run() throws Exception;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
      T map(ResultSet rs) throws SQLException;
    }

    public static final class Transactions {
      final Connections source;
      final ThreadLocal<Connection> current = new ThreadLocal<>();

      public Transactions(Connections source) {
        this.source = source;
      }

      public <T> T execute(Work<T> work) throws Exception {
        if (current.get() != null)
          throw new IllegalStateException("nested transaction unsupported");
        try (Connection connection = source.open()) {
          boolean original = connection.getAutoCommit();
          // This manager owns its transaction and must not take over an existing non-auto-commit
          // transaction.
          if (!original) throw new IllegalStateException("expected an auto-commit connection");
          connection.setAutoCommit(false);
          Throwable failure = null;
          try {
            // Bind the connection to the current thread so template operations in this transaction
            // can share it.
            current.set(connection);
            T result = work.run();
            connection.commit();
            return result;
          } catch (Exception | Error e) {
            failure = e;
            // Keep rollback failures as suppressed exceptions without replacing the original
            // business or commit error.
            try {
              connection.rollback();
            } catch (SQLException rollback) {
              e.addSuppressed(rollback);
            }
            throw e;
          } finally {
            // Threads may be reused, so remove the binding before restoring the connection state.
            current.remove();
            try {
              connection.setAutoCommit(original);
            } catch (SQLException reset) {
              if (failure != null) failure.addSuppressed(reset);
              else throw reset;
            }
          }
        }
      }
    }

    public static final class JdbcTemplate {
      final Transactions transactions;

      public JdbcTemplate(Transactions transactions) {
        this.transactions = transactions;
      }

      @FunctionalInterface
      interface WithConnection<T> {
        T run(Connection c) throws SQLException;
      }

      <T> T withConnection(WithConnection<T> action) throws SQLException {
        Connection bound = transactions.current.get();
        // The transaction manager owns this connection; the template must not close a borrowed
        // connection.
        if (bound != null) return action.run(bound);
        try (Connection c = transactions.source.open()) {
          return action.run(c);
        }
      }

      static void bind(PreparedStatement ps, Object[] values) throws SQLException {
        // JDBC parameter indexes start at 1. Bind values instead of concatenating them into SQL.
        for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
      }

      public int update(String sql, Object... values) throws SQLException {
        return withConnection(
            c -> {
              try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, values);
                return ps.executeUpdate();
              }
            });
      }

      public <T> List<T> query(String sql, RowMapper<T> mapper, Object... values)
          throws SQLException {
        return withConnection(
            c -> {
              try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, values);
                // Finish mapping before the statement closes; returned objects must not retain a
                // JDBC cursor.
                try (ResultSet rs = ps.executeQuery()) {
                  List<T> results = new ArrayList<>();
                  while (rs.next()) results.add(mapper.map(rs));
                  return results;
                }
              }
            });
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    Jdbc.Transactions tx =
        new Jdbc.Transactions(
            () -> DriverManager.getConnection("jdbc:h2:mem:demo16;DB_CLOSE_DELAY=-1"));
    Jdbc.JdbcTemplate jdbc = new Jdbc.JdbcTemplate(tx);
    jdbc.update("create table users(id bigint primary key, name varchar(100))");
    jdbc.update("insert into users values(?, ?)", 7L, "Ada");
    tx.execute(
        () -> {
          Connection first = jdbc.withConnection(c -> c);
          check(first == jdbc.withConnection(c -> c), "same transaction connection");
          jdbc.update("update users set name=? where id=?", "Lin", 7L);
          return null;
        });
    String committed =
        jdbc.query("select name from users where id=?", rs -> rs.getString(1), 7L).get(0);
    try {
      tx.execute(
          () -> {
            jdbc.update("update users set name=? where id=?", "Bad", 7L);
            throw new IllegalArgumentException("abort");
          });
      throw new AssertionError("business exception hidden");
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().equals("abort"), "original error");
    }
    String rolledBack =
        jdbc.query("select name from users where id=?", rs -> rs.getString(1), 7L).get(0);
    check(committed.equals("Lin") && rolledBack.equals("Lin"), "database rollback");
    check(tx.current.get() == null, "thread connection cleanup");
    try {
      tx.execute(() -> tx.execute(() -> null));
      throw new AssertionError("nested transaction accepted");
    } catch (IllegalStateException expected) {
      check(tx.current.get() == null, "nested failure cleanup");
    }
    System.out.println("committed: " + committed);
    System.out.println("after rollback: " + rolledBack);
    System.out.println("connection identity, cleanup and nested rejection verified");
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17. Download the pinned H2 driver into the current directory before the first run; skip the download if it is already present. H2 is only a database runtime dependency. All tutorial framework code is printed above.

```bash
curl -fL --connect-timeout 10 --max-time 60 -o h2-2.2.224.jar \
  https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 Demo16.java
java -cp '.:h2-2.2.224.jar' Demo16
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo16`.

Expected output:

```text
committed: Lin
after rollback: Lin
connection identity, cleanup and nested rejection verified
```

## Who owns the connection?

Outside a transaction, withConnection opens and closes a connection for the operation. Inside a transaction, the template borrows the bound connection and closes only its own statements and result sets. The transaction manager cleans up the connection after the entire unit of work.

ThreadLocal associates a resource with the current thread. It does not make Connection usable across threads or propagate it into asynchronous tasks. Threads may be reused, so finally must call remove.

## Exceptions and cleanup order

Normal completion commits. Failure rolls back and rethrows the original exception. In either case, remove the binding, restore autoCommit and close the connection. Preserve rollback or reset failures as suppressed exceptions where possible.

The manager requires an initially auto-commit connection and does not take over externally started transactions. A commit failure can leave the database outcome uncertain, so the implementation does not automatically retry the entire business operation. A real pool also needs invalid-connection handling.

## Why nested transactions are rejected

Whether an outer transaction may commit after catching an inner failure, whether an inner operation opens a new connection, and how savepoints behave are propagation semantics. Explicit rejection is clearer than sharing silently before those rules exist.

Exercise: remove withConnection's bound-connection branch and run again. The identity assertion fails. Without that assertion, business SQL might auto-commit independently, leaving the outer rollback unable to undo it. Verify transactions by reading the database again, not merely logging rollback.

Next: [Build and Test the Complete Application](17-end-to-end-application.md).
