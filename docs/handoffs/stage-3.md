# Stage 3 交接报告

## 元数据

- Stage：3，实现与验证后交付集中审核，不自行声明 ACCEPT。
- 分支：`codex/stage-3-redis-rabbitmq → main`。PR URL 与最终 CI 在验证完成后填写。
- Base SHA：`52dbbe098634fea92af001ce8e7639d25faff66d`；包含 Stage 2 PR #3 合并 `2ede68ebfdf88cd322811c10399c5f87e6d9f108`。
- 本机完整验证的实现 SHA：`e7a95496e403b47d8b59b36ffa1ca5dd8c82d4b6`；后续为交接/CI 证据更新，最终 Head 以 PR 为准，避免文档自引用 SHA。
- 执行位置：Codex 桌面端终端可访问的用户 Windows 10 Home 19045 x64；GitHub Actions 为独立 Ubuntu Linux 环境，二者分别记录。
- 复用 JDK 21.0.9 / Maven Wrapper 3.8.6，所有构建缓存/日志/JVM temp/home 在项目 D 盘 `.build-cache`，未修改全局 Java 17 或原 MySQL 5.7/3306、原 Redis。

## 环境与镜像

Docker daemon 29.7.2 / Linux engine；Compose 5.3.1。没有安装、升级 OS/WSL/Docker/全局 Java/Maven。开发 Compose 与测试容器隔离；本项目开发端口 3307/16379/5673/15673 只绑定 loopback，测试使用动态端口。

| 组件 | 实际固定版本 | 镜像 digest / 依赖来源 |
|---|---|---|
| MySQL | 8.4.11 | `0f51e63e537a1f176423fa03ef559aabe6fa4372a53160a44bc7e75da7d15364` |
| Redis | 8.4.7-alpine | `0514fa59e3d84e2f3be66731c5401fc2a85b8ea71b49c0116c850f6a558076f4` |
| RabbitMQ | 4.3.6-management-alpine | `3505b6096cdbc083bcc363e74366f575ecd0c1a55be4dadcbc60fa08e34647e1` |
| Spring Boot / Redis / AMQP Starter | 4.1.1 | Boot BOM；未更换原技术基线 |
| Spring Data Redis / Spring AMQP | 4.1.1 | Maven dependency:tree 实际解析 |
| Lettuce / Rabbit Java Client | 7.5.2.RELEASE / 5.30.0 | Maven dependency:tree 实际解析 |
| MyBatis Starter / Testcontainers | 4.1.0 / 1.21.4 | 原 Stage 1/2 基线 |

固定 tag 经 Docker official-images 清单核实，digest 经真实 pull 观察；参见 [ADR 0003](../decisions/0003-stage-3-reliability.md)。开发容器实际输出 `mysqld Ver 8.4.11`、`Redis server v=8.4.7`、`rabbitmqctl version 4.3.6`，三项 Compose 均 Healthy。

首次 RabbitMQ 冒烟捕获健康探针 root 用户抢先生成 cookie 的 eacces，已把探针改为 `su-exec rabbitmq rabbitmq-diagnostics`，只修复本项目新建卷内单个 cookie 所属用户后再次启动成功；未修改系统设置或不相关服务。

## 功能验收矩阵

