import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 验证容器装配、生命周期、父子查找以及非法配置的失败路径。 */
public class IocLab {
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

  public static class Controller {
    @Container.Inject public UserService service;
  }

  public static class CycleA {
    @Container.Inject public CycleB b;
  }

  public static class CycleB {
    @Container.Inject public CycleA a;
  }

  // 此处只支持 id 和 class；其他属性必须报错，不能被静默忽略。
  public static void loadXml(Container container, Path path) throws Exception {
    var f = DocumentBuilderFactory.newInstance();
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    try (var in = Files.newInputStream(path)) {
      Element root = f.newDocumentBuilder().parse(in).getDocumentElement();
      if (!root.getTagName().equals("beans")) throw new IllegalArgumentException("expected beans");
      for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (!(n instanceof Element e)) continue;
        if (!e.getTagName().equals("bean") || e.getElementsByTagName("*").getLength() != 0)
          throw new IllegalArgumentException("only flat bean definitions supported");
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
          String name = e.getAttributes().item(i).getNodeName();
          if (!Set.of("id", "class").contains(name))
            throw new IllegalArgumentException("unsupported: " + name);
        }
        if (e.getAttribute("id").isBlank() || e.getAttribute("class").isBlank())
          throw new IllegalArgumentException("id and class are required");
        container.register(
            e.getAttribute("id"), new Container.Definition(Class.forName(e.getAttribute("class"))));
      }
    }
  }

  static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
  }

  public static void main(String[] args) throws Exception {
    var events = new ArrayList<String>();
    UserService service;
    try (var c = new Container()) {
      loadXml(c, Path.of("examples/beans.xml"));
      c.register(
          "service",
          new Container.Definition(UserService.class)
              .constructor(new Class<?>[] {Repository.class}, new Container.Ref("repository"))
              .property("prefix", "hello ")
              .lifecycle("init", "destroy"));
      c.register("controller", new Container.Definition(Controller.class));
      c.register("fresh", new Container.Definition(Object.class).prototype());
      c.onEvent(events::add);
      c.refresh();
      service = c.getBean(UserService.class);
      check(service == c.getBean("service"), "singleton identity");
      check(service.ready && service.find(7).equals("hello user-7"), "injection and init");
      check(c.getBean(Controller.class).service == service, "field injection");
      check(c.getBean("fresh") != c.getBean("fresh"), "prototype identity");
      check(events.equals(List.of("refreshed")), "refresh event");
      try (var child = new Container(c)) {
        check(child.getBean("service") == service, "parent lookup");
      }
    }
    check(!service.ready, "destroy");
    try (var cyclic = new Container()) {
      cyclic.register("a", new Container.Definition(CycleA.class));
      cyclic.register("b", new Container.Definition(CycleB.class));
      try {
        cyclic.refresh();
        throw new AssertionError("cycle accepted");
      } catch (IllegalStateException e) {
        check(e.getMessage().contains("cycle"), "cycle diagnostic");
      }
    }
    System.out.println(
        "PASS IoC: XML, constructor, setter, field, singleton, prototype, lifecycle, event, parent, cycle rejection");
  }
}
