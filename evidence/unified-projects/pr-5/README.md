# PR-5 筛选与看板视觉验收

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

## 范围边界

本证据仅覆盖 UP5a/UP5b，不声称 UP5c 已完成。通知原因枚举的设计缺口在 PR 描述中单独列出；尚未增加通知字段、迁移或事件。
