# 维护与验证

网站由 Quarto 1.10.18 构建，经 GitHub Actions 发布到 GitHub Pages。根地址默认跳转到英文版 en/，中文首页为 zh.html。文章围绕图书预约业务组织，导航同时维护中英文。

## 内容与代码

- tutorial 与 en/tutorial 各包含导读、17 篇完整程序和两篇附录。
- 每个编号正文必须包含一个完整 Java 代码块、编译命令和预期输出。Demo01 至 Demo17 各自独立编译，不借用 examples 中的类。
- examples/library 是常规包结构的独立项目，含 13 个主源码文件和 1 个验收源码文件。
- 中英文注释可以不同，但可执行代码与输出保持一致。tools/verify_editions.py 检查词法一致性，不是任意程序的语义等价证明。
- 章节依赖 H2 的集合由 tools/verify_article_code.py 中 DATABASE_CHAPTERS 声明，目前为 05–11、13、15–17。修改章节结构时同步调整。

文章与分文件项目是两份可运行形态。tools/verify_project_alignment.py 对照项目源码检查正文中的共享组件，明确排除第 11、12 篇的早期 BeanBox 和第 16 篇不含启动入口的 LibraryApp 快照。修改共享机制时同步相关正文及项目；不要只更新一方。使用新文件名时同步 README、导读、下一篇和 _quarto.yml 的目录。不要保留指向已删除旧章节的导航。

## Google Style 与关键注释

使用 Maven Wrapper 3.9.9、Spotless 3.10.3 和 google-java-format 1.24.0，GOOGLE 风格。所需 Java 版本为 17。注释重点说明业务不变量、创建与增强顺序、连接和资源归属、错误传播，不逐行复述语句。

```bash
python3 tools/format_java.py apply
python3 tools/format_java.py check
```

脚本提取两种语言共 34 份程序到 build/spotless-articles，运行 Spotless，并在 apply 时只将格式化代码写回正文。项目的 examples/**/*.java 同样纳入检查，共 48 份 Java 文件。直接运行 mvnw spotless:apply 不会回写正文，因此维护与 CI 使用 Python 入口。禁止通配符导入。

## 行为验证

```bash
mkdir -p build/lib
curl -fL -o build/lib/h2-2.2.224.jar https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
python3 tools/verify_editions.py
python3 tools/verify_article_code.py
bash examples/library/run.sh test
python3 tools/verify_library_restart.py
```

文章校验在各自隔离目录中编译执行并比较输出。第 05 篇明确验证一个故意保留的自动提交反例；第 17 篇会启动临时 HTTP 服务并执行真实数据库和竞争检查。

项目验收单独运行，涵盖提交、回滚、重复、连接清理、竞争、HTTP、UTF-8 和生命周期。进程重启检查在临时目录创建文件型 H2，先后启动两个服务进程验证持久化；测试结束后仅清理自己启动的进程和临时目录。HTTP 检查需要允许监听本机端口。

## 网站

```bash
quarto preview
quarto render
```

中文首页引入 README.md，英文首页引入 en/README.md；filters/site-links.lua 将仓库 Markdown 导航映射为网页，并加载 Mermaid。示例项目文档链接指向 GitHub 文件，不作为课程网页渲染。

重命名章节后，本地 _site 中可能保留旧输出，应先清理这个生成目录，再完整构建。_site、.quarto、build 与 target 不提交。

main 更新后，工作流检查格式、34 份文章程序、独立项目和跨进程持久化，再构建发布网站。默认英文入口、中英文逐篇切换与站内链接都应随发布验证。

站点：[Spring from Scratch](https://codeideaai.github.io/spring-from-scratch/)。
