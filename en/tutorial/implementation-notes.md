---
pagetitle: "Implementation Boundaries and Extension Questions"
---

# Implementation Boundaries and Extension Questions

[中文](../../tutorial/实现边界与延伸阅读.md) · [Series contents](../README.md)

The design unit is a reservation invariant. Extracting a container, proxy or template must not change the relationship between available copies and reservation records.

| Area | Current contract | Questions before extending it |
| --- | --- | --- |
| Business | One sample title; unique reservation IDs and book/member pairs | How do cancellation, expiry and repeated cancellation affect stock? |
| Concurrency | Object lock in memory; conditional updates in the database | Does another database's isolation and lock-wait behavior satisfy the same assertions? |
| Persistence | File-backed H2 retains data; startup does not reset the book | Who coordinates concurrent initialization, migrations and recovery? |
| Transactions | One level per thread; commit or rollback; no nesting | What are propagation, asynchronous and uncertain-commit protocols? |
| JDBC | Template borrows the transaction connection and owns statements/results | Who resets and discards pooled connections? |
| Proxies | JDK interface proxy; reserve receives transaction advice | How are new writes matched, and does self-invocation bypass advice? |
| Container | Single-threaded configuration, constructor injection, singletons, no cycles | How should primitive values, ambiguous constructors and new scopes work? |
| Lifecycle | Constructed objects are owned; supplied objects are borrowed | Who cleans up a partially failed constructor, and how are close failures preserved? |
| Configuration | Trusted local XML with a strict bean/ref language | Are atomic configuration rollback and hot reload required? |
| Web | Single-valued query, String/long arguments and UTF-8 text | What are the contracts for JSON, authentication, limits, cancellation and replay? |

## Concurrency and identity are different properties

Singleton identity means multiple entry points receive the same completed object. It does not make internal business state thread-safe. This project freezes the object graph and routing table before serving requests, separates request connections through ThreadLocal and constrains stock with database updates.

A proxy's existence does not prove every call enters it. Direct raw-service/repository calls and internal this calls may bypass transactions. Acceptance checks verify boundaries and then query actual state.

## The next business extension

For cancellation, define legal state transitions and repeated-cancellation responses first. Then restore stock and change reservation state in one transaction before adding expiry jobs. An unconditional background stock increment can restore the same copy twice on retry.

For deeper study, consult the [JDK 17 API](https://docs.oracle.com/en/java/javase/17/docs/api/) and [Spring Framework documentation](https://docs.spring.io/spring-framework/reference/). Neither is required to run the listings, and this project does not claim full Spring API compatibility.
