---
title: 交付与发布
description: CI 阶段与统一入口、本地冒烟、分支模型与发布说明、草稿 Release 与 Publish、供应链与部署流程。
order: 90
audience: [developer]
---

读完你会知道：一次改动从分支到上线经过哪些检查和决定。本页只写流程，不涉及主机、目录或账号。

::: diagram delivery-pipeline
:::

## CI

CI 由统一入口 [tools/ci/run.mjs](../../../../../../tools/ci/run.mjs) 驱动，分五个阶段：

| 阶段 | 内容 |
|---|---|
| contracts | OpenAPI 合并与基线兼容性、lint 与金样例、生成客户端漂移、事件 Schema 与兼容性、迁移回退安全 |
| static | lint、工作区边界、preload 契约、Web 与桌面的类型检查、构建和测试、部署配置、文档与 Agent Note、CI 与发布策略测试 |
| backend | 后端回归（单元、集成与架构测试）、受限堆运行 |
| portable | 备份恢复输出、打包后的 HTTP 冒烟、可移植交付包的生成与校验 |
| windows | Windows 工具链、发布恢复、桌面冒烟、打包、安装器与更新签名校验 |

交付步骤按能力命名，本地可以用 `pnpm run ci` 或单独跑某个阶段。历史上的里程碑门禁已经退役。

## 本地浏览器冒烟

`pnpm test:e2e` 用系统 Edge 或 Chrome 跑一组串行冒烟用例，在与线上相同的 CSP 下检查主要页面没有控制台错误、页面错误或接口 5xx。它只在本地运行，不属于合并门禁。

部署配置检查（[verify-deployment-config.mjs](../../../../../../tools/verification/verify-deployment-config.mjs)）会读取前端路由，拒绝被反向代理的非 SPA 路径遮蔽的路由。

## 分支与 PR

- `dev` 是集成分支，`main` 是已发布线；功能与修复 PR 指向 `dev` 并 squash 合并，`dev` 晋升到 `main` 用合并提交。
- PR 正文必须有「## 发布说明」段，每行 `- 类别｜范围：描述`，面向使用者书写。Release 描述和桌面下载页都由它生成，`Release Notes Policy` 检查格式。

## 发布

- `main` 推送 CI 成功后，Prepare Release 自动生成**草稿 Release** 与版本标签。
- 在草稿上点击 **Publish** 就是上线决定；之后自动部署。
- 线上紧急修复从 `main` 建 `hotfix/*`，发布后自动生成 `backmerge/*` PR 合回 `dev`。

## 供应链

发布包附带 SBOM 与构建证明，可以验证产物由对应提交在 CI 中构建。

## 部署流程

自动部署按固定顺序执行：进入维护 → 备份 → 切换到新版本 → 验证健康与版本 → 开放访问；任何一步失败都会回滚到上一个版本。迁移只扩展的原则保证了回滚可行。
