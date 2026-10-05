# 第二篇 把配置转换成 Bean 定义

[English](../en/tutorial/02-configuration-to-bean-definitions.md)

第一篇用 Java 语句登记对象。本篇改成读取 XML，但仍让容器只关心 Class 和名称。这样换一种配置格式时，实例创建代码不需要一起修改。

我们把输入拆成 `Resource` 和 `XmlReader`。Resource 打开输入流，Reader 把 XML 转成定义，Container 在之后的 getBean 阶段创建对象。示例 XML 直接写在 main 的文本块中，无需额外下载配置文件。

当前 XML 语言只支持 bean 的 id 和 class。未知属性和子节点都会报错，不能让尚未实现的配置被悄悄忽略。

## 完整代码

将下面整段保存为 `Demo02.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

```java
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 把 XML 转为 Bean 定义，资源读取、配置解析与对象创建各司其职。 */
public class Demo02 {
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

  @FunctionalInterface
  public interface Resource {
    InputStream openStream() throws IOException;
  }

  public static class XmlReader {
    public void load(Resource resource, Container container) throws Exception {
      // JAXP 属于 JDK 标准库；关闭 DTD 和外部资源访问后再解析 XML。
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      try (InputStream input = resource.openStream()) {
        Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
        if (!root.getTagName().equals("beans"))
          throw new IllegalArgumentException("expected beans root");
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
          if (!(node instanceof Element bean)) continue;
          if (!bean.getTagName().equals("bean") || bean.getElementsByTagName("*").getLength() != 0)
            throw new IllegalArgumentException("only flat bean elements supported");
          for (int i = 0; i < bean.getAttributes().getLength(); i++) {
            String key = bean.getAttributes().item(i).getNodeName();
            if (!Set.of("id", "class").contains(key))
              throw new IllegalArgumentException("unsupported attribute: " + key);
          }
          String id = bean.getAttribute("id");
          String className = bean.getAttribute("class");
          if (id.isBlank() || className.isBlank())
            throw new IllegalArgumentException("id and class required");
          container.register(id, Class.forName(className));
        }
      }
    }
  }

  public static class GreetingService {
    public String hello() {
      return "hello from XML";
    }
  }

  public static void main(String[] args) throws Exception {
    String xml =
        """
            <beans>
                <bean id="greeting" class="Demo02$GreetingService"/>
            </beans>
            """;
    Resource resource = () -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    Container c = new Container();
    new XmlReader().load(resource, c);
    check(c.singletons.isEmpty(), "reader must not instantiate beans");
    GreetingService service = (GreetingService) c.getBean("greeting");
    System.out.println(service.hello());
    check(service == c.getBean("greeting"), "singleton identity");
    System.out.println("definitions loaded before creation");
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## 编译与运行

使用 JDK 17，在保存文件的目录执行：

```bash
javac --release 17 Demo02.java
java Demo02
```

预期输出：

```text
hello from XML
definitions loaded before creation
```

## Reader 为什么不直接创建对象

`load` 只调用 register。示例在加载结束后检查单例缓存仍为空，证明元数据加载和对象创建是两个阶段。所有定义先登记完，再递归创建依赖，就不会被 XML 中的书写先后束缚。

类名中的 `$` 表示静态内部类的二进制名称；这里必须与文件中的 Demo02 一致。若把业务类放进普通包，配置中就改成它的完整限定名。

Resource 的返回值是 InputStream，而不是 XML Element。因此以后可以让文件、classpath 甚至测试字符串提供输入，XML 结构知识仍留在 Reader 里。try-with-resources 保证解析出错时也关闭输入。

解析器禁止外部实体相关访问，因为当前配置协议根本不需要这些能力。它是一个受限的本地配置语言，不是完整 Spring XML 兼容实现。

## 动手修改

给 bean 增加 `scope="prototype"`，本篇会明确拒绝。若要支持它，应先扩展定义模型和工厂执行规则，再让 Reader 识别该属性，而不是仅把 XML 属性读出来就声称功能完成。

将来支持 property 与 constructor-arg 时，也应把它们转换成定义数据，把引用查找和反射注入留给工厂。下一篇先用 Java 定义验证这个执行过程。

下一篇：[实现构造器和属性注入](03-实现构造器和属性注入.md)。
