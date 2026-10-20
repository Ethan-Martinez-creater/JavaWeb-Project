# 架构、目录与编码规范（基线）

## 1. 架构选择

采用**单个 Spring Boot 部署单元 + 按业务边界分包的模块化单体**。不采用 Maven 多模块、微服务或 DDD 形式主义。演化时可据业务边界拆分，当前用包边界约束依赖。

建议仓库：
    backend/
      pom.xml
      mvnw
      mvnw.cmd
      .mvn/wrapper/...
      src/main/java/com/eventflow/
        EventFlowApplication.java
        common/ (errors, responses, security utils, config)
        identity/ (authentication, current user, RBAC)
        event/ (events, sessions, ticket SKU)
        order/ (Stage 2)
        ticket/ (Stage 2)
        messaging/ (Stage 3)
      src/main/resources/
        application.yml
        db/migration/V1__....sql
        mapper/.../*.xml
      src/test/java/com/eventflow/...
    frontend/ (Stage 4)
    infra/ (Docker Compose etc.)
    docs/
    .github/workflows/

在每个 feature 目录内按功能拆分 web / application / domain / infrastructure（mapper）；目录深度视复杂度控制。DAO 或 Mapper 只由该功能模块的应用服务调用；跨模块通过显式服务接口调用。避免共用一个巨大 common/service/utils 包。

## 2. 技术版本与工具

- 当前项目可复现构建基线：Java 21（编译目标）、Maven Wrapper，Spring Boot 4.1.1、MyBatis Spring Boot Starter 4.1.0、MySQL 8.4（默认 Docker 镜像）。**这些不是要求用户的宿主机逐项重复安装的清单。** Stage 0 先盘点现有版本；任何基线变更必须经过评估并同步更新此文档、POM、CI 和 Compose（若已存在）。
- Stage 1 使用 Spring Web MVC、Spring Security（Resource Server JWT 验证）、Validation、Actuator、Flyway（按版本包含 MySQL 支持模块）、MyBatis、MySQL JDBC。
- Testcontainers + JUnit 5 + Spring Boot Test；测试连接真实 MySQL，不允许 H2 代替。
- Stage 3 引入 Redis 和 RabbitMQ；Stage 4 引入 Vue 3 + TypeScript + Element Plus、Docker Compose 完整应用部署。MySQL/Redis/RabbitMQ 可优先运行在 Docker 容器，宿主机不必安装服务本体。已有兼容的宿主机数据库可复用，但不得用未验证的数据库版本替代 CI 集成测试目标。
- **Spring Boot 4.x 模块化 Starter 与测试模块命名可能不同于 3.x 教程，严格以官方依赖及构建成功为准**。不要机械复制旧版配置。
- Maven 构建应使用明确的、兼容的依赖版本；记录实际解析版本；不使用 SNAPSHOT。

## 3. 代码风格

- 包名小写：com.eventflow.event、com.eventflow.identity 等；类名 PascalCase；方法/字段 camelCase；枚举 UPPER_SNAKE_CASE。
- 不使用 Lombok，以方便后来逐行学习；适合值对象/请求响应的 DTO 优先 Java record，持久化实体用清晰的普通类。
- 构造器注入，禁止字段注入；一个类职责明确，不引入泛型万能 CRUD Service。
- Controller：请求解析/校验/响应映射；Service：业务用例/事务边界；Mapper XML：显式 SQL。
- 禁止 Controller 直接调用 Mapper；禁止在 DTO 上暴露 passwordHash；禁止把数据库实体直接作为 API JSON。
- 状态用枚举 + 明确转换校验；不以多个布尔标志隐式表示业务状态。
- 格式化使用 Spotless（Google Java Format 或明确同等一致规范），在 Maven verify 前执行校验；.editorconfig 统一 UTF-8、LF、文件末尾换行。
- 代码注释主要说明限制条件/设计原因，禁止大段无信息的模板注释；README、设计文档使用中文即可，代码标识符用英文。
- 增加/修改 API 时同步更新契约、测试和 README。

## 4. 数据与事务

- 所有 schema 变更只使用 Flyway 顺序迁移，严禁运行时 DDL（Hibernate ddl-auto）和自动悄然修改生产数据。
- MySQL 表必须有主键、必要唯一约束、外键取舍说明、匹配查询路径的索引；SQL 不可通过字符串拼接用户输入。
- 金额用最小货币单位 BIGINT（元以内不使用 double/float）。数量用非负整数并在数据库/服务层校验。
- UTC 时间以 MySQL DATETIME(6) 及 Java 时间类型协调，JDBC 时区显式设置/验证。业务有效期以服务端可信时钟为准。
- 数据库事务只覆盖本地数据库变更；避免在 @Transactional 里等待 HTTP、MQ 或长任务。
- Spring @Transactional 的自调用、运行时异常回滚、代理失效必须由实现者理解并通过测试体现。
- 库存扣减采用 SQL 条件更新作最终正确性保障，不把 Redis 当成唯一真值。

## 5. 安全

- USER 可自行注册；ORGANIZER/ADMIN 不能从公开注册请求自选。
- 密码 BCrypt 或 Argon2 哈希，禁止可逆加密与明文；JWT 固定验证算法、明确签名密钥来源/到期时间，密钥由环境变量注入。
- 使用 Spring Security Resource Server JWT 验证能力，避免自造脆弱认证过滤器；角色到 GrantedAuthority 映射明确。
- 客户端传入 organizerId/userId 不可用来取代当前登录用户。修改/查看个人或主办方资源前校验归属。
- 公共接口只能读已发布活动；未发布数据不可越权访问。
- 认证/授权/校验失败响应稳定，不能泄露堆栈、SQL、密码或签名密钥。
- DEV 环境如需初始化主办方帐号，只能在显式 dev profile 下按环境变量创建；不可由公开端点提升权限。

## 6. API 与可观测性

- 统一 /api/v1 前缀，JSON camelCase，ISO 8601 时间带明确 UTC 或 offset。
- 对输入使用 Bean Validation（含长度、分页上限、时间先后）；业务冲突 409，未登录 401，无权限 403，不存在/不可见 404，输入错误 400。
- 错误输出结构：{code, message, requestId, timestamp}。未预期错误统一 500，服务端保留日志。
- 分页输出结构：{items, page, size, total}；page 从 0 开始，size 默认 20，上限 100。
- 关键业务日志含 requestId / orderId（后期）而不包含用户密码、访问令牌、完整敏感信息。
- Actuator 只开放所需健康检查，不公开调试/敏感端点。

## 7. 测试基线

- 单元测试检验服务规则/状态转换。
- Spring MVC 集成测试检验 400/401/403/404/409、DTO JSON 和安全边界。
- MySQL Testcontainers 集成测试检验真实 SQL、唯一键、Flyway、分页和事务。
- 持续集成至少运行格式检查与单元/集成测试；可按需要拆 fast / IT job，但不得为绿色 CI 排除关键用例。
- 测试禁止依赖本地机器硬编码地址或手工数据库配置。不能执行测试则交接说明客观原因。

## 8. 架构决策记录（ADR）

影响长期维护的决策在 docs/decisions/ 写简短文档：背景、选择、替代方案、原因、后果。Stage 1 至少记录 JWT 方案与数据库迁移方案（可合并 1 篇）。
