# Codex 执行任务书 — Stage 3（已授权）

> Stage 2 PR #3 已由 ChatGPT 集中审核 ACCEPT，合并 SHA `2ede68ebfdf88cd322811c10399c5f87e6d9f108`。
>
> 当前唯一开发任务：**Redis 活动缓存与接口限流 + RabbitMQ 延时关单触发、可靠发布/消费、通知落库 + MySQL 最终一致性与故障补偿**。不得开始 Stage 4 Vue、性能宣传或微服务拆分。

## 0. 协作及基线

1. 从合并 PR #3 的 **最新 main** 新建 `codex/stage-3-redis-rabbitmq`，阅读 `AGENTS.md`、`docs/00-project-charter.md`、`docs/01-architecture-and-code-style.md`、`docs/02-domain-and-api-contract.md`、`docs/03-stage-roadmap-and-gates.md`、`docs/08-stage-2-codex-task.md`、`docs/decisions/0002-stage-2-order-consistency.md`、`docs/learning/stage-2-key-flows.md` 和 Stage 2 交接报告。
2. 继续 Java 21、Spring Boot 4.1.1、MyBatis、MySQL 8.4、Flyway、Maven Wrapper、JUnit/Testcontainers、Spotless；**仍为单个 Spring Boot 模块化单体**。新增消息、缓存包依照业务边界组织，不能跨模块直接调用其他业务的 Mapper。
3. Redis、RabbitMQ 均优先独立容器化；选择**经官方核实可用的明确版本/tag 或 digest**，记录最终镜像/依赖版本。使用项目专属端口/卷，不覆盖宿主已存在的 Redis 8.4、MySQL 5.7/3306 或其他服务。无需本机另安装 RabbitMQ，不得擅自升级 OS、WSL、Docker、全局 Java/Maven。
4. 由 Codex 阶段内自主设计、实现、真实测试、故障注入与文档复现；最后一个 PR。用户不需要阶段中频繁审核。**不自行合并、不启动 Stage 4**。

## 1. 必须持续保持的正确性（P0）

Stage 1/2 全部既有接口与测试继续通过：库存不为负、不超卖；幂等键不会多扣库存；PAID/CLOSED 终态互斥；每张票只能成功核销一次；所有权及 RBAC 不放宽。

**MySQL 仍是订单、库存、核销状态唯一最终真值。Redis 缓存不能参与订单最终库存判定，RabbitMQ 不是事务提交的替代品。**
异步消息按 **at least once** 语义设计，通过数据库事务内状态检查/唯一约束/消费去重做到**业务效果幂等**；不得声称 RabbitMQ 本身提供端到端 exactly-once。

## 2. Redis：缓存与限流（P0）

### 2.1 活动目录/详情缓存

实现明确的 Cache Aside 路径：先读 Redis → 未命中读 MySQL → 写入 Redis（TTL 含少量抖动）；已发布事件的正向详情/热点列表可以缓存。要有可观察的缓存命中/未命中与失效证据，不能只引入 Redis 依赖。

**特别注意 S2 已有实时变化的 `availableStock`：** 禁止在缓存里长期保存可被误认为真实可售量的快照。推荐只缓存相对稳定的已发布活动元数据和票种配置，库存由一个批量 MySQL 查询动态补齐；如确需缓存可售量，必须明确极短 TTL / 写后失效 / 最长陈旧窗口及测试验证；两种方案都必须说明最终下单扣减由 MySQL 决定。不要把序列化的完整详情与实时库存混为一谈。

发布事务成功提交后使相关缓存失效；使用 afterCommit 回调或等价安全机制，不能在事务失败后让 Redis 呈现“已发布”。考虑并发填充与发布竞态，至少对缺失/草稿返回缓存穿透防护（如短 TTL 空值 + afterCommit 失效，或合理的不缓存负值方案），明确最终一致性的容忍窗口并编写用例。保证 Redis 停用后仍可从 MySQL 正常查询公开目录（若采用降级策略需测试）。

### 2.2 下单限流

为 `POST /api/v1/orders` 引入按 **认证用户 ID**（可选补充 IP 维度）的 Redis 原子限流，推荐小 Lua 脚本的计数+过期原子操作。不得用两条存在竞态的非原子 Redis 操作；HTTP 超限响应 429，附结构化错误 JSON；不能记录完整 JWT。

