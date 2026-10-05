package io.github.codeideaai.library;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** A loopback-only text API. It accepts query parameters, not JSON request bodies. */
public final class HttpGateway implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService workers = Executors.newFixedThreadPool(4);

  public HttpGateway(Web.Router router, int port) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.setExecutor(workers);
    server.createContext(
        "/",
        exchange -> {
          Web.Reply reply;
          try {
            reply =
                router.dispatch(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    query(exchange.getRequestURI().getRawQuery()));
          } catch (IllegalArgumentException failure) {
            reply = new Web.Reply(400, "invalid query");
          }
          byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
          if (!reply.allow().isEmpty()) exchange.getResponseHeaders().set("Allow", reply.allow());
          exchange.sendResponseHeaders(reply.status(), bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public static Map<String, String> query(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return result;
    if (raw.length() > 4096) throw new IllegalArgumentException("query too long");
    for (String pair : raw.split("&", -1)) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
      // Reject duplicates rather than making request meaning depend on parameter order.
      if (key.isEmpty() || result.putIfAbsent(key, value) != null)
        throw new IllegalArgumentException("duplicate or empty parameter");
    }
    return result;
  }

  public void close() {
    server.stop(0);
    workers.shutdown();
    try {
      if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
    } catch (InterruptedException failure) {
      workers.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
