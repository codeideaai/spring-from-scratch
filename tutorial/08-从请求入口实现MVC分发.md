# 第八篇 从请求入口实现 MVC 分发

[English](../en/tutorial/08-mvc-dispatch.md)

从这一篇开始，把注意力从“怎样得到对象”转到“请求怎样调用对象”。我们先用 Request 和 Response 表示输入输出，不依赖 Tomcat，也不要求打开端口，就能测试分发规则。

核心结构是 `(HTTP 方法, 路径) → (Controller 对象, Java 方法)`。应用启动时登记路由，请求到来后查表。第一版只接受无参数、返回 String 的处理方法；参数绑定留给下一篇。

为了专注分发，本篇 main 显式装配 Service 和 Controller。后面的综合篇会把这段装配替换成前面写好的容器。

## 完整代码

将下面整段保存为 `Demo08.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** 预先建立路由表，把请求分发给已经注册的控制器实例。 */
public class Demo08 {
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Route {
    String path();

    String method() default "GET";
  }

  public record Request(String method, String path) {}

  public record Response(int status, String body) {}

  // 路径和 HTTP 方法共同决定路由，GET 与 POST 可以映射到不同处理器。
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
        // 区分未知路径的 404 与已知路径上不允许的方法的 405。
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

## 编译与运行

使用 JDK 17，在保存文件的目录执行：

```bash
javac --release 17 Demo08.java
java Demo08
```

预期输出：

```text
200 user-7
404 not found
405 method not allowed
duplicate route rejected
```

## 为什么把方法也放进路由键

同一个 /users 可以用 GET 查询、POST 创建。如果只以路径为键，两者无法同时登记。当前 Key 同时包含方法和路径，重复键在注册阶段报错。

路径不存在返回 404；路径存在但方法不支持返回 405。这里的 Response 还是内存模型，若接到真实 HTTP 层，还应为 405 输出 Allow 头。不要把一个整数状态误认为整个 HTTP 协议已经实现。

## 分发器的三个职责

register 负责建立映射，dispatch 的前半段负责选中 Handler，后半段通过反射调用。这分别对应将来可提取的 HandlerMapping 和 HandlerAdapter；等到支持不同处理器类型时，拆分接口就有了具体理由。

Controller 收到已经创建的 Service，不负责查找框架对象。接入 IoC 时，只需把登记到 Dispatcher 的 Controller 换成容器返回值；不要又创建一个没有注入依赖的新 Controller。

## 与 Servlet 的关系

Servlet 容器可以把网络请求交给一个统一入口，再由这个入口构造 Request 调用 Dispatcher。IoC 管理对象，Servlet 或其他 HTTP 服务管理网络入口，这两项职责不同。本文先实现传输无关的分发逻辑，后面可以在不改路由规则的前提下适配真实网络。

练习：再增加一个 `@Route(path="/users", method="POST")` 方法，确认它与 GET 共存；然后把两个方法声明成相同的键，确认启动阶段就拒绝冲突。

下一篇：[把请求参数绑定到方法参数](09-把请求参数绑定到方法参数.md)。
