# Codex 执行任务书 — Stage 2（已授权）

> Stage 1: PR #2 已经审核 ACCEPT 并合并（merge commit `0aa4a4d73995efd537c010cc2edd6a798fd3f0a4`）。本文件是 **Stage 2 唯一当前授权实现任务**；Stage 3 的 Redis/RabbitMQ 与 Stage 4 的 Vue 暂不开发。
>
> 目标：基于已验收的 Java 21 / Spring Boot 4.1.1 / MyBatis / MySQL 8.4 单体，实现**可以证明并发正确性**的票务预约、模拟支付、过期关单、电子票及入场核销业务闭环。

## 0. 阅读与实施规则

先拉最新 main，阅读 `AGENTS.md`、`docs/00-project-charter.md`、`docs/01-architecture-and-code-style.md`、`docs/02-domain-and-api-contract.md`、`docs/03-stage-roadmap-and-gates.md`、`docs/handoffs/stage-1.md`、`docs/decisions/0001-stage-1-security-and-persistence.md`。先确认 PR #2 已在 main，才建立 `codex/stage-2-order-lifecycle` 分支。自行实现、调试、测试和写文档，最后只开一个 Stage 2 PR。**不合并 PR，不开始 Stage 3。**

固定选型：继续复用 Java 21.0.9、现有 Maven/Wrapper，使用原 MySQL 8.4 专属 Compose/独立 Testcontainers；不可影响宿主机 MySQL 5.7 及其 3306、默认 Java 17。增量 MySQL 迁移必须使用 Flyway V2（不可篡改已经发布的 V1）。不引入 Spring Cloud、JPA、MyBatis-Plus、Redis、RabbitMQ、Vue、真实支付网关。

## 1. 业务范围与业务不变量（P0）

用户路径：浏览已发布活动与未开始场次 → 选择票种 → 带幂等键预约**一张票** → 原子预占票量并生成待付款订单 → 模拟支付 → 获得电子票 → 主办方扫描核销。未支付订单超时或用户主动取消时，订单关闭并释放库存**恰好一次**。

必须始终满足以下不变量：
1. 对每个 SKU：`0 <= available_stock <= total_stock`。数据库事务/原子 SQL 是最终真值，不能先读库存再无条件写回；无库存必定无新订单。
2. 每个订单初期固定只包含一个 SKU、一张票；`PENDING_PAYMENT -> PAID|CLOSED`，终态不可逆。成功支付最多生成一张有效票。
3. 同一用户 + 同一 idempotencyKey + 相同业务参数，重复请求**返回同一订单**，不再次扣减；同 key 不同参数返回 409。不同用户的 key 不相互冲突。
4. 支付、超时关单、手工取消相互竞争时只能有一个终态生效；只有由待付款转为关闭的事务释放一次库存；支付成功不能在关闭后发生。
5. 每张已支付的有效票最多成功核销一次；核销员只能核销自己活动下的票（ADMIN 可越级），用户不能调用核销接口；不能凭仅知道自增 ticket ID 伪造核销资格。
6. 禁止替他人看订单/电子票、付款或取消；不存在/不属于自己统一 404，不泄露订单是否存在。
7. 错误必须保持现有统一 JSON 格式 `{code,message,requestId,timestamp}`；输入 400、未授权 401、无角色 403、隐藏资源 404、重复/非法业务状态 409。

## 2. 持久化与模块边界

### 数据库迁移 V2（必须）

在新 Flyway V2 创建/补充：
- `orders`：id，业务订单号 `order_no` UNIQUE；user_id；status (PENDING_PAYMENT/PAID/CLOSED)；总价（整数分）；expires_at、paid_at、closed_at、created_at、updated_at；`idempotency_key` 与必要的请求语义字段；UNIQUE(user_id,idempotency_key)。
- `order_items`：id、order_id、sku_id、price_cents **下单快照**、quantity 固定 1；必要 FK、约束。现阶段每个订单一个 item，应在数据约束/业务层双重保证。
- `tickets`：id、order_item_id 唯一、owner_user_id、有效核销凭据的不可逆 hash 或合理替代方案、status (UNUSED/USED)、used_at/created_at；必要索引和约束。
- `checkin_records`：id、ticket_id、operator_user_id、result、created_at；用于追溯核销尝试或结果。

索引须覆盖：用户订单查询、过期待关单扫描、唯一幂等键、按票据凭据定位以及核销记录。明确 FK、状态约束、金额/数量非负及库存上限。完整性检查需在真实 MySQL 8.4 上执行，不使用 H2。

