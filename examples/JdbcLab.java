import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 区分事务管理器与模板的连接所有权，验证提交、回滚和参数绑定。 */
public class JdbcLab {
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
      if (current.get() != null) throw new IllegalStateException("nested transaction unsupported");
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

  static final class RecordingSource implements Connections {
    final List<String> events = new ArrayList<>();
    int opens;

    public Connection open() {
      opens++;
      events.add("open");
      return (Connection)
          Proxy.newProxyInstance(
              JdbcLab.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (p, m, args) -> {
                return switch (m.getName()) {
                  case "getAutoCommit" -> true;
                  case "setAutoCommit" -> {
                    events.add("auto=" + args[0]);
                    yield null;
                  }
                  case "commit", "rollback", "close" -> {
                    events.add(m.getName());
                    yield null;
                  }
                  default -> throw new UnsupportedOperationException(m.getName());
                };
              });
    }
  }

  static void contractTests() throws Exception {
    var source = new RecordingSource();
    var tx = new Transactions(source);
    var jdbc = new JdbcTemplate(tx);
    tx.execute(
        () -> {
          Connection first = jdbc.withConnection(c -> c);
          IocLab.check(first == jdbc.withConnection(c -> c), "same transaction connection");
          return null;
        });
    IocLab.check(
        source.opens == 1
            && source.events.equals(List.of("open", "auto=false", "commit", "auto=true", "close")),
        "commit lifecycle");
    source.events.clear();
    try {
      tx.execute(
          () -> {
            throw new IllegalArgumentException("business");
          });
      throw new AssertionError("expected rollback");
    } catch (IllegalArgumentException expected) {
    }
    IocLab.check(
        source.events.equals(List.of("open", "auto=false", "rollback", "auto=true", "close"))
            && tx.current.get() == null,
        "rollback and thread cleanup");
    BoundSql sql = parse("select id from users where id=#{id} or parent_id=#{id}");
    IocLab.check(
        sql.sql().equals("select id from users where id=? or parent_id=?")
            && Arrays.equals(sql.arguments(Map.of("id", 7)), new Object[] {7, 7}),
        "ordered parameters");
    try {
      sql.arguments(Map.of());
      throw new AssertionError("missing SQL parameter accepted");
    } catch (IllegalArgumentException expected) {
    }
    System.out.println(
        "PASS JDBC contracts: connection ownership, commit, rollback, cleanup, ordered SQL parameters (test double)");
  }

  static void databaseTests() throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:mem:mini;DB_CLOSE_DELAY=-1";
    var tx = new Transactions(() -> DriverManager.getConnection(url));
    var jdbc = new JdbcTemplate(tx);
    jdbc.update("create table users(id bigint primary key, name varchar(100))");
    jdbc.update("insert into users values(?, ?)", 7L, "Ada");
    BoundSql query = parse("select name from users where id=#{id}");
    IocLab.check(
        jdbc.query(query.sql(), rs -> rs.getString("name"), query.arguments(Map.of("id", 7L)))
            .equals(List.of("Ada")),
        "mapped query");
    tx.execute(
        () -> {
          jdbc.update("update users set name=? where id=?", "Lin", 7L);
          return null;
        });
    try {
      tx.execute(
          () -> {
            jdbc.update("update users set name=? where id=?", "Bad", 7L);
            throw new IllegalStateException("abort");
          });
      throw new AssertionError("no rollback");
    } catch (IllegalStateException expected) {
    }
    IocLab.check(
        jdbc.query("select name from users where id=?", rs -> rs.getString(1), 7L)
            .equals(List.of("Lin")),
        "database rollback");
    System.out.println(
        "PASS H2 integration: create, insert, query, committed update, rollback preserves data");
  }

  public static void main(String[] args) throws Exception {
    contractTests();
    if (args.length > 0 && args[0].equals("h2")) databaseTests();
  }
}
