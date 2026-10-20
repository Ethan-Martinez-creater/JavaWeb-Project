# Stage 0 环境预检

## 1. 检测环境与范围

- 检测日期：2026-10-20，Asia/Shanghai；仓库 main 基准：`fc10d5c5770ad958e6bbc7719801e5c9e00d353c`。
- 执行位置：Codex 桌面端的本地 Windows 终端，工作目录为仓库本地检出；不是云端 Linux 沙盒、开发容器或 CI。能够读取本机文件、注册表和部分宿主机服务信息；常规命令在受限沙箱中执行，OS、服务及端口等受阻读取通过授权的只读宿主机命令补查。
- 系统：Microsoft Windows 10 家庭中文版，10.0.19045（build 19045），OS/进程架构均为 x64；PowerShell 7.6.5。
- 结论覆盖这台 Codex 所在 Windows 本机，不代表其他电脑、远程服务器或未来部署机器。Codex 继承的 PATH 也未必等同于用户另开的交互终端，且混有 Codex 自带运行时。
- 已阅读根目录 `AGENTS.md` 与 `docs/06-environment-preflight.md`，并只读查阅架构文档的版本基线。仅提交本报告；未运行构建、测试、依赖解析、安装、升级、镜像下载、迁移或服务启停，未更改 PATH/JAVA_HOME、数据库内容或项目基线。

## 2. 版本矩阵

状态含义：检测到二进制不等于验证服务连接；`UNKNOWN` 表示没有足够证据，`NOT ACCESSIBLE` 表示本次访问失败。未对整机所有磁盘作穷举扫描，PATH 未找到不等于整机未安装。

