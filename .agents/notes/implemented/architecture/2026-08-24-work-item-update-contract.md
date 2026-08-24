# Agent Note: M2-16 Work Item 独立讨论契约

Status: implemented

## Problem

Work Item 需要可分页、可提及项目成员的独立讨论流，但既有 description/notes 是父事项字段，修改它们会推进父版本并混淆字段编辑与讨论发布。富文本还会引入脚本、危险链接、伪造 Mention 显示名和正文进入领域事件的安全风险；向上加载历史窗口也需要稳定且不依赖 offset 的顺序语义。

## Decision

`WorkItemUpdate` 是独立聚合。发布 Update 不改变父 Work Item 的 `rowVersion`、ETag 或 `updatedAt`。V35 使用 `work_item_update` 保存作者显示名、净化 HTML、纯文本、状态、固定 `createdAt + 15 分钟` 的编辑截止、版本以及 M2-17 所需编辑/删除预留字段；`work_item_update_mention` 保存发布时 Mention 用户与显示名快照，并以 `(updateId, mentionedUserId)` 去重。两表都通过企业边界外键连接父事项、作者和 Mention 用户。

服务端 `CollaborationHtmlSanitizer` 是 foundation 端口。白名单只允许 `p/br/strong/em/ul/ol/li/blockquote/code/a`，以及同时带有 `data-type="mention"` 和合法 UUID `data-mention-user-id` 的 `span`。链接必须是绝对 `http/https/mailto`，并重写 `target="_blank"` 与 `rel="nofollow noopener noreferrer"`；样式、class、事件属性、图片、标题、表格、代码块和未知标签均不保留。Mention 显示名一律按发布时的权威用户快照重写；净化后正文纯文本非空，HTML 最多 65,536 字符，纯文本最多 16,384 字符。

发布先完成 Work Item 可见性判断，再在事务中固定按 Project → Content → Work Item 加锁，复核父资源可写，并用一次批量查询校验所有 Mention 都是 ACTIVE 项目成员。Owner 与 ACTIVE Member 可读写；CompanyAdmin 只读；不可见、跨企业和墓碑事项隐藏为 404；Project 或 Content 归档后历史仍可读而发布返回 409。幂等键同请求精确重放原 201、Location、ETag 与正文响应，不重复 Mention 或事件；异参复用返回 409。

GET 初次读取最新窗口，数据库以 `createdAt DESC, id DESC` 选窗后对外始终按 `createdAt ASC, id ASC` 展示。`nextCursor` 是 Base64URL v1 的时间与 ID 组合键，只用于继续加载更早窗口；并发新增不会改变既有向上游标边界，不支持 offset。

发布只产生一条 `workitem.work_item_update_published` v1 事件，聚合类型为 `WorkItemUpdate`。载荷包含 Update、Work Item、Project、Content 引用，事项编号/标题、作者、排序后的 Mention 用户 ID 与版本，不携带 HTML 或纯文本正文。

## Alternatives considered

- 复用 Work Item notes 或 description：拒绝。讨论会无意义地推进父版本、覆盖协作字段，并无法独立分页与治理。
- 客户端传独立 Mention ID 列表：拒绝。正文与身份列表可能漂移；服务端只从净化后的 Mention wire 提取并验证。
- 接受任意 class 或内联样式来兼容编辑器输出：拒绝。会扩大 XSS 与视觉欺骗面，编辑器必须服从服务端白名单。
- 使用 offset 或从旧到新持续向后分页：拒绝。讨论页需要先显示最新消息并向上加载历史；复合游标能处理相同时间与并发新增。
- 在发布事件中携带正文供 Activity 使用：拒绝。正文可能包含敏感项目讨论；M2-20 投影应在授权边界内读取真源。

## Consequences

Web 只能渲染服务端返回的净化 HTML，不能乐观插入本地原文。向上加载时在顶部插入旧窗口并保持滚动位置；发布成功后使用响应追加并滚到底部。传输失败保留草稿及原幂等键，正文改变后才生成新键。关闭抽屉或切换事项必须确认丢弃未发布草稿；归档资源和 CompanyAdmin 只展示历史及只读原因。

备份恢复必须保留两张表的正文、状态、编辑截止、版本及 Mention 关系。M2-17 才开放 15 分钟编辑/删除与负责人治理删除；附件、Activity、最终事件盘点分别留给 M2-18/19、M2-20、M2-23。`FeedbackUpdate` 仍由 M3B 的 `productfeedback` 模块独立实现；本决定不改变现有 description/notes 的纯文本契约，也不引入实时更新、通知或未读数。
