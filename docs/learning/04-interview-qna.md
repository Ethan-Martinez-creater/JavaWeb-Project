# 04｜面试问答：从源码回答

回答时先讲约束，再讲实现，最后说明证据和边界。路径均相对于仓库根目录。不要把单机演示描述成高可用生产系统，也不要在读性能实验产生原始结果前写 QPS。

## Spring、接口与事务

### 1. 这个项目为什么使用模块化单体？

预约、订单、库存、票据的关键不变量都依赖同一个 MySQL 本地事务。单体减少跨服务事务和运维复杂度，同时通过 `identity/event/order/ticket/messaging/notification` 包界定职责。比如订单只通过 `InventoryService` 预占库存，不跨模块调用 `InventoryMapper`。见 `backend/src/main/java/com/eventflow/order/application/OrderService.java` 和 `event/application/InventoryService.java`。

### 2. Spring 的构造器注入解决什么问题？

`OrderService` 通过构造器显式声明 Mapper、库存、票据、Clock 和 Outbox 依赖。对象创建时依赖齐全、字段可设为 final，也容易看清模块协作；无需字段注入或 Service Locator。参见该类及 `EventFlowApplication` 的组件扫描入口。

### 3. `@Transactional` 放在哪层，为什么？

放在应用用例 Service，因为它编排订单、库存、票据和 Outbox 的一次业务事务；Controller 负责 HTTP DTO 和状态码，Mapper 负责 SQL。看 `OrderService.create/pay/cancel`。`InventoryService.reserve` 与 `TicketService.issue` 为 `MANDATORY`，要求加入调用方事务。

### 4. 为什么 Spring 同类自调用会影响事务？

声明式事务通过代理拦截外部经代理的调用；同一个对象里的 `this.method()` 不重新经过代理，可能没有预期的事务传播/隔离边界。到期扫描器从独立 Bean 调用 `OrderService.closeExpired`，每个订单建立事务，而不是在一个 `@Transactional` 批处理里内部自调用。见 `OrderExpiryScanner.scanOnce` 和 `OrderExpiryRetryService`。

### 5. 为什么不用 JPA？

仓库选择 MyBatis XML，以便直接审查 MySQL 的锁、唯一键和条件更新；Flyway 是唯一 schema 迁移来源。比如 `OrderMapper.xml` 清楚显示 `FOR UPDATE` 和状态条件，`InventoryMapper.xml` 清楚显示库存条件更新。这里是显式 SQL 的取舍，不代表 JPA 无法处理事务。

### 6. JWT 验证做了哪些边界检查？

`SecurityConfiguration` 固定 HS256、检查 issuer、签名、过期时间、正整数 subject 和已知角色集合；密钥由外部环境变量提供，少于 32 UTF-8 字节时启动失败。公开注册请求不能指定角色，只建 USER。令牌在有效期内没有即时撤销机制。见 `identity/application/JwtTokenService.java` 与 ADR 0001。

### 7. 前端路由守卫是否足以保护 organizer 页面？

不够。浏览器中的角色可以被修改，路由守卫只改善导航体验。后端 Resource Server 先检查角色，Service 再将当前 JWT subject 与资源 owner 比较；跨主办方资源通常返回相同的 404，避免泄露存在性。见 `SecurityConfiguration`、`OrganizerEventController`、`EventService.draft`。

## MySQL、库存与订单

### 8. 怎样防止库存超卖？

用数据库锁序和 `WHERE available_stock >= 1` 条件 UPDATE，检查影响行数；扣减、订单占位/明细、Outbox 位于同一事务。详情缓存返回的可用库存只是查询时观察值，不是预约保证。证据是 `InventoryMapper.xml` 与真实 HTTP 测试 `thousandDistinctUsersAndKeysOverRealHttpNeverOversellAndWriteSanitizedArtifact`，它使用库存100、1000个不同用户和 key，最终核对订单和库存数据。该结果是正确性实验，不是 QPS 指标。

### 9. 为什么幂等不是先查再插？

并发请求可能都先看到没有订单。用 `UNIQUE(user_id,idempotency_key)` 让 INSERT 成为数据库同步点，再 `SELECT ... FOR UPDATE` 读取赢家；候选订单号判定是否由当前事务占位。相同用户/键/SKU 重放返回原订单；不同 SKU 是 409。见 `OrderService.create`、`OrderMapper.xml`、Stage2 测试 `parallelSameUserReplayAndParameterConflictAreSerializedByMysqlUniqueKey`。

### 10. 为什么订单中有 price snapshot？

订单明细保存成交时价格和数量，避免后续 SKU 目录变化影响历史交易显示/履约。金额是 BIGINT 分，quantity 固定 1；业务层和 schema 约束一起保证。见 `OrderItemEntity`、`OrderDtos.OrderItemView` 和 V2 SQL。

### 11. 支付与超时关单同时发生时谁获胜？

两者都先锁同一个订单行，然后只能从 PENDING_PAYMENT 条件转换。获得锁并成功提交的一方决定终态：支付将订单设 PAID 并签票；关闭设 CLOSED 并释放一张库存。另一方基于新状态拒绝或幂等返回，不会同时成功。见 `OrderService.pay/cancel/closeExpired`、`OrderMapper.xml` 和 Stage2 测试 `orderExpirationIsCappedAtSessionStartAndHttpPayCancelRaceHasOneTerminalWinner`。

### 12. 事务异常怎么避免“订单有了但库存没扣”或相反？

