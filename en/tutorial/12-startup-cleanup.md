---
pagetitle: "Release Resources When Startup Fails"
---

# 12 Release Resources When Startup Fails

[中文](../../tutorial/12-启动失败时归还已经取得的资源.md) · [Series contents](../README.md)

A catalog subscription opens, then a reservation worker fails because of invalid configuration. Startup must release the earlier resource instead of merely throwing an exception. Add bulk startup, ownership tracking and reverse-order close to BeanBox.

CatalogFeed is an observable resource double, not a real remote subscription. Its event list makes cleanup testable without another service.

## Complete code

Save this entire listing as `Demo12.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Demo12 {
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

  /** Constructor-only singleton container. Configure it on one thread before serving requests. */
  public static final class BeanBox implements AutoCloseable {
    private record Definition(Class<?> type, List<String> dependencies) {}

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> ready = new LinkedHashMap<>();
    private final Set<String> creating = new LinkedHashSet<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
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
        // Startup owns this resource; a later dependency failure must still release it.
        if (raw instanceof AutoCloseable closeable) owned.add(closeable);
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

  public static final class CatalogFeed implements AutoCloseable {
    static final List<String> events = new ArrayList<>();

    public CatalogFeed() {
      events.add("open");
    }

    public void close() {
      events.add("close");
    }
  }

  public static final class ReservationWorker {
    public ReservationWorker(CatalogFeed feed) {
      throw new IllegalStateException("invalid worker configuration");
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

    BeanBox box = new BeanBox();
    box.define("feed", CatalogFeed.class);
    box.define("worker", ReservationWorker.class, "feed");
    try {
      box.start();
      throw new AssertionError("expected failure");
    } catch (IllegalStateException expected) {
      check(expected.getCause() instanceof IllegalStateException, "preserve constructor cause");
    }
    box.close();
    check(CatalogFeed.events.equals(List.of("open", "close")), "close exactly once");
    System.out.println("startup=[open, close];failed=true");
  }
}
```

## Compile and run

Use JDK 17. Only the JDK standard library is required.

```bash
javac --release 17 -encoding UTF-8 Demo12.java
java Demo12
```

Expected output:

```text
startup=[open, close];failed=true
```

## Trace the operation

The container owns AutoCloseable objects it constructs. Objects registered through instance remain borrowed from their caller. Dependencies are normally created first, so reverse close releases consumers before their dependencies.

Start catches creation failure, closes earlier resources and preserves the original failure. Cleanup failures become suppressed details. Close attempts every registered resource and is idempotent, so calling it again does not release twice.

If a constructor acquires a resource and throws before returning an object, that constructor must clean up itself. The container cannot manage an object it never received.

## Try it yourself

Add a second resource and record names to verify reverse order. Make one close fail and check that the other is still attempted and the original startup failure remains visible.

Next: [Expose and Cache the Completed Proxy](13-exposed-proxy.md).
