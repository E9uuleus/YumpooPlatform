# Agent Note: 非项目固定分类工时

Status: implemented

## Problem

出差、学习、商务、会议这类工作没有对应的项目或工作项。之前，用户只能找一个工作项挂靠，或者干脆不记。挂靠会把工时算进不相干的项目统计，不记则会让个人和团队日工时偏低。

用户要的是：在「我的一天」和快捷工时小窗里像记工作项一样记这类时间，记录、补录、计时都和工作项一致，只是不关联工作项，可选关联项目。

原模型里，`work_item_time_session` 与 `work_item_time_share` 的 `work_item_id`、`project_id` 都是 NOT NULL，所有写路径与报表都假定记录属于某个工作项。

## Decision

### 关联项目（2026-10-26）

- 分类记录的关联项目保存在会话与份额新增的 `category_project_id`；原 `project_id` 与 `work_item_id` 仍为 NULL，V81 的 CHECK 与按 `project_id` 的统计口径保持不变。
- V82 只新增可空列与索引，旧版本会忽略新字段，支持快速回退，不登记回退例外。
- 同一用户同一分类仍只能有一个运行计时，与关联项目无关；开始「会议@B」时先停止正在运行的「会议@A」。
- 时间线上一种分类只占一个位置：补录的 PARALLEL / REPLACE 阻挡与同一请求内的冲突按分类判断，与关联项目无关（`TimeSubject.slot()`）；subject 仍含项目，用于合并、目标排除、去重与改归属。
- 写入只允许关联本人可写的项目；分类仍属于个人，不加项目锁，不发领域事件。
- 项目归档不停止关联分类计时，项目统计、工作项汇总与工作项表格不包含分类工时。
- 不复用 `project_id`：复用会破坏 V81 的 CHECK，并迫使所有按项目统计的查询逐一排除分类；旧版本回退时也无法保持原有口径。

### 数据模型

- **固定分类**：`TimeCategory` 内置四类：`BUSINESS_TRIP`（出差）、`LEARNING`（学习）、`BUSINESS`（商务）、`MEETING`（会议）。
  - 命名为 `timeCategory`（列名 `time_category`），因为代码里的「类别」已经指工作项内容类型 `contentCode`。
  - 分类写在代码、V81 的 CHECK 和 OpenAPI 枚举里，不做管理员配置。
- **会话与份额（V81）**：
  - 两表都新增 `time_category varchar(32)`，并放宽 `work_item_id` 与 `project_id` 的 NOT NULL。
  - CHECK 约束 `ck_work_item_time_session_subject` / `ck_work_item_time_share_subject` 保证二选一：工作项记录同时带项目与工作项、没有分类；分类记录只带分类、项目与工作项为空。第二支显式写 `time_category IS NOT NULL`，避免三列全空时 CHECK 结果为 NULL 而放行。
  - 同一用户同一分类最多一个运行计时，由部分唯一索引 `uq_work_item_running_timer_category` 保证。原工作项唯一索引不变，因为 NULL 在唯一索引里互不冲突。
  - 外键不变：MATCH SIMPLE 会跳过 NULL。
- **记录归属（subject）**：
  - 应用层用 `TimeSubject`（`Item(workItemId)` 或 `Category(timeCategory, projectId)`）表示一条记录记在什么上。
  - 补录合并、REPLACE 的目标排除、切换与开始的去重都按 subject 比较；PARALLEL / REPLACE 阻挡与同项重叠校验按 slot 比较（工作项即自身，分类不区分项目）。
  - 份额计算本就与 subject 无关：分类与工作项按并发 1/N 分摊，份额行带上分类。

### 写入

- **权限**：分类记录只属于本人。
  - 写入不走项目 guard、不加项目锁，只要求当前有效成员；他人的记录返回 404。
  - 并发由本人 `work_item_timer_state` 行锁串行化。
  - 工作项侧的锁序仍是：项目锁、计时状态锁、工作项锁。
- **补录**：`POST /me/time-sessions/fill` 的每个条目在 `workItemId` 与 `timeCategory` 中二选一。
  - REPLACE 只能裁剪「已停止且未删除」的分类记录；工作项记录仍须满足原有的可编辑条件。
  - `reassign` 支持工作项与分类之间的四个方向；目标与原归属相同返回 UNCHANGED。
- **计时**：`start` 和 `switch` 接受 `timeCategory`；批量 `start` 合并 `workItemIds` 与 `timeCategories`，合计 1–10 项。
  - `currentTimeTracker.resumeCategorySubjects` 与 `resumeItems` 取自同一批最近停止的计时，所以「继续」能恢复混合的一组及分类关联项目；`resumeCategories` 保留分类代码供旧客户端读取。
