# YumpooPlatform 异机监控配置指南

本指南说明如何将 Windows 上的 YumpooPlatform 接入独立 Ubuntu 监控主机。所有地址、账号、密码、机器人 URL 和私钥均由部署者在仓库外配置；示例中的尖括号内容必须替换，不能直接用于运行。本文不记录任何实际部署凭据。

## 1. 本次实现与配置边界

| 能力 | 实现位置 | 部署时配置 |
| --- | --- | --- |
| 独立状态页、维护管理、通知记录 | `frontend/monitor-dashboard`、`packages/monitor-hub` | 独立登录凭据、业务站点、环境标识、通知通道 |
| 外网探测与指标存储 | `deployment/monitoring` | Prometheus 目标、规则、持久卷与保留时间 |
| 资源与应用趋势 | Grafana、Windows / Ubuntu exporter | 采集器、只读网关和 SSH 隧道 |
| 应用指标 | 后端内部 `/actuator/prometheus` | 保留公网拒绝规则，通过回环网关采集 |
| 发布与维护事件 | Windows 固定部署引擎 | `host.json.monitoring` 和受保护的 `monitor.json` |
| 工作流通知 | `tools/release/monitor-notify.mjs` | GitHub Variables / Secrets |

业务发布不会自动部署 Ubuntu 监控栈，也不会自动安装 Windows 的固定引擎、采集器和监督任务。完整接入需要下面三端配置，不能只设置一个开关。应用内运维中心继续保留；异机监控不依赖业务登录、业务数据库或业务通知队列。

## 2. 准备私有配置

准备业务站点的 HTTPS 根地址、Ubuntu 管理员 SSH 连接、Windows 管理员维护通道，以及运维和公告两个机器人。允许临时共用一个机器人，仍是两个逻辑通道；不能因此声称已验证两个不同群。

- 真实 IP、云主机登录账号和密码、Webhook、私钥、生成的密码及验收日志只保存在仓库外的受保护目录或 GitHub Secrets。
- 不把实际值填写到源码、本文、PR 正文、截图或测试夹具。生成目录不要放在 Git 工作树内；运行记录不要作为发布附件。
- Windows 配置目录的 ACL 仅授予管理员和 SYSTEM；Linux 敏感文件使用对应服务用户所有权及 `0600` 权限。Node 的文件 mode 不替代 Windows ACL。
- 监控服务全部监听回环地址，管理员通过自己的 SSH 连接访问。机器密钥只用于受限端口转发，不能替代管理员账号。

在受保护目录中创建 `channels.json`，填入两个真实机器人地址。不要把地址放进命令行或终端历史。

```json
{
  "operations": "<OPERATIONS_WEBHOOK>",
  "announcements": "<ANNOUNCEMENTS_WEBHOOK>"
}
```

用仓库锁定的 Node / pnpm 环境安装依赖后，从仓库根目录生成配置：

```text
node tools/monitoring/configure.mjs <protected-output-directory> https://<site-host> <environment> <protected-channels-json>
```

`site-host` 必须是业务站点；不是 Ubuntu 监控主机。URL 使用 HTTPS 根地址，不含路径、查询参数或认证信息。建议环境标识使用 `production` 等字母、数字、下划线或连字符，最长 64 字符，三端保持完全一致。

生成器拒绝覆盖已有 `secrets.json`。已接入环境应编辑原配置，不要为了修改站点或机器人重新生成整套密钥。

| 生成文件 | 放置位置 / 用途 |
| --- | --- |
| `secrets.json` | Ubuntu `/etc/yumpoo-monitor/`；Hub 配置、密码哈希、事件密钥和机器人地址 |
| `prometheus.yml` | 同上；采集目标和探测参数 |
| `alert-token` | 同上；Alertmanager 到 Hub 的认证令牌 |
| `grafana-password` | 同上；Grafana 首次初始化密码 |
| `admin-access.json` | 仅管理员在受保护位置保存；不要挂载进容器或加入仓库 |

`admin-access.json` 的 `username` / `password` 用于监控管理页，`grafanaPassword` 用于 Grafana；两者密码不同。生成器设置的是产品的独立管理账号，不是云服务器登录账号。

## 3. Ubuntu：安装并检查独立监控栈

