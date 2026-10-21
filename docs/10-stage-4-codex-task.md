# Codex 执行任务书 — Stage 4（最终交付，已授权）

> Stage 3 PR #4 已由 ChatGPT 审核 ACCEPT 并合并到 main（merge SHA `1ee0bb3efc60fbba55e224d2e533b499c20b3cb9`）。
>
> **当前仅授权 Stage 4：Vue 3 演示前端、可复制的完整容器化部署、端到端测试与可复现性能实验、最终源码学习/面试资料。**
>
> 本阶段完成后仍须提交 PR 接受最终集中审核；**不要自行合并 PR，也不要宣布项目已经验收完成。**

## 一、工作流程与约束

1. 从包含 PR #4 的最新 main 创建 `codex/stage-4-ui-deployment-learning` 分支；先读 `AGENTS.md`、项目章程、架构规范、完整 `docs/02-domain-and-api-contract.md`、`docs/03-stage-roadmap-and-gates.md`、Stage 1–3 交接报告及 ADR，核实目前全部后端 API，禁止凭猜测设计前端字段。
2. 保持**模块化单体**、Java 21 / Spring Boot 4.1.1 / MyBatis / MySQL 8.4 / Redis / RabbitMQ 既有基线，不重构为微服务，不加入真实支付或无关中间件。不修改 Flyway V1/V2/V3 已应用迁移；确有必要变更结构另加 V4 并解释，优先不新增表。
3. 复用现有 Node 24、pnpm 11、JDK 21 和 Docker，不进行任何全局系统安装、升级或 Windows/WSL 修改；前端项目局部依赖可按正常包管理获取。不得触碰宿主已有 MySQL 5.7、Redis、默认 Java 17 和其他项目 Docker volume。
4. 在 Stage 4 内自行设计、实现、调试、测试、修复和写文档；到所有验收证据齐全时一次性提交 PR，不请求频繁中途审核。
5. **本项目面向求职和后续逐行学习**：保持清晰的 feature 结构、类型安全和显式接口契约；不要生成巨大单文件前端、过度组件抽象、无解释魔法参数或无法维护的复制粘贴。

## 二、Vue 3 用户与主办方端（必须真实可操作）

### 技术基线与组织

- `frontend/`：Vue 3 + TypeScript + Vite + Vue Router + Pinia + Element Plus；优先 pnpm，提交 `pnpm-lock.yaml`、`package.json` 中明确的 typecheck/lint/test/build 命令和 README。
- 建议按 `src/api`（接口与统一错误）、`src/router`（访问守卫）、`src/stores`（身份/会话）、`src/features/auth|events|orders|tickets|organizer|notifications`、`src/components` 分包。组件应小、职责单一，表单验证有类型。
- 前端路由按角色展示功能；**不可把前端角色守卫当成后端授权**。任何权限与用户归属最终仍由已有 Spring Security 和业务 Service 判定。
- 只接真实后端 REST API，**不能用硬编码假活动、假订单、假库存来演示“正常业务”**。允许单元测试/mock fixtures，但实际演示必须连真实 DB/MQ。
- 视觉目标：简洁专业的活动售票平台，中文 UI，基本响应式布局（常规桌面和手机屏幕），统一主题/布局、清楚的空状态与错误提示。不需要设计成营销网站，不引入大型可视化库。

### 真实业务页面/流程

1. **公开页**：活动列表（搜索、分页、空状态），活动详情（场次/票种/单价/实时剩余库存、时间本地化），活动与价格在变动后可刷新。
2. **身份**：注册（仅 USER）、登录、登出、当前身份与权限显示；会话过期/401 处理，403 提示；不要公开 ORGANIZER/ADMIN 注册或升权入口。JWT 保存方案要在 ADR 说明 XSS/刷新后的行为（优先减少不必要的持久化；若存储在 sessionStorage/localStorage 必须披露风险）。
3. **USER 流程**：选择票种（一次仅一张）→ `POST /orders` 带稳定的客户端幂等键 → 待支付订单 → **明确标注“模拟支付”** → 本人订单列表/详情 → 电子票列表/核销码（随机码只在一次响应出现，按需刷新生成）；主动取消未支付订单，展示到期关闭状态及本人站内通知。
4. **ORGANIZER 流程**：本人活动列表、创建/编辑草稿、增加场次/票种、发布；场次时间和金额/库存输入校验；扫码核销可通过**手动粘贴核销码**演示完整业务，并将票码渲染为 QR（可用轻量 qrcode 依赖）。摄像头扫码为可选项，不能因此引入系统权限复杂度。不能核销别人的活动。
5. 演示角色切换：提供明确的登录/退出路径和说明，使用 dev profile 的独立主办方账号（必须人工从环境变量配置），普通用户通过公开注册获得；不要预置公开的管理员口令或生产后门。
6. **交互/异常**：防重复提交、loading、429 限流/售罄 409/过期支付/失效旧核销码、网络错误的可读提示；一次刷新不应凭 UI 误导用户库存仍准确。API 错误必须保留原有 `code,message,requestId,timestamp` 诊断信息（不展示隐私或敏感凭据）。

