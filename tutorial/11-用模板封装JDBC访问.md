# 第十一篇 用模板封装 JDBC 访问

[English](../en/tutorial/11-jdbc-template.md)

现在用数据库代替内存结果。JDBC 的连接、语句、参数和结果集处理有固定流程，变化部分是 SQL、参数以及“当前行如何变成对象”。本篇把固定流程封装成 JdbcTemplate，把行映射交给 RowMapper。

下面所有框架代码、业务对象和建表语句都在同一个文件。运行时只需要 H2 数据库驱动，它实现 JDBC 协议，不提供这里的模板功能。

这个阶段每次操作独立获取并释放连接，还没有事务关联。第十六篇会在 withConnection 边界增加事务感知。

## 完整代码

将下面整段保存为 `Demo11.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 模板管理 JDBC 资源，业务回调只负责把当前行转换成业务对象。 */
public class Demo11 {
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

  public record User(long id, String name) {}

  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    JdbcTemplate jdbc =
        new JdbcTemplate(() -> DriverManager.getConnection("jdbc:h2:mem:demo11;DB_CLOSE_DELAY=-1"));
    jdbc.update("create table users(id bigint primary key, name varchar(100))");
    jdbc.update("insert into users values(?, ?)", 7L, "Ada");
    jdbc.update("insert into users values(?, ?)", 8L, "Lin");
    List<User> users =
        jdbc.query(
            "select id, name from users order by id",
            rs -> new User(rs.getLong("id"), rs.getString("name")));
    check(users.equals(List.of(new User(7, "Ada"), new User(8, "Lin"))), "row mapping");
    check(
        jdbc.query("select id from users where id=?", rs -> rs.getLong(1), 99L).isEmpty(),
        "empty result");
    check(jdbc.update("update users set name=? where id=?", "Grace", 7L) == 1, "update count");
    System.out.println(users);
    System.out.println("empty result and update count verified");
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
javac --release 17 Demo11.java
java -cp '.:h2-2.2.224.jar' Demo11
```

Windows 的 classpath 分隔符使用分号，运行命令改为 `java -cp ".;h2-2.2.224.jar" Demo11`。

预期输出：

```text
[User[id=7, name=Ada], User[id=8, name=Lin]]
empty result and update count verified
```

## 模板负责哪些动作

每次调用先从 Connections 获取连接，再创建 PreparedStatement，按从 1 开始的 JDBC 下标绑定值。查询由模板调用 next 遍历，RowMapper 只处理当前行；若 RowMapper 再调用 next，就会跳过记录。

嵌套的 try-with-resources 按资源的相反创建顺序关闭 ResultSet、Statement 和 Connection。即使映射时抛错也会尝试清理，不需要业务在每个方法中重复 finally。

当前保留 SQLException，交给调用方处理。不能捕获所有异常后返回空列表：空列表表示查询成功但没有结果，数据库失败是不同的事实。

## 连接来源与连接池

Connections 是一个小型函数接口。有 DataSource 时可以用 dataSource::getConnection 适配；是否使用连接池由连接来源决定，模板不需要知道池内部结构。

连接池还涉及借用并发、超时、连接有效性、归还和状态重置，不是简单把 Connection 放进 List。本文没有手写连接池，也不把 DriverManager 调用伪装成池。

## 参数绑定的边界

问号用于参数值，不能绑定表名和列名。用户输入作为值交给 setObject，不拼进 SQL；需要变化的 SQL 结构应由应用选择有限的合法选项。

练习：让 RowMapper 抛出 SQLException，确认错误能到达 main。然后用不存在的用户查询，确认返回的是空列表而不是 null。第十六篇还会说明事务中的连接为何不能由每条 SQL 单独关闭。

下一篇：[把SQL变成可管理的元数据](12-把SQL变成可管理的元数据.md)。
