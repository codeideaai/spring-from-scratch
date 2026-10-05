# 第五篇 管理 Bean 生命周期与扩展点

[English](../en/tutorial/05-bean-lifecycle.md)

对象完成构造不代表已经就绪。它还要注入属性、进行初始化，必要时被包装，应用关闭时还要清理资源。本篇把这些动作排成确定的生命周期。

Processor.before 和 Processor.after 都返回 Object，因此允许替换对象。容器必须把每次返回值传给后续处理器，并最终缓存处理后的对象。销毁动作则绑定到持有资源的原始实例。

下面仍给出完整容器。相比第三篇，新增处理器列表、初始化方法和逆序销毁列表；注册处理器必须早于首次对象创建。

## 完整代码

将下面整段保存为 `Demo05.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

```java
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 演示初始化前后处理器、最终对象缓存和关闭时的销毁回调。 */
public class Demo05 {
  public static final class Container implements AutoCloseable {
    // 用名称描述依赖，真正创建对象时再向容器递归解析。
    public record Ref(String name) {}

    public static final class Definition {
      final Class<?> type;
      boolean singleton = true;
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
    private boolean closed;

    public void register(String name, Definition definition) {
      if (closed) throw new IllegalStateException("container closed");
      if (definitions.putIfAbsent(name, definition) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public void addProcessor(Processor p) {
      if (!singletons.isEmpty()) throw new IllegalStateException("register processors first");
      processors.add(p);
    }

    public Object getBean(String name) {
      if (closed) throw new IllegalStateException("container closed");
      // 先返回已完成的单例；创建失败的对象不会进入这个缓存。
      if (singletons.containsKey(name)) return singletons.get(name);
      Definition d = definitions.get(name);
      if (d == null) {
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

  public interface Repository {
    String find(long id);
  }

  public static class MemoryRepository implements Repository {
    public String find(long id) {
      return "user-" + id;
    }
  }

  public static class UserService {
    private final Repository repository;
    private String prefix;
    boolean ready;

    public UserService(Repository repository) {
      this.repository = repository;
    }

    public void setPrefix(String prefix) {
      this.prefix = prefix;
    }

    public void init() {
      ready = true;
    }

    public void destroy() {
      ready = false;
    }

    public String find(long id) {
      return prefix + repository.find(id);
    }
  }

  public static void main(String[] args) {
    List<String> trace = new ArrayList<>();
    UserService service;
    try (Container c = new Container()) {
      c.addProcessor(
          new Container.Processor() {
            public Object before(Object bean, String name) {
              if (name.equals("service")) trace.add("before:" + ((UserService) bean).ready);
              return bean;
            }

            public Object after(Object bean, String name) {
              if (name.equals("service")) trace.add("after:" + ((UserService) bean).ready);
              return bean;
            }
          });
      c.register("repository", new Container.Definition(MemoryRepository.class));
      c.register(
          "service",
          new Container.Definition(UserService.class)
              .constructor(new Class<?>[] {Repository.class}, new Container.Ref("repository"))
              .property("prefix", "hello ")
              .lifecycle("init", "destroy"));

      service = (UserService) c.getBean("service");
      check(trace.equals(List.of("before:false", "after:true")), "lifecycle order");
      System.out.println(String.join(", ", trace));
      check(service == c.getBean("service"), "final cached instance");
    }
    check(!service.ready, "destroy callback");
    System.out.println("destroyed: " + !service.ready);
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## 编译与运行

使用 JDK 17，在保存文件的目录执行：

```bash
javac --release 17 Demo05.java
java Demo05
```

预期输出：

```text
before:false, after:true
destroyed: true
```

## 生命周期顺序为什么这样安排

依赖解析和属性注入在前，所以 init 能看到配置完整的对象。before 在 init 之前执行，after 在 init 之后执行；测试通过 ready 的状态检查了这个时序。

关键语句是 `exposed = p.after(exposed, name)`。若只调用 after 而不接收返回值，处理器创建的代理就会丢失。缓存也必须写入 exposed，而不是最初 raw。

## 关闭时怎样善后

单例创建成功后，容器登记原始对象的销毁动作。关闭时按创建完成顺序倒序执行；单个回调失败，不影响尝试剩余回调，后续错误附加到最初异常。重复 close 不再执行回调。

本篇不负责统一销毁原型实例。若某个对象在 init 中取得资源后又抛错，它尚未成功登记销毁动作，也应由自身的失败路径释放已取得的资源。

定义处理器与对象处理器不是同一个阶段。前者修改创建说明，应早于实例化；本篇实现的是对已创建对象的处理。

练习：在 after 中返回一个实现相同接口的装饰器，并比较两次 getBean 的身份。然后故意把缓存值改回 raw，观察第二次调用为什么会失去包装效果。

下一篇：[让注解驱动依赖注入](06-让注解驱动依赖注入.md)。