## 三、全栈 Docker Compose 与环境隔离

1. 保留 `infra/docker-compose.dev.yml` 的单独 MySQL/Redis/RabbitMQ 开发模式；新增 `infra/docker-compose.full.yml` 或清晰叠加方案，提供 **MySQL + Redis + RabbitMQ + Spring Boot backend + Nginx 托管 Vue 静态资源** 一条可复现启动命令。
2. 前端容器经 Nginx 同源反向代理 `/api/v1` 与健康检查至 backend；外界只需访问单一本机站点端口（建议 `127.0.0.1:8088`，实际冲突需核对），避免浏览器 CORS 与分散 API 地址。内部服务之间使用 Compose DNS，非 localhost；后端不要向公网暴露调试端点。
3. Spring Boot / Vue 构建均纳入 Dockerfile，尽量采用多阶段镜像、固定合理版本、最小运行权限、`.dockerignore`；合理的健康检查与 startup dependency 顺序，并说明 MySQL Flyway 迁移如何自动执行。
4. 不提交 `.env`、JWT secret、DB/Rabbit 凭据。提交**无真实密码**的环境模板及部署说明；不在默认配置里植入可用的弱口令。开发主办方初始化只在显式 dev profile，并通过环境变量人工配置。
5. **绝不使用 docker compose down -v、system prune、重置既有 volume 等破坏性命令**；不覆盖已有端口 3306/6379/5672/15672 或其他项目服务。对容器名称、项目名、volume 归属与现有 Stage 3 开发服务的共存/冲突做说明。
6. 验证首次空库迁移 V1→V2→V3，且已有 Stage 3 库迁移后能正常运行；容器重启数据持续存在。说明测试用一次性容器、项目持久开发卷、备份/删除操作的区别。

## 四、自动化测试与 CI（不得偷减已通过的测试）

- 原 Stage 1–3 的 **42 项真实 MySQL/Redis/RabbitMQ 测试全部保留并通过**，不允许 skip、mock 三个依赖替代原集成测试；1000 不同用户库存并发的 100/900/余量 0 实验继续复现。
- Vue 端应有 **实际可运行的类型检查、ESLint/格式检查、核心组件或 API 层的自动化测试**；至少覆盖权限导航、请求错误转换与幂等提交逻辑，不用测试覆盖率数字代替正确性。
- 具备一套**真实全栈端到端**自动化验收：例如 Playwright（浏览器镜像/依赖可作为项目局部依赖），在干净隔离的测试库上运行“主办方发布 → 普通用户浏览下单 → 模拟支付 → 电子票生成/轮换 → 主办方核销 → 用户看到 USED/通知”。测试仍调用实际后端与 RabbitMQ，不假装 API stub 就是 E2E。若 UI E2E 采用 Playwright，必须将运行所需浏览器安装/缓存与 CI 路径写明，且 CI 实际能跑；不要在已有用户数据库上运行重置脚本。
- 更新 `.github/workflows/`：Java 格式/42 项测试、前端 lint/typecheck/unit/build、端到端验收至少应在 PR 自动执行；可分 jobs 避免过大等待，但最终 Gate 要可核对。CI 环境若没有 Docker/浏览器不得标记 E2E PASS。
- 测试报表须注明本机与 Linux runner、测试数、失败/跳过数、证据文件及真实命令；本阶段新 UI 问题不能用跳过来使 CI 变绿。

## 五、性能实验：只报告实测，不编造数字

提供 `benchmarks/` 下脚本（k6 或合理开源压测工具）和可复现的数据准备/环境检查/运行/清理步骤，在**本机隔离项目测试库**而非用户现有 MySQL 5.7 或公网地址运行：
- **读场景**：固定数量已发布活动、预热前后对比缓存命中和活动详情延迟；记录数据规模、持续时间、并发、测量方法、p50/p95、错误率与硬件/软件环境。考虑活动详情仍查询实时库存，不能错误宣称完全命中 Redis 后不访问 MySQL。
- **写场景**：库存=100、1000 个不同身份并发预约不超卖；复用既有并发正确性证据并可选补 HTTP 延迟观测。Redis 限流配置不可导致把 429 当成“售罄成功”。
- **可靠性**：简明复述/脚本复现 MQ 停机补偿、确认超时/重发消费、Redis fail-open 的观测结果；引用 Stage 3 真实故障 JSON，而非重做全部难以理解的实验。
- 保存至少一次**真实实测**的原始输出与结果表、脚本、测试环境、使用的命令与注意事项。未测到的项目不得声称；不要求达到任意 QPS 数字，也不通过降低断言迎合性能指标。
- 不得对生产/共享外部服务进行压测；限流、资源耗尽时及时停止压测并记录失败。

