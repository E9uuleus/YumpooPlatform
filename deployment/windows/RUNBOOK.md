# Windows Release 发布与生产运维手册

本手册是当前部署入口。常规发布采用 GitHub Release → SSH → 固定 SYSTEM 部署任务，后端以 `YumpooServer` Windows Service 运行，发布包模式为 `WINDOWS_SERVICE_WINSW`。包文件名沿用 M1-15 以兼容既有 CI 交付链；不代表代码或迁移停留在该里程碑。M1-13、M1-14 手册和历史 checklist 保留各自验收边界。Electron 继续使用 M1-14 `0.1.0` PILOT 包，本链路不提供 Electron 自动更新。

Release 发布即为上线决定。主机初始化、Environment 配置和发布链路必须先就绪；单凭仓库脚本、CI 或 Release 附件不能声称服务器已部署。真实企微通讯录、双管理员登录、后续同步和整机重启证据仍按对应 checklist 保持 `ENV_PENDING`，由目标环境验证清零；本次服务恢复验收不重启整台服务器。

第 3～7 节保留首次身份引导维护操作。M1-15 不新增 Flyway 迁移或 HTTP/OpenAPI 接口；后续版本的迁移随当前应用发布。首次引导只能在服务器终端、停服状态下执行一次，不得通过 SQL、匿名接口或 M1-13 fixture 建立管理员。

## 1. GitHub Release 自动发布

日常顺序：

1. 功能分支开 PR 合入 `dev`；
2. `dev` 开 PR 晋升到 `main`，用「Create a merge commit」合并；
3. `main` 的推送 CI 通过后，[Prepare Release](../../.github/workflows/prepare-release.yml) 自动生成草稿 Release；
4. 发布负责人核对草稿后点击 **Publish**，这一步就是上线决定；
5. [Windows Release](../../.github/workflows/windows-release.yml) 自动校验并部署，然后进入观察窗口。

**晋升门禁**

- 晋升 PR 只能来自本仓库的 `dev` 或 `hotfix/<名称>`，由 [Main Promotion Policy](../../.github/workflows/main-promotion-policy.yml) 强制检查，并在 PR 中汇总提交、迁移分类、契约变化和人工确认清单。
- 含非只扩展迁移的发布必须加 `release:breaking-migration` 标签，该版本不能快速回退。
- 契约兼容以 `main` 为基线：相对线上版本有破坏性 API 变更时，先在 `dev` 上登记检查结果给出的精确例外。

**草稿 Release**

- 草稿只来自 `main` 推送且全部 required jobs 成功的 Platform CI。
- 标签按北京时间日期自动编号，格式为 `server-vYYYY.MM.DD.N`。已有标签都占号，包括草稿和已删除的 Release 留下的标签。
- 发布说明按提交类型分组。
- 来源清单与 CI 原包在**草稿阶段**就校验并上传；中断后重跑会沿用已钉住的 CI attempt，不会换到另一次构建。
- 草稿永不自动删除：GitHub 按 ID 无条件删除 Release，清理与人工 Publish 交错时可能删掉刚发布的版本。新草稿生成时，源码是其祖先的旧草稿会在发布说明、工作流摘要和通知中标为「已被取代，请勿发布」；源码不早于它的其他草稿另行提示「发布前核对来源 SHA」。这些草稿都由发布负责人手动删除。
- 已发布更新版本时，拒绝为更旧的提交生成草稿。
- 仓库启用了 Immutable Releases：发布后标签和附件都不可更改。Windows Release 要求 Release 是不可变发布且附件齐全，只做校验和部署，不再写入附件。
- 不要手工新建 Release、手工打 `server-v*` 标签或替换附件。

**来源核验**：服务器通过 GitHub API 取得 Release 附件的 `digest` 和大小，核对上传的 `provenance.json` 原始字节和 ZIP，然后独立检查标签、同次 CI 和提交的前进关系。来源清单不需要再走 Release 下载域名；附件摘要缺失或不一致仍然拒绝。

**部署凭据与权限**：

- GitHub 托管 runner 使用 `wecom-dev` Environment。
  - secrets：`WINDOWS_DEPLOY_HOST`、`WINDOWS_DEPLOY_SSH_KEY`、`WINDOWS_DEPLOY_KNOWN_HOSTS`；
  - variable：`WINDOWS_DEPLOY_PORT`，缺省为 `22`。
