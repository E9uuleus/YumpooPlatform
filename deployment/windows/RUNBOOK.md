# M1-13 Windows 云服务器部署与验证

本手册用于把当前 M1-13 构建部署到 Windows Server 2022 云服务器并验证企微登录与通讯录合并。本次约束如下：

- 公网 IP：`1.15.24.215`。
- 域名：`wecom-dev.yumpoo.com`。
- 服务器只有 C 盘，程序、配置、数据、日志与临时文件均使用 C 盘。
- 不注册 Windows 应用服务；运行模式标识为 `MANUAL_JAVA_CONSOLE`，Spring Boot 在受控 PowerShell 控制台中手工启动和停止。
- Nginx 公网监听 443，静态 SPA 仅监听 `127.0.0.1:18173`，Spring Boot 仅监听 `127.0.0.1:8100`，PostgreSQL 仅监听 `127.0.0.1:5433`。
- 这是验证环境运行方式：关闭启动终端或重启服务器后，应用不会自动恢复。

## 1. 本地生成 M1-13 部署包

在最新 `dev` 源码根目录运行：

```powershell
pnpm install --frozen-lockfile
pnpm verify:m1-13:deployment
```

输出固定为：

```text
out\m1-13\yumpoo-windows-m1-13.zip
out\m1-13\yumpoo-windows-m1-13.zip.sha256
```

ZIP 包含当前 HEAD 构建的 JAR、Web dist、Nginx 配置、C 盘生产配置模板、数据库初始化 SQL、维护脚本和本手册；不包含 Windows 服务包装器、IIS 模板、真实密码或企微 Secret。

上传 ZIP 和 `.sha256` 后，在服务器复核：

```powershell
$zip = 'C:\Temp\yumpoo-windows-m1-13.zip'
$expected = (Get-Content "$zip.sha256" -Raw).Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)[0]
$actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected.ToLowerInvariant()) { throw '部署包 SHA-256 不匹配' }
```

## 2. C 盘目录

以管理员身份创建目录：

```powershell
$paths = @(
  'C:\Program Files\Yumpoo\releases',
  'C:\ProgramData\Yumpoo\config',
  'C:\ProgramData\Yumpoo\secrets',
  'C:\ProgramData\Yumpoo\data\attachments',
  'C:\ProgramData\Yumpoo\temp\uploads',
  'C:\ProgramData\Yumpoo\logs',
  'C:\ProgramData\Yumpoo\run',
  'C:\nginx\conf\conf.d',
  'C:\nginx\certificates'
)
$paths | ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
```

每次发布解压到独立目录，例如：

```powershell
$release = 'C:\Program Files\Yumpoo\releases\m1-13-96e118f'
Expand-Archive -LiteralPath 'C:\Temp\yumpoo-windows-m1-13.zip' -DestinationPath $release -Force
```

确认以下文件存在：

```text
<release>\server\yumpoo-server.jar
<release>\web\index.html
<release>\windows\RUNBOOK.md
<release>\windows\database\initialize-database.sql
<release>\windows\nginx\yumpoo-wecom.conf
```

初次部署创建 `current` 目录联接：

```powershell
New-Item -ItemType Junction `
  -Path 'C:\Program Files\Yumpoo\releases\current' `
  -Target $release | Out-Null
```

若 `current` 已存在，不要直接覆盖；先停止 Java 进程，确认新目录完整，再按升级记录切换联接。持久化目录始终位于 `C:\ProgramData\Yumpoo`，不得随 release 删除。

## 3. PostgreSQL 17 基础配置

安装 PostgreSQL 17 x64 后确认服务名和客户端路径：

```powershell
Get-Service 'postgresql*'
$psql = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
& $psql --version
```

在 `postgresql.conf` 中保持：

```conf
listen_addresses = 'localhost'
password_encryption = 'scram-sha-256'
```

在 `pg_hba.conf` 中把以下两行放在会匹配它们的宽泛 `host` 规则之前：

```conf
host    yumpoo    yumpoo_migrator    127.0.0.1/32    scram-sha-256
host    yumpoo    yumpoo_app         127.0.0.1/32    scram-sha-256
```

应用配置并检查数据库只监听回环：

```powershell
Restart-Service 'postgresql-x64-17'
Get-NetTCPConnection -State Listen -LocalPort 5433
```

