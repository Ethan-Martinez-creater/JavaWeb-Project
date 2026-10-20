# 领域模型与 REST API 契约（Stage 1/2）

本文件定义各阶段共有的业务约束。Stage 1 已验收；Stage 2 是当前授权范围，Stage 3/4 仅作设计预留。

## 1. 角色与边界

- USER：自行注册、登录、浏览发布活动；S2 才下单/支付/查看本人订单/核销个人票据。
- ORGANIZER：管理**本人创建**活动、场次、票种；不可操作别人的活动。
- ADMIN：保留最小管理权限。S2 可创建订单，但订单/本人票据接口仍严格限定本人；ADMIN 可跨主办方核销，不能据此越权查看或操作他人订单、票据。
- 创建者或当前登录用户由认证身份确定，客户端提供的所有者 ID 不可信。
- 公开信息只展示已发布内容，私有草稿不可通过列表、详情、关联资源泄露。

## 2. 数据模型（逐阶段迁移）

### Stage 1
- users: id BIGINT PK, email VARCHAR(254) UNIQUE NOT NULL, password_hash, status (ACTIVE/DISABLED), created_at, updated_at。
- user_roles: user_id FK, role (USER/ORGANIZER/ADMIN), UNIQUE(user_id,role)。
- events: id BIGINT PK, organizer_id FK users.id, title VARCHAR(120), description TEXT, status (DRAFT/PUBLISHED/CANCELLED), created_at, updated_at。
- event_sessions: id BIGINT PK, event_id FK, title VARCHAR(120), starts_at UTC DATETIME(6), ends_at UTC DATETIME(6), location VARCHAR(160), created_at, updated_at。
- ticket_skus: id BIGINT PK, session_id FK, name VARCHAR(100), price_cents BIGINT >= 0, total_stock INT >= 0, available_stock INT >= 0, created_at, updated_at；UNIQUE(session_id,name)。
- 关键关系：users 1-N events；events 1-N event_sessions；event_sessions 1-N ticket_skus。
- 对已发布活动的场次、价格、库存等影响履约字段，在 S1 直接禁止编辑；后续放宽必须另立规则。

### Stage 2（V2 订单与票据）
- V2 不改写已发布的 V1；完整字段、约束、状态与幂等语义见 §5。每个订单固定一个 SKU、一张票。

### Stage 3（只做设计预留）
- outbox_events: 事务内事件记录、发布状态、重试信息、唯一事件 ID。
- consumed_messages 或等价持久化幂等记录；辅以订单状态条件更新。
- RabbitMQ 负责超时/通知异步事件；周期扫描提供漏消息补偿；MySQL 最终判定订单状态和库存。

## 3. 活动状态规则

- 新建为 DRAFT。
- DRAFT → PUBLISHED 需拥有至少一个未来开始的有效场次，每个场次至少一个合法票种；仅活动所有者或 ADMIN 可发布。
- 活动 PUBLISHED → CANCELLED 的规则仍需单独授权；S2 只包含订单取消，不提供活动取消接口。
- 不允许客户端直接提交/修改状态字符串。
- 时间校验：startsAt < endsAt，发布前至少一场尚未开始的场次；所有场次时间均需严格校验。

## 4. Stage 1 API（必须实现）

全部 JSON 字段 camelCase；错误和分页结构见 docs/01-architecture-and-code-style.md。

认证：
- POST /api/v1/auth/register 公开。请求 {email,password}，固定分配 USER；201，响应用户 id/email/roles；重复 email → 409；密码最少 10 个 Unicode 字符，最多 72 个 UTF-8 字节（BCrypt 上限）。邮箱去除首尾空白后按 Locale.ROOT 转小写。
- POST /api/v1/auth/login 公开。请求 {email,password}；200，响应 {accessToken,tokenType:"Bearer",expiresInSeconds}；错误帐号或密码统一 401。
- GET /api/v1/auth/me 需登录。响应 {id,email,roles}。
- 禁止公开注册设置 role、userId、passwordHash；未知字段不应产生权限提升。

公开活动：
- GET /api/v1/events?keyword=&page=0&size=20 → 200，分页 {items,page,size,total}，只返回 PUBLISHED；标题关键字匹配需安全参数化。
- GET /api/v1/events/{id} → 200，公开的已发布活动详情（含场次和票种）；草稿/不存在 → 404。

