---
pagetitle: "Commit Stock and Reservation Together"
---

# 06 Commit Stock and Reservation Together

[中文](../../tutorial/06-让扣库存和预约记录一起提交.md) · [Series contents](../README.md)

The previous chapter supplied an executable failure. Change the transaction boundary now: both writes use one connection with auto-commit disabled, commit only after the whole operation succeeds, and roll back on failure.

Transactions owns the connection source and binds the active connection to the current thread. Callers borrow it through connection; run closes it when the transaction ends.

## Complete code

Save this entire listing as `Demo06.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

public class Demo06 {
  /** Small immutable values shared by the application and its adapters. */
  public static final class Domain {
    private Domain() {}

    public record Book(long id, String title, int available) {}

    public record Reservation(String id, long bookId, String member) {}

    public record Request(String id, long bookId, String member) {
      public Request {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,40}"))
          throw new Problem(400, "invalid reservation id");
        if (bookId <= 0) throw new Problem(400, "invalid book id");
        if (member == null || member.isBlank() || member.length() > 40)
          throw new Problem(400, "invalid member");
        member = member.strip();
      }
    }

    public static final class Problem extends RuntimeException {
      private final int status;

      public Problem(int status, String message) {
        super(message);
        this.status = status;
      }

      public int status() {
        return status;
      }
    }
  }

  /** One local transaction per thread; nesting is deliberately rejected. */
  public static final class Transactions {
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

  static void schema(Connection connection) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.execute(
          "create table books(id bigint primary key, available int check(available>=0))");
      statement.execute(
          "create table reservations(id varchar(40) primary key, book_id bigint references books(id), member varchar(40), unique(book_id, member))");
      statement.execute("insert into books values(101, 2)");
    }
  }

  static void write(Connection connection, Domain.Request request, boolean fail) throws Exception {
    try (var statement =
        connection.prepareStatement(
            "update books set available=available-1 where id=? and available>0")) {
      statement.setLong(1, request.bookId());
      if (statement.executeUpdate() != 1) throw new Domain.Problem(409, "no copies available");
    }
    if (fail) throw new IllegalStateException("failure between writes");
    try (var statement = connection.prepareStatement("insert into reservations values(?, ?, ?)")) {
      statement.setString(1, request.id());
      statement.setLong(2, request.bookId());
      statement.setString(3, request.member());
      statement.executeUpdate();
    }
  }

  static int number(Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  @FunctionalInterface
  interface Task {
    void run() throws Exception;
  }

  static void expect(int status, Task task) throws Exception {
    try {
      task.run();
      throw new AssertionError("expected " + status);
    } catch (Domain.Problem problem) {
      check(problem.status() == status, "wrong status");
    }
  }

  static String database() {
    return "jdbc:h2:mem:chapter_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
  }

  public static void main(String[] args) throws Exception {

    Class.forName("org.h2.Driver");
    String url = database();
    try (Connection connection = DriverManager.getConnection(url)) {
      schema(connection);
    }
    Transactions transactions = new Transactions(() -> DriverManager.getConnection(url));
    try {
      transactions.run(
          () ->
              transactions.connection(
                  connection -> {
                    try {
                      write(connection, new Domain.Request("r1", 101, "Lin"), true);
                    } catch (RuntimeException failure) {
                      throw failure;
                    } catch (Exception failure) {
                      throw new java.sql.SQLException(failure);
                    }
                    return null;
                  }));
      throw new AssertionError("expected failure");
    } catch (IllegalStateException expected) {
      check(!transactions.active(), "connection removed");
    }
    try (Connection connection = DriverManager.getConnection(url)) {
      check(number(connection, "select available from books where id=101") == 2, "stock restored");
      check(number(connection, "select count(*) from reservations") == 0, "no reservation");
    }
    System.out.println("rollback: available=2;reservations=0;bound=false");
  }
}
```

## Compile and run

Use JDK 17. H2 2.2.224 supplies the database runtime; all framework code is printed above. Skip the download if the driver is already present.

```bash
curl -fL -o h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 -encoding UTF-8 Demo06.java
java -cp ".:h2-2.2.224.jar" Demo06
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo06`.

Expected output:

```text
rollback: available=2;reservations=0;bound=false
```

## Trace the operation

Run rejects nesting, opens a connection, checks its initial state and binds it. A normal callback return leads to commit. Failure leads to rollback and rethrowing the original exception. A rollback failure is suppressed onto that original failure rather than replacing it.

The finally block removes the thread association so a reused worker cannot inherit an old connection. Within a transaction, connection borrows without closing. Outside one, it opens and closes its own connection. This Source provides fresh DriverManager connections; pooled lease reset is not implemented.

After the same injected failure, stock is two and the reservation count is zero. Query outside the failed transaction to verify that outcome. Schema creation happens separately and does not depend on transactional DDL.

## Try it yourself

Make connection always open a new connection. Explain why rollback can no longer undo those auto-committed writes. Restore sharing, then attempt nested run calls and explain the explicit rejection in the absence of propagation rules.

Next: [Extract JDBC Work Without Losing Connection Ownership](07-jdbc-ownership.md).
