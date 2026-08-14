# Yumpoo Windows M0-16 开发部署包

本目录只提供参数化模板、供应链锁定信息和 dry-run/static validation。M0-16 不会安装 IIS、注册 Windows 服务、修改 ACL/防火墙或生成目标服务器 `PASS` 证据；ZIP 也不是可直接投产的安装器。

## 目录约定

- release：`C:\Program Files\Yumpoo\releases\<版本>`，`current` 指向当前发布内容。
- 普通配置：`C:\ProgramData\Yumpoo\config\application-prod.yml`。
- Secret：`C:\ProgramData\Yumpoo\secrets\application-secrets.yml`。
- 会话密钥：`yumpoo.session.current-key` 使用至少 32 字节随机值的 Base64；轮换时同时配置 previous key/version/accept-until，窗口结束后清空 previous 三项。
- 附件、临时上传和日志位于独立持久目录，不得放在 release 下；附件和临时上传必须同卷。
- 服务环境变量 `SPRING_CONFIG_ADDITIONAL_LOCATION` 先加载 config，再加载 secrets，使 secrets 覆盖普通配置。

## 静态预检

1. 使用 Windows Server 2022 x64、JDK 21、PostgreSQL 17、IIS URL Rewrite 2 和 Application Request Routing 3（ARR 3）。
2. 只从 `service/winsw-lock.json` 的官方 URL 下载 WinSW x64，并在复制或改名为 `yumpoo-service.exe` 前复核字节数与 SHA-256；仓库和 ZIP 不携带该二进制。
3. IIS 站点只公开受信任证书的 443。后端和数据库只绑定回环地址，不开放防火墙入站端口。
4. 在 IIS 管理器的 URL Rewrite “View Server Variables” 中允许 `HTTP_X_REQUEST_ID` 与 `HTTP_X_FORWARDED_PROTO`，然后应用 `iis/web.config`。`/actuator` 与 `/_m0` 必须在代理前返回 404；`/api` 的 404 不得进入 SPA fallback。
5. 为 Yumpoo 建立专用低权限服务账号，拒绝交互登录；只授予 config、secrets、release 读取权和附件、临时上传、日志目录所需写权。移除继承的宽泛写权，不给管理员或桌面交互能力。
6. XML 不保存账号或密码。只能在受控管理员终端交互安装：`yumpoo-service.exe install /p`。不得运行不带 `/p` 的安装命令。
7. `yumpoo-server.xml` 中 PostgreSQL 服务依赖名为 `postgresql-x64-17`；目标机名称不同时必须先修改模板并复核。

## 顺序与回退

- 安装：预检 → 建持久目录 → ACL → 解压新 release → 交互注册 WinSW → IIS → PostgreSQL → Yumpoo → 健康探针。
- 升级：并列准备新 release → 停 Yumpoo（WinSW 等待 60 秒，Spring 等待 45 秒）→ 切换 current → 启动 → 探针；config、secrets、附件、临时上传和日志不随 release 替换。
- 回滚：停 Yumpoo → 把 current 切回上一 release → 启动 → 探针。不得回滚或删除持久数据。
- 整机重启：PostgreSQL 可用后启动 Yumpoo；liveness 与 readiness 均通过后再恢复 IIS 流量。

目标机验收项定义在 `deployment-checklist.json` 和 `evidence/m0-16`。M0-16 的当前证据必须保持 `NOT_RUN`；M5-14/M6 才采集并签名目标机收据。
## M1-09 APP_MANAGER 首管与紧急恢复

维护入口默认关闭，不得把 `YUMPOO_APP_MANAGER_MAINTENANCE_ENABLED=true` 写入 Windows 服务常驻配置，也不得新增 HTTP 映射。操作应由具备应用配置读取权限的受控服务器终端，以 Windows 服务账号或等价受控身份执行。

1. 确认目标是内部 `identity_user.id`，且用户在职、账号启用；不要使用企微 userid、手机号或邮箱。
2. 准备可追溯但不含个人资料的理由引用，长度 1～160 字符。
3. 首次部署且历史上从未存在 APP_MANAGER 时使用 `BOOTSTRAP`；仅在系统已初始化且当前可用 APP_MANAGER 为零时使用 `BREAK_GLASS`。
4. 执行脚本；脚本以非 Web 模式启动一次性进程、关闭 Outbox 调度，完成后清理临时环境变量并退出。

```powershell
& 'C:\Program Files\Yumpoo\current\deployment\windows\Invoke-AppManagerMaintenance.ps1' `
  -Mode BREAK_GLASS `
  -TargetUserId '00000000-0000-4000-8000-000000000000' `
  -ReasonReference 'INC-2026-0042' `
  -JarPath 'C:\Program Files\Yumpoo\current\yumpoo-server.jar'
```

`BOOTSTRAP` 成功后永久不能再次执行；数据库中的历史 Assignment 是不可逆哨兵。`BREAK_GLASS` 在已有可用管理员时会拒绝。不要绕过 Runner 直接修改 `platform_role_assignment` 或 `app_manager_governance_state`。操作后应确认进程退出码为 0，并由应用内角色查询核对结果；M1-10 上线后再同时核对 Security Audit。