- `known_hosts` 必须事先从可信管理通道确认，不能在工作流里临时接受扫描结果。
- 数据库密码、企微 Secret、证书私钥和恢复凭据都只留在服务器上。
- 非管理员账号固定为 `yumpoo-deploy`，只能上传暂存文件、触发固定任务、查询结果。
- 高权限任务执行管理员安装的 `cd` 引擎，不运行上传 ZIP 里的 PowerShell 脚本；更新引擎属于管理员维护操作。

**企业微信通知**：仓库 secret `WECOM_RELEASE_WEBHOOK` 配置后，草稿就绪、草稿生成失败、发布校验失败和部署作业失败都会推送到群里；服务器任务的最终状态由主机通知负责（见下文）。

**附件**：固定为以下六个：

- `yumpoo-windows-server-m1-15.zip`
- `yumpoo-windows-server-m1-15.zip.sha256`
- `artifact-manifest.json`：列出 ZIP 与两份 SBOM 的大小和 SHA-256
- `yumpoo-server.cdx.json`：后端 CycloneDX SBOM，与 ZIP 内 JAR 的内嵌清单相同
- `yumpoo-web.cdx.json`：Web 前端生产依赖的 CycloneDX SBOM
- `provenance.json`

**构建证明**：`main` 推送 CI 用 Sigstore 为 ZIP 和两份 SBOM 签署 SLSA 构建证明。草稿生成、发布部署、代码回退和每周发布演练都会用 `gh attestation verify` 核对：签名工作流必须是 Platform CI、源 ref 必须是 `refs/heads/main`、源提交和 CI attempt 必须与 `provenance.json` 一致。缺少或不符即拒绝，不会提交部署。

**重试**：部署失败后，优先重跑同一次 Actions run。手工入口必须在原 tag 上触发，工作流会核对执行 ref 与请求的 tag 一致：

```powershell
gh workflow run windows-release.yml --ref '<server-v...>' -f tag='<server-v...>'
```

**草稿补生成**：草稿没有自动生成，或 CI 产物过期之前需要补生成时，手工触发：

```powershell
gh workflow run prepare-release.yml --ref dev -f commit='<main 上的完整 commit SHA>'
```

**快速代码回退**：[Server Rollback](../../.github/workflows/server-rollback.yml) 只回到主机保留的上一版本，必须在**目标（上一版本）tag** 上触发，并填写原因：

```powershell
gh workflow run server-rollback.yml --ref '<上一版本 server-v...>' -f target_tag='<上一版本 server-v...>' -f reason='<原因>'
```

回退与发布共用同一条串行通道。服务器端的前提和行为见下文「代码回退」。

**热修复**：

1. 从 `main` 拉 `hotfix/<名称>`，开 PR 到 `main`；
2. 合并后同样经过「草稿 → Publish → 部署」；
3. [Back-merge Main](../../.github/workflows/back-merge.yml) 会自动开 `backmerge/<commit>` 到 `dev` 的 PR，必须用合并提交合并。已有回合并 PR 时，后续热修复会把该分支快进到最新 `main`；分支已被更新而无法快进时另开一个 PR。工作流在 `main` 和 `dev` 的每次推送后都会重新核对，合并旧回合并 PR 后仍有遗漏会再次处理。

**切换期限制**：切换后 `main` 的第一次推送以旧的初始提交为兼容基线，该基线没有数据库迁移，历史检查会按设计拒绝，CI 失败、不生成草稿。这只发生一次，经第一个晋升 PR 产生新的 `main` 推送即可恢复。切换前从 `dev` 发布的历史版本不满足 `main` 来源和元数据要求，所以不能作为回退目标。快速回退要从切换后的第二个 `main` 版本起才可用。

上传目录固定为 `C:/ProgramData/Yumpoo/deployment/inbox/<request UUID>/`。客户端将原 ZIP 切为 2 MiB 的 `package-0000.part` 起始分块，以最多四个 SFTP 连接并行上传；最后一块可以较小，包上限为 1 GiB。每块连接上限 5 分钟，整体上传上限 40 分钟；只有所有分块完成后才上传 `provenance.json`，并最后原子提交带 `packagePartCount` 的 `request.json`。服务器检查块数及每块长度，在私有工作目录按顺序组装并核验原包 SHA-256；兼容没有 `packagePartCount` 的旧 `package.zip` 请求。分块上传失败不会提交切换请求；若在最后提交时断线，应先查询该 UUID 的状态，不能仅凭客户端失败断定服务器没有执行。重试产生新的请求 UUID。

