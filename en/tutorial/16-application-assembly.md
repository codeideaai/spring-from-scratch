---
pagetitle: "Trace One Reservation Through the Whole Application"
---

# 16 Trace One Reservation Through the Whole Application

[中文](../../tutorial/16-把一次预约贯穿到数据库.md) · [Series contents](../README.md)

Connect the components: the controller binds input, the container injects a Reservations proxy, transaction advice surrounds the service, and the repository borrows the thread-bound connection through Jdbc to update two H2 tables.

LibraryApp is the single composition root. It prepares storage, registers definitions and processors, starts the container and freezes routing before accepting requests.

## Complete code

Save this entire listing as `Demo16.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
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
import java.util.function.BiFunction;
import java.util.function.Predicate;

public class Demo16 {
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

  /** Constructor-only singleton container. Configure it on one thread before serving requests. */
  public static final class BeanBox implements AutoCloseable {
    private record Definition(Class<?> type, List<String> dependencies) {}

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> ready = new LinkedHashMap<>();
    private final Set<String> creating = new LinkedHashSet<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private final List<BiFunction<String, Object, Object>> processors = new ArrayList<>();
    private boolean frozen;
    private boolean closed;

    private void editable(String name) {
      if (frozen || closed) throw new IllegalStateException("configuration closed");
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

    public void process(BiFunction<String, Object, Object> processor) {
      if (frozen || closed) throw new IllegalStateException("configuration closed");
      processors.add(processor);
    }

    public Object get(String name) {
      if (closed) throw new IllegalStateException("container closed");
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
        // Track the resource before processors run: even a failed wrapper must release it.
        if (raw instanceof AutoCloseable closeable) owned.add(closeable);
        Object exposed = raw;
        for (var processor : processors)
          exposed = java.util.Objects.requireNonNull(processor.apply(name, exposed));
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

    public void start() {
      try {
        for (String name : definitions.keySet()) get(name);
      } catch (RuntimeException | Error failure) {
        try {
          close();
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    public void close() {
      if (closed) return;
      closed = true;
      RuntimeException failure = null;
      for (int i = owned.size() - 1; i >= 0; i--) {
        try {
          owned.get(i).close();
        } catch (Exception error) {
          if (failure == null) failure = new IllegalStateException("shutdown failed", error);
          else failure.addSuppressed(error);
        }
      }
      owned.clear();
      ready.clear();
      if (failure != null) throw failure;
    }
  }

  /** Transport-independent routing; the HTTP adapter supplies only method, path and query. */
  public static final class Web {
    private Web() {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Route {
      String method();

      String path();

      int status() default 200;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Query {
      String value();
    }

    public record Reply(int status, String body, String allow) {
      public Reply(int status, String body) {
        this(status, body, "");
      }
    }

    private record Key(String method, String path) {}

    private record Handler(Object target, Method method, int status) {}

    public static final class Router {
      private final Map<Key, Handler> routes = new LinkedHashMap<>();
      private boolean frozen;

      public void register(Object controller) {
        if (frozen) throw new IllegalStateException("routes frozen");
        Map<Key, Handler> additions = new LinkedHashMap<>();
        for (Method method : controller.getClass().getMethods()) {
          Route route = method.getAnnotation(Route.class);
          if (route == null) continue;
          if (method.getReturnType() != String.class)
            throw new IllegalArgumentException("expected String result");
          for (Parameter parameter : method.getParameters()) {
            if (!parameter.isAnnotationPresent(Query.class)
                || (parameter.getType() != String.class && parameter.getType() != long.class))
              throw new IllegalArgumentException("unsupported parameter");
          }
          Key key = new Key(route.method(), route.path());
          if (routes.containsKey(key)
              || additions.putIfAbsent(key, new Handler(controller, method, route.status()))
                  != null) throw new IllegalArgumentException("duplicate route");
        }
        routes.putAll(additions);
      }

      public void freeze() {
        frozen = true;
      }

      public Reply dispatch(String verb, String path, Map<String, String> query) {
        if (!frozen) throw new IllegalStateException("freeze routes before dispatch");
        Handler handler = routes.get(new Key(verb, path));
        if (handler == null) {
          String allow =
              String.join(
                  ", ",
                  routes.keySet().stream()
                      .filter(k -> k.path().equals(path))
                      .map(Key::method)
                      .sorted()
                      .toList());
          return allow.isEmpty()
              ? new Reply(404, "route not found")
              : new Reply(405, "method not allowed", allow);
        }
        try {
          Parameter[] parameters = handler.method().getParameters();
          Object[] arguments = new Object[parameters.length];
          for (int i = 0; i < parameters.length; i++) {
            String value = query.get(parameters[i].getAnnotation(Query.class).value());
            if (value == null || value.isBlank())
              throw new Domain.Problem(400, "missing parameter");
            if (parameters[i].getType() == long.class) {
              try {
                arguments[i] = Long.parseLong(value);
              } catch (NumberFormatException failure) {
                throw new Domain.Problem(400, "invalid number");
              }
            } else arguments[i] = value;
          }
          return new Reply(
              handler.status(), (String) handler.method().invoke(handler.target(), arguments));
        } catch (InvocationTargetException failure) {
          if (failure.getCause() instanceof Domain.Problem problem)
            return new Reply(problem.status(), problem.getMessage());
          return new Reply(500, "request failed");
        } catch (Domain.Problem problem) {
          return new Reply(problem.status(), problem.getMessage());
        } catch (ReflectiveOperationException failure) {
          return new Reply(500, "dispatch failed");
        }
      }
    }

    public static final class Controller {
      private final Reservations reservations;

      public Controller(Reservations reservations) {
        this.reservations = reservations;
      }

      @Route(method = "GET", path = "/books")
      public String book(@Query("bookId") long bookId) throws Exception {
        Domain.Book book = reservations.book(bookId);
        return "bookId=" + book.id() + ";available=" + book.available();
      }

      @Route(method = "POST", path = "/reservations", status = 201)
      public String reserve(
          @Query("id") String id, @Query("bookId") long bookId, @Query("member") String member)
          throws Exception {
        Domain.Reservation reservation =
            reservations.reserve(new Domain.Request(id, bookId, member));
        return "reservation=" + reservation.id() + ";member=" + reservation.member();
      }
    }
  }

  /** The composition root: assemble infrastructure before exposing any request handler. */
  public static final class LibraryApp implements AutoCloseable {
    public final Transactions transactions;
    public final ReservationStore store;
    public final Reservations reservations;
    public final Web.Router router;
    private final BeanBox beans;

    public LibraryApp(String url, int copies, Runnable failurePoint) throws Exception {
      Class.forName("org.h2.Driver");
      transactions = new Transactions(() -> DriverManager.getConnection(url));
      store = new ReservationStore(new Jdbc(transactions));
      store.initialize(copies);
      beans = new BeanBox();
      beans.instance("store", store);
      beans.instance("failurePoint", failurePoint);
      beans.define("service", ReservationService.class, "store", "failurePoint");
      beans.define("controller", Web.Controller.class, "service");
      beans.process(
          (name, bean) ->
              name.equals("service")
                  ? Advisors.wrap(
                      Reservations.class,
                      (Reservations) bean,
                      List.of(Advisors.transaction(transactions)))
                  : bean);
      beans.start();
      reservations = beans.get("service", Reservations.class);
      router = new Web.Router();
      router.register(beans.get("controller"));
      router.freeze();
    }

    public void close() {
      beans.close();
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

    try (LibraryApp app = new LibraryApp(database(), 2, () -> {})) {
      check(
          app.router
                  .dispatch(
                      "POST", "/reservations", Map.of("id", "r1", "bookId", "101", "member", "Lin"))
                  .status()
              == 201,
          "create");
      check(
          app.router
                  .dispatch(
                      "POST", "/reservations", Map.of("id", "r2", "bookId", "101", "member", "Lin"))
                  .status()
              == 409,
          "duplicate rollback");
      check(
          app.store.book(101).available() == 1 && app.store.count() == 1, "database conservation");
    }
    try (LibraryApp app =
        new LibraryApp(
            database(),
            2,
            () -> {
              throw new IllegalStateException("injected");
            })) {
      check(
          app.router
                  .dispatch(
                      "POST", "/reservations", Map.of("id", "r1", "bookId", "101", "member", "Lin"))
                  .status()
              == 500,
          "failure");
      check(app.store.book(101).available() == 2 && app.store.count() == 0, "atomic failure");
    }
    System.out.println("application: created=201;duplicate=409;failure=500;atomic=true");
  }
}
```

## Compile and run

Use JDK 17. H2 2.2.224 supplies the database runtime; all framework code is printed above. Skip the download if the driver is already present.

```bash
curl -fL -o h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 -encoding UTF-8 Demo16.java
java -cp ".:h2-2.2.224.jar" Demo16
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo16`.

Expected output:

```text
application: created=201;duplicate=409;failure=500;atomic=true
```

## Trace the operation

Trace POST /reservations: bind a valid Request, enter the proxy transaction, acquire stock through a conditional update, insert the reservation, commit, then return 201. A duplicate constraint failure rolls back and becomes 409 at the routing boundary.

A second application injects failure immediately after stock changes. Assert 500 and then independently query stock two and reservation count zero. The hook is constructor-supplied test instrumentation, not a publicly exposed failure endpoint; normal startup uses a no-op.

Independent in-memory databases isolate test scenarios. Reads may use their own connection; writes must enter through the transactional service. Schema initialization and seed data are outside the reservation transaction.

## Try it yourself

Inject the raw service into the controller and run the failure scenario again. Give the repository a different Transactions instance and explain why equal database URLs do not make separate connections part of the same transaction.

Next: [Verify the System Through Real HTTP and Concurrent Reservations](17-system-acceptance.md).
