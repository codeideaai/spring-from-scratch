# 第一篇 实现第一个 Bean 容器

[English](../en/tutorial/01-first-bean-container.md)

业务代码需要调用 Service，却不应该在每个调用处决定用哪个实现类。我们先把对象创建集中到一个容器，再让调用者按名称取对象。这就是本篇要实现的最小 IoC。

容器保存两张表：`definitions` 记录“该创建什么”，`singletons` 记录“已经创建了什么”。第一次查询时才调用构造器，之后返回同一个对象。定义不是实例，把两者分开之后，后面才能加入配置读取、延迟创建和作用域。

本篇只用公共无参构造器。没有定义、重复注册、反射失败都会报告明确异常。下面把容器、业务类和验证入口放在同一个文件，复制后即可运行。

## 完整代码

将下面整段保存为 `Demo01.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

```java
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 通过定义表和单例缓存，演示对象创建与对象复用的区别。 */
public class Demo01 {
  public static class Container {
    // 定义只保存创建信息，与单例缓存中的实际对象分开。
    public record Definition(Class<?> type) {}

    final Map<String, Definition> definitions = new LinkedHashMap<>();
    final Map<String, Object> singletons = new HashMap<>();

    public void register(String name, Class<?> type) {
      if (definitions.putIfAbsent(name, new Definition(type)) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public Object getBean(String name) {
      // 先返回已完成的单例；创建失败的对象不会进入这个缓存。
      if (singletons.containsKey(name)) return singletons.get(name);
      Definition definition = definitions.get(name);
      if (definition == null) throw new IllegalArgumentException("no bean: " + name);
      try {
        // 通过公共无参构造器创建实例，成功后才发布到单例缓存。
        Object bean = definition.type().getConstructor().newInstance();
        singletons.put(name, bean);
        return bean;
      } catch (ReflectiveOperationException e) {
        // 反射异常只是包装，保留构造器抛出的真实原因。
        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
        throw new IllegalStateException("cannot create: " + name, cause);
      }
    }
  }

  public static class GreetingService {
    public String hello() {
      return "hello Spring";
    }
  }

  public static void main(String[] args) {
    Container c = new Container();
    c.register("greeting", GreetingService.class);
    GreetingService first = (GreetingService) c.getBean("greeting");
    GreetingService second = (GreetingService) c.getBean("greeting");
    check(first == second, "singleton identity");
    System.out.println(first.hello());
    System.out.println("same object: " + (first == second));
    try {
      c.getBean("missing");
      throw new AssertionError("missing definition accepted");
    } catch (IllegalArgumentException expected) {
      System.out.println(expected.getMessage());
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
javac --release 17 Demo01.java
java Demo01
```

预期输出：

```text
hello Spring
same object: true
no bean: missing
```

## 顺着 getBean 阅读实现

先查完成对象缓存，命中就立即返回；未命中时查定义，找到对应 Class 后用 `getConstructor().newInstance()` 创建实例。只有创建成功才放进缓存，所以构造失败不会留下一个“已经完成”的空对象。

反射的 `InvocationTargetException` 可能包住业务构造器抛出的异常。代码保留它的 cause，使报错仍能指向真正失败的位置。

这里的单例是“同一个容器里同一个名称”复用实例。给同一个类注册两个名字，或者建立两个容器，仍可以得到不同实例。它并不意味着某个类在整个 JVM 中只能有一个对象。

## 动手修改

把 `singletons.put` 注释掉再运行，身份断言会失败。接着恢复代码，把相同名称注册两次，观察启动时的重复定义异常。验证容器要比较对象身份，两个对象输出相同文字不代表它们是同一个对象。

本篇约定单线程使用。换成 ConcurrentHashMap 也不能自动保证“查询、创建、缓存”整个过程只有一个线程执行。

下一篇：[把配置转换成Bean定义](02-把配置转换成Bean定义.md)。
