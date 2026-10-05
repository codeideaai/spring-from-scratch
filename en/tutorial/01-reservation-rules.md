---
pagetitle: "Start with Executable Reservation Rules"
---

# 01 Start with Executable Reservation Rules

[中文](../../tutorial/01-先把预约规则写成可执行程序.md) · [Series contents](../README.md)

The library owns two copies of one title. A reservation by Lin must leave one available copy and one reservation record. The same member cannot reserve the same book twice. Missing books and invalid input must not change stock. Implement these rules before introducing containers or annotations.

Immutable records describe books, reservations and requests. Request validates its input at construction, while MemoryLibrary owns mutable stock and the reservation collection. Domain errors carry status codes for a later adapter; this chapter calls ordinary Java methods from main.

## Complete code

Save this entire listing as `Demo01.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Demo01 {
  /** Small immutable values shared by the application and its adapters. */
  public static final class Domain {
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

  /** The first executable model of the reservation rules; no framework is required. */
  public static final class MemoryLibrary {
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

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  @FunctionalInterface
  interface Task {
    void run() throws Exception;
  }

  static void expect(int status, Task task) throws Exception {
    try {
      task.run();
      throw new AssertionError("expected " + status);
    } catch (Domain.Problem problem) {
      check(problem.status() == status, "wrong status");
    }
  }

  static String database() {
    return "jdbc:h2:mem:chapter_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
  }

  public static void main(String[] args) throws Exception {

    MemoryLibrary library = new MemoryLibrary(2);
    Domain.Reservation reservation = library.reserve(new Domain.Request("r1", 101, "Lin"));
    check(reservation.bookId() == 101 && library.book(101).available() == 1, "one copy reserved");
    expect(409, () -> library.reserve(new Domain.Request("r2", 101, "Lin")));
    expect(400, () -> new Domain.Request("", 101, "Ada"));
    expect(404, () -> library.reserve(new Domain.Request("r3", 999, "Ada")));
    check(library.book(101).available() + library.count() == 2, "conservation of copies");
    System.out.println("available=1;reservations=1");
  }
}
```

## Compile and run

Use JDK 17. Only the JDK standard library is required.

```bash
javac --release 17 -encoding UTF-8 Demo01.java
java Demo01
```

Expected output:

```text
available=1;reservations=1
```

## Trace the operation

Read reserve from its checks to its writes. Book existence, reservation identity, duplicate membership and remaining stock are checked before a record is inserted or stock is decremented. Moving a check after a write can leave a failed operation with an unexplained side effect.

The central assertion is available + count == 2. It states conservation of this book's copies without depending on a particular Map implementation. Recheck it after rejected requests to verify that failures leave state unchanged.

The synchronized methods protect an entire operation on this object. The next chapter demonstrates why with two actual threads. This small model contains one title and does not yet model cancellation, returns or expiry.

## Try it yourself

Reserve the second copy for another member, then attempt a third reservation. Expect a conflict with stock still zero. Move the decrement before duplicate validation and identify the first conservation assertion that fails, then restore the implementation.

Next: [Give the Last Copy to Exactly One Reader](02-last-copy-race.md).
