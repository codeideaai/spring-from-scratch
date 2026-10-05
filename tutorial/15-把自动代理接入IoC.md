# 第十五篇 把自动代理接入 IoC

[English](../en/tutorial/15-automatic-proxying.md)

现在把代理工厂接进 IoC。业务调用者只拿 Users，不再负责 newProxyInstance。容器在目标初始化后执行 Processor.after，返回代理，再把代理写入完成缓存。

下面把所需容器与 AOP 类重新完整列出，因此复制这一篇就能运行，不需要去其他 Java 文件补定义。重点阅读 main 中的处理器注册，以及容器里 exposed 的返回值传递。

主容器继续拒绝循环依赖，避免提前把原始对象注入出去后又对外返回代理。

## 完整代码

将下面整段保存为 `Demo15.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

```java
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 在容器后置处理阶段生成代理，使依赖方拿到的就是增强后的对象。 */
public class Demo15 {
  public static final class Container implements AutoCloseable {
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Inject {
      String value() default "";
    }

    // 用名称描述依赖，真正创建对象时再向容器递归解析。
    public record Ref(String name) {}

    public static final class Definition {
      final Class<?> type;
      boolean singleton = true;
      boolean lazy;
      Class<?>[] argumentTypes = new Class<?>[0];
      Object[] arguments = new Object[0];
      final Map<String, Object> properties = new LinkedHashMap<>();
      String init;
      String destroy;

      public Definition(Class<?> type) {
        this.type = type;
      }

      public Definition constructor(Class<?>[] types, Object... values) {
        argumentTypes = types.clone();
        arguments = values.clone();
        return this;
      }

      public Definition property(String name, Object value) {
        properties.put(name, value);
        return this;
      }

      public Definition lifecycle(String init, String destroy) {
        this.init = init;
        this.destroy = destroy;
        return this;
      }

      public Definition prototype() {
        singleton = false;
        return this;
      }

      public Definition lazy() {
        lazy = true;
        return this;
      }
    }

    public interface Processor {
      default Object before(Object bean, String name) {
        return bean;
      }

      default Object after(Object bean, String name) {
        return bean;
      }
    }

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> singletons = new LinkedHashMap<>();
    private final Set<String> creating = new LinkedHashSet<>();
    private final List<Processor> processors = new ArrayList<>();
    private final List<Runnable> destruction = new ArrayList<>();
    private final List<Consumer<String>> listeners = new ArrayList<>();
    private final Container parent;
    private boolean frozen;
    private boolean closed;

    public Container() {
      this(null);
    }

    public Container(Container parent) {
      this.parent = parent;
    }

