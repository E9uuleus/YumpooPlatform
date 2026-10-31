# Agent Note: 统一提醒条 YpNotice

Status: implemented

## Problem

页面里的说明、警告和错误提醒直接使用 Element Plus `el-alert`。应用只覆盖了它的文字色，没有覆盖浅色背景变量：说明类显示为灰底灰字，与普通文字难以区分；错误类沿用 Element Plus 默认红，与 yp 色板不一致。另有十余处自定义提醒块（左边框说明、灰底小条、红底错误行），各自定义边距、圆角和颜色。提醒的作用不能一眼看出，新页面也没有可遵循的写法。

## Decision

- 所有提醒条使用 `components/yp/YpNotice.vue`，不再使用 `el-alert`，也不再为提醒单独写背景、边框和图标样式。源码约束 `design-system/source-constraints.spec.ts` 拒绝任何 `.vue` 中出现 `el-alert` / `ElAlert`。
- 色调只有四种，按作用选择，不按喜好：
  - `info`（蓝）：说明规则、范围或去向，例如「工时不在这里导入」。
  - `success`（绿）：操作已完成或文件已读取等确认。
  - `warning`（橙）：有副作用或需要留意，但可以继续，例如更新模式会清空字段、只读状态。
  - `danger`（红）：失败、无法继续或不可逆，`role="alert"`；其余色调为 `role="status"`。
- 结构：一句话的提醒只写正文；有结论和解释时，结论写 `title`，解释写正文。正文里需要用户找到的路径或数字用 `<strong>`。操作按钮放 `actions` 插槽，窄屏时换到下一行。
- 尺寸：页面、弹窗和卡片用默认尺寸；弹出层、卡片内小区域和计时面板用 `size="small"`。
- 视觉（2026-10-31 起）：四种色调均为实底白字，底色复用轻提示的 `--yp-message-*-bg`，无边框、无渐变、无左边线；内部链接与按钮为白色前景。见[视觉契约](2026-10-31-visual-contract.md)。
- 共享错误展示 `InlineProblem` 用 `danger` 色调的 YpNotice 实现。
- 不属于提醒条的不使用它：运维健康摘要横幅、引用用户输入的备注块、撤销条、带操作的设置卡、表单项下的行内提示和脚注、`ElMessage` 消息、文档站 Markdown 提示块（`.docs-callout`，配色同源），以及桌面壳诊断静态页。

## Alternatives considered

- **全局重写 `.el-alert` 样式**：改动最小，但组件内部结构、图标和插槽由 Element Plus 决定，无法加入操作插槽和小尺寸，自定义提醒块也无法收敛；升级 Element Plus 时样式可能失效。
- **保留 `el-alert`，只补齐 `-light-9` 背景变量**：解决灰底问题，但仍是四套 Element Plus 默认样式，图标和标题层级无法统一。
- **更多色调（如 tip、neutral）**：色调越多，选择越依赖个人判断；四种色调与 Element Plus 原有类型一一对应，迁移是机械替换。

## Consequences

- 新页面写提醒只需选一种色调和尺寸，不需要写样式；评审只需检查色调是否符合作用。
- 「项目已归档」横幅为红色实底，是页面上唯一的红色实底提醒条。
- 自定义提醒块的测试只依赖文字，迁移后不受影响；依赖 `ElAlert` 组件的测试改为查找 `YpNotice`。
- 文档站提示块与 YpNotice 共用状态色，但由 Markdown 渲染，不受源码约束约束。
