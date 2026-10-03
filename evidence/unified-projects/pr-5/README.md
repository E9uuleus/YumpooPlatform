# PR-5 筛选、看板与通知验收

本目录是新增证据，不改写 PR-4 证据。使用用户电脑上的可见 Chromium 打开只读设计包的 `prototype.html`，逐一核对来源项目表格、目标项目表格、设置页，以及双方成员、非目标项目成员、企业管理员只读三种身份。原型文件未修改、未复制入库。

实现截图来自本地 `visual-acceptance.html#connect-columns`，复用正式 `ConnectFilterSection`、`ConnectKanbanConnections`、`ConnectionChip`、`ConnectionList` 和 `ConnectionCardDialog`，使用静态服务响应。它们用于视觉与交互验收，不代表真实后端或部署验收。真实 SQL、HTTP 权限、计数、游标由 `ConnectionFilterIT`、`ConnectionHttpIT` 验证，正式工作项容器请求和 A6 批量加载由 `ProjectOverviewView.spec.ts` 验证。

## 截图

| 场景 | Light | Dark | Night |
| --- | --- | --- | --- |
| 连接筛选：同列已连接/未连接互斥、入站来源选中 | [截图](filters-light.jpg) | [截图](filters-dark.jpg) | [截图](filters-night.jpg) |
| 看板：标题下最多 3 个 chip 与 +2；无连接行为空 | [截图](kanban-light.jpg) | [截图](kanban-dark.jpg) | [截图](kanban-night.jpg) |
| +N 只读列表 | [截图](kanban-expanded-light.jpg) | [截图](kanban-expanded-dark.jpg) | [截图](kanban-expanded-night.jpg) |
| 重新读取后的只读连接卡片 | [截图](kanban-card-light.jpg) | [截图](kanban-card-dark.jpg) | [截图](kanban-card-night.jpg) |

身份补充：[非目标项目成员](kanban-card-nonmember-light.jpg)、[企业管理员只读](kanban-card-admin-light.jpg)。非成员不显示目标项目导航；看板展开列表和卡片不提供解除、新建或关联已有入口。

原型对照沿用 [PR-4 的逐屏原型证据](../pr-4/README.md)，本次另在用户电脑重新打开绝对路径原型并核对全部 9 个页面/身份组合。原型没有 PR-5 筛选与看板页面；新增部分按 `04-delivery-and-review.md §7` 实现，芯片与卡片的结构、颜色、身份规则对照原型既有连接组件。

## 浏览器检查

[机器记录](browser-checks.json)：14 张截图、8 组交互/主题/身份检查、0 个页面异常。检查包含已连接/未连接互斥、5 条连接仅 3 个 chip + `+2`、只读展开列表、只读卡片、非成员导航限制。打开卡片的实时 GET、已失效连接关闭、取消请求和旧响应丢弃由既有卡片组件测试继续覆盖。

## 目标负责人通知（UP5c）

用户批准 `CONNECTION_CREATED` 原因、既有 PROJECT 分组与 WORK_ITEM 目标、V63 追加迁移后，补充正式 `InboxRow` 和 `inboxPresentation` 的浏览器验收。截图使用静态 `NotificationItem` 数据，不代表真实后端或原生系统通知已部署。验收夹具在本任务本机临时目录运行，不修改设计包或产品页面。

| 场景 | Light | Dark | Night |
| --- | --- | --- | --- |
| 目标负责人文案、PROJECT 图标；失权后隐藏操作者和全部目标信息 | [截图](notification-light.jpg) | [截图](notification-dark.jpg) | [截图](notification-night.jpg) |

[390px 窄屏](notification-mobile-night.jpg)无水平溢出。[通知浏览器记录](notification-browser-checks.json)包含 4 组检查、0 个页面异常：三主题的目标工作项链接、失权行无链接且不暴露操作者/目标信息，以及窄屏检查。全目录合计 18 张截图、12 组浏览器检查。

真实行为由 `NotificationInboxProjectionTest`、`ConnectionNotificationIT`、`ConnectionNotificationMigrationIT` 和 `NotificationHttpIT` 验证：仅新建并关联投递目标负责人；持久化不含来源或列信息；部署前事件不补发；升级保留原水位和通知；重放去重；PROJECT 分组计数/读取/全部已读；撤销成员资格或删除目标后重新鉴权。`InboxHost.spec.ts`、桌面 preload/notifier 测试另覆盖新壳能力声明、旧壳混合包过滤、最小原生文案及拒绝扩展业务字段。