主办方：
- GET /api/v1/organizer/events?page=0&size=20 → 200，仅本人活动。
- POST /api/v1/organizer/events，输入 {title,description} → 201 DRAFT 活动；ORGANIZER 或 ADMIN。
- PATCH /api/v1/organizer/events/{id}，修改 DRAFT 的 title/description；只有本人或 ADMIN。
- POST /api/v1/organizer/events/{eventId}/sessions，输入 {title,startsAt,endsAt,location}；仅 DRAFT，必须属于本人；201。
- POST /api/v1/organizer/sessions/{sessionId}/ticket-skus，输入 {name,priceCents,totalStock}；仅相关活动 DRAFT 且本人拥有；201，availableStock = totalStock。
- POST /api/v1/organizer/events/{eventId}/publish；合法草稿 → 200 PUBLISHED，非法状态/缺少场次/票种 → 409，非法时间输入 → 400。
- S1 对有主办权限但不属于本人的资源统一返回 404（与不存在相同），无主办权限返回 403；ADMIN 可按上述契约管理他人活动，但列表始终只列自己的活动。

### 统一返回

正常写接口可返回资源 DTO；不必强制每个响应包一层 success/code。POST 创建建议 HTTP 201，Location 可提供资源 URI。异常才使用 {code,message,requestId,timestamp}。

S1 实际响应字段：活动列表 items 为 {id,organizerId,title,description,status,createdAt,updatedAt}；活动详情及活动写接口在上述字段外含 sessions。场次为 {id,eventId,title,startsAt,endsAt,location,ticketSkus}，票种为 {id,sessionId,name,priceCents,totalStock,availableStock}。创建场次/票种分别返回对应 DTO。列表不返回嵌套场次，分页在 SQL 层完成。

未知 JSON 字段一律 400，不把 role、status、organizerId、availableStock 等请求外字段写入数据库。PATCH 可单独提供 title 或 description，至少一项，title 非空白；不支持通过 null 清空字段。关键字按标题的字面子串匹配，LIKE 的 %、_ 作为普通字符转义。requestId 由服务端生成并同时返回在 X-Request-Id 中。

时间一律以带 Z 的 ISO 8601 输入/输出或明确 OffsetDateTime 语义；不接受无时区的歧义时间。

## 5. Stage 2 订单、库存与票据契约

本节固定已授权的 S2 对外语义与持久化边界。测试状态、实际构建和 CI 结果以 `docs/handoffs/stage-2.md` 为准；本契约本身不代表未执行的检查已通过。

### 5.1 V2 数据与状态

V2 只能追加迁移，禁止修改已发布的 V1。主要关系如下：

- `orders`：订单号 `order_no` 为唯一 32 位小写十六进制 UUID 文本；订单归属用户、客户端幂等键、请求 SKU、状态、总价分、到期/支付/关闭/创建/更新时间均持久化。`user_id,idempotency_key` 唯一。幂等键列使用 ASCII 二进制排序，区分大小写。
- `orders.requested_sku_id` 不设 SKU 外键。幂等占位必须先插入，随后按用户/键锁定；若该字段提前获得 SKU 外键共享锁，不同订单在升级为 SKU 排它锁时可能形成锁升级死锁。真正占用库存后写入的 `order_items.sku_id` 保留 SKU 外键。
- `order_items`：`order_id` 唯一，双重保证每单一条明细；保存 `price_cents` 下单快照和 `quantity=1`，数量与价格由 CHECK 约束保护。
- `tickets`：一个订单明细最多一张票；票主、UNUSED/USED 状态、使用时间持久化。初始 `checkin_code_hash` 为 NULL，轮换后只存 SHA-256 十六进制摘要，唯一且按 ASCII 二进制比较。
- `checkin_records`：仅在核销成功的事务内写入 `SUCCESS` 记录；`ticket_id` 唯一，保证每张票至多一次成功记录。失败尝试不写入此表，日志也不得包含核销明文码。
- 完整 FK、唯一索引、状态和数量约束在 `backend/src/main/resources/db/migration/V2__orders_and_tickets.sql`；数据库中的订单明细和票据记录由这些约束及同事务服务共同维持。

订单状态只允许：

| 当前状态 | 操作 | 后置状态与副作用 |
|---|---|---|
| PENDING_PAYMENT | 本人有效期内模拟支付 | PAID；同一事务签发唯一电子票 |
| PENDING_PAYMENT | 本人取消或过期补偿关闭 | CLOSED；只在该次转换成功时释放一张库存 |
| PAID | 重复支付 | 保持 PAID，返回同一订单与票 |
| CLOSED | 重复取消/过期扫描 | 保持 CLOSED，不再释放库存 |
| PAID | 取消，或 CLOSED 后支付 | 409；终态不可逆 |

