# EventFlow — 活动票务预约与核销平台

Java 21 / Spring Boot 4.1.1 / MyBatis 的模块化单体。当前实现 **Stage 1**：JWT/RBAC、活动草稿、场次与票种、发布及公开查询，等待阶段 PR 审核。订单、支付、核销、Redis、RabbitMQ、Vue 均不在本轮范围；不自行合并或推进 Stage 2。

## 环境与数据隔离

复用本机已有 JDK 21.0.9、Maven 3.8.6，无需重新安装。默认 Java 17 不改动；下列命令只改变当前 PowerShell 7+ 会话。Docker Desktop 需运行 Linux engine。开发 MySQL 使用独立 `mysql:8.4`、项目 named volume、`127.0.0.1:3307:3306`；不要修改或停止本机已有的 3306 MySQL 5.7，也不要将其数据目录挂入容器。集成测试另建临时 MySQL 容器并使用动态端口。

```powershell
$env:JAVA_HOME = 'D:\JAVA21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn -version
docker info
docker compose version
```

首次 wrapper 使用会获取固定的 Maven 3.8.6；依赖解析也需要网络。项目没有要求升级系统 Maven。若需要所有构建缓存留在 D 盘，可为当前会话配置：

```powershell
$buildCache = Join-Path (Get-Location) '.build-cache'
New-Item -ItemType Directory -Path (Join-Path $buildCache 'tmp') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $buildCache 'home') -Force | Out-Null
$env:MAVEN_USER_HOME = Join-Path $buildCache 'maven-user'
$env:TEMP = Join-Path $buildCache 'tmp'
$env:TMP = $env:TEMP
$env:MAVEN_OPTS = "-Duser.home=$buildCache/home -Djava.io.tmpdir=$buildCache/tmp -Dmaven.repo.local=$buildCache/m2"
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$buildCache/home -Djava.io.tmpdir=$buildCache/tmp -Djna.tmpdir=$buildCache/tmp"
```

JAVA_TOOL_OPTIONS 同时覆盖测试子 JVM，避免它们另用系统默认临时目录。仓库路径含空格时，上述 JVM 属性值需要加引号。不要将临时变量写入系统环境、注册表或其他项目配置。

Windows Wrapper 要求 PowerShell 7+（`pwsh`）。Wrapper 固定发行版 SHA-256，保留下载临时文件供用户手动清理，不自动批量删除目录。本机如需沿用已有 Maven 镜像设置，可显式传入 `-gs '现有 Maven 安装目录/conf/settings.xml'`；这只读取设置，不修改它，CI 使用 Maven Central。

## 启动开发库与应用

在仓库根目录复制 `.env.example` 为 **不提交的** `.env`，填写自选的本项目专用数据库密码、根密码和至少 32 字节的随机 JWT 密钥。不要复用现有数据库凭据。Compose 从该文件加载数据库配置；Spring Boot 不会自动读取这个文件，应用进程的 `DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET` 须另行通过当前终端或 IDE 注入。

```powershell
Copy-Item -LiteralPath '.env.example' -Destination '.env'
# 在本地编辑 .env，替换占位符后启动。不要将密码/密钥粘贴到聊天或提交。
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d --wait --wait-timeout 240
docker compose --env-file .env -f infra/docker-compose.dev.yml ps
```

应用配置：`DB_URL` 默认连接 `127.0.0.1:3307/eventflow`，`DB_USERNAME` 与 `DB_PASSWORD` 对应上述专用用户；若修改 `DB_NAME`，应同步设置 `DB_URL`。JDBC 连接统一 UTC。`JWT_SECRET` 没有生产默认值，缺失或过短会拒绝启动。Flyway 在启动时从空库执行 `V1__identity_and_event_catalog.sql`；不手工建表或修改已有迁移。

```powershell
# 先通过本地终端/IDE安全注入 DB_USERNAME、DB_PASSWORD、JWT_SECRET。
Set-Location backend
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

如需演示主办方，仅在显式 `dev` profile 设置 `DEV_ORGANIZER_EMAIL` / `DEV_ORGANIZER_PASSWORD`。初始化只新建专用账户，不会把已有公开注册账户升权；不设这两项则不创建演示账户。生产 profile 不启用该机制。不存在公开升权 API。

两项必须同时设置。相同邮箱、密码、ACTIVE 状态和恰好 USER+ORGANIZER 的现有账户可原样重复启动；其他已存在账户会使初始化拒绝执行，绝不重设密码或角色。修改演示配置时应取消这两项或另选专用邮箱。

健康检查：`GET http://localhost:8080/actuator/health`；仅公开健康状态，不开放数据库详情、环境或调试端点。退出应用用其终端的 Ctrl+C。开发数据库停止命令：

```powershell
# 从仓库根目录执行；保留本项目 volume 和数据。
docker compose --env-file .env -f infra/docker-compose.dev.yml stop
```

修改容器初始化密码不会自动更改已有 volume 内的账户；不要用删除 volume 的方式操作未知数据，先明确本项目数据的处置方案。

## 构建、测试与 CI

