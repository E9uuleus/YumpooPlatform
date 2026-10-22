---
title: 前端架构
description: Vue 3 + Element Plus 与 yp 设计系统、模块级组合式状态、API 客户端接入、路由守卫、页面导览，以及本文档站的构建方式。
order: 70
audience: [developer]
---

读完你会知道：Web 应用的技术选型、状态与数据怎样组织、路由怎样守卫，以及页面导览和本文档站是怎么实现的。

## 技术选型

- Vue 3（组合式 API、`<script setup>`）+ vue-router + Element Plus。
- `yp` 设计系统：在 Element Plus 之上的令牌与组件（`--yp-*` CSS 变量），支持浅色、深色、夜间三套主题和舒适、紧凑两种密度。
- 富文本编辑使用 Tiptap；图表使用 ECharts；导出使用 jsPDF 与 ExcelJS。

## 状态

不使用 Pinia。共享状态写成**模块级的组合式函数**（例如 `useSession`、`useTimeTracker`、`useAppearance`）：模块内持有 `ref`，多个组件调用得到同一份状态。跨窗口同步计时状态用 BroadcastChannel，桌面端另有 IPC 通知。

## API 接入

- 页面通过生成的 `@yumpoo/api-client` 调用后端，客户端由 OpenAPI 生成，见 [API 契约与客户端生成](api-contracts.md)。
- 写请求读取 XSRF Cookie 并带上令牌；修改资源时带 `If-Match`，命令类请求带 `Idempotency-Key`。
- 错误统一转换为问题对象，按错误码给出界面提示。

## 路由与守卫

- 进入任何非公开路由前，守卫会确认会话，未登录跳转登录页并记住返回地址；账号停用、需要升级客户端、服务不可用分别进入对应状态页。
- 路由 `meta.requiredRoles` 声明角色要求，不满足时进入无权限页。
- `meta.public` 的路由不需要登录：登录页、`/download` 和本文档站 `/docs`。

## 页面导览

页面导览在 `src/onboarding/`：[tours.ts](../../../../../../frontend/web-app/src/onboarding/tours.ts) 定义每个导览的步骤和版本，步骤用 `data-tour` 属性定位界面元素；路由的 `meta.onboardingTour` 把页面和导览关联起来。用户处理过的导览版本保存在服务端，导览内容更新后会再次出现；顶栏「查看本页引导」随时重放且不写服务端。

## 本文档站

本文档站是 Web 应用里的公开路由 `/docs`，源文件是 `src/docs-site/content/` 下的 Markdown：

- [构建插件](../../../../../../frontend/web-app/build/docs/markdown.ts) 在**构建期**把 Markdown 编译成 HTML，按页拆分、懒加载；运行时不引入 Markdown 解析器。
- 生产环境的 CSP 是 `default-src 'self'`，不允许内联样式和脚本。插件因此只输出 class，遇到 `style=`、`<script`、事件属性或图片时直接让构建失败。
- 文档中引用的仓库文件渲染成路径，同时由 `pnpm doc-sync` 校验目标存在，文件移动时 CI 会失败。