    public void register(String name, Definition definition) {
      if (frozen || closed) throw new IllegalStateException("registration closed");
      if (definitions.putIfAbsent(name, definition) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public void addProcessor(Processor p) {
      if (frozen || closed)
        throw new IllegalStateException("register processors before creating beans");
      processors.add(p);
    }

    public void onEvent(Consumer<String> listener) {
      listeners.add(listener);
    }

    public void refresh() {
      if (frozen || closed) throw new IllegalStateException("refresh only once, before getBean");
      frozen = true;
      try {
        // 只预创建非懒加载单例；全部成功后才广播刷新完成事件。
        for (var entry : definitions.entrySet())
          if (entry.getValue().singleton && !entry.getValue().lazy) getBean(entry.getKey());
        for (var listener : listeners) listener.accept("refreshed");
      } catch (RuntimeException | Error e) {
        try {
          close();
        } catch (RuntimeException cleanup) {
          e.addSuppressed(cleanup);
        }
        throw e;
      }
    }

    public Object getBean(String name) {
      if (closed) throw new IllegalStateException("container closed");
      frozen = true;
      // 先返回已完成的单例；创建失败的对象不会进入这个缓存。
      if (singletons.containsKey(name)) return singletons.get(name);
      Definition d = definitions.get(name);
      if (d == null) {
        // 本地定义优先，只有本地找不到名称时才委托父容器。
        if (parent != null) return parent.getBean(name);
        throw new IllegalArgumentException("no bean: " + name);
      }
      // 重复进入同一创建路径说明存在依赖环，本容器选择立即拒绝。
      if (!creating.add(name))
        throw new IllegalStateException("dependency cycle: " + creating + " -> " + name);
      try {
        // 先解析构造器依赖，再按显式声明的参数类型选择构造器。
        Object[] values = new Object[d.arguments.length];
        for (int i = 0; i < values.length; i++) values[i] = resolve(d.arguments[i]);
        Object raw = d.type.getConstructor(d.argumentTypes).newInstance(values);
        // 构造完成后再做 Setter 注入；重载不明确时拒绝猜测。
        for (var property : d.properties.entrySet()) {
          var candidates =
              Arrays.stream(d.type.getMethods())
                  .filter(
                      m ->
                          m.getName().equals("set" + capitalize(property.getKey()))
                              && m.getParameterCount() == 1)
                  .toList();
          if (candidates.size() != 1)
            throw new IllegalArgumentException("setter missing or ambiguous: " + property.getKey());
          candidates.get(0).invoke(raw, resolve(property.getValue()));
        }
        // 逐层扫描父类字段，避免遗漏继承而来的注入点。
        for (Class<?> type = d.type; type != Object.class; type = type.getSuperclass()) {
          for (Field field : type.getDeclaredFields()) {
            Inject inject = field.getAnnotation(Inject.class);
            if (inject == null) continue;
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
              throw new IllegalArgumentException("cannot inject static/final: " + field);
            // 有名称时按名称查找，否则要求类型候选唯一；不能随意取第一个。
            Object value =
                inject.value().isEmpty() ? getBean(field.getType()) : getBean(inject.value());
            if (!field.getType().isInstance(value))
              throw new IllegalArgumentException("incompatible dependency: " + field);
            field.setAccessible(true);
            field.set(raw, value);
          }
        }
        Object exposed = raw;
        // 初始化之前完成前置处理，之后的后置处理器可以把对象包装成代理。
        for (Processor p : processors) exposed = Objects.requireNonNull(p.before(exposed, name));
        if (d.init != null) exposed.getClass().getMethod(d.init).invoke(exposed);
        for (Processor p : processors) exposed = Objects.requireNonNull(p.after(exposed, name));
        if (d.singleton) {
          // 缓存最终暴露的实例；有代理时，后续依赖注入也必须拿到这个代理。
          singletons.put(name, exposed);
          // 销毁回调绑定原始实例，JDK 接口代理可能没有暴露销毁方法。
          if (d.destroy != null) destruction.add(() -> invokeDestroy(raw, d.destroy));
        }
        return exposed;
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(
            "cannot create bean: " + name,
            e instanceof InvocationTargetException ? e.getCause() : e);
        // 成功与失败都清理创建标记，避免下一次查询被误判为循环。
      } finally {
        creating.remove(name);
      }
    }

    public <T> T getBean(Class<T> type) {
      var names =
          definitions.entrySet().stream()
              .filter(e -> type.isAssignableFrom(e.getValue().type))
              .map(Map.Entry::getKey)
              .toList();
      if (names.isEmpty() && parent != null) return parent.getBean(type);
      // 零个候选或多个候选都属于配置错误，错误信息保留候选名称。
      if (names.size() != 1)
        throw new IllegalArgumentException("expected one " + type.getName() + ", found " + names);
      return type.cast(getBean(names.get(0)));
    }

    private Object resolve(Object value) {
      return value instanceof Ref ref ? getBean(ref.name()) : value;
    }

    private static String capitalize(String s) {
      return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void invokeDestroy(Object bean, String name) {
      try {
        bean.getClass().getMethod(name).invoke(bean);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("destroy failed", e);
      }
    }

    public void close() {
      if (closed) return;
      closed = true;
      RuntimeException failure = null;
      // 逆序释放已登记的单例；某个回调失败仍继续清理其余对象。
      for (int i = destruction.size() - 1; i >= 0; i--) {
        try {
          destruction.get(i).run();
        } catch (RuntimeException e) {
          if (failure == null) failure = e;
          else failure.addSuppressed(e);
        }
      }
      singletons.clear();
      destruction.clear();
      if (failure != null) throw failure;
    }
  }

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

    // 这里是目标对象内部的直接调用，不经过代理，因此不会触发 find 的增强。
    public String outer(long id) {
      return find(id);
    }

    public void fail() {
      throw new IllegalArgumentException("business failure");
    }
  }

  public static class Controller {
    @Container.Inject Users users;

    public String find(long id) {
      return users.find(id);
    }
  }

  public static void main(String[] args) {
    List<String> trace = new ArrayList<>();
    try (Container c = new Container()) {
      Aop.Advisor advisor =
          new Aop.Advisor(
              m -> m.getName().equals("find"),
              call -> {
                trace.add("audit");
                return call.proceed();
              });
      c.addProcessor(
          new Container.Processor() {
            public Object after(Object bean, String name) {
              // 初始化完成后创建代理，容器会缓存并注入这个返回值。
              return name.equals("service") ? Aop.proxy(bean, List.of(advisor)) : bean;
            }
          });
      c.register("service", new Container.Definition(UserService.class));
      c.register("controller", new Container.Definition(Controller.class));
      c.refresh();
      Users service = c.getBean(Users.class);
      Controller controller = c.getBean(Controller.class);
      check(Proxy.isProxyClass(service.getClass()), "proxy created");
      check(controller.users == service && service == c.getBean("service"), "proxy identity");
      check(controller.find(7).equals("user-7"), "controller result");
      service.find(8);
      check(trace.equals(List.of("audit", "audit")), "both calls enhanced");
      System.out.println("same proxy injected and cached");
      System.out.println(trace);
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
javac --release 17 Demo15.java
java Demo15
```

预期输出：

```text
same proxy injected and cached
[audit, audit]
```

## 为什么缓存位置决定正确性

完整时序是实例化 raw、注入依赖、执行 init、after 得到代理、缓存 exposed。如果提前缓存 raw，却只向第一次调用者返回代理，后续 getBean 就会取出未增强对象。

测试同时比较三者身份：Controller 字段、按接口获取的对象、按名称获取的对象。再通过两条路径各调用一次，检查增强记录有两条。只看到“创建过代理”的日志不够。

## 处理器必须先注册

处理器在 refresh 前加入，这样普通对象创建时才能经过它。本例按固定 Bean 名称选择目标，避免处理器、容器等基础设施也被意外包装。若以后按类型自动扫描，应该同样设置基础设施排除规则。

FactoryBean 是另一种协议：它是放进容器里生产产品对象的工厂 Bean，需要区分工厂本身与产品、定义产品缓存。这里直接返回代理就足以完成自动增强，不需要引入尚未实现的产品协议。

## 动手修改

把 Controller 字段改成具体 UserService，观察 JDK 代理不能赋给实现类。再注册第二个处理器，让它也包装 service，检查多层代理对匹配规则和执行顺序的影响。

若要同时支持循环依赖，就需要一致的早期代理与最终代理协议。第四篇的早期原始对象不能直接塞进这个实现，否则容器外部和依赖字段可能持有不同身份的对象。

下一篇：[用事务串联AOP与JDBC](16-用事务串联AOP与JDBC.md)。
