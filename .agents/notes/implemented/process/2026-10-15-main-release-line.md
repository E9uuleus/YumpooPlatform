# Agent Note: main 发布线、晋升准则与 Release 草稿

Status: implemented

## Problem

此前 `dev` 既是集成分支也是发布来源：任何合入 `dev` 的改动都会随下一次 Release 一起上线，热修复只能先进 `dev`。`main` 只有一个初始提交，Release 由人手动创建：手选 target、手写标签与说明，并在发布之后才上传附件。这带来三个问题：

- 标签和附件在源头上可改写；
- 默认分支指向空置的 `main`，造成界面陷阱；
- 没有「上一个已发布版本」的明确记录，回退和热修复都缺少依托。

## Decision

**分支模型**

- `dev` 是集成分支（也是默认分支）。功能、修复 PR 用 squash 合入 `dev`。
- `main` 是已发布线，只接收两类 PR：本仓库 `dev` 的晋升 PR，以及 `hotfix/<名称>`。二者都用合并提交合并，`main` 的推送 CI 产物是唯一可发布的服务器包。
- 热修复合入 `main` 后，[Back-merge Main](../../../../.github/workflows/back-merge.yml) 在 `main` 含有 `dev` 缺少的改动时，从 `backmerge/<commit>` 分支向 `dev` 开 PR，必须用合并提交合并。判定依据是 `dev...main` 的差异：普通晋升的合并提交不引入树变化，因此不会触发。已有回合并 PR 时，只有它已包含当前 `main` 才复用；否则把仍是 `main` 祖先的回合并分支快进到 `main`，无法快进（已被 Update branch 更新）时另开 PR。工作流在 `main` 与 `dev` 的每次推送后都重新核对，合并旧回合并 PR 后遗漏的后续热修复会再次被处理。

**晋升准则**

由 [Main Promotion Policy](../../../../.github/workflows/main-promotion-policy.yml) 作为 required check 执行，并在 PR 中维护同一条汇总评论。

硬性规则：

- 来源只能是本仓库的 `dev` 或 `hotfix/*`；
- PR 合并结果上的完整 CI 必须通过；
- 契约兼容以线上 `main` 为基线。相对线上有破坏性 API 变更时，必须先在 `dev` 上登记检查给出的 `(oldSha256, newSha256)` 精确例外；
- 增量中含非只扩展迁移时，必须加 `release:breaking-migration` 标签，表示已完成恢复演练、接受该版本不能快速回退。

人工确认清单：功能完整或已隐藏、观察窗口有人值守、避开业务高峰与节前、上一版本稳定。

**版本与草稿**

[Prepare Release](../../../../.github/workflows/prepare-release.yml) 在 `main` 推送 CI 成功后运行：

- 按北京时间日期自动编号 `server-vYYYY.MM.DD.N`。已有标签都占号，避免与不可变发布留下的标签冲突。
- 根据上一个已发布版本到本提交的非合并提交，生成分组发布说明，并列出新增迁移和契约变化。
- 创建**草稿** Release，在草稿阶段上传并核验来源清单和 CI 原包。来源清单最先上传，用来钉住 CI attempt；中断重跑不会换到另一次构建。
- 永不自动删除草稿。源码是新草稿祖先的旧草稿在说明、工作流摘要和通知中标记为已被取代，源码不早于它的其他草稿另行提示核对来源 SHA（生成时间新不代表源码新），都由发布负责人手动删除；已发布更新版本时，拒绝为更旧提交生成草稿。发布工具的 GitHub 客户端不提供 DELETE。

**发布与回退**

- 人工点击 Publish 即为上线决定。仓库开启 Immutable Releases 后，标签和附件在发布后锁定。
- [Windows Release](../../../../.github/workflows/windows-release.yml) 只校验「已发布、不可变、附件齐全、来自 `main` 推送 CI」，然后部署，不再写附件。
- [Server Rollback](../../../../.github/workflows/server-rollback.yml) 必须在上一版本的 tag 上手动触发，与发布共用串行通道，只提交代码回退请求；服务器端的前提由[发布部署决策](2026-10-14-windows-release-deployment.md)负责。
- 草稿生成、校验失败、部署或回退作业失败时，通过仓库 secret `WECOM_RELEASE_WEBHOOK` 推送企业微信通知；只接受群机器人 URL 的字面形式，失败不影响工作流结果。

**远端设置**：规则集 `Protect main`、`Release tags`，默认分支 `dev`，以及 Immutable Releases，都由维护者配置，清单见 [CI 说明](../../../../.github/CI.md#远端规则)。仓库代码不修改这些设置。

## Alternatives considered

- **工作流把 `main` 快进到已测的 `dev` 提交**：能复用 `dev` 的 CI 产物、零额外构建，但需要一个能绕过 `main` 规则集的 GitHub App/PAT，也没有可评审的晋升页面；改为 PR 加合并提交，代价是每次晋升多一轮 CI。
- **仓库内单一版本源，每次发布前提交版本号**：每次发布多一个 PR，并与「发布时决定版本」冲突；改为草稿生成时自动编号（见[发布版本标识](2026-10-15-release-version-identity.md)）。
- **保留发布后上传附件**：与 Immutable Releases 不兼容，也让已发布的附件可在发布后补写；改为草稿阶段完成全部附件。
- **默认分支设为 `main`**：发布界面不再需要手选分支，但日常 PR、Dependabot 和定时任务会默认指向发布线；草稿已经自动生成，因此默认分支设为 `dev`。
- **自动删除被取代的草稿**：GitHub 按 Release ID 无条件删除，即使删除前重新读取状态，人工 Publish 仍可能落在读取与删除之间，导致刚发布的不可变 Release 被删；误发旧草稿的后果只是部署一个较旧但仍前进的 `main` 版本，远小于丢失已发布版本，因此改为只标记、人工删除。
- **直接从 `main` 开 back-merge PR**：在 `dev` 要求分支最新时，「Update branch」会把 `dev` 合进 `main`；改为从 `main` 提交建立专用分支。

## Consequences

- 每次晋升会额外跑「合并结果的 PR CI」和「`main` 推送 CI」两轮。仓库是 Public，Actions 不计费。
- 草稿和回合并 PR 由 `GITHUB_TOKEN` 创建，GitHub 不会为它们触发其他工作流。回合并 PR 的 head 提交沿用 `main` 推送 CI 的检查结果；需要更新分支时由人操作，以触发 CI。
- `workflow_run` 与 `workflow_dispatch` 使用默认分支 `dev` 上的工作流定义。Prepare Release 会检出被发布的 `main` 提交来执行发布脚本，因此只信任同仓库 `main` 推送产生的运行。
- 切换前从 `dev` 发布的历史版本不满足 `main` 来源要求，也没有发布元数据：既不能被重新部署，也不能作为回退目标。快速回退从切换后的第二个 `main` 版本起可用。
- 部署引擎只有在管理员用 `-ReleaseBranch main -RequireImmutable` 升级之后才接受 `main` 来源。所以切换顺序固定为：
  1. 合入发布线代码；
  2. 把 `main` 快进到 `dev`；
  3. 配置远端设置；
  4. 生成第一个草稿；
  5. 升级引擎；
  6. 发布。
