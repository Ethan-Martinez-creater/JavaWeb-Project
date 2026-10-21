# Stage 4 Docker Compose 部署

`docker-compose.dev.yml` 仍只启动开发用 MySQL、Redis 和 RabbitMQ。完整应用使用 `docker-compose.full.yml`，内部地址由 Compose DNS 提供；只有 Nginx/Vue 网站发布到宿主机 `127.0.0.1:8088`。数据库、Redis、RabbitMQ 和 Spring Boot 均没有宿主端口。

## 本地启动

从仓库根目录在 PowerShell 7 中运行。先检查已有 Docker Desktop Linux 引擎状态，不要更改 Windows、WSL 或 Docker 全局配置。若 Compose 端口 8088 被占用，可在本地环境文件中改 `SITE_PORT`。

```powershell
Set-Location D:\JavaWeb_Project
.\infra\New-LocalStackEnv.ps1 -OutputPath .build-cache/stage4.env

docker compose --env-file .build-cache/stage4.env -p eventflow-full -f infra/docker-compose.full.yml up --build -d
docker compose --env-file .build-cache/stage4.env -p eventflow-full -f infra/docker-compose.full.yml ps
```

脚本只在项目工作区创建被 Git 忽略的 `.build-cache/stage4.env`，每项密码由本地密码学随机数生成；如果文件已存在，脚本拒绝覆盖。它不会输出密码。应用的 `dev` profile 显式启用主办方初始化，只使用该文件里单独配置的 ORGANIZER 账号。公开注册接口仍然只创建 USER。

打开 <http://127.0.0.1:8088/>。Nginx 对浏览器提供同源 `/api/v1` 与 `/actuator/health` 代理；MySQL Flyway 会在 Spring Boot 启动时执行 V1、V2、V3。Compose 将等待 MySQL、Redis 和 RabbitMQ 的健康检查通过后再启动后端，再等后端 readiness 后启动网站。

## 服务与数据隔离

完整栈包含 MySQL 8.4、Redis、RabbitMQ、Spring Boot 与 Nginx 托管的 Vue，共五类服务。镜像版本固定；MySQL、Redis、RabbitMQ 沿用已验证的 digest。Spring 构建使用仓库内 Maven Wrapper，Vue 用 Node 24 与 pnpm 11。

完整开发栈项目名固定为 `eventflow-full`。Compose 会建立 `eventflow-full_mysql_data`、`eventflow-full_redis_data` 与 `eventflow-full_rabbitmq_data`；它们与既有 `eventflow_mysql_data`、`eventflow_redis_data`、`eventflow_rabbitmq_data` 开发卷分开。完整部署不绑定 3306、6379、5672、15672 或 Stage 3 开发端口。

容器重建和停止会保留命名卷。安全停止方式：

```powershell
docker compose --env-file .build-cache/stage4.env -p eventflow-full -f infra/docker-compose.full.yml down
```

命令没有 `-v`；再次运行 `up` 会读回同一数据库和消息数据。请勿使用 `down -v`、`system prune` 或移除已有卷。如果要验证持久性，在创建并发布一项活动后执行 `down`，再以相同项目名 `eventflow-full` 执行 `up -d`，检查活动和账号仍在。用不同 `-p` 值会创建另一组卷及一套独立数据库。

## 真实浏览器 E2E

`docker-compose.e2e.yml` 只增加浏览器测试容器，不属于五服务开发部署。Playwright 与浏览器在匹配版本的 Microsoft Playwright 镜像中运行。测试项目应使用一次性项目名，例如 `eventflow-e2e-<run-id>`，从空数据库执行 Flyway，然后由真实前端和 Spring/MQ 服务完成发布、浏览、预约、模拟支付、码轮换、核销和通知。测试服务将脱敏 JSON、Playwright 报告与截图写到 `e2e/artifacts`。报告只保留测试标题、状态和耗时，不保存 Playwright 失败调用日志，因为其输入回显可能包含密码或一次性核销码。

浏览器测试要求 `.build-cache/stage4.env` 中的 `SPRING_PROFILES_ACTIVE=dev`、`DEV_ORGANIZER_EMAIL` 和 `DEV_ORGANIZER_PASSWORD` 已配置。使用全新 E2E 项目时，它会创建独立命名卷，不访问 `eventflow-full` 或三服务开发数据库；测试完成后不调用 `down -v`。把随机 E2E 项目名与卷保留，便于检查失败数据和人工复盘。

```powershell
$e2eProject = 'eventflow-e2e-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
docker compose --env-file .build-cache/stage4.env -p $e2eProject -f infra/docker-compose.full.yml -f infra/docker-compose.e2e.yml up --build --abort-on-container-exit --exit-code-from e2e
$e2eExit = $LASTEXITCODE
if ($e2eExit -ne 0) { throw "Playwright E2E failed with exit code $e2eExit" }
```

首次镜像和包需要网络下载。E2E 项目名唯一可保证第一次运行是空库；CI 在每个全新 runner 上运行，因此 CI 的 E2E 库也是空的。对失败运行，请保留输出和卷；修复后另建项目名复跑。

## 镜像与秘密

`infra/.env.example` 是空值模板，不包含可用凭据。密码、JWT 密钥、数据库用户名和开发主办方凭据都通过本机环境文件传入容器；启动时缺失的必须值会被 Compose 拒绝。不要提交 `.build-cache/stage4.env`，不要把实际秘密粘到 issue、日志或截图。

所有容器通信限制在专属 Compose 网络中。Nginx 与 Spring 分别使用非 root 运行用户；中间件镜像自身管理其专属数据用户。开发栈的 MySQL 数据卷不会挂载到主机目录，不会更改本机 MySQL 5.7。
