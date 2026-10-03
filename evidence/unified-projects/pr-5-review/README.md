# PR-5 复审修正验收

本目录记录 PR-5 复审修正，保留此前 [PR-5 验收证据](../pr-5/README.md)。用户电脑上的可见 Chromium 加载正式 `ProjectWorkItems` 与 `ConnectFilterSection`，使用真实 Vue Router web history 和静态 API 方法响应。验收夹具位于本机临时目录，不修改产品页面或只读设计包；截图不代表部署验收。

## 浏览器证据

| 场景 | Light | Dark | Night |
| --- | --- | --- | --- |
| A 已选时仍可选 B；完整分页后按名称显示；A+B 两条独立事项均出现 | [截图](union-light.jpg) | [截图](union-dark.jpg) | [截图](union-night.jpg) |
| 未知列和零计数来源保持选中，分别取消且保留其他搜索条件 | [截图](removable-light.jpg) | [截图](removable-dark.jpg) | [截图](removable-night.jpg) |

[选项加载失败](options-failure-light.jpg)：已选条件保持生效，来源显示“待确认”及未知计数，不推断为删除。

[机器记录](browser-checks.json)共 7 张截图、10 项检查、0 个页面异常、0 个意外后端请求。三个主题均通过浏览器原生后退/前进，在已访问的 A+B 与 A URL 间恢复选择；沿用产品原有的筛选 `router.replace` 行为，不新增每次勾选的历史记录。

入站选项夹具按 UUID 分两页返回 B、A；浏览器断言两页均未携带本分面的 `incomingProjectIds`，最终展示为 Alpha、Beta。其他筛选上下文仍保留，列表请求仍携带 A+B。真实 SQL 并集、计数及 UUID 游标行为由后端集成测试覆盖。

## 回归依据

- `ConnectFilterSection.spec.ts`：名称自然排序、同名 ID 稳定排序、输入不变；缺失选项保留和取消；等待/失败/恢复状态；20 项上限仍允许取消。
- `ProjectOverviewView.spec.ts`：入站分面仅排除自身条件、完整分页；保留其他筛选；路由恢复；缺失条件取消；加载失败及过期响应丢弃。
- `ConnectionFilterIT.incomingProjectsFormAUnionAndOptionPagingKeepsItsUuidOrder`：两来源并集扩大结果，选项 UUID 分页顺序及游标稳定。
- `ConnectionHttpIT.connectionFilterArraysAcceptTwentyAndRejectTwentyOneBeforeDeduplication`：三数组 × 列表/选项两个接口；20 项接受，21 个不同或重复 ID 均返回 422 及对应字段错误。
- 既有 `NotificationHttpIT` 覆盖 PROJECT 分组、计数、全部已读及跨用户隔离，验证通知筛选 switch 合并后的行为保持一致。

本轮定向 Web 测试 83 项通过、类型检查通过；上述两个连接集成测试类各 6 项通过，通知投影与 HTTP 测试类各 9 项通过。完整本地门禁及远端精确提交的最终结果记录在 PR 描述，避免将前一提交的结果当作本轮结果。

## 约束

保留 SQL EXISTS / NOT EXISTS、空结果语义、权限、看板只读及通知 ID 载荷；不自动清理失效 URL。OpenAPI 数组上限为 20，通知的既有兼容例外未改写，仅追加当前规范哈希的精确条目。API 仍以 UUID 排序分页，名称排序仅作用于加载完成后的显示副本。未修改已交付迁移、关系服务、冻结事件、归档 Note 或原证据，也未改写提交历史。
