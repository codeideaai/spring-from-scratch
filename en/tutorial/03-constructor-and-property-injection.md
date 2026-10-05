---
pagetitle: "03 — Implement Constructor and Property Injection"
---

# 03 — Implement Constructor and Property Injection

[中文](../../tutorial/03-实现构造器和属性注入.md) · [Series contents](../README.md)

Our Service now needs a Repository and a string configuration value. Constructor arguments and setter properties are stored in Definition and interpreted by the container during creation.

We introduce `Ref` to distinguish object references from ordinary strings. Ordinary values pass through unchanged; Ref triggers a recursive getBean. Constructor parameter types are declared explicitly as the Repository interface rather than inferred from the dependency's runtime implementation class.

This complete container adds singleton and prototype scopes and creation-cycle detection. Annotations and post-processors come later, keeping the construct-populate-cache sequence easy to follow.

## Complete code

Save the entire block as `Demo03.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Assemble objects by resolving constructor dependencies, constructing and then injecting
 * properties.
 */
public class Demo03 {
  public static final class Container implements AutoCloseable {
    // Describe a dependency by name and resolve it recursively when the object is created.
    public record Ref(String name) {}

    public static final class Definition {
      final Class<?> type;
      boolean singleton = true;
      Class<?>[] argumentTypes = new Class<?>[0];
      Object[] arguments = new Object[0];
      final Map<String, Object> properties = new LinkedHashMap<>();

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

      public Definition prototype() {
        singleton = false;
        return this;
      }
    }

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> singletons = new LinkedHashMap<>();
    private final Set<String> creating = new LinkedHashSet<>();
    private boolean closed;

    public void register(String name, Definition definition) {
      if (closed) throw new IllegalStateException("container closed");
      if (definitions.putIfAbsent(name, definition) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
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
        if (d.singleton) {
          // Cache the final exposed instance; when proxied, later dependency injection must use
          // that same proxy.
          singletons.put(name, exposed);
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

    public void close() {
      closed = true;
      singletons.clear();
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
    try (Container c = new Container()) {
      c.register("repository", new Container.Definition(MemoryRepository.class));
      c.register(
          "service",
          new Container.Definition(UserService.class)
              .constructor(new Class<?>[] {Repository.class}, new Container.Ref("repository"))
              .property("prefix", "hello "));

      c.register("fresh", new Container.Definition(Object.class).prototype());
      UserService service = (UserService) c.getBean("service");
      check(service.find(7).equals("hello user-7"), "constructor and setter");
      check(service == c.getBean("service"), "singleton");
      check(c.getBean("fresh") != c.getBean("fresh"), "prototype");
      System.out.println(service.find(7));
      System.out.println("singleton and prototype verified");
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
javac --release 17 Demo03.java
java Demo03
```

Expected output:

```text
hello user-7
singleton and prototype verified
```

## Resolve constructor arguments

getBean iterates over arguments and resolves each Ref. It then uses argumentTypes to find the exact constructor signature. A Repository parameter requires Repository.class in getConstructor. MemoryRepository is assignable to that parameter, but is not its declared type.

Recursive creation finishes Repository before passing it to UserService. The creating set tracks unfinished creation. A dependency that points back to an active name is rejected, and finally clears the marker.

## Choose a setter

The prefix property maps to setPrefix. We look for public methods with that name and one parameter, require exactly one candidate, resolve the value and invoke it. Overloaded setters are ambiguous in this version; we do not simply choose the first.

Ordinary values in Java definitions must already have the correct type. This chapter does not implicitly convert arbitrary strings into target types. HTTP string conversion is implemented separately in Chapter 9. Reflection's ability to invoke a method does not provide configuration-language conversion rules.

## Try a change

Replace Repository.class with MemoryRepository.class in the constructor type array and observe the missing-constructor error. Restore it, then remove prefix: the object can still be constructed, but its business configuration no longer meets expectations. Structural assembly and business validation have different responsibilities.

Prototype objects are not cached, so each getBean creates a new instance. Closing this version only clears the cache; destruction callbacks arrive in Chapter 5.

Next: [Understand Circular Dependencies and Early References](04-circular-dependencies.md).
