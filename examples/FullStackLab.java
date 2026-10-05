import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** 将控制器、事务代理和真实数据库接起来，验证端到端的业务结果。 */
public class FullStackLab {
  public interface Users {
    String find(long id) throws SQLException;

    void rename(long id, String name) throws SQLException;

    void renameThenFail(long id, String name) throws SQLException;
  }

  public static class Repository {
    final JdbcLab.JdbcTemplate jdbc;

    public Repository(JdbcLab.JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    public String find(long id) throws SQLException {
      var rows = jdbc.query("select name from users where id=?", rs -> rs.getString(1), id);
      if (rows.size() != 1) throw new IllegalArgumentException("expected exactly one user");
      return rows.get(0);
    }

    public void rename(long id, String name) throws SQLException {
      if (jdbc.update("update users set name=? where id=?", name, id) != 1)
        throw new IllegalArgumentException("expected exactly one update");
    }
  }

  public static class Service implements Users {
    final Repository repository;

    public Service(Repository repository) {
      this.repository = repository;
    }

    public String find(long id) throws SQLException {
      return repository.find(id);
    }

    public void rename(long id, String name) throws SQLException {
      repository.rename(id, name);
    }

    public void renameThenFail(long id, String name) throws SQLException {
      repository.rename(id, name);
      throw new IllegalStateException("abort after update");
    }
  }

  public static class Controller {
    @Container.Inject public Users users;

    @MvcLab.Route(path = "/users")
    public String find(@MvcLab.Param("id") long id) throws SQLException {
      return users.find(id);
    }

    @MvcLab.Route(path = "/rename", method = "POST")
    public String rename(@MvcLab.Param("id") long id, @MvcLab.Param("name") String name)
        throws SQLException {
      users.rename(id, name);
      return "renamed";
    }

    @MvcLab.Route(path = "/rename-fail", method = "POST")
    public String fail(@MvcLab.Param("id") long id, @MvcLab.Param("name") String name)
        throws SQLException {
      users.renameThenFail(id, name);
      return "unreachable";
    }
  }

  static Object proceed(AopLab.Invocation invocation) throws Exception {
    try {
      return invocation.proceed();
    } catch (Exception | Error e) {
      throw e;
    } catch (Throwable other) {
      throw new IllegalStateException(other);
    }
  }

  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    var tx =
        new JdbcLab.Transactions(
            () -> DriverManager.getConnection("jdbc:h2:mem:full;DB_CLOSE_DELAY=-1"));
    var jdbc = new JdbcLab.JdbcTemplate(tx);
    jdbc.update("create table users(id bigint primary key, name varchar(100))");
    jdbc.update("insert into users values(?, ?)", 7L, "Ada");
    try (var c = new Container()) {
      var advisor =
          new AopLab.Advisor(
              m -> m.getName().startsWith("rename"),
              invocation -> tx.execute(() -> proceed(invocation)));
      c.addProcessor(
          new Container.Processor() {
            public Object after(Object bean, String name) {
              // 初始化完成后创建代理，容器会缓存并注入这个返回值。
              return name.equals("service") ? AopLab.proxy(bean, List.of(advisor)) : bean;
            }
          });
      c.register(
          "repository",
          new Container.Definition(Repository.class)
              .constructor(new Class<?>[] {JdbcLab.JdbcTemplate.class}, jdbc));
      c.register(
          "service",
          new Container.Definition(Service.class)
              .constructor(new Class<?>[] {Repository.class}, new Container.Ref("repository")));
      c.register("controller", new Container.Definition(Controller.class));
      c.refresh();
      var dispatcher = new MvcLab.Dispatcher();
      dispatcher.register(c.getBean(Controller.class));
      var read = new MvcLab.Request("GET", "/users", Map.of("id", "7"));
      IocLab.check(dispatcher.dispatch(read).body().equals("Ada"), "query through all layers");
      var update = new MvcLab.Request("POST", "/rename", Map.of("id", "7", "name", "Lin"));
      IocLab.check(
          dispatcher.dispatch(update).status() == 200
              && dispatcher.dispatch(read).body().equals("Lin"),
          "transaction proxy commits");
      var failing = new MvcLab.Request("POST", "/rename-fail", Map.of("id", "7", "name", "Bad"));
      IocLab.check(
          dispatcher.dispatch(failing).status() == 500
              && dispatcher.dispatch(read).body().equals("Lin"),
          "transaction proxy rolls back");
      IocLab.check(tx.current.get() == null, "no leaked thread connection");
    }
    System.out.println(
        "PASS full stack: MVC -> injected AOP proxy -> service -> JDBC -> H2, commit and rollback");
  }
}
