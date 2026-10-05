package io.github.codeideaai.library;

import java.sql.SQLException;

/** Persistence rules for one book and its reservations. */
public final class ReservationStore {
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
            "update books set available=available-1 where id=? and available>0", request.bookId());
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
