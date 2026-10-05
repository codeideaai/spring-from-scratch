# 阅读站点维护

本项目使用 Quarto 构建教程网站，并通过 GitHub Pages 发布。文章的完整 Java 代码直接保存在 Markdown 中，网站渲染不会执行或删除这些代码。

## 本地预览

安装 Quarto 1.10.18 后，在仓库根目录运行：

```bash
quarto preview
quarto render
```

中文首页从 README.md 引入内容，英文首页从 en/README.md 引入内容；tutorial 与 en/tutorial 下的文章渲染为 HTML。站点具有独立的中英文目录、语言切换、搜索、深色模式、页内目录和代码复制按钮。构建目录 _site 与缓存目录 .quarto 不提交到 Git。

修改章节后，同时检查 README 的目录与 _quarto.yml 的侧栏。正文必须保留完整代码、编译命令和预期输出。

## 中英文同步

英文版地址为 `https://codeideaai.github.io/spring-from-scratch/en/`。每篇正文顶部链接到另一种语言的对应章节，原有中文地址保持不变。`en/_metadata.yml` 设置英文界面文案和侧栏。

两种语言各包含导读、17 篇独立程序和两篇附录。修改实现时同步两篇文章；Java 注释可以按语言分别撰写，但可执行代码、字符串和预期输出应一致。新增章节时同步调整目录和 `tools/article_sources.py` 中的完整性校验。

可用 `python3 tools/verify_editions.py` 快速检查代码与输出一致性。它按 Java 词法单元比较，忽略注释及代码外部空白，保留字符串和文本块；不是对任意两份程序进行语义等价证明。完整编译验证也会先执行此检查。

## Java 格式与关键注释

文章内的 Java 代码与 `examples/*.java` 统一使用 Spotless 的 Google Java Style（两空格缩进），固定 Spotless 3.10.3 和 google-java-format 1.24.0，与 JDK 17 配合运行。Maven Wrapper 固定 Maven 3.9.9，不需要另行安装 Maven；首次运行会下载 Maven 和插件。

在仓库根目录执行：

```bash
python3 tools/format_java.py apply
python3 tools/format_java.py check
```

脚本先将每篇正文的完整 Java 代码块提取到 `build/spotless-articles`，再调用 `./mvnw spotless:apply` 或 `./mvnw spotless:check`。Windows 使用 `mvnw.cmd`。`apply` 会将格式化后的代码写回原文章，保留正文说明、编译命令和预期输出；`check` 不修改文章和示例。生成目录不是源码，请直接编辑文章中的代码。

直接执行 `./mvnw spotless:apply` 不会更新文章，因此日常维护和 CI 都应使用上面的 Python 入口。代码使用明确的类导入；Spotless 拒绝通配符导入并整理排版，但不代替代码行为验证。

注释重点解释创建顺序、状态变化、资源所有权、异常处理和教学实现的边界，不必逐行复述代码。重复出现在后续章节的核心实现也保留这些注释，使每篇都能独立阅读。代码修改后先执行 `apply`，再执行 `check` 和下方的行为验证。

## 验证文章内代码

准备 JDK 17、Python 3 和 H2 驱动后执行：

```bash
mkdir -p build/lib
curl -fL -o build/lib/h2-2.2.224.jar \
  https://repo.maven.apache.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar
python3 tools/verify_article_code.py
```

脚本检查两种语言各 17 篇文章，从正文提取完整 Java 程序，在按语言和篇号隔离的目录中编译、执行并比较预期输出，共 34 份程序，不使用 examples 目录中的早期实验作为代码依赖。

## 自动发布

目标仓库为 codeideaai/spring-from-scratch。GitHub 仓库 Settings → Pages → Build and deployment → Source 选择 GitHub Actions。

main 分支更新后，Publish tutorial website 工作流先检查 Java 格式并验证文章代码，再构建并发布阅读站点。也可以在 Actions 中手动触发。首次使用需要仓库已启用 Pages，并允许工作流部署 github-pages 环境。

站点地址：https://codeideaai.github.io/spring-from-scratch/

工具配置参考：[Quarto GitHub Pages 发布文档](https://quarto.org/docs/publishing/github-pages.html)。

网站根地址默认跳转到英文版 `en/`，中文首页为 `zh.qmd`（发布后 `zh.html`）。中文文章保留原有 URL；`tutorial/_metadata.yml` 保持中文界面，英文目录使用英文界面。
