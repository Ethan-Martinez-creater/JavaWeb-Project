# Stage 3 交接报告

## 元数据

- Stage：3，实现与验证后交付集中审核，不自行声明 ACCEPT。
- 分支：`codex/stage-3-redis-rabbitmq → main`。PR URL 与最终 CI 在验证完成后填写。
- Base SHA：`52dbbe098634fea92af001ce8e7639d25faff66d`；包含 Stage 2 PR #3 合并 `2ede68ebfdf88cd322811c10399c5f87e6d9f108`。
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
| 元数据缓存、实时库存、afterCommit 失效、并发回填 | UNVERIFIED | RedisPublishedEventCache / EventService；待实测 |
| 原子用户限流、429、TTL 与 fail-open | UNVERIFIED | OrderRequestRateLimiter / OrderController；待实测 |
| 订单状态与 Outbox 原子提交/回滚 | UNVERIFIED | OrderService / OutboxRecorder / V3；待实测 |
| confirms、return、lease、持久退避及 MQ 恢复 | UNVERIFIED | OutboxPublisher；待实测 |
| TTL/DLX 早到、重投、晚到及终态竞争 | UNVERIFIED | 到期消费者 → OrderService；待实测 |
| 通知事务/ack、去重、本人分页、DLQ | UNVERIFIED | 通知消费者 / NotificationService；待实测 |
| DB 扫描与 101+ 毒单公平退避 | UNVERIFIED | OrderExpiryScanner / OrderExpiryRetryService；待实测 |
| V1→V2→V3 既有数据迁移 | UNVERIFIED | 只追加 V3，未改 V1/V2；待实测 |
| 原 Stage 1/2 30 项与 1000 请求回归 | UNVERIFIED | 原断言保留；待实测 |
| Linux CI / Spotless / 全部测试 | UNVERIFIED | 待 PR CI |
| 独立开发容器版本/健康 | PASS | 实际命令与输出见上 |

## 测试执行与原始证据

当前仍在实现/验证，本节在真实执行后更新；任何未执行项保持 UNVERIFIED。使用已提交 Maven Wrapper：

```powershell
Set-Location backend
.\mvnw.cmd -B spotless:check verify
```

本机额外只读传入既有 Maven `-gs .../conf/settings.xml`，所有缓存和 JVM 临时路径按 README 指向 D 盘。CI 使用同一 wrapper 与 Maven Central。原 1000 请求结果为 `target/stage-2-concurrency-result.json`，Stage 3 结果为 `target/stage-3-fault-results.json`，Surefire 报告包含实际 tests/failures/errors/skipped/time；CI 全部上传 artifact。

## 设计取舍、风险与定位

- [ADR 0003](../decisions/0003-stage-3-reliability.md)、[关键调用链](../learning/stage-3-key-flows.md)、[API 契约](../02-domain-and-api-contract.md)、[README 复现与故障命令](../../README.md)。
- 缓存仅加速稳定元数据；缺失/草稿不缓存，随机 ID 会回源；匿名流量防护仍需部署入口。Redis 失效失败时列表陈旧窗口为 TTL 加抖动；实时库存每次由 MySQL 补齐，下单仍以事务条件扣减裁决。
- Redis 故障限流 fail-open，会失去流量保护，不失去 MySQL 状态不变量。
- MQ 至少一次，发后宕机、ack 丢失均可重投；唯一约束与状态检查保证效果幂等。TTL/DLX 存在队头阻塞/延迟，DB scanner 保留。
- Publisher 无限持久重试但延迟封顶；消费重试有界后 DLQ。积压与 DLQ 需要运维观察；人工单事件重驱动见 README，保留原 DLQ 证据。
- 单节点开发 durable 队列不提供 broker 磁盘灾难恢复；生产复制、保留清理/归档策略、监控平台、真实容量评估不在本阶段。没有吞吐/QPS 宣传。
- 推荐先看 OrderService 的三个 Outbox 调用、RedisPublishedEventCache、OutboxPublisher、消费事务与监听 ack 的代理边界，再看 scanner retry 和集成测试。
- P0/P1/P2 与未验证事项：验证完成后按真实结果更新。

本 PR 交付条件为打开 PR，不自行合并；未开始 Stage 4。
