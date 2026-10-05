# 一步步手写 Spring

[English](en/README.md) · [在线阅读](https://codeideaai.github.io/spring-from-scratch/zh.html) · [GitHub 仓库](https://github.com/codeideaai/spring-from-scratch)

从 IoC 到 MVC、AOP 与事务，亲手实现框架核心。

一套面向有 Java 基础读者的中文系列教程。从对象如何创建开始，逐步实现依赖注入、生命周期、MVC 请求分发、JDBC 模板、动态代理与事务，最终用真实数据库验证完整调用链。

本系列围绕 IoC、MVC、JDBC、AOP 四条主线组织讲解与代码，结合错误路径、实现边界和综合实验，逐步搭建一个可运行的教学框架。

## 开始阅读

按下表顺序阅读。每篇正文都直接给出完整 Java 代码、业务示例、main 入口、编译命令和预期输出，无需引用其他源码文件；最后一篇提供实际 HTTP 与数据库整合。

| 篇号 | 内容 |
| --- | --- |
| 00 | [系列导读与学习路线](tutorial/00-导读与学习路线.md) |
| 01 | [实现第一个 Bean 容器](tutorial/01-实现第一个Bean容器.md) |
| 02 | [把配置转换成 Bean 定义](tutorial/02-把配置转换成Bean定义.md) |
| 03 | [实现构造器和属性注入](tutorial/03-实现构造器和属性注入.md) |
| 04 | [理解循环依赖与早期引用](tutorial/04-理解循环依赖与早期引用.md) |
| 05 | [管理 Bean 生命周期与扩展点](tutorial/05-管理Bean生命周期与扩展点.md) |
| 06 | [让注解驱动依赖注入](tutorial/06-让注解驱动依赖注入.md) |
| 07 | [组织应用上下文与事件](tutorial/07-组织应用上下文与事件.md) |
| 08 | [从请求入口实现 MVC 分发](tutorial/08-从请求入口实现MVC分发.md) |
| 09 | [把请求参数绑定到方法参数](tutorial/09-把请求参数绑定到方法参数.md) |
| 10 | [处理返回值与渲染视图](tutorial/10-处理返回值与渲染视图.md) |
| 11 | [用模板封装 JDBC 访问](tutorial/11-用模板封装JDBC访问.md) |
| 12 | [把 SQL 变成可管理的元数据](tutorial/12-把SQL变成可管理的元数据.md) |
| 13 | [用动态代理增强业务方法](tutorial/13-用动态代理增强业务方法.md) |
| 14 | [构造拦截链与切点](tutorial/14-构造拦截链与切点.md) |
| 15 | [把自动代理接入 IoC](tutorial/15-把自动代理接入IoC.md) |
| 16 | [用事务串联 AOP 与 JDBC](tutorial/16-用事务串联AOP与JDBC.md) |
| 17 | [综合实战与自测](tutorial/17-综合实战与自测.md) |

## 直接复制文章中的代码运行

打开第一篇，将“完整代码”中的内容保存为 `Demo01.java`，使用 JDK 17 执行：

```bash
javac --release 17 Demo01.java
java Demo01
```

第 01 至 17 篇分别对应 Demo01 至 Demo17。每篇都是独立程序，全部代码已经在文章里列出。第 11、12、16、17 篇需要 H2 数据库驱动，下载和运行命令也写在各篇中。

## 验证与阅读说明

已从文章中直接提取 17 份程序，逐篇独立编译运行，并比对文中的预期输出。验证没有引用其他教程源码文件。详细范围见[验证记录](tutorial/验证记录.md)。

[实现边界与延伸阅读](tutorial/实现边界与延伸阅读.md)汇总各模块的实现约定、扩展方向和官方文档。

## 实现范围

主线涵盖 IoC、依赖注入、生命周期、MVC、JDBC、Mapper XML、动态代理、自动代理和本地事务。最后一篇把完整代码汇总为一个可启动 HTTP 服务的数据库应用。

教学容器限定单线程启动，Setter 循环使用独立实验说明。Web 入口使用 JDK HttpServer，SQL 映射和事务均采用文中明确的简化协议。
