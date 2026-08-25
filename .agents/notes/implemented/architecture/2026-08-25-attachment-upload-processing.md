# Agent Note: M2-18/M2-19 附件生命周期与安全维护闭环

Status: implemented

## Problem

Work Item 与已发布讨论需要上传附件，但一次 HTTP 请求内完成接收、查毒和发布会把大文件 I/O、外部扫描器故障与数据库事务耦合。进程重启会丢失内存任务，扫描器短时不可用会产生不确定结果，并发上传若先写文件后检查配额也会超卖。多态 owner 的鉴权又属于 workitem 真源，filestorage 不能通过反向依赖 Repository 获取业务状态。对外 metadata 和事件还必须避免泄漏哈希、本机路径、storage key、扫描器输出或业务正文。

## Decision

V37 将 `attachment`、`attachment_scan_task` 与 `attachment_quota_usage` 作为 filestorage 独占真源。附件业务状态固定为 `UPLOADING/AVAILABLE/REJECTED/DELETED`，接收、排队、扫描和最终化只记录在隐藏的处理阶段。四种 owner 枚举一次冻结；M2-18 只接受 `WORK_ITEM` 与 `WORK_ITEM_UPDATE`，Feedback 两种 owner 由 M3B 开放。`AVAILABLE` 由数据库约束强制具备正数大小、SHA-256、探测 MIME、storage key 和可用时刻。

创建意图使用持久幂等键，缺省预约 100 MiB，显式 `sizeBytes` 作为本意图的接收上限。Company 与 Project 配额行按 COMPANY → PROJECT 固定顺序锁定，默认上限分别为 100 GiB 与 10 GiB。接收使用固定 64 KiB 缓冲并同步计算 SHA-256，不持有数据库事务；同一附件通过上传租约只允许一个活动 PUT。断流清理 `.part` 并解除租约以允许复用原意图，超出固定 100 MiB 或预约量分别稳定拒绝，封存后释放预约差额。

扫描任务是可重启的持久队列。工作器用 `FOR UPDATE SKIP LOCKED` 短事务领取，默认并发 2、租约 5 分钟；扫描器不可用在 5 秒与 30 秒后重试，第三次失败成为 `SCAN_UNAVAILABLE` 并保留 sealed 内容 24 小时。探测 MIME 只在查毒成功后持久化，并作为本代“安全扫描已通过”的检查点：因此不可用重试不会绕过查毒，而发布移动成功但数据库提交失败时，下一次可验证内容寻址 blob 并继续最终化。恶意、类型不符与完整性失败稳定拒绝；发布后的安全孤儿由 M2-19 清理。

模块调用方向固定为 administration.application → filestorage.api 与 workitem.api。filestorage 只提供意图、接收、租赁、CAS 状态与配额原子变更，不依赖 workitem。workitem.api 通过应用服务给出当前 Work Item 或未删除 Update 的读、写及原上传者后台鉴权结论，不暴露 Repository。最终事务重新验证上传者仍有效以及 Project、Content、Work Item、Update 当前可写，再原子提交 AVAILABLE、配额转换、Security Audit 与唯一 `filestorage.attachment_available` v1 Outbox 事件。

公开 metadata 只提供业务引用、净化文件名、声明/探测 MIME、大小、状态、拒绝码、版本和由当前主体、父对象及附件状态共同计算的 `canUploadContent/canDownloadContent/canDelete`；处理阶段、哈希、路径、storage key 与扫描器输出始终隐藏。事件 payload 同样只携带安全业务引用、文件名、探测类型、大小、操作者和版本，信封承载 Company 与发生时刻。APP_MANAGER 只能按当前 Company 对仍在宽限期的 `REJECTED/SCAN_UNAVAILABLE` 发起带理由、强 ETag 与幂等键的重扫，不因此获得普通附件读取权；成功与失败均记安全审计。

M2-19 下载仅接受 `AVAILABLE`，并在每次请求实时重新校验父对象读取权。正式文件在响应开始前按数据库登记大小和 SHA-256 完整核验，再使用 64 KiB 缓冲流式输出；缺件或篡改统一返回不含内部细节的 `DEPENDENCY_UNAVAILABLE`，同时登记内部对账问题。响应强制 UTF-8 附件文件名、检测 MIME、`nosniff`、`private, no-store` 与 `CSP: sandbox`，不支持 Range、在线预览或永久 URL。

