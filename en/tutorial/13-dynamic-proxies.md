---
pagetitle: "13 — Enhance Business Methods with Dynamic Proxies"
---

# 13 — Enhance Business Methods with Dynamic Proxies

[中文](../../tutorial/13-用动态代理增强业务方法.md) · [Series contents](../README.md)

Auditing before business methods and timing afterward can scatter repetitive code across Service classes. This chapter sends calls through a proxy, which then invokes the real object.

A JDK dynamic proxy implements business interfaces, with InvocationHandler handling calls. It neither changes the target object nor subclasses its implementation class, so callers depend on Users.

The complete example includes the business interface, implementation, proxy factory and tests, paying particular attention to self-invocation and business exceptions.

## Complete code

Save the entire block as `Demo13.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrate around advice with a JDK interface proxy and show how self-invocation bypasses it.
 */
public class Demo13 {
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

  public static Users proxy(Users target, List<String> trace) {
    return (Users)
        Proxy.newProxyInstance(
            Users.class.getClassLoader(),
            new Class<?>[] {Users.class},
            (proxy, method, args) -> {
              // Use proxy identity for equals and hashCode; forwarding them could break reflexive
              // equality.
              if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                  case "equals" -> proxy == args[0];
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "toString" -> "UsersProxy";
                  default -> throw new UnsupportedOperationException(method.getName());
                };
              }
              boolean enhanced = method.getName().equals("find");
              if (enhanced) trace.add("before");
              try {
                return method.invoke(target, args);
              } catch (InvocationTargetException e) {
                // Unwrap reflection errors so callers and transaction advice receive the original
                // business exception.
                throw e.getCause();
              } finally {
                if (enhanced) trace.add("after");
              }
            });
  }

  public static void main(String[] args) {
    List<String> trace = new ArrayList<>();
    Users users = proxy(new UserService(), trace);
    String result = users.find(7);
    check(result.equals("user-7") && trace.equals(List.of("before", "after")), "proxy invocation");
    System.out.println(result);
    System.out.println(trace);
    trace.clear();
    check(users.outer(7).equals("user-7"), "self invocation result");
    check(trace.isEmpty(), "self invocation must bypass find advice");
    System.out.println("self invocation bypassed advice");
    try {
      users.fail();
      throw new AssertionError("exception hidden");
    } catch (IllegalArgumentException e) {
      check(e.getMessage().equals("business failure"), "original cause");
      System.out.println(e.getMessage());
    }
    check(users.equals(users), "proxy identity equality");
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo13.java
java Demo13
```

Expected output:

```text
user-7
[before, after]
self invocation bypassed advice
business failure
```

## Follow one call

An external users.find call enters the proxy handler, then invokes find on the target. before and the finally-based after surround that invocation while preserving its return value.

Reflection wraps business exceptions in InvocationTargetException. The proxy rethrows the cause so the caller can still handle the original business exception type. This example uses runtime exceptions; checked exceptions not declared by the interface also remain subject to the JDK proxy exception contract.

## Why find inside outer is not advised

The proxy delegates outer to the target, where find is effectively this.find. It does not re-enter the external proxy. An empty trace verifies that having a proxy does not route every internal target call through advice.

Object methods also need defined semantics. equals uses proxy reference identity, hashCode agrees with it, and toString returns descriptive text. Indiscriminately forwarding these methods can even make a proxy unequal to itself.

## Try a change

Match every business method. outer's entry is now advised, but its internal find still does not pass through the proxy again. Make find throw: after in finally still runs. For success-only logic, place it after invoke returns normally.

The next chapter replaces the hard-coded before/after behavior with composable interceptors.

Next: [Build Interceptor Chains and Pointcuts](14-interceptor-chains.md).
