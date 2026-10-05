---
pagetitle: "15 — Integrate Automatic Proxying with IoC"
---

# 15 — Integrate Automatic Proxying with IoC

[中文](../../tutorial/15-把自动代理接入IoC.md) · [Series contents](../README.md)

We now connect the proxy factory to IoC. Business callers receive Users without calling newProxyInstance themselves. After initializing the target, the container calls Processor.after, receives a proxy and stores it in the completed-object cache.

The required container and AOP classes are repeated in full so this chapter runs on its own. Focus on processor registration in main and on how exposed is passed through the container.

The main container continues to reject circular dependencies, preventing a raw object from being injected early while a proxy is exposed later.

## Complete code

Save the entire block as `Demo15.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Create proxies during post-processing so dependent objects receive the enhanced instance. */
public class Demo15 {
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

  public interface Users {
    String find(long id);

    String outer(long id);

    void fail();
  }

  public static class UserService implements Users {
    public String find(long id) {
      return "user-" + id;
    }

    // This direct call inside the target bypasses the proxy and therefore skips advice on find.
    public String outer(long id) {
      return find(id);
    }

    public void fail() {
      throw new IllegalArgumentException("business failure");
    }
  }

  public static class Controller {
    @Container.Inject Users users;

    public String find(long id) {
      return users.find(id);
    }
  }

  public static void main(String[] args) {
    List<String> trace = new ArrayList<>();
    try (Container c = new Container()) {
      Aop.Advisor advisor =
          new Aop.Advisor(
              m -> m.getName().equals("find"),
              call -> {
                trace.add("audit");
                return call.proceed();
              });
      c.addProcessor(
          new Container.Processor() {
            public Object after(Object bean, String name) {
              // Create the proxy after initialization; the container caches and injects this
              // returned value.
              return name.equals("service") ? Aop.proxy(bean, List.of(advisor)) : bean;
            }
          });
      c.register("service", new Container.Definition(UserService.class));
      c.register("controller", new Container.Definition(Controller.class));
      c.refresh();
      Users service = c.getBean(Users.class);
      Controller controller = c.getBean(Controller.class);
      check(Proxy.isProxyClass(service.getClass()), "proxy created");
      check(controller.users == service && service == c.getBean("service"), "proxy identity");
      check(controller.find(7).equals("user-7"), "controller result");
      service.find(8);
      check(trace.equals(List.of("audit", "audit")), "both calls enhanced");
      System.out.println("same proxy injected and cached");
      System.out.println(trace);
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
javac --release 17 Demo15.java
java Demo15
```

Expected output:

```text
same proxy injected and cached
[audit, audit]
```

## Cache placement determines correctness

The sequence is: instantiate raw, inject dependencies, run initialization, obtain a proxy from after, and cache exposed. If raw is cached early and only the first caller receives the proxy, later getBean calls return an unadvised object.

The test compares three references: the Controller field, a lookup by interface, and a lookup by name. Calls through two paths must then produce two advice records. A log saying that a proxy was created is not sufficient verification.

## Register processors first

Processors are installed before refresh so ordinary objects pass through them during creation. This example selects targets by a fixed bean name, avoiding accidental wrapping of infrastructure such as the container or processors themselves. Type-based discovery would still need infrastructure exclusions.

FactoryBean is a different protocol: a bean that produces product objects requires distinguishing the factory from its product and defining product caching. Returning the proxy directly is sufficient here; no unimplemented product protocol is needed.

## Try a change

Change the Controller field to concrete UserService and observe why a JDK proxy cannot be assigned to it. Register another processor that also wraps service and examine how nested proxies affect matching and execution order.

Supporting cycles too would require consistent early and final proxy identity. The raw early objects from Chapter 4 cannot simply be inserted here, or callers and dependency fields may receive different objects.

Next: [Connect AOP and JDBC with Transactions](16-transactions.md).