订单占位、预占、明细和 Outbox 在同一事务；任何业务异常或 SQL 异常都会回滚。支付状态、票据和 Outbox 同事务；关闭状态、放库存和 Outbox 同事务。真实 trigger 故障测试也验证 Outbox 插入失败时业务回滚。见 Stage3 测试 `outboxWritesRollbackWithOrdersAndMandatoryReturnKeepsUnroutableEventPending` 与 `paidAndClosedOutboxFailuresRollbackTicketsAndStockAndReplaysCreateNoEvents`。

### 13. MySQL 锁是怎么排序的？

创建先锁幂等订单键，再按 event SHARE、session SHARE、SKU UPDATE 顺序预占。终态变更从 order UPDATE 锁起，再写票据或库存。核销先按哈希找到票，锁票行后复核当前 hash，不额外锁不可逆 PAID 订单，降低反向锁序风险。见 ADR 0002、`InventoryService.reserve`、`TicketService.checkIn` 和对应 Mapper XML。

### 14. 为什么不把过期支付顺带关单？

支付 API 发现 `expiresAt <= now` 就返回 409，不隐式做另一个副作用；独立扫描器和延迟消息共同触发关单检查。消息/扫描都只提供触发，MySQL 锁、状态和 expiresAt 仍作最终裁决。见 `OrderService.pay/closeExpired` 与 `OrderExpiryMessageHandler`。

### 15. 核销码怎样避免明文泄露和重复入场？

使用 SecureRandom 生成256 bit随机值，URL-safe Base64无填充后43字符；只保存 SHA-256 摘要，旋转后原码失效。核销时锁票并重查摘要，再检查票未使用、订单 PAID、操作者归属/ADMIN、核销时间窗；条件改 USED 并插入 ticket_id 唯一的成功记录。并发请求只有一个成功。见 `TicketService`、`TicketMapper.xml`、`checkinCodeIsHighEntropyHashedRotatesAndHonorsOwnershipWindowAndOneTimeUse`。

## Redis、RabbitMQ 与性能边界

### 16. 缓存一致性是怎样处理的？

Cache Aside 只存已发布活动的稳定元数据；详情库存每次从 MySQL 批量读取。写发布事务提交后推进 generation；Lua CAS 防止旧请求回填覆盖新一代。Redis 失败时公开目录回源 MySQL；失效异常时，目录元数据可能在 TTL 加抖动期内陈旧。下单始终在 MySQL 条件扣减。见 `RedisPublishedEventCache`、`EventService`、`cacheAsideUsesLiveMysqlStockInvalidatesAfterCommitAndDoesNotCacheMisses`。

### 17. Redis 限流为什么 fail-open？

该层目标是软性保护请求量，Redis 中断时 fail-open 避免全站预约被限流服务故障拖垮；代价是故障期间请求保护降低。库存、幂等和状态正确性不依赖 Redis。429 是 `RATE_LIMIT_EXCEEDED`，不能误认为售罄。见 `OrderRequestRateLimiter` 和 `redisRateLimitIsAtomicPerUserAndCacheAndOrderFallbackRecoverAfterPause`。

### 18. Outbox 解决了什么，又没有解决什么？

Outbox 行和业务状态同事务提交，避免先提交 DB 后因应用崩溃漏掉事件；publisher 在事务外发送并等 confirm/return，再标 SENT 或退避。它没有构成 DB+MQ 原子事务；发送后标记前崩溃会重复，因此消息为至少一次、消费需幂等。见 `OutboxRecorder`、`OutboxPublisher`、`ConfirmedMessagePublisher`、ADR 0003。

### 19. 消费者为什么先提交 DB 再 ack？

否则 broker ack 后如果 DB 回滚，消息就丢失。先提交后 ack 若进程崩溃会重投；`(event_id,consumer_name)` 去重键和通知 eventId 唯一约束令业务效果幂等。不能声称 exactly-once。见 `NotificationMessageHandler`、`ConsumerDeduplicationService` 和 `paidNotificationsAreDurableIdempotentOwnerScopedAndSurviveListenerRestart`。

### 20. Rabbit TTL/DLX 能保证准时关单吗？

不能。TTL/DLX 可能队头阻塞或因消费者/服务停机而延迟；消息仅触发检查。MySQL `expires_at` 与订单状态负责最终判定，周期扫描承担兜底；失败订单有持久退避，避免固定前100个坏记录长期饿死后续健康记录。见 `OrderExpiryMessageHandler`、`OrderExpiryScanner` 和 `scannerContinuesPastOneHundredPoisonOrdersWhileRabbitIsStoppedAndBacksOffFailures`。

### 21. 1000 用户并发结果能说明系统每秒支持多少请求吗？

不能。该测试报告完成的并发工作量和库存不变量，线程池/barrer下的 HTTP 结果不是吞吐基准。读路径的 p50/p95 只能引用 Stage4 `benchmarks/results` 原始数据，并同时给出硬件、请求规模、并发、缓存冷/热定义、错误率和执行命令；实验不外推到生产负载。

### 22. 项目有哪些明确的非目标？

没有真实支付/退款、微服务、Redis 库存权威锁、强一致缓存、端到端 exactly-once、Broker 多节点高可用或生产容量承诺。单节点 Compose 适合学习和本地复现，不等同灾备或生产基线。见 [项目章程](../00-project-charter.md)、[Stage3 ADR](../decisions/0003-stage-3-reliability.md) 和 [阶段交接](../handoffs/stage-4.md)。
