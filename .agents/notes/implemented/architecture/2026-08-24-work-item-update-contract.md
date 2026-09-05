# Agent Note: M2-16/M2-17 Work Item 独立讨论与生命周期治理契约

Status: implemented

当前前端倒序流与富文本白名单由[编辑器决定](../product/2026-09-05-work-item-discussion-composer.md)部分替代。操作时限、统一删除、两级回复、共享置顶、UI 删除展示及新事件版本由[两级评论决定](../product/2026-09-05-work-item-discussion-threads.md)部分替代。本文继续拥有独立聚合、净化与 Mention 身份、强版本、幂等、事务审计和事件隐私理由；下文时限、治理理由与 v1 描述为被替代决定的历史说明。

## Problem

Work Item 需要可分页、可提及项目成员的独立讨论流，但既有 description/notes 是父事项字段，修改它们会推进父版本并混淆字段编辑与讨论发布。讨论还需要限时纠错、作者撤回和负责人治理，同时不能让删除破坏时间线顺序、审计链或泄漏已经删除的正文。富文本会引入脚本、危险链接、伪造 Mention 显示名和正文进入领域事件的安全风险；向上加载历史窗口也需要稳定且不依赖 offset 的顺序语义。

## Decision

`WorkItemUpdate` 是独立聚合。发布 Update 不改变父 Work Item 的 `rowVersion`、ETag 或 `updatedAt`。V35 使用 `work_item_update` 保存作者显示名、净化 HTML、纯文本、状态、固定 `createdAt + 15 分钟` 的编辑截止、版本以及 M2-17 所需编辑/删除预留字段；`work_item_update_mention` 保存发布时 Mention 用户与显示名快照，并以 `(updateId, mentionedUserId)` 去重。两表都通过企业边界外键连接父事项、作者和 Mention 用户。

服务端 `CollaborationHtmlSanitizer` 是 foundation 端口。白名单只允许 `p/br/strong/em/ul/ol/li/blockquote/code/a`，以及同时带有 `data-type="mention"` 和合法 UUID `data-mention-user-id` 的 `span`。链接必须是绝对 `http/https/mailto`，并重写 `target="_blank"` 与 `rel="nofollow noopener noreferrer"`；样式、class、事件属性、图片、标题、表格、代码块和未知标签均不保留。Mention 显示名一律按发布时的权威用户快照重写；净化后正文纯文本非空，HTML 最多 65,536 字符，纯文本最多 16,384 字符。

发布先完成 Work Item 可见性判断，再在事务中固定按 Project → Content → Work Item 加锁，复核父资源可写，并用一次批量查询校验所有 Mention 都是 ACTIVE 项目成员。Owner 与 ACTIVE Member 可读写；CompanyAdmin 只读；不可见、跨企业和墓碑事项隐藏为 404；Project 或 Content 归档后历史仍可读而发布返回 409。幂等键同请求精确重放原 201、Location、ETag 与正文响应，不重复 Mention 或事件；异参复用返回 409。

M2-17 以服务端时钟和固定 `editDeadlineAt` 管理生命周期，截止时刻本身已经超窗。仅作者可在窗口内编辑或无理由自删；编辑重新执行完整净化与 ACTIVE 项目成员校验，并原子替换 Mention 集合和显示名快照。净化后正文不变时不增版、不发事件。当前 ProjectOwner 可随时提供去除首尾空白后的 1–500 字理由治理删除，包括 Project 或 Content 已归档时；CompanyAdmin、普通成员和非作者没有治理权。所有写操作要求强 `If-Match`，只推进 Update 版本，不改变父 Work Item。

删除不可恢复且保留原时间线位置、作者、创建时间、既有编辑事实、删除时间、操作者及 Mention 快照；正文 HTML/纯文本必须置为 `NULL`。作者自删不保存理由，治理删除必须保存理由。所有成功删除与业务事实、Security Audit、Outbox 在同一事务提交；治理拒绝回滚后使用独立事务写失败审计，审计不可写时失败关闭。公开的 Project 治理锁只绕过归档写阻断，不绕过企业边界、资源可见性或当前 Owner 判断。

为满足上述同事务与独立失败审计，模块允许矩阵显式增加 `workitem → audit.api` 依赖；workitem 只能调用审计模块公开追加端口，不能访问审计 application、domain 或 infrastructure 实现。该依赖不允许反向读取审计数据，也不改变 audit 仅依赖 foundation 的下游方向。

GET 初次读取最新窗口，数据库以 `createdAt DESC, id DESC` 选窗后对外始终按 `createdAt ASC, id ASC` 展示。`nextCursor` 是 Base64URL v1 的时间与 ID 组合键，只用于继续加载更早窗口；并发新增不会改变既有向上游标边界，不支持 offset。

发布、编辑和删除分别产生 `workitem.work_item_update_published`、`workitem.work_item_update_edited` 与 `workitem.work_item_update_deleted` v1 事件，聚合类型均为 `WorkItemUpdate`。载荷只包含资源引用、操作者、前后状态和版本、正文长度变化或 Mention ID 增删、删除模式与治理理由，不携带 HTML、纯文本、可还原摘要或附件信息。业务事务不直接写 Activity；M2-20 从事件投影和查询 Activity。

## Alternatives considered

- 复用 Work Item notes 或 description：拒绝。讨论会无意义地推进父版本、覆盖协作字段，并无法独立分页与治理。
- 客户端传独立 Mention ID 列表：拒绝。正文与身份列表可能漂移；服务端只从净化后的 Mention wire 提取并验证。
- 接受任意 class 或内联样式来兼容编辑器输出：拒绝。会扩大 XSS 与视觉欺骗面，编辑器必须服从服务端白名单。
- 使用 offset 或从旧到新持续向后分页：拒绝。讨论页需要先显示最新消息并向上加载历史；复合游标能处理相同时间与并发新增。
- 在发布事件中携带正文供 Activity 使用：拒绝。正文可能包含敏感项目讨论；M2-20 投影应在授权边界内读取真源。
- 物理删除记录或同步删除 Mention：拒绝。时间线位置、参与关系、治理审计和事件对账都需要不可变墓碑。
- 允许 CompanyAdmin 治理项目讨论：拒绝。CompanyAdmin 在项目业务数据上仍只读，治理责任属于当前 ProjectOwner。
- 保存完整编辑前正文或可还原摘录：拒绝。M2-17 只保留最小长度、版本和 Mention 差异，降低删除后的内容泄漏面。

## Consequences

Web 只能渲染服务端返回的净化 HTML，不能乐观插入本地原文。向上加载时在顶部插入旧窗口并保持滚动位置；发布成功后使用响应追加并滚到底部。传输失败保留草稿及原幂等键，正文改变后才生成新键。关闭抽屉或切换事项必须确认丢弃未发布草稿；归档资源和 CompanyAdmin 只展示历史及只读原因。

备份恢复必须保留两张表的正文或墓碑空值、状态、编辑/删除操作者与时间、治理理由、版本及 Mention 关系。客户端能力字段和本地截止计时只用于操作提示，服务端授权、时钟、归档状态和版本始终是最终裁决；412 刷新单条真源但保留编辑草稿，409 关闭写能力并允许复制草稿。附件、Activity、最终事件盘点分别留给 M2-18/19、M2-20、M2-23。`FeedbackUpdate` 仍由 M3B 的 `productfeedback` 模块独立实现；本决定不改变现有 description/notes 的纯文本契约，也不引入实时更新、通知或未读数。
