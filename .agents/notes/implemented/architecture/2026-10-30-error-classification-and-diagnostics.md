# Agent Note: 错误分类与诊断信息

Status: implemented

## Problem

导入工作项「更新已有」提交失败时，用户只看到「请求字段校验失败」和请求 ID，运行日志里是一条 INFO 的 422，没有原因。追查发现三层问题：应用时钟精度（100ns）高于 PostgreSQL（微秒），同一请求先写字段、再迁移状态时，读回的 updated_at 可能比内存中的 now 晚，领域对象判定「时间倒退」；导入把提交阶段的领域异常包装成 422 VALIDATION_FAILED，并把英文异常消息放进 fieldErrors；界面不展示 fieldErrors，访问日志也不记录。

## Decision

- **时间精度**：Clock bean 按 1µs 截断（`Clock.tick`），与 timestamptz 一致，读回的时间不会晚于同一请求里的 now。领域与应用代码只用注入的 Clock 取时间。
- **分类**：422/INFO 只用于调用方能修正的输入问题。导入预览与提交共用规划器，输入在规划阶段已全部校验，所以提交阶段的 IllegalArgument/IllegalState 视为程序缺陷，抛出带文件行号的 IllegalStateException，走统一的 500：ERROR 日志、受控堆栈、requestId。fieldErrors.message 只放面向用户的固定中文说明，不放异常消息。
- **访问日志**：被拒绝的请求在同一条访问日志里记录 errorCode、reason、fieldErrors（「字段路径:错误码」，最多 10 项）和接口声明的 stage（导入为 PREVIEW/COMMIT）；Bean Validation 的 422 与 500 同样记录 errorCode。不记字段值、提示文本、请求正文或表格内容。
- **运行日志**：「失败请求」筛选在服务端按访问日志 status ≥ 400 过滤，实时、历史与直方图口径一致；详情按状态码与错误码给出失败分类，并展示阶段、原因与字段。
- **界面**：InlineProblem 逐条展示 fieldErrors，提供「查看详情」与「复制诊断信息」（时间、客户端、页面路径、状态、错误码、原因、字段、请求 ID）。调用方可传 fieldLabel 把字段路径换成用户能理解的位置；导入把 `rows[i]` 换成文件行号，并按失败分类说明处理方式，明确整批未保存。

## Alternatives considered

- **只在导入里换用新的 now 或在仓储写入前截断**：只修一个调用点，其他同请求多次写入的路径仍有同样风险。
- **保留 422、只改提示文案**：内部缺陷仍然没有堆栈，排查依旧只能靠复现。
- **在日志里记录字段值或请求正文**：违反[诊断数据访问边界](../security/2026-09-26-operations-diagnostics-access.md)；路径与错误码已足够定位。
- **前端按级别或事件组合筛失败请求**：4xx、403、5xx 与慢请求分散在不同级别和事件里，筛不全。

## Consequences

- 预览不写库，提交仍可能因并发变化失败（409/412），弹窗引导重新预览；内部错误会以 500 出现，用户凭「复制诊断信息」交给管理员。
- fieldErrors 现在对用户可见，后续新增的字段错误必须给出面向用户的说明。
- 单项创建与修改的领域校验仍归为 422（它们可能由用户输入触发），消息改为固定中文说明，不再区分具体字段。
