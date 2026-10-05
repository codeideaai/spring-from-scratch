---
pagetitle: "17 — Build and Test the Complete Application"
---

# 17 — Build and Test the Complete Application

[中文](../../tutorial/17-综合实战与自测.md) · [Series contents](../README.md)

The final application connects all four threads of the series to query and update user names. A request travels through Dispatcher, Controller, an injected transactional proxy, Service, Repository and JDBC to H2.

Every required class appears below. No Java file from an earlier chapter is referenced. Framework components are static nested classes of Demo17. For this longer listing, locate Container, Aop, Mvc, Jdbc, the business classes and main, then trace one call through them.

By default, the program runs integration assertions and exits. Adding serve starts a local HTTP server after the assertions pass. POST requests also use query parameters; JSON request bodies are not implemented.

## Complete code

Save the entire block as `Demo17.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Connect IoC, MVC, AOP and JDBC, verifying request outcomes against a real database. */
public class Demo17 {
  public static final class Container implements AutoCloseable {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Inject {
      String value() default "";
    }

    // Describe a dependency by name and resolve it recursively when the object is created.
    public record Ref(String name) {}

    public static final class Definition {
      final Class<?> type;
      boolean singleton = true;
      boolean lazy;
      Class<?>[] argumentTypes = new Class<?>[0];
      Object[] arguments = new Object[0];
      final Map<String, Object> properties = new LinkedHashMap<>();
      String init;
      String destroy;

      public Definition(Class<?> type) {
        this.type = type;
      }

      public Definition constructor(Class<?>[] types, Object... values) {
        argumentTypes = types.clone();
        arguments = values.clone();
        return this;
      }

      public Definition property(String name, Object value) {
        properties.put(name, value);
        return this;
      }

      public Definition lifecycle(String init, String destroy) {
        this.init = init;
        this.destroy = destroy;
        return this;
      }

      public Definition prototype() {
        singleton = false;
        return this;
      }

      public Definition lazy() {
        lazy = true;
        return this;
      }
    }

    public interface Processor {
      default Object before(Object bean, String name) {
        return bean;
      }

      default Object after(Object bean, String name) {
        return bean;
      }
    }

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> singletons = new LinkedHashMap<>();
    private final Set<String> creating = new LinkedHashSet<>();
    private final List<Processor> processors = new ArrayList<>();
    private final List<Runnable> destruction = new ArrayList<>();
    private final List<Consumer<String>> listeners = new ArrayList<>();
    private final Container parent;
    private boolean frozen;
    private boolean closed;

    public Container() {
      this(null);
    }

    public Container(Container parent) {
      this.parent = parent;
    }

    public void register(String name, Definition definition) {
      if (frozen || closed) throw new IllegalStateException("registration closed");
      if (definitions.putIfAbsent(name, definition) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public void addProcessor(Processor p) {
      if (frozen || closed)
        throw new IllegalStateException("register processors before creating beans");
      processors.add(p);
    }

    public void onEvent(Consumer<String> listener) {
      listeners.add(listener);
    }

    public void refresh() {
      if (frozen || closed) throw new IllegalStateException("refresh only once, before getBean");
      frozen = true;
      try {
        // Pre-create only eager singletons and publish the refresh event after all of them succeed.
        for (var entry : definitions.entrySet())
          if (entry.getValue().singleton && !entry.getValue().lazy) getBean(entry.getKey());
        for (var listener : listeners) listener.accept("refreshed");
      } catch (RuntimeException | Error e) {
        try {
          close();
        } catch (RuntimeException cleanup) {
          e.addSuppressed(cleanup);
        }
        throw e;
      }
    }

    public Object getBean(String name) {
      if (closed) throw new IllegalStateException("container closed");
      frozen = true;
      // Return a completed singleton first; objects whose creation failed never enter this cache.
      if (singletons.containsKey(name)) return singletons.get(name);
      Definition d = definitions.get(name);
      if (d == null) {
        // Local definitions take precedence; delegate to the parent only if the name is absent
        // locally.
        if (parent != null) return parent.getBean(name);
        throw new IllegalArgumentException("no bean: " + name);
      }
      // Re-entering an active creation path indicates a dependency cycle, which this container
      // rejects.
      if (!creating.add(name))
        throw new IllegalStateException("dependency cycle: " + creating + " -> " + name);
      try {
        // Resolve dependencies first, then select the constructor using the explicitly declared
        // parameter types.
        Object[] values = new Object[d.arguments.length];
        for (int i = 0; i < values.length; i++) values[i] = resolve(d.arguments[i]);
        Object raw = d.type.getConstructor(d.argumentTypes).newInstance(values);
        // Inject setters after construction and reject ambiguous overloads instead of guessing.
        for (var property : d.properties.entrySet()) {
          var candidates =
              Arrays.stream(d.type.getMethods())
                  .filter(
                      m ->
                          m.getName().equals("set" + capitalize(property.getKey()))
                              && m.getParameterCount() == 1)
                  .toList();
          if (candidates.size() != 1)
            throw new IllegalArgumentException("setter missing or ambiguous: " + property.getKey());
          candidates.get(0).invoke(raw, resolve(property.getValue()));
        }
        // Walk the superclass hierarchy so inherited injection points are not missed.
        for (Class<?> type = d.type; type != Object.class; type = type.getSuperclass()) {
          for (Field field : type.getDeclaredFields()) {
            Inject inject = field.getAnnotation(Inject.class);
            if (inject == null) continue;
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
              throw new IllegalArgumentException("cannot inject static/final: " + field);
            // Use the explicit name if present; otherwise require one type match instead of picking
            // the first.
            Object value =
                inject.value().isEmpty() ? getBean(field.getType()) : getBean(inject.value());
            if (!field.getType().isInstance(value))
              throw new IllegalArgumentException("incompatible dependency: " + field);
            field.setAccessible(true);
            field.set(raw, value);
          }
        }
        Object exposed = raw;
        // Run before-processors before initialization; after-processors can then wrap the object in
        // a proxy.
        for (Processor p : processors) exposed = Objects.requireNonNull(p.before(exposed, name));
        if (d.init != null) exposed.getClass().getMethod(d.init).invoke(exposed);
        for (Processor p : processors) exposed = Objects.requireNonNull(p.after(exposed, name));
        if (d.singleton) {
          // Cache the final exposed instance; when proxied, later dependency injection must use
          // that same proxy.
          singletons.put(name, exposed);
          // Bind destruction to the raw instance; a JDK interface proxy may not expose its destroy
          // method.
          if (d.destroy != null) destruction.add(() -> invokeDestroy(raw, d.destroy));
        }
        return exposed;
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(
            "cannot create bean: " + name,
            e instanceof InvocationTargetException ? e.getCause() : e);
        // Always clear the creation marker so a later lookup is not mistaken for a dependency
        // cycle.
      } finally {
        creating.remove(name);
      }
    }

    public <T> T getBean(Class<T> type) {
      var names =
          definitions.entrySet().stream()
              .filter(e -> type.isAssignableFrom(e.getValue().type))
              .map(Map.Entry::getKey)
              .toList();
      if (names.isEmpty() && parent != null) return parent.getBean(type);
      // Zero or multiple matches are configuration errors; include candidate names in the
      // diagnostic.
      if (names.size() != 1)
        throw new IllegalArgumentException("expected one " + type.getName() + ", found " + names);
      return type.cast(getBean(names.get(0)));
    }

    private Object resolve(Object value) {
      return value instanceof Ref ref ? getBean(ref.name()) : value;
    }

    private static String capitalize(String s) {
      return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void invokeDestroy(Object bean, String name) {
      try {
        bean.getClass().getMethod(name).invoke(bean);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("destroy failed", e);
      }
    }

    public void close() {
      if (closed) return;
      closed = true;
      RuntimeException failure = null;
      // Destroy registered singletons in reverse order, continuing cleanup if one callback fails.
      for (int i = destruction.size() - 1; i >= 0; i--) {
        try {
          destruction.get(i).run();
        } catch (RuntimeException e) {
          if (failure == null) failure = e;
          else failure.addSuppressed(e);
        }
      }
      singletons.clear();
      destruction.clear();
      if (failure != null) throw failure;
    }
  }

  public static class Aop {
    @FunctionalInterface
    public interface Interceptor {
      Object invoke(Invocation call) throws Throwable;
    }

    public record Advisor(Predicate<Method> pointcut, Interceptor advice) {}

    public static final class Invocation {
      final Object target;
      final Method method;
      final Object[] args;
      final List<Interceptor> chain;
      // The cursor belongs to this invocation; never reuse one Invocation across multiple requests.
      int index = -1;

      Invocation(Object target, Method method, Object[] args, List<Interceptor> chain) {
        this.target = target;
        this.method = method;
        this.args = args;
        this.chain = chain;
      }

      public Object proceed() throws Throwable {
        // Enter advice in registration order, invoke the target at the end, and unwind in reverse
        // order.
        if (++index < chain.size()) return chain.get(index).invoke(this);
        try {
          return method.invoke(target, args);
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      }
    }

    public static Object proxy(Object target, List<Advisor> advisors) {
      // This teaching implementation uses directly declared interfaces without traversing parent
      // classes.
      Class<?>[] interfaces = target.getClass().getInterfaces();
      if (interfaces.length == 0)
        throw new IllegalArgumentException("JDK proxy requires an interface");
      List<Advisor> snapshot = List.copyOf(advisors);
      return Proxy.newProxyInstance(
          target.getClass().getClassLoader(),
          interfaces,
          (proxy, method, args) -> {
            // Use proxy identity for equals and hashCode; forwarding them could break reflexive
            // equality.
            if (method.getDeclaringClass() == Object.class) {
              return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "MiniProxy(" + target.getClass().getName() + ")";
                default -> throw new UnsupportedOperationException(method.getName());
              };
            }
            // Match against the target class method so pointcuts can inspect implementation method
            // metadata.
            Method specific =
                target.getClass().getMethod(method.getName(), method.getParameterTypes());
            var chain =
                snapshot.stream()
                    .filter(a -> a.pointcut().test(specific))
                    .map(Advisor::advice)
                    .toList();
            // Create fresh chain state for each method call so calls do not share an invocation
            // cursor.
            return new Invocation(target, specific, args, chain).proceed();
          });
    }
  }

  public static class Mvc {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Route {
      String path();

      String method() default "GET";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Param {
      String value();
    }

    // The path and HTTP method form the route key, allowing GET and POST to use different handlers.
    record Key(String method, String path) {}

    record Handler(Object bean, Method method) {}

    public record Request(String method, String path, Map<String, String> parameters) {}

    public record Response(int status, String contentType, String body) {}

    public record ModelAndView(String view, Map<String, Object> model) {}

    public static final class Dispatcher {
      final Map<Key, Handler> mappings = new HashMap<>();

      public void register(Object controller) {
        for (Method method : controller.getClass().getMethods()) {
          Route route = method.getAnnotation(Route.class);
          if (route != null
              && mappings.putIfAbsent(
                      new Key(route.method(), route.path()), new Handler(controller, method))
                  != null) throw new IllegalArgumentException("duplicate route: " + route.path());
        }
      }

      public Response dispatch(Request request) {
        Handler handler = mappings.get(new Key(request.method(), request.path()));
        if (handler == null) {
          // Return 405 when the path exists but the method does not match, and 404 when the path is
          // unknown.
          boolean pathExists =
              mappings.keySet().stream().anyMatch(k -> k.path().equals(request.path()));
          return text(pathExists ? 405 : 404, pathExists ? "method not allowed" : "not found");
        }
        try {
          // Build arguments in method parameter order; annotations supply names without compiler
          // name metadata.
          Parameter[] parameters = handler.method().getParameters();
          Object[] args = new Object[parameters.length];
          for (int i = 0; i < parameters.length; i++) {
            Param p = parameters[i].getAnnotation(Param.class);
            if (p == null) throw new IllegalStateException("@Param required");
            String raw = request.parameters().get(p.value());
            if (raw == null) throw new IllegalArgumentException("missing parameter: " + p.value());
            args[i] = convert(raw, parameters[i].getType());
          }
          Object value = handler.method().invoke(handler.bean(), args);
          // Render model-and-view results as HTML and handle ordinary strings as plain text.
          if (value instanceof ModelAndView mv) {
            if (!mv.view().equals("user")) throw new IllegalStateException("unknown view");
            return new Response(
                200,
                "text/html; charset=utf-8",
                "<h1>" + escape(String.valueOf(mv.model().get("name"))) + "</h1>");
          }
          // This example treats String results as plain response bodies, not as view names.
          if (value instanceof String s) return text(200, s);
          throw new IllegalStateException("unsupported return type");
        } catch (IllegalArgumentException e) {
          return text(400, e.getMessage());
        } catch (InvocationTargetException e) {
          return text(500, "controller failed");
        } catch (ReflectiveOperationException | IllegalStateException e) {
          return text(500, "dispatch failed");
        }
      }
    }

    static Object convert(String text, Class<?> type) {
      if (type == String.class) return text;
      if (type == int.class || type == Integer.class) return Integer.valueOf(text);
      if (type == long.class || type == Long.class) return Long.valueOf(text);
      if (type == boolean.class || type == Boolean.class) {
        // Reject misspellings instead of silently converting every string other than true into
        // false.
        if (!text.equals("true") && !text.equals("false"))
          throw new IllegalArgumentException("expected true or false");
        return Boolean.valueOf(text);
      }
      throw new IllegalStateException("no converter: " + type);
    }

    static Response text(int status, String body) {
      return new Response(status, "text/plain; charset=utf-8", body);
    }

    // Escape ampersands first so HTML entities introduced by later replacements are not escaped
    // again.
    static String escape(String text) {
      return text.replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
          .replace("\"", "&quot;")
          .replace("'", "&#39;");
    }
  }

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

  public interface Users {
    String find(long id) throws SQLException;

    void rename(long id, String name) throws SQLException;

    void renameThenFail(long id, String name) throws SQLException;
  }

  public static class Repository {
    final Jdbc.JdbcTemplate jdbc;

    public Repository(Jdbc.JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    public String find(long id) throws SQLException {
      var rows = jdbc.query("select name from users where id=?", rs -> rs.getString(1), id);
      if (rows.size() != 1) throw new IllegalArgumentException("expected exactly one user");
      return rows.get(0);
    }

    public void rename(long id, String name) throws SQLException {
      if (jdbc.update("update users set name=? where id=?", name, id) != 1)
        throw new IllegalArgumentException("expected exactly one update");
    }
  }

  public static class Service implements Users {
    final Repository repository;

    public Service(Repository repository) {
      this.repository = repository;
    }

    public String find(long id) throws SQLException {
      return repository.find(id);
    }

    public void rename(long id, String name) throws SQLException {
      repository.rename(id, name);
    }

    public void renameThenFail(long id, String name) throws SQLException {
      repository.rename(id, name);
      throw new IllegalStateException("abort after update");
    }
  }

  public static class Controller {
    @Container.Inject public Users users;

    @Mvc.Route(path = "/users")
    public String find(@Mvc.Param("id") long id) throws SQLException {
      return users.find(id);
    }

    @Mvc.Route(path = "/rename", method = "POST")
    public String rename(@Mvc.Param("id") long id, @Mvc.Param("name") String name)
        throws SQLException {
      users.rename(id, name);
      return "renamed";
    }

    @Mvc.Route(path = "/rename-fail", method = "POST")
    public String fail(@Mvc.Param("id") long id, @Mvc.Param("name") String name)
        throws SQLException {
      users.renameThenFail(id, name);
      return "unreachable";
    }
  }

  static Object proceed(Aop.Invocation invocation) throws Exception {
    try {
      return invocation.proceed();
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable other) {
      throw new IllegalStateException(other);
    }
  }

  static Map<String, String> query(String raw) {
    Map<String, String> values = new HashMap<>();
    if (raw == null || raw.isEmpty()) return values;
    for (String pair : raw.split("&")) {
      String[] parts = pair.split("=", 2);
      String name = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value =
          java.net.URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
      // Only single-valued parameters are supported; reject duplicates instead of silently
      // overwriting them.
      if (values.putIfAbsent(name, value) != null)
        throw new IllegalArgumentException("duplicate parameter");
    }
    return values;
  }

  static void serve(Mvc.Dispatcher dispatcher, Container container) throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 8080), 0);
    server.createContext(
        "/",
        exchange -> {
          Mvc.Response response;
          try {
            response =
                dispatcher.dispatch(
                    new Mvc.Request(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        query(exchange.getRequestURI().getRawQuery())));
          } catch (IllegalArgumentException e) {
            response = new Mvc.Response(400, "text/plain; charset=utf-8", "invalid query");
          }
          // HTTP content length counts UTF-8 bytes; character counts can differ from the number of
          // bytes sent.
          byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", response.contentType());
          if (response.status() == 405) {
            String allowed =
                String.join(
                    ", ",
                    dispatcher.mappings.keySet().stream()
                        .filter(k -> k.path().equals(exchange.getRequestURI().getPath()))
                        .map(Mvc.Key::method)
                        .sorted()
                        .toList());
            exchange.getResponseHeaders().set("Allow", allowed);
          }
          exchange.sendResponseHeaders(response.status(), bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          } finally {
            exchange.close();
          }
        });
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.stop(0);
                  container.close();
                }));
    server.start();
    System.out.println("Listening on http://127.0.0.1:8080/users?id=7");
    new java.util.concurrent.CountDownLatch(1).await();
  }

  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    Jdbc.Transactions tx =
        new Jdbc.Transactions(
            () -> DriverManager.getConnection("jdbc:h2:mem:demo17;DB_CLOSE_DELAY=-1"));
    Jdbc.JdbcTemplate jdbc = new Jdbc.JdbcTemplate(tx);
    jdbc.update("create table users(id bigint primary key, name varchar(100))");
    jdbc.update("insert into users values(?, ?)", 7L, "Ada");
    try (Container c = new Container()) {
      // Transaction advice surrounds the entire business method: commit on success and roll back on
      // failure.
      Aop.Advisor advisor =
          new Aop.Advisor(
              m -> m.getName().startsWith("rename"), call -> tx.execute(() -> proceed(call)));
      c.addProcessor(
          new Container.Processor() {
            public Object after(Object bean, String name) {
              // Create the proxy after initialization; the container caches and injects this
              // returned value.
              return name.equals("service") ? Aop.proxy(bean, List.of(advisor)) : bean;
            }
          });
      c.register(
          "repository",
          new Container.Definition(Repository.class)
              .constructor(new Class<?>[] {Jdbc.JdbcTemplate.class}, jdbc));
      c.register(
          "service",
          new Container.Definition(Service.class)
              .constructor(new Class<?>[] {Repository.class}, new Container.Ref("repository")));
      c.register("controller", new Container.Definition(Controller.class));
      c.refresh();
      Mvc.Dispatcher dispatcher = new Mvc.Dispatcher();
      dispatcher.register(c.getBean(Controller.class));
      Mvc.Request read = new Mvc.Request("GET", "/users", Map.of("id", "7"));
      check(dispatcher.dispatch(read).body().equals("Ada"), "initial query");
      Mvc.Response updated =
          dispatcher.dispatch(new Mvc.Request("POST", "/rename", Map.of("id", "7", "name", "Lin")));
      check(
          updated.status() == 200 && dispatcher.dispatch(read).body().equals("Lin"),
          "proxy commit");
      Mvc.Response failed =
          dispatcher.dispatch(
              new Mvc.Request("POST", "/rename-fail", Map.of("id", "7", "name", "Bad")));
      // Check the status and read the database again to confirm that the failed update was rolled
      // back.
      check(
          failed.status() == 500 && dispatcher.dispatch(read).body().equals("Lin"),
          "proxy rollback");
      check(tx.current.get() == null, "no thread connection leak");
      System.out.println("initial: Ada");
      System.out.println("after commit: Lin");
      System.out.println("failed request: " + failed.status());
      System.out.println("after rollback: " + dispatcher.dispatch(read).body());
      if (args.length > 0 && args[0].equals("serve")) serve(dispatcher, c);
    }
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
javac --release 17 Demo17.java
java -cp '.:h2-2.2.224.jar' Demo17
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo17`.

