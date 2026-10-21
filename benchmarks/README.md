# 隔离读性能实验

使用项目局部的 [autocannon](https://github.com/mcollina/autocannon)，不全局安装压测工具。仅支持 `127.0.0.1/localhost` 和 `eventflow-bench-*` 项目名；脚本拒绝非空活动集，绝不清空已有库。需要已有 Node 24、pnpm 11、可用 Docker 及完整部署镜像。

从根目录执行 PowerShell 7。先按 [部署说明](../infra/README.md) 生成仅本地的随机凭据文件，再用**新的项目名**创建隔离卷。不能复用 E2E/个人开发库：

```powershell
$benchEnv = '.build-cache/stage4-bench.env'
./infra/New-LocalStackEnv.ps1 -OutputPath $benchEnv
$benchProject = 'eventflow-bench-local-001' # 每次新实验更换；不删除上次卷
docker compose --env-file $benchEnv -p $benchProject -f infra/docker-compose.full.yml up --build -d --wait --wait-timeout 300
$env:BENCH_PROJECT = $benchProject
$env:BENCH_ENV_FILE = (Resolve-Path -LiteralPath $benchEnv).Path
$env:BENCH_URL = 'http://127.0.0.1:8088'
$env:BENCH_ISOLATED_ACK = 'YES'
$env:TEMP = Join-Path (Get-Location) '.build-cache/tmp'
$env:TMP = $env:TEMP
$env:npm_config_cache = Join-Path (Get-Location) '.build-cache/npm'
$env:XDG_CACHE_HOME = Join-Path (Get-Location) '.build-cache/xdg'
$env:COREPACK_HOME = Join-Path (Get-Location) '.build-cache/corepack'
$env:PNPM_HOME = Join-Path (Get-Location) '.build-cache/pnpm-home'
Set-Location benchmarks
pnpm install --frozen-lockfile --store-dir ../.build-cache/pnpm-store --registry=https://registry.npmjs.org
node --env-file=../.build-cache/stage4-bench.env read.mjs
Set-Location ..
docker compose --env-file $benchEnv -p $benchProject -f infra/docker-compose.full.yml stop
```

如果另一个全栈项目已使用 8088，先安全 `stop` 它或为本实验选择另一个 `SITE_PORT` 并同步 `BENCH_URL`。不要停止原生 MySQL/Redis。测试结束保留本项目卷，不执行删除卷或 Docker 清理命令。生成的凭据不可提交；`node --env-file` 只为本进程读取它。

## 测量定义

- 通过真实 API 创建 100 个发布活动，每活动 1 场次、1 票种，票价 1000 分、库存 100。先逐个读取，预热 JVM/JDBC/SQL 页缓存；这里的“冷”只指 **Redis 元数据未命中**，不是机器或 JVM 冷启动。
- 两个阶段均为 5 轮 × 100 次详情请求，5 个连接、pipeline=1。每轮按全局递增序列访问同一组 100 个 ID，每 ID 一次。冷阶段每轮前仅对**私有 Redis** 的 generation 做 INCR；旧 generation 自然 TTL 过期。热阶段沿用最后一轮缓存，不发管理写请求。
- 每次响应校验 HTTP 200、发布状态、场次/票种及库存 100。Redis INFO 的 keyspace misses 必须分别为 500/0，否则实验失败；INFO hits 包括 generation 读取，不能直接叫应用命中率。私有项目无其他访问时，用 `详情数 - misses` 推算详情命中率。健康检查 PING 不计 GET 命中。
- 保存每次响应时间样本、autocannon 全部轮次输出、最近秩 p50/p95、HTTP/网络/校验错误率、测量轮次持续时间、硬件、镜像和 Node 版本。数据准备与轮间 INCR 不进入响应时间。短轮次包含 autocannon 采样结束等待，不拿总时长倒算“最大 QPS”。
- 超时、非 2xx、响应校验失败会停止；每轮最多 60 秒，部分请求不算通过。这是闭环、固定负载的本机实验，不能外推生产容量、开放负载排队或高可用。**热详情仍查询 MySQL 实时库存**。

原始实测：[stage-4-read-local.json](results/stage-4-read-local.json)。最终数值和环境见 [Stage 4 交接](../docs/handoffs/stage-4.md)，不预设性能目标。

## 写正确性与可靠性

写实验继续使用 `Stage2IntegrationTest.thousandDistinctUsersAndKeysOverRealHttpNeverOversellAndWriteSanitizedArtifact` 和本阶段 Wrapper `verify` 输出：1000 不同身份、100 库存、100 成功、900 售罄、余量 0。429 不是售罄。真实原始结果在 `backend/target/stage-2-concurrency-result.json`，本阶段保存副本到 `docs/handoffs/stage-4-concurrency-local.json`。

可靠性使用 [Stage 3 真实故障证据](../docs/handoffs/stage-3-fault-local.json) 与 [源码/故障阅读指南](../docs/learning/03-redis-rabbitmq-reliability.md)。本阶段完整后端回归重新运行这些真实故障测试，不用缓存或 MQ 取代数据库库存/状态约束。
