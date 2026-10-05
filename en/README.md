# Spring from Scratch: A Library Reservation System

[Read online](https://codeideaai.github.io/spring-from-scratch/en/) · [GitHub](https://github.com/codeideaai/spring-from-scratch) · [中文](../README.md)

Two readers cannot take the last copy. A failed reservation must restore stock. Start with these business constraints, then build transactions, proxies, a container and routing when the application needs them.

The first four chapters establish the memory model and API contract. Chapter 5 deliberately reproduces a partial database commit; transactions and a JDBC template address that failure. Factory registration, constructor graphs, lifecycle handling and automatic proxies arrive when repeated assembly becomes a problem. Real HTTP requests and concurrent reservations provide final acceptance evidence.

## Start reading

Begin with the [series guide](tutorial/00-start-with-reservations.md). Every chapter prints a complete Java program, key comments, commands, expected output and a failure experiment. No companion source is required. Use JDK 17; database chapters include download commands for H2 2.2.224.

| Chapter | Problem to solve |
| --- | --- |
| 01 | [Start with Executable Reservation Rules](tutorial/01-reservation-rules.md) |
| 02 | [Give the Last Copy to Exactly One Reader](tutorial/02-last-copy-race.md) |
| 03 | [Define the Reservation API Contract](tutorial/03-api-contract.md) |
| 04 | [Reject Invalid Input Before Any Write](tutorial/04-request-binding.md) |
| 05 | [Persist Stock and Expose a Partial Commit](tutorial/05-partial-commit.md) |
| 06 | [Commit Stock and Reservation Together](tutorial/06-atomic-reservation.md) |
| 07 | [Extract JDBC Work Without Losing Connection Ownership](tutorial/07-jdbc-ownership.md) |
| 08 | [Put Transactions Around the Service Interface](tutorial/08-service-proxy.md) |
| 09 | [Record Success Only After the Transaction Commits](tutorial/09-advice-order.md) |
| 10 | [Share One Assembled Service Across Entry Points](tutorial/10-service-registry.md) |
| 11 | [Resolve a Constructor Dependency Graph](tutorial/11-constructor-graph.md) |
| 12 | [Release Resources When Startup Fails](tutorial/12-startup-cleanup.md) |
| 13 | [Expose and Cache the Completed Proxy](tutorial/13-exposed-proxy.md) |
| 14 | [Declare Routes and Query Bindings Beside Methods](tutorial/14-annotated-routes.md) |
| 15 | [Load Dependency Metadata from Strict Configuration](tutorial/15-dependency-configuration.md) |
| 16 | [Trace One Reservation Through the Whole Application](tutorial/16-application-assembly.md) |
| 17 | [Verify the System Through Real HTTP and Concurrent Reservations](tutorial/17-system-acceptance.md) |

## Complete example project

The [library reservation application](../examples/library/README.md) uses normal Java packages and component files, with a command-line entry point, a file-backed database and automated acceptance checks. Standalone article programs and the multi-file application support different reading styles; all framework implementation lives in this repository.

```bash
mkdir -p build/lib
curl -fL -o build/lib/h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
bash examples/library/run.sh test
bash examples/library/run.sh serve
```

## Verification and scope

Checks cover conservation of copies, duplicate rollback, the last-copy race, connection ownership, startup cleanup and actual HTTP statuses. See the [verification record](tutorial/verification.md) and [implementation boundaries](tutorial/implementation-notes.md).

This is a teaching system for framework control flow. It does not implement authentication, cancellation, expiry, distributed transactions or full Spring API compatibility.
