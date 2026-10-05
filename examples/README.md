# 一步步手写 Spring 实验运行说明

此目录保留早期配套实验。当前教程正文已提供独立的 Demo01 至 Demo17 完整代码，阅读和运行正文不依赖此目录；正文的最新行为与验证结果以各篇文章为准。

这些实验配合教程使用，基线为 JDK 17。所有类都在默认包下，便于直接使用 javac 编译；这些类用于演示框架机制，不属于 Spring Framework。

## 无第三方依赖的实验

在项目根目录执行：

```bash
bash examples/run.sh
```

脚本编译全部 Java 文件并运行五组实验，输出位于 `build/classes`。断言不依赖 `-ea`，失败会直接抛出 AssertionError 并使脚本退出。

| 文件 | 已实现及验证内容 |
| --- | --- |
| Container.java | 显式定义、构造器、Setter、自定义字段注入、生命周期、父级查找、处理器 |
| IocLab.java | 最小 XML、单例身份、原型、注入、初始化、销毁、事件、父容器、拒绝循环 |
| CycleLab.java | 单独的 Setter 循环实验、早期引用身份、失败缓存清理 |
| AopLab.java | JDK 代理、切点谓词、拦截链、自动代理、自调用、异常解包 |
| MvcLab.java | 路由、参数绑定、视图、状态码、IoC 与 AOP 整合、可选 HTTP 服务 |
| JdbcLab.java | JDBC 模板、占位符编译、本地事务；默认以测试替身验证事务协议 |
| FullStackLab.java | 需要 H2 的完整数据库集成，默认脚本只编译、不运行 |

## 真实数据库验证

H2 固定为 2.2.224，作为可重复实验依赖，不要求它是最新版。只从 Maven Central 获取对应 JAR，不使用 Spring 依赖。

macOS 或 Linux 命令：

```bash
mkdir -p build/lib
curl -fL --connect-timeout 10 --max-time 60 \
  -o build/lib/h2-2.2.224.jar \
  https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
bash examples/run.sh
java -cp 'build/classes:build/lib/h2-2.2.224.jar' JdbcLab h2
java -cp 'build/classes:build/lib/h2-2.2.224.jar' FullStackLab
```

Windows 的 classpath 分隔符改为分号，并通过对应终端下载 JAR、执行 javac。实验仅访问当前 Java 进程里的 H2 内存数据库，不需要创建外部数据库账号。JVM 退出后数据消失。

`JdbcLab h2` 检查真实 SQL、查询映射、提交及回滚；`FullStackLab` 使用内存 Request 调用 Dispatcher，经过容器注入的事务代理执行数据库操作，验证失败更新没有生效。

## HTTP 交互实验

编译完成后运行：

```bash
java -cp build/classes MvcLab serve
```

在另一个终端执行：

```bash
curl -i 'http://127.0.0.1:8080/users?id=7'
curl -i 'http://127.0.0.1:8080/users?id=abc'
curl -i 'http://127.0.0.1:8080/users'
curl -i -X POST 'http://127.0.0.1:8080/users?id=7'
curl -i 'http://127.0.0.1:8080/missing'
curl -i 'http://127.0.0.1:8080/page?name=%3Cscript%3E'
curl -i 'http://127.0.0.1:8080/fail'
```

预期状态依次为 200、400、400、405、404、200、500。第六项页面内容应被转义。服务只监听本机 127.0.0.1，使用 Ctrl+C 停止。这个交互版本使用演示 Service 返回 `user-7`，数据库整合由 FullStackLab 单独验证。

## 已知边界

- Container 仅约定单线程启动，不支持并发 getBean 创建；不要在请求期间修改定义。首次 getBean 后冻结注册，不支持二次 refresh。
- 主容器拒绝全部依赖环。CycleLab 只演示无代理、单线程的 Setter 循环；二者未合并。
- XML 仅支持 id 和 class；构造器、属性、作用域等由 Java Definition API 配置。
- 字段注入支持显式名称或唯一类型；不支持 Qualifier、Primary、泛型集合注入或扫描组件。
- 初始化失败的对象需自行清理已经取得的资源；容器只销毁已成功登记的单例。原型由调用者管理。
- MVC 使用 JDK HttpServer，不是 Servlet 或 Tomcat 部署项目。仅解析 Query 标量参数，不含请求体 JSON、多值参数、通用 JSON 输出或 JSP。
- AOP 只收集目标类直接声明的接口，Invocation 是一次调用的状态；实验 trace 列表不是并发日志实现。
- SQL 编译器有意拒绝引号、字面量及注释；没有 Mapper XML、SqlSession、连接池或自动对象映射。
- 事务仅覆盖一个线程、一种连接来源的一层本地事务；嵌套调用拒绝。没有事务传播、隔离级别配置或异步传递。

默认脚本通过只证明无外部依赖的五组检查通过；H2 和 HTTP 应按各自命令单独验证。此次实际验证结果见[验证记录](../tutorial/验证记录.md)。