票状态只允许 `UNUSED → USED`。轮换核销码不改变票状态，只替换当前摘要；旧摘要立即失效。

### 5.2 认证、所有权与 API

所有路由在 `/api/v1` 下，错误结构延续 S1 的 `{code,message,requestId,timestamp}`。分页延续 `{items,page,size,total}`，page 从 0 开始，size 默认 20、上限 100。ADMIN 的角色不会绕过个人订单/票据的所有权检查。

| 方法与路径 | 授权 | 成功语义 | 主要拒绝语义 |
|---|---|---|---|
| `POST /api/v1/orders` | USER 或 ADMIN；纯 ORGANIZER 为 403 | 首次幂等键请求 201；相同用户/相同键/相同 SKU 重放 200 并返回同一订单 | 非法请求 400；相同键换 SKU、SKU 不存在/停售/已开场或无库存 409 |
| `GET /api/v1/orders?page=0&size=20` | USER 或 ADMIN；纯 ORGANIZER 为 403 | 只分页列出当前主体的订单 | 非法分页 400 |
| `GET /api/v1/orders/{id}` | USER 或 ADMIN；纯 ORGANIZER 为 403 | 只返回本人订单、单条明细和票据视图 | 不存在或非本人 404，包括 ADMIN 查询他人 |
| `POST /api/v1/orders/{id}/pay` | USER 或 ADMIN；纯 ORGANIZER 为 403 | 本人有效期内的待付订单变为 PAID；重复支付返回同一已付订单 200 | 不存在/非本人 404；过期、已关闭或其它不可支付状态 409；过期支付不会自行关单 |
| `POST /api/v1/orders/{id}/cancel` | USER 或 ADMIN；纯 ORGANIZER 为 403 | 待付单关闭并释放一次；重复取消已关闭订单返回同一终态 200 | 不存在/非本人 404；已支付订单 409 |
| `GET /api/v1/me/tickets?page=0&size=20` | USER 或 ADMIN；纯 ORGANIZER 为 403 | 只分页列出当前用户拥有且订单已 PAID 的票据与使用状态 | 非法分页 400 |
| `POST /api/v1/me/tickets/{id}/checkin-code` | USER 或 ADMIN；纯 ORGANIZER 为 403 | 仅本人、已支付、未使用票可创建或轮换；明文只在本次 `{ticketId,checkinCode}` 响应出现 | 不存在/非本人 404；未支付或已使用 409 |
| `POST /api/v1/organizer/tickets/check-in` | ORGANIZER 或 ADMIN；USER 为 403 | 有效核销返回 200 `{ticketId,status,usedAt}`，并在同一事务记录 SUCCESS | 格式不合法或非 canonical 400；无效/旧码或跨主办方 404；未支付、已使用或不在核销窗口返回 `TICKET_CONFLICT` 409 |

下单请求 JSON 固定为 `{skuId,idempotencyKey}`。键正则为 ASCII `[A-Za-z0-9_-]{8,64}`，大小写敏感，不 trim、不改写；相同用户键如果指向不同 SKU 返回 `IDEMPOTENCY_CONFLICT` 409。首次事务若在提交前失败则订单占位、库存扣减和明细整体回滚，键可重新成功。关闭后的旧键重放仍返回原 CLOSED 订单，不创建新单也不扣库存。

`OrderView` 字段为 `{id,orderNo,status,totalPriceCents,expiresAt,paidAt,closedAt,createdAt,item,ticket}`。`item` 为 `{id,skuId,priceCents,quantity}`；待付/关闭订单的 `ticket` 为 null。`TicketView` 为 `{id,orderItemId,orderId,eventId,sessionId,skuId,status,usedAt,createdAt}`，不含核销摘要或明文码。支付成功最多生成一张票；核销码明文不会包含在订单或票分页响应中。核销成功返回 `{ticketId,status,usedAt}`，不是票据详情 DTO。

### 5.3 下单和库存的事务与锁序

订单创建不使用“先 SELECT 再 INSERT”。`OrderService.create` 先以候选 UUID 订单号执行 `INSERT ... ON DUPLICATE KEY UPDATE id=id` 抢 `user_id,idempotency_key` 唯一键，再按用户和键 `SELECT ... FOR UPDATE`。候选订单号相同才代表本次调用创建了占位行；不同则说明并发先行事务已提交，返回现存订单而不再扣库。候选行直到库存、订单总价/到期时间及明细都成功才提交。

