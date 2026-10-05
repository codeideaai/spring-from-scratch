---
pagetitle: "08 — Implement MVC Request Dispatch"
---

# 08 — Implement MVC Request Dispatch

[中文](../../tutorial/08-从请求入口实现MVC分发.md) · [Series contents](../README.md)

We now move from obtaining objects to invoking them for requests. Request and Response represent input and output independently of Tomcat or an open network port, making dispatch rules easy to test.

The central mapping is `(HTTP method, path) → (Controller instance, Java method)`. Register routes during startup, then look them up for each request. This first version accepts only no-argument methods returning String; parameter binding follows in the next chapter.

To focus on dispatch, main explicitly assembles Service and Controller. The final application replaces that assembly with our container.

## Complete code

Save the entire block as `Demo08.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Build a routing table ahead of time and dispatch requests to registered controller instances. */
public class Demo08 {
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Route {
    String path();

    String method() default "GET";
  }

  public record Request(String method, String path) {}

  public record Response(int status, String body) {}

  // The path and HTTP method form the route key, allowing GET and POST to use different handlers.
  record Key(String method, String path) {}

  record Handler(Object controller, Method method) {}

  public static class Dispatcher {
    private final Map<Key, Handler> routes = new HashMap<>();

    public void register(Object controller) {
      for (Method method : controller.getClass().getMethods()) {
        Route route = method.getAnnotation(Route.class);
        if (route == null) continue;
        if (method.getParameterCount() != 0 || method.getReturnType() != String.class)
          throw new IllegalArgumentException("this stage requires no-arg String methods");
        Key key = new Key(route.method(), route.path());
        if (routes.putIfAbsent(key, new Handler(controller, method)) != null)
          throw new IllegalArgumentException("duplicate route: " + key);
      }
    }

    public Response dispatch(Request request) {
      Handler handler = routes.get(new Key(request.method(), request.path()));
      if (handler == null) {
        // Distinguish an unknown path (404) from an unsupported method on a known path (405).
        boolean known = routes.keySet().stream().anyMatch(k -> k.path().equals(request.path()));
        return new Response(known ? 405 : 404, known ? "method not allowed" : "not found");
      }
      try {
        return new Response(200, (String) handler.method().invoke(handler.controller()));
      } catch (ReflectiveOperationException e) {
        return new Response(500, "controller failed");
      }
    }
  }

  public static class UserService {
    public String find() {
      return "user-7";
    }
  }

  public static class UserController {
    private final UserService service;

    public UserController(UserService service) {
      this.service = service;
    }

    @Route(path = "/users")
    public String users() {
      return service.find();
    }
  }

  public static void main(String[] args) {
    Dispatcher dispatcher = new Dispatcher();
    UserController controller = new UserController(new UserService());
    dispatcher.register(controller);
    Response ok = dispatcher.dispatch(new Request("GET", "/users"));
    Response missing = dispatcher.dispatch(new Request("GET", "/missing"));
    Response method = dispatcher.dispatch(new Request("POST", "/users"));
    check(ok.status() == 200 && ok.body().equals("user-7"), "dispatch");
    check(missing.status() == 404 && method.status() == 405, "route errors");
    System.out.println(ok.status() + " " + ok.body());
    System.out.println(missing.status() + " " + missing.body());
    System.out.println(method.status() + " " + method.body());
    try {
      dispatcher.register(controller);
      throw new AssertionError("duplicate accepted");
    } catch (IllegalArgumentException expected) {
      System.out.println("duplicate route rejected");
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
javac --release 17 Demo08.java
java Demo08
```

Expected output:

```text
200 user-7
404 not found
405 method not allowed
duplicate route rejected
```

## Why the route key includes the method

The same /users path can use GET for reading and POST for creation. A path-only key cannot register both. Key includes the method and path, and duplicate keys fail during registration.

An unknown path returns 404; a known path with an unsupported method returns 405. Response is still an in-memory model. A real HTTP adapter should also supply an Allow header for 405. An integer status alone is not a complete HTTP implementation.

## Three dispatcher responsibilities

register builds mappings. The first part of dispatch selects a Handler, and the second invokes it through reflection. These responsibilities can later become HandlerMapping and HandlerAdapter. Supporting different handler types gives a concrete reason to extract those interfaces.

Controller receives an already-created Service and does not look up framework objects itself. To integrate IoC, register the Controller returned by the container rather than constructing a second instance without injected dependencies.

## How this relates to Servlet

A Servlet container can deliver a network request to a common entry point that builds Request and calls Dispatcher. IoC manages objects; Servlet or another HTTP server manages the network entry point. Keeping dispatch independent of transport allows a real network adapter without changing routing rules.

Exercise: add a `@Route(path="/users", method="POST")` method and confirm it coexists with GET. Then give two methods the same route key and confirm startup rejects the conflict.

Next: [Bind Request Parameters to Method Arguments](09-request-parameter-binding.md).
