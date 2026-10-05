---
pagetitle: "Reject Invalid Input Before Any Write"
---

# 04 Reject Invalid Input Before Any Write

[中文](../../tutorial/04-在写入之前拒绝无效输入.md) · [Series contents](../README.md)

An adapter receives strings rather than long values or Request objects. The contract uses id, bookId and member. Parameter order is irrelevant, while repeated names are rejected instead of allowing order to determine which value wins.

Decode the query, parse the number and construct the validated Request before calling reserve. Failed binding must not touch stock.

## Complete code

Save this entire listing as `Demo04.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Demo04 {
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

  static Map<String, String> query(String raw) {
    Map<String, String> values = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return values;
    for (String pair : raw.split("&", -1)) {
      String[] parts = pair.split("=", 2);
      String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
      if (name.isEmpty() || values.putIfAbsent(name, value) != null)
        throw new Domain.Problem(400, "duplicate or empty parameter");
    }
    return values;
  }

  static Domain.Request bind(String raw) {
    try {
      Map<String, String> values = query(raw);
      return new Domain.Request(
          values.get("id"), Long.parseLong(values.get("bookId")), values.get("member"));
    } catch (IllegalArgumentException failure) {
      throw new Domain.Problem(400, "invalid query");
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

    Domain.Request request = bind("member=Lin&bookId=101&id=r1");
    MemoryLibrary library = new MemoryLibrary(1);
    library.reserve(request);
    expect(400, () -> bind("id=r2&bookId=bad&member=Ada"));
    expect(400, () -> bind("id=r2&bookId=101&bookId=999&member=Ada"));
    expect(400, () -> bind("id=r2&bookId=101"));
    expect(400, () -> bind("id=r2&bookId=101&member=%ZZ"));
    check(library.count() == 1, "binding errors cannot write");
    System.out.println("bound=r1/101/Lin;invalid=400");
  }
}
```

## Compile and run

Use JDK 17. Only the JDK standard library is required.

```bash
javac --release 17 -encoding UTF-8 Demo04.java
java Demo04
```

Expected output:

```text
bound=r1/101/Lin;invalid=400
```

## Trace the operation

Split each pair at its first equals sign so additional equals signs remain in the value. Decode with UTF-8. putIfAbsent detects repeated names rather than overwriting them, and parameter names remain case-sensitive.

Long.parseLong handles number syntax. Request handles positive book identifiers, reservation identifier syntax and member length. Missing values, malformed escapes, invalid numbers and repeated parameters must all fail before the write path.

The implementation supports single-valued query parameters only. It does not bind JSON, arrays or arbitrary objects. Chapter 14 moves explicit names into method annotations, but annotations must preserve these validation rules.

## Try it yourself

Reorder the parameters and expect the same request. Try member=A%26B and verify that the encoded ampersand remains part of the member value. Remove member and confirm that no reservation is added.

Next: [Persist Stock and Expose a Partial Commit](05-partial-commit.md).
