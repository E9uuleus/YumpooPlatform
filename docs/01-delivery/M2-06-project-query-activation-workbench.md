# M2-06 Project 查询、激活与工作台

M2-06 交付 Project 权限过滤列表、完整详情、Owner 配置 PATCH、DRAFT 激活、Workspace 可见计数以及 Vue 项目工作台。OpenAPI 是 HTTP 唯一契约源，Web 只使用重新生成的 TypeScript SDK。

## 权限与并发

普通用户仅能查询具有 ACTIVE membership 的 Project；COMPANY_ADMIN 可查询同企业全部 Project。权限、筛选和分页总数在数据库使用同一谓词，默认生命周期为 DRAFT+ACTIVE。详情与写命令使用强 ETag；PATCH 无变化时不增版本、不发事件，412 后客户端刷新服务端版本但保留用户草稿。

能力摘要只优化界面展示。Owner 可更新设置与激活，Owner/管理员可治理成员，只有管理员可重指派负责人；所有命令仍在服务端重新鉴权。

## 激活前置

激活事务锁定 Project 并重验版本和当前 Owner。Owner 必须是本企业 ACTIVE+ENABLED 用户且保有 ACTIVE membership；固化模板版本必须存在且为 PUBLISHED 或 RETIRED；至少一个 ACTIVE Content 必须匹配模板 provenance。PRE_SALES、IMPLEMENTATION、HYPERCARE 还必须填写客户名称。

Owner、模板和 Content 阻断分别使用 `OWNER_MISSING`、`TEMPLATE_UNAVAILABLE`、`ACTIVE_CONTENT_MISSING`。事件和 Security Audit 只记录生命周期、负责人、模板、版本及变更字段名，不复制描述、客户或联系备注。

## 范围边界

Product–Project 关系、Project 归档/恢复与 Workspace 移动、Content CRUD/View Config、Activity 投影分别留给 M2-07、M2-08、M2-09 和 M2-20。
