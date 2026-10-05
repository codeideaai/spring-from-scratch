# 第十六篇 用事务串联 AOP 与 JDBC

[English](../en/tutorial/16-transactions.md)

事务需要把多个 SQL 操作放到同一条连接里。拦截器调用 commit 并不自动意味着业务 SQL 也使用了那条连接；模板与事务管理器必须共享一个连接获取协议。

本篇实现当前线程的一层本地事务。Transactions 持有 ThreadLocal，JdbcTemplate 先检查绑定连接，没有事务才临时打开连接。嵌套事务直接拒绝，避免在没有传播语义时静默复用。

下面给出完整事务管理器、事务感知模板和真实 H2 验证。下一篇再把 execute 包装成 AOP 增强，放到 Service 方法边界。

## 完整代码

将下面整段保存为 `Demo16.java`。所需类、接口、注解、工具方法和入口均在代码中，无需其他教程源码。

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 通过线程绑定共享事务连接，并检查真实数据库的提交与回滚结果。 */
public class Demo16 {
  public static class Jdbc {
    @FunctionalInterface
    public interface Connections {
      Connection open() throws SQLException;
    }

    @FunctionalInterface
    public interface Work<T> {
      T run() throws Exception;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
      T map(ResultSet rs) throws SQLException;
    }

    public static final class Transactions {
      final Connections source;
      final ThreadLocal<Connection> current = new ThreadLocal<>();

      public Transactions(Connections source) {
        this.source = source;
      }

      public <T> T execute(Work<T> work) throws Exception {
        if (current.get() != null)
          throw new IllegalStateException("nested transaction unsupported");
        try (Connection connection = source.open()) {
          boolean original = connection.getAutoCommit();
          // 本例自己管理事务，不能接管一个已经关闭自动提交的外部事务。
          if (!original) throw new IllegalStateException("expected an auto-commit connection");
          connection.setAutoCommit(false);
          Throwable failure = null;
          try {
            // 把连接绑定到当前线程，同一事务内的模板操作才能共用一个连接。
            current.set(connection);
            T result = work.run();
            connection.commit();
            return result;
          } catch (Exception | Error e) {
            failure = e;
            // 回滚失败作为附加异常保留，不能覆盖最初的业务或提交异常。
            try {
              connection.rollback();
            } catch (SQLException rollback) {
              e.addSuppressed(rollback);
            }
            throw e;
          } finally {
            // 线程可能被线程池复用，必须先解除绑定，再恢复连接状态。
            current.remove();
            try {
              connection.setAutoCommit(original);
            } catch (SQLException reset) {
              if (failure != null) failure.addSuppressed(reset);
              else throw reset;
            }
          }
        }
      }
    }

    public static final class JdbcTemplate {
      final Transactions transactions;

      public JdbcTemplate(Transactions transactions) {
        this.transactions = transactions;
      }

      @FunctionalInterface
      interface WithConnection<T> {
        T run(Connection c) throws SQLException;
      }

      <T> T withConnection(WithConnection<T> action) throws SQLException {
        Connection bound = transactions.current.get();
        // 事务连接由事务管理器统一关闭，模板借用时不能提前关闭它。
        if (bound != null) return action.run(bound);
        try (Connection c = transactions.source.open()) {
          return action.run(c);
        }
      }

      static void bind(PreparedStatement ps, Object[] values) throws SQLException {
        // JDBC 参数下标从 1 开始；值通过绑定传入，不拼接进 SQL。
        for (int i = 0; i < values.length; i++) ps.setObject(i + 1, values[i]);
      }

      public int update(String sql, Object... values) throws SQLException {
        return withConnection(
            c -> {
              try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, values);
                return ps.executeUpdate();
              }
            });
      }

      public <T> List<T> query(String sql, RowMapper<T> mapper, Object... values)
          throws SQLException {
        return withConnection(
            c -> {
              try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, values);
                // 结果集必须在语句关闭前完成映射，返回值不再持有 JDBC 游标。
                try (ResultSet rs = ps.executeQuery()) {
                  List<T> results = new ArrayList<>();
                  while (rs.next()) results.add(mapper.map(rs));
                  return results;
                }
              }
            });
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    Jdbc.Transactions tx =
        new Jdbc.Transactions(
            () -> DriverManager.getConnection("jdbc:h2:mem:demo16;DB_CLOSE_DELAY=-1"));
    Jdbc.JdbcTemplate jdbc = new Jdbc.JdbcTemplate(tx);
    jdbc.update("create table users(id bigint primary key, name varchar(100))");
    jdbc.update("insert into users values(?, ?)", 7L, "Ada");
    tx.execute(
        () -> {
          Connection first = jdbc.withConnection(c -> c);
          check(first == jdbc.withConnection(c -> c), "same transaction connection");
          jdbc.update("update users set name=? where id=?", "Lin", 7L);
          return null;
        });
    String committed =
        jdbc.query("select name from users where id=?", rs -> rs.getString(1), 7L).get(0);
    try {
      tx.execute(
          () -> {
            jdbc.update("update users set name=? where id=?", "Bad", 7L);
            throw new IllegalArgumentException("abort");
          });
      throw new AssertionError("business exception hidden");
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().equals("abort"), "original error");
    }
    String rolledBack =
        jdbc.query("select name from users where id=?", rs -> rs.getString(1), 7L).get(0);
    check(committed.equals("Lin") && rolledBack.equals("Lin"), "database rollback");
    check(tx.current.get() == null, "thread connection cleanup");
    try {
      tx.execute(() -> tx.execute(() -> null));
      throw new AssertionError("nested transaction accepted");
    } catch (IllegalStateException expected) {
      check(tx.current.get() == null, "nested failure cleanup");
    }
    System.out.println("committed: " + committed);
    System.out.println("after rollback: " + rolledBack);
    System.out.println("connection identity, cleanup and nested rejection verified");
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
javac --release 17 Demo16.java
java -cp '.:h2-2.2.224.jar' Demo16
```

Windows 的 classpath 分隔符使用分号，运行命令改为 `java -cp ".;h2-2.2.224.jar" Demo16`。

预期输出：

```text
committed: Lin
after rollback: Lin
connection identity, cleanup and nested rejection verified
```

## 连接的所有者是谁

没有事务时，withConnection 打开连接，模板操作结束后关闭。存在事务时，模板只借用绑定连接，关闭本次语句与结果集，不关闭连接；事务管理器在整个工作结束后统一清理。

ThreadLocal 只是把资源关联到当前线程，不能让 Connection 自动跨线程使用，也不会随异步任务传播。线程可能复用，因此 finally 中必须 remove。

## 异常与清理顺序

正常完成后 commit，失败时 rollback，并重新抛出原异常；无论如何都解除绑定、恢复 autoCommit 并关闭连接。如果回滚或恢复状态又失败，尽量把这些错误作为 suppressed exception 保留。

本实现要求打开的连接原本处于自动提交状态，不接管外部已开启的事务。提交失败的数据库结果可能不明确，因此不自动重试整个业务。真实连接池还应具备失效连接处理能力。

## 为什么拒绝嵌套

内层失败被外层捕获后是否允许提交、内层是否能开启新连接、保存点如何处理，都属于传播语义。没有定义这些规则之前，拒绝比悄悄共享更明确。

练习：把 withConnection 的绑定连接分支删掉，重新运行。身份断言会失败；若去掉断言，业务 SQL 可能独立自动提交，外层 rollback 就无法撤销。真正验证事务要重新查询数据库，而不是仅打印一条 rollback 日志。

下一篇：[综合实战与自测](17-综合实战与自测.md)。
