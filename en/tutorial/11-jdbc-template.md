---
pagetitle: "11 — Encapsulate JDBC with a Template"
---

# 11 — Encapsulate JDBC with a Template

[中文](../../tutorial/11-用模板封装JDBC访问.md) · [Series contents](../README.md)

We now replace in-memory results with a database. JDBC connection, statement, parameter and result-set handling follows a recurring sequence. SQL, values and the conversion of the current row into an object vary. JdbcTemplate owns the fixed sequence and RowMapper supplies row mapping.

All framework code, business objects and table-creation statements are in one file. The only runtime dependency is the H2 database driver. It implements JDBC; it does not supply our template.

Each operation independently obtains and releases a connection at this stage. Chapter 16 adds transaction awareness at the withConnection boundary.

## Complete code

Save the entire block as `Demo11.java`. It includes every required class, interface, annotation, helper and entry point; no other tutorial source file is needed.

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Let the template manage JDBC resources while a callback maps the current row to a business
 * object.
 */
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

## Compile and run

Use JDK 17. Download the pinned H2 driver into the current directory before the first run; skip the download if it is already present. H2 is only a database runtime dependency. All tutorial framework code is printed above.

```bash
curl -fL --connect-timeout 10 --max-time 60 -o h2-2.2.224.jar \
  https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
javac --release 17 Demo11.java
java -cp '.:h2-2.2.224.jar' Demo11
```

On Windows, use a semicolon in the classpath: `java -cp ".;h2-2.2.224.jar" Demo11`.

Expected output:

```text
[User[id=7, name=Ada], User[id=8, name=Lin]]
empty result and update count verified
```

## What the template owns

Each call obtains a connection from Connections, creates a PreparedStatement and binds values using JDBC indexes starting at 1. The template advances the result set with next; RowMapper handles only the current row. Calling next again inside the mapper would skip records.

Nested try-with-resources closes ResultSet, Statement and Connection in reverse creation order. Cleanup is attempted even when mapping throws, so business methods do not each need their own finally block.

SQLException remains visible to the caller. Catching every exception and returning an empty list would confuse a successful query with no rows with a database failure.

## Connection sources and pools

Connections is a small functional interface. A DataSource can adapt through dataSource::getConnection. Pooling is a property of the source, and the template need not know the pool's internals.

A pool must handle concurrent borrowing, timeouts, validation, returns and state reset. Storing connections in a List is insufficient. This chapter does not implement a pool or describe DriverManager as one.

## Parameter-binding boundaries

Question marks bind values, not table or column names. Pass user input to setObject rather than concatenating it into SQL. If SQL structure must vary, let the application select from a bounded set of valid choices.

Exercise: throw SQLException from RowMapper and confirm it reaches main. Query an absent user and confirm an empty list rather than null. Chapter 16 explains why individual SQL operations must not close a transaction's shared connection.

Next: [Turn SQL into Managed Metadata](12-sql-metadata.md).
