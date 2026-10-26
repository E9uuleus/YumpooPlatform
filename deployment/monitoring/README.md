# 异机监控与企微通知

此栈独立于 Windows 业务服务、业务数据库及业务 Release。实现位于 `packages/monitor-hub`、`frontend/monitor-dashboard`；组件配置由本目录维护。六个容器的内存上限合计 1312 MiB，Prometheus 最多保留 30 天或 10 GB，事件与已结束投递保留 180 天。未解决告警和待投递消息不会因年龄自动删除。

首次接入、配置项对照、换机器人或主机以及开关行为见[异机监控配置指南](CONFIGURATION.md)。本手册侧重部署命令、运行约束和验收。两份文档均只使用占位符与产品默认值；实际云主机连接信息、密码、私钥和机器人地址应保存在仓库外。

## 配置与凭据

配置由管理员在仓库外生成。两个企业微信群分别添加「群机器人」，把 Webhook 放入受保护的 `channels.json`：

```json
{
  "operations": "",
  "announcements": ""
}
```

本地建议存放在仓库外、仅当前用户与 SYSTEM 可读写的受保护目录。可以临时指向同一个机器人；限流按机器人地址计算，两个逻辑通道共用 15 条/分钟额度。

```powershell
node tools/monitoring/configure.mjs <protected-output-directory> https://<site-host> <environment> <channels-json>
```

输出 `secrets.json`、`prometheus.yml`、`alert-token`、`grafana-password` 与仅管理员保留的 `admin-access.json`。前四项放入 Ubuntu `/etc/yumpoo-monitor/`；不要把 `admin-access.json` 放入容器挂载目录。安装脚本设置不同组件所需的所有权，密钥文件权限为 0600。

`secrets.json.channels.operations`、`.announcements` 是运行时可变配置，服务每 30 秒重新读取并验证 URL；修改时在该目录原子替换整个文件，并保留 uid 10001 与 0600 权限。无效配置不会替换当前有效地址。切换通道后，未完成消息使用新地址投递。管理页面只显示配置状态，不返回 Webhook。Windows 看门狗使用独立受保护配置中的 `watchdogWebhook`，换运维机器人时也需同步修改。

## SSH 与监听范围

所有服务只监听 Ubuntu 回环地址：状态/管理页面 8787、机器事件入口 8788、Grafana 3000、Prometheus 9090、Alertmanager 9093、blackbox 9115、node_exporter 9100。不要将这些端口开放到公网。

管理员使用自己的 SSH 账号：

```sh
ssh -N -L 8787:127.0.0.1:8787 -L 3000:127.0.0.1:3000 <admin>@<monitor-host>
```

打开 `http://127.0.0.1:8787`，独立账号见受保护的 `admin-access.json`；Grafana 位于本机 3000 端口，使用其单独密码。当前成员公告不附加这个仅管理员可访问的地址。

为 Windows 与 GitHub Actions 各生成一对专用 Ed25519 密钥，在 Ubuntu 执行：

```sh
sudo bash deployment/monitoring/install-ssh-accounts.sh <windows-public-key> <github-public-key>
```

两个账号仅允许公钥认证、指定端口转发，`MaxSessions 0` 禁止 shell、SFTP 和命令会话。Windows 账号可以向 Ubuntu 转发本机指标网关，并连接事件入口；GitHub 账号仅允许事件入口。不能转发管理页面、Grafana 或任意地址。主机密钥通过已有可信管理员连接核对后分发，不使用关闭 host-key 校验的连接。

## Ubuntu 安装

先确认磁盘、Docker、端口和既有容器；使用独立项目 `yumpoo-monitor`。配置准备好后，在评审过的提交运行：

```sh
sudo MONITOR_BUILD=<full-commit> bash deployment/monitoring/install.sh
```

Docker Hub 不可达时，在联网机器的干净工作区运行 `node tools/monitoring/bundle.mjs <output-directory>`。经 SSH 传输生成的包，展开 `source.tar`；核对传输前后的 SHA-256，然后运行：

```sh
sudo MONITOR_BUILD=<full-commit> MONITOR_OFFLINE_DIR=<absolute-bundle-directory> bash deployment/monitoring/install.sh
```