配置项需允许测试关闭或提高限额，避免破坏既有 Stage 2 的 1000 用户库存并发回归。Redis 故障时选择**明确且可解释**的 fail-open/fail-closed 策略并测试；即使降级，所有订单正确性仍由 MySQL 保证。提供并发多请求命中限额与 TTL/窗口复原真实 Redis 测试。

## 3. RabbitMQ：订单状态事件和可靠性（P0）

### 3.1 事务 Outbox + V3 schema

新增 Flyway `V3__...`（绝不能修改 V1/V2），设计具备唯一 eventId、聚合 ID、事件类型、必要 payload、创建时间、发布/重试状态、重试次数、下次尝试时间和合理索引的 `outbox_events`。如需要消费去重表、通知记录表，也使用 V3 增量迁移，给事件 ID/业务唯一性建明确约束。

在 **订单创建、支付成功、关闭成功** 等真正发生状态变更的 MySQL 事务中写 Outbox 事件；幂等重放、重复支付、重复取消、重复扫描不得伪造新的状态事件。Outbox 写入失败必须使该业务事务回滚；避免在 `@Transactional` 中直接阻塞等待 RabbitMQ 发送确认。

由独立 publisher 周期扫描 Outbox，发布持久化消息至 durable exchange/queue，使用 publisher confirms（必要时处理 mandatory returns / unroutable），确认发布后才将事件标记为已投递；发生超时/否认/进程崩溃时留待重试。发后未标记成功导致的重复投递由消费者幂等兜底。多 publisher 并发要求状态 claim/锁/lease 或其他可证明的并发安全设计，崩溃中的 claim 可超时恢复；重试应有退避上限与异常观测，避免 CPU 忙循环。不要跨数据库和 MQ 假装有真正单一 ACID 事务。

### 3.2 超时关单消息与数据库最终校验

在下单成功后发送与 `expires_at` 关联的消息，以 TTL + DLX / 死信队列（优先无需额外 RabbitMQ 插件）或经验证的等效方式在到期时触发 `OrderService.closeExpired(id)`，然后对消息 ack/nack。**实际能否关闭必须由 MySQL 当前 `expires_at` 和订单状态决定；**早到消息只触发检查，不能提前释放；PAID/CLOSED 不可二次释放；晚到/重投消息幂等。注意 TTL/DLX 在不同到期时间下可能存在队头阻塞/延迟，不能把它描述为严格精准定时器。

**必须保留独立 DB 周期扫描兜底**：MQ 关闭/消息丢失/消费者宕机后，逾期订单最终由扫描恢复。Stage 2 的 `OrderExpiryScanner` 当前固定读取最早 100 项；此阶段消除“永久失败的最早 100 项饿死后续订单”的风险，可用持久 nextRetryAt/backoff、游标公平扫描或等价方案。提供真实测试：至少 101 个已到期订单，前面部分长期失败，仍能推进后面的健康订单；失败会被限频重试。必须解释事务与调度代理边界。

### 3.3 异步通知（可演示闭环）

用 `ORDER_PAID` / `ORDER_CLOSED` 事件消费生成持久化**站内通知记录**（可用 `user_notifications`，按 eventId 唯一防重），提供登录用户本人列表 `GET /api/v1/me/notifications?page=0&size=20`，SQL 分页，禁止 IDOR。可选异步下单确认通知，但不得只打印日志作为全部消息功能。

消费者应持久化幂等检查/唯一约束后提交事务，再 ack；事务失败按明确可控的重试/死信策略处理，不能无穷高频 requeue。记录并解释 poison message、dead-letter、消息丢失/未路由场景。通知数据只包含用户可读取的必要信息，禁止完整 JWT、核销码、密码等敏感内容。

## 4. 故障恢复与验证（Gate，不得仅凭 CI 绿灯）

全部验证需包含 **真实 MySQL 8.4 + 真实 Redis + 真实 RabbitMQ**（优先 Testcontainers），通过真实 API/业务入口及可重复故障注入完成；不允许在关键 Gate 用 H2、纯 mock、关闭中间件测试代替。正常缓存/限流可以分别做单元测试，但每条业务链也须有实库/实中间件集成测试。

