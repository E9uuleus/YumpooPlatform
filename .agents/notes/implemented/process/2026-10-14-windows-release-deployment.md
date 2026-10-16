# Agent Note: Windows Release 发布、服务身份与失败恢复边界

Status: implemented

## Problem

CI 已验证并打包 Windows 服务端，但上线仍依赖管理员交互式 PowerShell、手工复制和版本路径切换。后端进程与终端会话绑定，既有线程诊断还观察到请求结束日志等待控制台写入锁；Actuator 跳过该请求日志，因此健康检查正常不能排除业务请求阻塞。QuickEdit 是否为最初诱因并未证实。

目标机同时运行其他网站和 PostgreSQL 18；Yumpoo 使用 PostgreSQL 17、独立 HTTPS 证书及续期任务。自动发布必须绑定经过验证的同一产物，在短暂停服期间保护数据库与附件的一致性，并避免部署账号获得任意 SYSTEM 命令执行能力。

## Decision

发布入口为 `release.published` 的 `server-v*` 标签，Release 发布本身就是上线决定。标签必须指向发布线 `main` 上已通过完整推送 CI 的提交（分支模型、草稿与晋升准则见 [main 发布线决策](2026-10-15-main-release-line.md)），来源绑定 source commit、CI run、attempt 和服务器 artifact，拒绝 PR 临时产物与“最新成功包”选择。发布阶段不重新构建；经清单、大小和 SHA-256 验证的原包、SBOM 及 `provenance.json` 在草稿阶段就保存为 Release 附件，发布后由不可变发布锁定，部署只校验不写入。原包和 SBOM 还须带有同一次 CI attempt 签署的构建证明（见[供应链基线](2026-10-16-supply-chain-baseline.md)）。手工重试仍核验原标签和执行 ref。

SSH 使用固定主机密钥，私钥仅存于 `wecom-dev` Environment。`yumpoo-deploy` 是非管理员账号，权限限于暂存上传、触发固定 `YumpooDeploy` 任务及查询脱敏状态。SYSTEM 任务执行管理员安装的部署引擎，不执行 ZIP 中的脚本；上传中的 `request.json` 最后原子提交。任务不依赖 SSH 会话，串行处理，使用请求 UUID 查询结果；同一已完成版本幂等返回，默认禁止自动降级；唯一例外是下文受限的代码回退请求。数据库恢复凭据、企微 Secret 和证书私钥保留在主机受控目录。

首个 Release 的两次托管 runner 上传均因单连接慢链路超过 10 分钟而失败，未提交请求、未进入停服阶段。传输改用 2 MiB 分块和最多四连接并发，全部完成才提交含 `packagePartCount` 的请求；服务器限制块数、长度和总量，组装后仍验证原 CI ZIP 的 SHA-256。保留旧单 ZIP 协议兼容；每块上限 5 分钟、整体上传 40 分钟，部署 job 上限 90 分钟。分块只改变传输，不改变 Release 附件或来源绑定。

服务器访问 Release 下载域名也出现超时，因此来源清单改用 GitHub API 返回的附件 `digest` 和大小核对上传的原始字节，不重复下载。该摘要来自服务器独立 HTTPS 请求，不能用上传方自报摘要替代；原有清单字段、标签、CI run/attempt 和前进关系校验继续执行，缺失摘要时拒绝。

后端采用已锁定的 WinSW 2.12.0，服务名 `YumpooServer`，身份为 `NT AUTHORITY\LocalService`，自动延迟启动并依赖 `postgresql-x64-17`。生产安装时生成绝对 Java、JAR 和配置路径，授予只读程序/配置/Secret 与必要数据目录写权限。首次接管只停止匹配已核验目标 JAR 的指定 PID。共享 Nginx 增加无既有实例时才启动的 SYSTEM 开机任务，只修改 Yumpoo 站点的维护和版本路径 include，保留独立证书、HTTP-01、企微验证文件、续期及其他站点。

主机初始化拒绝路径祖先及受管目录中的重解析点，逐项重建已有目录和文件的 ACL，并将所有者设为 Administrators，不能依赖顶层关闭继承去清除子文件的宽权限。已安装且健康的旧服务可通过 `-CompleteRegistration` 继续未完成的固定任务注册；入口必须核验服务身份、锁定包装器、进程与版本指向、维护状态、健康及已安装引擎字节，不重新接管进程或改写运行配置。共享 Nginx 开机任务不设置执行时限，避免任务调度器终止长期运行的 Nginx 子进程；普通部署任务保留一小时限制。