| 要求 | 状态 | 实现与证据 |
|---|---|---|
| 元数据缓存、实时库存、afterCommit 失效、并发回填 | PASS | 缓存无 availableStock；命中后库存实时变化；回滚不失效、提交递增 generation、旧代 CAS 回填被拒 |
| 原子用户限流、429、TTL 与 fail-open | PASS | 同用户 8 并发：3×201、5×429；独立用户可用、TTL 复原；已达限额后暂停 Redis 仍可下单 |
| 订单状态与 Outbox 原子提交/回滚 | PASS | 创建、支付、关闭分别注入 Outbox INSERT 失败；订单/明细/票/库存回滚；成功重放无新状态事件 |
| confirms、return、lease、持久退避及 MQ 恢复 | PASS | MQ 停机期间业务提交；恢复发布 SENT；真实 broker pause 得 CONFIRM_TIMEOUT，解绑得 UNROUTABLE，二者保持 PENDING；并发认领与失效 lease 回收/旧 owner 拒绝 |
| TTL/DLX 早到、重投、晚到及终态竞争 | PASS | 真 MQ 早到转 TTL 队列，到期 DLX 关闭；晚到/重复/乱序/支付/取消竞争均验证终态与库存 |
| 通知事务/ack、去重、本人分页、DLQ | PASS | 唯一通知；本人 SQL 分页与 RBAC；暂停/重启监听器后投递；消费 INSERT 失败连同去重回滚，有限重试 DLQ，修复后单事件重驱 |
| DB 扫描与 101+ 毒单公平退避 | PASS | MQ stop_app 时前100缺明细毒单退避，健康第101单下一轮关闭；退避前次数不增、到期后重试 |
| V1→V2→V3 既有数据迁移 | PASS | 保留用户角色、BCrypt hash、活动/场次/库存/订单金额/PAID/票及票码摘要；只追加 V3，未改 V1/V2 |
| 原 Stage 1/2 30 项与 1000 请求回归 | PASS | 原断言保留；本机 30/0/0/0，100×201 + 900×SOLD_OUT，库存0 |
| Linux CI / Spotless / 全部测试 | UNVERIFIED | 待 PR CI |
| 独立开发容器版本/健康 | PASS | 实际命令与输出见上 |
| 打包应用与带密码 Compose 闭环 | PASS | [脱敏原始 JSON](stage-3-smoke.json)：health UP、V2→V3、库存 4→3、PAID、1 条 ORDER_PAID 通知 |

## 测试执行与原始证据

本机完整结果：2026-10-21 19:45:17（Asia/Shanghai）结束，Maven Wrapper `spotless:apply verify`（包含 validate 的 Spotless check）**BUILD SUCCESS，42 tests / 0 failures / 0 errors / 0 skipped**，总计 4:12。其中 Stage 1/2 原30项全部保留，Stage 3 新增12项真实三中间件测试。Linux CI 尚待 PR 后运行，不与本机结果混为一谈。

| 本机 Surefire suite | Tests | Failures / Errors / Skipped | 耗时（秒） |
|---|---:|---|---:|
| Stage1IntegrationTest | 13 | 0 / 0 / 0 | 43.658 |
| Stage2IntegrationTest | 17 | 0 / 0 / 0 | 43.081 |
| Stage3IntegrationTest | 12 | 0 / 0 / 0 | 125.251 |

[本机故障原始 JSON](stage-3-fault-local.json) 包含13条通过断言后的故障观察（一个测试可产生多条观察）；[本机1000请求原始 JSON](stage-3-concurrency-local.json) 得到 100×201、900×SOLD_OUT 409，数据库 pending orders/items/quantity 各100、不同订单所有者100、剩余库存0，无吞吐或 QPS 声明。

测试曾真实捕获两类问题并修复：临时 MySQL 的二进制日志限制阻止故障注入 trigger 创建，仅在一次性测试容器启用 `log-bin-trust-function-creators`；并行 publisher 的范围锁认领引发死锁，已改主键条件原子认领、owner 条件落状态与瞬时失败下次调度恢复。支付/关闭 Outbox 插入失败的票据、库存和状态回滚也加入回归。修复后的42项全量通过；开发 Compose 和宿主数据库未启用上述测试特例。

```powershell
Set-Location backend
.\mvnw.cmd -B spotless:check verify
```

本机额外只读传入既有 Maven `-gs .../conf/settings.xml`，所有缓存和 JVM 临时路径按 README 指向 D 盘。CI 使用同一 wrapper 与 Maven Central。原 1000 请求结果为 `target/stage-2-concurrency-result.json`，Stage 3 结果为 `target/stage-3-fault-results.json`，Surefire 报告包含实际 tests/failures/errors/skipped/time；CI 全部上传 artifact。

可独立复现的关键故障命令（本次通过全量 verify 执行相同方法；从 backend 目录运行，均启动真实容器）：

```powershell
.\mvnw.cmd -B '-Dtest=Stage3IntegrationTest#publisherConfirmTimeoutIsNotMistakenForSuccessfulDelivery' test
.\mvnw.cmd -B '-Dtest=Stage3IntegrationTest#outboxWritesRollbackWithOrdersAndMandatoryReturnKeepsUnroutableEventPending' test
.\mvnw.cmd -B '-Dtest=Stage3IntegrationTest#scannerContinuesPastOneHundredPoisonOrdersWhileRabbitIsStoppedAndBacksOffFailures' test
.\mvnw.cmd -B '-Dtest=Stage3IntegrationTest#redisRateLimitIsAtomicPerUserAndCacheAndOrderFallbackRecoverAfterPause' test
```