Expected output:

```text
initial: Ada
after commit: Lin
failed request: 500
after rollback: Lin
```

## Follow an update through the components

Dispatcher uses POST and the path to find Controller.rename, then binds id and name with @Param. The Controller field holds a Users proxy. The pointcut matches rename and enters the transaction executor before calling the target Service. Repository shares the JdbcTemplate, which obtains the transaction connection from the current thread.

Success commits; a Service exception rolls back and becomes a 500 response from Dispatcher. A subsequent query still returns Lin, proving the failed update did not commit independently. The identity of the injected proxy and the identity of the transaction connection are two invariants spanning this call chain.

## Send actual HTTP requests

Compile using the commands above, then start the server:

```bash
java -cp '.:h2-2.2.224.jar' Demo17 serve
```

On Windows, use `java -cp ".;h2-2.2.224.jar" Demo17 serve`.

In another terminal, run the following. The startup assertions have already changed the user's name to Lin:

```bash
curl -i 'http://127.0.0.1:8080/users?id=7'
curl -i -X POST 'http://127.0.0.1:8080/rename?id=7&name=Grace'
curl -i 'http://127.0.0.1:8080/users?id=7'
curl -i -X POST 'http://127.0.0.1:8080/rename-fail?id=7&name=Bad'
curl -i 'http://127.0.0.1:8080/users?id=7'
```

