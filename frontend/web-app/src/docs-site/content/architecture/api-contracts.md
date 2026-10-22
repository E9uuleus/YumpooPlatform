---
title: API 契约与客户端生成
description: OpenAPI 分域源与生成物、HTTP 约定，以及前端 API 客户端的生成与兼容性策略。
order: 40
audience: [developer]
---

读完你会知道：接口契约写在哪里、怎么生成单文件与 TypeScript 客户端、所有接口共同遵守的 HTTP 约定，以及怎样做破坏性变更。

## 契约源与生成物

- **编辑入口**是分域源目录 [contracts/openapi/yumpoo-v1/](../../../../../../contracts/openapi/yumpoo-v1/bundle.yaml)：`bundle.yaml` 是清单，`paths/` 和 `components/` 按业务域拆分。
- `pnpm generate:openapi` 按清单顺序合并成单文件 [contracts/openapi/yumpoo-v1.yaml](../../../../../../contracts/openapi/yumpoo-v1.yaml)（服务前缀 `/api/v1`）。这个文件提交到仓库，**禁止手改**；`pnpm check:openapi-bundle` 校验两者同步。
- 后端契约测试、API 客户端生成和兼容性检查都读取单文件。

决策背景见 [OpenAPI 分域源](../../../../../../.agents/notes/implemented/process/2026-10-21-openapi-split-sources.md)。

## HTTP 约定

| 约定 | 说明 |
|---|---|
| 乐观并发 | 资源返回 `ETag`；修改时带 `If-Match`，版本不符返回 412，缺少返回 428 |
| 幂等 | 创建与命令类请求带 `Idempotency-Key`，重试不会重复执行 |
| 请求关联 | 每个请求带或生成 `X-Request-Id`，贯穿日志与事件 |
| 客户端版本 | 桌面客户端带版本头；版本过低返回 426，界面引导升级 |
| CSRF | 写请求带 XSRF 令牌，令牌与会话绑定 |
| 错误模型 | 统一的问题详情结构，含稳定的错误码与 `details`；每种错误有金样例 |
| 隐藏 404 | 无权访问的资源返回 404 而不是 403，不泄露资源是否存在 |

## 客户端生成

[tools/openapi/api-client-codegen.mjs](../../../../../../tools/openapi/api-client-codegen.mjs) 用 OpenAPI Generator 生成 `packages/api-client/src/generated/`：

```bash
pnpm generate:openapi
pnpm generate:api-client
```

生成物提交到仓库，CI 用同一工具的检查模式确认没有漂移。

## 兼容性

- CI 对比基线契约检查破坏性变更（删除字段、收紧约束等），违反即失败。
- 确需破坏性变更时，在 [breaking-change-exceptions.json](../../../../../../tools/openapi/breaking-change-exceptions.json) 登记精确例外，并在 Agent Note 记录理由。
- 新增字段默认可选，前端对未知枚举值要有兜底展示。