将前四个文件安全传入 `/etc/yumpoo-monitor/`，核对 SSH 主机指纹。按[部署手册](README.md#ubuntu-安装)安装已评审的版本；网络无法拉取镜像时使用手册中的已验证离线包。

```sh
sudo MONITOR_BUILD=<reviewed-full-commit> bash deployment/monitoring/install.sh
```

安装器设置文件所有权、验证配置并启动服务，但不会替换已有业务容器或覆盖管理员配置。机器上应保留足够的磁盘及内存余量；六个容器的限制合计 1312 MiB，不代表实际持续占用量。

Hub 的关键配置在 `secrets.json`：

| 字段 | 配置含义 | 修改后生效方式 |
| --- | --- | --- |
| `environment` | 与 Windows / GitHub 事件的环境标识匹配 | 重启 Hub，并同步两端 |
| `siteUrl` | 业务站点 HTTPS 根地址，供资源发现及发布复核使用 | 同步 Prometheus 外网目标后重启 Hub |
| `channels.operations` / `channels.announcements` | 运维 / 公告机器人 URL；空值表示未配置 | 原子替换文件，最长约 30 秒热加载 |
| `sources.windows.secret` | Windows 事件 HMAC 密钥 | 同步 Windows `sourceSecret` 后协调重启 Hub |
| `sources.github.secret` | GitHub 事件 HMAC 密钥 | 同步 GitHub Secret 后协调重启 Hub |
| `sources.*.types` | 每种机器来源允许的事件类型 | 保留生成器的权限划分，勿扩大 GitHub 权限 |
| `alertToken` | 接收 Alertmanager 通知的令牌 | 同步 `alert-token` 文件，并重启 Hub 与 Alertmanager |
| `admin` | 独立管理页用户名、salt 和 scrypt 密码哈希 | 不能把明文密码写入 `passwordHash`；修改后重启 Hub |
| `database` / `uiRoot` / `discoveryFile` / `prometheusUrl` | 容器内存储、界面与回环采集位置 | 默认与 Compose 挂载匹配，通常无需修改 |
| `services` | 状态页服务及参与判定的检查项 | 与探测标签保持一致，修改后重启 Hub |

只有机器人地址支持热加载；其他字段不能依赖热加载生效。替换 `secrets.json` 后保留 uid `10001` 和 `0600`。Grafana 密码文件用于首次初始化，已有数据卷的账号密码不能靠改文件完成重置。

Prometheus 的保留时间 `30d`、容量 `10GB` 及容器内存限制在 [compose.yml](compose.yml)；修改后需要重新创建对应容器。抓取间隔、外网目标在私有 `prometheus.yml`；规则在 [rules.yml](rules.yml)。应用与 Hub 的故障确认时序有代码约束，不是任意修改抓取间隔即可同步调整的配置项。

## 4. Windows：事件、指标与固定引擎

为 Windows 与 GitHub 各生成一对独立 Ed25519 密钥。只把公钥交给 Ubuntu 的[受限账号安装脚本](install-ssh-accounts.sh)，私钥保留在各自发送端。脚本创建的是产品机器账号，禁止 shell / SFTP，仅允许指定端口转发；当前连接使用 SSH 默认端口 22。

Windows 的 `<Root>/secrets/monitor.json` 示例：

```json
{
  "eventUrl": "http://127.0.0.1:28788/internal/v1/events",
  "sourceSecret": "<COPY_FROM_SOURCES_WINDOWS_SECRET>",
  "sshHost": "<monitor-host>",
  "watchdogWebhook": "<OPERATIONS_WEBHOOK>"
}
```

- `eventUrl` 是 Windows 本机 SSH 转发入口，不填写云服务器公网 IP。
- `sshHost` 填 Ubuntu 地址或主机名，不附带账号、协议或端口；机器账号由脚本指定。
- `sourceSecret` 与 Hub 的 `sources.windows.secret` 完全一致，不使用 GitHub 的密钥。
- `watchdogWebhook` 用于监控主机失联时绕过 Hub 直达运维群；留空将没有这一路失联通知。
- 同目录的 `monitor-ssh-key` 放 Windows 私钥，`monitor-known-hosts` 放可信渠道核对过的 Ubuntu 主机公钥。不要关闭主机密钥校验。

通过管理员维护通道，从同一个经过来源、摘要和构建证明验证的服务器 ZIP 提取并运行 `Update-YumpooEngine.ps1`。保留原 `host.json` 的发布分支、不可变校验、探针、保留数量、观察时间和桌面源选项，只增加或调整：

```powershell
# 将这两个参数加入当前已验证的固定引擎升级命令，保留该命令的其余现役参数。
-EnableMonitoring -MonitoringEnvironment <environment>
```

结果应包含以下配置；不要通过直接修改 SYSTEM 引擎文件代替管理员升级流程：

```json
{
  "monitoring": {
    "enabled": true,
    "environment": "<environment>"
  }
}
```

随后按[Windows 接入说明](README.md#windows-接入)运行同包的 `Install-YumpooMonitoring.ps1`，提供固定版本且校验摘要的 exporter MSI 和网关配置。它安装采集器、回环 Nginx 网关及 `YumpooMonitorTunnel`、`YumpooMonitorRelay`、`YumpooMonitorWatchdog` 三个监督任务。首次安装和重复配置均须检查服务与实际指标，不能只看 MSI 退出码。

## 5. GitHub：变量和 Secrets

在仓库 Settings → Secrets and variables → Actions 配置。以下监控参数采用**仓库级**设置，使草稿生成、发布失败和定期检查等没有部署 Environment 的作业也能读取；业务发布自身的 Environment 与部署凭据保持既有配置。

| 类型 | 名称 | 值 / 来源 |
| --- | --- | --- |
| Variable | `MONITOR_ENABLED` | `true` 启用统一通知入口 |
| Variable | `MONITOR_ENVIRONMENT` | 与 Hub / Windows 完全相同；未设置时由通知脚本使用默认 `wecom-dev` 并在工作流摘要提示；与 Hub 不一致的事件会被拒绝，摘要单独标明 |
| Secret | `MONITOR_SSH_HOST` | Ubuntu 地址或主机名，不含账号或端口 |
| Secret | `MONITOR_SSH_KEY` | GitHub 专用 SSH 私钥完整内容 |
| Secret | `MONITOR_KNOWN_HOSTS` | 核验后的 SSH 主机公钥记录 |
| Secret | `MONITOR_SOURCE_SECRET` | Hub 的 `sources.github.secret` |

`MONITOR_ENVIRONMENT` 是通知事件标识，不会切换业务部署服务器、GitHub Environment 或发布分支。Windows `monitoring.environment` 与 Hub `environment` 也不会随仓库变量自动修改；三端不一致时事件会被拒绝。

开启统一入口后，GitHub 通知失败不会回退旧 Webhook。不要将真实机器人地址填入 workflow YAML；GitHub 只向 Hub 发送签名事件，实际机器人由 Hub 私有配置决定。

## 6. 常见调整

| 目标 | 需要修改 | 生效与核对 |
| --- | --- | --- |
| 临时机器人换正式群 | `channels` 对应项；换运维群时同步 Windows `watchdogWebhook` | Hub 最长约 30 秒热加载；Windows 看门狗发送时读取配置；核对两个逻辑通道 |
| 更换 Ubuntu 主机 | Windows `sshHost` / `monitor-known-hosts`，GitHub SSH Host / Known Hosts，新主机受限公钥与持久数据 | 重启 Windows 隧道任务，验证主机指纹与指标入口；不要删除旧数据后再验证 |
| 更换业务站点 | Hub `siteUrl`、私有 Prometheus 外网目标 | 保留事件密钥；校验 Prometheus 配置并重启对应服务，验证资源发现与公开版本 |
| 更换环境标识 | Hub `environment`、Windows `monitoring.environment`、GitHub Variable | 先处理待投递事件，协调三端切换；旧环境事件不会自动重写 |
| 暂停某个群通知 | 对应 `channels` 设为空；运维群同时核对看门狗 | 这是停止通道投递，不是停止采集或删除队列；恢复后核对未过期消息 |
| 关闭 Windows 发布接入 | 管理员升级命令显式使用 `-EnableMonitoring:$false` | 不再由该开关触发新维护事件与等待；不等于卸载采集器、隧道或看门狗 |

若关闭 `MONITOR_ENABLED`，GitHub 将使用既有通知逻辑；如旧 `WECOM_RELEASE_WEBHOOK` 仍有配置，可能恢复旧通道发送。停止所有通知时必须同时检查旧通道与独立看门狗，不能把某一个开关视作总开关。

## 7. 日常访问与验收

管理员在自己的电脑运行并保持连接：

```sh
ssh -N -L 8787:127.0.0.1:8787 -L 3000:127.0.0.1:3000 <admin>@<monitor-host>
```

打开 `http://127.0.0.1:8787` 查看状态或登录「维护与通知」，打开 `http://127.0.0.1:3000`，默认首页即 Yumpoo 异机监控看板。使用生成的独立账号和相应密码；不要使用云主机密码或业务用户密码。连接退出或电脑重启后需要重建隧道。

接入完成须同时满足：

1. 所有服务仍只监听回环地址；Windows 指标网关实际返回 CPU、内存、磁盘、关键服务与应用指标。
2. Prometheus targets 正常，真实 `probe_success` 为成功，采样新鲜；`up=1` 不能单独证明网站探测成功。
3. 两种机器密钥不能打开 shell 或管理端口；管理页和 Grafana 可用。
4. 运维、公告通道都有真实投递 ACK；普通发布预告至少 300 秒，完成公告须通过异机健康及 tag / commit 复核。
5. 断线期间事件落盘，重连补投且相同事件不重复入库；维护结束和恢复通知符合实际观测。
6. 保留至少 24 小时的采样数量、失败记录、资源和队列证据。若本机轮询中断，可查询服务器保留的精确历史窗口，并明确容器瞬时快照等无法补足的项目。

故障演练仅使用隔离环境。详细通知时序、回退语义、离线安装和测试命令见[部署与运维手册](README.md)。