Expect, in order: 200 Lin, 200 renamed, 200 Grace, 500 controller failed, and 200 Grace. Stop with Ctrl+C. If port 8080 is occupied, change the port in serve and recompile.

The server listens only on localhost. Its adapter uses the UTF-8 byte length, rejects duplicate query parameters, and supplies Allow for 405 responses. It is not a Servlet implementation and has no request-body parsing, authentication or production hosting facilities.

## Self-check questions and answers

1. **Why should Controller not construct Service itself?** It would bypass the injected proxy and therefore the transaction boundary.
2. **Why capture post-processor return values?** They may replace raw with a proxy, and the cache must store that replacement.
3. **Why can a logged rollback still be ineffective?** SQL may have used another auto-commit connection. Compare connection identity first.
4. **Can an early-reference map resolve constructor cycles?** Mutually waiting constructors have not produced objects, so no reference exists to expose.
5. **Can map iteration order determine Java argument order?** No. Follow Method parameter positions and look up each value by name.
6. **Why are prototypes absent from the completed cache?** Each lookup must create a new instance, and resource cleanup needs a separate ownership rule.
7. **Why is self-invocation not advised again?** The internal target call never passes through the external proxy.
8. **Should RowMapper call next?** No. The template owns iteration; the mapper handles the current row.
9. **Does singleton imply thread safety?** No. Creation protocols and shared business state must be considered separately.
10. **Why call ThreadLocal.remove?** A reused thread must not carry an old transaction connection into later work.

## Keep verification as you extend the framework

Possible next steps include DTO binding, SQL result-type configuration, early proxies and transaction propagation. Define success, failure and resource ownership before changing each implementation.

The series now provides complete inline code from a minimal container through actual HTTP and database integration. Every version is a teaching implementation with the boundaries stated in its chapter.

Return to the [series contents](../README.md).