| 组件/层次 | 检测命令或只读证据 | 实际结果 | 项目目标基线 | 后续评估 |
|---|---|---|---|---|
| OS / 架构 / shell | `Get-CimInstance Win32_OperatingSystem`；RuntimeInformation；`$PSVersionTable` | Windows 10 家庭中文版 10.0.19045；x64；pwsh 7.6.5 | 未限定 OS；需支持后续工具链 | REVIEW：Docker 运行条件尚未验证 |
| Git | `git --version` | 2.54.0.windows.1；已抓取并检出最新 main | Git 可用 | REUSE |
| 默认 JDK / 编译器 | `Get-Command java,javac -All`；`java -version`；`javac -version`；JAVA_HOME | Oracle Java/Javac 17.0.2；JAVA_HOME 指向 JDK 17；PATH 中只发现这一套 Java/Javac | Java 21 编译目标 | REVIEW：默认选择不满足目标 |
| 非默认 JDK 21 | 读取 `HKLM:\SOFTWARE\JavaSoft\JDK\21.0.9` 的 JavaHome；直接执行对应 java/javac 的 `-version` | `D:\JAVA21`：Java 21.0.9，build 21.0.9+7-LTS-338；Javac 21.0.9；已实际执行，未加入当前 PATH | Java 21 | REUSE 候选：现有 JDK 已满足目标主版本；切换方式待授权 |
| 非默认 JDK 8 | 标准 Java 安装目录枚举；直接执行对应 java/javac 的 `-version` | Java/Javac 1.8.0_291；另有 JRE 目录；注册表另列 Java 8u321，但未执行核实该 JRE | 不用于本项目 Java 21 目标 | REVIEW：保留现有用途，不推断可用于本项目 |
| Maven | `mvn -version` | Apache Maven 3.8.6；实际采用 Oracle Java 17.0.2；默认 locale zh_CN / platform encoding GBK | 后续提交固定版本 Maven Wrapper；当前尚无 Wrapper/POM | REVIEW：已有 Maven 可执行，Java 选择及 Wrapper 版本待评估；未构建 |
| Docker CLI | `docker --version` | 29.7.2，build a7dcaa6；Windows/amd64；卸载注册表列 Docker Desktop 4.86.0（元数据） | 后续 Compose / Testcontainers | REUSE 候选，仅 CLI 已验证 |
| Docker Compose | `docker compose version` | v5.3.1 | Compose；未固定具体版本 | REUSE 候选，仅版本命令已验证 |
| Docker daemon | `docker version --format '{{json .}}'` | NOT ACCESSIBLE；context 为 desktop-linux；Server=null；退出码 1，dockerDesktopLinuxEngine 命名管道不存在 | 可连接的 Docker engine | REVIEW：阻断容器与 Testcontainers 验证；未启动 daemon |
| 本地 Docker 镜像 | daemon 不可连接，按任务书跳过 `docker image ls` | NOT CHECKED；不能推断有无缓存镜像 | MySQL 8.4；Redis/RabbitMQ 以后评估 | NOT CHECKED |
| MySQL 客户端 | `mysql --version` | Distrib 5.7.24，Win64 x86_64 | MySQL 8.4 为数据库基线；客户端非强制同版 | REVIEW：客户端版本不代表服务端 |
| MySQL 服务端二进制 | 直接执行与 mysql 客户端同目录的 `mysqld.exe --version` | MySQL Community Server 5.7.24，Win64 x86_64；仅验证该二进制 | 默认隔离 MySQL 8.4 | CONTAINER 候选；不据此更改基线或升级现有实例 |
| MySQL 在线实例 | `Get-Service`；筛选 Win32_Service；只读核对 3306 监听归属 | MySQL 服务 Running，且其进程拥有 3306 监听；运行中进程可执行路径 NOT ACCESSIBLE；未登录、未执行 SELECT VERSION()，在线版本/认证/SQL 可用性 UNKNOWN | MySQL 8.4；真实 MySQL 集成测试 | REVIEW：不可把上述 5.7.24 二进制直接当成在线版本 |
| MariaDB | `Get-Command mariadb -All`；相关服务/卸载项筛选 | 未发现 PATH 命令或匹配服务/卸载项；整机安装情况 UNKNOWN | 无替代基线授权 | NOT CHECKED：未执行版本命令 |
| Redis 服务端二进制 | `redis-server --version` | 8.4.0，64-bit，build 813a9dd6fb3d2910；Windows Cygwin 发行包 | Stage 3；尚未固定 Redis 版本 | REVIEW：需评估发行包与后续容器方案 |
| Redis 客户端 / 在线服务 | `redis-cli --version`；相关服务与端口读取 | 客户端 8.4.0；未发现匹配 Windows 服务；6379 未监听；未连接 Redis，在线服务版本 UNKNOWN | Stage 3；可考虑 Compose 隔离 | CONTAINER 候选；本轮不引入 |
| RabbitMQ | `Get-Command rabbitmqctl -All`；相关服务、标准安装目录及卸载项筛选 | 未发现工具/匹配服务或安装登记；5672 未监听；安装及服务端版本 UNKNOWN；因工具未发现，未运行 rabbitmqctl | Stage 3；尚未固定版本 | CONTAINER 候选；不声明整机未安装 |
| 本机默认 Node.js | `Get-Command node -All`；`node --version`；二进制 FileVersion | PATH 首选 `D:\node16\node.exe`，实际 v24.15.0；目录名 node16 不代表版本 | Stage 4；未固定 Node 版本 | REVIEW：已可执行，前端兼容性尚未评估 |
| Codex 自带 Node.js | 对 Codex runtime 中的 node 执行 `--version` | v24.19.0；PATH 的第二个 Node 候选，与本机默认版本分列；个人目录已脱敏 | Codex 工具运行时，不作为项目基线 | REVIEW：单独记录工具运行时，不据此认定用户项目使用该版本 |
| npm | 读取默认 npm 启动脚本；使用默认 node 直接运行其 npm-cli.js，参数 `--version --logs-max=0 --update-notifier=false` | 11.12.1；与本机默认 Node 对应；未安装依赖 | Stage 4；未固定版本 | REVIEW |
| pnpm | 读取默认 pnpm 启动脚本/package.json；默认 node 直接运行其 pnpm.mjs，参数 `--version` | 11.21.0；实际执行成功；不是通过 Corepack 下载执行 | Stage 4；未固定版本 | REVIEW |
| Spring Boot / MyBatis 等项目依赖 | 只读查阅架构文档；未执行 Maven/npm 解析 | 未检查本地缓存或可解析性；不是单独安装的系统软件 | Boot 4.1.1、MyBatis Starter 4.1.0；Security/Flyway/JDBC 等由后续构建解析 | NOT CHECKED：等待开发授权后再验证 |

npm/pnpm 的版本检测直接调用已安装的入口；没有运行 Corepack、npx 或任何安装命令。版本命令仅证明工具可以执行，不证明应用构建或运行已通过。

### 端口快照

通过 `Get-NetTCPConnection -State Listen` 只读取指定端口，不记录 PID、用户名或无关进程。

