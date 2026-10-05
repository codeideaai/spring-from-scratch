---
pagetitle: "09 — Bind Request Parameters to Method Arguments"
---

# 09 — Bind Request Parameters to Method Arguments

[中文](../../tutorial/09-把请求参数绑定到方法参数.md) · [Series contents](../README.md)

The previous dispatcher invoked methods without arguments. We now want /users?id=7 to reach `find(long id)`. @Param explicitly associates each Java parameter with a request parameter; conversion then turns the string into the declared type.

The argument array must follow Java parameter order, while each value is looked up by its annotation name. Map iteration order is irrelevant. Missing required values and malformed input return 400. A missing converter is a framework capability mismatch and returns 500.

The complete code includes routing, annotations, binding, reflection and tests. In-memory requests keep the parameter semantics easy to observe.

## Complete code

Save the entire block as `Demo09.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * Read and convert annotated parameters, rejecting invalid input before invoking the controller.
 */
public class Demo09 {
  public static class Mvc {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Route {
      String path();

      String method() default "GET";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Param {
      String value();
    }

    // The path and HTTP method form the route key, allowing GET and POST to use different handlers.
    record Key(String method, String path) {}

    record Handler(Object bean, Method method) {}

    public record Request(String method, String path, Map<String, String> parameters) {}

    public record Response(int status, String contentType, String body) {}

    public static final class Dispatcher {
      final Map<Key, Handler> mappings = new HashMap<>();

      public void register(Object controller) {
        for (Method method : controller.getClass().getMethods()) {
          Route route = method.getAnnotation(Route.class);
          if (route != null
              && mappings.putIfAbsent(
                      new Key(route.method(), route.path()), new Handler(controller, method))
                  != null) throw new IllegalArgumentException("duplicate route: " + route.path());
        }
      }

      public Response dispatch(Request request) {
        Handler handler = mappings.get(new Key(request.method(), request.path()));
        if (handler == null) {
          // Return 405 when the path exists but the method does not match, and 404 when the path is
          // unknown.
          boolean pathExists =
              mappings.keySet().stream().anyMatch(k -> k.path().equals(request.path()));
          return text(pathExists ? 405 : 404, pathExists ? "method not allowed" : "not found");
        }
        try {
          // Build arguments in method parameter order; annotations supply names without compiler
          // name metadata.
          Parameter[] parameters = handler.method().getParameters();
          Object[] args = new Object[parameters.length];
          for (int i = 0; i < parameters.length; i++) {
            Param p = parameters[i].getAnnotation(Param.class);
            if (p == null) throw new IllegalStateException("@Param required");
            String raw = request.parameters().get(p.value());
            if (raw == null) throw new IllegalArgumentException("missing parameter: " + p.value());
            args[i] = convert(raw, parameters[i].getType());
          }
          Object value = handler.method().invoke(handler.bean(), args);
          // This example treats String results as plain response bodies, not as view names.
          if (value instanceof String s) return text(200, s);
          throw new IllegalStateException("unsupported return type");
        } catch (IllegalArgumentException e) {
          return text(400, e.getMessage());
        } catch (InvocationTargetException e) {
          return text(500, "controller failed");
        } catch (ReflectiveOperationException | IllegalStateException e) {
          return text(500, "dispatch failed");
        }
      }
    }

    static Object convert(String text, Class<?> type) {
      if (type == String.class) return text;
      if (type == int.class || type == Integer.class) return Integer.valueOf(text);
      if (type == long.class || type == Long.class) return Long.valueOf(text);
      if (type == boolean.class || type == Boolean.class) {
        // Reject misspellings instead of silently converting every string other than true into
        // false.
        if (!text.equals("true") && !text.equals("false"))
          throw new IllegalArgumentException("expected true or false");
        return Boolean.valueOf(text);
      }
      throw new IllegalStateException("no converter: " + type);
    }

    static Response text(int status, String body) {
      return new Response(status, "text/plain; charset=utf-8", body);
    }
  }

  public static class UserController {
    @Mvc.Route(path = "/users")
    public String find(@Mvc.Param("id") long id, @Mvc.Param("active") boolean active) {
      return "user-" + id + ", active=" + active;
    }
  }

  public static void main(String[] args) {
    Mvc.Dispatcher dispatcher = new Mvc.Dispatcher();
    dispatcher.register(new UserController());
    Mvc.Response ok =
        dispatcher.dispatch(new Mvc.Request("GET", "/users", Map.of("active", "true", "id", "7")));
    Mvc.Response bad =
        dispatcher.dispatch(
            new Mvc.Request("GET", "/users", Map.of("id", "bad", "active", "true")));
    Mvc.Response missing = dispatcher.dispatch(new Mvc.Request("GET", "/users", Map.of("id", "7")));
    Mvc.Response invalidBool =
        dispatcher.dispatch(new Mvc.Request("GET", "/users", Map.of("id", "7", "active", "ture")));
    check(ok.status() == 200 && ok.body().equals("user-7, active=true"), "named binding");
    check(
        bad.status() == 400 && missing.status() == 400 && invalidBool.status() == 400,
        "binding failures");
    System.out.println(ok.body());
    System.out.println("bad number: " + bad.status());
    System.out.println("missing value: " + missing.status());
    System.out.println("bad boolean: " + invalidBool.status());
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo09.java
java Demo09
```

Expected output:

```text
user-7, active=true
bad number: 400
missing value: 400
bad boolean: 400
```

## Notice where exceptions originate

convert runs before Controller, so invalid numbers are request errors. Exceptions thrown inside Controller are wrapped in InvocationTargetException and return 500. A business IllegalArgumentException therefore does not accidentally become a 400 response.

Boolean.valueOf treats many invalid strings as false. We first restrict input to true or false. Missing values do not silently become zero or an empty string. Optional arguments and defaults would need explicit rules.

## From scalars to request objects

Supporting a UserQuery object could start by creating a DTO and binding only an allowed set of properties. HTTP input comes from the caller; reusing property-access utilities should not mean exposing every field of an arbitrary business object for binding.

Collections, repeated values and nested property paths are not supported here. Converters also do not keep the previous result in shared temporary fields, which would risk mixing data from different requests.

## Try a change

Add a LocalDate converter and a method receiving a date. Test a valid date, a missing value and an impossible date. Swap the insertion order of id and active in the map: the result must stay the same. Method.getParameters determines argument order, not request parameter ordering.

Next: [Handle Return Values and Render Views](10-return-values-and-views.md).
