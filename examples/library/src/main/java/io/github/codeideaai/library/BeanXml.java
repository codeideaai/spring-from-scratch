package io.github.codeideaai.library;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** A local, trusted configuration language: bean(id, class) with ordered ref(bean) children. */
public final class BeanXml {
  private BeanXml() {}

  private static void attributes(Element element, String... allowed) {
    for (int i = 0; i < element.getAttributes().getLength(); i++) {
      String name = element.getAttributes().item(i).getNodeName();
      if (!List.of(allowed).contains(name))
        throw new IllegalArgumentException("unknown attribute: " + name);
    }
  }

  private static List<Element> children(Element parent) {
    List<Element> result = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element) result.add(element);
      else if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().isBlank())
        throw new IllegalArgumentException("unexpected text");
    }
    return result;
  }

  private static String required(Element element, String name) {
    String value = element.getAttribute(name);
    if (value.isBlank()) throw new IllegalArgumentException("missing attribute: " + name);
    return value;
  }

  public static void load(InputStream input, BeanBox box) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    // Configuration has no need for external entities or a network-accessible DTD.
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
    if (!root.getTagName().equals("beans")) throw new IllegalArgumentException("expected beans");
    attributes(root);
    for (Element bean : children(root)) {
      if (!bean.getTagName().equals("bean")) throw new IllegalArgumentException("expected bean");
      attributes(bean, "id", "class");
      List<String> dependencies = new ArrayList<>();
      for (Element ref : children(bean)) {
        if (!ref.getTagName().equals("ref")) throw new IllegalArgumentException("expected ref");
        attributes(ref, "bean");
        if (!children(ref).isEmpty()) throw new IllegalArgumentException("ref must be empty");
        dependencies.add(required(ref, "bean"));
      }
      // Loading records metadata only; all references resolve during container startup.
      box.define(
          required(bean, "id"),
          Class.forName(required(bean, "class")),
          dependencies.toArray(String[]::new));
    }
  }
}
