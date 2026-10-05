# Spring from Scratch

[Read online](https://codeideaai.github.io/spring-from-scratch/en/) · [GitHub repository](https://github.com/codeideaai/spring-from-scratch) · [中文](../README.md)

Build the core of a framework yourself, from IoC to MVC, AOP and transactions.

A tutorial series for readers with Java fundamentals. Starting with object creation, we implement dependency injection, lifecycle management, MVC dispatch, JDBC templates, dynamic proxies and transactions, then verify the complete call chain against a real database.

The series follows four threads—IoC, MVC, JDBC and AOP—with failure cases, implementation boundaries and integration exercises at each stage.

## Start reading

Follow the chapters in order. Each technical chapter prints its complete Java program, business example, main method, compilation commands and expected output. No companion source file is needed. The final chapter integrates real HTTP requests and a database.

| Chapter | Topic |
| --- | --- |
| 00 | [Series Guide and Learning Path](tutorial/00-guide.md) |
| 01 | [Build Your First Bean Container](tutorial/01-first-bean-container.md) |
| 02 | [Turn Configuration into Bean Definitions](tutorial/02-configuration-to-bean-definitions.md) |
| 03 | [Implement Constructor and Property Injection](tutorial/03-constructor-and-property-injection.md) |
| 04 | [Understand Circular Dependencies and Early References](tutorial/04-circular-dependencies.md) |
| 05 | [Manage the Bean Lifecycle and Extension Points](tutorial/05-bean-lifecycle.md) |
| 06 | [Add Annotation-Driven Dependency Injection](tutorial/06-annotation-driven-injection.md) |
| 07 | [Organize the Application Context and Events](tutorial/07-application-context-and-events.md) |
| 08 | [Implement MVC Request Dispatch](tutorial/08-mvc-dispatch.md) |
| 09 | [Bind Request Parameters to Method Arguments](tutorial/09-request-parameter-binding.md) |
| 10 | [Handle Return Values and Render Views](tutorial/10-return-values-and-views.md) |
| 11 | [Encapsulate JDBC with a Template](tutorial/11-jdbc-template.md) |
| 12 | [Turn SQL into Managed Metadata](tutorial/12-sql-metadata.md) |
| 13 | [Enhance Business Methods with Dynamic Proxies](tutorial/13-dynamic-proxies.md) |
| 14 | [Build Interceptor Chains and Pointcuts](tutorial/14-interceptor-chains.md) |
| 15 | [Integrate Automatic Proxying with IoC](tutorial/15-automatic-proxying.md) |
| 16 | [Connect AOP and JDBC with Transactions](tutorial/16-transactions.md) |
| 17 | [Build and Test the Complete Application](tutorial/17-end-to-end-application.md) |

## Copy the code and run it

Open Chapter 1, save the “Complete code” block as `Demo01.java`, and run with JDK 17:

```bash
javac --release 17 Demo01.java
java Demo01
```

Chapters 01–17 use Demo01 through Demo17. Every program is independent and printed in full. Chapters 11, 12, 16 and 17 require the H2 database driver and include its download and execution commands.

## Verification and implementation boundaries

Both editions are checked by extracting each article's code, compiling it in isolation, running it and comparing its output with the article. No companion tutorial source is used. See the [verification record](tutorial/verification.md).

[Implementation boundaries and further reading](tutorial/implementation-notes.md) summarize module contracts, extension topics and official documentation.

## Scope

The main path covers IoC, dependency injection, lifecycle management, MVC, JDBC, Mapper XML, dynamic proxies, automatic proxying and local transactions. The last chapter contains a complete database application with an HTTP server.

Container startup is single-threaded. Setter cycles are demonstrated separately. The web entry point uses JDK HttpServer, and SQL mapping and transactions follow the explicit simplified contracts in the articles.
