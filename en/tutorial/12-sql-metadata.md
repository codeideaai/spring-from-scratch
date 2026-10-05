---
pagetitle: "12 — Turn SQL into Managed Metadata"
---

# 12 — Turn SQL into Managed Metadata

[中文](../../tutorial/12-把SQL变成可管理的元数据.md) · [Series contents](../README.md)

The previous chapter removed repetitive JDBC handling, but SQL still lives at each call site. We now add a minimal mapping layer: XML declares statements, statementId identifies them, and SqlSession calls the template.

The full implementation includes Mapper XML loading, statement registration, named-parameter compilation, selectOne and update. XML is a text block in main, with no separate mapping file. Framework code still uses only the standard library; H2 runs the database.

The supported language is deliberately limited to flat select and update elements and simple placeholders. There is no dynamic SQL, relationship mapping or caching. The caller still supplies RowMapper for column-to-object conversion.

## Complete code

Save the entire block as `Demo12.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Compile SQL mappings into statement metadata and use ordered parameters for JDBC binding. */
public class Demo12 {
  @FunctionalInterface
  public interface Connections {
    Connection open() throws SQLException;
  }

  @FunctionalInterface
  public interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  public static class JdbcTemplate {
    private final Connections source;

    public JdbcTemplate(Connections source) {
      this.source = source;
    }

    private void bind(PreparedStatement ps, Object[] values) throws SQLException {
      // JDBC parameter indexes start at 1. Bind values instead of concatenating them into SQL.
      for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
    }

    public int update(String sql, Object... values) throws SQLException {
      // The template owns these resources; try-with-resources closes them in reverse order even on
      // failure.
      try (Connection c = source.open();
          PreparedStatement ps = c.prepareStatement(sql)) {
        bind(ps, values);
        return ps.executeUpdate();
      }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... values)
        throws SQLException {
      // The template owns these resources; try-with-resources closes them in reverse order even on
      // failure.
      try (Connection c = source.open();
          PreparedStatement ps = c.prepareStatement(sql)) {
        bind(ps, values);
        // Finish mapping before the statement closes; returned objects must not retain a JDBC
        // cursor.
        try (ResultSet rs = ps.executeQuery()) {
          List<T> result = new ArrayList<>();
          while (rs.next()) result.add(mapper.map(rs));
          return result;
        }
      }
    }
  }

  public record BoundSql(String sql, List<String> names) {
    public Object[] arguments(Map<String, Object> params) {
      // Read values in placeholder order, preserving repeats; do not depend on the map's iteration
      // order.
      return names.stream()
          .map(
              name -> {
                if (!params.containsKey(name))
                  throw new IllegalArgumentException("missing SQL parameter: " + name);
                return params.get(name);
              })
          .toArray();
    }
  }

  // Only a restricted SQL grammar without literals or comments is supported; this is not a general
  // parser.
  public static BoundSql parse(String sql) {
    if (sql.contains("'") || sql.contains("\"") || sql.contains("--") || sql.contains("/*"))
      throw new IllegalArgumentException(
          "SQL literals/comments unsupported; pass values as parameters");
    var matcher = Pattern.compile("#\\{([a-zA-Z][a-zA-Z0-9_]*)}").matcher(sql);
    List<String> names = new ArrayList<>();
    StringBuffer result = new StringBuffer();
    // Compile parameter markers into question marks and record the name associated with each
    // position.
    while (matcher.find()) {
      names.add(matcher.group(1));
      matcher.appendReplacement(result, "?");
    }
    matcher.appendTail(result);
    if (result.indexOf("#{") >= 0 || result.indexOf("${") >= 0)
      throw new IllegalArgumentException("invalid placeholder");
    return new BoundSql(result.toString(), List.copyOf(names));
  }

  enum Command {
    SELECT,
    UPDATE
  }

  record Statement(String id, Command command, BoundSql sql) {}

  public static class SqlSession {
    final JdbcTemplate jdbc;
    final Map<String, Statement> statements = new HashMap<>();

    public SqlSession(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    public void load(String xml) throws Exception {
      // Use the JDK XML parser with DTDs and external resources disabled for local configuration.
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
        Element root = f.newDocumentBuilder().parse(in).getDocumentElement();
        if (!root.getTagName().equals("mapper"))
          throw new IllegalArgumentException("mapper root required");
        String namespace = root.getAttribute("namespace");
        if (namespace.isBlank()) throw new IllegalArgumentException("namespace required");
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
          if (!(n instanceof Element e)) continue;
          Command command =
              switch (e.getTagName()) {
                case "select" -> Command.SELECT;
                case "update" -> Command.UPDATE;
                default ->
                    throw new IllegalArgumentException("unsupported statement: " + e.getTagName());
              };
          if (e.getAttribute("id").isBlank() || e.getElementsByTagName("*").getLength() != 0)
            throw new IllegalArgumentException("flat statement with id required");
          // Combine the namespace and local id to avoid collisions between statements in different
          // mappers.
          String id = namespace + "." + e.getAttribute("id");
          String sql = e.getTextContent().trim();
          if (sql.isBlank()) throw new IllegalArgumentException("empty SQL");
          Statement statement = new Statement(id, command, parse(sql));
          if (statements.putIfAbsent(id, statement) != null)
            throw new IllegalArgumentException("duplicate statement: " + id);
        }
      }
    }

    Statement require(String id, Command command) {
      Statement s = statements.get(id);
      if (s == null || s.command() != command)
        throw new IllegalArgumentException("missing or wrong command: " + id);
      return s;
    }

    public <T> Optional<T> selectOne(String id, Map<String, Object> parameters, RowMapper<T> mapper)
        throws SQLException {
      Statement s = require(id, Command.SELECT);
      List<T> rows = jdbc.query(s.sql().sql(), mapper, s.sql().arguments(parameters));
      // selectOne allows zero rows, but must reject multiple rows rather than silently discard
      // results.
      if (rows.size() > 1) throw new IllegalStateException("expected at most one row: " + id);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int update(String id, Map<String, Object> parameters) throws SQLException {
      Statement s = require(id, Command.UPDATE);
      return jdbc.update(s.sql().sql(), s.sql().arguments(parameters));
    }
  }

  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    JdbcTemplate jdbc =
        new JdbcTemplate(() -> DriverManager.getConnection("jdbc:h2:mem:demo12;DB_CLOSE_DELAY=-1"));
    jdbc.update("create table users(id bigint primary key, name varchar(100))");
    jdbc.update("insert into users values(?, ?)", 7L, "Ada");
    SqlSession session = new SqlSession(jdbc);
    session.load(
        """
            <mapper namespace="user">
                <select id="find">select name from users where id=#{id}</select>
                <update id="rename">update users set name=#{name} where id=#{id}</update>
                <select id="all">select name from users</select>
            </mapper>
            """);
    String name =
        session.selectOne("user.find", Map.of("id", 7L), rs -> rs.getString(1)).orElseThrow();
    check(name.equals("Ada"), "mapped select");
    check(session.update("user.rename", Map.of("id", 7L, "name", "Lin")) == 1, "mapped update");
    String renamed =
        session.selectOne("user.find", Map.of("id", 7L), rs -> rs.getString(1)).orElseThrow();
    check(renamed.equals("Lin"), "updated value");
    check(
        session.selectOne("user.find", Map.of("id", 99L), rs -> rs.getString(1)).isEmpty(),
        "zero rows");
    BoundSql repeated = parse("select id from users where id=#{id} or id=#{id}");
    check(
        Arrays.equals(repeated.arguments(Map.of("id", 7L)), new Object[] {7L, 7L}),
        "repeated parameter");
    jdbc.update("insert into users values(?, ?)", 8L, "Grace");
    try {
      session.selectOne("user.all", Map.of(), rs -> rs.getString(1));
      throw new AssertionError("multiple rows accepted");
    } catch (IllegalStateException expected) {
      System.out.println("multiple rows rejected");
    }
    System.out.println(name + " -> " + renamed);
    System.out.println("zero rows and repeated parameters verified");
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
```