- **编辑与删除**：原 `/work-items/{id}/time-sessions/{sessionId}` 路径要求带工作项，所以新增只限本人的 `PATCH` / `DELETE /me/time-sessions/{sessionId}`。
  - 工作项记录委托给原 edit/delete 逻辑，会重新校验可见性。
  - 分类记录在本人计时状态锁内校验 If-Match、运行状态与同分类重叠。
  - 幂等范围是 `timeTracking:editOwn` 和 `timeTracking:deleteOwn`。
  - 「我的一天」的编辑和删除统一走这两个端点。
- **幂等哈希**：新增的请求字段都标 `@JsonInclude(NON_NULL)`，只含工作项的请求体哈希保持不变，跨部署的重试不会被拒。

### 事件、审计与读取

- **事件与审计**：分类记录不是工作项事实，只写安全审计，不发 `workitem.time_tracking_*` 领域事件。
  - 活动投影与单元格活动投影都要求项目和工作项，此外没有其他订阅者，所以事件 schema 与版本不变。
- **本人记录与项目归档**：
  - `GET /me/time-sessions` 对分类记录总是披露并允许编辑：`session.timeCategory` 有值，项目与工作项字段为空。
  - 客户端要先看 `timeCategory`，再按 null 的项目或工作项字段判断「不可见」。
  - 归档项目只停该项目的计时；项目清除不删除分类记录。
- **报表口径**：
  - `/me/timesheet` 与团队工时表新增 `categoryEntries`，按成员、分类、自然日给出均摊后的计入时长。本人工时不依赖项目成员身份。
  - 团队工时按项目筛选时只返回关联到所选项目的分类；未筛选时返回所有分类。关联项目的名称仅在调用者可见范围内披露。
  - 工时异常定时任务把分类计入每人每日合计。
  - 项目统计、工作项汇总、候选与最近工作项都按工作项或项目过滤，不含分类。

## Alternatives considered

- **系统公共项目**：每个公司自动建一个隐藏项目，四类各对应一个工作项。不用迁移，报表也会自动包含；但成员资格、项目列表、统计、归档、清除和权限都要特殊屏蔽，属于绕路方案，所以放弃。
- **独立的分类工时表**：分类与工作项必须共同参与并发分摊、GAPS 空档和 REPLACE 裁剪。拆成两张表会复制整条写路径与分摊逻辑。
- **拆成两次发布（先容错、再放宽约束）**：可以保留快速回退，但要多一次发版，功能也要推迟到第二次发布才能用。用户选择一次发布，并把 V81 如实登记为破坏性迁移。
- **管理员可配置分类**：需要新表、管理页和接口。当前四类足够，按需再加。
- **复用 `project_id` 关联分类**：会破坏 V81 的 CHECK 和项目统计口径。采用独立可空字段，只校验关联项目可写性，不改变分类个人记录属性。

## Consequences

- **项目口径**：项目统计页仍不含分类工时；团队「项目工时」包含关联到项目的分类工时。

- **回退**：V81 在 `contracts/migrations/rollback-safety-exceptions.json` 登记为 `breaking`。晋升到 `main` 的 PR 须加 `release:breaking-migration` 标签，上线后不能用 Server Rollback 快速回退到上一版本。旧版本读到分类行时，会在份额重建、停止全部、切换、归档停表和项目清除处空指针失败，也会丢失分类份额。
- **项目工时稀释**：分类与工作项按并发分摊，会议和工作项重叠时，工作项计入的工时会减半。项目统计本身不含分类工时。
- **新增分类属于契约破坏**：响应枚举扩展会被 openapi-diff 判为不兼容，需要同时登记 OpenAPI 例外并修改 V81 的 CHECK（新迁移）。
- **活动与通知**：分类工时不进入项目活动或「我的动态」，只在安全审计中留痕。
- **数据清理**：分类记录不会被任何现有清除路径删除。将来做用户数据清除时须覆盖这部分。
- **旧客户端**：长期运行的旧前端（例如预热的快捷小窗）会把分类记录显示为不可见工作项，编辑也会失败，直到重新加载到新版本。
- **相关记录**：本记录部分扩展 [工作项原始计时与跨端退出](2026-09-07-work-item-time-tracking.md) 与 [多项并行计时](2026-10-14-parallel-time-tracking.md)，两者其余约束不变。
