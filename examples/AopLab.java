import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 验证方法切点、拦截链顺序、自动代理和异常解包。 */
public class AopLab {
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
    // 游标属于本次调用，不能把同一个 Invocation 复用于多个请求。
    int index = -1;

    Invocation(Object target, Method method, Object[] args, List<Interceptor> chain) {
      this.target = target;
      this.method = method;
      this.args = args;
      this.chain = chain;
    }

    public Object proceed() throws Throwable {
      // 按注册顺序进入增强；链尾才调用目标方法，返回时沿调用栈逆序退出。
      if (++index < chain.size()) return chain.get(index).invoke(this);
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException e) {
        // 解开反射包装，让调用者和事务增强看到原始业务异常。
        throw e.getCause();
      }
    }
  }

  public static Object proxy(Object target, List<Advisor> advisors) {
    // 教学实现只读取目标类直接声明的接口，不递归收集父类接口。
    Class<?>[] interfaces = target.getClass().getInterfaces();
    if (interfaces.length == 0)
      throw new IllegalArgumentException("JDK proxy requires an interface");
    List<Advisor> snapshot = List.copyOf(advisors);
    return Proxy.newProxyInstance(
        target.getClass().getClassLoader(),
        interfaces,
        (proxy, method, args) -> {
          // equals 和 hashCode 使用代理自身的身份语义，避免转发后破坏自反性。
          if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
              case "equals" -> proxy == args[0];
              case "hashCode" -> System.identityHashCode(proxy);
              case "toString" -> "MiniProxy(" + target.getClass().getName() + ")";
              default -> throw new UnsupportedOperationException(method.getName());
            };
          }
          // 切点匹配目标类的方法，才能读取实现方法上的信息。
          Method specific =
              target.getClass().getMethod(method.getName(), method.getParameterTypes());
          var chain =
              snapshot.stream()
                  .filter(a -> a.pointcut().test(specific))
                  .map(Advisor::advice)
                  .toList();
          // 每次方法调用创建独立的链状态，防止不同调用共享游标。
          return new Invocation(target, specific, args, chain).proceed();
        });
  }

  public interface Users {
    String find(long id);

    String outer(long id);

    void fail();
  }

  public static class UserService implements Users {
    static final List<String> trace = new ArrayList<>();

    public String find(long id) {
      trace.add("target");
      return "user-" + id;
    }

    // 这里是目标对象内部的直接调用，不经过代理，因此不会触发 find 的增强。
    public String outer(long id) {
      return find(id);
    }

    public void fail() {
      throw new IllegalArgumentException("business failure");
    }
  }

  public static class Controller {
    @Container.Inject public Users service;
  }

  public static void main(String[] args) {
    List<String> trace = UserService.trace;
    Advisor a =
        new Advisor(
            m -> m.getName().equals("find"),
            call -> {
              trace.add("A before");
              try {
                return call.proceed();
              } finally {
                trace.add("A after");
              }
            });
    Advisor b =
        new Advisor(
            m -> m.getName().equals("find"),
            call -> {
              trace.add("B before");
              try {
                return call.proceed();
              } finally {
                trace.add("B after");
              }
            });
    try (var c = new Container()) {
      c.addProcessor(
          new Container.Processor() {
            public Object after(Object bean, String name) {
              // 初始化完成后创建代理，容器会缓存并注入这个返回值。
              return name.equals("userService") ? proxy(bean, List.of(a, b)) : bean;
            }
          });
      c.register("userService", new Container.Definition(UserService.class));
      c.register("controller", new Container.Definition(Controller.class));
      c.refresh();
      Users service = c.getBean(Users.class);
      IocLab.check(Proxy.isProxyClass(service.getClass()), "automatic proxy");
      IocLab.check(service == c.getBean(Controller.class).service, "injected proxy identity");
      IocLab.check(service.find(7).equals("user-7"), "return value");
      IocLab.check(
          trace.equals(List.of("A before", "B before", "target", "B after", "A after")),
          "chain order");
      trace.clear();
      service.outer(7);
      IocLab.check(trace.equals(List.of("target")), "self invocation bypasses proxy");
      try {
        service.fail();
        throw new AssertionError("exception hidden");
      } catch (IllegalArgumentException e) {
        IocLab.check(e.getMessage().equals("business failure"), "unwrapped exception");
      }
      IocLab.check(service.equals(service), "proxy equality");
    }
    System.out.println(
        "PASS AOP: chain order, automatic proxy, identity, self invocation, exception cause");
  }
}
