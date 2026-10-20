# ADR 0001：Stage 1 认证、迁移与活动状态一致性

- 状态：已实现，等待阶段审核。
- 范围：Java 21、Spring Boot 4.1.1、MyBatis Starter 4.1.0、MySQL 8.4；不改变既定版本基线。

## 背景与选择

认证使用 Spring Security Resource Server 的 JWT 验签，固定 HS256、issuer 与令牌到期时间。密钥由环境变量注入，缺失或不足 32 个 UTF-8 字节时拒绝启动。公开注册只创建 USER，密码使用 BCrypt；角色来自数据库，不能从请求体设置。开发主办方初始化只在显式 dev profile 与显式凭据下启用，不覆盖已有账户的角色或口令。

未另造 Bearer 认证过滤器，也不把用户提交的身份作为资源所有者。无主办权限返回 403；有主办权限但资源不属于本人时统一返回 404，避免泄露其他主办方资源是否存在。ADMIN 按契约可管理他人的活动，但主办方列表仍只列出登录者自己的活动。

持久化使用 MyBatis XML 显式 SQL 与 Flyway 顺序迁移，金额为 BIGINT 最小货币单位，数量为 INT。外键、唯一键和 CHECK 约束为数据库最后防线。业务服务进行输入和状态验证，SQL 约束错误不直接输出数据库消息。DATETIME(6) 统一解释为 UTC，接口输入要求明确 offset，输出转换为 UTC Instant；输入先截断到数据库微秒精度后再比较。

所有活动草稿修改（含子场次/票种新增）和发布在事务内锁定同一条 events 父记录。发布持锁验证所有场次和票种，再用带 DRAFT 条件的 UPDATE 原子转换状态。并发发布只能成功一次；子资源新增与发布不会穿过对方的校验窗口。保留条件 UPDATE 作为额外防线，不依赖 Java 内存锁，支持多实例服务。

## 替代方案与后果

- 不使用 JPA/MyBatis-Plus、H2、数据库运行时自动建表或内存库存。
- 不采用仅先查状态再更新的无锁实现，否则发布与新增子资源会竞态。
- 暂不引入缓存、消息、订单与履约逻辑。已发布活动在 S1 不开放修改/取消。
- JWT 在有效期内不提供即时撤销机制；后续若需强制下线或角色即时失效，应单独设计令牌撤销/版本策略，不扩大本轮范围。
- 列表用 SQL COUNT、LIMIT/OFFSET 和稳定 id 排序；详情单独加载场次/票种，避免联表分页破坏父资源数量。关键词为参数化字面子串，转义 LIKE 的通配符；前导通配符搜索不声称使用 B-tree 全文加速。

## 环境与验证

复用现有非默认 JDK 21.0.9 与 Maven 3.8.6，仅在项目进程临时选用。开发 Compose 的 MySQL 8.4 映射 127.0.0.1:3307，使用项目专属 named volume；不访问本机旧 MySQL 5.7 的数据目录或改变其 3306 服务。集成测试使用独立 Testcontainers MySQL 8.4 与动态端口；Docker 不可用时必须失败，不跳过或替换数据库。Linux CI 使用相同格式检查和 verify 命令。实际结果以阶段交接报告和 CI 证据为准。

本次实际核实官方镜像为 MySQL 8.4.11，Compose 与测试均固定同一 manifest digest，避免浮动 8.4 标签改变复现结果。Testcontainers 固定 1.21.4，使用官方 1.21.x 针对新 Docker Engine 的兼容性修复；不通过更改 Docker 系统级 API 配置来绕过客户端不兼容。Spring Security 7.1.1 的 Nimbus encoder 使用真实 API `algorithm(HS256)`，decoder 使用 `macAlgorithm(HS256)`；以实际编译核对，未降级 Boot 或替换认证框架。

任务书中“JUnit 5”采用的 Jupiter 编程模型保留；实际依赖沿用 Boot 4.1.1 管理的 JUnit Jupiter/Platform 6.0.3，不另行强制降级测试平台。此差异在交接报告中披露，测试仍使用 `org.junit.jupiter` 与真实 Testcontainers，不改成 JUnit 4 或无安全过滤器的模拟测试。

Testcontainers 1.x 自带的旧 MySQL 配置包含 10 MiB redo log；在本机 MySQL 8.4 初始化中实际出现 redo 空间等待。测试容器显式使用 128 MiB redo 容量，并将一次性数据目录放入 512 MiB tmpfs；运行的仍是相同 digest 的真实 MySQL 引擎，开发 Compose 则保留持久化 named volume。测试镜像用 digest-only 引用，避开 Testcontainers 1.x 对 tag+digest 字符串的兼容解析问题。

Windows Maven Wrapper 适配仓库要求的 PowerShell 7：显式 `pwsh -Command`、完整 cmdlet、UTF-8 读取及移动前的绝对路径范围校验。两种平台脚本都保留下载临时文件，不自动批量删除；使用者可自行清理已确认的缓存。所有本次本机 JVM 与 Wrapper 临时路径均指向 D 盘项目缓存。

## 官方依据

- [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [MyBatis Starter](https://github.com/mybatis/spring-boot-starter)
- [MySQL locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- [Testcontainers Docker 环境](https://java.testcontainers.org/supported_docker_environment/)
- [Testcontainers 1.21.4 Docker Engine 兼容修复](https://github.com/testcontainers/testcontainers-java/releases/tag/1.21.4)
