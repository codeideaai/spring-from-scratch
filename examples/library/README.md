# Library Reservation Application

[中文](README.zh.md) · [English tutorial](https://codeideaai.github.io/spring-from-scratch/en/)

An executable project built around one invariant: a successful reservation consumes one available copy and creates one record; a rejected reservation changes neither. The application supplies its own routing, constructor injection, proxies, interceptor chain, JDBC template and transaction boundary. H2 supplies only the database engine.

## Run

Use JDK 17, Python 3 for article checks, and a Bash-compatible terminal for the launcher. Run from the repository root:

```bash
mkdir -p build/lib
curl -fL -o build/lib/h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
bash examples/library/run.sh test
bash examples/library/run.sh serve
```

The test command compiles main and test sources and runs real H2, concurrency and loopback HTTP checks. It exits after printing:

```text
PASS library: commit, rollback, duplicates, restart, race, HTTP, lifecycle
```

Serve binds `127.0.0.1:8080` and uses `build/library-data.mv.db`. A fresh database contains book 101 with three copies. Startup does not reset an existing database. Stop with Ctrl+C. To use another port and a fresh database:

```bash
bash examples/library/run.sh serve 8090 jdbc:h2:./build/another-library
```

On Windows, use Git Bash for the launcher or compile the source list with `javac --release 17 -encoding UTF-8 -d build/library-classes @build/library-sources.txt` after generating that list. The Java classpath must use a semicolon: `build/library-classes;build/lib/h2-2.2.224.jar`.

## API contract

The API uses UTF-8 text responses and query parameters, including for POST. It does not parse a JSON request body.

| Method and path | Parameters | Success |
| --- | --- | --- |
| GET /books | bookId | 200, `bookId=101;available=3` on a new database |
| POST /reservations | id, bookId, member | 201, `reservation=demo-1;member=Lin` |

```bash
curl -i 'http://127.0.0.1:8080/books?bookId=101'
curl -i -X POST 'http://127.0.0.1:8080/reservations?id=demo-1&bookId=101&member=Lin'
curl -i -X POST 'http://127.0.0.1:8080/reservations?id=demo-2&bookId=101&member=Lin'
curl -i 'http://127.0.0.1:8080/books?bookId=101'
```

On a fresh database the statuses are 200, 201, 409 and 200; final stock is two. Existing records affect repeated runs. Use another reader and identifier for another reservation. Reservation identifiers allow 1–40 ASCII letters, digits and hyphens; members are nonblank, at most 40 Java characters and stripped of surrounding whitespace.

Invalid input returns 400. Unknown paths/books return 404. Unsupported methods return 405 with Allow. Duplicate identifiers, duplicate book/member pairs and insufficient stock return 409. Unexpected operation failures return 500. If stock is already zero, insufficient stock may be reported before duplicate detection; both are conflicts.

## Components

All Java sources use the package `io.github.codeideaai.library`.

| File | Responsibility |
| --- | --- |
| Domain.java | Immutable values, validated request and domain rejection |
| MemoryLibrary.java | Initial in-memory rules and synchronized reservation |
| Transactions.java | Connection ownership, commit/rollback, thread cleanup |
| Jdbc.java | Parameter binding and statement/result-set lifetime |
| ReservationStore.java | Conditional decrement, uniqueness, durable state |
| Reservations.java / ReservationService.java | Business contract and implementation |
| Advisors.java | Interface proxy, per-call interceptor chain and transaction advice |
| BeanBox.java | Constructor graph, singleton cache, processors and owned resources |
| BeanXml.java | Optional strict local dependency configuration |
| Web.java | Route discovery, typed query binding and response mapping |
| HttpGateway.java | Actual HTTP transport and worker shutdown |
| LibraryApp.java | Application assembly and command-line entry point |
| AcceptanceTest.java | Real database, race, lifecycle and HTTP checks |

The default application uses Java configuration. BeanXml is exercised in Chapter 15 as an alternative definition source. It is not silently claimed to be the runtime's default. Each article prints its own complete program, independent of these files.

## Correctness boundaries

Container setup is single-threaded and constructor-only, with no cycles, prototype scope, scanning or hot reload. Configure and freeze everything before serving requests. Objects supplied through `instance` are borrowed; constructed AutoCloseable objects are owned and closed in reverse order.

The database enforces stock through a conditional update and reservation uniqueness through constraints. Two writes are atomic only when callers enter through the transactional service. Direct calls to the raw service/store bypass that boundary. Local transaction nesting is rejected; no propagation, pooling or distributed transaction protocol is implemented. Do not infer a known outcome after a connection failure during commit or retry automatically.

The system intentionally excludes authentication, cancellation, expiry, JSON bodies, idempotent replay and production capacity controls. Startup supports one initializer at a time. The HTTP adapter is loopback-only and not a Servlet container. Tests are evidence for the covered H2/JDK behavior, not a general production readiness claim.
