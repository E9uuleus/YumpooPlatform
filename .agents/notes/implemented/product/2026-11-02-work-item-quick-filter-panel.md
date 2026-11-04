# Agent Note: 工作项快速筛选弹窗

Status: implemented

## Problem

工作项表格的「筛选」弹窗同时放着名称搜索、字段多选、连接筛选、计时状态和累计时长，名称搜索与工具栏 Search 重复，连接与计时条件很少使用却占据大半面积；字段选项是带勾选框的透明按钮，选中状态不醒目，也不能按处理人筛选。

## Decision

弹窗只保留四列字段与两项日期：处理人、状态、优先级、工作项类别；截止日期范围与最后更新时间。字段选项是灰底按钮（`--yp-bg-sunken`），选中为浅蓝（`--yp-bg-selected-strong`），不显示勾选框，右侧显示数量。处理人选项列出项目有效成员，与工具栏独立的「处理人」按钮修改同一条件，「筛选」按钮的计数包含处理人。

名称搜索只在工具栏 Search。连接筛选（`connectedColumnIds`、`unconnectedColumnIds`、`incomingProjectIds`）与计时筛选（`timeTrackingState`、`timeTrackingMinMs`、`timeTrackingMaxMs`）从 Web 移除：不再读写路由参数，也不随列表、看板与筛选选项请求发送；`ConnectFilterSection` 组件删除。接口参数与服务端语义保留，供其他调用方使用。

## Alternatives considered

- 只调整样式、保留全部条件：弹窗仍然过长，重复的名称搜索继续让人困惑。
- 删除接口中的连接与计时参数：属于破坏性契约变更，而服务端实现没有维护负担，没有必要。
- 用处理人分区替换工具栏「处理人」按钮：用户选择保留，工具栏入口可搜索成员，列头菜单的「筛选」仍打开它。

## Consequences

旧链接中的连接或计时参数不再生效，留在地址栏中被忽略。[连接筛选](2026-10-03-connection-enhancements.md)的服务端语义不变，Web 不再提供入口。
