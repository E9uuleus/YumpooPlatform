# Agent Note: 非工作面 Canvas 氛围动效

Status: implemented

## Problem

前端动效规范只允许 120–240ms 的微交互，并禁止持续脉冲等与效率无关的动画。登录页、404/403、会话状态页和大型空态长期停留在静态的 `el-result`、`el-empty` 上，品牌识别弱；用户要求在不改变业务与功能的前提下引入 JS + Canvas 动效。持续动画一旦进入表格、表单或工作区，会与高频操作争夺注意力并持续占用 CPU；Canvas 也无法直接消费 CSS Token 与 `prefers-reduced-motion` 规则，因此需要明确适用边界与运行约束。

## Decision

Canvas 只用于非工作面：登录品牌区的协作星座、404/403/会话状态页的四种 `StatusScene`，以及页面级空态 `YpEmptyState ambient` 的轨道光晕（仪表板欢迎态、空组件态与收件箱空态）。紧凑空态、表格、表单、看板、详情抽屉和运维中心不使用 Canvas。

`src/motion/canvasStage.ts` 统一驱动所有场景：按设备像素比（上限 2）缩放；rAF 循环有帧率上限（登录 60fps，其余 30fps），单步最长 50ms；IntersectionObserver 判定离屏、`visibilitychange` 判定页面隐藏时停止循环；`prefers-reduced-motion: reduce` 时预热后只绘制一帧静态画面，并随系统设置实时切换；`getContext('2d')` 为空时整体空转。场景颜色经探针元素解析 `--yp-*` Token，`watchTheme` 在 `<html>` 的 `class` 或 `data-theme` 变化时重读颜色并重绘。随机源使用固定种子，静态帧与每次访问的布局一致。所有画布 `aria-hidden`，只有登录品牌区转发指针位置。

该决策是对“禁止持续脉冲”的有限例外：例外只覆盖上述非工作面，工作区继续遵循微交互时长规则，不引入彩纸或大幅缩放。登录页只读引用品牌 Logo，品牌资产仍遵循[品牌资产与界面主题分离](2026-09-14-brand-identity-and-loading.md)。

## Alternatives considered

- 引入 Lottie 等动画库：违反前端不新增 UI 依赖的边界，动画资源也难以随三主题 Token 变色。
- 纯 CSS 或 SVG：可以完成轨道和淡入，但节点连线、粒子碰撞与指针牵引需要逐帧计算，DOM 节点数量与样式重算成本过高。
- WebGL：对二维装饰过重，上下文初始化与丢失处理增加维护成本，老旧显卡上的 Electron 环境风险更高。
- 在仪表板页头、运维 KPI 等工作区加入持续动效：视觉更强，但长期与高频操作争夺注意力并占用 CPU，评审时已被否决。

## Consequences

新增场景必须通过 `createCanvasStage` 与 `useCanvasScene` 挂载，只读语义 Token，不在页面源码中写入颜色常量。`motion/canvasStage.spec.ts` 与 `motion/scenes/scenes.spec.ts` 覆盖空转、静态帧、暂停、颜色合法性、节点上限与布局确定性；happy-dom 不提供 2D 上下文，页面级测试只验证语义与文案，实际视觉依赖 `visual-acceptance.html` 与真实浏览器人工验收。状态场景在 260×220、光晕在 132×132 画板上绘制后等比缩放，调整所在布局时需要同步检查窄屏尺寸。
