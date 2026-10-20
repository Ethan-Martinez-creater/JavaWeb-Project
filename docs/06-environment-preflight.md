# Codex 执行任务书 — Stage 0 环境预检（当前任务）

## 目的

用户可能已安装与项目预设不同版本的 JDK、Maven、MySQL、Redis、RabbitMQ、Node、Docker 等。先**只读盘点**，等用户将结果发给 ChatGPT 后，再判断复用、调整版本、Docker 隔离或者确需新增安装。

**本轮绝不实现 Stage 1 代码，也不安装/更新/下载任何软件或镜像。** Stage 0 是一次性技术环境检查，不构成额外的阶段代码审查。

## 重要边界：机器身份

- 先说明你实际运行命令的环境：本机终端/用户本机、Codex 云端沙盒、远程开发容器、CI runner 等；记录 OS 版本、CPU 架构。
- 若你只能访问云端沙盒，明确说明结论**仅代表 Codex 沙盒**，不能推断用户 Windows/macOS/Linux 电脑的已安装软件。此时额外给用户附上一段可复制的**纯只读本地检测命令**（按用户已知平台选择；未知则给 Windows PowerShell 和 macOS/Linux 两套），但不要假装已在用户电脑执行。
- 若命令不存在、没有执行权限或 Docker daemon 不可用，按实际记录 UNKNOWN / NOT INSTALLED / NOT ACCESSIBLE，不要猜测。

## 检查清单（仅运行存在且安全的只读命令；Windows 使用 PowerShell 等价命令）

| 范围 | 建议检查 | 核心信息 |
|---|---|---|
| 基本环境 | 操作系统/CPU 架构、\`git --version\` | 在哪台机器、架构及 Git |
| JDK | \`java -version\`、\`javac -version\`、\`where java\` / \`which java\`，必要时 \`echo JAVA_HOME\`（路径可脱敏） | 可用 JDK 版本，不仅是 JRE；是否有多版本 |
| Maven | \`mvn -version\`（若已有安装） | Maven 版本、采用的 Java 版本；Maven Wrapper 尚未创建时不必运行 |
| Docker | \`docker --version\`、\`docker compose version\`、只读 daemon 状态/版本 | CLI/Compose 是否存在、Docker daemon 是否可用 |
| 数据库 | \`mysql --version\` / \`mariadb --version\`，若能安全读取服务端版本才记录 | 区分 MySQL **客户端**与实际 **服务端**版本，不能混同 |
| Redis | \`redis-server --version\`、\`redis-cli --version\` | 区分服务端与客户端；Docker 可替代 |
| RabbitMQ | \`rabbitmqctl version\`（仅当已有且可访问） | 工具/服务器实际版本及可访问性 |
| 前端 | \`node --version\`、\`npm --version\`、\`pnpm --version\`（若已安装） | Node 和包管理器，为 Stage 4 做准备 |
| Docker 本地镜像 | 仅当 daemon 可用时 \`docker image ls\`，按需筛选 mysql/redis/rabbitmq/node 等 | 已缓存镜像版本，仅供参考，不保证运行正常 |
| 本地端口（可选） | 安全只读检查 3306/6379/5672/8080/5173 监听状态 | 只写冲突与否，不记录无关进程的敏感信息 |

**特殊说明：** Spring Boot、MyBatis、Spring Security、Flyway、MySQL JDBC 等由 Maven 解析，不是必须单独安装的系统级软件；Vue / Element Plus 一般由前端包管理器解析。仓库中写的 Java 21 / Boot 4.1.1 / MyBatis Starter 4.1.0 / MySQL 8.4 是当前**项目构建基线**，不代表本机必须存在同版实例。JDK 需要能满足项目编译目标；Maven 可交给 Maven Wrapper；MySQL/Redis/RabbitMQ 优先考虑 Compose 隔离。

## 禁止事项

- 不安装、不下载、不升级、不自动执行 SDKMAN/winget/brew/apt/yum/Chocolatey，不拉镜像。
- 不运行可能自动下载依赖的 \`mvnw\`、\`npm install\`、\`npx\`、\`docker pull\`、\`docker compose up\`；不运行迁移或启动/停止服务。
- 不修改 PATH、JAVA_HOME、系统配置或已有数据库内容。
- 不输出或提交密码、token、数据库连接串、完整环境变量列表、SSH key、Docker 容器敏感配置或本机私人路径。OS 用户名/个人目录按需替换为 \`<redacted>\`。
- 不把实际未检查的组件标成已检查；明确每项是**检测到软件**还是**验证可连接**。
- 不从项目 POM 版本推断宿主机已经安装某组件。

## 交付格式

基于最新 main 新建 \`codex/environment-preflight\` 分支，只新增 \`docs/handoffs/environment-preflight.md\`，报告使用：

1. **检测环境**：实际机器类型、OS/arch、Codex 能否访问用户目标部署机器。
2. **版本矩阵**：组件、检测命令、检测结果（版本/不可访问）、目标基线、是否需要后续评估；客户端与服务端分别写。
3. **可复用性初判**：按 \`REUSE\` / \`CONTAINER\` / \`REVIEW\` / \`NOT CHECKED\` 给建议，**不得自行批准升级/改变基线**。
4. **客观限制与阻断项**：例如无法查看 Docker daemon、缺 JDK 编译器、缺 Maven 但可用 Wrapper、当前只观察到 Codex 云端。
5. **如需用户本地检查**：只读可复制命令，以及哪些项目无法确认。

创建一个仅文档变动的 PR \`[Preflight] Environment version inventory\`，目标 \`main\`。**不要合并 PR**。

给用户的回复只写 PR 链接、实际检测的机器、关键版本概览、无法确认的项目和需要用户操作的只读本地检查（如果适用）；然后停止，等待 ChatGPT 评估结果、决定下一步。不要开始 Stage 1。
