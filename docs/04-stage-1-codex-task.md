# Codex 执行任务书 — Stage 1（仅此阶段已授权）

## 目标

从仓库当前 main 基线创建规范的可运行 Spring Boot 后端。完成基本认证/权限和活动域 API，使后续 Stage 2 可以直接构建订单/库存业务。请独立完成实现、测试、文档、CI，并开 PR 等候审查。**本轮禁止实现 Stage 2/3/4。**

## 开工前必读

- 根目录 AGENTS.md
- docs/00-project-charter.md
- docs/01-architecture-and-code-style.md
- docs/02-domain-and-api-contract.md
- docs/03-stage-roadmap-and-gates.md
- docs/05-handoff-template.md

如某条技术细节与真实 Spring Boot 4.1 API 不兼容，以官方文档与可运行的构建为准；在 ADR 和交接报告解释，不能暗自切换 Spring Boot 3.x、JPA 或 MyBatis-Plus。

## A. 工程基础（必须完成）

1. 创建 backend/ 单 Maven 工程、Maven Wrapper、Java 21、Spring Boot 4.1.1、MyBatis Starter 4.1.0，依赖明确可复现。
2. 引入 MySQL 8.4、Flyway SQL migration（如需 MySQL 扩展模块请添加）、Spring Security JWT、Validation、Actuator（health）、JUnit 5、Testcontainers MySQL。
3. 创建按 feature 分包结构（identity/event/common），明确 controller/service/mapper XML/DTO，禁止直接暴露持久化对象。
4. 创建 .editorconfig、.gitignore、Spotless（格式检查）、环境变量示例 .env.example、开发 profile 和最少权限配置；禁止提交任何真实密钥。
5. infra/docker-compose.dev.yml 启动开发 MySQL，数据库 healthcheck；README 说明启动/停止、配置环境、迁移、构建、测试、接口 curl 样例。
6. GitHub Actions 在 PR 上执行 format check + tests（使用容器的方式记录，优先 Testcontainers 与 runner Docker）。不要为了 CI 绿色而跳过 MySQL 集成测试。

## B. 数据与安全（必须完成）

1. 使用 Flyway V1 建立 users、user_roles、events、event_sessions、ticket_skus 及 FK、索引、唯一约束、created_at/updated_at，数据列与约束遵循 docs/02。
2. 注册：只创建 USER，email 唯一且规范化，密码安全哈希，校验输入。登录：统一错误，JWT 签名/验签、明确过期与权限映射；/auth/me 正常返回。
3. ORGANIZER 仅可访问自己的活动管理资源；USER 不可发布；公开查询严格只返回 PUBLISHED。
4. 如需演示主办方帐号，在 dev profile 通过明示环境变量/初始化方式创建，不能提供可由外部调用的升权 API、提交固定口令或将开发机制带进 prod。
5. 失败处理与日志：全局错误 JSON 结构统一；有基本 requestId；日志不泄露 token、密码、SQL 参数敏感值；参数/时间/分页边界校验。

## C. 活动域 API（必须完成）

完整实现 docs/02 中列出的所有 S1 路由：
- 公共注册/登录、当前用户信息；
- 已发布活动列表（关键字与分页）和详情；
- 本人的主办方活动列表、创建/编辑草稿；
- 添加场次、添加票种、发布草稿（状态检查与校验）。

要保持以下不变量：
- 只有自己的 DRAFT 可编辑，发布后禁止修改影响履约的字段；
- startsAt < endsAt，发布时有至少一个未来场次且每场次至少一个合法票种；
- ticket_skus.available_stock 初始等于 total_stock，初始两者均不得为负；
- 用户提交的 organizerId / role / status 等字段绝不形成越权；
- 不允许通过 IDOR 访问/修改别人活动，不允许公共详情读取草稿；
- 发布操作是原子状态转换；重复发布应 409，避免并发双成功；
- 必须使用 MyBatis XML 手写关键 SQL，分页不能先加载全部数据再内存分页。

## D. 测试与质量（必须完成）

至少测试以下矩阵，优先真实 MySQL Testcontainers 集成测试：
- 注册合法/无效/重复邮箱；公开注册不能升权；密码 hash 非明文；
- 登录正确/错误、token 错误或过期、匿名访问管理接口 → 401/403；
- USER 无主办方权限、ORGANIZER 无法修改其他人的活动；
- DRAFT 不对公众可见，PUBLISHED 公开可见；
- 时间非法、负数票量/价格、重复票种名、无场次或无票种发布 → 400/409；
- 创建/编辑/发布操作的状态约束；同草稿并发发布只能成功一次；
- 搜索、分页、size 上限及 count 正确性；
- Flyway 从空数据库启动成功，Mapper 查询和索引 SQL 至少有基本真实数据库验证；
- Maven 格式检查+verify 从干净 checkout 可成功（遇到资源约束如实说明）。

测试要校验实际结果/不变量，不能只有应用上下文启动测试。新增安全权限用例不得只模拟无安全过滤器的 Controller。

## E. 交付物（必须完成）

- 后端源码、pom、Maven Wrapper、Flyway migrations、配置与 .env.example；
- MySQL Compose、本地启动文档、GitHub Actions；
- docs/decisions/ 至少一份记录主要选型的 ADR；
- docs/handoffs/stage-1.md 依照交接模板：功能矩阵、关键文件路径、运行指令、**实际执行结果**、风险清单、范围外项；
- 一个 PR（codex/stage-1-foundation → main），建议标题 [Stage 1] Backend foundation, auth and event catalog。

## 完成后停止

当且仅当所有 S1 范围任务完成、测试结果透明披露并创建 PR 后结束本轮。给用户的回复只需要：PR URL、分支/commit SHA、通过/未验证的检查、主要风险。**不合并 PR，不开始 Stage 2，不请求阶段中零散审核。**
