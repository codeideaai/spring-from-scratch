---
pagetitle: "Resolve a Constructor Dependency Graph"
---

# 11 Resolve a Constructor Dependency Graph

[中文](../../tutorial/11-把构造依赖变成可检查的图.md) · [Series contents](../README.md)

A service needs a repository, and the repository needs database infrastructure. Factory closures express those relationships but make missing-dependency and cycle diagnosis ad hoc. BeanBox records a type and dependency names, resolves them first, then selects a constructor.

The service remains an ordinary object in this chapter, with an explicit transaction in the caller. Automatic proxying is a separate concern.

## Complete code

Save this entire listing as `Demo11.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Demo11 {
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

  /** Constructor-only singleton container. Configure it on one thread before serving requests. */
  public static final class BeanBox {
    private record Definition(Class<?> type, List<String> dependencies) {}

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> ready = new LinkedHashMap<>();
    private final Set<String> creating = new LinkedHashSet<>();
    private boolean frozen;

    private void editable(String name) {
      if (frozen) throw new IllegalStateException("configuration closed");
      if (ready.containsKey(name) || definitions.containsKey(name))
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public void instance(String name, Object borrowed) {
      editable(name);
      ready.put(name, java.util.Objects.requireNonNull(borrowed));
    }

    public void define(String name, Class<?> type, String... dependencies) {
      editable(name);
      definitions.put(name, new Definition(type, List.of(dependencies)));
    }

    public Object get(String name) {
      frozen = true;
      if (ready.containsKey(name)) return ready.get(name);
      Definition definition = definitions.get(name);
      if (definition == null) throw new IllegalArgumentException("missing bean: " + name);
      if (!creating.add(name))
        throw new IllegalStateException("dependency cycle: " + creating + " -> " + name);
      try {
        Object[] arguments = definition.dependencies().stream().map(this::get).toArray();
        List<Constructor<?>> candidates =
            Arrays.stream(definition.type().getConstructors())
                .filter(c -> compatible(c.getParameterTypes(), arguments))
                .toList();
        if (candidates.size() != 1)
          throw new IllegalArgumentException("ambiguous constructor: " + name);
        Object raw = candidates.get(0).newInstance(arguments);
        Object exposed = raw;
        ready.put(name, exposed);
        return exposed;
      } catch (ReflectiveOperationException failure) {
        Throwable cause =
            failure instanceof InvocationTargetException invocation
                ? invocation.getCause()
                : failure;
        throw new IllegalStateException("creation failed: " + name, cause);
      } finally {
        creating.remove(name);
      }
    }

    private static boolean compatible(Class<?>[] types, Object[] arguments) {
      if (types.length != arguments.length) return false;
      for (int i = 0; i < types.length; i++) if (!types[i].isInstance(arguments[i])) return false;
      return true;
    }

    public <T> T get(String name, Class<T> type) {
      return type.cast(get(name));
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

    BeanBox box = new BeanBox();
    box.instance("store", store);
    box.define("service", ReservationService.class, "store");
    check(box.get("service") == box.get("service"), "constructor injection singleton");
    Reservations service = box.get("service", Reservations.class);
    transactions.run(() -> service.reserve(new Domain.Request("r1", 101, "Lin")));
    BeanBox cycle = new BeanBox();
    cycle.define("a", Object.class, "b");
    cycle.define("b", Object.class, "a");
    try {
      cycle.get("a");
      throw new AssertionError("expected cycle");
    } catch (IllegalStateException expected) {
      check(expected.getMessage().contains("cycle"), "cycle diagnosis");
    }
    check(store.count() == 1, "injected repository");
    System.out.println("graph: singleton=true;cycle=rejected");
  }
}
```

## Compile and run

Use JDK 17. H2 2.2.224 supplies the database runtime; all framework code is printed above. Skip the download if the driver is already present.

```bash
curl -fL -o h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 -encoding UTF-8 Demo11.java
java -cp ".:h2-2.2.224.jar" Demo11
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo11`.

Expected output:

```text
graph: singleton=true;cycle=rejected
```

## Trace the operation

Dependency order determines argument order. Once dependencies exist, use isInstance on each declared parameter type. Exactly one public constructor must match. The experiment does not bind primitive configuration values or convert strings automatically.

Ready contains completed objects only. Creating records the recursive path and rejects a repeated incomplete node. In a constructor cycle, neither object exists yet; an extra early-reference map cannot manufacture one.

Instance registers an object supplied by the caller, while define asks the container to construct one. Lifecycle ownership is introduced next.

## Try it yourself

Reference a missing repository name and inspect the diagnostic. Then provide two constructors compatible with the supplied dependency and verify that ambiguity is rejected instead of depending on reflection order.

Next: [Release Resources When Startup Fails](12-startup-cleanup.md).
