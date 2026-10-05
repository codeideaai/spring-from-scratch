# 第十二篇 把 SQL 变成可管理的元数据

[English](../en/tutorial/12-sql-metadata.md)

上一篇把重复 JDBC 流程抽走了，但 SQL 仍写在调用位置。本篇继续实现一个最小映射层：XML 声明语句，statementId 查找语句，SqlSession 调用模板。

本篇直接给出 Mapper XML 读取、SQL 注册、命名参数编译、selectOne 和 update 的完整代码。XML 作为文本块写在 main，不需要另一份映射文件。框架仍只依赖标准库，H2 只负责执行数据库协议。

限制也明确：仅支持平面 select、update 节点与简单占位符，不支持动态 SQL、关联映射或缓存。列到对象的转换仍由调用方提供 RowMapper。

## 完整代码

将下面整段保存为 `Demo12.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

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

/** 把 SQL 映射编译成语句元数据，用有序参数列表驱动 JDBC 绑定。 */
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
      // JDBC 参数下标从 1 开始；值通过绑定传入，不拼接进 SQL。
      for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
    }

    public int update(String sql, Object... values) throws SQLException {
      // 本模板拥有连接和语句，try-with-resources 保证异常时也按逆序关闭。
      try (Connection c = source.open();
          PreparedStatement ps = c.prepareStatement(sql)) {
        bind(ps, values);
        return ps.executeUpdate();
      }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... values)
        throws SQLException {
      // 本模板拥有连接和语句，try-with-resources 保证异常时也按逆序关闭。
      try (Connection c = source.open();
          PreparedStatement ps = c.prepareStatement(sql)) {
        bind(ps, values);
        // 结果集必须在语句关闭前完成映射，返回值不再持有 JDBC 游标。
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
      // 按占位符出现顺序取值，重复参数也保留，不能依赖 Map 的迭代顺序。
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

  // 只支持不含字面量和注释的受限 SQL，下面的正则不是通用 SQL 解析器。
  public static BoundSql parse(String sql) {
    if (sql.contains("'") || sql.contains("\"") || sql.contains("--") || sql.contains("/*"))
      throw new IllegalArgumentException(
          "SQL literals/comments unsupported; pass values as parameters");
    var matcher = Pattern.compile("#\\{([a-zA-Z][a-zA-Z0-9_]*)}").matcher(sql);
    List<String> names = new ArrayList<>();
    StringBuffer result = new StringBuffer();
    // 仅把参数标记编译成问号，同时记录每个问号对应的参数名。
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
      // JDK 自带 XML 解析器；禁止 DTD 和外部资源，配置只在本地解析。
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
          // 命名空间加局部 id 组成全局键，避免不同 Mapper 的同名语句冲突。
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
      // selectOne 允许零行，但多行必须报错，不能悄悄丢弃后面的结果。
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

## 编译与运行

使用 JDK 17。首次运行先下载固定版本的 H2 驱动到当前目录；如果已经存在就不必重复下载。H2 仅是数据库运行依赖，教程框架代码全部在上文。

```bash
curl -fL --connect-timeout 10 --max-time 60 -o h2-2.2.224.jar \
  https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 Demo12.java
java -cp '.:h2-2.2.224.jar' Demo12
```

Windows 的 classpath 分隔符使用分号，运行命令改为 `java -cp ".;h2-2.2.224.jar" Demo12`。

预期输出：

```text
multiple rows rejected
Ada -> Lin
zero rows and repeated parameters verified
```

## SQL 编译得到的不只是字符串

parse 把每个 #{name} 替换为问号，同时按出现顺序记录名称。重复参数也要记录两次；不能用 Set，也不能直接遍历参数 Map。arguments 再生成有序实参数组，交给模板按下标绑定。

编译器有意拒绝 SQL 引号、字符串字面量与注释，因为简单正则不能可靠判断占位符是不是处于这些语法内部。实际参数值仍可以是任意字符串，它们通过问号绑定，不经过这个文本解析器。

## SqlSession 的职责

load 将 namespace 与节点 id 合成唯一标识；require 检查调用 API 与语句种类一致。selectOne 对零行返回 Optional.empty，对多行报错，不静默取第一行。这个版本要求 RowMapper 返回非空对象。

加载只用于单线程启动，不支持并发修改、热重载或失败后的原子恢复。复杂配置需求出现时，应先定义这些行为，再扩展实现。

## 动手修改

给同一个 namespace 重复写 id，确认加载时报错；调用 rename 时漏传 name，确认在进入 JDBC 前报错；给 selectOne 返回两行，确认数据唯一性问题不会被掩盖。

如果以后做读写分离，不要按请求修改一个共享模板的数据源字段。数据源路由和事务连接归属需要在连接获取边界协调，否则并发请求会互相覆盖配置。

下一篇：[用动态代理增强业务方法](13-用动态代理增强业务方法.md)。