只触发 `schtasks.exe /Run /TN YumpooDeploy`，不将 SSH 参数拼成任意高权限命令。查询 `C:/ProgramData/Yumpoo/deployment/status/<request UUID>.json` 可恢复中断后的观察；状态包含 `schemaVersion`、`requestId`、`tag`、`sourceCommit`、`state`、`phase`、`updatedAt` 及可选 `error` 摘要。终态为 `succeeded`、`failed`、`rolled_back` 或 `recovery_required`；后两者不能当作候选版本发布成功。工作流部署 job 上限 90 分钟，包含最多 40 分钟上传与 40 分钟状态观察；观察超时不会终止服务器任务。

部署序列如下：

1. 在暂存区校验来源、SHA-256、ZIP 条目和目标路径、磁盘空间以及数据库恢复权限，并获取串行锁。
   - 来源必须是 `host.json` 中 `releaseBranch` 所指分支上的 push 触发的 CI。
   - `requireImmutable` 为 `true` 时，Release 必须是不可变发布。
   - 标签格式为 `server-vYYYY.MM.DD.N`。
   - 同一个已完成的版本再次提交时直接返回成功；默认禁止自动降级。
2. 只把 `wecom-dev.yumpoo.com` 这一个站点置为维护状态，然后停止 `YumpooServer`。维护标志由已安装的站点规则读取。修改版本路径配置时，先验证 Nginx 配置，再平滑重载。
3. 在同一个停服时点，备份数据库、附件、上传临时文件、生产配置、服务配置和版本指向。备份没有完整成功时，恢复旧服务，不能启动候选版本。
   - 附件中按 `sha256/xx/yy/<摘要>` 存放的已发布 blob 从不原地改写，快照用硬链接共享这些字节。
   - 其余文件仍然整体复制。
4. 解压到独立的版本目录，更新该站点的 Web 路径和后端 JAR 路径，并通过服务环境变量 `YUMPOO_DEPLOYMENT_RELEASE_TAG` 注入发布标签。然后启动服务，执行 Flyway。
5. 在维护状态下检查：回环健康、HTTPS/SNI、首页及 JS/CSS、正式认证接口 `/api/v1/auth/me`。
   - 未认证请求预期返回 `401`；不能用不存在的 `/api/auth/me` 或仅凭 Actuator `UP` 代替。
   - 包含 `windows/release-metadata.json` 的版本还要求：首页的 `yumpoo-commit` meta 与 `127.0.0.1:8100/actuator/info` 中的 commit 都等于来源提交，发布标签等于 Release tag。
6. 验收通过后开放访问，执行严格 TLS 的公网复核，记录请求 ID、版本、来源和部署结果。SSH 中断不会终止 SYSTEM 任务，重新连接后查询同一请求的状态即可。
7. 观察窗口（阶段名 `observe`）：默认 10 分钟，按截止时间计时，开始时、每 30 秒以及窗口结束时各复核一次公网站点和 readiness，并统计开放后新增的应用 `ERROR` 日志。复核本身耗时不会缩短窗口。
   - 新增 `ERROR` 超过 `observe.maxErrorLines`（默认 20），或任意一次复核失败时，状态记为 `failed/observation_failed`。
   - 这按开放后失败处理：保留业务数据，不自动恢复，由发布负责人决定是否执行代码回退。
   - 观察已经通过、只是写入完成记录失败时，状态为 `failed/completion_record_failed`：新版本已在线，由管理员核对公网身份与 readiness 后补写成功记录，不需要回退。状态文件可能正被 SFTP 读取，引擎以原子替换写入，遇到短暂占用会在约 30 秒内重试。

开放访问前失败时的处理：

- 停止候选服务，恢复旧程序、配置、版本指向以及整套数据快照，再验收旧版本。
- 附件快照在恢复前，逐个按文件名复核 blob 的 SHA-256。
- 数据库恢复先清除受管 `yumpoo` schema 内候选迁移新增的对象，再完整恢复该 schema。只替换 JAR 或只恢复部分表都不算回退。
- 恢复失败时保持维护状态，保留备份与诊断，停止自动重试。

开放访问后失败时，保留新增的业务数据，只记录失败，不自动恢复旧数据库快照。

### 代码回退

请求为 `schemaVersion: 2` 且 `mode: "rollback"` 时，引擎只换回主机上保留的上一版本（即 `current.json` 中的 `previous`），不动数据库。

前提条件（全部满足才执行）：

- 目标版本的 commit 和 tag 与 `previous` 一致；
- 目标是当前版本的祖先提交，来源校验与正常发布相同；
- 两个版本目录都还保留，且都含有 `windows/release-metadata.json`；
- 当前版本比目标版本多出的迁移全部是「只扩展」。分类规则见[迁移回退安全](../../.agents/notes/implemented/data/2026-10-15-rollback-safe-migrations.md)。

执行过程：

