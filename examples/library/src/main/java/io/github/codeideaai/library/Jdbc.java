package io.github.codeideaai.library;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQL values are bound as parameters; table and column names belong to application SQL. */
public final class Jdbc {
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