逻辑删除只允许当前仍可写父对象的成员或负责人执行 `AVAILABLE → DELETED`，要求 XSRF、强 ETag、稳定幂等键和 1–500 字理由。状态、删除事实、可用配额释放、`ATTACHMENT_DELETED` Security Audit、`filestorage.attachment_deleted` v1 Outbox 与幂等结果在同一事务提交；物理 blob 不删除，任何 `DELETED` 引用都继续保护它。删除后普通 metadata、列表和下载隐藏墓碑，同一幂等键仍可重放墓碑响应。

V38 的 `attachment_blob` 是物理内容登记真源，但不维护易漂移的引用计数；保护引用始终从 `attachment` 查询。发布和清理共用 `PUBLISH/CLEANUP` 操作租约，清理领取租约前再次检查同摘要 `UPLOADING` 及 `AVAILABLE/DELETED` 引用。维护运行以数据库持久化阶段、游标、计数和租约，默认启动延迟 5 分钟、每分钟至多 100 条短事务续跑、完整运行间隔 24 小时。它拒绝过期意图并释放预约，核对正式 blob、配额和扫描代次，登记临时/发布孤儿和异常目录项；孤儿必须文件年龄和连续观察都满 24 小时。物理删除默认 dry-run，启用时必须有非空批准引用；异常 key、符号链接或重解析点只告警。

生产 profile 必须使用已经存在且非符号链接的附件/临时目录，并提供可验证的 Defender 可执行文件，否则启动失败关闭。普通门禁使用可控扫描器且不生成 EICAR；Defender、NTFS、同卷原子移动与 EICAR 只在显式 opt-in 的 live 门禁运行。

## Alternatives considered

- 在 PUT 请求中同步查毒并直接返回 AVAILABLE：拒绝。扫描器延迟、重试和进程重启会占住请求与事务，无法提供持久恢复语义。
- 使用内存队列或只扫描目录：拒绝。无法原子关联代次、尝试次数、租约、最终结果和数据库业务状态。
- 先完整接收再检查 Company/Project 配额：拒绝。并发请求会超卖，失败时还会无界占用临时磁盘。
- 由 filestorage 直接读取 Work Item Repository：拒绝。会形成模块反向依赖并绕过 workitem 的当前授权规则。
- 在扫描器不可用后持久化 detected MIME 并据此跳过后续扫描：拒绝。类型探测不是恶意内容检查，不能充当安全检查点。
- 在事件或 metadata 中暴露 SHA-256、storage key 或本机路径：拒绝。这些是内部存储与安全实现细节，会扩大信息泄漏和未来迁移成本。
- 为尚未发布的 Update 草稿创建附件 owner：拒绝。草稿没有稳定业务身份；M2-18 只绑定已存在且未删除的 Update。
- 删除附件时立即删除内容寻址 blob：拒绝。共享 blob、并发发布、幂等重放、审计保留和备份门禁都要求先保留物理内容；正式墓碑 blob 的 30 天、legal hold 与备份门禁清理由 M5-17 决策。
- 保存物理 blob 引用计数器：拒绝。跨状态转换或恢复时容易漂移；维护与清理在领取租约前按附件真源实时查询引用。
- 为维护提供公开管理 HTTP API：拒绝。M2-19 只使用定时租约、持久运行/问题、低基数日志和 Micrometer 指标，避免暴露内部 storage key。

## Consequences

Web 必须先创建意图再 PUT Blob，并按 1/2/5 秒退避轮询最长 5 分钟。PUT 结果未知时先读取 metadata；仍可上传便复用 attachmentId 重试，否则继续轮询，不能盲目创建第二意图。Work Item 附件随详情加载，Update 附件仅在用户展开对应讨论后加载并缓存；只读或已删除 owner 不显示上传入口。`AVAILABLE` 文件名直接链接同源下载端点；删除弹窗强制理由。409/412 只刷新真源，传输结果未知则刷新确认附件仍存在后才复用原键和理由重试。

备份恢复必须同时核对真实 attachment metadata、`attachment_blob`、配额、扫描任务与内容寻址文件，并在恢复后先完整对账、保持物理清理 dry-run。Feedback owner 的业务鉴权和 UI 仍由 M3B 负责；冻结枚举不表示已经支持创建。Range、在线预览、附件恢复，以及墓碑正式 blob 的保留期/legal hold/备份门禁清理由 M5-17 负责。
