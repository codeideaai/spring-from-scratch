---
pagetitle: "02 — Turn Configuration into Bean Definitions"
---

# 02 — Turn Configuration into Bean Definitions

[中文](../../tutorial/02-把配置转换成Bean定义.md) · [Series contents](../README.md)

Chapter 1 registered objects with Java statements. This chapter reads XML while keeping the container concerned only with classes and names. Changing the configuration format should not require changing instance creation.

We separate input handling into `Resource` and `XmlReader`. Resource opens a stream, the reader converts XML into definitions, and Container creates objects later during getBean. The example XML is a text block in main, so no separate configuration file is needed.

The XML language currently supports only bean id and class. Unknown attributes and child elements are rejected instead of silently accepting configuration whose behavior has not been implemented.

## Complete code

Save the entire block as `Demo02.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

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

/** Convert XML into bean definitions, separating resource access, parsing and object creation. */
public class Demo02 {
  public static class Container {
    // A definition stores creation metadata separately from the actual object in the singleton
    // cache.
    public record Definition(Class<?> type) {}

    final Map<String, Definition> definitions = new LinkedHashMap<>();
    final Map<String, Object> singletons = new HashMap<>();

    public void register(String name, Class<?> type) {
      if (definitions.putIfAbsent(name, new Definition(type)) != null)
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public Object getBean(String name) {
      // Return a completed singleton first; objects whose creation failed never enter this cache.
      if (singletons.containsKey(name)) return singletons.get(name);
      Definition definition = definitions.get(name);
      if (definition == null) throw new IllegalArgumentException("no bean: " + name);
      try {
        // Use the public no-argument constructor and publish to the singleton cache only after
        // success.
        Object bean = definition.type().getConstructor().newInstance();
        singletons.put(name, bean);
        return bean;
      } catch (ReflectiveOperationException e) {
        // Unwrap the reflection exception to preserve the actual cause thrown by the constructor.
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
      // JAXP is part of the JDK; disable DTDs and external resource access before parsing XML.
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

## Compile and run

Use JDK 17 and run these commands in the directory containing the saved file.

```bash
javac --release 17 Demo02.java
java Demo02
```

Expected output:

```text
hello from XML
definitions loaded before creation
```

## Why the reader does not create objects

`load` only calls register. After loading, the example checks that the singleton cache is still empty, proving that metadata loading and object creation are separate stages. Registering all definitions before resolving dependencies also avoids depending on their order in XML.

The `$` in a class name denotes the binary name of a static nested class. It must match Demo02 in this file. For a business class in a regular package, use its fully qualified name instead.

Resource returns an InputStream, not an XML Element. Files, classpath resources or test strings can therefore supply the input while XML knowledge remains in the reader. Try-with-resources closes the input even when parsing fails.

The parser disables access related to external entities because this configuration language does not need it. This is a restricted local format, not a complete implementation of Spring XML compatibility. The XML parser APIs used here are included in the JDK.

## Try a change

Add `scope="prototype"` to a bean. This chapter explicitly rejects it. Supporting it requires extending both the definition model and the factory's execution rules before teaching the reader to recognize the attribute. Merely reading an attribute does not implement its behavior.

Future property and constructor-arg support should also become definition data, leaving reference lookup and reflection-based injection to the factory. The next chapter first verifies that process with Java definitions.

Next: [Implement Constructor and Property Injection](03-constructor-and-property-injection.md).