```powershell
Set-Location backend
.\mvnw.cmd -B spotless:check verify
```

测试启动真正的 MySQL 8.4（Testcontainers），通过 HTTP 调用完整安全链，检查 Flyway、约束、所有权、认证、分页、草稿不可见及并发发布。Docker 不可用会明确失败；没有 H2、Docker 不可用时自动跳过或硬编码本机测试库。无需启动开发 Compose，也不使用 3306/3307 的现有数据库。仅在主动修正格式时执行 `.\mvnw.cmd spotless:apply`，然后重跑上述检查。

GitHub Actions 在 PR 和 main 推送上运行 JDK 21、格式检查及同一 verify，上传测试报告。CI 不通过时不能将数据库验收视为通过。实际本机与 CI 结果见 [Stage 1 交接](docs/handoffs/stage-1.md)。

## HTTP 示例

以下 PowerShell 7+ 示例使用内存变量接收登录令牌，避免打印令牌；密码变量应由用户在本机安全输入。默认注册仅分配 USER。主办方接口需要显式 dev 初始化的主办方账户登录令牌。

```powershell
$api = 'http://localhost:8080/api/v1'
$registration = @{email='reader@example.test'; password=$localPassword} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/auth/register" -ContentType 'application/json' -Body $registration -TimeoutSec 10
$credentials = @{email='reader@example.test'; password=$localPassword} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType 'application/json' -Body $credentials -TimeoutSec 10
$headers = @{Authorization="Bearer $($login.accessToken)"}
Invoke-RestMethod -Uri "$api/auth/me" -Headers $headers -TimeoutSec 10
Invoke-RestMethod -Uri "$api/events?keyword=Java&page=0&size=20" -TimeoutSec 10

# 以下 $organizerHeaders 为主办方登录结果构建的同类 Authorization 字典。
$body = @{title='Java Meetup';description='Backend practice'} | ConvertTo-Json
$draft = Invoke-RestMethod -Method Post -Uri "$api/organizer/events" -Headers $organizerHeaders -ContentType 'application/json' -Body $body -TimeoutSec 10
$sessionBody = @{title='Morning';startsAt=[DateTimeOffset]::UtcNow.AddDays(1).ToString('o');endsAt=[DateTimeOffset]::UtcNow.AddDays(1).AddHours(2).ToString('o');location='Room A'} | ConvertTo-Json
$session = Invoke-RestMethod -Method Post -Uri "$api/organizer/events/$($draft.id)/sessions" -Headers $organizerHeaders -ContentType 'application/json' -Body $sessionBody -TimeoutSec 10
$skuBody = @{name='Standard';priceCents=1000;totalStock=20} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/organizer/sessions/$($session.id)/ticket-skus" -Headers $organizerHeaders -ContentType 'application/json' -Body $skuBody -TimeoutSec 10
Invoke-RestMethod -Method Post -Uri "$api/organizer/events/$($draft.id)/publish" -Headers $organizerHeaders -TimeoutSec 10
Invoke-RestMethod -Uri "$api/events/$($draft.id)" -TimeoutSec 10
```

S1 路由、输入和状态码的完整定义见 [API 契约](docs/02-domain-and-api-contract.md)。PATCH 只支持草稿的 title/description；列表 page 从 0 开始，size 默认 20、上限 100。时间必须带 UTC/offset，输出为 UTC。金额是整数分，票量非负；发布时必须至少有一场未来场次，每场必须有票种。发布后不再修改。跨主办方资源统一 404；无主办角色 403，未登录 401。错误固定为 `{code,message,requestId,timestamp}`，`X-Request-Id` 对应服务端生成的 requestId。

## 目录与设计

- `backend/src/main/java/com/eventflow/identity`：注册、登录、账户 DTO 与权限。
- `backend/src/main/java/com/eventflow/event`：活动、场次、票种；controller → service → XML mapper。
- `backend/src/main/java/com/eventflow/common`：统一错误、requestId、安全配置与分页响应。
- `backend/src/main/resources/db/migration`：Flyway 顺序迁移。
- `backend/src/test`：真实 MySQL 与安全边界测试。
- `infra/docker-compose.dev.yml`：本项目独立开发库。
- [ADR 0001](docs/decisions/0001-stage-1-security-and-persistence.md)：JWT、UTC、迁移、发布锁与隔离。
- [项目章程](docs/00-project-charter.md)、[架构规范](docs/01-architecture-and-code-style.md)、[阶段门禁](docs/03-stage-roadmap-and-gates.md)、[Stage 1 任务](docs/04-stage-1-codex-task.md)、[环境复用授权](docs/07-stage-0-review-and-stage-1-authorization.md)、[原始环境预检](docs/handoffs/environment-preflight.md)。

所有主办方修改都在事务中锁定活动父记录，发布再执行带 DRAFT 条件的状态更新；不使用进程内锁模拟数据库一致性。关键词通过绑定参数与 LIKE 字面通配符转义处理，分页在 SQL 层完成。设计与证据限于 Stage 1，不声明未实测的吞吐或性能提升。