生产配置关闭 `logging.console.enabled`，保留既有 JSON 文件格式、脱敏映射以及 50 MB / 90 天 / 10 GB 轮转边界。剩余 stdout/stderr 仍由 WinSW 接收，以 10 MB、8 份归档独立轮转，保存启动失败诊断。此项部分替代[运维中心决策](../architecture/2026-09-26-operations-center.md)中生产使用人读控制台的默认行为；开发控制台格式、文件查询与诊断权限仍由原 Note 负责。

部署先校验来源、包路径、磁盘空间和恢复权限，再将该站点置为维护状态、停服并备份数据库、附件、上传临时文件、配置及版本指向。完整备份成功后切换独立版本目录，启动新服务执行迁移；通过回环健康、HTTPS/SNI、静态资源和正式认证接口检查后才开放访问，再做公网复核。

开放访问前失败时恢复同一停服时点的数据与程序快照。数据库恢复必须清除候选迁移产生的受管对象并完整重建 `yumpoo` schema，不反向执行迁移、不修改 Flyway checksum、不只替换 JAR。恢复失败保持维护状态并保存诊断，停止自动重试。开放访问后产生的新业务事实优先保留，不自动恢复旧数据库快照。

[部署引擎](../../../../deployment/windows/cd/YumpooDeployment.psm1)在此边界上补充以下能力：

- **配置兼容**：`host.json` schema 2 增加 `releaseBranch`、`requireImmutable`、`probeRoutes`、`retention` 和 `observe` 五个字段。所有读取都带默认值：缺字段时分支为 `dev`，不要求不可变发布，不写探测路由，未升级的主机行为保持不变。
- **版本注入与核对**：切换时向服务注入 `YUMPOO_DEPLOYMENT_RELEASE_TAG`。对带发布元数据的版本，验收要求首页 `yumpoo-commit` meta 和本机 Actuator `info` 的 commit 等于来源提交，标签等于 Release tag。这一规则依据[发布版本标识](2026-10-15-release-version-identity.md)。
- **观察窗口**：开放访问后进入 `observe` 阶段，按截止时间覆盖完整窗口、窗口末端必复核一次，按设定周期复核公网站点和 readiness，并统计开放后新增的 `ERROR` 日志。超限时按开放后失败处理，状态为 `observation_failed`，不自动回退。
- **代码回退**：请求 schema 2 以 `mode: rollback` 只回到 `current.previous` 保留的上一版本，前提是目标为当前版本的祖先，且两版之间新增迁移全部只扩展（依据[迁移回退安全](../data/2026-10-15-rollback-safe-migrations.md)）。回退只替换版本指针、不动数据库；开放前失败时切回原版本。成功后清空 `previous`。
- **主机保留**：每个终态后清理已结束和超过 24 小时未提交的上传，保留当前版本、上一版本和若干最近成功版本，以及这些版本各自部署时的快照（不论该次部署最终状态），只删除引擎创建的 `<commit>-<UUID>` 目录；状态未解决的请求所对应的目录一律保留。含重解析点的树拒绝删除；单项失败不中断其余清理。部署台账记录在 `engine/releases.json`。
- **附件快照**：附件中内容寻址的已发布 blob 用硬链接进入快照，恢复前按文件名复核 SHA-256；其余文件照旧复制。
- **通知**：终态向管理员配置的企业微信群机器人推送版本、短 commit、状态和阶段，只接受不带显式端口的 `https://qyapi.weixin.qq.com/cgi-bin/webhook/send` 字面地址。发送失败不改变部署结果。
- **引擎升级**：只能由管理员运行 [`Update-YumpooEngine.ps1`](../../../../deployment/windows/cd/Update-YumpooEngine.ps1)。脚本持有部署锁，校验服务器包 SHA-256，只从 ZIP 提取固定引擎文件，并保留旧引擎与 `host.json` 备份。

