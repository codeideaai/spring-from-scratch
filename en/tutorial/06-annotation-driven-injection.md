---
pagetitle: "06 — Add Annotation-Driven Dependency Injection"
---

# 06 — Add Annotation-Driven Dependency Injection

[中文](../../tutorial/06-让注解驱动依赖注入.md) · [Series contents](../README.md)

Next, fields declare dependencies through annotations. An annotation holds metadata; the container still performs the assignment by discovering fields, selecting an object, checking its type and calling Field.set.

Our custom @Inject uses an explicit name when supplied. Otherwise it requires exactly one type candidate. This is a teaching protocol, without full qualifier semantics, generic collection injection or component scanning.

The complete implementation retains lifecycle support and adds superclass field traversal, rejection of static and final fields, and type-based selection.

## Complete code

Save the entire block as `Demo06.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

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

/**
 * Read field annotations at runtime and resolve dependencies by name or by a unique matching type.
 */
public class Demo06 {
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
    @Container.Inject Repository repository;

    public String find(long id) {
      return repository.find(id);
    }
  }

  public static class OtherRepository implements Repository {
    public String find(long id) {
      return "other-" + id;
    }
  }

  public static void main(String[] args) {
    try (Container c = new Container()) {
      c.register("repository", new Container.Definition(MemoryRepository.class));
      c.register("controller", new Container.Definition(Controller.class));
      Controller controller = c.getBean(Controller.class);
      check(controller.repository == c.getBean("repository"), "injected identity");
      System.out.println(controller.find(7));
    }
    try (Container c = new Container()) {
      c.register("one", new Container.Definition(MemoryRepository.class));
      c.register("two", new Container.Definition(OtherRepository.class));
      try {
        c.getBean(Repository.class);
        throw new AssertionError("ambiguous dependency accepted");
      } catch (IllegalArgumentException expected) {
        System.out.println("ambiguous type rejected");
      }
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
javac --release 17 Demo06.java
java Demo06
```

Expected output:

```text
user-7
ambiguous type rejected
```

## Names and types express different choices

A nonempty annotation value explicitly selects a bean. Otherwise, `type.isAssignableFrom(definition.type)` selects candidates, allowing an interface-typed field to use an implementation class. Zero or multiple candidates are errors; map iteration order must not choose a dependency for business code.

The field name is not used for default matching. Renaming repository therefore does not silently change injection. With two implementations, use `@Container.Inject("one")` to make the choice explicit.

## Where annotation discovery happens

getDeclaredFields returns only fields declared on the current class, so we walk the superclass hierarchy. Static fields belong to the class rather than an individual bean. Final fields should receive values through construction. Both are explicitly rejected in this experiment.

This implements interpretation of injection points, not automatic component discovery. @Component scanning belongs in definition registration; field injection belongs in object population.

## Preserve an interface contract for proxies

A matching definition type does not guarantee that the post-processed object still has the same concrete class. Checking isInstance again before assignment detects incompatible replacements. When using JDK proxies later, Controller should depend on a business interface.

Exercise: move the injected field into a superclass and confirm it still works. Make the field final and confirm rejection. Each rule should have observable success or failure behavior.

Next: [Organize the Application Context and Events](07-application-context-and-events.md).
