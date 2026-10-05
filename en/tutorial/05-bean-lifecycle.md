---
pagetitle: "05 — Manage the Bean Lifecycle and Extension Points"
---

# 05 — Manage the Bean Lifecycle and Extension Points

[中文](../../tutorial/05-管理Bean生命周期与扩展点.md) · [Series contents](../README.md)

Construction alone does not make an object ready. Dependencies must be injected, initialization must run, wrapping may be needed, and resources must be cleaned up when the application closes. This chapter orders those operations into a lifecycle.

Processor.before and Processor.after return Object, allowing replacement. The container must pass each returned value to the next processor and cache the final result. Destruction remains bound to the original instance that owns the resources.

The complete container below adds processors, an initialization method and a reverse-order destruction list. Register processors before creating objects.

## Complete code

Save the entire block as `Demo05.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Demonstrate processing around initialization, caching the exposed object and destruction on
 * close.
 */
public class Demo05 {
  public static final class Container implements AutoCloseable {
    // Describe a dependency by name and resolve it recursively when the object is created.
    public record Ref(String name) {}

    public static final class Definition {
      final Class<?> type;
      boolean singleton = true;
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
    private boolean closed;

    public void register(String name, Definition definition) {
      if (closed) throw new IllegalStateException("container closed");
      if (definitions.putIfAbsent(name, definition) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public void addProcessor(Processor p) {
      if (!singletons.isEmpty()) throw new IllegalStateException("register processors first");
      processors.add(p);
    }

    public Object getBean(String name) {
      if (closed) throw new IllegalStateException("container closed");
      // Return a completed singleton first; objects whose creation failed never enter this cache.
      if (singletons.containsKey(name)) return singletons.get(name);
      Definition d = definitions.get(name);
      if (d == null) {
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

  public interface Repository {
    String find(long id);
  }

  public static class MemoryRepository implements Repository {
    public String find(long id) {
      return "user-" + id;
    }
  }

  public static class UserService {
    private final Repository repository;
    private String prefix;
    boolean ready;

    public UserService(Repository repository) {
      this.repository = repository;
    }

    public void setPrefix(String prefix) {
      this.prefix = prefix;
    }

    public void init() {
      ready = true;
    }

    public void destroy() {
      ready = false;
    }

    public String find(long id) {
      return prefix + repository.find(id);
    }
  }

  public static void main(String[] args) {
    List<String> trace = new ArrayList<>();
    UserService service;
    try (Container c = new Container()) {
      c.addProcessor(
          new Container.Processor() {
            public Object before(Object bean, String name) {
              if (name.equals("service")) trace.add("before:" + ((UserService) bean).ready);
              return bean;
            }

            public Object after(Object bean, String name) {
              if (name.equals("service")) trace.add("after:" + ((UserService) bean).ready);
              return bean;
            }
          });
      c.register("repository", new Container.Definition(MemoryRepository.class));
      c.register(
          "service",
          new Container.Definition(UserService.class)
              .constructor(new Class<?>[] {Repository.class}, new Container.Ref("repository"))
              .property("prefix", "hello ")
              .lifecycle("init", "destroy"));

      service = (UserService) c.getBean("service");
      check(trace.equals(List.of("before:false", "after:true")), "lifecycle order");
      System.out.println(String.join(", ", trace));
      check(service == c.getBean("service"), "final cached instance");
    }
    check(!service.ready, "destroy callback");
    System.out.println("destroyed: " + !service.ready);
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo05.java
java Demo05
```

Expected output:

```text
before:false, after:true
destroyed: true
```

## Why this lifecycle order matters

Dependencies and properties are resolved first, so init sees a configured object. before runs before init and after runs afterward. The test observes ready to verify this ordering.

The crucial assignment is `exposed = p.after(exposed, name)`. Calling after without capturing its result loses any proxy it creates. The cache must also store exposed rather than the original raw instance.

## Clean up on close

After singleton creation succeeds, the container registers a destruction action for the original instance. Closing executes these actions in reverse completion order. One callback failure does not prevent attempts to clean up the rest; later failures are suppressed on the first exception. Repeated close calls do not repeat callbacks.

The container does not centrally destroy prototype instances. An object that acquires resources in init and then throws has not registered a successful destruction action yet; its own failure path must release those resources.

Definition processors and object processors operate at different stages. The former change creation metadata before instantiation. This chapter processes already-created objects.

Exercise: return a decorator implementing the same interface from after and compare two getBean results by identity. Then deliberately cache raw and observe why a later lookup loses the wrapper.

Next: [Add Annotation-Driven Dependency Injection](06-annotation-driven-injection.md).
