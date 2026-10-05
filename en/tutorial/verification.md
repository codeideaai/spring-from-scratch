---
pagetitle: "Verification of the Code Printed in the Articles"
---

# Verification of the Code Printed in the Articles

[中文](../../tutorial/验证记录.md) · [Series contents](../README.md)

Verification extracts code directly from the articles to check that readers can copy and run each chapter independently. The local environment is macOS with OpenJDK 17.0.20, targeting Java 17 and using H2 2.2.224.

On October 5, 2026, both editions passed all 34 independent compilation, execution and output checks. Spotless checked 41 Java files (34 article programs and 7 companion examples), and all 17 bilingual implementation/output pairs matched.

## Independent compilation

Each edition contains one complete Java block per chapter from 01 through 17, saved as Demo01.java through Demo17.java. Each program is compiled in its own directory; its classpath does not include another chapter or the earlier companion experiments.

The verification script compiles and executes all 34 programs and compares their output with the expected output printed in the corresponding article. Chapters 11, 12, 16 and 17 use an actual H2 in-memory database. The remaining chapters use only the JDK standard library. A separate parity check compares executable Java tokens and expected outputs across languages, allowing comments and formatting to differ.

| Chapter | Verification coverage |
| --- | --- |
| 01 | First creation, singleton identity, missing definitions |
| 02 | Separation of XML loading and instantiation, singleton identity |
| 03 | Constructor and setter injection, singleton and prototype scopes |
| 04 | Setter-cycle identity, early-cache cleanup and failure invalidation |
| 05 | Before/after initialization order, final-instance caching, destruction |
| 06 | Annotation injection, identity, rejection of ambiguous type lookup |
| 07 | Refresh event, parent dependencies, closing a child without closing its parent |
| 08 | Dispatch, 404, 405, duplicate-route rejection |
| 09 | Explicit parameter names, numeric conversion, missing values and invalid booleans |
| 10 | Text and view results, HTML escaping, error responses |
| 11 | Actual SQL, row mapping, empty results, update counts |
| 12 | Mapper XML, selectOne, update, repeated parameters, zero and multiple rows |
| 13 | JDK proxies, self-invocation, original business exceptions, proxy equality |
| 14 | Interceptor ordering, return values, forwarding of unmatched methods |
| 15 | Automatic proxying, injected/cached proxy identity, advice on repeated calls |
| 16 | Shared connection identity, commit, rollback, ThreadLocal cleanup, nesting rejection |
| 17 | MVC, IoC, AOP, Service, Repository and database integration |

Maintainer scripts live in the repository's tools directory. Readers do not need them: each chapter already contains its compilation and execution commands.

## Comments and formatting

The Chinese edition's October 4, 2026 update added key implementation comments and standardized 17 article programs plus seven companion Java files using Spotless 3.10.3 and google-java-format 1.24.0 in GOOGLE mode. Its compilation, output comparisons, five companion experiments, JdbcLab h2 and FullStackLab checks all passed.

The English edition translates those comments and extends formatting checks to 41 Java sources: 34 article programs and seven shared examples. Explicit imports are required; wildcard imports are rejected. Run `python3 tools/format_java.py check` from the repository root. To format and write results back into both editions, use `python3 tools/format_java.py apply`.

The publication workflow checks formatting before code execution and website rendering. The English edition's code is kept inline rather than linked to external source files.

## Recorded HTTP verification

The original Chinese-edition validation started Demo17 serve from the extracted article code at 127.0.0.1:8080. The service used H2 rather than fixed-response stubs and was stopped after testing. These results document that HTTP validation; they are distinct from the default integration assertions run by the automated article checker.

| Request | Result |
| --- | --- |
| GET /users?id=7 | 200, Lin |
| POST /rename?id=7&name=Grace | 200, renamed |
| GET /users?id=7 again | 200, Grace |
| POST /rename-fail?id=7&name=Bad | 500, controller failed |
| Query after the failed update | Still Grace, demonstrating rollback |
| GET /users?id=bad | 400 |
| GET /missing | 404 |
| POST /users?id=7 | 405, Allow: GET |
| GET /users?id=7&id=8 | 400, duplicate parameter rejected |
| Update and query a name containing Chinese characters | 200, text returned correctly |

The check also compared Content-Length with the actual UTF-8 byte length.

## Scope of these checks

Each article provides complete code, execution steps, expected output, implementation discussion and exercises. Chapter navigation and local links are checked separately when building the reading site.

Passing these checks does not imply concurrent container creation, Servlet support, general JSON handling, component scanning, a general SQL parser, pooling or transaction propagation. Those boundaries remain explicit in the articles. Chapter 4's early-reference experiment is separate from the main container, which continues to reject dependency cycles.

Return to the [series contents](../README.md).