- 回退同样依次经过维护、停服、切换、验收、开放和观察。
- 开放访问前失败时切回原版本，状态记为 `failed/rollback_aborted_current_restored`。
- 回退成功后 `previous` 被清空，因此不能连续回退。
- 含破坏性迁移的版本无法代码回退，只能由管理员按第 7 节，用部署快照做整套恢复。

### 主机保留

每个请求进入终态后（`recovery_required` 除外），引擎自动清理：

- **删除**：
  - 已结束请求的 `inbox/<UUID>` 和 `work/<UUID>`；
  - 超过 24 小时仍未提交的上传。
- **保留**：
  - 当前版本；
  - 上一版本；
  - `retention.extraReleases`（默认 2）个最近成功的版本；
  - 以上各版本对应的部署快照（不论该次部署的最终状态，例如观察未通过但仍在线的版本）。

  其余由引擎创建的 `<commit>-<UUID>` 版本目录和快照都会被删除。
- **永不删除**：首次初始化时建立的版本目录；状态仍为 `running` 或 `recovery_required` 的请求所对应的候选目录、上传和快照。
- **拒绝删除**：含重解析点的目录。这类情况记入 `deployment/engine/retention-error.txt`。
- `failure-*.txt` 保留 90 天。

部署台账在 `deployment/engine/releases.json`；最近一次清理的结果在 `retention-last.json`。

### 通知与公开探测

**通知**：管理员在 `C:\ProgramData\Yumpoo\secrets\deploy-notify.json` 中写入：

```json
{"wecomWebhook":"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<key>"}
```

之后每个终态都会向企业微信群推送版本、短 commit、状态、阶段和耗时。

- 收到 `recovery_required` 意味着站点仍在维护，必须立即处理。
- 只接受 `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...` 这一字面形式，不允许显式端口或其他路径。该文件的权限与其他 Secret 相同。
- 发送失败只记入 `notify-error.txt`，不影响部署结果。

**公开探测**：`/healthz`（readiness）和 `/version.json`（Actuator info）由引擎写进受管的 `yumpoo-web-root.conf`。

- 只有确认该 include 位于允许写 `location` 的 server 级别时，管理员才能在升级引擎时加上 `-EnableProbeRoutes`。
- 如果 Nginx 拒绝该配置，切换会失败，并按开放访问前失败恢复。
- 外部拨测服务探测 `/healthz` 和首页；维护期间两者都返回 503。

### 升级部署引擎

Release 不能升级 SYSTEM 部署引擎，必须由管理员手动操作：

1. 从对应 Release 下载服务器 ZIP。
2. 从 Release 页面或 `provenance.json` 中抄录这个 ZIP 的 SHA-256。可以再用 GitHub CLI 独立核对构建证明（只读，不需要仓库写权限）：

   ```powershell
   gh attestation verify '<下载目录>\yumpoo-windows-server-m1-15.zip' -R E9uuleus/YumpooPlatform `
     --signer-workflow E9uuleus/YumpooPlatform/.github/workflows/m0-18-ci.yml --source-ref refs/heads/main
   ```

3. 解压 ZIP，然后以管理员身份打开 PowerShell，运行同一个包里的升级脚本：

```powershell
& '<解压目录>\windows\cd\Update-YumpooEngine.ps1' `
  -PackagePath '<下载目录>\yumpoo-windows-server-m1-15.zip' `
  -PackageSha256 '<Release 上的 SHA-256>' `
  -ReleaseBranch main -RequireImmutable
```

脚本的安全检查：

- 持有部署锁；有部署正在进行或处于 `recovery_required` 时拒绝执行。
- 校验包的哈希后，只从 ZIP 中取出固定的几个引擎文件。
- 要求升级脚本所在目录的模块与包内的模块完全一致。

脚本的结果：

- 旧引擎和旧 `host.json` 备份在 `deployment/engine/backup-<UTC 时间>`。
- `host.json` 迁移为 schema 2，写入以下字段：
  - `releaseBranch`；
  - `requireImmutable`；
  - `probeRoutes`；
  - `retention.extraReleases`（参数 `-ExtraReleases`）；
  - `observe`（参数 `-ObserveMinutes`、`-MaxErrorLines`）。
- `engine/engine.json` 记录引擎的来源 commit 和文件哈希。

未升级的 schema 1 主机保持原有行为：分支为 `dev`，不要求不可变发布，不写探测路由。切换到 `main` 发布线时，必须先用 `-ReleaseBranch main -RequireImmutable` 升级引擎，再发布第一个 `main` 草稿。

本地复核现行服务器包仍使用既有入口：

