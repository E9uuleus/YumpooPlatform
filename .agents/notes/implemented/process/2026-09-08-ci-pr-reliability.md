# Agent Note: CI 事实边界、统一入口与失败关闭的 PR 门禁

Status: implemented

## Problem

已关闭 PR 的日志与修复提交证明，正常产品迭代多次触发验收噪声：PR #64 新增 V51/V52 后补改固定迁移数量和目标版本，`ff5a099` 又同步整份 OpenAPI 哈希；PR #63 因旧 `54px` 源码断言失败，并由 `fb12a0c` 修复类别版本外键清理。实际记录和运行链接见 [CI 与 PR](../../../../.github/CI.md#已核验的故障来源)。

原 CI 经 M1-13 → M0-18 → M0-17 → M0-14 → M0-13 → M0-12 嵌套到全量回归；OpenAPI/codegen 重复执行，M2-23/M2-24 重复事件兼容测试，多个旧切片反复检查同一类别实现。Windows 还重新编译已经通过 Linux 验证的服务端。源码片段、固定阶段版本和当前规范哈希承担了本应由语义回归、基线和历史完整性分别负责的职责。

## Decision

`tools/ci/plan.mjs` 与 `run.mjs` 提供平铺的 `ci:contracts/static/backend/portable/windows` 和统一 `pnpm ci` 入口。昂贵能力各执行一次，后端 `clean verify` 保留全部业务、安全、架构、数据库迁移和备份恢复集成测试，96 MiB 堆的附件探针另行执行。当前资产、历史证据结构、事件兼容与校验器测试分工明确；M2-23/M2-24 的 assets 不再隐式运行整套工具链。旧切片入口仍作历史复现，普通 PR 不依赖嵌套命令。

历史 SQL、M2-23 冻结清单及既有 OpenAPI 精确例外对照 PR 基线不可改写，归档 Note 继续由原 manifest 门禁保护。当前 OpenAPI 和完整事件目录是现行规范，兼容基线从目标提交临时提取。已有例外仅匹配原始精确哈希对，不授权后续规范；类别专项不再要求每次正常迭代追加“旧历史 → 当前”的哈希链。恢复请求枚举缩减和响应枚举扩展检测，没有增加或批准例外。本决策部分替代[类别重构闸门](2026-09-02-content-category-openapi-breaking-change.md)的当前哈希维护要求，保留其精确授权与审计理由。

事件比较覆盖全部当前登记版本及共享载荷。既有版本只允许增加可选字段、嵌套可选对象字段或变更 Schema 注释；聚合语义、必需字段、原有约束、引用和历史合法样例继续保护。JSON Schema 的业务属性即使名为 `description` 也不是可忽略的注释。M2-23 的 14 项清单仍是冻结历史，新增版本在当前目录登记；本决策补充[事件冻结决定](../data/2026-08-31-work-item-event-contract-freeze.md)。

迁移测试从真实资源和 Flyway pending 清单推导期望版本、执行数量和执行集合，保留具体数据、索引、约束、V46 checksum 及重复 migrate 的断言；增加未来迁移夹具，修改已应用 SQL 仍由 checksum 和 Git 历史检查拒绝。类别专项读取结构化 OpenAPI 与事件契约，移除旧 CSS 尺寸、源码符号和文档措辞匹配，原有前端/后端行为回归继续运行。

验证子进程清除继承的应用配置、认证 fixture 和 JVM/Maven/Node 注入选项，再显式注入当前验证参数。阶段报告记录源码指纹、基线提交及逐步完成状态；后续交付拒绝旧源码、旧基线和缺失步骤。Windows 在校验 handoff 后对同一 JAR/Web 字节执行 smoke 和组包，服务器包再次逐文件对照 handoff。

保留远端 required check 名称 `M0 Portable Gate` 与 `M0 Windows x64 Gate`。实际 Windows 工作任务为 `Windows Delivery`；最终门禁使用 `always()`，要求每个工作任务为 success 且有完成凭据。失败、取消、意外 skipped、未知或缺失结果都不能成功；没有路径过滤。PR/ref 新轮次取消旧轮次，保留合入 dev 后对实际集成提交的验证。无论成功与否都尝试上传阶段记录和 Maven 诊断；必要交付物缺失仍失败。

## Alternatives considered

- 持续更新历史版本数字、UI 片段和整份规范哈希：把本来可兼容的产品变化变成审批噪声，且容易掩盖真实破坏，拒绝。
- 关闭兼容检查、宽泛忽略枚举变化或按路径跳过大部分测试：会削弱已有消费者、安全或 Windows 保障，拒绝；未新增按需跳过规则。
- 删除全部里程碑校验：其中仍有备份、产物白名单、敏感信息排除及环境延期清单的独特保障；仅移除已证实的重复编排和脆弱断言。
- 提交永久 OpenAPI/事件快照并维护多套基线：PR 目标提交已是可信比较对象，复制只增加漂移，拒绝。
- 将 Windows 构建结果当成与 Linux 已测包等价：重新构建的字节没有同一测试证明，改为验证并复用 handoff。
- 改名所有 required checks 并同步改远端设置：会制造配置切换窗口；保留两个既有名字，用最终状态承载完整依赖判断。

## Consequences

正常 PR 的最少步骤是基于最新 dev 开分支、安装锁定依赖、运行统一入口并向 dev 开 PR。契约破坏、已交付迁移改写、新版本遗漏旧版本、生成客户端漂移与安全回归仍必须修复；不能用刷新历史证据消除失败。源代码改变后不能复用旧阶段报告，失败定位使用明确的阶段/步骤和 Actions 诊断 artifact。

CI 不代表部署：公司 HTTPS、真实企微、Defender、干净 Windows Server、生产数据迁移和灾备环境验收仍保留原 evidence 中的 NOT_RUN/延期状态，CI 报告保持 WINDOWS_X64_CI_STAGE。本机 Windows 的大小写碰撞与符号链接夹具由必需 Linux job 执行。实际远端执行和本地验证结果随 PR 提供，不伪造历史 PASS。

远端规则集 Protect dev（20779725）已读取，未修改；现有两个必需状态继续有效。其 required_approving_review_count 仍为 0，至少一位其他评审者批准并未由远端强制；准确配置建议与现有 bypass 记录在 CI 文档中。
