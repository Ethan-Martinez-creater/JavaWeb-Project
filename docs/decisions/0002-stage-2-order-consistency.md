# ADR 0002：Stage 2 订单幂等、库存锁序与票务一致性

- 状态：Stage 2 实现中的设计决策；具体验证结果以 `docs/handoffs/stage-2.md` 为准。
- 范围：单 SKU 单票订单、MySQL 库存预占、模拟支付、到期补偿、电子票与入场核销。不引入 Redis、RabbitMQ、真实支付网关或多 SKU 订单。

## 背景

订单预约会同时写入幂等订单、SKU 库存和订单价格快照。支付、用户取消与超时关单会竞争同一待付款订单；成功支付必须生成唯一票据，关闭只能释放一次库存。核销码需要可轮换且不能在数据库中以明文保存。MySQL 是持久状态的唯一最终判定者。

## 决策

### 幂等行先于库存锁

以 `UNIQUE(user_id,idempotency_key)` 作为竞争请求的数据库串行点。键只接受 ASCII `[A-Za-z0-9_-]{8,64}`，列使用二进制排序，保持大小写敏感。首次请求生成候选 32 位 UUID 十六进制 orderNo，以 `INSERT ... ON DUPLICATE KEY UPDATE id=id` 插入占位，再以 `SELECT ... FOR UPDATE` 读取该用户的键行；不使用 SELECT-then-INSERT。

若候选 orderNo 与锁定行的 orderNo 相同，当前事务是占位拥有者；只有其库存预占、订单金额/到期时间和单条明细都完成后才允许提交。否则请求为重放：SKU 相同则返回现存订单，状态可以是 PENDING_PAYMENT、PAID 或 CLOSED；SKU 不同则 409。候选占位、库存变更及明细处在同一事务，任何首次处理失败都会回滚，让相同键可重试。关闭订单的旧键仍指向旧订单，不创建替代订单。

不先查键再插入，因为首次并发请求都可能看到不存在。候选 orderNo 用于区分本事务新插入与已存在的唯一键，不将 UUID 本身误当幂等保证。MySQL 8.4 对重复键 `ON DUPLICATE KEY UPDATE` 的行锁和相关外键锁行为见[官方 InnoDB 锁文档](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)。

### 请求 SKU 不加提前外键

`orders.requested_sku_id` 保存幂等请求语义，不设 `ticket_skus` 外键；`order_items.sku_id` 保留实际成交 SKU 的 FK。下单必须先获得幂等行锁，之后才按固定顺序取得 event/session/SKU 锁。若在占位订单处通过外键提前拿 SKU 共享锁，并发订单可能在共享锁升级为 SKU 排它锁时互相等待。首次事务失败时占位也会回滚，因此不会提交孤立的无明细订单。

### 库存使用可排序的数据库锁与条件更新

订单模块只调用 event application 的 `InventoryService`，不直接访问其 Mapper。其事务内先用非锁定读取定位 event/session，再依序锁 `events FOR SHARE`、`event_sessions FOR SHARE`、`ticket_skus FOR UPDATE`。父活动共享锁彼此兼容，允许独立 SKU 预约互相并行；它与 Stage 1 编辑/发布持有的活动行排它锁互斥，从而建立数据库串行边界。拿到锁后重新读取注入的 Clock，并确认活动仍为 PUBLISHED、场次仍未开始。

最后通过 `available_stock >= 1` 条件 UPDATE 扣减，并检查受影响行数；库存不足返回业务冲突。下单在同一事务中写入价格快照、数量 1 与订单有效期。过期时间为 `min(reservedAt + 15 minutes, session.startsAt)`。应用进程内的锁、先查后无条件写或“测试没有超卖”都不能替代数据库条件。

### 订单行锁决定唯一终态

支付、取消和到期关单都先 `SELECT ... FOR UPDATE` 锁订单行，然后以 `status=PENDING_PAYMENT` 条件更新。支付在未到期时转为 PAID，并在同一事务以 MANDATORY propagation 创建票据；取消/超时转为 CLOSED 后在该事务释放一张库存。只有成功转换的关闭操作才释放；重复取消或扫描看到 CLOSED 时不再释放。PAID/CLOSED 不可逆。

锁先到的一方决定结果：支付提交后，关闭方看到 PAID 并不释放；关闭提交后，支付方看到 CLOSED 并返回 409。支付请求发现订单已过期时直接返回 409，不承担关单副作用；持久化扫描器执行补偿，避免支付异常把到期订单留作“假成功”。

### 到期扫描保持可重试

`OrderExpiryScanner` 通过 `(status,expires_at,id)` 索引每轮读取最多 100 个候选，每 30 秒调度一次。扫描器本身不持有跨批次事务；它逐单经 Spring 代理调用 `OrderService.closeExpired`，每单一个事务。失败记录 orderId 与异常类型，失败项保持待处理且下轮重新查询。这样避免 `this.closeExpired()` 自调用绕过代理导致事务边界失效；Spring 官方说明了代理模式下仅通过代理进入的调用会被拦截，参见[声明式事务注解文档](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)。本阶段不依赖尚未授权的 MQ。调度可通过 `app.orders.expiry-scan-enabled=false` 关闭。

### 票据凭据只保存摘要

订单支付事务签发唯一票据；票据明细唯一约束保证最多一张。只有票主可为已支付且 UNUSED 的票轮换核销码。生成 256 bit 随机值并以无填充 Base64 URL-safe 编码；接口只在当前响应返回 43 字符明文，数据库保存 SHA-256 十六进制摘要。旧摘要在轮换提交后失效，票据/订单查询不返回明文或摘要。

核销先以摘要定位候选票，再锁票行并重新核对当前摘要，随后验证 PAID、UNUSED、主办方归属或 ADMIN 角色以及核销窗口，最后把票改为 USED 并插入 SUCCESS 记录后提交。窗口为场次开始前 2 小时到结束后 1 小时，边界包含在内。PAID 是不可逆状态，因此资格读取不额外锁订单，避免订单与票行形成相反锁序。失败尝试不持久化到 checkin_records；这是明确的审计边界，错误和日志不得含完整核销码。

## 替代方案与后果

- 不使用“先 SELECT 再 INSERT”的应用层幂等，不依赖仅生成 UUID。
- 不在本轮实现多 SKU/多票、预授权/真实支付、订单状态事件、Redis 锁、MQ 超时消息或失败核销审计记录。
- 订单号唯一索引、用户+键唯一索引、order item 单条唯一约束、ticket item 唯一约束和条件状态 SQL 共同作为数据库防线；应用层负责可读的 404/409 语义。
- `requested_sku_id` 的 FK 被有意省略，读者可从 order item 的 SKU 外键验证实际成交关系。若未来允许订单明细删除、保留失败草稿或回填请求数据，需重新评估约束。
- 失败核销不会形成持久审计事件；若合规审计需要追踪拒绝尝试，应另行设计脱敏、保留期、限流与隐私边界，不能把明文码写入日志。
- 正确性与吞吐量分开验证；并发测试报告只说明所测环境与断言结果，不构成容量或 QPS 声明。
