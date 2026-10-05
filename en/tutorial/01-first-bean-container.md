---
pagetitle: "01 — Build Your First Bean Container"
---

# 01 — Build Your First Bean Container

[中文](../../tutorial/01-实现第一个Bean容器.md) · [Series contents](../README.md)

Business code needs a Service, but should not choose an implementation at every call site. We first centralize object creation in a container and let callers retrieve objects by name. This is the minimal form of IoC implemented in this chapter.

The container keeps two maps: `definitions` describes what to create, while `singletons` stores what has already been created. The first lookup invokes a constructor; subsequent lookups return the same object. Separating definitions from instances makes configuration loading, lazy creation and scopes possible later.

This version uses public no-argument constructors. Missing definitions, duplicate registration and reflection failures produce explicit exceptions. The container, business class and verification entry point are all in one runnable file.

## Complete code

Save the entire block as `Demo01.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Use definitions and a singleton cache to distinguish object creation from object reuse. */
public class Demo01 {
  public static class Container {
    // A definition stores creation metadata separately from the actual object in the singleton
    // cache.
    public record Definition(Class<?> type) {}

    final Map<String, Definition> definitions = new LinkedHashMap<>();
    final Map<String, Object> singletons = new HashMap<>();

    public void register(String name, Class<?> type) {
      if (definitions.putIfAbsent(name, new Definition(type)) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public Object getBean(String name) {
      // Return a completed singleton first; objects whose creation failed never enter this cache.
      if (singletons.containsKey(name)) return singletons.get(name);
      Definition definition = definitions.get(name);
      if (definition == null) throw new IllegalArgumentException("no bean: " + name);
      try {
        // Use the public no-argument constructor and publish to the singleton cache only after
        // success.
        Object bean = definition.type().getConstructor().newInstance();
        singletons.put(name, bean);
        return bean;
      } catch (ReflectiveOperationException e) {
        // Unwrap the reflection exception to preserve the actual cause thrown by the constructor.
        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
        throw new IllegalStateException("cannot create: " + name, cause);
      }
    }
  }

  public static class GreetingService {
    public String hello() {
      return "hello Spring";
    }
  }

  public static void main(String[] args) {
    Container c = new Container();
    c.register("greeting", GreetingService.class);
    GreetingService first = (GreetingService) c.getBean("greeting");
    GreetingService second = (GreetingService) c.getBean("greeting");
    check(first == second, "singleton identity");
    System.out.println(first.hello());
    System.out.println("same object: " + (first == second));
    try {
      c.getBean("missing");
      throw new AssertionError("missing definition accepted");
    } catch (IllegalArgumentException expected) {
      System.out.println(expected.getMessage());
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
javac --release 17 Demo01.java
java Demo01
```

Expected output:

```text
hello Spring
same object: true
no bean: missing
```

## Follow the getBean implementation

Look in the completed-object cache first. On a miss, retrieve the definition and call `getConstructor().newInstance()` on its class. Cache the object only after creation succeeds, so a failed constructor cannot leave a supposedly completed object behind.

Reflection may wrap an exception from the business constructor in `InvocationTargetException`. Preserving its cause keeps the diagnostic connected to the actual failure.

A singleton here means one instance per name within one container. Registering the same class under two names, or creating two containers, can still produce different instances. This does not mean a class has only one instance in the entire JVM.

## Try a change

Comment out `singletons.put` and run again: the identity assertion fails. Restore it, then register the same name twice and observe the duplicate-definition error. Compare object identity when testing a container; equal output does not prove that two references point to the same instance.

This chapter assumes single-threaded use. Replacing the map with ConcurrentHashMap would not make the entire lookup-create-cache sequence atomic.

Next: [Turn Configuration into Bean Definitions](02-configuration-to-bean-definitions.md).
