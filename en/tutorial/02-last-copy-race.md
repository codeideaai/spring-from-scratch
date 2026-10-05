---
pagetitle: "Give the Last Copy to Exactly One Reader"
---

# 02 Give the Last Copy to Exactly One Reader

[中文](../../tutorial/02-最后一本书只能有一个预约者.md) · [Series contents](../README.md)

A passing single-threaded example does not prove stock safety. Two requests might both observe one available copy and then both succeed. Use two threads and one release signal to turn that concern into an executable race.

The domain model stays unchanged. CountDownLatch and an executor are JDK testing tools, not new business dependencies.

## Complete code

Save this entire listing as `Demo02.java`. Every implementation type, business object and verification entry point is included; no other tutorial source file is required.

```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Demo02 {
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

    MemoryLibrary library = new MemoryLibrary(1);
    var workers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        final String id = "r" + i;
        results.add(
            workers.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                  try {
                    library.reserve(new Domain.Request(id, 101, id));
                    return 201;
                  } catch (Domain.Problem problem) {
                    return problem.status();
                  }
                }));
      }
      check(ready.await(5, TimeUnit.SECONDS), "workers ready");
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (var result : results) statuses.add(result.get(5, TimeUnit.SECONDS));
      statuses.sort(Integer::compareTo);
      check(statuses.equals(List.of(201, 409)), "exactly one winner");
      check(library.book(101).available() == 0 && library.count() == 1, "no overselling");
      System.out.println("statuses=[201, 409];available=0");
    } finally {
      start.countDown();
      workers.shutdownNow();
    }
  }
}
```

## Compile and run

Use JDK 17. Only the JDK standard library is required.

```bash
javac --release 17 -encoding UTF-8 Demo02.java
java Demo02
```

Expected output:

```text
statuses=[201, 409];available=0
```

## Trace the operation

The ready latch confirms both workers have reached the barrier. The start latch releases them together; sleeping would only guess at scheduling. Timed Future.get propagates worker failures and bounds the test duration.

The reserve lock covers duplicate checks, the stock check and both state changes. A concurrent map would protect individual map operations, not this compound action. Reads take the same lock so they cannot observe a half-finished mutation.

Require sorted statuses [201, 409], rather than a particular winner. The rule concerns the outcome, not scheduling order. This lock protects one object in one process; independent processes need a shared storage mechanism.

## Try it yourself

Remove synchronized and repeat the experiment. Occasional passing runs do not establish correctness. Explain what the race demonstrates and why it cannot exhaust every schedule; later chapters enforce the same rule with a conditional database update.

Next: [Define the Reservation API Contract](03-api-contract.md).
