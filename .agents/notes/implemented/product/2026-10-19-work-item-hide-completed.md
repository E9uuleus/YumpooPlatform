# Agent Note: 工作项隐没已完成

Status: implemented

## Problem

项目可以自定义状态名，而状态分类只有 TODO、IN_PROGRESS、DONE、CANCELED，成员新建的状态一律归为 TODO，所以“已完成”不能只靠分类判断。已完成的工作项一直留在工作项表格、我的一天侧栏和计时选择列表里，干扰挑选当前要做的事；但如果直接从表格隐藏，分页、排序和计数都会失真，刚完成的项也会找不到。

## Decision

“隐没已完成”是每人一个开关，保存在计时偏好 `work_item_timer_state.hide_completed`（V80，默认关），经 `GET /me/time-tracker` 的 `preferences.hideCompleted` 读取、`PUT /me/time-tracker/preferences` 写入，请求省略该字段时保留原值。Web 只通过 [`useHideCompleted`](../../../../frontend/web-app/src/composables/useHideCompleted.ts) 读写，复用计时偏好的 If-Match 保存与计时广播，工作项表格、我的一天、补录选择器、计时器面板和桌面窗口因此同步。

什么算已完成由每人按项目定义，保存在[工作项表格个人设置](2026-10-07-work-item-table-settings.md)的 `completedStatusCodes`；为 null 时 DONE 与 CANCELED 两类状态视为完成。表格工具栏开关旁的下拉列出本项目全部状态供勾选，只对本人生效，跨设备同步。计时候选接口按同一定义返回 `completed`，服务端与表格的判断一致。

开关开启后，工作项表格的主表与子项表只做原位淡化：单元格内容透明度 0.5、标题加删除线，约 220ms 过渡并尊重减少动态效果；单元格背景保持不透明，不改排序、分页、计数和分组。计时与补录的选择列表在没有搜索词时去掉已完成项，并显示“已隐藏 N 项”；有搜索词时始终包含已完成项，淡化显示并标注“已完成”。时间线上已经记录的块不受影响。

## Alternatives considered

- 按状态分类判断完成：自定义状态都归为 TODO，无法表达“已上线”这类团队自己的完成状态。
- 项目共享的完成定义：需要负责人治理和覆盖规则；当前定位为个人的筛选习惯，每人按项目设置。
- 表格中把已完成项沉底或直接隐藏：需要改服务端排序，或让分页与计数失真；用户选择原位淡化。
- 每个页面各自保存开关：同一件事在表格、侧栏和计时器里状态不一致；改为一个个人偏好处处同步。
- 搜索时也隐藏：会让人以为工作项不存在，因此搜索结果始终包含已完成项。

## Consequences

开关与完成定义分别存于计时状态与表格设置的新列，回退后的旧版本忽略这两列。完成定义引用的状态停用或删除后静默失效。选择列表在已加载的候选上过滤，“已隐藏 N 项”只统计当前已加载的页。`TimeTrackingHttpIT` 验证偏好省略时保留，以及候选 `completed` 按本人定义计算；`WorkItemHttpIT` 验证分组与完成定义不写入 `settings` jsonb；`useHideCompleted.spec.ts` 验证无搜索词时隐藏、搜索时保留与保存。
