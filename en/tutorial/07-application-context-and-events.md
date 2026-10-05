---
pagetitle: "07 — Organize the Application Context and Events"
---

# 07 — Organize the Application Context and Events

[中文](../../tutorial/07-组织应用上下文与事件.md) · [Series contents](../README.md)

We can create individual objects; now we need to coordinate application startup. refresh registers a clear boundary: prepare definitions and extension points, create eager singletons, then publish the refresh-completed event.

If refresh fails, it closes successfully registered singletons rather than leaving a partially usable container. This context starts only once. The first getBean freezes registration; hot refresh and concurrent creation are not supported.

This chapter also adds parent-child containers. A child looks locally first and delegates only when no local definition exists. A parent never searches its children.

## Complete code

Save the entire block as `Demo07.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Create eager singletons and publish a startup event; children can look up dependencies in a
 * parent.
 */
public class Demo07 {
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

  public static class Controller {
    @Container.Inject public UserService service;
  }

  public static void main(String[] args) {
    List<String> events = new ArrayList<>();
    try (Container parent = new Container()) {
      parent.register("repository", new Container.Definition(MemoryRepository.class));
      parent.register(
          "service",
          new Container.Definition(UserService.class)
              .constructor(new Class<?>[] {Repository.class}, new Container.Ref("repository"))
              .property("prefix", "hello ")
              .lifecycle("init", "destroy"));

      parent.onEvent(events::add);
      parent.refresh();
      check(events.equals(List.of("refreshed")), "refresh event");
      try (Container child = new Container(parent)) {
        child.register("controller", new Container.Definition(Controller.class));
        child.refresh();
        Controller controller = child.getBean(Controller.class);
        check(controller.service == parent.getBean("service"), "parent identity");
        System.out.println(controller.service.find(7));
      }
      check(((UserService) parent.getBean("service")).ready, "child must not close parent");
      System.out.println("event: " + events.get(0));
      System.out.println("parent survives child close");
    }
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo07.java
java Demo07
```

Expected output:

```text
hello user-7
event: refreshed
parent survives child close
```

## refresh coordinates startup

This example combines BeanFactory and ApplicationContext responsibilities in Container to keep the entire process visible. Prepare definitions and processors before refresh. Only eager singletons are pre-created. Prototypes remain on-demand; lazy beans can still be created early if another bean depends on them.

Events use synchronous Consumer callbacks on the startup thread. A listener exception fails refresh. Switching to asynchronous listeners would require redefining whether refresh completion also means that listener tasks have finished.

## Delegate to a parent only when appropriate

Consult the parent only when the local definition is absent. If a local definition exists but construction fails, preserve that error instead of swallowing it and fetching the parent's object with the same name. Type lookup delegates only when there are no local candidates; multiple local candidates remain an ambiguity error.

Closing a child does not close its parent, which may be shared by other children. The test queries the parent after the child closes to verify this resource ownership rule.

## The startup protocol

Assemble objects during single-threaded startup, then pass the resulting business objects to request handlers. Do not mutate Definition or race to create lazy objects from request threads; this version does not implement the required concurrency protocol.

Exercise: register a local service in the child whose constructor fails, and check that the error is preserved. Try refreshing twice and explain why a one-shot context rejects the second start.

Next: [Implement MVC Request Dispatch](08-mvc-dispatch.md).