```powershell
pnpm verify:m1-15:deployment
```

输出位于 `out/m1-15`，包含 `yumpoo-windows-server-m1-15.zip`、同名 `.sha256`、`artifact-manifest.json`、两份 SBOM（`yumpoo-server.cdx.json`、`yumpoo-web.cdx.json`）和 `verification-report.json`。服务器包包含已验证的 JAR/Web、当前全部迁移、Nginx 和配置模板、数据库初始化资产、维护脚本、`service/` 锁定信息、`cd/` 部署资产、本手册及历史 checklist。WinSW 二进制从锁定官方 URL 获取并核验，不提交到仓库。不得把真实 CorpID、企微 UserID、Secret、SSH 私钥或一次性输入文件打入 ZIP。

## 2. 首次主机初始化与运行边界

管理员首次初始化使用 [Initialize-YumpooHost.ps1](cd/Initialize-YumpooHost.ps1)。先确认实际 Java 21 路径、PostgreSQL 17 服务及 `5433` 端口、当前运行 JAR 的完整路径和 PID、已有 Nginx 目录，以及当前已部署 commit。不能因模板默认 PostgreSQL 为 `5432` 而覆盖目标机的 `5433`。

```powershell
$releaseRoot = 'C:\Program Files\Yumpoo\releases\<完整 commit SHA>'
$currentCommit = '<当前已部署的完整 commit SHA>'
$legacyProcessId = <已核验的 Yumpoo Java PID>

& "$releaseRoot\windows\cd\Initialize-YumpooHost.ps1" `
  -ReleaseRoot $releaseRoot `
  -JavaPath 'C:\jdk-21.0.6\bin\java.exe' `
  -PublicKeyPath 'C:\ProgramData\Yumpoo\secrets\deploy-key.pub' `
  -CurrentCommit $currentCommit `
  -LegacyProcessId $legacyProcessId
```

示例中的 SHA 和 PID 必须替换为已核验值。初始化程序只停止匹配目标 JAR 的指定进程，禁止按 `java.exe` 批量结束进程。`-Root` 默认 `C:\ProgramData\Yumpoo`，`-PgBin` 默认 `C:\Program Files\PostgreSQL\17\bin`，`-NginxRoot` 默认 `C:\nginx`；目录不同需显式指定。

服务名固定为 `YumpooServer`，使用已锁定的 WinSW 2.12.0 和 `NT AUTHORITY\LocalService`，自动延迟启动，依赖 `postgresql-x64-17`。管理员生成实际 Java、JAR、配置绝对路径，服务不依赖登录会话或终端环境变量。LocalService 只读程序、配置与 Secret，对日志、附件、上传临时目录具有必要写权限。初始化逐一重建受管目录及现有文件的 ACL，移除较宽的显式授权，将所有者设为 Administrators；目录继承保护本身不能代替现有文件检查。路径及其祖先、受管树内均不得有重解析点。

若首次接管已经安装服务，随后恢复了健康旧版本或仅任务注册失败，不再次执行进程接管。先修正失败原因、确认维护标志已解除，再用相同的 `ReleaseRoot`、`JavaPath`、`PublicKeyPath`、`CurrentCommit` 和自定义目录参数运行初始化脚本并加 `-CompleteRegistration`，省略 `LegacyProcessId`。该入口核验 WinSW 哈希与 LocalService 身份、服务配置、版本指向、8100 监听所属的 Java 子进程、健康状态、公网站点及已安装引擎字节，再补齐固定任务；它不修改运行配置。若恢复时还原了旧日志配置，管理员应先修正失败原因并确认生产配置已关闭 `logging.console.enabled`。维护仍开启、版本不一致或存在未完成部署时必须先执行管理员恢复，不能通过注册入口跳过恢复。`deployment/engine/bootstrap.json` 记录 `prepared`、`registration_pending`、`registered` 或 `recovery_required` 状态，供管理员判断接管进度。

应用停止超时为 60 秒；异常退出依次在 10 秒、30 秒后重启，后续失败停止，运行稳定 1 小时后重置失败计数。服务模板显式使用 `<delayedAutoStart>true</delayedAutoStart>`；该大小写与 WinSW 2.12.0 实现一致。停止或恢复失败应查看服务状态与诊断，不反复强杀进程。

前端继续由现有共享 Nginx 提供。初始化只接入该站点的维护和版本路径 include，保留已有独立证书路径、HTTP-01 路径、企微验证文件、续期任务及其他站点配置，不能直接套用仓库整份 Nginx 模板。SYSTEM 开机任务只在未发现既有实例时启动 Nginx，执行时限为无限，防止任务调度器在一小时后结束其长期运行的子进程；部署任务仍限制为一小时。已有 `YumpooWebsite` 属于其他 Node 网站，不能更改。

生产迁移或维护前必须具备可核验的数据库备份和文件快照。恢复账户需要删除及恢复整个受管 `yumpoo` schema 的权限；其他 schema、数据库和 PostgreSQL 18 实例不在本部署恢复范围。正式启用前应在隔离环境演练候选迁移增加对象后的整套恢复。

`C:\ProgramData\Yumpoo` 的 config、secrets、data、temp、logs、run 和备份随版本保留。备份只允许受控维护身份读取，不得提交到仓库或放入 Web 根目录；成功发布不会自动删除唯一恢复依据。

## 3. 企微通讯录配置前置

`C:\ProgramData\Yumpoo\config\application-prod.yml` 必须启用目录同步并保持 OAuth 与目录 CorpID 一致：

```yaml
yumpoo:
  wecom:
    oauth:
      enabled: true
      corp-id: ww-replace-with-corp-id
    directory:
      enabled: true
      corp-id: ww-replace-with-corp-id