| 本机端口 | 检测时监听情况 | 含义 |
|---|---|---|
| 3306 | 已监听，归属 MySQL 服务 | 默认 MySQL 主机端口存在冲突；未来隔离方案需评估端口映射 |
| 6379 / 5672 / 8080 / 5173 | 未监听 | 当时未观察到监听；不保证以后可用，也不代表相关软件未安装 |

## 3. 可复用性初判与待评估问题

- **REUSE**：Git；非默认 JDK 21.0.9 是可复用候选。当前无需因默认 Java 17 就推断必须新装 JDK。
- **REVIEW**：由评估者确认是否在后续构建中显式选择现有 JDK 21，并确定 Maven Wrapper 版本；本轮未切换 Java 或修改 Maven 配置。
- **CONTAINER**：按既有 MySQL 8.4 基线优先评估隔离实例；保留已有本机数据库。Redis/RabbitMQ 仅列为以后阶段的容器候选。
- **REVIEW**：Docker daemon 不可访问的原因、Windows/Docker 后端适配与 3306 映射方案；本轮不启动/修复 Docker，不改变现有服务。
- **NOT CHECKED**：实际数据库认证/在线版本、Redis/RabbitMQ 连接、缓存镜像、构建与集成测试、前端依赖兼容性。
- 待后续版本评估确认：现有 JDK 21 的选用方式；MySQL 8.4 隔离及端口方案；Docker 可用性与未来 Node/npm/pnpm 固定版本。以上均为问题与候选方案，不构成版本升级、基线变更或 Stage 1 批准。

## 4. 客观限制与阻断

1. 普通沙箱读取 CIM/端口曾返回“拒绝访问”；Redis 版本命令曾报 `couldn't create signal pipe, Win32 error 5`。授权宿主机只读补查后已取得 OS、端口及 Redis 版本，不能把首次失败当成未安装。
2. 沙箱内与授权宿主机读取均遇到 Docker daemon 命名管道缺失，只能确认客户端/Compose 版本，未枚举镜像或验证容器。不能据此断言 Docker 一定未启动或未安装。
3. MySQL 未提供既有只读账号；未猜测凭据、读取配置密码或尝试认证。服务 Running 和端口监听不能替代 SQL 层验证。安装二进制版本与在线版本明确分列。
4. PATH/服务/注册表/已检查目录不能证明整机所有软件均已发现；无权限的进程路径、其他盘符、自定义便携安装、其他用户或远程服务仍可能不可见。
5. Git 首次拉取受代理影响；仅为单次 Git 命令覆盖代理后，成功抓取 main。没有更改全局代理或 Git 配置。仓库没有 Maven Wrapper/POM，本轮未创建。
6. 本轮仅文档核对和环境读取；没有应用测试结果，不宣称构建、连接或 Stage 0 审批通过。PR 保持打开、不得合并；等待版本评估与下一步授权。

## 5. 用户可选的本地只读补查

已访问本机，无需重复整套盘点。若需核实用户交互终端与 Codex 的差异，可在 **PowerShell 7+** 运行：

```powershell
$PSVersionTable.PSVersion
Get-Command java,javac,mvn,node,npm,pnpm,docker -All -ErrorAction SilentlyContinue |
    Select-Object Name,Source
java -version
javac -version
mvn -version
node --version
& 'D:\JAVA21\bin\java.exe' -version
& 'D:\JAVA21\bin\javac.exe' -version

# 只读取当前 daemon；失败时停止，不启动 Docker，不拉镜像。
docker version
if ($LASTEXITCODE -eq 0) {
    docker image ls --format '{{.Repository}}:{{.Tag}}' |
        Where-Object { $_ -match '(^|/)(mysql|redis|rabbitmq|node):' }
}

Get-Command rabbitmqctl,mariadb -All -ErrorAction SilentlyContinue |
    Select-Object Name,Source
Get-Service |
    Where-Object { $_.Name -match 'mysql|maria|redis|rabbit|docker' } |
    Select-Object Name,Status
```

如已有获授权的数据库只读账号，可自行替换下面的账号占位符，在本机交互输入密码（不要把密码或连接串发到聊天/报告）：

```powershell
mysql --no-defaults --protocol=TCP --host=127.0.0.1 --port=3306 --user=EXISTING_READONLY_USER --password --connect-timeout=5 --batch --skip-column-names --execute='SELECT VERSION();'
```

该查询只核实在线 MySQL 版本；没有账号时跳过，不新增账号、不修改权限或数据库。Docker 若仍失败，仅提供脱敏错误，等待评估后另行授权处理。
