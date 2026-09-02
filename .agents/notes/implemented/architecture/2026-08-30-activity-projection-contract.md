# Agent Note: Activity 追加投影与当前权限查询

Status: implemented

## Problem

Project 与 Work Item 需要统一动态时间线，但业务模块不能在写事务中直接维护读模型，`audit` 也不能反向依赖 catalog、identityaccess 或 workitem。原始 Outbox 载荷还包含正文、理由、客户字段和内部标识，不能直接作为动态查询源或展示模型。上线时既有 Outbox 已处于多种状态，若隐式回放会让历史边界和重复语义不可解释。

## Decision

V44 建立 append-only `activity_event` 和单行 `activity_projection_state`。Activity V1 只接收迁移切点后的显式 v1 事件；切点前后来被领取的 `PENDING/RETRY` 只完成 receipt，不生成投影，既有 `COMPLETED/DEAD` 不重排、不回填。查询返回 `historyStartedAt` 公开这条边界。

`audit` 拥有事件白名单、模板码、安全参数映射、追加仓储、游标和摘要渲染。受现有层级规则约束，Outbox 消费映射放在 `audit.api`，通过同层上下文端口取得投影时刻的成员显示名和事项安全引用；`administration` 仅组合 identityaccess 与 workitem 的公开查询端口。`audit.application` 不反向依赖 API DTO，`audit.infrastructure` 不依赖公共枚举。

每条投影保存不可变行为人显示名快照、事实时间、实体引用、最多两个同范围 Work Item 关联、模板码和白名单 JSON。正文、删除或治理理由、客户字段、哈希、Update 内容长度以及跨 Project 的另一端标识均不保存。Project 与 Work Item HTTP 查询先按当前访问关系验证，移除成员和仅 APP_MANAGER 身份得到 404；归档 Project 和软删除 Work Item 的已投影历史保持可读。

游标绑定 Company、Project、可选 Work Item 和完整筛选指纹，锚点为 `(occurred_at, id)`；响应不返回总数。Product 生命周期也写入 `PRODUCT` 范围，但 M2-20 不开放 Product Activity HTTP 或页面。

工作项详情的字段审计使用独立 `WORK_ITEM_CELL_ACTIVITY_V1` 追加投影，不改变上述综合 Activity V1。V46 建立自己的迁移切点和一事件多字段拆行幂等键；V46 已执行后保持冻结，V47 仅以追加迁移移除 facet 索引中的 Content 键。投影只记录创建名称、名称、处理人、状态、优先级和截止日期，明确排除描述、备注、计划日期、排序、删除恢复、讨论、关系、附件和派生更新时间。Content 只保存事件发生时的类别 ID/显示名快照，不作为查询筛选或统计维度，不产生伪造的类别变更，也不引入跨 Content 移动能力。字段值是白名单结构快照：状态/优先级保存代码、显示名和颜色，成员保存 ID 与当时显示名，自然日保存日期文本。

`GET /work-items/{id}/cell-activity` 继续先按当前 Project 可见性鉴权，允许可见 Project 下的软删除事项读取历史。它按企业时区与周起始日解释今天、昨天、本周、本月和今年；同维度 OR、跨维度 AND。游标除筛选指纹和 `(occurred_at,id)` 外还绑定首次请求时间锚点，避免跨午夜继续分页时范围漂移。筛选计数基于完整专用投影，每个维度忽略自身筛选、保留其他维度，不能由前端已加载页推算。

## Alternatives considered

- 在各业务事务中同步写 Activity：拒绝，因为会复制映射、安全裁剪与幂等逻辑，并让业务模块持有审计读模型。
- 查询时直接扫描 Outbox：拒绝，因为 Outbox 可清理，载荷不是展示契约，而且会扩大敏感字段和权限侧信道。
- 上线时回填全部旧事件：拒绝，因为已完成、死亡和旧版本事件无法可靠重建行为人快照与去重语义；采用明确切点更可验证。
- 让 `audit.application` 直接调用其他模块或其自身 API 端口：拒绝，因为违反模块依赖矩阵和同模块单向层级。
- 为 Product 同步开放页面：拒绝，Product 投影先稳定存储，公开面留给后续里程碑。
- 把单元格拆行塞进既有 `activity_event`：拒绝，既有一事件一范围唯一约束和综合摘要语义不能安全表达多字段前后值；独立投影可保持旧 API 与项目动态兼容。
- 回放旧 Outbox 推断字段旧值：拒绝，旧 fields-changed v1 没有标题、优先级与截止日的可靠前值；采用新切点并由生产者兼容增加可选前值。

## Consequences

Activity 的历史从 V44 切点开始，产品界面必须持续显示未回填提示。显示名是投影时快照，不随目录重命名变化；访问权仍在每次读取时重新判断。新增可展示事件必须显式加入订阅、模板和参数白名单并补契约测试，不能把整个 payload 复制进投影。跨 Project 关系会为两端各写一条范围记录，但每条只携带本范围事项。`activity_event` 不外键关联 Outbox，因此 Outbox 清理不会破坏时间线；Activity 自身保留与清理政策需在后续运维里程碑单独决策。

工作项详情必须持续展示 V46 的准确切点并区分无历史与筛选无结果。标签或成员后续改名不会重写既有单元格动态；筛选只按企业日历范围、操作成员 ID 和字段列进行。新增工作项字段若要进入详情动态，必须同时补生产者前值、专用投影白名单、结构化值契约、筛选枚举和展示测试，不能默认继承综合 Activity。
