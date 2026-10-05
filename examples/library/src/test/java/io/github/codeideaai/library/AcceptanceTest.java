package io.github.codeideaai.library;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Contract tests use actual H2 connections and an actual ephemeral HTTP listener. */
public final class AcceptanceTest {
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static String database() {
    return "jdbc:h2:mem:library_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
  }

  static void expect(int status, Transactions.Work<?> work) throws Exception {
    try {
      work.run();
      throw new AssertionError("expected " + status);
    } catch (Domain.Problem problem) {
      check(problem.status() == status, "wrong problem status");
    }
  }

  public static void main(String[] args) throws Exception {
    String url = database();
    try (LibraryApp app = new LibraryApp(url, 3, () -> {})) {
      app.reservations.reserve(new Domain.Request("first", 101, "Lin"));
      check(
          app.store.book(101).available() == 2 && app.store.count() == 1, "successful reservation");
      expect(409, () -> app.reservations.reserve(new Domain.Request("second", 101, "Lin")));
      expect(409, () -> app.reservations.reserve(new Domain.Request("first", 101, "Ada")));
      check(
          app.store.book(101).available() == 2 && app.store.count() == 1,
          "unique failure restores stock");
      check(!app.transactions.active(), "transaction thread cleaned");
      expect(404, () -> app.reservations.reserve(new Domain.Request("absent", 999, "Ada")));
    }
    try (LibraryApp reopened = new LibraryApp(url, 100, () -> {})) {
      check(
          reopened.store.book(101).available() == 2 && reopened.store.count() == 1,
          "startup must not reseed");
    }
    try (LibraryApp failing =
        new LibraryApp(
            database(),
            2,
            () -> {
              throw new IllegalStateException("injected failure");
            })) {
      Web.Reply reply =
          failing.router.dispatch(
              "POST", "/reservations", Map.of("id", "failure", "bookId", "101", "member", "Ada"));
      check(reply.status() == 500, "failure reaches HTTP boundary");
      check(
          failing.store.book(101).available() == 2 && failing.store.count() == 0,
          "both writes rolled back");
      check(!failing.transactions.active(), "failed transaction thread cleaned");
      try {
        failing.transactions.run(() -> failing.transactions.run(() -> 1));
        throw new AssertionError("nested");
      } catch (IllegalStateException expected) {
        check(!failing.transactions.active(), "nested cleanup");
      }
    }
    concurrentReservation();
    httpContract();
    containerFailure();
    System.out.println(
        "PASS library: commit, rollback, duplicates, restart, race, HTTP, lifecycle");
  }

  static void concurrentReservation() throws Exception {
    try (LibraryApp app = new LibraryApp(database(), 1, () -> {})) {
      var pool = Executors.newFixedThreadPool(2);
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch go = new CountDownLatch(1);
      try {
        List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          final String id = "race-" + i;
          results.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    try {
                      app.reservations.reserve(new Domain.Request(id, 101, id));
                      return 201;
                    } catch (Domain.Problem problem) {
                      return problem.status();
                    } finally {
                      check(!app.transactions.active(), "worker connection leak");
                    }
                  }));
        }
        check(ready.await(5, TimeUnit.SECONDS), "workers ready");
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
        statuses.sort(Integer::compareTo);
        check(statuses.equals(List.of(201, 409)), "exactly one winner");
        check(app.store.book(101).available() == 0 && app.store.count() == 1, "no overselling");
      } finally {
        go.countDown();
        pool.shutdownNow();
      }
    }
  }

  static HttpResponse<String> send(HttpClient client, String base, String method, String path)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(java.time.Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  static void httpContract() throws Exception {
    try (LibraryApp app = new LibraryApp(database(), 3, () -> {});
        HttpGateway gateway = new HttpGateway(app.router, 0)) {
      String base = "http://127.0.0.1:" + gateway.port();
      HttpClient client = HttpClient.newHttpClient();
      check(send(client, base, "GET", "/books?bookId=101").statusCode() == 200, "read");
      for (String query : List.of("", "?bookId=bad", "?bookId=101&bookId=101"))
        check(send(client, base, "GET", "/books" + query).statusCode() == 400, "bad query");
      check(send(client, base, "GET", "/books?bookId=999").statusCode() == 404, "missing book");
      check(send(client, base, "GET", "/missing").statusCode() == 404, "missing route");
      var method = send(client, base, "GET", "/reservations");
      check(
          method.statusCode() == 405
              && method.headers().firstValue("Allow").orElse("").equals("POST"),
          "method and Allow");
      String member = URLEncoder.encode("读者甲", StandardCharsets.UTF_8);
      var created =
          send(client, base, "POST", "/reservations?id=http-1&bookId=101&member=" + member);
      check(created.statusCode() == 201 && created.body().contains("读者甲"), "unicode response");
      check(
          created.headers().firstValueAsLong("Content-Length").orElse(-1)
              == created.body().getBytes(StandardCharsets.UTF_8).length,
          "byte length");
      check(
          send(client, base, "POST", "/reservations?id=http-2&bookId=101&member=" + member)
                  .statusCode()
              == 409,
          "HTTP duplicate");
      check(app.store.book(101).available() == 2 && app.store.count() == 1, "HTTP rollback state");
    }
  }

  public static final class Resource implements AutoCloseable {
    static int closed;

    public Resource() {}

    public void close() {
      closed++;
    }
  }

  public static final class Broken {
    public Broken(Resource resource) {
      throw new IllegalStateException("cannot start");
    }
  }

  static void containerFailure() {
    Resource.closed = 0;
    BeanBox box = new BeanBox();
    box.define("resource", Resource.class);
    box.define("broken", Broken.class, "resource");
    try {
      box.start();
      throw new AssertionError("expected startup failure");
    } catch (IllegalStateException expected) {
      check(Resource.closed == 1, "startup cleanup");
    }
    box.close();
    check(Resource.closed == 1, "idempotent close");
  }
}
