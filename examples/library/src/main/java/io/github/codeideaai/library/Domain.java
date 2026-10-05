package io.github.codeideaai.library;

/** Small immutable values shared by the application and its adapters. */
public final class Domain {
  private Domain() {}

  public record Book(long id, String title, int available) {}

  public record Reservation(String id, long bookId, String member) {}

  public record Request(String id, long bookId, String member) {
    public Request {
      if (id == null || !id.matches("[A-Za-z0-9-]{1,40}"))
        throw new Problem(400, "invalid reservation id");
      if (bookId <= 0) throw new Problem(400, "invalid book id");
      if (member == null || member.isBlank() || member.length() > 40)
        throw new Problem(400, "invalid member");
      member = member.strip();
    }
  }

  public static final class Problem extends RuntimeException {
    private final int status;

    public Problem(int status, String message) {
      super(message);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
