# Agent Note: MAIN 单工作空间契约

Status: implemented

## Problem

Workspace 曾承担 Company 内项目导航和归类的稳定入口，并提供创建、排序、归档、恢复、项目迁移与治理覆盖。实际产品没有多工作空间需求，继续暴露这些生命周期会让项目创建依赖无意义的选择、让列表和权限统计重复分组，并保留归档与项目写入之间的并发复杂度。收口时仍必须保护既有 Workspace 身份、Project 及其成员和内容事实、历史事件与治理记录，且不能把 Workspace 误变成授权边界。

## Decision

`catalog` 继续拥有 Workspace 事实，但每个 Company 永远只有一个内部主工作空间。它固定为 `code=MAIN`、`sortOrder=0`、`status=ACTIVE`，数据库以 Company 唯一约束和固定值检查约束关闭第二行、非 MAIN、非零排序及归档状态。Company 新建后由数据库初始化主空间；系统生成行允许创建人和更新人为空，管理员首次 PATCH 后记录更新人。主空间名称和可空描述仍可由 CompanyAdmin 通过强 ETag 修改，稳定 ID 不随改名改变。

公开接口只保留 Workspace 列表、详情以及名称/描述 PATCH。列表无分页且恰好返回当前 Company 的 MAIN；详情对同 Company 有效成员可见；PATCH 只允许 CompanyAdmin，规范化后无变化不写库、不递增版本、不产生事件。创建、排序修改、归档、恢复、Workspace 归档覆盖和跨 Workspace 项目迁移不再是可调用能力，Project capabilities 也不再包含 `canMoveWorkspace`。

Project 创建请求不接收 `workspaceId`。原子创建事务锁定当前 Company 的 ACTIVE MAIN 并自动写入其稳定 ID；响应继续返回 workspace ID/code/name 作为内部归属兼容信息。Project 恢复同样重新确认其归属仍是当前 MAIN。Workspace 不保存成员、角色或授权事实；Project 可见性继续只由 Company、ACTIVE Project membership、Owner 与 CompanyAdmin 权限谓词决定。

浏览器中的个人 `/workspace/{workspaceSlug}` 地址只是当前用户项目目录的规范展示入口，不对应 `catalog.workspace` 行，也不改变 MAIN 归属或授权谓词；其身份与不可变规则由[个人工作台规范路由](2026-08-24-personal-workspace-route.md)拥有。

V32 迁移按“现有 MAIN → 首个 ACTIVE → 首个 ARCHIVED → 新建主工作空间”选择每个 Company 的 canonical 行。已有行保留 ID、名称、描述、行版本、创建/更新时间和操作者，只规范化固定字段；没有历史行时使用默认名称“主工作空间”。所有生命周期的 Project 只更新 `workspace_id` 到 canonical ID，不修改 Project ID、成员、Content、关系、rowVersion 或业务时间，随后删除其余 Workspace 行并建立单例约束。迁移不可逆，执行前必须备份数据库，回滚通过备份恢复。

历史 `catalog.workspace_created`、`catalog.workspace_archived`、`catalog.workspace_restored`、`catalog.project_moved_to_workspace` 事件和 `WORKSPACE_ARCHIVE_WITH_ACTIVE_PROJECTS` 治理记录继续可读取，以维持 Activity、审计和恢复解释；运行时代码不再产生这些事实。治理创建契约只接受 Project 归档覆盖，历史响应枚举仍保留旧值。

## Alternatives considered

- 只在前端隐藏 Workspace 选择并保留多 Workspace 后端：拒绝。旧客户端和内部调用仍可创建、归档或迁移，数据库无法保证单例，权限分页和并发复杂度也不会消失。
- 新建 MAIN 并删除所有旧 Workspace 身份：拒绝。会无必要地改变稳定资源 ID，破坏历史事件、审计与外部引用的解释。
- 按 Project 数量最多或最近修改选择主空间：拒绝。选择依赖会变化的业务事实，难以预测且无法优先尊重已经显式存在的 MAIN。
- 把多个 Workspace 名称编码进 Project 或复制 membership、Content 和关系：拒绝。Workspace 只是内部归属字段；复制会制造第二事实源并改变不相关业务事实。
- 保留主空间归档或治理覆盖：拒绝。MAIN 必须永远可供项目创建和恢复，允许归档会重新引入共享锁、blocker 和不可用状态。
- 从主空间名称生成 code：拒绝。名称允许改名和本地化，不能承担稳定身份；code 永远固定为 MAIN。
- 删除全部历史事件 schema 和治理枚举：拒绝。历史消费者与审计查询仍需要解释已提交事实；停止产生新事件不等于抹除历史 wire 语义。

## Consequences

客户端不再加载 Workspace 作为项目创建或列表筛选选项，只在需要管理主空间名称/描述时使用 Workspace 资源。项目目录以单表展示，显式请求全部生命周期；所有行与 total 复用相同权限及筛选谓词，Workspace `visibleProjectCount` 仍可作为兼容派生值但不再参与分组。

数据库升级会合并并删除多余 Workspace 行，运维必须在 Flyway 执行前完成可恢复备份。任何未来重新引入多 Workspace 的提案都必须新增决策，说明授权边界、迁移、分页、生命周期并发和历史兼容，而不能移除单例约束后恢复旧接口。

本记录完整替代已封存的 [Workspace 生命周期契约](../../archived/product/2026-08-20-workspace-lifecycle-contract.md)。旧记录关于稳定身份、强 ETag、隐藏式跨 Company 资源、Workspace 非授权边界、真实权限计数、事件最小化及并发守卫的有效理由已经吸收到本决策；旧的创建、排序、归档、恢复和迁移语义只保留为历史。