## 六、学习、面试与交付文档（必须完整但精练）

请在 `docs/learning/` 提供可按源码学习的材料，面向**原本以 Python/FastAPI/Agent 为主、正在补 Java 后端的求职者**：

1. `00-project-overview.md`：业务背景、用户价值、为什么模块化单体、完整部署示意、目录地图、10 分钟演示流程。
2. `01-java-spring-code-tour.md`：从 `@SpringBootApplication` 启动，经 Spring Security JWT、Controller → Service/`@Transactional` → Mapper XML → MySQL 的阅读顺序；对应真实类路径/关键方法。
3. `02-orders-inventory-concurrency.md`：MySQL 条件库存、唯一键幂等、锁顺序、事务 rollback、支付-关单竞态与核销一次性；指出具体 SQL 和真实测试名称，给出哪些事实能证明“不超卖”。
4. `03-redis-rabbitmq-reliability.md`：Cache Aside、缓存一贯性边界、Redis fail-open、Outbox 与 confirms、至少一次、消费去重、TTL/DLX、DLQ、DB 兜底及故障注入命令，附源码与证据定位。
5. `04-interview-qna.md`：至少 15 个**确实能用源码回答**的面试问题与参考回答，侧重 Spring 事务、MyBatis、MySQL 行锁/索引、JWT/RBAC、Redis、RabbitMQ、性能实验和架构取舍；拒绝空话/未实测 QPS。
6. `05-resume-and-demo.md`：约 90 秒项目介绍、3–5 条有证据链接的简历表述、3 分钟现场演示和常见追问，所有描述严格匹配代码/CI/实测；把未实现的高可用、真实支付、强一致缓存明确标为限制。

README 调整为新读者可从零启动：技术栈、安装前置（优先复用）、一条启动命令、角色演示、数据库/消息如何初始化、运行截图（**仅真实应用截图**，非渲染示意图）、测试/性能复现入口、PR/CI 历史与真实约束。OpenAPI API 说明可以使用静态 OpenAPI YAML 或代码生成，但必须与 Stage 1–3 真实 API 契约核对一致，且生产环境不暴露无需的内部 debug UI。

**务必保留**原 Stage1–3 ADR、hand-off、原始测试 JSON 与历史链接。不要为写文档重构大量已验收后端代码，除非当前阶段需要且有回归证据。

## 七、最终 Gate 与 PR 交接

必须满足：

- 从新的隔离项目环境按 README 启动**全部五类服务**，通过浏览器执行真实“发布→浏览→预约→支付→核销→通知”端到端演示；可重启并核验数据持久化。
- 用户端与主办方端权限、安全边界、库存/订单状态真实一致，二维码仅作为已授权的真实票码表达；不暴露生产凭据或伪造成功。
- 42 项已有后端测试及 Stage 4 新增测试全绿；完整 CI 包含前端、真浏览器/真全栈 E2E，上传证据；不跳过、不隐藏失败。
- 至少一个完整记录的可重现读性能实验（含 p50/p95、错误率、环境与命令）；写并发与故障正确性依据不变量及证据，不以虚构 QPS 充数。
- README、OpenAPI/契约、架构图、源码学习索引、面试问答、简历表述全部可以实际追踪到类、SQL 和测试。
- 本地旧数据库/Redis/默认 Java 未受影响；无真实密钥入库、无强制全局安装，Compose 健康且退出方式安全。

新建 `docs/handoffs/stage-4.md`，按 `docs/05-handoff-template.md` 并补充 UI/E2E/性能/面试材料核验矩阵；真实附上版本、环境、命令、CI URL、总测试数及失败/跳过数、原始实验文件位置与可复现步骤、遗留风险/安全说明、核心文件索引。可增加截图或网页录制证据但不能假造。

创建一个 PR `codex/stage-4-ui-deployment-learning → main`，建议标题 `[Stage 4] Vue frontend, full-stack deployment and interview handoff`。向用户只交付 PR 链接、Head SHA、全栈/CI/E2E/性能验证摘要及风险，然后**停止，等待 ChatGPT 最终集中审核；不自行合并**。
