# Agent Note: 视觉契约与 Element Plus 外观接管

Status: implemented

## Problem

按钮、提醒、菜单和弹层的外观同时由 Element Plus 默认样式、全局适配和各页面局部 CSS 决定：未带 type 的按钮是描边按钮，项目页又单独加回描边；菜单用分割线、行高各异；弹层有描边和箭头；提醒是浅渐变加左边线；分段切换是灰轨道加白色滑块；骨架只在工作项表格使用。同类控件在不同页面看起来不一样，新页面也没有可遵循的写法。

## Decision

- Element Plus 继续提供行为，外观由 `styles/element-plus.css` 全局接管，不新建 Button / Input / Select / Menu 包装组件。
- 实底填色只用于操作与提醒：主要按钮蓝底白字、危险按钮红底白字、默认按钮浅灰实底深色字，全部无描边；工具栏里的非主操作用 `text`。实底上的白字用 `--yp-on-solid`，不用随主题变化的 `--yp-text-inverse`。
- 菜单以工作项表格设置菜单为基准：36px 行、左右 10px、图标距 10px、浅灰悬停；`divided` 渲染为组间留白，组名用 `.yp-menu-label`，危险项 `yp-menu-item--danger`，当前项 `yp-menu-item--current`。选择列表同一行规格。弹层无描边、无箭头。
- Element Plus 2.14.7 的菜单分组分隔符是独立的 `.el-dropdown-menu__item--divided` 节点，不能要求它同时带菜单项类；时间面板用 `--el-timepicker-inner-border-color` 隐去内部边线，不与日期面板变量混用。
- 输入类保留 1px 单描边（[单描边聚焦](2026-10-04-single-stroke-input-focus.md)）。
- 仪表板数量与讨论表格行列数使用 `ElInputNumber`，沿用原最小、最大值并接受组件在失焦时的标准范围修正；清空仍由既有有效性判断阻止保存或插入，不新增回退值。
- 切换控件统一外观、保留语义：YpSegmented / OpsSegmented / 单选按钮组选中为蓝底白字、无轨道；页签为文字加 2px 指示条、无整条底线。
- [YpNotice](2026-10-29-notice-bar.md) 四种色调实底白字；YpStatusTag 只保留 solid / cell / dot。
- 页面表面不用装饰描边、卡片阴影、毛玻璃与装饰渐变，分区靠标题与留白；承载信息的线（表格网格、日历格、时间轴、拖放占位、选中与聚焦轮廓、输入边界）保留。
- 加载（与[工作项表格骨架](2026-10-30-work-item-table-skeleton-loading.md)共用令牌）：首次读取且无数据用 `YpSkeleton`，已有数据刷新用品牌遮罩，按钮动作用按钮忙碌状态，空 / 错 / 无权限各自表达。

## Alternatives considered

- **为每类控件建 Yp 包装组件并迁移全部调用**：可控性最高，但 278 个按钮、上百个选择与弹层要逐个替换，回归面过大；全局样式已能覆盖外观差异。
- **撤换 Element Plus**：要重写定位、键盘与可访问性行为，收益只在外观。
- **只改主题色变量**：变量只决定颜色，不决定描边、分割线、箭头与行高。

## Consequences

- 新页面直接使用 Element Plus 组件即得到统一外观；评审只需检查按钮 type / text、菜单是否用分组而非手写分割线、首次加载是否用骨架。
- 升级 Element Plus 时需在浏览器中复核全局覆盖的选择器（按钮变量、下拉菜单、选择列表、日期面板、单选按钮组、分页）。
- 单元测试不加载普通 CSS，颜色、描边与扫光只能在真实浏览器中验收。
- 独立监控站与桌面壳启动 / 诊断静态页不在本契约范围内。

[仪表板高度决策](2026-09-18-dashboard-stable-widget-height.md)继续拥有布局高度与标题尺寸，标题栏装饰底线由本契约去除；[自定义图表决策](2026-09-17-dashboard-custom-charts.md)继续拥有配置、统计与预览，通用控件与弹层外观由本契约接管。

[团队视图决策](2026-10-08-dashboard-tabs-export-team-view.md)继续拥有统计口径、周期与导出；其表格悬停外观由本视觉契约统一为中性配色。