```

Secret 文件必须配置互不相同的 OAuth `app-secret`、`directory-secret`、`profile-secret`。对应企微应用必须具有读取成员 ID、成员资料和部门所需权限，云服务器实际出口 IP 必须满足企微可信 IP 约束。

正常配置中的首次引导开关保持关闭：

```yaml
yumpoo:
  maintenance:
    initial-identity:
      enabled: false
```

维护脚本只在本次无 Web 进程中通过临时环境变量打开 Runner，执行后会恢复原环境变量。

## 4. 创建 ACL 保护的一次性输入

从服务器包模板复制文件，不要在命令行中填写企微 UserID：

```powershell
$releaseRoot = (Get-Content -LiteralPath 'C:\ProgramData\Yumpoo\deployment\engine\current.json' -Raw | ConvertFrom-Json).releaseRoot
$template = Join-Path $releaseRoot 'windows\secrets\initial-identity-bootstrap.example.json'
$input = 'C:\ProgramData\Yumpoo\secrets\initial-identity-bootstrap.json'

Copy-Item -LiteralPath $template -Destination $input -Force
notepad.exe $input
```

填写真实 `expectedCorpId`、首个 APP_MANAGER 的企微 UserID、首个 COMPANY_ADMIN 的企微 UserID。两人必须不同，且均应为当前企业通讯录中的在职、启用成员。不要截图、粘贴到工单或写入终端历史。

收紧 ACL，只允许 SYSTEM、Administrators 和当前执行账号读取：

```powershell
$acl = New-Object System.Security.AccessControl.FileSecurity
$acl.SetAccessRuleProtection($true, $false)
$rights = [System.Security.AccessControl.FileSystemRights]::FullControl
$allow = [System.Security.AccessControl.AccessControlType]::Allow

foreach ($sidValue in @('S-1-5-18', 'S-1-5-32-544')) {
  $sid = New-Object System.Security.Principal.SecurityIdentifier($sidValue)
  $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($sid, $rights, $allow)))
}

$currentSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($currentSid, $rights, $allow)))
Set-Acl -LiteralPath $input -AclObject $acl

Get-Acl -LiteralPath $input | Format-List Owner,AccessToString
```

不得向 Everyone、Authenticated Users 或 BUILTIN\Users 授予读取权限。脚本和 Java Runner 都会拒绝越出 secrets root、重解析点、空文件、超过 8 KiB、错误确认短语、未知 JSON 字段或相同目标成员。

## 5. 执行一次性生产引导

首次身份引导是独立维护操作。先置站点为维护状态、完成数据库备份，停止服务，并确认 8100 无监听，再以管理员 PowerShell 执行：

```powershell
Stop-Service -Name YumpooServer
Get-NetTCPConnection -State Listen -LocalPort 8100 -ErrorAction SilentlyContinue

$java = 'C:\jdk-21.0.6\bin\java.exe'
$releaseRoot = (Get-Content -LiteralPath 'C:\ProgramData\Yumpoo\deployment\engine\current.json' -Raw | ConvertFrom-Json).releaseRoot
$jar = Join-Path $releaseRoot 'server\yumpoo-server.jar'
$script = Join-Path $releaseRoot 'windows\Invoke-InitialIdentityBootstrap.ps1'
$input = 'C:\ProgramData\Yumpoo\secrets\initial-identity-bootstrap.json'

& $script `
  -JavaPath $java `
  -JarPath $jar `
  -InputFile $input `
  -ReasonReference 'M1-15 approved production initialization'
