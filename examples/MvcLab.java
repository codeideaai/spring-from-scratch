import com.sun.net.httpserver.HttpServer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 把请求参数绑定到控制器方法，提供 JDK HTTP 入口，不实现 Servlet 协议。 */
public class MvcLab {
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

  // 路径和 HTTP 方法共同决定路由，GET 与 POST 可以映射到不同处理器。
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
        // 路径存在但方法不匹配返回 405，路径根本不存在才返回 404。
        boolean pathExists =
            mappings.keySet().stream().anyMatch(k -> k.path().equals(request.path()));
        return text(pathExists ? 405 : 404, pathExists ? "method not allowed" : "not found");
      }
      try {
        // 按照方法参数顺序组装实参，参数名由注解指定，不依赖编译器保留名称。
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
        // 模型视图走 HTML 渲染分支，普通字符串走纯文本分支。
        if (value instanceof ModelAndView mv) {
          if (!mv.view().equals("user")) throw new IllegalStateException("unknown view");
          return new Response(
              200,
              "text/html; charset=utf-8",
              "<h1>" + escape(String.valueOf(mv.model().get("name"))) + "</h1>");
        }
        // 本例明确约定：String 是纯文本响应体，不作为视图名解析。
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
      // 拒绝拼写错误，不能把所有非 true 字符串都静默转换为 false。
      if (!text.equals("true") && !text.equals("false"))
        throw new IllegalArgumentException("expected true or false");
      return Boolean.valueOf(text);
    }
    throw new IllegalStateException("no converter: " + type);
  }

  static Response text(int status, String body) {
    return new Response(status, "text/plain; charset=utf-8", body);
  }

  // 先转义 &，避免后续生成的 HTML 实体再次被转义。
  static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  public static class UserController {
    @Container.Inject public AopLab.Users service;

    @Route(path = "/users")
    public String find(@Param("id") long id) {
      return service.find(id);
    }

    @Route(path = "/page")
    public ModelAndView page(@Param("name") String name) {
      return new ModelAndView("user", Map.of("name", name));
    }

    @Route(path = "/fail")
    public String fail() {
      throw new IllegalStateException("private diagnostic");
    }
  }

  static Map<String, String> query(String raw) {
    Map<String, String> result = new HashMap<>();
    if (raw == null || raw.isEmpty()) return result;
    for (String pair : raw.split("&")) {
      String[] parts = pair.split("=", 2);
      String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
      if (result.putIfAbsent(name, value) != null)
        throw new IllegalArgumentException("duplicate query parameter");
    }
    return result;
  }

  public static void main(String[] args) throws Exception {
    var container = new Container();
    container.addProcessor(
        new Container.Processor() {
          public Object after(Object bean, String name) {
            return name.equals("service")
                ? AopLab.proxy(
                    bean,
                    List.of(
                        new AopLab.Advisor(
                            m -> m.getName().equals("find"),
                            call -> {
                              System.out.println("audit find");
                              return call.proceed();
                            })))
                : bean;
          }
        });
    container.register("service", new Container.Definition(AopLab.UserService.class));
    container.register("controller", new Container.Definition(UserController.class));
    container.refresh();
    var dispatcher = new Dispatcher();
    dispatcher.register(container.getBean(UserController.class));
    if (args.length > 0 && args[0].equals("serve")) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
      server.createContext(
          "/",
          exchange -> {
            Response response;
            try {
              response =
                  dispatcher.dispatch(
                      new Request(
                          exchange.getRequestMethod(),
                          exchange.getRequestURI().getPath(),
                          query(exchange.getRequestURI().getRawQuery())));
            } catch (IllegalArgumentException e) {
              response = text(400, "invalid query");
            }
            // HTTP 长度以 UTF-8 字节数计算，中文字符数不等于发送的字节数。
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            if (response.status() == 405) {
              String allowed =
                  String.join(
                      ", ",
                      dispatcher.mappings.keySet().stream()
                          .filter(k -> k.path().equals(exchange.getRequestURI().getPath()))
                          .map(Key::method)
                          .sorted()
                          .toList());
              exchange.getResponseHeaders().set("Allow", allowed);
            }
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (var out = exchange.getResponseBody()) {
              out.write(bytes);
            } finally {
              exchange.close();
            }
          });
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    server.stop(0);
                    container.close();
                  }));
      server.start();
      System.out.println("Listening on http://127.0.0.1:8080/users?id=7");
      return;
    }
    IocLab.check(
        dispatcher
            .dispatch(new Request("GET", "/users", Map.of("id", "7")))
            .body()
            .equals("user-7"),
        "integrated request");
    IocLab.check(
        dispatcher.dispatch(new Request("GET", "/users", Map.of("id", "bad"))).status() == 400,
        "bad parameter");
    IocLab.check(
        dispatcher.dispatch(new Request("GET", "/users", Map.of())).status() == 400,
        "missing parameter");
    IocLab.check(
        dispatcher.dispatch(new Request("GET", "/missing", Map.of())).status() == 404,
        "missing route");
    IocLab.check(
        dispatcher.dispatch(new Request("POST", "/users", Map.of())).status() == 405,
        "method mismatch");
    IocLab.check(
        dispatcher.dispatch(new Request("GET", "/fail", Map.of())).status() == 500,
        "business exception");
    IocLab.check(
        dispatcher
            .dispatch(new Request("GET", "/page", Map.of("name", "<script>")))
            .body()
            .equals("<h1>&lt;script&gt;</h1>"),
        "HTML escaping");
    try {
      dispatcher.register(container.getBean(UserController.class));
      throw new AssertionError("duplicate accepted");
    } catch (IllegalArgumentException expected) {
    }
    container.close();
    System.out.println(
        "PASS MVC: IoC + AOP integration, binding, 400/404/405/500, view escaping, duplicate route");
  }
}
