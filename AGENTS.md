- 中文沟通：本仓库默认使用中文沟通与说明。
- 编码约定：当前代码文件统一使用 UTF-8 编码，注意中文显示一致性，新增文件保持一致。
- 目录约定：`docs/` 文档和版本化原型导出物。

## Git 分支规范

* 子功能开发必须基于最新的 `dev` 分支创建独立分支，例如 `feature/<功能名>`。
* 子功能完成后必须通过 PR 合并回 `dev`，PR 的目标分支统一为 `dev`。
* 禁止直接向 `dev` 分支推送代码；合并前必须通过代码审查和 CI 检查。
* commit message格式必须为：<type>(<scope>): <操作/变更内容> (<步骤编号>)

## Repository layout

```
.github/                         GitHub Actions workflows and CI gates
backend/                         Spring Boot modular monolith backend
backend/src/main/java/           Backend modules and application code
backend/src/main/resources/      Runtime configuration and database migrations
backend/src/test/java/           Unit, integration, and architecture tests

contracts/                       Versioned API and event contracts
contracts/openapi/               Canonical REST/OpenAPI specifications
contracts/events/                Versioned domain-event schemas
contracts/examples/              Golden contract examples

frontend/web-app/                Vue 3 and Vite web application
frontend/web-app/src/            Web UI, routing, authentication, and API integration

desktop/desktop-shell/            Electron desktop application shell
desktop/desktop-shell/src/main/   Electron main-process runtime and security policies
desktop/desktop-shell/src/preload/Preload bridge implementation
desktop/desktop-shell/test/       Desktop security and authentication tests

packages/                        Shared TypeScript workspace packages
packages/api-client/             Generated TypeScript API client
packages/api-client/src/generated/Generated API models and endpoints
packages/api-client/test/        API client tests
packages/preload-contract/       Typed Electron preload IPC contract

deployment/windows/              Windows deployment scripts, runbooks, and checklists
docs/                            Product, architecture, delivery, and operations documentation
evidence/                        Milestone acceptance evidence and verification records

tools/                           Workspace validation and automation utilities
tools/architecture/              Workspace boundary and architecture checks
tools/events/                    Event contract validation tools
tools/openapi/                   OpenAPI linting and API client generation
tools/verification/              Milestone verification, packaging, and smoke tests
```

- Do not comment on facts obvious from code.
- 当变更建立、修改或推翻长期且未来可能被重新讨论的决策时，必须新增或更新 [Agent Note](.agents/notes/README.md#何时写-agent-note)；是否达到门槛由作者和评审进行语义判断，CI 只校验已存在 Note 的结构。
- [归档 Agent Note](.agents/notes/README.md#归档与删除) 是冻结历史：不得修改、移动、删除或作为当前实现的权威来源。
