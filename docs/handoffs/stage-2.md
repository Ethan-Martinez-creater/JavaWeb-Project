# Stage 2 交接报告

## 元数据

- Stage：2，完成实现后交付集中审核；不自行声明阶段 ACCEPT。
- PR URL：[Stage 2 PR #3](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/3)；分支 `codex/stage-2-order-lifecycle → main`，保持打开、不合并。
- Base SHA：`a0f7db063327371acdb9cda3c42b904ac56fa034`。PR #2 已审核合并，merge SHA `0aa4a4d73995efd537c010cc2edd6a798fd3f0a4`；开发前已正常 fetch、快进 main 并阅读最新授权。
- 已验证实现基准：`13c12c7f3f7161cf5a766ad0278de26fda2d821b`。后续交接/CI 证据更新以 PR Head 为准，避免文档自引用 SHA。
- 时间：2026-10-20，Asia/Shanghai；原始 JSON 的 timestampUtc 使用 UTC。
- 执行位置：Codex 桌面端可访问的用户 Windows 10 Home 19045 x64；GitHub Actions 为独立 Ubuntu Linux 环境，二者分开记录。

## 环境与范围

沿用现有 JDK 21.0.9（`D:\JAVA21`）、Maven 3.8.6 / 已提交 Wrapper、Boot 4.1.1、MyBatis Starter 4.1.0、Testcontainers 1.21.4、MySQL 8.4.11。JUnit Jupiter/Platform 继续采用 Stage 1 已披露的 Boot BOM 管理版本 6.0.3，未更换依赖基线。

本机 Docker daemon 起初未连接，仅按既有授权正常启动已安装 Desktop，最终 `docker info` 返回 29.7.2 / linux；未安装升级软件、变更 OS/WSL/daemon 配置。全部构建 JVM home、temp、Maven repo、Wrapper 缓存与日志位于项目 D 盘 `.build-cache`，仅在子进程选 Java 21。收尾检查默认 Java 仍为 17.0.2，原 MySQL 服务仍运行；没有连接、升级、停止或迁移旧 3306 数据库。

开发冒烟复用项目专属 3307 容器和 `eventflow_mysql_data` 卷，实际从已有 V1 迁移到 V2。自动测试另外使用固定 digest 的 MySQL 8.4.11、动态端口和一次性数据目录；Stage 2 测试数据放在 1 GiB tmpfs，运行的仍是真实 MySQL 引擎。未引入 Redis、RabbitMQ、Vue、真实支付网关或下一阶段业务。

## 功能验收矩阵

| 要求 | 结果 | 关键实现文件 | 证据 |
|---|---|---|---|
| 单 SKU/单票预占与价格快照 | PASS | `order/application/OrderService.java`、`event/application/InventoryService.java`、两者 Mapper/XML | HTTP 预占、停售/草稿/开场/无库存拒绝，数据库库存边界与价格快照测试 |
| 并发幂等、回滚重试 | PASS | `OrderService.create`、`OrderMapper.xml`、V2 唯一索引 | 同用户/同 key 并发只建一单；异参 409；键区分大小写/用户；事务主动回滚后同 key 成功；CLOSED 键重放不扣库存 |
| 模拟支付与唯一出票 | PASS | `OrderService.pay`、`TicketService.issue` | 本人未过期订单才支付；重复支付保持同订单与唯一 ticket；同事务出票，item 唯一约束 |
| 取消、到期补偿与终态竞争 | PASS | `OrderService.cancel/closeExpired`、`OrderExpiryScanner`、`OrderExpirySchedule` | pay/cancel、cancel/scanner、过期 pay/scanner 并发；恰好一次释放；到期边界和重复扫描；无票/订单/库存关系断言 |
| 订单/票据授权与 SQL 分页 | PASS | `OrderController`、`MyTicketController`、`SecurityConfiguration`、XML | USER/ADMIN 可下单；纯 ORGANIZER 403；匿名 401；本人之外统一 404，ADMIN 不绕过个人所有权；COUNT/LIMIT/OFFSET 与精确倒序 |
| 高熵码轮换与核销安全 | PASS | `TicketService`、`TicketMapper.xml`、`OrganizerTicketController` | 256 bit canonical Base64URL，数据库仅 hash；旧码失效；跨组织者/错误码/未付/已用/窗口外拒绝；并发同票仅一次成功和一条 SUCCESS |
| Flyway V1→V2 与 MySQL 约束 | PASS | `V2__orders_and_tickets.sql` | 空库两次迁移；独立 MySQL 中先应用 V1、保留旧目录数据后升级 V2；FK/unique/index/CHECK 真实 SQL 验证；V1 文件未修改 |
| Stage 1 回归与完整套件 | PASS | `Stage1IntegrationTest`、`Stage2IntegrationTest` | 原 13 项保持通过，新增 17 项；完整 30 项，0 失败/错误/跳过 |
| 1000 不同用户并发库存验证 | PASS | `Stage2IntegrationTest.thousandDistinctUsersAndKeysOverRealHttpNeverOversellAndWriteSanitizedArtifact` | 48 线程，1000 请求全部完成；100×201、900×SOLD_OUT 409、余量 0、订单/明细各 100 |
| 干净检出 verify | PASS | 已提交 Wrapper 与 `.worktrees/verify-stage2` | 无既有 target，重新编译/格式检查/全部 30 项测试通过，1000 请求不变量再次全部满足 |
| Linux CI 与证据上传 | PASS | `.github/workflows/backend-ci.yml` | [run 39441979436](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39441979436)：30/0/0/0；Surefire 和并发 JSON 均已上传，已下载核对 JSON |

## 实际执行结果

| 命令/操作 | 本机时间 | 结果 | 关键输出 |
|---|---|---|---|
| Wrapper `spotless:apply compile` | 22:17:04 完成 | PASS | 46 主源码、Java release 21；BUILD SUCCESS |
| Wrapper `spotless:check verify -Dtest=Stage1IntegrationTest` | 22:20:07 完成，1:18 | PASS | V2 下原 13 tests，0 failures/errors/skipped；打包通过 |
| 专属开发库 Compose 启动 + JAR dev profile | 22:27–22:29 | PASS | 容器 Healthy；Flyway 当前版本 1 → 成功应用 V2，旧项目数据保留；health UP |
| 打包 JAR 的真实 HTTP 订单流程 | 22:29 | PASS | 新买家注册/登录；预占/重放同单；取消/重复取消；新单支付/重复支付同票；本人票列表和轮换码 |
| Wrapper `spotless:apply verify` | 22:37:56 完成，2:18 | PASS | 30 tests，Failures 0、Errors 0、Skipped 0；BUILD SUCCESS；Stage 2 子套件 17 项、67.74 s |
| 全新检出 Wrapper `spotless:check verify` | 22:41:02 完成，2:23 | PASS | 30/0/0/0；Stage 2 子套件 17 项、64.12 s；BUILD SUCCESS；并发仍为 100/900/余量 0 |
| Linux CI `./mvnw --batch-mode spotless:check verify` | 22:45:46 完成，1:10 | PASS | [run 39441979436](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39441979436)，实现/报告提交 309dcb5；30/0/0/0，Stage 2 17 项、22.02 s；BUILD SUCCESS |
| `git diff --check` 与 V1 差异检查 | 提交前 | PASS | 无空白错误；已发布 V1 无改动 |

开发过程中曾出现测试夹具类型编译错误，以及首次完整运行的两项测试预期错误（倒序分页错误排除最新订单、误把预占后库存当成初始库存）；还修正了布尔关闭结果断言。修复后执行完整套件得到上述 30/0/0/0。失败或中断轮次没有计为通过，未修改业务不变量来迎合测试。

### 1000 请求原始证据摘要

本机通过轮次 JSON 时间为 `2026-10-20T14:37:12.269245Z`，文件为 `backend/target/stage-2-concurrency-result.json`（忽略的构建产物，CI 上传同名 artifact）。

干净检出再次得到相同计数，JSON 时间 `2026-10-20T14:39:55.944520200Z`。Linux CI 实测时间为 `2026-10-20T14:45:36.581532330Z`，同样 1000 完成、100 成功、900 SOLD_OUT、余量 0、100 个待付款订单和明细。已从 run 39441979436 下载 artifact `stage-2-concurrency-result`（ID 12274061897）核对；其原始脱敏 JSON 副本保留为 [stage-2-concurrency-ci.json](stage-2-concurrency-ci.json)，避免仅依赖构建日志保留期。原始来源 Head 为 `309dcb5436b4c51b08db9b110bc22c601c885a3e`，后续证据提交不修改实现代码。

| 指标 | 本机实测 |
|---|---:|
| 真实数据库 ACTIVE USER / 不同 key / HTTP 请求 | 1000 / 1000 / 1000 |
| 客户端线程池 / 初始同步 barrier | 48 / 48 |
| 完成请求 | 1000 |
| HTTP 201 新订单 | 100 |
| HTTP 409 | 900，全部为 SOLD_OUT |
| 初始库存 / 最终库存 | 100 / 0 |
| PENDING_PAYMENT 订单 / order_items / quantity 合计 | 100 / 100 / 100 |
| 成功订单不同用户数 | 100 |
| 越界库存行 | 0 |

fixture 为 SQL 插入的 1000 个不同用户及 USER 角色，每个用户分别签发有效 HS256 JWT 后发送真实 HTTP 请求，经完整 security filter；没有用一个用户重复 key 冒充 1000 用户，也没有绕过生产鉴权。新注册/登录与密码测试保留在 Stage 1 回归中。所有 future 必须完成；网络异常直接失败，不转换成“库存不足”。并发 JSON 不含邮箱、密码、JWT 或核销码。以上验证正确性与完成数量，不声明 QPS、吞吐提升或 1000 条数据库连接同时执行。

## 复现指令

先按 README 在项目专用终端设置现有 JDK 21、D 盘 cache/home/temp/TEMP/TMP；确保 Docker Linux engine 正常。测试不依赖开发 Compose、3307 或手工 .env，使用独立 Testcontainers MySQL。

```powershell
Set-Location D:\JavaWeb_Project\backend
.\mvnw.cmd -B spotless:check verify

# 只重现 1000 请求实验；完整交付仍要求上一条全部通过。
.\mvnw.cmd -B verify '-Dtest=Stage2IntegrationTest#thousandDistinctUsersAndKeysOverRealHttpNeverOversellAndWriteSanitizedArtifact'
Get-Content -LiteralPath 'target/stage-2-concurrency-result.json' -Encoding utf8
```

本次本机命令额外只读复用 `-gs 'E:\No.3 [NTFS 新加卷]\ 根目录\Maven\apache-maven-3.8.6\conf\settings.xml'`。CI 使用 Maven Central；没有把机器路径或现有镜像设置写进项目 POM。日志保留在 D 盘 `.build-cache/stage2-full-verify.log`、`stage2-clean-verify.log` 和 `stage2-app.stdout.log`；CI 保存 Surefire 及 JSON artifact。

## 设计取舍、风险与未验证项

- [ADR 0002](../decisions/0002-stage-2-order-consistency.md) 记录唯一键先占位、请求 SKU 无提前 FK 的原因、event/session/SKU 锁序、订单终态、代理事务补偿与核销码边界。订单模块只调用 event/ticket application service，不跨模块直接调用 Mapper。
- P0/P1：目前实测及代码复核未发现；待 ChatGPT 独立集中审核，不绝对声称不存在缺陷。
- P2：到期扫描每 30 秒最多 100 单，数据库短暂故障后可重扫；大量积压时关闭有延迟，尚未引入 MQ 或高负载调优。列表/详情采用清晰的条目/票据查询，未作容量承诺。
- 核销只持久化成功结果，失败返回脱敏错误但不落审计表；旧码在轮换提交后失效，客户端应按需刷新展示码。核销窗口为 `[startsAt-2h, endsAt+1h]`，两端包含。
- 沿用 Stage 1 JWT 有效期内不即时撤销的限制；未扩大为账户管理/强制下线功能。
- 未验证事项：未进行容量/QPS、硬件故障、真实支付或多实例长期运行实验；这些不属于本阶段正确性 Gate。最终文档提交触发的 CI 状态以 PR Checks 为准，上述固定链接保留已验证实现的证据。
- 不在范围：多 SKU/多票订单、真实支付退款、活动取消运营规则、Redis、MQ、Outbox、前端、性能基准、OS/Docker 升级。

## 审核定位与后续依赖

- 下单链：`OrderController → OrderService.create → InventoryService.reserve → OrderMapper/InventoryMapper`；付款：`OrderService.pay → TicketService.issue`；关单：`OrderExpirySchedule → OrderExpiryScanner → OrderService.closeExpired → InventoryService.release`；核销：`OrganizerTicketController → TicketService.checkIn → TicketMapper`。
- 迁移：`backend/src/main/resources/db/migration/V2__orders_and_tickets.sql`。V1 不变，order item / ticket / success record 唯一键及状态 CHECK 为最后防线。
- 测试：`backend/src/test/java/com/eventflow/Stage2IntegrationTest.java`；建议重点读幂等并发、事务回滚、支付/取消/扫描竞态、码轮换/窗口/并发核销及 thousandDistinctUsers 方法。原 Stage 1 13 项只增加禁调度测试配置，行为覆盖保留。
- 学习材料：[四条关键调用链与状态图](../learning/stage-2-key-flows.md)；[API/数据契约](../02-domain-and-api-contract.md)；根 README 提供端到端 PowerShell 演示，令牌与码仅留内存。
- 后续已具备：持久订单/库存/电子票状态、可重复调用的 DB 关单用例与扫描补偿，为后续经授权的异步触发提供基础；没有开始消息实现。
- 本 PR 保持打开、不合并；尚未开始 Stage 3，等待集中审核。

收尾已停止本次冒烟 Java PID 和项目 3307 容器，保留项目卷；原 MySQL 服务仍 Running、3306 监听，默认 Java 17 未改。未批量删除文件/目录或清理 Docker 数据。
