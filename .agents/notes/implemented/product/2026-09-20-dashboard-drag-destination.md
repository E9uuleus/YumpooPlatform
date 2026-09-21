# Agent Note: 仪表板拖拽目标位置优先

Status: implemented

## Problem

GridStack 默认的覆盖率判断、同尺寸交换和重力收拢，会阻挡不同宽高控件的插入，或在松手后改变目标行。用户要求控件能够到达画布内选定的位置，其它控件自动上下避让。

## Decision

本记录部分替代[自动避让与窗口独立保存](2026-09-19-dashboard-layout-autosave.md)中拖拽采用默认重力布局、配置变化统一收拢空隙的决策。自动保存、跨窗口版本竞争及跨断点共享高度仍由旧记录拥有。

[DashboardGridEngine](../../../../frontend/web-app/src/components/dashboard/dashboardGridEngine.ts) 通过 GridStack 的实例级 engineClass 扩展拖动碰撞：目标控件优先占据经过网格边界约束的落点，其它控件保持横向位置和尺寸。向下拖入其它控件时，已经越过其起始行且上方可容纳则向上避让，否则向下级联避让。每次指针更新均从手势起始布局重新计算，避免往返拖动累积位移。

[DashboardGrid](../../../../frontend/web-app/src/components/dashboard/DashboardGrid.vue) 使用浮动布局保留拖动目标行；[dashboardLayout](../../../../frontend/web-app/src/components/dashboard/dashboardLayout.ts) 默认只解决重叠，保留用户选择的空隙。加载、自动保存、改标题和图表设置不再把有效落点吸回顶部。缩放结束及删除控件仍显式收拢空隙，保留此前的缩小跟进与删除填补行为。六列、十二列均适用；单列仍是只读堆叠展示。

## Alternatives considered

- 只开启 GridStack float：仍保留默认拖拽覆盖率门槛，也无法实现不同尺寸控件的向上避让。
- 只在松手后重排：拖动预览与最终落点不同，用户仍不能从占位框判断实际结果。
- 每次基于上一帧结果继续推挤：拖动经过的控件会累积下移，返回原处也无法复原。
- 继续在每次保存时强制收拢：会再次改变用户选定的空白落点，因此改成按操作显式收拢。

## Consequences

有效布局可以保留纵向空隙，整体高度由用户落点决定；其它控件不横向换列。没有新增接口字段、数据库迁移或依赖。扩展使用 GridStack 的运行时拖动/脏标记；升级网格库时必须保留真实引擎和浏览器拖拽回归验证。

## Verification

定向测试覆盖六列与十二列的不同尺寸碰撞、向上避让、向下级联、连续合法落点、往返复原、保存重载和后续配置编辑保持位置。浏览器使用真实 DashboardGrid 组件的临时回归画布验证上下避让、六列拖动、断点往返与重载，画面坐标和保存布局一致，未修改用户既有仪表板数据。类型检查、生产构建、改动文件 lint、Agent Note 格式及文档链接校验通过。
