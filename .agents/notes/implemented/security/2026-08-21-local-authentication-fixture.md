# Agent Note: 本地免企微认证身份夹具

Status: implemented

## Problem

本地前后端联调无法依赖真实企业微信 OAuth、扫码和通讯录同步，但 Project 工作台等纵向切片必须验证真实身份、角色、Session 与 CSRF 行为。仅让 `/auth/me` 返回硬编码用户会绕过权限事实，并使写请求仍因缺少会话绑定的 CSRF 凭据而失败；长期保留无环境边界的认证旁路也会扩大生产攻击面。

## Decision

`YUMPOO_LOCAL_AUTH_ENABLED=true` 与 `local` profile 共同启用本地身份夹具，默认保持关闭。启动时夹具经现有目录成员服务创建或刷新配置的本地管理员与内部备份管理员，经平台角色维护和命令端口授予登录账号 `COMPANY_ADMIN + APP_MANAGER`，不建立第二套用户或权限事实。

无 Session 或持有本地重启后失效 Session 的请求会为配置的本地管理员签发正常 Web Session 与绑定 CSRF Cookie，再进入既有 Spring Security、`CurrentActor` 和业务鉴权链。该模式只允许回环监听，混入 `prod`、真实企微 OAuth、企微通讯录或受控身份提供者时拒绝启动；遇到无法由夹具治理的既有角色状态也失败关闭。

## Alternatives considered

- 前端注入固定用户：不能让后端接口通过认证，也不能产生 Session 绑定的 CSRF。
- `/auth/me` 返回固定 DTO 并关闭 CSRF：形成与正式权限事实分离的认证旁路，无法验证写路径。
- 继续使用 M1-13 受控 OAuth 夹具：仍要求进入登录页并执行授权回调，且其 pristine 数据库与专项 profile 约束不适合日常增量开发。
- 提供用户名密码登录：引入当前产品不需要维护的第二种正式凭据与认证协议。

## Consequences

- 日常本地启动只需显式设置 `SPRING_PROFILES_ACTIVE=local` 与 `YUMPOO_LOCAL_AUTH_ENABLED=true`，首次 `/auth/me` 即建立可用于读写验证的正式会话。
- 登录账号默认拥有完整本地治理能力；内部备份管理员仅用于遵守 APP_MANAGER 首管与授权治理规则。
- 本地数据库保留真实 User、ExternalIdentity、角色、审计、事件与 Session 记录，重启可幂等复用；与夹具不兼容的既有角色历史需要开发者选择干净数据库或恢复配置角色。
- 该能力不能作为真实企微 OAuth、通讯录同步、扫码、HTTPS 或发布环境证据。
