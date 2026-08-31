# Agent Note: Product–Project 关系契约

Status: implemented

## Problem

Product 与 Project 的关系同时影响项目导航、Product 可见范围、后续 Feedback 来源校验和 Project 归档并发。若把关系塞入任一主聚合版本，会让普通关联操作无意义地冲突；若只在页面拼接关系，权限分页、总数和后续跨模块校验会产生不同事实。

## Decision

Product–Project 关系由 Catalog 拥有的 `project_product_link` 小聚合保存。每条关系有独立 ID、强 ETag、审计字段和软移除事实；有效的 project/product/type 三元组唯一，同一 Product 可用不同类型重复关联。移除后重新关联创建新 ID，不复活历史行，也不增加 Project `rowVersion`。

每个 Project 最多一个有效主关系，但允许没有主关系。切换主 Product 是两个显式命令：先取消旧主，再设置新主；服务端不会自动提升其他关系。所有写入先锁 Project 行，使关系写入和 M2-08 归档守卫共享串行化边界，再对相关 Product 按 UUID 排序获取共享锁并在锁内复核 ACTIVE。Owner 只能在 DRAFT/ACTIVE Project 写关系；成员与非成员 CompanyAdmin 只读。建立关系要求 Product 当前 ACTIVE，解绑历史关系不受 Product 后续归档影响。Product 归档只排他锁 Product 后读取聚合计数，不反向锁 Project；由此关系先提交必被 blocker 看见，归档先提交则关系等待后因 ARCHIVED 失败。

Product 读取范围包含 CompanyAdmin、ProductOwner 和任一关联 Project 的 ACTIVE member；列表、总数和详情使用同一 SQL `EXISTS` 谓词。Product 更新仍显式要求 CompanyAdmin 或 ProductOwner。Project 列表的 `productId` 过滤同样在权限 SQL 内参与计数和稳定分页。

Catalog 发布只读 `ProductProjectRelationQuery`：调用者显式传入关系类型校验现有关联合法性，按 Product 统计 ACTIVE 研发/支持 Project，并可按 Project 查询有效关联 Product ID。M3B 必须复用该端口，不得读取 Catalog 内部表。M2-07/M2-24 不创建 Feedback 表、空 blocker 或虚假引用；真实 Feedback 对解绑和 Product 归档的阻断由 M3B-11 建立。

## Alternatives considered

- 将 Product ID 或关系列表放进 Project 聚合：拒绝，会扩大 Project 并发冲突并耦合归档版本。
- 移除后复活原关系行：拒绝，会覆盖历史审计边界并让幂等与重新关联含义混淆。
- 自动把第二条关系提升为主关系：拒绝，主 Product 是显式业务选择，不能由排序副作用决定。
- 让 CompanyAdmin 代替 Owner 写关系：拒绝，治理读取不等于日常业务所有权。
- 在 M2-07 预建 Feedback blocker：拒绝，在没有 Feedback 真源时无法形成可验证的引用完整性。

## Consequences

关系消费者必须使用有效关系而非历史行，且不能把 `ProjectCapabilities` 当作服务端授权凭据。M2-08 归档写入锁同一 Project 行；M2-24 已冻结 Project→Product 锁序和真实活动项目 Product blocker。M3B 必须通过公开查询端口选择允许类型，并在 Feedback 真源存在后于 M3B-11 冻结解绑及归档 blocker。事件载荷只携带稳定 ID、关系类型与主标记变化，不复制 Product 名称或移除理由正文。
