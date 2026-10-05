package io.github.codeideaai.library;

/** The service describes the use case; a proxy supplies its transaction boundary. */
public final class ReservationService implements Reservations {
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