### 包/服务

遵守已有按业务边界分包方式，建议 `order/{web,application,domain,infrastructure}` 和 `ticket/{...}`。订单模块**不能直接调用 event 模块 MyBatis Mapper**；通过 event application 下明确的库存业务服务或接口调用。新业务事务边界在 application service，单次本地 DB 事务协调订单 + 库存 + 票务写入；跨业务包允许调用公共应用服务，避免循环依赖和万能 Util/Manager 类。MyBatis Mapper XML 使用显式 SQL；禁止 SQL 拼接/Java 内存锁作为正确性最终保证。

订单超时不依赖 MQ（留待 S3）；本阶段采用**数据库过期扫描 + Spring 定时任务**，并暴露可在集成测试中直接调用的事务级关闭用例。计划任务的发起方法与事务方法不能依赖 `this.method()` 自调用产生 `@Transactional` 代理失效。

## 3. 具体 API（契约必须记录到 docs/02）

使用已有 JWT / 角色和 `/api/v1` 前缀。建议固定下述 Stage 2 契约；如实现需要微调命名，应先在本 PR 的契约文档解释**语义不变**的差异。

- `POST /api/v1/orders` — USER（ADMIN 是否可下单请固定并测试）：JSON `{skuId, idempotencyKey}`，创建待付款订单，首次 201；同参数重放返回同一单（可以 200），不同参数冲突 409；库存不足/停售/已开场返回 409。key 采用合理长度与格式校验，禁止空白和超长值。
- `GET /api/v1/orders?page=0&size=20` — 登录用户的本人订单列表，SQL 真分页。
- `GET /api/v1/orders/{id}` — 本人的订单及关联条目/电子票状态。
- `POST /api/v1/orders/{id}/pay` — **模拟支付**，必须是本人且未过期的 PENDING_PAYMENT；成功转 PAID，产生唯一电子票。不可访问真实支付服务。重复支付按契约稳定返回 200 同一已支付订单或 409；但不能创建第二张票。
- `POST /api/v1/orders/{id}/cancel` — 本人取消待付款订单，关闭并释放库存一次；重复取消只能返回同一终态或 409，不得重复释放。
- `GET /api/v1/me/tickets?page=0&size=20` — 本人有效票/使用状态。
- `POST /api/v1/me/tickets/{id}/checkin-code` — 本人（仅订单已支付且票未使用）生成/轮换随机高熵核销码，返回**仅一次可见**的明文码，持久化仅保存 hash；旧码轮换后失效。这样前端 Stage 4 可按需生成可展示的 QR，而不是依赖首次支付响应长期保存明文。若采用等效可验证的短期签名码方式，必须说明安全与取回语义并测试。
- `POST /api/v1/organizer/tickets/check-in` — ORGANIZER/ADMIN，JSON `{checkinCode}`；在可信服务端时间的场次核销窗口内验证票据、PAID 订单、所属活动主办方/ADMIN、UNUSED 状态；并发同码只能一单成功；写入核销记录。

核销窗口给出清晰常量（如场次开始前 2 小时至结束后 1 小时），使用可注入的 `Clock`，文档说明时间口径和在该窗口外的行为。若入场口令泄漏、重复扫描、旧核销码、跨主办方扫码，必须有安全拒绝。不得通过日志或错误响应泄露完整核销码。

事件详情可保留 Stage 1 的公共只读接口；已发布活动仍不允许新增票种/场次或随意增加库存，避免跨阶段引入运营编辑规则。

## 4. 必须重点设计的并发/可靠性

### 库存原子预占

下单事务内验证 SKU 所属活动已发布、场次尚未开始且票量充足。最终扣减必须是受条件限制的 SQL，例如 `UPDATE ticket_skus SET available_stock=available_stock-1 ... WHERE id=? AND available_stock>=1` 并检查受影响行数。初始无库存或已开始的场次不能扣减。若与发布状态或时间存在 TOCTOU 风险，使用同事务里的必要行锁和重新校验消除竞态。任何事务异常应回滚已预占库存和订单。

### 幂等

以唯一约束 `(user_id,idempotency_key)` 实现竞争的最终序列化；重复提交不能靠先 SELECT 再 INSERT。对重复键的冲突与**同 key 不同参数**给出明确响应；考虑首次请求尚未提交、DB 异常回滚后重试、超时关单后原 key 再次重放。幂等语义不能因请求次数改变库存。

### 支付/关单竞态

