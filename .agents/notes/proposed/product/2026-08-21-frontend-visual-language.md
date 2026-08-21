# Agent Note: 前端视觉语言与三主题设计系统

Status: proposed

## Problem

当前 Vue 前端使用固定浅色、中等深度海军蓝顶栏、少量全局 CSS 和 Element Plus 默认视觉。状态在部分页面仍是裸枚举，人员、日期、优先级和进度没有稳定的跨页面表达，原生控件与 Element Plus 也存在混用。随着项目、工时、审批、反馈和管理后台页面增加，如果继续由页面自行决定颜色、圆角和交互反馈，会产生无法统一维护的视觉语义，并使 Dark/Night 支持演变为逐页修补。

用户需要更活泼、直观、高效率的协作体验，同时业务状态、权限、审批和审计仍必须保持可靠。主题和组件语言属于会跨越多个模块、未来很可能被重新讨论的长期产品取舍，需要在实际实现前明确目标和替代方案。

## Proposal

采用 Yumpoo 语义 Token 与复合业务组件作为前端视觉语言：

- 大面积使用中性工作表面；全局行动色由 Yumpoo 语义 Token 管理，项目工作区使用明确的行动蓝，状态使用稳定的语义色板。
- 支持 `system | light | dark | night`；系统模式只解析为 Light/Dark，Night 是用户主动选择的低眩光深蓝主题。
- 支持 `comfortable | compact` 两档密度，默认 Comfortable。
- Light、Dark、Night 保持相同状态色相；状态必须同时显示文字，不能以颜色作为唯一信息通道。
- Vue 3 和 Element Plus 继续作为实现基线，通过 `--yp-*` 到 `--el-*` 的变量映射统一基础控件。
- Button、Input、Dropdown 等基础控件直接复用 Element Plus；状态、负责人、日期、进度、筛选栏和页头等重复业务模式由 `Yp*` 复合组件拥有。
- monday.com Vibe Design System 只作为操作层级、组件组合和反馈方式的参考，不引入 React 的 `@vibe/core`，也不复制其品牌资产和业务模型。
- 项目模块采用约 260px 二级侧栏、项目专用页头和开放式工作画布；Monday Workspace 仅提供布局层级、留白和视觉节奏参考，不迁移其品牌色、图标、文案或功能入口。
- 项目目录在桌面按 Workspace 形成可独立折叠的分组看板，通过稳定分组色轨、状态汇总和整格状态色强化扫描效率；不同主题保持相同信息层级。
- 项目目录在触屏宽度改用分组内连续行列表，将项目名、状态、负责人和类型保持在同一屏；桌面与触屏视图都只消费摘要接口已有字段。
- 主题和密度一期只在设备本地持久化，不新增后端偏好字段或同步 API。

设计规范先作为目标基线存在。运行时 Token、主题切换、复合组件、页面迁移和视觉验收未全部交付前，本 Note 保持 proposed。

## Alternatives considered

1. **保留固定浅色与现有海军蓝样式**：实现成本最低，但无法满足三主题要求，也会继续放大页面级硬编码和状态表达不一致。
2. **直接引入 Vibe Design System**：可以快速获得成熟组件语言，但 `@vibe/core` 面向 React，与当前 Vue 3 技术栈不兼容，也会把外部品牌和 API 约束带入产品。
3. **只支持 Light/Dark**：实现简单，但不能提供与普通炭灰深色明显区分的低眩光 Night 体验，不满足已确认主题目标。
4. **允许各业务模块独立主题化**：短期灵活，但相同状态和控件会在项目、工时、审批与后台产生不同含义，维护和验收成本不可控。

## Acceptance criteria

- `docs/03-frontend` 明确页面模式、三主题 Token、两档密度、组件规则、Element Plus 映射和当前实现边界。
- 运行时代码在 Vue 挂载前解析主题，使用 `data-theme`、`data-density` 和 Dark/Night 共用的 `dark` 类，首屏无错误主题闪烁。
- 核心页面不再使用冲突硬编码色、裸业务枚举或重复的通用 Element Plus 深层覆盖。
- `YpStatusTag`、`YpAssignee`、`YpPriorityBadge`、`YpDateBadge`、`YpProgress`、`YpFilterBar` 和统一页头模式已覆盖代表页面。
- 项目目录、概览、成员和设置共享同一工作区层级；目录只展示摘要接口已有字段，详情页继续展示创建与更新时间，且不为视觉完整度虚构搜索、菜单或额外接口请求。
- 项目目录在桌面使用 Workspace 分组表格并支持独立折叠，在小于等于 720px 的触屏布局使用分组内连续行列表；关键状态不得因横向滚动落在首屏之外。
- Light、Dark、Night 与 Comfortable、Compact 的六种组合完成壳层、表格、看板、表单、浮层和反馈状态验证。
- 普通文字对比度、键盘焦点、状态非颜色单通道和 `prefers-reduced-motion` 验收通过。
- 实际交付结果反映到设计规范后，本 Note 才迁移为 implemented；若停止采用则迁移为 rejected。

## Risks

- Element Plus 默认深色变量与 Yumpoo Token 的覆盖顺序处理不当，可能在浮层或少数组件中泄漏默认蓝灰色。
- 高饱和状态色在大面积使用时会造成视觉疲劳，因此实心色必须限制在标签和关键状态，Banner 与图表使用派生表面。
- Dark 与 Night 如果只改变画布而未验证图表、阴影、遮罩、头像 fallback 和状态前景，会形成表面支持而非完整主题。
- 过度包装 Element Plus 会增加 API 维护负担，因此只有稳定业务语义和重复组合建立 `Yp*` 组件。
- 设计文档先于运行时实现，维护者可能误认为能力已交付；准确性文档和 Note lifecycle 必须持续标明 proposed 状态。