若实际服务名不同，后续命令使用 `Get-Service 'postgresql*'` 返回的真实名称。5433 的 LocalAddress 不得是 `0.0.0.0` 或公网地址。

## 4. 初始化数据库、账号和权限

初始化脚本不包含密码，可以重复运行。它会：

1. 创建 `yumpoo_migrator` 和 `yumpoo_app` 两个非超级用户登录角色。
2. 创建 UTF-8 数据库 `yumpoo`，所有者为迁移账号。
3. 创建 `yumpoo` schema，撤销 PUBLIC 的默认数据库/schema 权限。
4. 只给应用账号 schema USAGE 和表 DML 权限，不给 CREATE、CREATEDB、CREATEROLE 或超级用户权限。
5. 设置默认权限，使后续 Flyway 新建表和序列自动授权给应用账号。

执行：

```powershell
$psql = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
$sql = 'C:\Program Files\Yumpoo\releases\current\windows\database\initialize-database.sql'
& $psql -h 127.0.0.1 -p 5433 -U postgres -d postgres -W -v ON_ERROR_STOP=1 -f $sql
if ($LASTEXITCODE -ne 0) { throw '数据库初始化失败' }
```

`-W` 会提示 PostgreSQL 管理员密码；不要把密码写入命令历史。然后打开交互终端，为两个账号分别设置不同的高强度密码：

```powershell
& $psql -h 127.0.0.1 -p 5433 -U postgres -d postgres -W
```

在 `psql` 中执行，`\password` 会分别提示两次且不回显：

```text
\password yumpoo_migrator
\password yumpoo_app
\q
```

不要在 SQL 文件中写 `PASSWORD '...'`，也不要在 PowerShell 命令行设置 `PGPASSWORD`。把这两个密码分别填入 Secret 文件的 Flyway 和 datasource 项。

复核角色属性、数据库和 schema：

```powershell
& $psql -h 127.0.0.1 -p 5433 -U postgres -d yumpoo -W -v ON_ERROR_STOP=1 -c `
  "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname IN ('yumpoo_app','yumpoo_migrator') ORDER BY rolname;"

& $psql -h 127.0.0.1 -p 5433 -U postgres -d yumpoo -W -v ON_ERROR_STOP=1 -c `
  "SELECT current_database(), nspname, pg_get_userbyid(nspowner) AS owner FROM pg_namespace WHERE nspname='yumpoo';"
```

两个角色的三个权限列必须全部为 false，`yumpoo` schema owner 必须是 `yumpoo_migrator`。

## 5. 生产配置和 Secret

复制模板：

```powershell
Copy-Item `
  'C:\Program Files\Yumpoo\releases\current\windows\config\application-prod.yml' `
  'C:\ProgramData\Yumpoo\config\application-prod.yml' -Force
Copy-Item `
  'C:\Program Files\Yumpoo\releases\current\windows\secrets\application-secrets.yml' `
  'C:\ProgramData\Yumpoo\secrets\application-secrets.yml' -Force
```

修改普通配置：

- 保持 `server.address: 127.0.0.1`、`server.port: 8100`。
- `yumpoo.wecom.oauth.enabled` 改为 true，填写真实 Corp ID、纯数字 Agent ID。
- `yumpoo.wecom.directory.enabled` 改为 true，填写同一企业 Corp ID。
- OAuth 回调保持 `https://wecom-dev.yumpoo.com/api/v1/auth/wecom/callback`。
- 所有 deployment 路径保持 `C:/Program Files/...` 或 `C:/ProgramData/...`。

修改 Secret 文件：

- `spring.datasource.password`：`yumpoo_app` 密码。
- `spring.flyway.password`：`yumpoo_migrator` 密码。
- `yumpoo.wecom.oauth.app-secret`：企微 OAuth 应用 Secret。
- `directory-secret` 与 `profile-secret`：通讯录读取所需 Secret。
- `yumpoo.session.current-key`：至少 32 字节随机值的 Base64。

生成会话密钥示例：

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

若 YAML 密码包含 `#`、`:`、前后空格等字符，使用双引号包裹。完成后限制 Secret 目录 ACL；以下 `SERVER\deploy-user` 替换为实际启动 Java 的 Windows 账号：