用订单行锁或条件状态更新来保证排他终态，并在同个事务内关联库存释放/票据生成。订单过期以 `expires_at` + 数据库持久状态判定；同一到期订单在重复扫描、手动取消、支付竞争中不会重复释放。定时任务可在 MySQL 短时不可用后重扫补偿未处理订单，不丢事件。S3 再加入 MQ，不要在本轮模拟消息可靠性。

### 核销码与核销

生成至少 128 bit 随机码（例如 SecureRandom）；只存储 SHA-256 或更强 hash，查询和对比时只接收服务端持有/验真的凭据；票核销用数据库条件更新或行锁，只有成功状态切换的一次算成功。核销日志应明确成功/失败写入边界，不能破坏已用票的不可重复性。

## 5. 测试与 Gate（不能仅声明通过）

在**真实 MySQL 8.4 Testcontainers + 真实 HTTP/security filter**上验证，CI 不跳过：
1. 对库存=100 的同一 SKU，**1000 个不同用户**发起 1000 次并发预约（线程池合理控制实际并行度，全部请求确实完成）；成功订单数必须 =100 或解释因环境故障而少于 100 的异常，始终 **<=100**；数据库剩余票量、待付款订单计数吻合且 >=0。不可用 “1000 个同一 key” 冒充不同用户。
2. 相同用户/相同 key 的并发重放，只创建/扣减一次；不同参数重用同 key 返回 409；首个事务回滚后同 key 可以重新成功。
3. 库存不足、活动草稿、场次开始、非法 sku、无 token、USER/ORGANIZER 权限边界、IDOR（本人列表/详情/支付/取消/票码）等真实鉴权测试。
4. 重复支付只能有一张票；重复关闭只释放一次；手动取消与到期关单竞争，**模拟支付和关单竞态**不可既支付成功又释放库存；订单/库存/票状态一致。
5. 过期补偿扫描（服务/测试中以可控时钟或直接赋予过去 expires_at 模拟，无随机睡眠），重复扫描可安全重试；关单后的幂等重放不会再次预占。
6. 不同主办方互相核销失败，错误核销码/轮换后的旧码/未支付票/使用过的票/核销窗口外拒绝；同张票并发核销只有一个成功，核销记录可追溯。
7. Flyway V1→V2 数据库升级，已有 Stage 1 API/13 项测试**全部保持通过**；新增真实 SQL 约束和索引验证。
8. 格式检查、Maven `verify`、GitHub Actions Linux JDK 21 + Testcontainers 通过；保留测试命令、真实测试数量和失败/跳过数字。不能自行声称 QPS 或性能提升。

测试多线程失败时，要给出真实异常与可复现的结果，不把网络错误隐藏为“库存不足”，也不要为了 CI 绿色降低不变量断言力度。允许拆分 Stage 1 的超长集成测试类到按领域组织的测试套件，保留测试行为覆盖。

## 6. 质量、文档与审查

- 与 Stage 1 一致：Java 21、构造器注入、DTO records、Spring Security、Bean Validation、精确 HTTP 状态、统一错误、安全配置、Flyway SQL、MyBatis XML、Spotless、Maven Wrapper；禁止 Lombok/JPA/无理由的抽象，注释重点解释 WHY、锁顺序及事务边界。
- 将 S2 API、状态图、订单/库存/票据数据关系更新到 `docs/02-domain-and-api-contract.md`；补充 ADR（幂等、锁序、支付/关单/核销、定时补偿方案）；README 加入实际演示和测试步骤。
- **新增 `docs/handoffs/stage-2.md`**，使用 `docs/05-handoff-template.md`：功能矩阵、所有关键类路径、迁移文件、实际环境/构建命令、CI 运行、并发结果（含数据和执行方式）、可能的 P0/P1/P2 问题、未验证内容，清楚说明不在范围的 Redis/MQ/Vue。
- 可新建 `docs/learning/stage-2-key-flows.md`（推荐）：下单事务、关闭事务、支付与关单竞态、核销事务四张简洁调用链/状态图与类路径，帮助后续用户面试学习。
- Codex 在 `codex/stage-2-order-lifecycle` 推送并开 **一个 PR**，建议标题 `[Stage 2] Orders, inventory, simulated payment and ticket check-in`。阶段内自行修复问题到完整交付，再请求一次集中审核。
- **停止条件**：PR 创建并提供 URL + Head SHA + CI 与并发测试实测摘要；不合并 PR，不开始 Stage 3。如存在阻断无法自行解决，真实披露并停在本阶段。
