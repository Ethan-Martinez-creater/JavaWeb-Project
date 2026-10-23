# Stage 4 交接：Vue、全栈部署与源码学习

本报告记录实现与实测证据，**等待 ChatGPT 最终集中验收**。不自行合并 PR。

## 元数据

- 分支：`codex/stage-4-ui-deployment-learning`；Base：`3470d5dc7ee19d57739e0391a45694bcf9af9ff1`（含 Stage 3 PR #4 merge `1ee0bb3efc60fbba55e224d2e533b499c20b3cb9`）。
- PR：[Stage 4 #5](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/5)，保持 open、未合并。归档的完整 Linux CI 源 SHA：`c41c05f0153a11bb531282fa668bc353fdf6f44a`；其后的提交只补充交接和证据。最终 Head 以 PR commits/checks 为准（报告无法包含自己的提交 SHA），同时在交付消息给出。
- 本机执行环境：Codex Desktop 在用户 Windows 10 宿主执行，通过 Docker Desktop Linux engine 运行隔离容器；不是远程 Linux runner，也没有把云端检测误当用户本机。JDK 21.0.9 仅进程内复用，Wrapper Maven 3.8.6，Node 24.15.0、pnpm 11.21.0、Docker 29.7.2、Compose 5.3.1。默认 Java 17.0.2 未改，原生 `MySQL` 服务保持 Running。
- Docker 实验资源：Intel i5-8300H / 8 逻辑核，Linux engine 8 vCPU、8268677120 bytes RAM；性能 JSON 同时保存 Node 观察到的宿主内存/OS。
- 数据库迁移：V1/V2/V3 保持原样，无 V4。沿用 MySQL 8.4.11、Redis 8.4.7、RabbitMQ 4.3.6 的既有 digest。

## 功能与验证矩阵

| 要求 | 观察状态 | 实现与证据 |
|---|---|---|
| 原 42 项业务正确性保留 + 草稿重新读取权限 | PASS（本机 43/0/0/0） | [backend-local.json](stage-4-backend-local.json)、`Stage1IntegrationTest.organizerCanReloadOwnedDraftDetailWithoutLeakingItToOtherUsers` |
| 1000 不同用户/不同键，库存 100 | PASS（100 创建、900 SOLD_OUT、余量 0） | [并发原始 JSON](stage-4-concurrency-local.json)；48 个 worker 一次释放栅栏，并非声称 1000 TCP 请求同一瞬间开始 |
| Redis/MQ 故障与补偿不变量 | PASS（原 12 项 Stage3 测试重新执行） | [本阶段故障 JSON](stage-4-fault-local.json)，保留 [Stage3 CI 13 条故障观察](stage-3-fault-ci.json) |
| 已有 Stage3 持久库兼容 | PASS | [既有库实测](stage-4-existing-db-local.json)：3 migrations validated、version3、无需新迁移、健康 UP、公开活动 HTTP200；检查时禁用后台消息/扫描写操作 |
| Vue 公开/USER/ORGANIZER 流程、权限/异常/二维码 | PASS（真实浏览器） | [10 个流程观察](stage-4-e2e-local.json)，公开 401、USER-only 注册/403、主办方编辑发布、支付/码轮换/旧码拒绝/核销/通知；无浏览器运行异常 |
| 前端 typecheck/lint/格式/unit/build | PASS（本机 13/0/0/0） | [前端实测](stage-4-frontend-local.json)，含 HTTP 浏览器缺少 randomUUID 时的幂等键回归；Docker production build 通过 |
| 五服务空库 Flyway、真实浏览器全流程 | PASS（全新项目 1/0/0） | [空库基线](stage-4-fresh-db-local.json)：V1/V2/V3、1 dev 主办方、0 活动/订单/票；[Playwright](stage-4-browser-local.json)：7972 ms、零重试/失败/跳过 |
| 重启后持久化 | PASS | [重启原始 JSON](stage-4-persistence-local.json)：迁移 1,2,3；users=2、events/orders/tickets/checkins/notifications 各 1，重启前后相同 |
| 冷/热缓存读性能 | PASS（本机各 500 请求、零错误） | [完整原始样本](../../benchmarks/results/stage-4-read-local.json)，p50/p95：冷 14.936/27.715 ms，热 10.001/17.075 ms；详情 miss=500/0 |
| 学习/架构/面试/简历/OpenAPI | PASS（静态源码核对） | 6 篇学习文档、22 题、19 API paths/22 operations、131 本地 `$ref` 解析；PyYAML 校验 |
| 三项 CI Gate | PASS（Linux 全部成功） | [后端 run](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39893213836)、[前端/全栈 run](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39893213842)、[CI 证据索引](stage-4-ci.json)；无基础设施自动 skip |

## 已执行后端与兼容性结果

本机 `backend` 下使用提交的 Wrapper：

```powershell
.\mvnw.cmd -gs 'E:\No.3 [NTFS 新加卷]\ 根目录\Maven\apache-maven-3.8.6\conf\settings.xml' -B spotless:check verify
```

临时进程变量选择 `D:\JAVA21`，所有 Maven/JVM/tmp 缓存在 `.build-cache`；`-gs` 只读取既有镜像设置，不修改系统配置。2026-10-21 21:52:36 +08:00 完成，`BUILD SUCCESS`，耗时 07:15；Stage1 14、Stage2 17、Stage3 12，合计 43，failures/errors/skipped 均 0。故障注入会出现预期 MQ/DB 错误日志，不以单行 ERROR 判断测试失败；结果由 Surefire 断言及汇总决定。

兼容性检查使用本次构建 jar、原 Stage3 项目卷及原凭据，在独立 18084 端口启动，无 dev 初始化，并关闭消息/扫描后台写操作。Flyway 校验 3 个历史 checksum，数据库无需新迁移；读取健康与目录后停止该 Java 进程及本轮启动的开发服务，不删除数据。没有连接原生 3306。

## 前端、全栈与性能复现

从零启动：[README](../../README.md)、[infra](../../infra/README.md)。前端项目依赖与脚本：[frontend/README.md](../../frontend/README.md)。真实浏览器与报告：[e2e/README.md](../../e2e/README.md)。完整原始样本和控制变量：[benchmarks/README.md](../../benchmarks/README.md)。

E2E 使用全新 `eventflow-e2e-*` 项目，性能使用 `eventflow-bench-*`；默认个人演示 `eventflow-full`。三者 Compose scoped 卷互不共享，也不挂载 Stage3 的显式命名开发卷。E2E 的 Playwright 运行在版本匹配的浏览器容器，身份只在 Pinia 内存，因此登录后采用 SPA 路由导航，刷新测试必须重新登录。测试不 stub API、数据库或 MQ。

2026-10-23 本机最终验收项目 `eventflow-e2e-local-20261023-final` 从新卷启动。源功能提交 `8b4f39b77a9b16e75816c34ebbb16de2d6b38288`；先查询空业务表和 V1/V2/V3，再用 Compose overlay `run --build --rm e2e` 完成 10 项流程，最后执行 `Verify-Persistence.ps1` 重启全部五服务并比较记录。公开页面截图经实际检查后保存在 [桌面](../screenshots/stage-4-events-desktop.png) / [手机](../screenshots/stage-4-events-mobile.png)；重新以匿名上下文拍摄，等待瞬时 toast 消失，不隐藏 UI 或修改图片。验收栈随后安全 stop，保留卷。

读实验固定 100 个活动（各 1 场次/1 SKU/100 库存）、5 连接、每阶段 5×100 次请求。先预热 JVM/SQL；冷阶段每轮推进私有 Redis generation，热阶段复用缓存，验证详情 miss=500/0。p50/p95 为逐响应时间的最近秩分位数，不把采样结束等待折算成生产 QPS；热缓存详情仍有真实库存 SQL。

2026-10-23 10:37:22–10:39:20 +08:00，在全新 `eventflow-bench-local-20261023` 运行 `node --env-file=../.build-cache/stage4-bench.env read.mjs`。总区间含 400 次建活动/场次/票种/发布请求和 100 次 JVM/SQL 预热，不计入下面的逐响应延迟；正式测量期间没有并行构建或其它项目业务流量。宿主 Windows 10.0.19045、i5-8300H、8 逻辑核、17063727104 bytes RAM；Docker Linux engine 8 vCPU、8268677120 bytes RAM，Node 24.15.0。脚本与完整环境、镜像 ID、每轮 autocannon 输出见原始 JSON。

| 元数据阶段 | 请求数/连接 | p50 ms | p95 ms | HTTP/网络/响应校验错误率 | 详情 miss / 推算 hit | 工具轮次时长合计 s |
|---|---|---|---|---|---|---|
| 冷 | 500 / 5 | 14.936 | 27.715 | 0% | 500 / 0 | 5.06 |
| 热 | 500 / 5 | 10.001 | 17.075 | 0% | 0 / 500 | 5.05 |

两阶段 Redis INFO hits 都是 1500（含 generation GET 等），不能把此原始计数直接当应用命中率；隔离项目无其它读流量时按 500−miss 推算详情 hit。这里“冷”指 Redis 元数据未命中，JVM/SQL 已预热。这是闭环固定负载实验，无最大吞吐、生产容量或高可用结论。实验后仅 stop 本项目，卷保留；未对共享/公网服务压测。

## Linux CI 与证据核验

第二轮完整运行于 2026-10-23。后端使用 Ubuntu runner 的 Temurin 21.0.12.1 和仓库 Maven 3.8.6 Wrapper，**不是修改宿主 JDK**；前端使用 workflow 配置的 Node 24、pnpm 11.21.0。真实 MySQL 8.4.11/Redis/RabbitMQ 由 Testcontainers 或隔离 Compose 项目提供，未替换为 H2/stub。

| Gate / 命令 | 真实结果 | 可核对证据 |
|---|---|---|
| [backend verify](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39893213836/job/120217535141)：`./mvnw --batch-mode spotless:check verify` | 14 + 17 + 12 = 43；failures/errors/skipped 全 0；BUILD SUCCESS | [Surefire 脱敏汇总](stage-4-backend-ci.json)，原始 XML 在该 run 的 surefire-reports artifact |
| [frontend](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39893213842/job/120217535491)：frozen install/typecheck/lint/format/unit/build | 全通过；13 tests，failures/errors/skipped 全 0 | [原始 JUnit](stage-4-frontend-ci.xml) |
| [full-stack-e2e](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39893213842/job/120217535209)：Compose build/up、browser、`Verify-Persistence.ps1`、health | 1 passed，0 failed/skipped，8457 ms；10 个业务/安全观察；重启持久化相同；5 服务 healthy | [浏览器](stage-4-browser-ci.json)、[流程](stage-4-e2e-ci.json)、[持久化](stage-4-persistence-ci.json)、[健康状态](stage-4-compose-health-ci.json) |
| 同次后端的 1000 HTTP 并发和故障注入 | 100 创建/900 SOLD_OUT/余量 0；13 条故障场景观察 | [写并发](stage-4-concurrency-ci.json)、[故障原始 JSON](stage-4-fault-ci.json) |

归档下载的全部 artifact 先与 GitHub 返回的 SHA256 比较，再按明确 entry 名读取；未把任意 ZIP 路径写入文件系统。全栈 artifact ID `12476045305` / SHA256 `9454cac4033dd6756bdd4bbfd6405777ab735b0b085c71c174df99265b80516a`，核验实际只含六个预期报告/图片，环境秘密不在其中。测试源 SHA、run/job/artifact ID 及失败历史见 [stage-4-ci.json](stage-4-ci.json)。首轮 [39891526085](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39891526085) 的报告落盘权限失败保留；修复后完整重新运行，没有修改或隐藏首轮结论。

交付核验已完成：

- [x] Vue 真实业务闭环、USER-only 注册/角色安全、中文响应式与真实截图。
- [x] 五服务空库迁移、既有 Stage3 库兼容和重启持久化。
- [x] 本机/CI 后端 43、前端 13、真实浏览器及 1000 请求证据。
- [x] 冷/热读性能的脚本、原始逐响应样本、环境与解释边界。
- [x] README、API/OpenAPI、架构/调用链、六篇学习材料、22 题与面试演示。
- [x] 本机默认 Java 17.0.2、原生 MySQL Running；没有系统升级、全局配置修改或卷删除。Stage4 本轮测试栈已 stop，卷保留。
- [x] 单一 Stage4 PR 保持 open，等待 ChatGPT 最终集中验收；不自行合并。

## 设计取舍、风险与未验证边界

- [ADR0004](../decisions/0004-stage-4-ui-deployment-and-evidence.md)：JWT 内存、幂等键会话边界、同源代理、隔离数据与证据方法。历史 ADR0001–0003、交接和 JSON 均保留。
- 生产安全边界保持：公开只注册 USER，无升权接口；后端资源归属未放宽。草稿详情新增受已有 ORGANIZER/ADMIN 角色保护；公开仍不可读草稿。编辑/价格/库存规则仍遵循 S1–3。
- 内存 JWT 降低持久残留，但不抵御已执行 XSS；刷新重新登录。sessionStorage 只存幂等键、主体/SKU 标识；存储被禁用时只有当前页面内存重试保证，不能声称跨刷新安全。
- 单机 Compose 没有生产 TLS、秘密托管、多节点 HA、真实支付/退款、强一致缓存或 exactly-once 消息；手动粘贴核销码，不申请摄像头权限。不对公网或共享服务压测。
- 经当前回归未发现 P0/P1；已知 P2：Element Plus 主 bundle 957.81 kB（gzip 307.30 kB），触发 Vite 默认 500 kB 提醒。路由页面已按需加载；目前优先清楚的接入，后续可评估按需组件引入并以实际构建验证，不声称已完成包体优化。
- Docker Desktop 启动前 daemon 不可用；按既有授权正常启动已安装应用，未改 WSL/Windows 功能或 Docker 系统配置。首次构建拉取固定镜像/项目依赖；主机无安装/升级。显式宿主 Maven/pnpm/npm/浏览器缓存与临时文件均指向 D 盘项目 `.build-cache`；Docker 镜像/卷由现有 Desktop 存储管理，未迁移其位置。
- 本机首次 pnpm 获取被既有进程失效代理阻断（ECONNREFUSED），后续仅在命令进程中改用官方 registry/清除失效代理，未修改全局 npm/pnpm 配置。
- 实际调试修复：builder 缺少 unzip 导致 Wrapper 切换归档但 checksum 不匹配；补充仅构建阶段 unzip。受保护的 readiness 子路径返回 401，探针改用公开的整体 health；Nginx 默认 upstream Host 含下划线被 Tomcat 拒绝 400，显式传递合法 Host。首次冷库初始化与大镜像解压竞争磁盘，曾触发启动期限，保留卷后正常恢复，未重置数据。
- 浏览器先后暴露 fixture 参数声明、Element Plus 原生 input 属性转发、HTTP 服务名不提供 randomUUID、重复嵌套 alert。分别修正测试声明/定位、以 getRandomValues 保持 128 bit 安全随机键并增加单测、保留单一 alert；没有跳过业务断言。失败报告保存在本机 `.build-cache`，最终证据以完整成功运行和 CI 为准。
- 首轮 Linux CI 的前端、43 项后端及浏览器闭环通过，但持久化报告落盘失败：Linux bind mount 的浏览器目录由 root 创建，runner 不能写入。持久化脚本默认报告改到宿主自有 `.build-cache/stage4-persistence.json`，并纳入 artifact；不 chmod、不提权、不放宽数据库断言。首轮失败保留在 Actions 历史，最终结果另列。
- 停止项目保留卷；修改环境文件密码不会更新已有数据库账户。敏感本地环境文件、缓存、JWT、明文码均不提交。真实截图仅包含公开页。
- 所列阶段内验证已执行并归档，不代表人类阶段审核已经通过。最终 Head 的后续 CI 以 [PR checks](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/5/checks) 为准；生产 HA/真实支付等边界未在本阶段验证。

## 推荐阅读与审核定位

1. [学习总览](../learning/00-project-overview.md)、[Java 调用链](../learning/01-java-spring-code-tour.md)：启动、Security、Controller、Service 事务、Mapper SQL。
2. [订单并发](../learning/02-orders-inventory-concurrency.md)、[Redis/MQ](../learning/03-redis-rabbitmq-reliability.md)：条件更新、锁序、幂等、Outbox、消费去重与失败退避。
3. `frontend/src/api/client.ts`、`stores/auth.ts`、`router/access.ts`、`features/orders/idempotency.ts`：前端边界；随后看各 feature 页面。
4. `infra/docker-compose.full.yml`、Dockerfiles/Nginx、`e2e/tests/stage4-demo.spec.ts`、`benchmarks/read.mjs`：可复现部署、业务闭环、实验方法。
5. [OpenAPI](../openapi.yaml) 与 [权威契约](../02-domain-and-api-contract.md)；[面试问答](../learning/04-interview-qna.md)、[项目介绍/简历/演示](../learning/05-resume-and-demo.md)。

本 PR 不合并，等待 ChatGPT 最终集中验收；没有自行推进其它开发阶段。