库存由 event 模块公开的 `InventoryService` 协调，订单模块不直接调用 event Mapper。顺序为：非锁定读取 SKU 的 event/session 定位 → `events FOR SHARE` → `event_sessions FOR SHARE` → `ticket_skus FOR UPDATE` → 重新读取可信 `Clock` → 校验 PUBLISHED、场次开始时间仍大于 now → `available_stock >= 1` 条件 UPDATE。受影响行数必须为 1。订单/库存/明细处在同一本地 MySQL 事务；任何异常整体回滚。活动父记录共享锁与 S1 编辑/发布持有的活动行排它锁互斥，使预订与活动变更按数据库锁串行化。

预占后 `expiresAt=min(reservedAt+15分钟,session.startsAt)`。金额使用加锁后读取的 SKU 当前价快照。最终库存正确性来自事务、行锁和 `WHERE available_stock >= 1`，不是进程内锁，也不以压力测试成功代替 SQL 不变量。

### 5.4 支付、取消与过期补偿

支付、取消与到期关闭都先对订单行做 `FOR UPDATE`，并以条件状态 UPDATE 校验转换。`PAID` 只能由尚未过期的 `PENDING_PAYMENT` 进入；`CLOSED` 可由待付款订单经用户取消或到期补偿进入。关闭只在 `markClosed` 影响一行后释放一张票量。同一订单的并发终态竞争由这条订单行锁序列化：

- 支付先取得锁：更新为 PAID、在同一事务调用 `TicketService.issue`，提交后关闭方看到 PAID 并不释放库存。
- 取消/过期关闭先取得锁：更新 CLOSED、释放库存并提交；支付方随后读到 CLOSED 返回 409，不会签票。
- 两个事务任一失败则本地数据库写入回滚；无中间“订单已提交但库存/票据未完成”的状态。

支付使用可信 `Clock` 判定 `expiresAt > now`。已过期支付返回 409，不在支付请求里顺带关单；扫描器负责可重试补偿。`OrderExpiryScanner.scanOnce()` 用 `(status,expires_at,id)` 索引最多选 100 个到期候选，对每个 ID 单独代理调用 `OrderService.closeExpired`，每单独立事务，避免 Spring `@Transactional` 自调用失效。默认调度间隔 30 秒，可用 `app.orders.expiry-scan-enabled=false` 禁用；单单失败记录 orderId 和异常类型，后续扫描重新查询持久化的待付记录。

### 5.5 票据与核销码语义

支付事务调用 `TicketService.issue`（MANDATORY），用订单明细唯一约束保证一单至多一票。生成核销码时用 256 bit 随机值编码为 43 字符 Base64 URL-safe 字符串；只存储 SHA-256 十六进制摘要，原文只在轮换响应一次。仅票主可为已支付且 UNUSED 票创建/轮换码；旋转后旧码不再有效。除本次生成/轮换核销码的成功响应外，其它响应、错误和日志均不得输出完整明文码；调用方应把它只保存在当前内存流程或安全二维码呈现状态中。

核销时间窗为场次开始前 2 小时至结束后 1 小时，含起止边界，时间来自可注入 `Clock`。处理顺序为：验证格式、按摘要定位票、锁定票行、再核对当前摘要，随后验证订单已 PAID、票 UNUSED、操作者是活动主办方或 ADMIN、时间在窗口内；最后原子改为 USED 并写 SUCCESS 记录后提交。先按摘要找票再加锁并复核摘要，可让核销与码轮换竞争时旧码失败。PAID 不可逆，因此资格读取不额外锁订单，避免票行与订单行反向锁序。失败不写 check-in 记录；错误响应不泄漏完整码。

## 6. Stage 3 中间件规则（仅供设计）

- Redis Cache Aside 用于活动信息热点缓存（读缓存、回源、更新后失效、TTL 和缓存雪崩/穿透基本防护），库存的最终真值仍在 MySQL。
- RabbitMQ 用于异步通知和订单超时关闭；默认可使用 TTL + DLX/死信队列，不依赖可选的 delayed-message 插件。超时条件最终以订单 expires_at + MySQL 状态为准；定时补偿保障异常漏处理。
- 生产消息需说明 publish confirms、消费 ack/nack、重试/死信、消息持久化、重投幂等；Outbox 或同等机制解决数据库提交与发送消息原子性间隙。
