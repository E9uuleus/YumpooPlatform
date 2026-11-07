# Agent Note: 工作项字段图标

Status: implemented

## Problem

工作项字段在多处以“彩色方块 + 白色图标”的形式出现：连接列的搜索字段、连接卡片的字段行、新建连接列的类型，以及新增的固定列面板、显示列菜单和看板卡片标签。原先每处各自写图标与颜色，同一字段在两处可能是不同颜色（例如工作项名称在搜索字段里是紫色，与截止日期相同），新增字段时也没有可遵循的来源。

## Decision

[`workItemFieldIdentity.ts`](../../../../frontend/web-app/src/components/projects/workItemFieldIdentity.ts) 是工作项字段图标与颜色的唯一来源，[`WorkItemFieldGlyph.vue`](../../../../frontend/web-app/src/components/projects/WorkItemFieldGlyph.vue) 是唯一的渲染方式。任何界面显示字段图标都从登记表取，不再手写图标或颜色；新字段先在登记表登记再使用。

| 字段 | 图标 | 颜色 |
|---|---|---|
| 工作项名称 `title` | Tickets | `--yp-label-indigo` |
| 处理人 `assignee` | User | `--yp-label-chili-blue` |
| 状态 `status` | List | `--yp-label-green` |
| 优先级 `priority` | Flag | `--yp-label-orange` |
| 工作项类别 `content` | CollectionTag | `--yp-label-egg-yolk` |
| 截止日期 `dueDate` | Calendar | `--yp-label-purple` |
| 时长追踪 `timeTracking` | Timer | `--yp-label-aquamarine` |
| 最后更新时间 `updatedAt` | Refresh | `--yp-label-lavender` |
| 报告人 `reporter` | UserFilled | `--yp-label-teal` |
| 连接列 `connect:*` | Connection | `--yp-label-sunset` |

图标只用 `@element-plus/icons-vue`。默认样式是带字段色的圆角方块，20px 用 `--yp-radius-xs`，24px 用 `--yp-radius-sm`，图标为方块的 60%，白色前景用 `--yp-on-solid`（遵循[视觉契约](2026-10-31-visual-contract.md)，不用会随主题变深的 `--yp-text-inverse`）；`plain` 变体只显示次要文字色的图标，用于看板标签和空字段占位。当前使用位置：固定列面板、显示列菜单、连接搜索字段、连接卡片字段行、新建连接列类型、看板卡片标签与占位标签。表格列头不加图标，保持原有密度。

## Alternatives considered

- **各处手写图标与颜色：** 改动最小，但已经出现同一字段两种颜色，后续每加一个入口都要重新对齐。
- **引入 lucide 等图标库：** 图标更丰富，但项目只依赖 icons-vue，额外依赖与两套线条风格的成本高于收益。
- **列头也显示图标：** 与 Monday 的物品列设置一致，但会挤占窄列宽度，表格现有列宽是按纯文字列头调过的。

## Consequences

修改某个字段的颜色或图标只需改登记表，所有入口同步变化。连接搜索字段中的“名称”由紫色改为靛蓝，避免与截止日期撞色。登记表以表格列 key 为键，自定义字段出现时需要为其扩展登记方式（例如按字段类型取图标），而不是在调用处分支。
