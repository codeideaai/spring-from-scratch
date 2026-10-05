package io.github.codeideaai.library;

import java.util.LinkedHashMap;
import java.util.Map;

/** The first executable model of the reservation rules; no framework is required. */
public final class MemoryLibrary {
  private final Map<String, Domain.Reservation> reservations = new LinkedHashMap<>();
  private int available;

  public MemoryLibrary(int copies) {
    if (copies < 0) throw new IllegalArgumentException("negative copies");
    available = copies;
  }

  public synchronized Domain.Book book(long id) {
    if (id != 101) throw new Domain.Problem(404, "book not found");
    return new Domain.Book(101, "The Art of Testing", available);
  }

  public synchronized Domain.Reservation reserve(Domain.Request request) {
    book(request.bookId());
    if (reservations.containsKey(request.id())
        || reservations.values().stream()
            .anyMatch(r -> r.bookId() == request.bookId() && r.member().equals(request.member())))
      throw new Domain.Problem(409, "duplicate reservation");
    if (available == 0) throw new Domain.Problem(409, "no copies available");
    // Validation and both writes form one critical section, not three separate operations.
    Domain.Reservation reservation =
        new Domain.Reservation(request.id(), request.bookId(), request.member());
    reservations.put(reservation.id(), reservation);
    available--;
    return reservation;
  }

  public synchronized int count() {
    return reservations.size();
  }
}
