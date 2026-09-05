# Agent Note: Content 作为项目级工作项类别目录

Status: implemented

## Problem

早期 Content 同时承担工作项类型、Table/Kanban 配置、归档容器和独立工作区，形成了第二套列顺序与查询入口。项目工作项总表已经拥有字段配置、排序、筛选与看板，因此继续保留 Content 视图配置会产生两个真源；类别切换还会被错误理解为类型转换或跨工作区移动。

本 Note 替代 [旧 Content 管理决策](2026-08-22-content-management-contract.md) 中由 Content 拥有类型、生命周期工作区和 `ContentViewConfig` 的部分。旧 Note 记录的项目写锁、Owner 治理、隐藏 404、强 ETag、幂等、防止虚假 blocker、事件不传播正文等理由仍由本决策吸收并保留。

## Decision

Content 保留后端名称，产品语义固定为 Project 级“工作项类别”。条目只保存稳定 code、名称、受控颜色、顺序、启用、保护、曾使用、版本和审计事实；不保存描述、工作项类型、视图配置或模板来源。模板蓝图只初始化 `{contentCode, displayName, colorToken, sortOrder}`，至少一个且 code 唯一。

默认“需求 / 任务 / 缺陷”受保护，初始颜色为亮蓝、明亮绿、深红。Owner 可改名、调色、排序和停用；受保护类别不可删除。Owner 创建的新类别从未使用时可软删除，一经工作项引用即永久记为曾使用，只能停用。目录始终至少有一个启用类别。Member 与 CompanyAdmin 可按项目可见性读取，只有 Owner 可管理。

目录 GET 返回条目、目录 rowVersion、强 ETag 和 `canManage`。创建只接受名称和颜色，code 由服务端生成并使用幂等键；PATCH/DELETE 使用项目嵌套路径和目录 ETag。不存在单 Content 工作区、Content 详情、archive/restore 或旧路由重定向。

Content 写事务继续先取得 Project 写守卫，再锁目录版本和目标条目。强目录 ETag 串行化新增、改名、排序、启停和删除；最后启用项、保护项与曾使用项由服务端拒绝。Content 本身不构成开放事项 blocker，Project blocker 仍直接统计 Work Item 真源。

## Alternatives considered

- 继续让 Content 保存列显隐、排序和 Kanban 分组：拒绝，因为项目总表已经成为唯一视图入口，双真源会持续漂移。
- 用客户端提交 `workItemType` 或从 code 推断类别语义：拒绝，code 是稳定标识而非类型枚举，服务端不再公开 Work Item 类型。
- 对使用过的类别做级联删除或把工作项迁到默认类别：拒绝，会改写历史事实和审计含义；停用保留引用最安全。
- 保留旧路由并重定向到项目总表：拒绝，会延长已删除工作区的契约寿命并掩盖调用方迁移。
- 全局关闭 OpenAPI 兼容检查：拒绝；本次破坏变更只由精确旧/新 SHA-256 放行。

## Consequences

项目工作项总表是 Table/Kanban 唯一入口，类别在根项、子项、创建和详情中表现为普通必填字段。选择器只显示启用类别及当前停用类别；停用不令既有事项只读。表格类别采用 26px 全圆角长条、36px 行高，上下留白 5px、水平内缩 24px并随列宽省略；详情保留 34px 全圆角长条，选择器采用 34px 高度与 xs 圆角。紧凑尺寸沿用步骤 5 的表格调整，资产门禁应与主表及子项表同时保持一致。

V48 是不可逆瘦身迁移；回滚需要从备份恢复旧列与旧 API，不允许通过空 JSON 重建 `ContentViewConfig`。冻结 v1 事件保留历史契约，当前 Content 生命周期使用无 `workItemType` 的 v2。任何重新引入 Content 级视图或类型都必须作为新的产品与数据决策评审。