离线安装先核对整个镜像归档和各镜像配置 ID，再使用 `compose.offline.yml` 中的本地锁定镜像，禁止在线拉取。后续 Compose 操作须同时传入 `-f compose.yml -f <bundle>/compose.offline.yml`。保留前一份已验证镜像包，以便独立回退监控栈。

只升级通知服务、且双方已有相同摘要的五个基础监控镜像时，可使用 `node tools/monitoring/bundle.mjs <output-directory> --hub-only` 生成较小的增量包。安装仍核验全部镜像 ID；目标缺少任一锁定基础镜像时会失败，需要改用完整包。

数据位于 `/var/lib/yumpoo-monitor/{hub,prometheus,alertmanager,grafana,discovery}`。不随代码升级删除数据卷。SQLite 使用 WAL 与 FULL 同步；复制数据库需要先停止 hub 或使用 SQLite 在线备份，不能只复制活动的主数据库文件。此功能不承担业务数据备份。

## Windows 接入

在 `C:/ProgramData/Yumpoo/secrets/` 配置以下文件，ACL 仅 Administrators 与 SYSTEM：

- `monitor.json`：`eventUrl` 固定为 `http://127.0.0.1:28788/internal/v1/events`，`sourceSecret` 对应 Ubuntu 配置的 `sources.windows.secret`，`sshHost` 指向监控主机，`watchdogWebhook` 指向运维机器人。
- `monitor-ssh-key`：Windows 专用私钥。
- `monitor-known-hosts`：已核对的 Ubuntu SSH 主机公钥记录。

使用正常 Prepare Release 生成的已验证服务器 ZIP，通过管理员维护通道运行 `Update-YumpooEngine.ps1 -EnableMonitoring -MonitoringEnvironment <environment>`，保留原有其他选项。旧 `host.json` 默认关闭；普通业务部署不会安装或升级 SYSTEM 引擎。

下载官方 `windows_exporter-0.32.0-amd64.msi`，安装脚本核对固定 SHA-256，使用 `ADDLOCAL=DefaultFeature REMOVE=FirewallException` 安装主程序并移除防火墙例外，监听 127.0.0.1:9182。首次安装不能只传 `REMOVE`，否则可能返回成功但没有安装主程序。执行同包内的 `Install-YumpooMonitoring.ps1 -ExporterMsi <msi> -GatewayConfig <yumpoo-monitor.conf>`：等待采集服务运行，再配置只读 Nginx 网关、校验后 reload，注册隧道、事件转发和看门狗三个独立任务。不会停止业务服务。

接入后核对 CPU、内存、磁盘和服务指标均有实际数据；0.32.0 的 CPU 累计时间指标为 `windows_cpu_time_seconds_total`，告警规则和看板须使用同一名称。

重复运行安装器时，已安装的同一 MSI 产品会显式重装主功能与服务参数，并启动采集服务；仅重复传入 `ADDLOCAL` / `REMOVE` 会跳过上游的默认重装分支，却仍终止原进程。重复接入也必须以服务运行和网关返回指标为完成条件。

隧道在 Windows 上监听 127.0.0.1:28788，并把只读的 19100 指标网关转发到 Ubuntu 回环端口。网关只接受 `/metrics/windows` 与 `/metrics/app` 的 GET；其他路径全部拒绝。应用的 `/actuator/prometheus` 仍被公网 Nginx 拒绝。

本地发布事件先原子写入 `deployment/monitor-outbox`，独立 relay 确认收到中心 ACK 才标记已发送。5 分钟预告超过截止时间直接过期；事件按请求与阶段幂等，中心拒绝用迟到的维护事件覆盖最终结果。relay 网络失败按事件退避重试（最多间隔 5 分钟），不占用部署锁；永久拒绝保留为 `.dead`，修复原因后由管理员改回原 `.json` 文件名重投。队列长度、拒绝数和任务心跳通过 exporter textfile 采集。隧道断开自动重连；看门狗发现监测端不可达或 Prometheus / Alertmanager 采样异常持续 3 分钟时，直接通知运维群，未恢复时最多每小时重复一次。监测链路需要连续新鲜健康 60 秒才确认恢复。事件入口在链路故障时仍可接收并持久化事件，不因采集故障拒绝发布事实。