至少验证以下矩阵：

1. 原 Stage 1/2 30 项测试继续通过；测试配置可独立关闭缓存/限流/消息，但 **Stage 3 新增用例必须在真实 Redis/RabbitMQ 打开的情况下执行**。
2. 热点活动详情首次回源、再次命中、发布/必要变更后失效；数据库仍返回实时库存且不会因缓存陈旧超卖。Redis 停机/恢复时符合实际文档降级策略。
3. 同一用户并发超过限额最多通过已规定数量，429 结构、TTL 恢复与 Redis 故障策略在真 Redis 中验证；其他用户不受影响。
4. 真实 MQ 下验证 Outbox 与业务在同一事务的提交/回滚一致性；断开 MQ 仍能成功保存待发布事件、MQ 恢复后 publisher 发布；确认超时或未路由不得错误标成发送成功。
5. 重复发布/重投通知事件不会创建双通知；消费者失败/重启后事务与 ack 语义正确。恶意/损坏消息进入有界重试或 DLQ，且能观察或人工重驱动，不能静默丢失。
6. 早到、到期、晚到、重复、乱序关单消息；与支付/取消竞争后库存与终态始终一致，测试必须包含实际 MQ 路径和 DB 不变量，而不只是直接调用 Service。
7. 在 MQ 不可用/消费者暂停时，数据库扫描依然能关闭到期订单；**101+ 到期订单含故障毒单不会永久阻塞后续健康订单**；失效恢复有证据。
8. V1→V2→V3 在已有数据上正确迁移，用户、活动、订单、票不被破坏；所有权、密码/票码安全约束保持。Docker Compose 所用镜像、配置、密码外部化；README 可以在干净环境复现。
9. GitHub Actions Linux CI 的 Maven `spotless:check verify` / 集成测试全绿，无跳过；记录真实测试数、耗时、失败/跳过数、测试覆盖矩阵和关键故障注入证据（脱敏 JSON/日志/断言）。不编造吞吐或 QPS。

特别强调：若某项测试真实 MQ/Redis 无法运行，必须披露为 UNVERIFIED，不准声称该 Stage 3 Gate 通过。异常日志不得包含 SQL 密码、消息携带的 token、核销码或未脱敏用户数据。

## 5. 工程质量与阶段交付

- 事务边界在 application service，数据库 Outbox 提交与 MQ 发布分离，跨模块调用通过 service 或明确接口；保持 Stage 2 的线程/锁顺序与可理解的代码结构，优先清晰的 Java 实现，不引入分布式事务、Spring Cloud 或 K8s。
- Stage 3 新的配置全部环境变量化，Compose 仅绑定本机需要的端口、项目专属 volume，使用健康检查。为 RabbitMQ 提供 exchange/queue/binding/dead-letter 声明与启动步骤，并保证重复启动幂等。
- 将 Cache Aside 方案（包括动态库存）、Redis 限流 Lua、Outbox、publisher confirms、consumer ack、TTL/DLX、状态重试及 DB 兜底画成简洁图，写入 `docs/decisions/0003-stage-3-reliability.md` 和 `docs/learning/stage-3-key-flows.md`；在 `docs/02-domain-and-api-contract.md` 固化新增通知 API 与数据模型。
- 更新 README 的开发容器启动/停止、故障注入与端到端命令，确保不需要泄露凭据即可复现；注意 Redis/RabbitMQ 端口冲突、Windows 和现有服务隔离。
- 写 `docs/handoffs/stage-3.md`：功能/测试矩阵、关键源码、CI 链接、真实故障复现命令和输出、已知 P0/P1/P2/未验证项目、缓存陈旧与消息至少一次边界、版本选择。测试材料能让用户后来学懂而非只能引用名词。
- 若必要可整理 Stage 2 过长集成测试和复杂测试夹具，但**不能减少已有断言或弱化权限/一致性**。
- 从 `codex/stage-3-redis-rabbitmq` 创建一个 PR，建议标题 `[Stage 3] Redis cache, rate limiting and reliable RabbitMQ events`。结束只向用户交付 PR URL、head SHA、完整真实 CI/故障测试证据及仍未解决风险。**不得自行合并或继续 Stage 4。**
