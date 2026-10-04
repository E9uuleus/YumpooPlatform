# Agent Note: 输入框单层焦点描边

Status: implemented

## Problem

Element Plus 在输入框聚焦时绘制 1px 内描边，全局样式又给 `.el-input__wrapper:has(input:focus-visible)` 与 `.el-select__wrapper` 加了 2px、偏移 2px 的外轮廓。Chromium 与 Electron 对鼠标点击的文本输入同样匹配 `:focus-visible`，于是每次点击都同时出现内外两层高亮；深色主题下两层还分别取 `--yp-action-primary` 与 `--yp-focus-ring`，颜色不一致。各组件为消除冲突各自写了 `outline: none !important`、外扩光晕或嵌套 outline，共有十余种焦点画法。

## Decision

文本类控件只用自身的一条 1px 描边表达状态，静止、悬停、聚焦只改变颜色，不再叠加外轮廓或光晕：

- `styles/tokens.css` 定义 `--yp-input-bg`、`--yp-input-border`、`--yp-input-border-hover`、`--yp-input-border-focus`（取主题的 `--yp-focus-ring`）与 `--yp-input-radius`（6px）。派生值写在 `:root`，随三套主题的基础 Token 解析。
- `styles/element-plus.css` 只为 `.el-button:focus-visible` 保留 2px 焦点环；在 `.el-input`、`.el-textarea`、`.el-date-editor`、`.el-select` 上重设 Element Plus 输入变量，并统一 `__wrapper`、`.el-textarea__inner` 与范围日期编辑器的描边、圆角和过渡。聚焦时前缀图标加深为次要文字色。
- 原生输入与组合输入（侧栏搜索、图表编辑器、计时器选择器与时间段、讨论浮层、截止日期编辑器）改用同一组 Token；由容器拥有边框的名称单元格、快速添加行和标签编辑框保留各自容器描边，颜色改为 `--yp-input-border-focus`，并移除不再需要的 `!important`。
- 按钮、单选、复选与表格单元格选择框不属于文本输入，继续使用既有焦点环或选中边框。

`visual-acceptance/VisualLanguagePreview.vue` 的「输入框状态」区展示搜索、选择、日期、校验失败与禁用状态。

## Alternatives considered

- 保留外轮廓、去掉内描边：外圈会被弹层边缘与表格单元格裁切，紧凑密度下也会与相邻控件重叠。
- 只用 `:focus-visible` 区分键盘与鼠标：Chromium 对文本输入恒定匹配，无法据此关闭鼠标点击时的外圈。
- 封装新的 `YpInput` 组件：前端视觉语言提案要求直接复用 ElInput，避免过度包裹；全局变量即可覆盖全部 Element Plus 输入。
- 聚焦时加 2–3px 半透明光晕：本质仍是第二层描边，与用户要求的单层样式冲突。

## Consequences

本决策覆盖 `docs/03-frontend/05-control-design.md` 中“输入框焦点同样使用 2px 外环”的写法；按钮焦点环不变。新增输入样式应引用 `--yp-input-*` Token，不得为文本输入重新加入 `outline` 或外扩 `box-shadow`。`tools/verification/verify-m2-21a-assets.mjs` 仍校验快速添加行由外层边框显示焦点，内部 wrapper 保持 `outline: none`。

焦点外观依赖浏览器真实渲染，Vitest 只加载 CSS 原文，不断言计算样式；三主题与两种密度需在视觉验收页和真实页面人工确认。
