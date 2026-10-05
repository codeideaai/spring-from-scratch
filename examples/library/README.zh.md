# 图书预约项目

[English](README.md) · [中文教程](https://codeideaai.github.io/spring-from-scratch/zh.html)

项目围绕一条不变量展开：预约成功，库存减少一本并增加一条记录；预约失败，两者都不能改变。路由、构造器注入、代理、拦截链、JDBC 模板和事务边界均由本项目实现，H2 只提供数据库引擎。

## 运行

使用 JDK 17；文章验证另需 Python 3。在仓库根目录使用 Bash 执行：

```bash
mkdir -p build/lib
curl -fL -o build/lib/h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
bash examples/library/run.sh test
bash examples/library/run.sh serve
```

验收使用真实 H2、两个竞争线程和临时 HTTP 端口，完成后退出并打印：

```text
PASS library: commit, rollback, duplicates, restart, race, HTTP, lifecycle
```

serve 监听 `127.0.0.1:8080`，数据库位于 `build/library-data.mv.db`。首次创建时图书 101 有三本库存，重启不会重置已有数据。Ctrl+C 停止。另选端口和新数据库：

```bash
bash examples/library/run.sh serve 8090 jdbc:h2:./build/another-library
```

Windows 可使用 Git Bash 执行脚本。手动调用 Java 时，classpath 改用分号 `build/library-classes;build/lib/h2-2.2.224.jar`。

## 接口

接口返回 UTF-8 纯文本，POST 也使用 Query 参数，不解析 JSON 请求体。

| 方法与路径 | 参数 | 成功结果 |
| --- | --- | --- |
| GET /books | bookId | 200，新库正文为 `bookId=101;available=3` |
| POST /reservations | id、bookId、member | 201，例如 `reservation=demo-1;member=Lin` |

```bash
curl -i 'http://127.0.0.1:8080/books?bookId=101'
curl -i -X POST 'http://127.0.0.1:8080/reservations?id=demo-1&bookId=101&member=Lin'
curl -i -X POST 'http://127.0.0.1:8080/reservations?id=demo-2&bookId=101&member=Lin'
curl -i 'http://127.0.0.1:8080/books?bookId=101'
```

新库预期状态依次为 200、201、409、200，最终库存为 2。再次运行会受到已有预约影响。编号支持 1–40 个英文字母、数字和连字符；读者非空、长度不超过 40 个 Java 字符，首尾空白被移除。

无效输入为 400；不存在的路径或图书为 404；方法错误为带 Allow 的 405；重复编号、同一读者重复预约或库存不足为 409；意外操作失败为 500。库存为零时可能先报告库存不足，再谈重复判定，两者都属于冲突。

## 项目结构

源码位于 `src/main/java/io/github/codeideaai/library`，验收入口位于对应的 `src/test/java` 包目录。

| 组件 | 职责 |
| --- | --- |
| Domain、MemoryLibrary | 不可变业务值、输入校验与内存预约规则 |
| Transactions、Jdbc | 事务连接归属，语句、参数与结果集处理 |
| ReservationStore | 条件扣库存、预约唯一约束和数据库读取 |
| Reservations、ReservationService | 业务接口与实现 |
| Advisors | JDK 代理、每次调用独立的拦截链与事务增强 |
| BeanBox、BeanXml | 构造依赖图、单例、处理器、资源清理与可选 XML 配置 |
| Web、HttpGateway | 注解路由、参数绑定、响应转换与真实网络入口 |
| LibraryApp | 唯一应用装配入口 |
| AcceptanceTest | 数据库回滚、竞争、HTTP 和启动清理验收 |

默认运行使用 Java 配置；BeanXml 在第 15 篇作为另一种定义来源单独验证。正文每篇都完整列出其所需代码，不依赖项目文件。

## 边界

容器单线程配置，仅支持构造器单例，不支持循环依赖、原型、扫描或热更新。处理请求之前完成装配与冻结。instance 传入的对象由调用者拥有，容器创建的 AutoCloseable 对象由容器逆序关闭。

库存由数据库条件更新保护，重复预约由约束保护。两次 SQL 只有从事务代理进入时才属于同一事务；直接调用原始服务或仓储会绕过边界。拒绝嵌套事务，未实现传播、连接池和分布式事务。提交阶段连接故障可能使结果不明确，不能自动重试业务。

项目不提供认证、取消、过期、JSON、幂等重放或生产容量控制；只支持单个初始化流程。HTTP 只监听本机，不是 Servlet 容器。测试覆盖所声明的 H2/JDK 行为，不表示完整生产可用性。
