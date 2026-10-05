---
pagetitle: "Load Dependency Metadata from Strict Configuration"
---

# 15 Load Dependency Metadata from Strict Configuration

[中文](../../tutorial/15-让配置描述依赖而不执行创建.md) · [Series contents](../README.md)

Definitions currently live in Java. When assembly needs a configuration format, let XML describe names, types and ordered dependencies without creating objects inside the parser.

The language contains only beans, bean and ref. Unknown attributes, unexpected nodes and missing required values fail explicitly. It does not implement scopes, properties or full Spring XML.

## Complete code

Save this entire listing as `Demo15.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class Demo15 {
  /** Small immutable values shared by the application and its adapters. */
  public static final class Domain {
    private Domain() {}

    public record Book(long id, String title, int available) {}

    public record Reservation(String id, long bookId, String member) {}

    public record Request(String id, long bookId, String member) {
      public Request {
        if (id == null || !id.matches("[A-Za-z0-9-]{1,40}"))
          throw new Problem(400, "invalid reservation id");
        if (bookId <= 0) throw new Problem(400, "invalid book id");
        if (member == null || member.isBlank() || member.length() > 40)
          throw new Problem(400, "invalid member");
        member = member.strip();
      }
    }

    public static final class Problem extends RuntimeException {
      private final int status;

      public Problem(int status, String message) {
        super(message);
        this.status = status;
      }

      public int status() {
        return status;
      }
    }
  }

  /** One local transaction per thread; nesting is deliberately rejected. */
  public static final class Transactions {
    @FunctionalInterface
    public interface Source {
      Connection open() throws SQLException;
    }

    @FunctionalInterface
    public interface Work<T> {
      T run() throws Exception;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
      T run(Connection connection) throws SQLException;
    }

    private final Source source;
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    public Transactions(Source source) {
      this.source = source;
    }

    public boolean active() {
      return current.get() != null;
    }

    public <T> T connection(SqlWork<T> work) throws SQLException {
      Connection shared = current.get();
      // A statement borrows the transaction connection; only its owner may close it.
      if (shared != null) return work.run(shared);
      try (Connection connection = source.open()) {
        return work.run(connection);
      }
    }

    public <T> T run(Work<T> work) throws Exception {
      if (active()) throw new IllegalStateException("nested transaction");
      // The application uses fresh DriverManager connections, not a pooled connection lease.
      try (Connection connection = source.open()) {
        if (!connection.getAutoCommit())
          throw new IllegalStateException("transaction already active");
        connection.setAutoCommit(false);
        current.set(connection);
        try {
          T result = work.run();
          connection.commit();
          return result;
        } catch (Exception | Error failure) {
          try {
            connection.rollback();
          } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
          }
          throw failure;
        } finally {
          // Worker threads can be reused. Never leave a closed connection attached to one.
          current.remove();
        }
      }
    }
  }

  /** SQL values are bound as parameters; table and column names belong to application SQL. */
  public static final class Jdbc {
    @FunctionalInterface
    public interface Row<T> {
      T read(ResultSet result) throws SQLException;
    }

    private final Transactions transactions;

    public Jdbc(Transactions transactions) {
      this.transactions = transactions;
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    public int update(String sql, Object... values) throws SQLException {
      return transactions.connection(
          connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
              bind(statement, values);
              return statement.executeUpdate();
            }
          });
    }

    public <T> List<T> query(String sql, Row<T> row, Object... values) throws SQLException {
      return transactions.connection(
          connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
              bind(statement, values);
              try (ResultSet result = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                // Iteration belongs to the template. A row mapper must not call next().
                while (result.next()) rows.add(row.read(result));
                return rows;
              }
            }
          });
    }
  }

  /** Persistence rules for one book and its reservations. */
  public static final class ReservationStore {
    private final Jdbc jdbc;

    public ReservationStore(Jdbc jdbc) {
      this.jdbc = jdbc;
    }

    public void initialize(int copies) throws SQLException {
      jdbc.update(
          "create table if not exists books(id bigint primary key, title varchar(100) not null, "
              + "available int not null check(available >= 0))");
      jdbc.update(
          "create table if not exists reservations(id varchar(40) primary key, "
              + "book_id bigint not null references books(id), member varchar(40) not null, "
              + "unique(book_id, member))");
      // Startup seeds a new database only; reopening must not replenish borrowed copies.
      jdbc.update(
          "insert into books select ?, ?, ? where not exists(select 1 from books where id=?)",
          101L,
          "The Art of Testing",
          copies,
          101L);
    }

    public Domain.Book book(long id) throws SQLException {
      var rows =
          jdbc.query(
              "select id, title, available from books where id=?",
              r -> new Domain.Book(r.getLong(1), r.getString(2), r.getInt(3)),
              id);
      if (rows.isEmpty()) throw new Domain.Problem(404, "book not found");
      return rows.get(0);
    }

    public int count() throws SQLException {
      return jdbc.query("select count(*) from reservations", r -> r.getInt(1)).get(0);
    }

    public Domain.Reservation find(String id) throws SQLException {
      var rows =
          jdbc.query(
              "select id, book_id, member from reservations where id=?",
              r -> new Domain.Reservation(r.getString(1), r.getLong(2), r.getString(3)),
              id);
      if (rows.isEmpty()) throw new Domain.Problem(404, "reservation not found");
      return rows.get(0);
    }

    public Domain.Reservation reserve(Domain.Request request, Runnable afterStockChange)
        throws SQLException {
      book(request.bookId());
      // The database decides the winner. A preceding SELECT alone cannot prevent overselling.
      int changed =
          jdbc.update(
              "update books set available=available-1 where id=? and available>0",
              request.bookId());
      if (changed != 1) throw new Domain.Problem(409, "no copies available");
      afterStockChange.run();
      try {
        jdbc.update(
            "insert into reservations(id, book_id, member) values(?, ?, ?)",
            request.id(),
            request.bookId(),
            request.member());
      } catch (SQLException failure) {
        // A uniqueness failure must escape the transaction so the stock change is rolled back.
        if ("23505".equals(failure.getSQLState()))
          throw new Domain.Problem(409, "duplicate reservation");
        throw failure;
      }
      return new Domain.Reservation(request.id(), request.bookId(), request.member());
    }
  }

  public interface Reservations {
    Domain.Book book(long id) throws Exception;

    Domain.Reservation reserve(Domain.Request request) throws Exception;
  }

  /** The service describes the use case; a proxy supplies its transaction boundary. */
  public static final class ReservationService implements Reservations {
    private final ReservationStore store;
    private final Runnable afterStockChange;

    public ReservationService(ReservationStore store) {
      this(store, () -> {});
    }

    public ReservationService(ReservationStore store, Runnable afterStockChange) {
      this.store = store;
      this.afterStockChange = afterStockChange;
    }

    public Domain.Book book(long id) throws Exception {
      return store.book(id);
    }

    public Domain.Reservation reserve(Domain.Request request) throws Exception {
      return store.reserve(request, afterStockChange);
    }
  }

  /** Constructor-only singleton container. Configure it on one thread before serving requests. */
  public static final class BeanBox implements AutoCloseable {
    private record Definition(Class<?> type, List<String> dependencies) {}

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Object> ready = new LinkedHashMap<>();
    private final Set<String> creating = new LinkedHashSet<>();
    private final List<AutoCloseable> owned = new ArrayList<>();
    private final List<BiFunction<String, Object, Object>> processors = new ArrayList<>();
    private boolean frozen;
    private boolean closed;

    private void editable(String name) {
      if (frozen || closed) throw new IllegalStateException("configuration closed");
      if (ready.containsKey(name) || definitions.containsKey(name))
        throw new IllegalArgumentException("duplicate bean: " + name);
    }

    public void instance(String name, Object borrowed) {
      editable(name);
      ready.put(name, java.util.Objects.requireNonNull(borrowed));
    }

    public void define(String name, Class<?> type, String... dependencies) {
      editable(name);
      definitions.put(name, new Definition(type, List.of(dependencies)));
    }

    public void process(BiFunction<String, Object, Object> processor) {
      if (frozen || closed) throw new IllegalStateException("configuration closed");
      processors.add(processor);
    }

    public Object get(String name) {
      if (closed) throw new IllegalStateException("container closed");
      frozen = true;
      if (ready.containsKey(name)) return ready.get(name);
      Definition definition = definitions.get(name);
      if (definition == null) throw new IllegalArgumentException("missing bean: " + name);
      if (!creating.add(name))
        throw new IllegalStateException("dependency cycle: " + creating + " -> " + name);
      try {
        Object[] arguments = definition.dependencies().stream().map(this::get).toArray();
        List<Constructor<?>> candidates =
            Arrays.stream(definition.type().getConstructors())
                .filter(c -> compatible(c.getParameterTypes(), arguments))
                .toList();
        if (candidates.size() != 1)
          throw new IllegalArgumentException("ambiguous constructor: " + name);
        Object raw = candidates.get(0).newInstance(arguments);
        // Track the resource before processors run: even a failed wrapper must release it.
        if (raw instanceof AutoCloseable closeable) owned.add(closeable);
        Object exposed = raw;
        for (var processor : processors)
          exposed = java.util.Objects.requireNonNull(processor.apply(name, exposed));
        ready.put(name, exposed);
        return exposed;
      } catch (ReflectiveOperationException failure) {
        Throwable cause =
            failure instanceof InvocationTargetException invocation
                ? invocation.getCause()
                : failure;
        throw new IllegalStateException("creation failed: " + name, cause);
      } finally {
        creating.remove(name);
      }
    }

    private static boolean compatible(Class<?>[] types, Object[] arguments) {
      if (types.length != arguments.length) return false;
      for (int i = 0; i < types.length; i++) if (!types[i].isInstance(arguments[i])) return false;
      return true;
    }

    public <T> T get(String name, Class<T> type) {
      return type.cast(get(name));
    }

    public void start() {
      try {
        for (String name : definitions.keySet()) get(name);
      } catch (RuntimeException | Error failure) {
        try {
          close();
        } catch (RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    public void close() {
      if (closed) return;
      closed = true;
      RuntimeException failure = null;
      for (int i = owned.size() - 1; i >= 0; i--) {
        try {
          owned.get(i).close();
        } catch (Exception error) {
          if (failure == null) failure = new IllegalStateException("shutdown failed", error);
          else failure.addSuppressed(error);
        }
      }
      owned.clear();
      ready.clear();
      if (failure != null) throw failure;
    }
  }

  /** A local, trusted configuration language: bean(id, class) with ordered ref(bean) children. */
  public static final class BeanXml {
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

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  @FunctionalInterface
  interface Task {
    void run() throws Exception;
  }

  static void expect(int status, Task task) throws Exception {
    try {
      task.run();
      throw new AssertionError("expected " + status);
    } catch (Domain.Problem problem) {
      check(problem.status() == status, "wrong status");
    }
  }

  static String database() {
    return "jdbc:h2:mem:chapter_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
  }

  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    String url = database();
    Transactions transactions = new Transactions(() -> DriverManager.getConnection(url));
    ReservationStore store = new ReservationStore(new Jdbc(transactions));
    store.initialize(2);

    try (BeanBox box = new BeanBox()) {
      box.instance("store", store);
      String xml =
          "<beans><bean id='service' class='"
              + ReservationService.class.getName()
              + "'><ref bean='store'/></bean></beans>";
      try (var input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
        BeanXml.load(input, box);
      }
      box.start();
      Reservations service = box.get("service", Reservations.class);
      transactions.run(() -> service.reserve(new Domain.Request("r1", 101, "Lin")));
    }
    try (BeanBox invalid = new BeanBox();
        var input =
            new ByteArrayInputStream(
                "<beans><bean id='x' class='java.lang.Object' scope='prototype'/></beans>"
                    .getBytes(StandardCharsets.UTF_8))) {
      try {
        BeanXml.load(input, invalid);
        throw new AssertionError("unknown attribute");
      } catch (IllegalArgumentException expected) {
        check(expected.getMessage().contains("attribute"), "strict XML");
      }
    }
    check(store.count() == 1, "XML composition");
    System.out.println("configuration: assembled=true;unknownAttribute=rejected");
  }
}
```

## Compile and run

Use JDK 17. H2 2.2.224 supplies the database runtime; all framework code is printed above. Skip the download if the driver is already present.

```bash
curl -fL -o h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 -encoding UTF-8 Demo15.java
java -cp ".:h2-2.2.224.jar" Demo15
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo15`.

Expected output:

```text
configuration: assembled=true;unknownAttribute=rejected
```

## Trace the operation

BeanXml.load records BeanBox definitions; start resolves references and constructs objects afterward. Java supplies the store instance, while XML describes its service consumer. class.getName works for both packaged classes and the nested classes printed here.

DocumentBuilderFactory, XMLConstants and DOM are part of the JDK java.xml module, not third-party libraries. Disable DOCTYPE and external DTD/schema access because they are outside this local configuration contract.

Configuration must be trusted: class names eventually reach reflection. Discard the unstarted container after a load failure; hot reload and atomic configuration rollback are not implemented. The caller owns the input stream.

## Try it yourself

Add scope="prototype" and confirm explicit rejection. Then reference a missing dependency and compare configuration syntax failure with dependency resolution failure during startup.

Next: [Trace One Reservation Through the Whole Application](16-application-assembly.md).
