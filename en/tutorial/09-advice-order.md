---
pagetitle: "Record Success Only After the Transaction Commits"
---

# 09 Record Success Only After the Transaction Commits

[中文](../../tutorial/09-把审计放在事务提交之后.md) · [Series contents](../README.md)

The API needs start, success and failure observations. A returned business method is not necessarily a committed transaction. Put audit outside the transaction advice so committed is recorded only after commit succeeds.

Advice pairs method selection with an Around action. Each invocation creates a Call containing target, method, arguments and its own chain cursor.

## Complete code

Save this entire listing as `Demo09.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public class Demo09 {
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

  /** SQL values are bound as parameters; table and column names belong to application SQL. */
  public static final class Jdbc {
    @FunctionalInterface
    public interface Row<T> {
      T read(ResultSet result) throws SQLException;
    }

    private final Transactions transactions;

    public Jdbc(Transactions transactions) {
      this.transactions = transactions;
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    public int update(String sql, Object... values) throws SQLException {
      return transactions.connection(
          connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
              bind(statement, values);
              return statement.executeUpdate();
            }
          });
    }

    public <T> List<T> query(String sql, Row<T> row, Object... values) throws SQLException {
      return transactions.connection(
          connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
              bind(statement, values);
              try (ResultSet result = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                // Iteration belongs to the template. A row mapper must not call next().
                while (result.next()) rows.add(row.read(result));
                return rows;
              }
            }
          });
    }
  }

  /** Persistence rules for one book and its reservations. */
  public static final class ReservationStore {
    private final Jdbc jdbc;

    public ReservationStore(Jdbc jdbc) {
      this.jdbc = jdbc;
    }

    public void initialize(int copies) throws SQLException {
      jdbc.update(
          "create table if not exists books(id bigint primary key, title varchar(100) not null, "
              + "available int not null check(available >= 0))");
      jdbc.update(
          "create table if not exists reservations(id varchar(40) primary key, "
              + "book_id bigint not null references books(id), member varchar(40) not null, "
              + "unique(book_id, member))");
      // Startup seeds a new database only; reopening must not replenish borrowed copies.
      jdbc.update(
          "insert into books select ?, ?, ? where not exists(select 1 from books where id=?)",
          101L,
          "The Art of Testing",
          copies,
          101L);
    }

    public Domain.Book book(long id) throws SQLException {
      var rows =
          jdbc.query(
              "select id, title, available from books where id=?",
              r -> new Domain.Book(r.getLong(1), r.getString(2), r.getInt(3)),
              id);
      if (rows.isEmpty()) throw new Domain.Problem(404, "book not found");
      return rows.get(0);
    }

    public int count() throws SQLException {
      return jdbc.query("select count(*) from reservations", r -> r.getInt(1)).get(0);
    }

    public Domain.Reservation find(String id) throws SQLException {
      var rows =
          jdbc.query(
              "select id, book_id, member from reservations where id=?",
              r -> new Domain.Reservation(r.getString(1), r.getLong(2), r.getString(3)),
              id);
      if (rows.isEmpty()) throw new Domain.Problem(404, "reservation not found");
      return rows.get(0);
    }

    public Domain.Reservation reserve(Domain.Request request, Runnable afterStockChange)
        throws SQLException {
      book(request.bookId());
      // The database decides the winner. A preceding SELECT alone cannot prevent overselling.
      int changed =
          jdbc.update(
              "update books set available=available-1 where id=? and available>0",
              request.bookId());
      if (changed != 1) throw new Domain.Problem(409, "no copies available");
      afterStockChange.run();
      try {
        jdbc.update(
            "insert into reservations(id, book_id, member) values(?, ?, ?)",
            request.id(),
            request.bookId(),
            request.member());
      } catch (SQLException failure) {
        // A uniqueness failure must escape the transaction so the stock change is rolled back.
        if ("23505".equals(failure.getSQLState()))
          throw new Domain.Problem(409, "duplicate reservation");
        throw failure;
      }
      return new Domain.Reservation(request.id(), request.bookId(), request.member());
    }
  }

  public interface Reservations {
    Domain.Book book(long id) throws Exception;

    Domain.Reservation reserve(Domain.Request request) throws Exception;
  }

  /** The service describes the use case; a proxy supplies its transaction boundary. */
  public static final class ReservationService implements Reservations {
    private final ReservationStore store;
    private final Runnable afterStockChange;

    public ReservationService(ReservationStore store) {
      this(store, () -> {});
    }

    public ReservationService(ReservationStore store, Runnable afterStockChange) {
      this.store = store;
      this.afterStockChange = afterStockChange;
    }

    public Domain.Book book(long id) throws Exception {
      return store.book(id);
    }

    public Domain.Reservation reserve(Domain.Request request) throws Exception {
      return store.reserve(request, afterStockChange);
    }
  }

  /** An invocation owns its cursor; the immutable advice list can be shared between requests. */
  public static final class Advisors {
    private Advisors() {}

    @FunctionalInterface
    public interface Around {
      Object invoke(Call call) throws Throwable;
    }

    public record Advice(Predicate<Method> matches, Around around) {}

    public static final class Call {
      private final Object target;
      private final Method method;
      private final Object[] arguments;
      private final List<Advice> chain;
      private int cursor;

      Call(Object target, Method method, Object[] arguments, List<Advice> chain) {
        this.target = target;
        this.method = method;
        this.arguments = arguments;
        this.chain = chain;
      }

      public Object proceed() throws Throwable {
        if (cursor < chain.size()) return chain.get(cursor++).around().invoke(this);
        try {
          return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
          throw failure.getCause();
        }
      }
    }

    public static <T> T wrap(Class<T> contract, T target, List<Advice> advice) {
      List<Advice> immutable = List.copyOf(advice);
      Object proxy =
          Proxy.newProxyInstance(
              contract.getClassLoader(),
              new Class<?>[] {contract},
              (self, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                  return switch (method.getName()) {
                    case "equals" -> self == arguments[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "Proxy[" + contract.getSimpleName() + "]";
                    default -> throw new IllegalStateException("unknown Object method");
                  };
                }
                Method implementation =
                    target.getClass().getMethod(method.getName(), method.getParameterTypes());
                List<Advice> chain =
                    immutable.stream().filter(a -> a.matches().test(implementation)).toList();
                return new Call(target, implementation, arguments, chain).proceed();
              });
      return contract.cast(proxy);
    }

    public static Advice transaction(Transactions transactions) {
      return new Advice(
          method -> method.getName().equals("reserve"),
          call ->
              transactions.run(
                  () -> {
                    try {
                      return call.proceed();
                    } catch (Exception | Error failure) {
                      throw failure;
                    } catch (Throwable failure) {
                      throw new IllegalStateException(failure);
                    }
                  }));
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
    Transactions transactions = new Transactions(() -> DriverManager.getConnection(url));
    ReservationStore store = new ReservationStore(new Jdbc(transactions));
    store.initialize(2);

    List<String> trace = new ArrayList<>();
    Advisors.Advice audit =
        new Advisors.Advice(
            method -> method.getName().equals("reserve"),
            call -> {
              trace.add("start");
              try {
                Object result = call.proceed();
                trace.add("committed");
                return result;
              } catch (Throwable failure) {
                trace.add("failed");
                throw failure;
              }
            });
    Reservations service =
        Advisors.wrap(
            Reservations.class,
            new ReservationService(store),
            List.of(audit, Advisors.transaction(transactions)));
    service.reserve(new Domain.Request("r1", 101, "Lin"));
    expect(409, () -> service.reserve(new Domain.Request("r2", 101, "Lin")));
    check(trace.equals(List.of("start", "committed", "start", "failed")), "advice order");
    check(store.book(101).available() == 1, "rollback inside audit");
    System.out.println("audit=[start, committed, start, failed]");
  }
}
```

## Compile and run

Use JDK 17. H2 2.2.224 supplies the database runtime; all framework code is printed above. Skip the download if the driver is already present.

```bash
curl -fL -o h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 -encoding UTF-8 Demo09.java
java -cp ".:h2-2.2.224.jar" Demo09
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo09`.

Expected output:

```text
audit=[start, committed, start, failed]
```

## Trace the operation

Proceed enters the next advice or invokes the target when the chain ends. Outer audit records start, enters the transaction and records committed only after it returns. Failure rolls back before audit records failed. Assert the whole trace, not isolated log entries.

Matching uses the target implementation method, which can later carry annotations. Copy the advice list to make it stable; create a fresh mutable Call per invocation. A Call cannot be cached and reused as a retry mechanism.

This audit is an in-process observation, not a durable record atomically committed with business data. Reliable event publication requires another protocol.

## Try it yourself

Swap the advice order and explain why committed might then be recorded before database commit. Add advice that returns without proceed, verify that the target is skipped, and describe the control granted to around advice.

Next: [Share One Assembled Service Across Entry Points](10-service-registry.md).
