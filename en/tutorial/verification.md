---
pagetitle: "Reservation Tutorial Verification"
---

# Reservation Tutorial Verification

[中文](../../tutorial/验证记录.md) · [Series contents](../README.md)

On October 5, 2026, all 34 rebuilt article programs compiled independently, executed and matched their printed output. The new library application's acceptance checks also passed. Verification extracts code directly from each article without borrowing implementation types from the example project.

## Coverage

| Chapter | Verified behavior |
| --- | --- |
| 01 | Valid requests, duplicate rejection and conservation of copies |
| 02 | Two real threads compete for the final copy; exactly one succeeds |
| 03 | 201, 409, 404, 405 and Allow contract |
| 04 | Order-independent binding; missing, invalid, duplicate and malformed values |
| 05 | An intentional partial auto-commit against real H2 |
| 06 | Failure between writes restores both stock and reservations |
| 07 | Template connection sharing and rollback of a unique-constraint failure |
| 08 | Service proxy transaction boundary and duplicate rollback |
| 09 | Audit success follows commit; failures do not record success |
| 10 | Factory caching and final-service singleton identity |
| 11 | Constructor resolution and cycle rejection |
| 12 | Startup-failure cleanup and idempotent close |
| 13 | Final proxy caching and actual transaction behavior |
| 14 | Annotation routing, duplicates, named binding and conversion |
| 15 | XML assembly and unknown-attribute rejection |
| 16 | Complete request path with 201, 409, 500 and database-state assertions |
| 17 | Real HTTP, UTF-8, concurrent stock, reopening and lifecycle checks |

A passing Chapter 5 deliberately reproduces a partial commit; it does not establish atomicity for that version. Database chapters are 05–11, 13 and 15–17, using actual H2 2.2.224. Chapter 17 opens a temporary HTTP listener and includes a two-thread race by default.

## Reproduce the checks

```bash
python3 tools/format_java.py check
python3 tools/verify_article_code.py
bash examples/library/run.sh test
python3 tools/verify_library_restart.py
```

Prepare JDK 17 and H2 using the homepage commands first. Spotless 3.10.3 uses google-java-format 1.24.0 in GOOGLE mode for 48 Java files: 34 article programs and 14 application source files. Comments use each edition's language; executable tokens and expected output are compared for every chapter pair. Project alignment also checks 83 shared component snapshots covering all 14 source files, with three explicitly declared earlier-stage variants.

The standalone application checks commit, duplicate identifiers/members, failure between writes, thread cleanup, nesting rejection, the last-copy race, HTTP and startup resource cleanup. A separate process-restart script uses a temporary file database, launches two Java processes sequentially, and checks that stock is not reseeded and existing membership still prevents duplicate reservation.

## HTTP contract checks

| Scenario | Result |
| --- | --- |
| Read an existing book | 200 |
| Valid reservation | 201 with stock decrement and a corresponding record |
| Duplicate member or identifier | 409 with restored stock |
| Missing values, invalid numbers or repeated query parameters | 400 |
| Missing book or route | 404 |
| GET /reservations | 405 with Allow: POST |
| Injected failure after stock change | Router returns 500; both tables retain their prior state |
| Chinese member name | Correct UTF-8 and Content-Length equal to actual byte length |

The failure hook is supplied by tests during application construction, not exposed as a public HTTP endpoint. Passing these checks is not a production-readiness claim. See [implementation boundaries](implementation-notes.md).
