# Stage 0 评估决策与 Stage 1 环境执行约束（2026-10-20）

## 审核结论

基于环境盘点 PR #1（head `17e73ff`）、官方兼容性资料及用户提供的信息：**Stage 0 = ACCEPT**。仅涉及环境信息，未做业务实现。PR #1 **尚未合并时不得开始 Stage 1**；用户合并 PR #1 后，Codex 可以按已授权的 Stage 1 任务书完整开发，结束时提交一个 PR 供集中审查。

实际宿主机：Windows 10 Home 19045 x64；本机已存在 JDK 17（默认）及非默认 JDK 21.0.9；Maven 3.8.6；原生 MySQL 5.7.24；Redis 8.4.0 Cygwin；Docker CLI 29.7.2 / Compose 5.3.1，但 daemon 未连通；Node.js 24.15.0、npm 11.12.1、pnpm 11.21.0。

## 技术版本决策

| 组件 | 决策 | 理由与操作 |
|---|---|---|
| JDK | **REUSE 21.0.9**；编译 release=21 | 不需要安装；项目专用终端临时设置 JAVA_HOME 和 PATH；不要全局替换 Java 17 或影响旧项目。 |
| Maven | **REUSE 3.8.6**；提交 Maven Wrapper，优先 pin 3.8.6 | Spring Boot 4.1.1 官方要求 Maven >= 3.6.3。安装新 Maven 非必要；Wrapper 第一次使用可能下载对应发行版，属于依赖获取，不是系统升级。 |
| Spring Boot / MyBatis | **维持 Boot 4.1.1 + MyBatis Starter 4.1.0** | 官方对应兼容线；通过 Maven 下载项目依赖，无需手动安装框架。 |
| MySQL | **使用隔离的 mysql:8.4 容器** | 原生 MySQL 5.7.24 已过维护期，且本机 3306 已被占用。保留旧数据库原状，不升级、不停止、不复用其数据目录。 |
| Redis | Stage 3 优先容器化，**现在不操作** | 原生 Redis 8.4 可保留，不以 Cygwin 发行版作为最终项目可复现环境。 |
| RabbitMQ | Stage 3 容器化，**现在不安装** | 目前无使用需求。 |
| Node/npm/pnpm | **REUSE 24.x / 11.x / 11.x**，Stage 4 再验兼容 | 前端阶段才引入依赖，不需要提前更换版本。 |
| Docker | **保留已有 CLI/Compose，不盲目重装** | 先诊断 Desktop/WSL/Linux engine；Stage 1 的真 MySQL 集成测试需要可用 Docker API（本机或 CI）。 |

## 为 Codex 的 Stage 1 执行指令

### A. 项目专用的 Java 环境

Windows PowerShell 在**当前终端会话**中设置，避免影响其他 Java 项目：

```powershell
$env:JAVA_HOME = 'D:\JAVA21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
javac -version
mvn -version
```

若在 Codex 中运行命令，确保同一进程环境有效，尤其 `mvn -version` 必须显示 Java 21，而不是 17。无需修改系统环境变量、Windows 注册表或旧 Java 安装。提交明确的 POM `maven.compiler.release=21`、UTF-8 源码编码，防止本地 GBK 默认编码造成构建不一致。CI 使用 JDK 21。

### B. Docker 非破坏性诊断

首次开始 Stage 1 时检查：

- `docker context ls`；`docker info`；`docker compose version`。
- `wsl --version`、`wsl --status`、`wsl --list --verbose`（不必先安装/更新）。
- Docker Desktop 图形界面是否可启动、当前是否是 Linux containers / WSL2 后端。如 Docker Desktop 已安装但未运行，可在正常用户权限下尝试启动应用并重新执行 `docker info`。记录实际命令结果。
- **绝不能**擅自升级/重装 Docker Desktop、开启 Windows 功能、修改 BIOS/WSL 配置、执行 `wsl --install` / `wsl --update`、清理 Docker 数据/volume、修改系统级配置；如必须做这些，先将诊断和建议发给用户请求授权。
- 该电脑是 Windows 10 **Home** 19045；Docker 当前官方要求与操作系统支持期限可能限制新版 Docker Desktop 的维护支持。不能承诺本机必然可修复。官方文档：https://docs.docker.com/desktop/setup/install/windows-install/

若本机 Docker 仍不可用：**允许继续编写 Stage 1**，但必须通过 GitHub Actions Linux runner + Docker + Testcontainers 运行实际 MySQL 集成测试。不允许通过 skip、H2 或假测试代替；交接报告明确本机 Docker/测试为 UNVERIFIED。不得因此提前开发 Stage 2。若 CI 也不能运行真 MySQL 测试，Stage 1 暂不能视为验收通过。

### C. MySQL 8.4 隔离和端口

- `infra/docker-compose.dev.yml` 使用 mysql:8.4 的已确定镜像基线（可以在实现时固定明确 patch/digest）；容器端口 3306，宿主端口配置 `127.0.0.1:3307:3306`。
- 使用项目专属 named volume，例如 `eventflow_mysql_data`；切勿 bind mount 到既有 MySQL 5.7 数据目录或复用旧容器卷。
- 项目本地 JDBC 指向 127.0.0.1:3307；CI Testcontainers 启动独立的临时 MySQL 8.4 容器，并使用动态映射端口（不能硬编码 3307）；测试后自动销毁。
- 只操作本项目数据库、用户和 volume；不停止、不升级、不迁移本机现有 MySQL 5.7 服务。

### D. Stage 1 交接要求

- docs/handoffs/stage-1.md 须准确列出构建使用的 JDK、Maven、Spring Boot、MyBatis、MySQL、CI/本地测试结果；
- 说明本地 Docker daemon 诊断结果和最终方案；如 Docker 本地不可用，明确本机未验证事项和 CI 证据链接；
- 保持 Stage 1 范围严格不变：只做工程、JWT/RBAC、活动域、真实 MySQL 持久化与测试；不开发 Redis、MQ、Vue、订单；
- Stage 1 完成后以 `codex/stage-1-foundation` 分支开 PR，**不合并、不推进 Stage 2**。

## 官方参考

- Spring Boot 4.1.1 系统要求（Maven >=3.6.3 / Java >=17）：https://docs.spring.io/spring-boot/system-requirements.html
- MyBatis Spring-Boot-Starter 4.1 配套：https://github.com/mybatis/spring-boot-starter
- MySQL 5.7 最终版本通告：https://dev.mysql.com/doc/relnotes/mysql/5.7/en/news-5-7-44.html
- Docker daemon 诊断：https://docs.docker.com/engine/daemon/troubleshoot/
- Docker Desktop Windows 官方要求：https://docs.docker.com/desktop/setup/install/windows-install/
- Testcontainers Docker runtime 要求：https://java.testcontainers.org/supported_docker_environment/
