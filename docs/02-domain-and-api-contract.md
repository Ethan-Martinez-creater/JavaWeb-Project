# 领域模型与 REST API 契约 v1

本文件定义各阶段共有的业务约束。Stage 1 **只实现标记为 S1 的部分**；S2/S3/S4 不得提前开发。

## 1. 角色与边界

- USER：自行注册、登录、浏览发布活动；S2 才下单/支付/查看本人订单/核销个人票据。
- ORGANIZER：管理**本人创建**活动、场次、票种；不可操作别人的活动。
- ADMIN：保留角色和最小管理权限，额外管理界面可在后续阶段开发。
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

### Stage 2（只做设计预留）
- orders: id, user_id, order_no UNIQUE, status (PENDING_PAYMENT/PAID/CLOSED), expires_at, paid_at, closed_at, created_at，必要的业务幂等键。
- order_items: id, order_id, sku_id, unit_price_cents, quantity。
- tickets: id, order_item_id, owner_user_id, token_hash/随机核销凭据, status (UNUSED/USED), used_at。
- checkin_records: id, ticket_id, operator_user_id, result, created_at。
- 一个订单 S2 最初只允许一个票种、一张票；先确保正确性，再评估多票能力。不能仅靠 UUID 声称幂等。

### Stage 3（只做设计预留）
- outbox_events: 事务内事件记录、发布状态、重试信息、唯一事件 ID。
- consumed_messages 或等价持久化幂等记录；辅以订单状态条件更新。
- RabbitMQ 负责超时/通知异步事件；周期扫描提供漏消息补偿；MySQL 最终判定订单状态和库存。

## 3. 活动状态规则

- 新建为 DRAFT。
- DRAFT → PUBLISHED 需拥有至少一个未来开始的有效场次，每个场次至少一个合法票种；仅活动所有者或 ADMIN 可发布。
- PUBLISHED → CANCELLED 与后续履约有关，留至 S2 设计；S1 不暴露取消接口。
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

## 5. Stage 2 主流程（仅供设计，不在 S1 实现）

- 用户选择一张 SKU 票提交预约，请求带客户端生成的 idempotencyKey；订单与库存预占使用本地 MySQL 事务。
- 扣库存使用 UPDATE ticket_skus SET available_stock=available_stock-1 WHERE id=? AND available_stock>=1 等价原子条件更新，并检查受影响行数。
- 模拟支付仅处理本人 PENDING_PAYMENT 且未超过 expires_at 订单，转 PAID 并生成唯一有效电子票；不得访问支付真实网关。
- 取消/超时关单只从 PENDING_PAYMENT → CLOSED；库存释放且仅释放一次；支付与关单竞争通过条件状态转换串行化。
- 核销必须验证资格归属、活动场次、核销员权限和票状态；同票并发只能成功一次。
- Stage 2 必须完成并发预定、重复请求、支付-关单竞态和重复核销的真实 MySQL 测试。

## 6. Stage 3 中间件规则（仅供设计）

- Redis Cache Aside 用于活动信息热点缓存（读缓存、回源、更新后失效、TTL 和缓存雪崩/穿透基本防护），库存的最终真值仍在 MySQL。
- RabbitMQ 用于异步通知和订单超时关闭；默认可使用 TTL + DLX/死信队列，不依赖可选的 delayed-message 插件。超时条件最终以订单 expires_at + MySQL 状态为准；定时补偿保障异常漏处理。
- 生产消息需说明 publish confirms、消费 ack/nack、重试/死信、消息持久化、重投幂等；Outbox 或同等机制解决数据库提交与发送消息原子性间隙。
