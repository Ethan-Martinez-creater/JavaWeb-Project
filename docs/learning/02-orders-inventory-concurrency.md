# 02｜订单、库存与并发

本系统把 MySQL 作为预约、支付、关闭和核销状态的最终判断者。测试结果证明指定场景和不变量经过验证，不是对任意硬件或流量的容量承诺。

## 预约事务从哪里开始

`OrderController.create` 先读取已验签 JWT 的 subject 作为 userId，再经过 `OrderRequestRateLimiter.check`；客户端不能指定归属人。`OrderService.create` 开启本地 MySQL 事务，先为 `(user_id,idempotency_key)` 做唯一键占位：

```sql
INSERT INTO orders (...)
VALUES (...)
ON DUPLICATE KEY UPDATE id = id
```

再用 `SELECT ... WHERE user_id = ? AND idempotency_key = ? FOR UPDATE` 找到唯一订单。首次请求判断新占位归当前事务；同键同 SKU 返回已存订单（包括 PAID/CLOSED），同键不同 SKU 返回 `409 IDEMPOTENCY_CONFLICT`。首次请求之后任意一步失败，订单占位、库存、明细和 Outbox 同事务回滚，原键可重试。

UI 必须给一次用户操作生成一个 8–64 位 ASCII `[A-Za-z0-9_-]` key。网络结果不确定时重试要继续用原 key；不能用新的 key 把超时请求转为第二单。键应区分大小写。服务端给 `POST /orders` 新单返回 201，重放返回 200。

## 库存锁序与条件更新

`OrderService.create` 调用 event 模块的 `InventoryService.reserve`，不直接调它的 Mapper。顺序为：非锁定定位 SKU 的活动/场次 → 活动 `FOR SHARE` → 场次 `FOR SHARE` → SKU `FOR UPDATE` → 在锁等待之后重读可信 Clock → 校验活动发布、场次未开始 → 条件扣减。Mapper XML 的最终 SQL：

```sql
UPDATE ticket_skus
SET available_stock = available_stock - 1, updated_at = UTC_TIMESTAMP(6)
WHERE id = ? AND available_stock >= 1
```

影响行数不是 1 时预约失败。event/session 的共享锁与活动编辑/发布的父行排它锁协调；SKU 排它锁令并行扣减串行化。事务随后保存成交时的 `price_cents` 快照和 `quantity=1`，过期时间是 `min(reservedAt + 15 分钟, session.startsAt)`。数据库 CHECK、唯一键和外键继续兜底；Redis 库存缓存不会授权售票。

实现位置：[`OrderService.create`](../../backend/src/main/java/com/eventflow/order/application/OrderService.java)、[`InventoryService.reserve`](../../backend/src/main/java/com/eventflow/event/application/InventoryService.java)、[`InventoryMapper.xml`](../../backend/src/main/resources/mapper/event/InventoryMapper.xml)、[`V2`](../../backend/src/main/resources/db/migration/V2__orders_and_tickets.sql)。更完整的锁序动机见 [ADR 0002](../decisions/0002-stage-2-order-consistency.md)。

## 幂等、回滚和终态竞态

支付、用户取消、到期关单都先 `SELECT ... FOR UPDATE` 锁订单，再按 `PENDING_PAYMENT` 条件改状态。只有将 PENDING_PAYMENT 改为 CLOSED 的事务成功后才释放一张库存；PAID 事务内签发一张票。先持锁并提交者决定终态：支付先提交时关单不释放库存；关闭先提交时支付返回 409。过期支付直接返回 409，不顺便执行关单。

事件也与该数据库事务一起写 Outbox。失败回滚订单、库存、票据与 Outbox。后续异步发送只在数据库提交后进行，见 [可靠性导读](03-redis-rabbitmq-reliability.md)。

## 票码轮换与核销

每个 PAID 订单最多签一张电子票。票主请求 `POST /me/tickets/{id}/checkin-code` 时服务生成 256 bit 随机码，以 43 字符无填充 Base64 URL-safe 格式返回，并只将 SHA-256 摘要存入 MySQL。明文只出现在该次成功响应，旧码在新码提交时立即失效。前端需按需轮换并只在当前内存 UI/二维码中使用；不要把码写进日志、URL、截图或持久存储。

核销端先校验格式，按摘要定位票，再锁票行并复核当前摘要，核对 PAID、UNUSED、活动主办方/ADMIN 和 `[startsAt−2h, endsAt+1h]` 含边界的时间窗；最后条件更新 USED 并插入唯一 SUCCESS 记录。同一张票并发核销只有一个事务能成功。失败核销不会写成功记录，也不会回显码。

## 可以直接读的真实测试

在 [`Stage2IntegrationTest.java`](../../backend/src/test/java/com/eventflow/Stage2IntegrationTest.java) 中重点看：

- `parallelSameUserReplayAndParameterConflictAreSerializedByMysqlUniqueKey`：并发相同 key 和不同参数复用。
- `transactionRollbackAllowsRetryWithSameIdempotencyKey`：事务失败回滚后复用原 key。
- `paymentExpiryAndScannerUseInclusiveClockBoundaryAndAreRetryable` 和 `orderExpirationIsCappedAtSessionStartAndHttpPayCancelRaceHasOneTerminalWinner`：到期边界、起始时间上限与支付/关闭唯一终态。
- `expiryScannerAndConcurrentManualCancelReleaseAnExpiredReservationOnce`：扫描与取消不会重复放库存。
- `checkinCodeIsHighEntropyHashedRotatesAndHonorsOwnershipWindowAndOneTimeUse` 和 `checkinWindowIncludesEndPlusOneHourAndRejectsUnpaidTicketsAndUsedCodes`：码摘要、失效、权限、时间窗和一次性核销。
- `thousandDistinctUsersAndKeysOverRealHttpNeverOversellAndWriteSanitizedArtifact`：真实鉴权 HTTP、1000 个不同用户和 key、库存 100 下最多 100 成功。

最终 Stage 3 交接中的本机与 CI 原始脱敏并发记录分别为 [`stage-3-concurrency-local.json`](../handoffs/stage-3-concurrency-local.json) 与 [`stage-3-concurrency-ci.json`](../handoffs/stage-3-concurrency-ci.json)：两次结果均为 100 个 201、900 个 SOLD_OUT 409、剩余库存 0；数据只说明该实验断言，不是 QPS。Stage 4 若再次运行写压力或读基准，使用 [benchmark 说明/原始结果](../../benchmarks/README.md) 与 `benchmarks/results/` 当前实际提交的文件。

## 哪些证据支撑“不超卖”

先看数据库最终条件 `available_stock >= 1` 和同事务订单明细，再看 SQL 行锁/唯一键及 CHECK，最后看 1000 请求真实 HTTP 测试验证这组约束在竞争下的行为。单独的响应计数不够：测试还要核对待付订单数、明细数与 SKU 最终库存。即便压力测试通过，也不能推出任意机器上的极限吞吐、无延迟或永不超时。
