# 01｜Java / Spring 源码阅读路线

建议按一次请求的实际调用方向阅读。初学者可以把 Controller 类比成 FastAPI 路由函数；Spring Bean、构造器注入和代理事务分别承担依赖生命周期、依赖传递和拦截式事务边界。先读小的 DTO 和 SQL，再追业务 Service，不要从整个框架源码开始。

## 从应用启动到认证

1. [`EventFlowApplication.java`](../../backend/src/main/java/com/eventflow/EventFlowApplication.java) 的 `main` 调用 `SpringApplication.run`。Spring Boot 扫描 `com.eventflow` 下的组件，创建 Controller、Service、Mapper、配置和基础设施 Bean。
2. [`SecurityConfiguration.java`](../../backend/src/main/java/com/eventflow/common/security/SecurityConfiguration.java) 创建无状态 `SecurityFilterChain`、HS256 JWT encoder/decoder、issuer/claims 校验与 `ROLE_*` 映射。公开注册/登录、公开活动和健康检查之外，路由按 USER/ORGANIZER/ADMIN 角色约束；角色检查之后仍有 Service 级资源所有权检查。
3. [`AuthController.java`](../../backend/src/main/java/com/eventflow/identity/web/AuthController.java) 只负责把请求交给 `AuthService`；登录在 [`JwtTokenService.java`](../../backend/src/main/java/com/eventflow/identity/application/JwtTokenService.java) 签发短期 bearer token。注册入口只接受 email/password，固定创建 USER。
4. [`ApiExceptionHandler.java`](../../backend/src/main/java/com/eventflow/common/error/ApiExceptionHandler.java) 和 Security 的认证/拒绝处理器分别把 MVC 错误、安全错误统一转为 `{code,message,requestId,timestamp}`。看响应时也检查 `X-Request-Id`；服务端不要向客户端返回 SQL、堆栈、密码、JWT 或核销码。

读完可对照 [ADR 0001](../decisions/0001-stage-1-security-and-persistence.md)：为什么采用 Resource Server、显式 DTO、MyBatis XML 和数据库迁移，而不把角色从请求体接收。

## 一条活动目录请求

```text
EventController.list / detail
  → EventService.published / publishedDetail
    → RedisPublishedEventCache（命中或回源）
    → EventMapper（MyBatis XML 查询已发布元数据）
    → InventoryMapper（详情批量补齐当前库存）
    → EventDtos + JSON
```

- HTTP 入口：[`EventController.java`](../../backend/src/main/java/com/eventflow/event/web/EventController.java)。分页 DTO 是 [`PageResponse.java`](../../backend/src/main/java/com/eventflow/common/web/PageResponse.java)。
- 用例和 DTO 映射：[`EventService.java`](../../backend/src/main/java/com/eventflow/event/application/EventService.java)，重点看 `published`、`publishedDetail`、`publish`、`detail`。
- SQL：[`EventMapper.xml`](../../backend/src/main/resources/mapper/event/EventMapper.xml)；库存读取/锁定/扣减：[`InventoryMapper.xml`](../../backend/src/main/resources/mapper/event/InventoryMapper.xml)。
- 缓存边界：[`RedisPublishedEventCache.java`](../../backend/src/main/java/com/eventflow/event/infrastructure/RedisPublishedEventCache.java)。缓存活动元数据，详情库存从 MySQL 读取；缓存值不能作为下单授权。

## 一条订单写请求

```text
OrderController.create
  → 身份主体 userId + OrderRequestRateLimiter
  → OrderService.create @Transactional
      → OrderMapper 幂等键占位
      → InventoryService.reserve（MANDATORY，仍在同一 DB 事务）
      → OrderMapper 写订单明细/价格快照
      → OutboxRecorder.record
  → 事务提交
  → 后台 OutboxPublisher → RabbitMQ → 通知消费者
```

关键类：[`OrderController.java`](../../backend/src/main/java/com/eventflow/order/web/OrderController.java)、[`OrderService.java`](../../backend/src/main/java/com/eventflow/order/application/OrderService.java)、[`InventoryService.java`](../../backend/src/main/java/com/eventflow/event/application/InventoryService.java)、[`OutboxRecorder.java`](../../backend/src/main/java/com/eventflow/messaging/application/OutboxRecorder.java)。对应 SQL 在 [`OrderMapper.xml`](../../backend/src/main/resources/mapper/order/OrderMapper.xml) 和 [`InventoryMapper.xml`](../../backend/src/main/resources/mapper/event/InventoryMapper.xml)。

支付与关单都从订单行锁开始。支付事务内调用 `TicketService.issue`，再写 Outbox；关闭事务执行 CLOSED 条件转换、释放库存并写 Outbox。学习 Spring 事务时，特别看 `TicketService.issue` 的 `MANDATORY`、`InventoryService.reserve/release` 的 `MANDATORY`，以及到期扫描调用 Spring Bean `OrderService.closeExpired` 的代理边界。

## Mapper XML 和 MySQL

MyBatis Mapper 是接口，查询逻辑放在 `backend/src/main/resources/mapper/<feature>` 下的 XML。`#{...}` 使用绑定参数，列表分页和更新条件可以直接看到。Flyway 顺序迁移为 [`V1`](../../backend/src/main/resources/db/migration/V1__identity_and_event_catalog.sql)、[`V2`](../../backend/src/main/resources/db/migration/V2__orders_and_tickets.sql)、[`V3`](../../backend/src/main/resources/db/migration/V3__reliable_order_events.sql)；不要改写已应用迁移。

用两个窗口练习：窗口 A 读 `OrderService.create` 和 `InventoryService.reserve`；窗口 B 在 Mapper XML 对照每次 Java 方法调用的 SQL、行锁和返回行数。遇到“Java 判断够库存”时继续追到条件 `UPDATE`，它才是扣减最后判据。

## 身份与前端的对应关系

Vue 在 `frontend/src/api` 定义 JSON 类型与错误转换，在 `frontend/src/stores` 保留当前会话，在 `frontend/src/router` 做导航体验。访问令牌仅保存在内存时，刷新页面需要重新登录；XSS 若执行任意脚本仍可能读取内存令牌，因此安全边界仍需输入输出处理和依赖治理。浏览器 API 通过同源 Nginx 入口访问后端，无需 CORS 配置。

从 Python 迁移时可以这样类比：Spring `@RestController` 类似 FastAPI router，Java record DTO 类似 Pydantic 请求/响应模型，构造器注入类似显式依赖传递，Service 是业务用例而不是 ORM model，Mapper XML 是可审查 SQL。`@Transactional` 是 Spring 代理拦截方法的事务声明，不能把它等同于任意函数调用上的上下文管理器；同类内部调用会绕过代理。

下一步阅读：[并发与订单状态](02-orders-inventory-concurrency.md)、[中间件恢复路径](03-redis-rabbitmq-reliability.md)，最后在 [访谈题](04-interview-qna.md) 中逐题从源码回答。