| 注入 / 恢复 | 原始断言结果 |
|---|---|
| Redis `CLIENT PAUSE 2200 ALL`，同用户先已达429限额；等待恢复 | 目录200、故障期间下单201；恢复后缓存命中；并发8中3成功/5超限、独立用户成功、TTL后可再下单 |
| Rabbit `stop_app` / `start_app` | 停机下单201与Outbox PENDING；持久 next_attempt_at 到期后恢复SENT |
| 暂停真实 broker 容器 / unpause | `last_error=CONFIRM_TIMEOUT`、保持PENDING；没有误标SENT |
| 移除 `order.created` binding / 重新声明 | `UNROUTABLE`、保持PENDING，即使 broker 有confirm也不视为路由成功 |
| 真实SQL trigger拒绝Outbox或通知INSERT / 删除测试trigger | 业务状态、库存、票与Outbox整体回滚；消费去重与通知一起回滚；修复重驱后恰好一条通知 |
| 监听器暂停/恢复，重复投递，poison与有界重试 | 消息等待后继续处理；重复通知无重复行；损坏内容净化为错误码进入DLQ，耗尽重试保留count=2（测试配置） |
| 真MQ早到、TTL/DLX到期、晚到、重复、乱序及终态竞争 | 早到不消费完成、不释放；到期释放一次；PAID保持票据与预占，取消/到期CLOSED无重复事件 |
| 并发claim、遗留lease超过5秒（测试配置）、旧owner落状态 | 一个owner胜出；lease到期可接管；旧owner标记失败；并行publisher完整投递且正常记录attempts=1 |
| 101到期单，其中100缺明细毒单，MQ停机 | 初批100失败持久退避；第二轮健康第101单关闭并释放1；退避期不增次数，到期后次数从1到2 |

## 完成清单

- [x] Stage 3 业务、缓存、消息、补偿与通知接口实现，保留原架构和安全边界。
- [x] V3 增量迁移与既有数据保持；原 V1/V2 未修改。
- [x] 本机42项真实测试、1000请求回归、带密码开发容器/打包应用冒烟。
- [x] 设计决策、关键调用链、API、README 与脱敏原始证据。
- [ ] Linux CI 与 PR 链接：创建 PR 后补入真实结果。
- [x] 不自行合并，不开始 Stage 4，交付集中审核。

## 设计取舍、风险与定位

- [ADR 0003](../decisions/0003-stage-3-reliability.md)、[关键调用链](../learning/stage-3-key-flows.md)、[API 契约](../02-domain-and-api-contract.md)、[README 复现与故障命令](../../README.md)。
- 缓存仅加速稳定元数据；缺失/草稿不缓存，随机 ID 会回源；匿名流量防护仍需部署入口。Redis 失效失败时列表陈旧窗口为 TTL 加抖动；实时库存每次由 MySQL 补齐，下单仍以事务条件扣减裁决。
- Redis 故障限流 fail-open，会失去流量保护，不失去 MySQL 状态不变量。
- MQ 至少一次，发后宕机、ack 丢失均可重投；唯一约束与状态检查保证效果幂等。TTL/DLX 存在队头阻塞/延迟，DB scanner 保留。
- Publisher 无限持久重试但延迟封顶；消费重试有界后 DLQ。积压与 DLQ 需要运维观察；人工单事件重驱动见 README，保留原 DLQ 证据。
- 单节点开发 durable 队列不提供 broker 磁盘灾难恢复；生产复制、保留清理/归档策略、监控平台、真实容量评估不在本阶段。没有吞吐/QPS 宣传。
- 推荐先看 OrderService 的三个 Outbox 调用、RedisPublishedEventCache、OutboxPublisher、消费事务与监听 ack 的代理边界，再看 scanner retry 和集成测试。
- 经本机测试未发现尚未修复的 P0/P1；P2 边界为上述匿名随机 ID 回源、单节点 broker、积压监控与数据保留策略。Linux CI 在 PR 创建前仍为 UNVERIFIED，完成后在此补证；未做容量基准、broker 磁盘损坏、多节点故障或真实支付网关测试，均不在本阶段 Gate 范围。

本 PR 交付条件为打开 PR，不自行合并；未开始 Stage 4。