实现依据：[Release workflow](../../../../.github/workflows/windows-release.yml)、[来源校验](../../../../tools/release/policy.mjs)、[主机初始化](../../../../deployment/windows/cd/Initialize-YumpooHost.ps1)、[固定部署入口](../../../../deployment/windows/cd/Invoke-YumpooDeploy.ps1)、[部署引擎](../../../../deployment/windows/cd/YumpooDeployment.psm1)、[运行手册](../../../../deployment/windows/RUNBOOK.md)。[CI 决策](2026-09-08-ci-pr-reliability.md)继续拥有构建验证、handoff 同字节及 required checks 的既有约束，本 Note 不替代它。WinSW 字段以 [2.12.0 配置文档](https://github.com/winsw/winsw/blob/v2.12.0/doc/xmlConfigFile.md)及[同版本解析器](https://github.com/winsw/winsw/blob/v2.12.0/src/WinSW.Core/Configuration/XmlServiceConfig.cs)为依据；延迟启动使用大小写精确的 `delayedAutoStart` 节点。

## Alternatives considered

- 合入 `dev` 后立即上线：CI 成功不等于发布时点已经确定，使用 Release 作为明确上线动作。
- 发布阶段重新构建或取最新成功包：不能证明与被审查提交、同次 CI 及已测试字节一致，采用来源绑定及不可覆盖附件。
- 给 SSH 账号管理员权限，或让 SYSTEM 直接运行上传包内脚本：扩大了凭据失窃和任意脚本执行的影响，采用受限账号与管理员维护的固定引擎。
- 仅关闭 QuickEdit 或长期使用终端捕获日志：诱因未经证实，也未消除请求线程对控制台的依赖，采用服务托管并关闭生产常规控制台日志。
- 新建前端应用服务或覆盖整份 Nginx 模板：当前静态前端无此需要，还会破坏共享站点及已部署证书，采用局部 include 与现有 Nginx。
- 启动失败后只切回 JAR：候选迁移可能新增对象、附件及业务处理也可能改变文件，必须恢复同一停服点的数据库与文件集合。
- 开放访问后仍自动恢复旧数据：会丢失已接受的新业务事实，改为保留数据、报告失败并由后续受控修复处理。
- 观察失败后自动代码回退：误报会让站点在无人确认时来回切换；观察只报告并通知，回退由负责人通过受限请求触发。
- 远程触发整套数据恢复：会丢弃开放后的业务事实，且需要高权限恢复凭据参与远程流程；远程只允许不动数据库的代码回退，整套恢复保留为主机本地管理员操作。
- 快照继续全量复制附件：每次发布占用一份完整附件空间，并随保留数量成倍增长；blob 不可变，硬链接在相同恢复语义下几乎不占额外空间。
- 由 Release 自带新引擎并自动安装：普通发布将能提升自身的 SYSTEM 执行权限，违背固定引擎边界；改为管理员显式升级。

## Consequences

短暂停机是发布协议的一部分；维护状态、停服时点、开放时点和最终状态必须可追溯。来源清单、部署状态与备份证明不同事实：CI 成功、包存在、服务运行均不能单独代表真实站点验收完成。包名保留 M1-15 兼容现有 artifact 消费者，运行模式改为 `WINDOWS_SERVICE_WINSW`，包含当前全部迁移与 `service/`、`cd/` 资产；历史 evidence 和 checklist 不改写为 PASS。

恢复凭据拥有清除 `yumpoo` schema 的权限，必须在隔离演练中验证完整恢复及对象清除边界，不能扩大到其他 schema、数据库或 PostgreSQL 实例。Secret 和包含配置的数据快照受管理员 ACL 保护；主机引擎更新需要独立管理员维护，普通 Release 不能提升自己的执行权限。

硬链接快照与在线 blob 共享同一份文件 ACL，快照目录的管理员 ACL 不能阻止通过在线路径改写字节；这依赖 blob 只写一次的不变量，并由恢复前的摘要复核兜底。探测路由写入受管 include 前需要管理员确认其位于 server 级别，否则 Nginx 校验失败会使切换按开放前失败恢复。切换前的历史版本缺少发布元数据，既不能作为代码回退目标，也不进行版本核对。

生产日志文件继续服务于运维中心，验证需要业务请求完成且对应 requestId 落盘；只检查 liveness/readiness 不足。目录 ACL、服务异常恢复、HTTPS、其他网站及数据库/附件整套恢复需要对应环境证据。仓库实现不冒称目标机已完成初始化或发布；整机重启后的真实恢复留到维护窗口，不由服务重启测试替代。业务 HTTP/OpenAPI 与 Electron 自动更新均未扩展。
