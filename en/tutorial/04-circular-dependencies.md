---
pagetitle: "04 — Understand Circular Dependencies and Early References"
---

# 04 — Understand Circular Dependencies and Early References

[中文](../../tutorial/04-理解循环依赖与早期引用.md) · [Series contents](../README.md)

When A depends on B and B depends on A, the previous container reports a cycle. This separate experiment shows how early references can complete assembly when no-argument construction happens before references are populated.

This does not enable every kind of circular dependency. The experiment covers only single-threaded setter-style cycles without proxies. A constructor must produce an object before an early reference exists. Do not directly merge this factory into the proxy-enabled container built later.

ready stores completed objects, early stores constructed but incompletely populated objects, and creating detects cycles for which no early reference is available yet.

## Complete code

Save the entire block as `Demo04.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Demonstrate setter cycles without proxies; early references cannot resolve constructor cycles.
 */
public class Demo04 {
  record Definition(Supplier<Object> constructor, BiConsumer<Object, Factory> populate) {}

  static class Factory {
    final Map<String, Definition> definitions = new HashMap<>();
    final Map<String, Object> ready = new HashMap<>();
    final Map<String, Object> early = new HashMap<>();
    final Set<String> creating = new HashSet<>();
    boolean failed;

    Object get(String name) {
      if (failed) throw new IllegalStateException("discard failed factory");
      if (ready.containsKey(name)) return ready.get(name);
      if (early.containsKey(name)) return early.get(name);
      Definition d = Objects.requireNonNull(definitions.get(name), "missing " + name);
      // Re-entering an active creation path indicates a dependency cycle, which this container
      // rejects.
      if (!creating.add(name)) throw new IllegalStateException("constructor cycle: " + name);
      try {
        Object bean = d.constructor().get();
        // Expose the constructed object before populating its properties to break a setter
        // dependency cycle.
        early.put(name, bean);
        d.populate().accept(bean, this);
        // Promote the populated object to the completed cache; finally removes its early reference.
        ready.put(name, bean);
        return bean;
      } catch (RuntimeException | Error e) {
        // Completed dependents may hold this unfinished object, so a failure invalidates the whole
        // factory.
        failed = true;
        ready.clear();
        early.clear();
        throw e;
      } finally {
        early.remove(name);
        creating.remove(name);
      }
    }
  }

  static class A {
    B b;
  }

  static class B {
    A a;
  }

  public static void main(String[] args) {
    var f = new Factory();
    f.definitions.put("a", new Definition(A::new, (bean, ctx) -> ((A) bean).b = (B) ctx.get("b")));
    f.definitions.put("b", new Definition(B::new, (bean, ctx) -> ((B) bean).a = (A) ctx.get("a")));
    A a = (A) f.get("a");
    check(a.b.a == a && f.early.isEmpty(), "early identity and cleanup");
    var broken = new Factory();
    broken.definitions.put(
        "a",
        new Definition(
            A::new,
            (bean, ctx) -> {
              ctx.get("b");
              throw new IllegalStateException("init");
            }));
    broken.definitions.put(
        "b", new Definition(B::new, (bean, ctx) -> ((B) bean).a = (A) ctx.get("a")));
    try {
      broken.get("a");
      throw new AssertionError("failure hidden");
    } catch (IllegalStateException expected) {
      check(broken.ready.isEmpty() && broken.early.isEmpty(), "discard all polluted objects");
    }
    System.out.println("PASS cycle: setter identity, early cleanup, failed factory discarded");
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo04.java
java Demo04
```

Expected output:

```text
PASS cycle: setter identity, early cleanup, failed factory discarded
```

## Trace A and B

Construct A and place it in early, then populate A.b. Looking up B constructs B before populating B.a. At that point, get("a") finds the early A. Completing B allows A's population to finish too.

The code asserts `a.b.a == a`. Identity matters more than merely avoiding infinite recursion: constructing a second A could terminate successfully while violating singleton identity.

If dependency resolution happens before construction, early has no object to return and creating detects the cycle. Another cache cannot manufacture an instance that has not been constructed.

## Why failure invalidates the factory

The second test makes A fail during population after B has already received an early reference to A. Removing only A would leave B holding a broken dependency. The experiment clears both caches and marks the factory failed, preventing further use.

Fine-grained recovery needs a dependency graph and an undo protocol, not just removal of one map entry. Early references solve assembly only; they do not make arbitrary business calls during initialization safe.

## The boundary with proxies

If B receives raw A but the container later exposes a proxy for A, B can bypass advice. Supporting both requires a consistent early-proxy protocol. The main container in this series continues to reject cycles.

Exercise: make A's constructor Supplier call get("b"), and B's constructor Supplier call get("a"). Explain the constructor-cycle error. Restore the code and retain both the identity assertion and the failure-cleanup assertion.

Next: [Manage the Bean Lifecycle and Extension Points](05-bean-lifecycle.md).
