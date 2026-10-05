---
pagetitle: "Define the Reservation API Contract"
---

# 03 Define the Reservation API Contract

[中文](../../tutorial/03-为预约结果定义接口契约.md) · [Series contents](../README.md)

The business can reserve a copy, but callers still need a stable way to distinguish success from rejection. Define POST /reservations to return 201 on creation and 409 for duplicates or exhausted stock. Unknown paths return 404; unsupported methods return 405 with Allow.

Start with a transport-independent dispatch function. No network listener is involved yet. Routing can then be tested without mixing sockets, character encoding and persistence into the same experiment.

## Complete code

Save this entire listing as `Demo03.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class Demo03 {
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

  record Reply(int status, String body, String allow) {}

  static Reply dispatch(MemoryLibrary library, String method, String path, Domain.Request request) {
    if (!path.equals("/reservations")) return new Reply(404, "route not found", "");
    if (!method.equals("POST")) return new Reply(405, "method not allowed", "POST");
    try {
      Domain.Reservation created = library.reserve(request);
      return new Reply(201, "reservation=" + created.id(), "");
    } catch (Domain.Problem problem) {
      return new Reply(problem.status(), problem.getMessage(), "");
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

    MemoryLibrary library = new MemoryLibrary(1);
    Domain.Request request = new Domain.Request("r1", 101, "Lin");
    check(
        dispatch(library, "GET", "/reservations", request).allow().equals("POST"),
        "method contract");
    check(dispatch(library, "POST", "/missing", request).status() == 404, "unknown route");
    check(dispatch(library, "POST", "/reservations", request).status() == 201, "created");
    check(dispatch(library, "POST", "/reservations", request).status() == 409, "duplicate");
    System.out.println("created=201;duplicate=409;method=405;missing=404");
  }
}
```

## Compile and run

Use JDK 17. Only the JDK standard library is required.

```bash
javac --release 17 -encoding UTF-8 Demo03.java
java Demo03
```

Expected output:

```text
created=201;duplicate=409;method=405;missing=404
```

## Trace the operation

Dispatch checks the path and method before calling the business operation. Checking the method afterward could let a forbidden GET consume a copy.

Reply carries status, body and the permitted method. Domain.Problem represents an expected rejection and can become a response. Unexpected programming failures should not all become conflicts; the final router will translate them to 500 at its boundary.

The request already contains typed domain data here. The next chapter handles strings. This separation lets tests distinguish selecting the correct operation from constructing valid input.

## Try it yourself

Send a valid reservation to an unknown path and verify that the reservation count stays unchanged. Move method checking after the business call and show why observing 405 alone does not prove the absence of side effects.

Next: [Reject Invalid Input Before Any Write](04-request-binding.md).
