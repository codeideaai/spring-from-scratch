package io.github.codeideaai.library;

import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** The composition root: assemble infrastructure before exposing any request handler. */
public final class LibraryApp implements AutoCloseable {
  public final Transactions transactions;
  public final ReservationStore store;
  public final Reservations reservations;
  public final Web.Router router;
  private final BeanBox beans;

  public LibraryApp(String url, int copies, Runnable failurePoint) throws Exception {
    Class.forName("org.h2.Driver");
    transactions = new Transactions(() -> DriverManager.getConnection(url));
    store = new ReservationStore(new Jdbc(transactions));
    store.initialize(copies);
    beans = new BeanBox();
    beans.instance("store", store);
    beans.instance("failurePoint", failurePoint);
    beans.define("service", ReservationService.class, "store", "failurePoint");
    beans.define("controller", Web.Controller.class, "service");
    beans.process(
        (name, bean) ->
            name.equals("service")
                ? Advisors.wrap(
                    Reservations.class,
                    (Reservations) bean,
                    List.of(Advisors.transaction(transactions)))
                : bean);
    beans.start();
    reservations = beans.get("service", Reservations.class);
    router = new Web.Router();
    router.register(beans.get("controller"));
    router.freeze();
  }

  public void close() {
    beans.close();
  }

  public static void main(String[] args) throws Exception {
    String url = args.length > 1 ? args[1] : "jdbc:h2:./build/library-data";
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
    LibraryApp app = new LibraryApp(url, 3, () -> {});
    HttpGateway gateway;
    try {
      gateway = new HttpGateway(app.router, port);
    } catch (Exception failure) {
      app.close();
      throw failure;
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  gateway.close();
                  app.close();
                }));
    System.out.println("Library API: http://127.0.0.1:" + gateway.port() + "/books?bookId=101");
    new CountDownLatch(1).await();
  }
}
