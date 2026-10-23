# EventFlow：最终验收记录与源码学习导航

状态：**FINAL ACCEPT / 开发阶段完成**。日期：2026-10-23。对象：`Ethan-Martinez-creater/JavaWeb-Project`。本记录由 ChatGPT 在 Stage 4 集中审查通过并合并 PR #5 后添加，**不表示生产环境认证或审核端本地重新运行测试**。

## 1. 验收结论与追踪

| 阶段 | GitHub PR | 合并 SHA（缩写） | 结论 |
|---|---|---|---|
| Stage 0：版本预检 | [#1](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/1) | `970a51c` | ACCEPT |
| Stage 1：基础、认证与活动 | [#2](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/2) | `0aa4a4d` | ACCEPT |
| Stage 2：库存、订单、支付与核销 | [#3](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/3) | `2ede68e` | ACCEPT |
| Stage 3：Redis、MQ、Outbox | [#4](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/4) | `1ee0bb3` | ACCEPT |
| Stage 4：Vue、全栈、性能与学习 | [#5](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/5) | `2d12de6` | **FINAL ACCEPT** |

Stage 4 审核 Head：`f773484701a9e2518a382261972fed638ebda84e`，最终合并 SHA：`2d12de6740b12c152b4f045ee825ebc6372a4980`。详尽审核结论已直接写入 [PR #5 讨论](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/pull/5)。

最终 Head 的 [后端 CI](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39916864524)：**43 tests / 0 failures / 0 errors / 0 skipped**。 [前端及全栈 CI](https://github.com/Ethan-Martinez-creater/JavaWeb-Project/actions/runs/39916864558)：**13 个单元测试**、类型检查/格式/lint/build 通过；一条真实浏览器完整业务 E2E 通过，五服务重启持久化核对通过。均核实 run 的 head SHA 与审核 Head 一致；审核采用 GitHub 原始日志和已提交证据，并未在 ChatGPT 审核机重跑。

1000 个不同用户/幂等键的真实 HTTP 并发正确性：**100 创建、900 SOLD_OUT、库存由 100 到 0、订单与条目各 100**。这是正确性证明，**不是峰值 QPS**。隔离本机读实验：100 活动、5 连接，冷/热元数据各 500 请求，**冷 p50/p95 为 14.936/27.715 ms，热为 10.001/17.075 ms，均零错误**。这里的“冷”仅指 Redis 元数据未命中，JVM/SQL 已预热；动态库存始终读 MySQL。原始样本见 [benchmarks/results/stage-4-read-local.json](../../benchmarks/results/stage-4-read-local.json)，重现方法在 [benchmark README](../../benchmarks/README.md)。

## 2. 后续源码学习路线

按以下顺序阅读和**自己运行/解释**，不要仅背项目介绍：

1. **业务与系统地图**：[项目总览](../learning/00-project-overview.md) → README 五服务部署 → 真实浏览器演示。能说清 USER、ORGANIZER 与 ADMIN 的权限。
2. **Java/Spring 项目入口**：[Java 调用链](../learning/01-java-spring-code-tour.md) → `EventFlowApplication` → `SecurityConfiguration` → Controller / Service / Mapper XML。对比熟悉的 FastAPI 依赖注入、中间件与事务。
3. **最重要的面试亮点**：[库存并发与事务](../learning/02-orders-inventory-concurrency.md) → `OrderService`、`InventoryService`、`OrderMapper.xml`、`InventoryMapper.xml`、`Stage2IntegrationTest`。主动画出幂等键、条件更新、事务回滚、支付/关单竞态和票据核销。
4. **异步可靠性与取舍**：[Redis/RabbitMQ](../learning/03-redis-rabbitmq-reliability.md) → `RedisPublishedEventCache`、`OutboxPublisher`、`NotificationMessageHandler`、`OrderExpiryScanner`。解释至少一次、租约、幂等消费和数据库补偿。
5. **完整演示及面试表达**：[问答（22 题）](../learning/04-interview-qna.md) → [90 秒介绍与简历/三分钟演示](../learning/05-resume-and-demo.md) → [API 契约](../openapi.yaml) → [Stage 4 全部证据与交接](stage-4.md)。

建议练习方式：自己重新启动空栈，走一遍组织者发布到用户核销；在代码中逐一定位真正执行 SQL 的位置；把 1000 用户并发、MySQL 订单终态、RabbitMQ 故障补偿各用三分钟讲清楚；最后复述缓存/限流降级与性能实验不可外推的理由。

## 3. 真实限制与维护积压（非阻断 P2）

- 单机 Compose 仅绑定本地回环接口，**没有生产 TLS、HA、正式备份、秘密托管、跨节点 MQ 复制或生产监控告警**。
- **没有真实支付/退款**；内存 JWT 刷新需重登，JWT 有效期内不能即时撤销；前端无法存储 sessionStorage 时幂等键不跨刷新保留。
- Redis 缓存短暂陈旧、故障 fail-open 限流；RabbitMQ 为至少一次消息与数据库业务效果幂等，**不是端到端 exactly-once**。
- Element Plus 为主的入口 bundle 约 957.81 kB / gzip 307.30 kB，打包有体积提醒；后续可以考虑按需导入与拆包，实测前不声称改善。
- Actions/依赖弃用提醒属定期技术债。E2E 当前一条完整浏览器业务闭环，尚可逐步补充浏览器矩阵、移动端可访问性与异常路径。

以上不阻断本项目的**求职演示/学习**完成定义；未来改进必须获得新的明确授权，采用独立维护 PR，不能因为本文件存在就自行开始开发。

## 4. 最终交付入口

- [主 README](../../README.md)：从零启动、安全停止、截图与业务演示
- [Stage 4 交接](stage-4.md)：全部测试环境、命令、结果及可复现证据
- [Stage 4 CI 证据索引](stage-4-ci.json)：所有实际运行/原始 artifact 的对应关系
- [部署说明](../../infra/README.md)、[E2E](../../e2e/README.md)、[读性能](../../benchmarks/README.md)
