---
pagetitle: "Persist Stock and Expose a Partial Commit"
---

# 05 Persist Stock and Expose a Partial Commit

[中文](../../tutorial/05-把库存写进数据库并暴露半笔操作.md) · [Series contents](../README.md)

Move state into two H2 tables: books stores available copies and reservations stores accepted reservations. One use case now requires two SQL writes, a stock decrement and an insertion.

This chapter deliberately retains JDBC auto-commit. Throwing between the writes reproduces a missing copy with no corresponding reservation. The expected output is a counterexample, not a finished implementation.

## Complete code

Save this entire listing as `Demo05.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

public class Demo05 {
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
    try (Connection connection = DriverManager.getConnection(database())) {
      schema(connection);
      write(connection, new Domain.Request("r1", 101, "Lin"), false);
      try {
        write(connection, new Domain.Request("r2", 101, "Ada"), true);
        throw new AssertionError("expected failure");
      } catch (IllegalStateException expected) {
        check(expected.getMessage().contains("between"), "injected failure");
      }
      int available = number(connection, "select available from books where id=101");
      int reservations = number(connection, "select count(*) from reservations");
      // This is a deliberate counterexample: auto-commit has lost a copy without a reservation.
      check(available == 0 && reservations == 1, "expose partial commit");
      System.out.println("auto-commit counterexample: available=0;reservations=1");
    }
  }
}
```

## Compile and run

Use JDK 17. H2 2.2.224 supplies the database runtime; all framework code is printed above. Skip the download if the driver is already present.

```bash
curl -fL -o h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 -encoding UTF-8 Demo05.java
java -cp ".:h2-2.2.224.jar" Demo05
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo05`.

Expected output:

```text
auto-commit counterexample: available=0;reservations=1
```

## Trace the operation

The UPDATE predicate includes available>0. Its affected-row count tells us whether this request acquired stock; a SELECT followed by an unconditional update leaves a race window. The database CHECK constraint adds protection but does not replace application decisions.

PreparedStatement binds input as values. Primary-key and unique constraints cover reservation identifiers and each book/member pair. Actual H2 statements exercise these rules, rather than simulated database logs.

After the first reservation, stock and count are both one. The second decrement commits immediately and the injected failure prevents insertion. Stock becomes zero while the record count remains one. Closing JDBC resources does not undo an auto-committed statement.

## Try it yourself

Move the failure after both writes and observe that both have committed. Explain why returning a failure response cannot repair this database state. The next chapter assigns explicit ownership of commit and rollback.

Next: [Commit Stock and Reservation Together](06-atomic-reservation.md).