```powershell
icacls 'C:\ProgramData\Yumpoo\secrets' /inheritance:r
icacls 'C:\ProgramData\Yumpoo\secrets' /grant:r `
  'Administrators:(OI)(CI)F' 'SERVER\deploy-user:(OI)(CI)R'
icacls 'C:\ProgramData\Yumpoo\data' /grant 'SERVER\deploy-user:(OI)(CI)M'
icacls 'C:\ProgramData\Yumpoo\temp' /grant 'SERVER\deploy-user:(OI)(CI)M'
icacls 'C:\ProgramData\Yumpoo\logs' /grant 'SERVER\deploy-user:(OI)(CI)M'
```

## 6. 配置 Nginx

复制专用 virtual server：

```powershell
Copy-Item `
  'C:\Program Files\Yumpoo\releases\current\windows\nginx\yumpoo-wecom.conf' `
  'C:\nginx\conf\conf.d\yumpoo-wecom.conf' -Force
```

在现有 `nginx.conf` 的 `http {}` 内确保存在：

```nginx
include C:/nginx/conf/conf.d/*.conf;
```

证书默认路径为：

```text
C:\nginx\certificates\yumpoo.com.pem
C:\nginx\certificates\yumpoo.com.key
```

路径不一致时修改 snippet。确认不存在另一个使用相同 `server_name wecom-dev.yumpoo.com` 的 443 server，然后检查并重载：

```powershell
Set-Location 'C:\nginx'
.\nginx.exe -t -c conf\nginx.conf
if ($LASTEXITCODE -ne 0) { throw 'Nginx 配置检查失败' }
.\nginx.exe -s reload
```

## 7. 手工启动 Spring Boot 并执行 Flyway

先确认 PostgreSQL 正常：

```powershell
Start-Service 'postgresql-x64-17'
```

打开一个专用 PowerShell 窗口，不要关闭。运行：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:C:/ProgramData/Yumpoo/config/,file:C:/ProgramData/Yumpoo/secrets/application-secrets.yml'
$java = 'C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe'
$jar = 'C:\Program Files\Yumpoo\releases\current\server\yumpoo-server.jar'
$log = 'C:\ProgramData\Yumpoo\logs\server-console.log'

& $java '-Dfile.encoding=UTF-8' -jar $jar 2>&1 | Tee-Object -FilePath $log -Append
```

首次启动时 Flyway 使用 `yumpoo_migrator` 自动执行全部迁移。看到应用启动完成后，在另一个 PowerShell 窗口验证：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8100/actuator/health/liveness'
Invoke-RestMethod 'http://127.0.0.1:8100/actuator/health/readiness'
Get-NetTCPConnection -State Listen -LocalPort 18173,8100,5433
```

liveness/readiness 必须为 UP；18173、8100、5433 必须只绑定回环。停止应用时回到 Java 窗口按 `Ctrl+C`，等待进程完成 graceful shutdown。由于本次不注册服务，服务器重启后必须重新执行本节命令。

## 8. 核对 Flyway 与初始化业务数据

使用迁移账号检查 Flyway：

```powershell
$psql = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
& $psql -h 127.0.0.1 -p 5433 -U yumpoo_migrator -d yumpoo -W -v ON_ERROR_STOP=1 -c `
  'SELECT installed_rank, version, description, success FROM yumpoo.flyway_schema_history ORDER BY installed_rank;'
```

所有记录必须 `success = true`。再检查正式基础数据：

```powershell
& $psql -h 127.0.0.1 -p 5433 -U yumpoo_migrator -d yumpoo -W -v ON_ERROR_STOP=1 -c `
  'SELECT id, display_name, timezone, week_start_day, default_workday_minutes FROM yumpoo.company;'

& $psql -h 127.0.0.1 -p 5433 -U yumpoo_migrator -d yumpoo -W -v ON_ERROR_STOP=1 -c `
  'SELECT count(*) AS users FROM yumpoo.identity_user;'
```

Flyway 会初始化唯一 Company：

```text
id = 00000000-0000-4000-8000-000000000001
display_name = Yumpoo
timezone = Asia/Shanghai
week_start_day = MONDAY
default_workday_minutes = 480
```

不要手工插入、更新 `identity_user`、`external_identity`、`platform_role_assignment`、`app_manager_governance_state` 或 Flyway history。身份合并还会维护哈希、审计、Outbox、授权版本和治理哨兵，直接 SQL 无法满足完整业务不变量。

### 首个身份限制

当前 M1-13 正式实现存在明确的首次初始化闭环缺口：

- 企微 OAuth 登录只接受已经存在的企微外部身份。
- 手工通讯录同步只允许已登录的 `COMPANY_ADMIN` 触发。
- 空库中没有首个 identity user，也没有首个 COMPANY_ADMIN。

因此，空库可以完成数据库/Flyway、Nginx、健康探针和 OAuth 跳转验证，但不能安全完成第一次真实通讯录合并和第一次真实登录。本次包没有生产身份 bootstrap 命令。继续实际验证前必须选择：

1. 恢复一个与当前 Flyway 版本兼容、已包含首个有效企微身份和 COMPANY_ADMIN 的受控数据库备份；或
2. 另行实现并代码审查一个只允许服务器终端执行、一次性关闭的生产通讯录 bootstrap 命令。

不得在 `prod` 启用 `m1-13-e2e` 或 `YUMPOO_M113_FIXTURE_ENABLED`，不得开放匿名同步接口，也不得用手写 SQL 绕过应用服务。该缺口解决前，真实企微登录与通讯录合并应记录为 BLOCKED，而不是 PASS。

## 9. HTTPS 与 M0 基础验证

从外部网络验证 DNS：

```powershell
Resolve-DnsName wecom-dev.yumpoo.com -Type A
```

必须解析到 `1.15.24.215`。云安全组和 Windows 防火墙仅公开 443，不公开 18173、8100、5433。

```powershell
curl.exe -I https://wecom-dev.yumpoo.com/
curl.exe -i https://wecom-dev.yumpoo.com/actuator/health
curl.exe -i https://wecom-dev.yumpoo.com/_m0/
curl.exe -I https://wecom-dev.yumpoo.com/api/v1/auth/wecom/authorize
```

期望：

- 首页 200，证书主机名、链和有效期正常。
- `/actuator` 与 `/_m0` 均为 404。
- `/api` 未知路径不能返回 SPA `index.html`。
- authorize 为 302，Location 指向企业微信认证域名。
- 响应包含 HSTS、CSP、X-Frame-Options、X-Content-Type-Options、Referrer-Policy 和 Permissions-Policy。

停止 PostgreSQL 时 readiness 应变为 503/DOWN，liveness 仍为 200/UP；恢复 PostgreSQL 后 readiness 应自动恢复。

## 10. 首个身份前置条件满足后的 M1 验证

### 10.1 真实企微登录

1. 无痕浏览器访问 `https://wecom-dev.yumpoo.com/`，应进入企微认证。
2. 使用已合并的在职成员登录，回调必须返回同一 HTTPS 域名。
3. `GET /api/v1/auth/me` 返回当前用户和稳定角色集合。
4. Cookie 必须为 Secure/HttpOnly；URL 和 localStorage 不得出现 token。
5. 退出后旧 Cookie 访问受保护 API 返回 401。
6. 已离职或禁用账号的旧会话应失效。

### 10.2 通讯录合并

1. 使用 COMPANY_ADMIN 打开 `/admin/identity/overview`，确认 OAuth 和通讯录配置完整。
2. 在 `/admin/identity/sync-runs` 触发同步。新请求返回 201，同一 Idempotency-Key 重放返回 200，活动运行冲突返回 409 和 Location。
3. 等待 `SUCCEEDED` 或明确失败终态，核对新增、更新、离职/禁用、未变化和失败计数。
4. 在 `/admin/identity/members` 抽查企微稳定 ID、姓名、联系方式和部门摘要；响应不得包含 Secret 或 token。
5. 修改一个测试成员资料并再次同步，必须更新同一 identity user，不能新增重复成员。
6. 将测试成员设为离职后同步，确认账号状态和会话撤销。
7. 重复同步确认结果幂等，数据库中不存在同一企微身份映射到多个用户。

保留脱敏的 Git SHA、ZIP SHA-256、时间、同步运行 ID、状态和计数作为验证记录；不要保存 Cookie、Secret、token、手机号、邮箱或企微原始响应。
