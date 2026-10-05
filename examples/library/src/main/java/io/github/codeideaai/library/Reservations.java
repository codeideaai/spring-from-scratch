package io.github.codeideaai.library;

public interface Reservations {
  Domain.Book book(long id) throws Exception;

  Domain.Reservation reserve(Domain.Request request) throws Exception;
}
