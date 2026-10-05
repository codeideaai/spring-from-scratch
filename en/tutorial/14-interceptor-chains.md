---
pagetitle: "14 — Build Interceptor Chains and Pointcuts"
---

# 14 — Build Interceptor Chains and Pointcuts

[中文](../../tutorial/14-构造拦截链与切点.md) · [Series contents](../README.md)

A proxy can need auditing, timing, authorization and other advice. Each behavior becomes an Interceptor, and a pointcut decides whether it applies to the current method. Advisor combines the predicate and the advice.

An Interceptor receives Invocation and calls proceed to continue to the next layer. Every proxy call creates a new Invocation, so the chain position is not shared across requests.

The complete example includes Invocation, the proxy factory, Advisor and two pieces of advice. Pointcuts use Java Predicate, without an external expression parser.

## Complete code

Save the entire block as `Demo14.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Build a separate interceptor chain for each call, selecting advice through pointcuts. */
public class Demo14 {
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

  public static void main(String[] args) {
    List<String> trace = new ArrayList<>();
    Aop.Advisor first =
        new Aop.Advisor(
            m -> m.getName().equals("find"),
            call -> {
              trace.add("A before");
              try {
                return call.proceed();
              } finally {
                trace.add("A after");
              }
            });
    Aop.Advisor second =
        new Aop.Advisor(
            m -> m.getName().equals("find"),
            call -> {
              trace.add("B before");
              try {
                Object result = call.proceed();
                trace.add("returned " + result);
                return result;
              } finally {
                trace.add("B after");
              }
            });
    Users users = (Users) Aop.proxy(new UserService(), List.of(first, second));
    check(users.find(7).equals("user-7"), "result");
    check(
        trace.equals(List.of("A before", "B before", "returned user-7", "B after", "A after")),
        "chain order");
    for (String line : trace) System.out.println(line);
    trace.clear();
    users.outer(7);
    check(trace.isEmpty(), "non-matching method");
    System.out.println("non-matching method forwarded");
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo14.java
java Demo14
```

Expected output:

```text
A before
B before
returned user-7
B after
A after
non-matching method forwarded
```

## How proceed advances

index starts at -1. The first proceed enters the first interceptor; its proceed enters the second. Once no interceptor remains, reflection calls the target. Returning unwinds the stack, so after runs for B and then A.

Invocation belongs to one single-threaded call. Do not cache it or treat proceed as a retry API that restarts the whole chain. It remembers its current position.

## Which method gets matched

The Method received by a JDK proxy generally comes from an interface. We first find the concrete method with the same signature on the target class, then ask each Advisor to match it. This also provides the correct place to inspect implementation-method annotations later.

Only interfaces declared directly by the target class are collected; inherited interfaces are not gathered recursively. Objects without interfaces are explicitly rejected. This is a teaching proxy factory, not arbitrary-class enhancement.

## Three kinds of after behavior

Put success logic after proceed returns normally, failure logic in catch, and unconditional cleanup in finally. The example records returned only on success, while B after and A after also run on exceptional paths.

Exercise: make the second interceptor throw before proceed. Verify that the target is skipped but A after still runs. Then return a replacement value directly to understand how around advice can prevent target invocation.

Next: [Integrate Automatic Proxying with IoC](15-automatic-proxying.md).
