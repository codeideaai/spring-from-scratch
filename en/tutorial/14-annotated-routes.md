---
pagetitle: "Declare Routes and Query Bindings Beside Methods"
---

# 14 Declare Routes and Query Bindings Beside Methods

[中文](../../tutorial/14-把路由和参数规则放到方法旁边.md) · [Series contents](../README.md)

As endpoints grow, conditional dispatch repeats itself. Move method, path, success status and parameter names into @Route and @Query, then build a routing table during startup.

Use the same memory-backed reservation rules to isolate routing in this chapter. Chapter 16 connects the router to the transactional database service without changing the API contract.

## Complete code

Save this entire listing as `Demo14.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Demo14 {
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

  /** The first executable model of the reservation rules; no framework is required. */
  public static final class MemoryLibrary {
    private final Map<String, Domain.Reservation> reservations = new LinkedHashMap<>();
    private int available;

    public MemoryLibrary(int copies) {
      if (copies < 0) throw new IllegalArgumentException("negative copies");
      available = copies;
    }

    public synchronized Domain.Book book(long id) {
      if (id != 101) throw new Domain.Problem(404, "book not found");
      return new Domain.Book(101, "The Art of Testing", available);
    }

    public synchronized Domain.Reservation reserve(Domain.Request request) {
      book(request.bookId());
      if (reservations.containsKey(request.id())
          || reservations.values().stream()
              .anyMatch(r -> r.bookId() == request.bookId() && r.member().equals(request.member())))
        throw new Domain.Problem(409, "duplicate reservation");
      if (available == 0) throw new Domain.Problem(409, "no copies available");
      // Validation and both writes form one critical section, not three separate operations.
      Domain.Reservation reservation =
          new Domain.Reservation(request.id(), request.bookId(), request.member());
      reservations.put(reservation.id(), reservation);
      available--;
      return reservation;
    }

    public synchronized int count() {
      return reservations.size();
    }
  }

  public interface Reservations {
    Domain.Book book(long id) throws Exception;

    Domain.Reservation reserve(Domain.Request request) throws Exception;
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

    MemoryLibrary library = new MemoryLibrary(1);
    Reservations service =
        new Reservations() {
          public Domain.Book book(long id) {
            return library.book(id);
          }

          public Domain.Reservation reserve(Domain.Request request) {
            return library.reserve(request);
          }
        };
    Web.Router router = new Web.Router();
    router.register(new Web.Controller(service));
    try {
      router.register(new Web.Controller(service));
      throw new AssertionError("expected duplicate route");
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains("duplicate"), "duplicate check");
    }
    router.freeze();
    check(
        router.dispatch("GET", "/books", Map.of("bookId", "bad")).status() == 400,
        "number binding");
    check(
        router.dispatch("GET", "/reservations", Map.of()).allow().equals("POST"),
        "method contract");
    check(
        router
                .dispatch(
                    "POST", "/reservations", Map.of("member", "Lin", "id", "r1", "bookId", "101"))
                .status()
            == 201,
        "annotation invocation");
    check(
        router
            .dispatch("GET", "/books", Map.of("bookId", "101"))
            .body()
            .equals("bookId=101;available=0"),
        "typed read");
    System.out.println("routes: created=201;invalid=400;duplicate=rejected");
  }
}
```

## Compile and run

Use JDK 17. Only the JDK standard library is required.

```bash
javac --release 17 -encoding UTF-8 Demo14.java
java Demo14
```

Expected output:

```text
routes: created=201;invalid=400;duplicate=rejected
```

## Trace the operation

Registration validates return types, parameter types and duplicate routes. Collect additions temporarily before merging them so a rejected controller cannot leave partial routes. Freeze before dispatch; request threads then read a stable table.

Build the argument array in Method parameter order, obtaining each value by its @Query name rather than map iteration order. Unwrap target exceptions after reflection: expected domain failures retain their statuses, while unexpected failures become 500.

A known path with an unsupported method produces Allow; an unknown path produces 404. Supported parameters are String and long, with String response bodies. This is not a complete Spring MVC or Servlet implementation.

## Try it yourself

Change query-map insertion order and expect identical behavior. Register a controller twice and observe startup rejection. Add an unannotated parameter and verify that the error is discovered before serving a request.

Next: [Load Dependency Metadata from Strict Configuration](15-dependency-configuration.md).