```

命令会依次执行真实全量通讯录同步、校验两个目标、原子授予 APP_MANAGER 与 COMPANY_ADMIN、关闭 APP_MANAGER 首管闩锁并写入 Outbox 和 Security Audit。

只有目录运行 `SUCCEEDED` 且发现成员数大于 0 才会赋权。部分成功、空目录、目标缺失/离职/禁用、配置不一致、并发同步或审计失败均以非零退出，不落任何角色。目录同步已经安全写入的普通用户事实可以保留，修正配置或输入后可重新运行。

完整成功后脚本自动删除 `initial-identity-bootstrap.json`：

```powershell
Test-Path 'C:\ProgramData\Yumpoo\secrets\initial-identity-bootstrap.json'
```

期望为 `False`。失败时期望为 `True`，便于受控修正；不得在失败诊断中输出或复制文件内容。

## 6. 重启服务与验收

确认首次引导开关仍关闭，使用已安装的服务启动正常 prod 进程：

```powershell
Start-Service -Name YumpooServer
Get-Service -Name YumpooServer
```

在服务器验证，完成后才解除维护状态：

```powershell
Invoke-RestMethod http://127.0.0.1:8100/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8100/actuator/health/readiness
```

随后执行真实验收：

1. APP_MANAGER 使用 Chrome 扫码登录，`GET /api/v1/auth/me` 具有 APP_MANAGER，不具有 COMPANY_ADMIN。
2. COMPANY_ADMIN 使用 Chrome 扫码登录，`GET /api/v1/auth/me` 具有 COMPANY_ADMIN，不具有 APP_MANAGER。
3. COMPANY_ADMIN 打开 `/admin/company/overview`，确认 OAuth 和通讯录状态完整。
4. 在 `/admin/company/sync-runs` 再执行一次正式手工同步，确认 `SUCCEEDED` 和幂等行为。
5. APP_MANAGER 使用正式角色治理 API 授予更多 COMPANY_ADMIN；不得重新运行 M1-15。
6. 检查 Security Audit 存在双角色和总体 bootstrap 成功事实，正文不含企微 UserID。

重复执行首次引导必须以“首次身份引导已永久关闭”失败。真实环境检查仍在本地 checklist 中保持 `ENV_PENDING`，不要把 Cookie、UserID、Secret、企微 code/state 或原始供应商响应写入验证报告。

## 7. 故障诊断、脱敏与回退

- `INITIAL_IDENTITY_BOOTSTRAP_WECOM_CONFIG_INVALID`：检查目录启用、CorpID 一致及三个 Secret 相互独立。
- `INITIAL_IDENTITY_BOOTSTRAP_SYNC_FAILED`：检查企微权限、可信 IP、出口网络和目录/profile Secret，不盲目重试凭据错误；具体供应商错误只在既有脱敏同步运行诊断中查看。
- `*_TARGET_NOT_FOUND/INELIGIBLE`：在企微后台确认两名成员为当前企业在职成员，修正受保护输入后重试。
- `INITIAL_IDENTITY_BOOTSTRAP_SYNC_CONFLICT`：确认没有其他目录同步，等待活动租约结束后重试。
- `INITIAL_IDENTITY_BOOTSTRAP_PERMANENTLY_CLOSED`：首次引导已关闭或已有平台角色；改用正式治理 API/APP_MANAGER break-glass，不修改数据库。
- `INITIAL_IDENTITY_BOOTSTRAP_ROLE_REJECTED`：同步后角色事务前置状态发生变化；核对审计与治理状态，不通过 SQL 重开入口。

首次身份引导诊断只保留 requestId、目录运行 ID、阶段和稳定错误码，不记录企业微信外部 UserID 或供应商响应。应用运行日志的字段和查询边界见下节。Nginx 不得记录包含 OAuth query 的 `$request_uri`、`$args` 或完整 `$request`。

首次身份引导本身不新增 Flyway。它的角色与审计写入必须作为完整事务保留；若必须撤销已成功的首次引导事实，只能在独立批准的停服恢复中使用执行前数据库备份，不执行逆向 SQL 或手工删除角色。常规 Release 回退遵循第 1 节的开放访问边界，不能套用早期 M1-15「仅切回 JAR」的历史假设。

## 8. 运维中心与应用日志

当前版本追加运维迁移 V58/V59。仅 `APP_MANAGER` 可见独立「运维」模块，入口 `/admin/operations/overview`，包含主机指标、有效会话、运行日志和告警。公司管理员没有该权限。规则修改需要 CSRF 和版本条件，产生安全审计；确认告警不会关闭告警，恢复或停用规则才结束事件。

生产模板显式设置 `logging.console.enabled: false` 和 `logging.file.name: C:/ProgramData/Yumpoo/logs/yumpoo-server.log`。常规应用日志写入 JSON 文件，避免请求线程依赖交互式控制台的写入锁；是否曾由 QuickEdit 触发无法仅凭线程阻塞证明。健康检查跳过业务请求结束日志，因此 Actuator 正常不能独立证明该问题消失。验收需连续请求 `/api/v1/auth/me`，确认及时返回且对应 requestId 已写入 JSON 日志。

启动预检要求日志文件是 `yumpoo.deployment.log-root` 内的绝对路径；移动日志根时同步修改文件路径和目录 ACL。默认开发路径为 `out/logs/yumpoo-server.log`，可用 `YUMPOO_LOG_FILE` 配置。JSON 文件按 UTC 日期及 50 MB 滚动为 `.yyyy-MM-dd.N.gz`，保留至多 90 天且总量不超过 10 GB，达到任一限制都会清理旧归档。WinSW 持续接收剩余 stdout/stderr，保留启动失败等诊断，按每文件 10 MB 滚动并保留 8 份归档；它不是运维中心的历史检索数据源。开发环境的人读控制台格式保持不变。

日志由统一映射脱敏，包含稳定 event、requestId、内部用户 UUID、耗时和受控异常堆栈。禁止把日志目录配置为 Web 静态目录或扩大其 ACL。日志 API 不提供文件下载，不接受文件路径；查询最多 24 小时，文件扫描最多 3 秒/256 MiB，繁忙时返回 429。界面出现缺口、文件变化或 partial 时缩小区间重查；游标 5 分钟过期、进程重启后失效。无匹配日志不等于期间没有发生故障。

指标每 15 秒采样，内存保留 1 小时、数据库分钟历史保留 14 天；告警历史保留 180 天。运维与 Actuator 请求不计入业务 HTTP 指标。P95 由不带路由标签的全局业务请求计时器计算，低请求量或缺失采样显示未知/空白；主机图表请求量统一显示次/分钟，阈值读取当前启用规则。采样使用独立执行器，数据库探针阻塞不占用 Outbox/附件的业务调度线程。连接池忙显示降级，本机告警观测继续采集，最多保留 240 份；连接恢复后按原采样时间顺序每轮评估至多 40 份，告警窗口不依赖 I/O 完成频率。运行中探针的等待时长作为数据库延迟下界。数据库长时间故障时，待写分钟桶最多 60 个；两类积压的丢弃数都在页面明示。

数据库完全不可达会同时影响认证，应用内中心无法替代外部存活监测；连续数据库失败在连接恢复后补记，进程停止期间没有采样。当前出口为运维页面和徽标，未配置 P3 外部通知。构建信息中的 commit 可通过 Maven `-Dyumpoo.build.commit=<commit>` 注入，未注入显示 `unknown`；不要把它当成已验证的部署版本。

运维表随正常数据库备份一起保护。回滚到旧应用保留 V58/V59 表，禁止为了回滚修改既有 Flyway checksum 或手工删除运维数据。首次身份引导的 M1-15 回退边界仍适用前节。

## 9. 项目永久删除与清除续跑

负责人或企业管理员先归档项目，再在“归档项目”页输入准确项目编号计划永久删除。默认缓冲期为 30 天，到期前 1 天提醒；页面显示服务端确切截止时间。缓冲期内可撤销，撤销后仍为归档状态，需另行恢复。`YUMPOO_PROJECT_DELETION_GRACE_PERIOD` 和 `YUMPOO_PROJECT_DELETION_REMINDER_LEAD` 可配置为正 ISO-8601 Duration，提醒提前量必须小于缓冲期。

服务每分钟领取到期项目，按通知、动态、附件、Outbox、工作项、看板引用、项目目录七阶段清除，每批最多删除 500 行。领取后项目对所有人不可见，不能撤销；安全审计和治理记录保留。附件物理清除属于该业务操作，不受附件维护 dry-run 配置控制，共享 blob 保留到最后一个引用消失。

进程中断或物理文件删除失败时保留阶段 checkpoint，租约到期后自动续跑。诊断沿 requestId、内部项目 UUID 和阶段查询应用日志，检查数据库可用性、附件目录 ACL 与文件占用；修复后等待正常轮询。不要手动跳过阶段、删除 checkpoint 或删附件 metadata 来模拟成功。完成记录 `project_purge_run.completed_at`、系统安全审计 `PROJECT_PURGED` 与 ID/时间完成事件在最后一个事务同时提交。
