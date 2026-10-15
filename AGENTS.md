# YumpooPlatform 项目约定

本文件补充项目约束；通用的自主执行、澄清、批准和完成规则沿用当前生效的全局 AGENTS.md。

- 编码约定：当前代码文件统一使用 UTF-8 编码，注意中文显示一致性，新增文件保持一致。
- 目录约定：`docs/` 文档和版本化原型导出物；`docs/`目录永远不被跟踪，也不加入提交、推送。
## Git 分支与交付

- 子功能开发必须基于最新的 `dev` 分支创建独立分支，例如 `feature/<功能名>`或`fix/<功能名>`。已有对应任务分支时继续使用，不为阶段切换重复建分支。
- 变更集成必须通过目标为 `dev` 的 PR；禁止直接推送 `dev`，合并前必须通过代码审查和 CI。
- 任务明确包含相应交付阶段时持续完成，不把早期阶段当成全部完成。
- commit message格式必须为：<type>(<scope>): <操作/变更内容> (<步骤编号>)

## 数据库迁移

- 新迁移默认必须「只扩展」：新建表或索引、可空或带默认值的新增列等，保证上一版本可快速回退；`ci:contracts` 的迁移回退安全检查拒绝白名单外的语句。
- 删除、改名、类型或约束收紧、无默认值的 NOT NULL、修改已有数据等破坏性变更，拆为 expand/contract 两次发布；确需一次完成时在 `contracts/migrations/rollback-safety-exceptions.json` 登记精确例外（`breaking` 例外须链接已实施 Agent Note）。规则见[迁移回退安全](.agents/notes/implemented/data/2026-10-15-rollback-safe-migrations.md)。

## 版本号

- 产品版本以 Release tag 为准，构建产物只携带 commit。工作区 package.json 与服务端 pom 的版本是占位值，不得手工修改（desktop-shell 另有桌面版本线）。规则见[发布版本标识](.agents/notes/implemented/process/2026-10-15-release-version-identity.md)。

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
