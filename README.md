# EventFlow — 活动票务预约与核销平台

一个用于 Java 后端求职作品集的**模块化单体**项目。业务覆盖活动发布、场次/票种、预约下单、库存预占、模拟支付、订单超时关闭、电子票核销及运营后台。重点是**可解释、可测试的工程实践**，而非堆砌中间件。

## 项目状态

- 当前：Stage 1 待 Codex 实施；尚无已验收的应用功能。
- 协作：Codex 负责实施；ChatGPT 负责每阶段结束后的集中代码审查；仓库 PR 是唯一交付与审核载体。
- **禁止未经阶段验收就继续下一阶段，也禁止自行合并阶段 PR。**

## 已确定技术选型

Java 21 · Spring Boot 4.1.1 · MyBatis Spring Boot Starter 4.1.0 · MySQL 8.4 · Redis · RabbitMQ · Spring Security/JWT · Vue 3/Element Plus · Maven · Flyway · Testcontainers · Docker Compose。

技术栈逐步接入，Stage 1 **只引入实际需要**的组件；不预先搭建微服务、Nacos、Seata、Kubernetes 等。

## 仓库导航（按阅读顺序）

1. [项目边界与目标](docs/00-project-charter.md)
2. [架构与编码规范](docs/01-architecture-and-code-style.md)
3. [领域模型与 API 契约](docs/02-domain-and-api-contract.md)
4. [分阶段路线与审核门禁](docs/03-stage-roadmap-and-gates.md)
5. [当前 Codex 任务：Stage 1](docs/04-stage-1-codex-task.md)
6. [Codex 阶段交接模板](docs/05-handoff-template.md)
7. [自动化协作约束](AGENTS.md)

## 预期仓库布局

- backend/：Spring Boot 模块化单体，按业务包隔离
- frontend/：Vue 3 前端（Stage 4 才建立）
- infra/：开发/部署配置
- docs/：架构决策、设计、测试证据、阶段交接
- .github/workflows/：CI（Stage 1 建立）

**注意：上述业务目录是未来目标，当前不代表已经实现。** 实现之后由 Codex 更新本 README 的实际启动命令和已完成阶段状态。

## 资料来源

- Spring Boot 4.1.1：https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/
- MyBatis Starter 4.1.x 兼容线：https://github.com/mybatis/spring-boot-starter
- Spring Security JWT：https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
