# M1-15 Windows 生产首次身份引导运行手册

本手册是当前部署入口。M1-13、M1-14 手册保留为版本化历史。M1-15 只发布 Windows x64 服务器包；Electron 继续使用 M1-14 `0.1.0` PILOT 包。真实企微通讯录、双管理员登录和后续同步证据保持 `ENV_PENDING`，由 M6-01 在目标环境清零。

M1-15 不新增 Flyway 迁移或 HTTP/OpenAPI 接口。首次引导只能在服务器终端、停服状态下执行一次，不得通过 SQL、匿名接口或 M1-13 fixture 建立管理员。

## 1. 生成与核验服务器产物

在 Windows x64 开发机仓库根目录执行：

```powershell
pnpm verify:m1-15:deployment
```

输出位于 `out/m1-15`：

- `yumpoo-windows-server-m1-15.zip`
- `yumpoo-windows-server-m1-15.zip.sha256`
- `artifact-manifest.json`
- `verification-report.json`

服务器包包含 JAR、Web、Nginx、配置模板、V1～V15 迁移、数据库初始化资产、两个维护脚本、本手册和 checklist。不得把真实 CorpID、企微 UserID、Secret 或一次性输入文件打入 ZIP。

## 2. 部署 M1-15 与执行前备份

把服务器 ZIP 解压到新的并行版本目录，例如：

```text
C:\Program Files\Yumpoo\releases\m1-15-<git-short-sha>
```

不要覆盖 `C:\ProgramData\Yumpoo` 中已有的 config、secrets、data、logs 和 run。确认现有配置仍使用目标机实际 PostgreSQL 端口；已部署环境为 5433 时不得被模板的 5432 覆盖。

先停止 Java 进程，确认 8100 已无监听：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8100 -ErrorAction SilentlyContinue
```

输出必须为空。然后使用目标机既有备份规范备份 PostgreSQL；至少生成一次带时间戳的自定义格式备份并核验文件存在：

```powershell
$pgDump = 'C:\Program Files\PostgreSQL\17\bin\pg_dump.exe'
$backup = 'C:\ProgramData\Yumpoo\data\backup\yumpoo-before-m1-15.backup'

& $pgDump `
  -h 127.0.0.1 `
  -p 5433 `
  -U yumpoo_migrator `
  -d yumpoo `
  -F c `
  -f $backup `
  -W

Get-Item -LiteralPath $backup | Format-List FullName,Length,LastWriteTime
```

同时备份当前 ProgramData 配置和 Secret 文件的受控副本；不得把备份提交到仓库或放入 Web 根目录。

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
$template = 'C:\Program Files\Yumpoo\releases\current\windows\secrets\initial-identity-bootstrap.example.json'
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

确认 8100 无监听后，以管理员 PowerShell 执行：

```powershell
$java = 'C:\jdk-21.0.6\bin\java.exe'
$jar = 'C:\Program Files\Yumpoo\releases\current\server\yumpoo-server.jar'
$script = 'C:\Program Files\Yumpoo\releases\current\windows\Invoke-InitialIdentityBootstrap.ps1'
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

用正常 prod 配置启动服务：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = `
  'file:C:/ProgramData/Yumpoo/config/,file:C:/ProgramData/Yumpoo/secrets/application-secrets.yml'

$java = 'C:\jdk-21.0.6\bin\java.exe'
$jar = 'C:\Program Files\Yumpoo\releases\current\server\yumpoo-server.jar'
$log = 'C:\ProgramData\Yumpoo\logs\server-console.log'

& $java '-Dfile.encoding=UTF-8' -jar $jar 2>&1 |
  Tee-Object -FilePath $log -Append
```

在另一 PowerShell 验证：

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

M1-15 不新增 Flyway。成功前回退时停止 Java、把 `current` 指回 M1-14 并恢复正常配置即可；成功后的用户、身份、角色、审计和 Outbox 与 M1-14 schema 兼容。若必须撤销已成功的首次引导事实，只能停服并恢复执行前数据库备份，不执行逆向 SQL 或手工删除角色。

## 8. 运维中心与应用日志

当前版本追加运维迁移 V58/V59。仅 `APP_MANAGER` 可见独立「运维」模块，入口 `/admin/operations/overview`，包含主机指标、有效会话、运行日志和告警。公司管理员没有该权限。规则修改需要 CSRF 和版本条件，产生安全审计；确认告警不会关闭告警，恢复或停用规则才结束事件。

生产模板显式设置 `logging.file.name: C:/ProgramData/Yumpoo/logs/yumpoo-server.log`。启动预检要求它是 `yumpoo.deployment.log-root` 内的绝对路径；移动日志根时同步修改文件路径和目录 ACL。默认开发路径为 `out/logs/yumpoo-server.log`，可用 `YUMPOO_LOG_FILE` 配置。JSON 文件按 UTC 日期及 50 MB 滚动为 `.yyyy-MM-dd.N.gz`，保留至多 90 天且总量不超过 10 GB，达到任一限制都会清理旧归档。WinSW/stdout 或上述 `Tee-Object` 捕获的是便于阅读的控制台日志，不是历史检索数据源；应独立配置其保留策略，避免重复日志无限增长。

日志由统一映射脱敏，包含稳定 event、requestId、内部用户 UUID、耗时和受控异常堆栈。禁止把日志目录配置为 Web 静态目录或扩大其 ACL。日志 API 不提供文件下载，不接受文件路径；查询最多 24 小时，文件扫描最多 3 秒/256 MiB，繁忙时返回 429。界面出现缺口、文件变化或 partial 时缩小区间重查；游标 5 分钟过期、进程重启后失效。无匹配日志不等于期间没有发生故障。

指标每 15 秒采样，内存保留 1 小时、数据库分钟历史保留 14 天；告警历史保留 180 天。运维与 Actuator 请求不计入业务 HTTP 指标。P95 由不带路由标签的全局业务请求计时器计算，低请求量或缺失采样显示未知/空白；主机图表请求量统一显示次/分钟，阈值读取当前启用规则。采样使用独立执行器，数据库探针阻塞不占用 Outbox/附件的业务调度线程。连接池忙显示降级，本机告警观测继续采集，最多保留 240 份；连接恢复后按原采样时间顺序每轮评估至多 40 份，告警窗口不依赖 I/O 完成频率。运行中探针的等待时长作为数据库延迟下界。数据库长时间故障时，待写分钟桶最多 60 个；两类积压的丢弃数都在页面明示。

数据库完全不可达会同时影响认证，应用内中心无法替代外部存活监测；连续数据库失败在连接恢复后补记，进程停止期间没有采样。当前出口为运维页面和徽标，未配置 P3 外部通知。构建信息中的 commit 可通过 Maven `-Dyumpoo.build.commit=<commit>` 注入，未注入显示 `unknown`；不要把它当成已验证的部署版本。

运维表随正常数据库备份一起保护。回滚到旧应用保留 V58/V59 表，禁止为了回滚修改既有 Flyway checksum 或手工删除运维数据。首次身份引导的 M1-15 回退边界仍适用前节。
