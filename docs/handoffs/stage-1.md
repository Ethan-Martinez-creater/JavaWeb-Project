# Stage 1 交接报告

## 元数据

- Stage：1；实现完成，等待集中审核，不代表阶段已获 ACCEPT。
- PR：[Stage 1 PR #2](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/2)，`codex/stage-1-foundation → main`，保持打开、不合并。
- Base SHA：`cbebe2db925462fe95468bbbfcf4b049c4cc19eb`。已用 GitHub API 再次确认 main；PR #1 已合并，merge SHA `970a51cd38c85ca96d5bfaa52937b6d414ecdc7d`。
- 已验证实现 SHA：本地 `b62c915`，GitHub 实现提交 `2340d6213f2612e4fbdfd609f33212bee271f3d0`；二者文件树同为 `0d278dd5641034e6dc93b1f28e83b667138868e9`。后续提交只补充本报告及 CI 证据，最终 Head 以 PR 为准。
- 原生 Git HTTPS 多次连接超时；通过已连接 GitHub API 发布，完整文件内容与 executable mode 由 Git tree SHA 核对一致。原本地逻辑提交仍保留，未 reset 或删除。
- 时间：以下均为 2026-10-20，Asia/Shanghai。

## 执行环境与复用

| 项目 | 实际使用/状态 |
|---|---|
| 执行位置 | Codex 桌面端终端，可访问用户本机 Windows 10 Home 19045 x64；不是远端 Linux 容器。CI 则是独立 GitHub Ubuntu runner |
| Java | 现有 `D:\JAVA21` JDK 21.0.9，编译 release 21；只设置子进程环境。验证后默认 `java -version` 仍为 17.0.2 |
| Maven | 已有 Maven 3.8.6；提交 Wrapper 3.3.4，固定 Maven 3.8.6 发行版与 SHA-256。Windows 要求 pwsh 7+；本机 7.6.5 |
| 框架/测试 | Spring Boot 4.1.1、MyBatis Starter 4.1.0、Spring Security 7.1.1、Testcontainers 1.21.4；JUnit Jupiter/Platform 实际为 Boot 管理的 6.0.3，保留 Jupiter 编程模型，差异见 ADR |
| Docker | CLI/daemon 29.7.2，Compose 5.3.1，Linux engine；仅正常启动已安装 Docker Desktop，未升级、重装或修改 OS/WSL/daemon 配置 |
| MySQL | 真实 MySQL 8.4.11；Compose 与 Testcontainers 固定同一 manifest digest；原 MySQL 服务仍 Running、3306 保留 |
| 缓存与凭据 | Wrapper、Maven repo、JVM home/temp、日志与未提交的随机开发凭据均在 `D:\JavaWeb_Project\.build-cache`；没有提交真实密码/JWT 密钥 |

Docker 初始报 `dockerDesktopLinuxEngine` pipe 不存在；只读检查 context、WSL 版本/状态及已有应用后，按 docs/07 正常启动现有 Docker Desktop，`docker info` 随后返回 29.7.2 / linux。会话中断后应用停止过，恢复工作时再次正常启动。未执行 WSL 安装/更新、启用 Windows 功能、系统环境变量修改或数据清理。

开发库独立项目名 `eventflow-stage1`，映射 `127.0.0.1:3307:3306`，新建项目专属 `eventflow_mysql_data` named volume；没有挂载、迁移或写入旧 MySQL 5.7 数据目录。测试另开动态端口容器，不依赖开发库。Redis、RabbitMQ、Node 均未用于本阶段。

## 功能验收矩阵

| 要求 | 结果 | 主要实现 | 实测证据 |
|---|---|---|---|
| 工程、配置、Wrapper、可执行 JAR | PASS | `backend/pom.xml`、`mvnw*`、`application*.yml`、`.env.example` | 本机 verify 与干净检出 verify 均 BUILD SUCCESS；JAR 独立启动 |
| 空数据库 Flyway、五表、FK/唯一键/索引/CHECK | PASS | `backend/src/main/resources/db/migration/V1__identity_and_event_catalog.sql` | 真实 MySQL 从 Empty Schema 迁移到 v1；测试检查五表、FK、唯一索引并执行 Mapper |
| 注册/登录/me、密码哈希、公开注册只给 USER | PASS | `identity/application/AuthService.java`、`identity/web` | 合法/无效/重复邮箱、归一化、哈希、伪造 role/userId、统一登录失败及 Unicode/BCrypt 字节边界 |
| JWT/RBAC、所有权、统一错误/requestId | PASS | `common/security`、`common/error`、`common/web`、`event/application/EventService.java` | 真实 HTTP 安全链验证错误签名/过期/畸形 claims、匿名 401、USER 403、跨所有者 404、ADMIN 边界 |
| 主办方草稿列表/创建/编辑、场次、票种 | PASS | `event/web`、`EventService`、`mapper/event/EventMapper.xml` | owner/status 注入拒绝；时间/offset/负价格库存/重复票种等边界；初始库存一致 |
| 发布与公开目录 | PASS | `EventService.publish`、父记录 FOR UPDATE 和条件 UPDATE | 无场次/票种/未来场次拒绝；已发布不可改；并发同草稿只能一次成功；公开不泄露草稿 |
| SQL 搜索/分页/count | PASS | `EventMapper.xml` | 实际数据库 COUNT/LIMIT/OFFSET；关键词字面转义、分页上限、草稿过滤、count/空页/注入样例 |
| dev 主办方初始化 | PASS | `DevOrganizerInitializer`、`AuthService.ensureDevOrganizer` | 默认 profile 无 seed；相同 seed 幂等；已有 USER/冲突口令/禁用账户拒绝；打包 dev 启动并登录通过 |
| 格式与真实 MySQL 测试 | PASS | `Stage1IntegrationTest.java`、Spotless | 两轮 13 tests，0 failures、0 errors、0 skipped；无 H2、disabled/assume/跳过容器回退 |
| Linux CI | PASS | `.github/workflows/backend-ci.yml` | [实现提交首次运行](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39427007642)：13 tests，0/0/0；格式、打包和 Surefire 上传均成功 |

## 实际测试结果

| 命令/操作 | 时间 | 结果 | 关键输出 |
|---|---|---|---|
| 本地 `mvnw.cmd -gs <已有 Maven conf/settings.xml> -B spotless:apply verify` | 20:53:27 完成，3:05 | PASS | 13 tests，0/0/0，BUILD SUCCESS，生成 Spring Boot 可执行 JAR |
| 全新 worktree + 首次 Wrapper 缓存 `mvnw.cmd -gs <同一只读设置> -B spotless:check verify` | 20:59:16 完成，3:02 | PASS | 27 Java 文件格式通过；26 主源码、1 测试源码从头编译；13 tests，0/0/0，BUILD SUCCESS |
| `docker compose --project-name eventflow-stage1 --env-file .build-cache/dev.env -f infra/docker-compose.dev.yml up -d --wait --wait-timeout 240` | 本轮恢复后 | PASS | `eventflow-stage1-mysql-1 Healthy`，仅 3307 映射 |
| JDK 21 `java -jar ... --spring.profiles.active=dev --server.port=18080` | 20:56 起 | PASS | Flyway 开发空库 v1；health UP；HTTP 登录/me、创建草稿/场次/票种、发布、公共详情及 availableStock=totalStock 通过 |
| `git diff --check` | 提交前 | PASS | 无空白错误；远端 tree 与本机已验证 tree 完全一致 |
| GitHub Linux `./mvnw --batch-mode spotless:check verify` | 21:03:47 完成，1:27 | PASS | [run 39427007642](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39427007642)：13 tests，0/0/0，29.60 s；真实 MySQL 动态端口 32769，BUILD SUCCESS |

本机命令显式 `-gs` 只读取现有 Maven 镜像配置，未改全局设置；项目不提交该机器路径或镜像覆盖，CI 使用 Central。干净检出位于 `.worktrees/verify-stage1`，当时没有 target，Wrapper 还使用新的 `.build-cache/wrapper-fresh` 下载缓存验证发行版校验与移动路径保护。构建日志保留在忽略目录 `wrapper-verify.log`、`clean-checkout-verify.log`；Surefire 摘要：第二轮 `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 122.2 s`。

收尾已停止本次记录 PID 的冒烟应用及 `eventflow-stage1-mysql-1`，保留项目 volume；只读复核原 MySQL 服务仍为 Running、3306 仍监听。未批量删除文件/目录、清理镜像或 volume。PR 文档提交会触发新的 CI，最终状态可从 PR Checks 查看；以上链接固定对应已验证的实现文件树。

开发迭代中曾出现过真实失败：Testcontainers tag+digest 兼容解析失败、旧 redo 配置导致 MySQL 初始化超时、UserMapper 缺少扫描标记、Windows Wrapper 的 pwsh 参数/空 Target 兼容错误。均已修复后重跑以上完整测试；未将失败轮次或中断轮次计作通过。Testcontainers 1.x 的 10 MiB redo 改为测试容器内的 128 MiB，一次性数据目录使用 512 MiB tmpfs，实际 MySQL 引擎和约束均保留。

### 复现命令

先按根 README 设置项目专用 Java 21、D 盘 Maven/JVM/TEMP/TMP 缓存，并确保 Docker Linux engine 可连接。仅首次 Wrapper/依赖获取需要网络；不需要另装 JDK/Maven。

```powershell
Set-Location D:\JavaWeb_Project\backend
.\mvnw.cmd -B spotless:check verify
```

本机若需要已有镜像设置，可加 `-gs 'E:\No.3 [NTFS 新加卷]\ 根目录\Maven\apache-maven-3.8.6\conf\settings.xml'`。测试不需要开发 `.env`，JWT 密钥随机生成；测试凭据仅属于自动销毁的一次性数据库。启动开发应用的配置和 HTTP PowerShell 示例见根 README。

## 设计取舍、风险与范围

- 决策：[ADR 0001](../decisions/0001-stage-1-security-and-persistence.md)。MyBatis XML、UTC DATETIME(6)、父行锁、条件发布、JWT、dev seed 与版本兼容均已说明。
- P0/P1：当前测试和只读复核未发现；仍待独立阶段审核，不能据此声明绝对不存在问题。
- P2/限制：JWT 在有效期内不即时撤销，账户角色/状态改变不会使已签发令牌立刻失效；需后续独立设计撤销策略。详情按场次加载票种，关键词为字面子串，未声明性能指标或全文索引能力。
- 兼容偏差：任务书提到 JUnit 5，实际保留 Boot 4.1.1 BOM 管理的 Jupiter 6.0.3，未强制降级平台；Testcontainers 固定 1.21.4 以适配现有 Docker API，未修改 daemon 配置。Spring Security encoder 使用真实 7.1 API；无 Boot/MyBatis 技术栈替换。
- 基础设施限制：Windows 10 Home/Docker Desktop 长期维护兼容性未在本次解决；没有升级 OS、WSL、Docker 或其他软件。Linux 以 CI 实测为准。
- 凭据：开发库随机凭据仅在忽略文件，`.env.example` 无可用固定密码/密钥。日志不输出登录 token、密码或 SQL 参数；部署应通过自己的环境/secret 管理注入。
- 范围外：订单、扣减/预占库存、支付、核销、Redis、RabbitMQ、Vue、性能压测；本 PR 无上述业务实现。

## 审核定位与下一阶段依赖

- 推荐先看 `common/security/SecurityConfiguration`、`identity/application/AuthService`、`event/application/EventService`、两个 MyBatis XML、Flyway V1 和 `Stage1IntegrationTest`。
- 关键重现：`everyWriteToPublishedEventIsRejectedAndConcurrentPublishOnlySucceedsOnce`、`onlyOwnerOrAdminCanManageEventsAndAdminListingIsStillOwnerScoped`、`publicSearchUsesDatabasePaginationAndCountAndNeverIncludesDrafts`、`devOrganizerSeedIsAbsentByDefaultIdempotentAndRefusesExistingConflictingAccounts`。
- 已提供接口：docs/02 全部 S1 路由；稳定用户/活动/场次/SKU 标识，DTO 与库存初值，统一错误契约；表结构由 V1 管理。
- 后续技术债须另经授权：令牌撤销、数据量上升后的详情/搜索优化、后续库存状态迁移。不得在未审核情况下提前实现。
- 明确声明：本 PR 未合并，尚未开始 Stage 2；等待阶段审核。
