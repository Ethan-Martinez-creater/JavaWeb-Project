# 05｜项目介绍、简历与现场演示

本材料将功能描述限制在仓库实现和可复现证据内。Stage 4 的最终性能数字与测试状态，以最终 [Stage 4 交接报告](../handoffs/stage-4.md)、[CI 工作流](../../.github/workflows) 和 `benchmarks/results/` 实际文件为准；不要从本模板推断尚未执行的测试已通过。

## 90 秒项目介绍

> 我做了一个活动预约与电子票系统，用来展示 Java/Spring 后端工程能力。主办方可以创建草稿并发布场次和票种，用户从公开页面预约一张票、完成模拟支付并拿到电子票，主办方能在授权时间窗内核销。未支付订单通过 RabbitMQ 到期触发，并由数据库扫描兜底。
>
> 后端采用 Spring Boot 模块化单体，按身份、活动、订单、票务、消息和通知分包。MyBatis XML 明确写出 SQL；MySQL 事务、唯一键、行锁和条件更新负责订单、库存和核销正确性。Redis 只缓存活动元数据、协助做用户限流，不作库存真值。数据库状态和 Outbox 同事务提交，publisher confirm 后再标记消息；消费者以数据库唯一键去重，所以设计语义是至少一次。
>
> Vue 3 页面通过同源 Nginx 代理连接真实 API，Compose 部署前端、后端、MySQL、Redis 和 RabbitMQ；我用真实浏览器 E2E 验证从发布到通知的流程，并用单独的读性能实验记录了指定环境的 p50/p95。结果和局限都有可复现命令与原始文件。我不会把实验数字外推成生产 QPS：目前它是一个单机学习和求职演示项目，没有真实支付、消息 exactly-once 或多节点高可用。

讲完后立即打开 [项目总览](00-project-overview.md) 和源码定位，再展示实际 E2E/性能证据文件，不只讲中间件名词。

## 简历表述（投递前按最终结果核对）

- 使用 Java 21、Spring Boot 与 MyBatis 构建模块化单体活动票务后端，按业务拆分身份、活动、订单、票务和消息包；用 Flyway 和显式 Mapper SQL 管理 MySQL schema 与查询。证据：[架构规范](../01-architecture-and-code-style.md)、`backend/src/main/java/com/eventflow`、`backend/src/main/resources/mapper`。
- 设计基于 MySQL 唯一键幂等、行锁与条件库存更新的单票预约事务；对库存 100、1000 个不同身份请求核对至多 100 成功且余量为 0。证据：`Stage2IntegrationTest.thousandDistinctUsersAndKeysOverRealHttpNeverOversellAndWriteSanitizedArtifact` 与 [Stage 3 原始记录](../handoffs/stage-3-concurrency-ci.json)。这不是吞吐承诺。
- 将订单状态与 Outbox 事件置于同一数据库事务，通过 publisher confirms、持久 lease、消费去重和到期扫描补偿处理重发/故障。证据：`Stage3IntegrationTest` 故障用例及 [真实 CI 故障 JSON](../handoffs/stage-3-fault-ci.json)。
- 实现 Vue 3 用户/主办方流程和同源 Docker Compose；以真实 MySQL/Redis/RabbitMQ 与浏览器完成发布、预约、模拟支付、核销和通知验收。证据：[`e2e`](../../e2e)、[`infra`](../../infra)、最终 CI/artifact 链接见 [Stage 4 handoff](../handoffs/stage-4.md)。发简历前先确认最终 E2E 为 PASS。
- 在隔离本机 Compose 环境用仓库基准脚本进行活动详情冷/热读取实验，简历只引用 [原始输出和结果表](../../benchmarks/results) 实际存在的 p50/p95、请求数、并发和错误率；不写未经测量的 QPS/“提速百分比”。

如果面试版本或分支未包含 Stage 4 最终验收，不要使用第四条；如果读基准原始文件不在仓库，也不要使用第五条。

## 3 分钟浏览器演示

演示前遵循根 README 和 [部署说明](../../infra/README.md)，确认使用项目独立 Compose 卷与测试数据。主办方账号通过环境变量人工配置，普通用户公开注册为 USER。不要在终端历史、截图或屏幕录制里显示口令、JWT 或明文核销码。

| 时间 | 操作与讲解 |
|---|---|
| 0:00–0:35 | 用 ORGANIZER 登录，创建活动草稿，新增未来 30 分钟以上开始的场次和票种，发布。讲清楚用户不能自助选择角色；发布要求未来场次。 |
| 0:35–1:00 | 登出，用 USER 注册/登录；搜索刚发布活动，打开详情，说明显示的是查询时库存。 |
| 1:00–1:35 | 预约一个 SKU，检查本人订单处于待付款。模拟网络重试时复用幂等 key；解释库存扣减仍由数据库裁决。 |
| 1:35–2:05 | 点击明确标注的“模拟支付”，打开电子票并生成/轮换核销码。只展示当前响应，不复制码到历史/日志；二维码只是码的显示方式。 |
| 2:05–2:35 | 切回所属 ORGANIZER，手动粘贴核销码并核销；回到 USER 侧刷新票状态，确认 USED 和稍后到达的站内通知。 |
| 2:35–3:00 | 指出 `OrderService` 事务、库存 Mapper 条件 UPDATE 与 Outbox 记录；再展示真实 E2E artifact。通知最终一致，若有短暂延迟说明由消费者异步处理。 |

如果场次时间已不在核销窗口，E2E 或现场演示会得到拒绝。创建未来且在 `[startsAt - 2 小时, endsAt + 1 小时]` 内的场次；不要改后端时钟绕开业务约束。

## 常见追问的短答

- **为什么模拟支付？** 展示同一订单状态事务及签票，不接第三方网关、不处理真实资金/退款。
- **Redis 挂掉会怎样？** 目录退回 MySQL；下单限流 fail-open；MySQL 继续保证库存、幂等和状态正确性，但流量防护变弱。
- **消息是否 exactly-once？** 不是。Outbox + confirms + 重试是至少一次；事务去重让业务效果幂等。
- **Redis 库存正确吗？** Redis 不保存库存真值。详情库存从 MySQL 查，预约执行加锁条件 UPDATE。
- **可以说支持多少请求吗？** 只报告提交的读基准在明确环境与请求配置下的原始数值，不外推 QPS 或生产容量。
- **能不能高可用部署？** 当前全栈 Compose 是单机复现，RabbitMQ durable 队列不是集群复制；没有生产 HA/灾备验证。

## 交付核验入口

- API 及 schema：[OpenAPI](../openapi.yaml) 与 [权威契约](../02-domain-and-api-contract.md)。
- 业务调用链：[Java 源码路线](01-java-spring-code-tour.md)、[并发说明](02-orders-inventory-concurrency.md)、[中间件恢复](03-redis-rabbitmq-reliability.md)。
- 自动化和原始结果：测试命令及计数、真实 Linux CI、真实 E2E screenshot/report 和 benchmark 原始 JSON 均以本阶段 [handoff](../handoffs/stage-4.md) 所列链接为准。
- 本项目限制：不含真实支付、退款、微服务拆分、缓存强一致性、消息 exactly-once、RabbitMQ 多节点高可用、生产监控平台或未经实测的容量指标。
