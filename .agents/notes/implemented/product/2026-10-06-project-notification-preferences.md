# Agent Note: 按项目的个人通知偏好

Status: implemented

## Problem

站内收件箱按事件为项目成员生成评论、提及、指派与连接通知，成员无法按项目降低噪音，只能在桌面端整体关闭系统提醒。若只在浏览器本地隐藏，未读数、桌面徽标与收件箱列表会不一致且换设备失效；偏好又必须不能屏蔽成员资格与负责人变化这类权限通知。

## Decision

偏好由 notification 模块自有的 `project_notification_preference`（V66）按 Company、Project、User 保存，主键三元组，只对 company 建外键，与 V57 通知表一样不反向约束 project 或用户数据清理。模式为 ALL、MUTED、CUSTOM；CUSTOM 下按 mention、comment（含 REPLY）、assigned、connectionCreated 四个类别开关，切换模式时保留类别选择。`PROJECT_MEMBER_ADDED/REMOVED`、`PROJECT_OWNER_ASSIGNED/TRANSFERRED` 始终送达，与需求 NTF-REQ-008 一致。

过滤发生在 `notification-inbox-v1` 投影写入之前：`workitem.*` 事件每次按项目读取一次偏好，在合并接收人原因时逐个判断，被屏蔽的高优先级原因不会覆盖已允许的低优先级原因（关闭 @提及但保留评论时，被 @ 的处理人仍收到 COMMENT）；连接通知对目标项目负责人单独判断。偏好只影响此后投影的通知，既有通知、已读状态与部署水位不变，不做补发或追溯隐藏。

HTTP 提供 `GET/PUT /me/projects/{projectId}/notification-preference`，仅当前账号且为项目 ACTIVE 成员可用，非成员返回 404；未设置时 GET 返回 ALL 默认值与 null 的 updatedAt。PUT 整体替换、天然幂等，沿用收件箱本人状态的约定只要求会话与 XSRF，不要求幂等键或资源版本。Web 在项目页头标题菜单提供"通知提醒"入口与设置弹窗；企业管理员只读访问项目时不显示入口。

不提供项目级"为所有人静音"开关。

## Alternatives considered

- 仅前端本地过滤：无需后端，但未读数、桌面徽标与列表不一致，且偏好不随账号。
- 读取时按偏好隐藏：可追溯生效，但每次列表与计数查询都要联表偏好，并让"已生成但不可见"的通知进入计数语义。
- 项目级"为所有人静音"：需要负责人治理权限、审计与覆盖个人偏好的优先级规则，当前没有明确场景，经讨论不实现。
- 允许静音成员与负责人变化：与权限类通知不可关闭的需求冲突。

## Consequences

`NotificationInboxProjectionTest` 验证偏好在写入前过滤、低优先级原因保留与连接通知按负责人偏好跳过；`NotificationHttpIT` 验证默认值、整体替换、CSRF 与非成员 404。偏好表随项目或成员移除不会自动删除，重新加入时沿用原偏好；被移出期间偏好不生效，因为投影先按当前成员资格筛选。后续若引入 Delivery 通道偏好（Electron、到期提醒）应扩展本表或另建通道维度，不得复制第二份项目偏好。
