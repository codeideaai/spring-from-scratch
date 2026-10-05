---
pagetitle: "Implementation Boundaries and Further Reading"
---

# Implementation Boundaries and Further Reading

[中文](../../tutorial/实现边界与延伸阅读.md) · [Series contents](../README.md)

## Understanding the implementation

The series starts with object creation and separates responsibilities as concrete needs appear: definitions from instances, request mapping from invocation, JDBC control flow from row mapping, and advice from method matching.

Each chapter contains a complete program, commands, expected output and exercises. Verify the current stage, then use failure cases to explore its limits. Chapter 17 connects the container, MVC, proxies and database to check successful updates and failed-update rollback.

## Key contracts

| Topic | Contract in this series | Considerations for further work |
| --- | --- | --- |
| Singletons | Identity belongs to a bean definition within its container | One class can have several definitions or containers; this does not imply one instance throughout the JVM |
| Thread safety | Container startup is single-threaded; definitions remain unchanged afterward | A concurrent map alone does not guarantee atomic creation, safe publication or thread-safe business objects |
| Circular dependencies | The main container rejects cycles; Chapter 4 separately demonstrates setter cycles | Constructor cycles, proxy identity and cleanup after failed creation need separate handling |
| Annotation injection | A unique type match is the default; explicit names select candidates | Multiple-candidate resolution and full annotation semantics require more detailed rules |
| Web entry point | Chapter 17 uses JDK HttpServer | Servlet integration, request-body parsing and general JSON handling are outside the implementation |
| Automatic proxying | Processor return values propagate; the final proxy is cached and injected | Early references and final proxies must agree; an advice log alone does not prove correct identity |
| AOP | JDK interface proxies compose an interceptor chain | Self-invocation bypasses the proxy; class proxies and bytecode weaving require other mechanisms |
| JDBC | The template owns common control flow; callers supply row mapping | Pools, multiple data sources and read/write routing need explicit ownership and concurrency rules |
| Transactions | A thread-bound connection commits on success, rolls back on failure and is cleaned up; nesting is rejected | Propagation, cross-thread calls and coordination across resources require separate designs |

## Verification and exercises

Check more than successful execution. Verify instance identity, initialization order, exception preservation, resource cleanup and actual database values. Chapter assertions and the final HTTP examples provide repeatable checks.

Both editions share executable code and expected output, with comments and explanations in their respective languages. See the [verification record](verification.md) for the checking procedure.

## Further reading

The following official Spring Framework documentation offers more detail on the corresponding mechanisms. Reading it is optional for running the tutorial code, and the simplified implementation does not provide Spring's complete feature set.

- [Dependency injection and circular dependencies](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html): resolution and the distinction between constructors and setters.
- [Bean scopes](https://docs.spring.io/spring-framework/reference/core/beans/factory-scopes.html): per-container singleton identity and prototype lifecycle.
- [Container extension points](https://docs.spring.io/spring-framework/reference/core/beans/factory-extension.html): definition processing, object processing and FactoryBean responsibilities.
- [Proxying mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html): interface proxies, class proxies and self-invocation.
- [MVC context hierarchy](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/context-hierarchy.html): root and child contexts.
- [DispatcherServlet](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet.html): the request entry point and registration.
- [JDBC connection management](https://docs.spring.io/spring-framework/reference/data-access/jdbc/connections.html): data sources and transaction-aware acquisition.
- [Transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html): propagation semantics outside this tutorial's scope.

Return to the [series contents](../README.md).