机器入口 `/healthz` 的 `status` 表示事件服务存活，`monitoring` 表示采集及告警链路是否新鲜可用；独立看门狗检查两者。Grafana 的回环 metrics 也纳入 Prometheus，单独故障仍由正常告警链路报告。升级既有监控栈时，应同步由 `configure.mjs` 生成的 Prometheus 采集配置；安装器不会覆盖管理员已有配置。

## GitHub 接入

仓库 Secrets：`MONITOR_SSH_HOST`、`MONITOR_SSH_KEY`、`MONITOR_KNOWN_HOSTS`、`MONITOR_SOURCE_SECRET`。使用 GitHub 专用密钥与 `sources.github.secret`；不要复用 Windows 密钥。仓库变量 `MONITOR_ENABLED=true` 启用新入口，`MONITOR_ENVIRONMENT` 与 Hub 及 Windows 的环境标识一致，未设置时兼容默认 `wecom-dev`。所有通知仅进入运维通道，GitHub 来源不能声明主机部署成功或建立维护窗口。

每次调用短暂建立 SSH 隧道，HMAC 绑定原始 JSON、发送时间与随机 nonce。中心允许 5 分钟时钟偏差并拒绝 replay；重投用新 nonce 和相同事件 ID。投递最多重试三次，失败写入 Actions 摘要；未收到中心 ACK 的事件没有持久化保证。启用统一入口后不回退旧 Webhook，避免双发。

## 通知与维护语义

- 黑盒探测与指标每 30 秒采样；访问持续失败 90 秒通知运维，持续 5 分钟通知公告群。恢复需要连续健康 60 秒。采样丢失或进程重启中断连续窗口，状态为未知，不补零、不算恢复。
- 应用规则复用运维中心已提交的活动缓存，CPU/磁盘告警归主机 exporter；数据库不可达无法写入规则表时另使用内存失败次数。采样、I/O、规则评估分别导出时间，过期会告警。
- 自动维护从实际进入维护开始，最长 30 分钟；只有带 `expected_maintenance` 的关联应用故障被抑制。超时、恢复失败立即解除静默，继续保留真实访问记录。
- 服务端成功必须同时满足主机成功、健康与目标 tag/commit 一致；桌面成功还检查 `release.json` 与 `latest.yml` 的版本、文件名、大小和更新摘要一致，以及安装包 HTTP/长度。复核失败只通知运维，不改变主机状态、不自动回滚。回退公告说明实际版本。
- SQLite 事务同时提交事件与消息。每个机器人每分钟最多尝试 15 条，检查 HTTP 和 `errcode=0`，UTF-8 正文不超过 4096 字节。指数退避最多 1 小时，12 次失败后进入 dead，管理页可重试未过期消息。服务恢复会终止旧故障消息的重试。
- 消息发送成功但 ACK 写盘前宕机仍可能重复，这是外部 Webhook 不提供幂等键时的至少一次投递边界。
- 两台机器同时失联没有第三方观测者；Windows 看门狗本身不能消除该盲区。

## 验证与 24 小时观察

本地执行 hub 测试、仪表板构建、`node tools/monitoring/check.mjs --rules` 与 Windows 监控测试。部署后检查所有监听地址、Prometheus targets、两种机器密钥的端口允许列表和 shell 拒绝、临时机器人两个通道的 ACK、重启后数据库与投递记录保留。

构建应用 JAR 与监控页面后，运行 `pnpm exec tsx tools/monitoring/drill.mts`。脚本创建独占的临时 PostgreSQL 容器和本机应用进程，停止数据库并观察 90 秒故障通知，再停止应用，验证独立状态页与 API 仍可访问。它不读取生产连接配置、不发送真实通知，退出时只清理自己创建的进程和容器；证据保存在忽略目录 `out/monitoring/drill/`。

故障演练使用隔离服务与数据库，不主动停止生产业务。覆盖短抖动、长故障、未知采样、维护超时、错误版本、损坏清单、安装包不可达、非零企微 errcode 与网络中断。持续观察至少 24 小时：资源上限、容器重启、采样连续性、磁盘增长及通知积压；将时间范围和结果保存在受保护的部署记录，不能把短时检查称为 24 小时验收。