## Compile and run

Use JDK 17. Download the pinned H2 driver into the current directory before the first run; skip the download if it is already present. H2 is only a database runtime dependency. All tutorial framework code is printed above.

```bash
curl -fL --connect-timeout 10 --max-time 60 -o h2-2.2.224.jar \
  https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 Demo12.java
java -cp '.:h2-2.2.224.jar' Demo12
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo12`.

Expected output:

```text
multiple rows rejected
Ada -> Lin
zero rows and repeated parameters verified
```

## SQL compilation produces more than a string

parse replaces each #{name} with a question mark while recording names in appearance order. Repeated parameters must be recorded repeatedly; neither a Set nor direct iteration over the parameter map works. arguments builds an ordered array that the template binds by position.

The compiler rejects SQL quotes, literals and comments because a simple regular expression cannot reliably determine whether a placeholder appears inside them. Actual parameter values can still contain arbitrary strings: they are bound through question marks and never pass through this text parser.

## SqlSession responsibilities

load combines namespace and element id into a unique identifier. require checks that the requested API matches the statement kind. selectOne returns Optional.empty for zero rows and rejects multiple rows instead of silently taking the first. This version requires RowMapper to return a non-null object.

Loading is restricted to single-threaded startup. Concurrent modification, hot reload and atomic recovery after loading failure are unsupported. Define those behaviors before extending the implementation for more complex configuration.

## Try a change

Repeat an id in the same namespace and confirm loading fails. Omit name when calling rename and confirm failure before JDBC execution. Make selectOne return two rows and check that the uniqueness problem remains visible.

For future read/write routing, do not change a shared template's data-source field per request. Routing and transaction-connection ownership must be coordinated at connection acquisition, or concurrent requests may overwrite each other's configuration.

Next: [Enhance Business Methods with Dynamic Proxies](13-dynamic-proxies.md).
