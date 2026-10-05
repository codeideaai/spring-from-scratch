---
pagetitle: "10 — Handle Return Values and Render Views"
---

# 10 — Handle Return Values and Render Views

[中文](../../tutorial/10-处理返回值与渲染视图.md) · [Series contents](../README.md)

After Controller returns, its result must become a response. This chapter supports two paths: String becomes a plain-text body, while ModelAndView combines a logical view name with model data. The String rule is specific to this tutorial, not a claim about the default behavior of every MVC framework.

We implement an in-memory view named user. Controller prepares name and the dispatcher selects its representation. The template is included in the code, with no external JSP, template file or message-conversion library.

Parameter binding from the previous chapter remains in place. Tests cover HTML escaping and responses to business exceptions.

## Complete code

Save the entire block as `Demo10.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

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

/** Distinguish text from model-and-view results, escaping model data when rendering HTML. */
public class Demo10 {
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

    public record ModelAndView(String view, Map<String, Object> model) {}

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
          // Render model-and-view results as HTML and handle ordinary strings as plain text.
          if (value instanceof ModelAndView mv) {
            if (!mv.view().equals("user")) throw new IllegalStateException("unknown view");
            return new Response(
                200,
                "text/html; charset=utf-8",
                "<h1>" + escape(String.valueOf(mv.model().get("name"))) + "</h1>");
          }
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

    // Escape ampersands first so HTML entities introduced by later replacements are not escaped
    // again.
    static String escape(String text) {
      return text.replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
          .replace("\"", "&quot;")
          .replace("'", "&#39;");
    }
  }

  public static class UserController {
    @Mvc.Route(path = "/page")
    public Mvc.ModelAndView page(@Mvc.Param("name") String name) {
      return new Mvc.ModelAndView("user", Map.of("name", name));
    }

    @Mvc.Route(path = "/text")
    public String text(@Mvc.Param("name") String name) {
      return "hello " + name;
    }

    @Mvc.Route(path = "/fail")
    public String fail() {
      throw new IllegalStateException("internal diagnostic");
    }
  }

  public static void main(String[] args) {
    Mvc.Dispatcher dispatcher = new Mvc.Dispatcher();
    dispatcher.register(new UserController());
    Mvc.Response page =
        dispatcher.dispatch(new Mvc.Request("GET", "/page", Map.of("name", "<script>")));
    Mvc.Response text = dispatcher.dispatch(new Mvc.Request("GET", "/text", Map.of("name", "Ada")));
    Mvc.Response failure = dispatcher.dispatch(new Mvc.Request("GET", "/fail", Map.of()));
    check(page.body().equals("<h1>&lt;script&gt;</h1>"), "HTML escaping");
    check(page.contentType().startsWith("text/html"), "HTML content type");
    check(text.body().equals("hello Ada"), "text response");
    check(
        failure.status() == 500 && !failure.body().contains("internal diagnostic"),
        "error response");
    System.out.println(page.body());
    System.out.println(text.body());
    System.out.println(failure.status() + " " + failure.body());
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo10.java
java Demo10
```

Expected output:

```text
<h1>&lt;script&gt;</h1>
hello Ada
500 controller failed
```

## ModelAndView connects data and presentation

model holds business data; view is a logical name. The dispatcher inspects the return type, chooses a view and renders it. To add another view, first move view logic into a registry, then extract lookup into ViewResolver and rendering into View. Service does not need to change.

Unsupported return types produce 500. Calling toString on arbitrary objects is not JSON serialization. General JSON support needs rules for quotes, escaping, arrays, null and nested objects; it is not hidden behind omitted code here.

## Encoding and escaping are separate

HTML escaping prevents characters in name from being interpreted as markup. UTF-8 determines how a string becomes bytes. A real HTTP adapter should call getBytes(UTF_8) and use the byte length for content length, not String.length.

Business failures return a uniform body. Server logs and request identifiers can be added later, but exception stacks should not be sent directly to clients. Parameter errors, business exceptions and rendering failures are identified at different stages.

## Try a change

Pass a name containing ampersands and quotes and inspect the escaping order. Return an unknown view name and confirm a clear failure response. Then add a greeting view while keeping the user view tests passing.

Chapter 17 provides the complete HTTP adapter that writes Response to the network.

Next: [Encapsulate JDBC with a Template](11-jdbc-template.md).
