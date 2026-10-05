package io.github.codeideaai.library;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-independent routing; the HTTP adapter supplies only method, path and query. */
public final class Web {
  private Web() {}

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Route {
    String method();

    String path();

    int status() default 200;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  public @interface Query {
    String value();
  }

  public record Reply(int status, String body, String allow) {
    public Reply(int status, String body) {
      this(status, body, "");
    }
  }

  private record Key(String method, String path) {}

  private record Handler(Object target, Method method, int status) {}

  public static final class Router {
    private final Map<Key, Handler> routes = new LinkedHashMap<>();
    private boolean frozen;

    public void register(Object controller) {
      if (frozen) throw new IllegalStateException("routes frozen");
      Map<Key, Handler> additions = new LinkedHashMap<>();
      for (Method method : controller.getClass().getMethods()) {
        Route route = method.getAnnotation(Route.class);
        if (route == null) continue;
        if (method.getReturnType() != String.class)
          throw new IllegalArgumentException("expected String result");
        for (Parameter parameter : method.getParameters()) {
          if (!parameter.isAnnotationPresent(Query.class)
              || (parameter.getType() != String.class && parameter.getType() != long.class))
            throw new IllegalArgumentException("unsupported parameter");
        }
        Key key = new Key(route.method(), route.path());
        if (routes.containsKey(key)
            || additions.putIfAbsent(key, new Handler(controller, method, route.status())) != null)
          throw new IllegalArgumentException("duplicate route");
      }
      routes.putAll(additions);
    }

    public void freeze() {
      frozen = true;
    }

    public Reply dispatch(String verb, String path, Map<String, String> query) {
      if (!frozen) throw new IllegalStateException("freeze routes before dispatch");
      Handler handler = routes.get(new Key(verb, path));
      if (handler == null) {
        String allow =
            String.join(
                ", ",
                routes.keySet().stream()
                    .filter(k -> k.path().equals(path))
                    .map(Key::method)
                    .sorted()
                    .toList());
        return allow.isEmpty()
            ? new Reply(404, "route not found")
            : new Reply(405, "method not allowed", allow);
      }
      try {
        Parameter[] parameters = handler.method().getParameters();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
          String value = query.get(parameters[i].getAnnotation(Query.class).value());
          if (value == null || value.isBlank()) throw new Domain.Problem(400, "missing parameter");
          if (parameters[i].getType() == long.class) {
            try {
              arguments[i] = Long.parseLong(value);
            } catch (NumberFormatException failure) {
              throw new Domain.Problem(400, "invalid number");
            }
          } else arguments[i] = value;
        }
        return new Reply(
            handler.status(), (String) handler.method().invoke(handler.target(), arguments));
      } catch (InvocationTargetException failure) {
        if (failure.getCause() instanceof Domain.Problem problem)
          return new Reply(problem.status(), problem.getMessage());
        return new Reply(500, "request failed");
      } catch (Domain.Problem problem) {
        return new Reply(problem.status(), problem.getMessage());
      } catch (ReflectiveOperationException failure) {
        return new Reply(500, "dispatch failed");
      }
    }
  }

  public static final class Controller {
    private final Reservations reservations;

    public Controller(Reservations reservations) {
      this.reservations = reservations;
    }

    @Route(method = "GET", path = "/books")
    public String book(@Query("bookId") long bookId) throws Exception {
      Domain.Book book = reservations.book(bookId);
      return "bookId=" + book.id() + ";available=" + book.available();
    }

    @Route(method = "POST", path = "/reservations", status = 201)
    public String reserve(
        @Query("id") String id, @Query("bookId") long bookId, @Query("member") String member)
        throws Exception {
      Domain.Reservation reservation = reservations.reserve(new Domain.Request(id, bookId, member));
      return "reservation=" + reservation.id() + ";member=" + reservation.member();
    }
  }
}
