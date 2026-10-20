# CI 与 PR

从最新 `dev` 建独立分支，提交后向 `dev` 开 PR；热修复从 `main` 拉 `hotfix/<名称>` 向 `main` 开 PR。不直接推送或合并 `dev/main`。现有未提交改动需要单独保留。发布线规则见[运行手册第 1 节](../deployment/windows/RUNBOOK.md#1-github-release-自动发布)。

```powershell
git fetch origin dev
pnpm install --frozen-lockfile
pnpm run ci
```

日常快速反馈可先运行 `pnpm ci:static`；涉及后端时运行 `pnpm ci:backend`。`pnpm run ci` 按相同顺序执行全部本机可运行阶段，显式 `run` 避免与 pnpm 内置的清理安装命令 `ci` 冲突。需要 Node/pnpm 的仓库固定版本、Java 21 和 Linux-container Docker；Windows 交付阶段还需要 Windows x64、PowerShell 和 Electron。Linux 本地不会运行 Windows 阶段，PR 的 Windows Delivery 始终必需。

旧 `verify:m*` 命令保留切片复现用途；已退役的 `verify:m2-03/07/24` 及其 `:assets` 入口仅校验历史验收记录，不再读取产品实现。普通 PR 使用统一入口，不需要串联里程碑命令、手工修改验收报告或刷新历史哈希。

## 当前执行链

| 入口 | 保障 |
| --- | --- |
| `pnpm ci:contracts` | PR 基线、历史迁移与冻结清单、OpenAPI 严格兼容、例外结构、lint/样例、生成客户端漂移、全部既有事件版本与共享载荷兼容、事件库存 |
| `pnpm ci:static` | 上述契约检查各一次，加文档/归档完整性、校验器故障测试、前端/桌面 lint、架构、类型、构建与全部现有测试、历史证据结构和当前资产 |
| `pnpm ci:backend` | 一次 `clean verify`：单元/集成、安全、架构、PostgreSQL/Flyway、备份恢复；随后单独执行 96 MiB JVM 的附件流式探针 |
| `pnpm ci:portable` | 校验当前源码的 static/backend 成功记录与新鲜备份恢复报告；运行 packaged JAR + SPA HTTP；生成和验证 JAR/Web handoff |
| `pnpm ci:windows` | 复核 handoff，真实 Electron smoke、Windows 桌面安装包（EXE 图标/版本资源/fuse/ASAR 白名单，默认目录与 `/D=` 自定义目录的静默安装、自检、覆盖安装与卸载边界）、ZIP、PowerShell 行为及服务器包验证；服务器包逐文件对照同一 handoff；生成并核对后端与 Web 的 CycloneDX SBOM；把经过冒烟的安装包、blockmap、`latest.yml`、桌面 SBOM 与 manifest 收集到 `out/desktop` |

[plan.mjs](../tools/ci/plan.mjs) 是统一入口的执行清单。M2-23 的 `:assets` 检查当前切片资产，完整契约检查归 `ci:contracts`；类别资产只执行一次，M2-03、M2-07、M2-24 等旧切片由 [historical-assets.mjs](../tools/ci/historical-assets.mjs) 校验历史报告与验收矩阵。类型检查由各包实际构建或专用类型测试承担，不再重复运行同一整套 Node 入口。Windows smoke 使用 handoff 的 Web 字节；服务器不重复编译。

Windows 打包前的[配置隔离测试](../tools/verification/desktop-profile.test.mjs)验证卸载等待不会漏掉已分离的子孙进程，也不等待无关进程，并覆盖参数、退出码和超时终止。卸载助手先将挂起的根进程加入 Job Object，再恢复执行；完整进程树退出后才能恢复本机配置。失败与超时继续保留备份，具体边界见[桌面交付决策](../.agents/notes/implemented/process/2026-10-17-desktop-release-channel.md)。

[部署引擎行为测试](../deployment/windows/cd/Test-YumpooDeployment.ps1)使用含中文说明的无 BOM UTF-8 桌面更新描述，并模拟非 UTF-8 的默认读取编码，验证已有版本的校验与重发不依赖主机代码页，也不改写 Release 文件字节。

`out/ci/<stage>.json` 记录步骤、起止时间、状态、解析后的基线提交及源码指纹。中途失败立即非零退出；重跑会覆盖本阶段结果，不缓存跳过步骤。后续阶段拒绝旧源码、旧基线、不完整或失败报告。输出、构建目录和测试夹具不写入 Git。子进程清除继承的 `SPRING_*`、`YUMPOO_*` 和 JVM/Maven/Node 注入选项，再显式设置本次验证需要的值；不连接开发或生产数据库，也不继承受控身份 fixture。

## 四种事实来源

- **冻结历史**：归档 Agent Note 的 manifest、已在目标分支存在的 SQL 迁移、M2-23 冻结清单与既有 OpenAPI 精确例外不可改写。换行按仓库 LF 约定比较；迁移追加更高且唯一的版本，不能删除、移动或回填旧版本。
- **当前规范**：`contracts/openapi/yumpoo-v1.yaml`、事件目录与 Schema、生产代码和行为测试。历史验收的 Flyway 版本、旧 UI 尺寸、源码片段及 Note 措辞不定义当前行为。
- **兼容性基线**：PR 取 `pull_request.base.sha`（晋升 PR 即线上 `main`），`dev`/`main` push 取 `before`，本地默认取 `origin/dev`，手动 Actions 使用 `base_ref`。开始时解析为完整 commit，缺失、全零或无效引用失败；不复制永久规范基线。用 `YUMPOO_CI_BASE_REF` 可以显式指定本地比较基线。
- **例外**：已有条目只授权其原始 `oldSha256/newSha256` 对；原因、唯一 ID、活动决策链接必须完整。后续兼容演进不需要把当前规范连到旧例外。额外不兼容变更仍失败；真正破坏变更须先作明确产品/迁移决定，不能为过 CI 自动批准或刷新哈希。请求枚举缩减和响应枚举扩展恢复严格检测。

迁移测试从实际资源和 Flyway pending 清单推导数量、版本与执行集合，继续逐项核验表、索引、约束、已有数据、checksum 和重跑幂等性。事件只允许同版本增加可选字段（含嵌套对象）或修改 Schema 注释；既有约束、必需字段、引用、事件名与聚合语义保持兼容，旧合法样例必须可读。新版本不能使旧版本漏检。显式退役仅豁免只追加清单登记的精确版本；决策 Note 按原路径或同分类同名归档路径解析，当前目录、Schema、样例和后端 Java 源码均不得保留退役事件，冻结事件不可退役。

## Actions 与最终门禁

工作流在指向 `dev` 或 `main` 的 PR、`dev`/`main` 合并后和显式手动运行时执行；`main` 的推送 CI 产物是唯一可发布的服务器包。合并后的执行保留，用于验证实际集成提交；分支 push 不另起重复工作流。新一轮运行取消同一 PR/ref 的旧轮次。没有按路径跳过必需任务。

`M0 Portable Gate` 保持已有必需状态名。`Windows Delivery` 只在 portable 成功后运行。最终 `M0 Windows x64 Gate` 使用 `always()` 检查所有工作任务，逐项要求 `success` 和完成凭据；失败、取消、跳过、未知结果、缺失依赖/凭据均不能通过。工作流结构测试会拒绝新增任务未进入最终门禁、条件性跳过必需命令或 `continue-on-error`。

Actions 始终尝试上传故障诊断：`out/ci` 阶段记录和 Maven Surefire/Failsafe 报告。未产生诊断文件时只警告；交付所需的 handoff/报告缺失必须失败。handoff 名称绑定 run ID 与 attempt，Windows 校验提交、文件集合和哈希。SHA 固定的 Actions、只读 token 与不持久化 checkout 凭据继续保留。

Windows 本机不能构造的大小写碰撞与无特权符号链接夹具由必需 Linux job 执行。公司 HTTPS/企微、干净 Windows Server、真实生产迁移及灾备环境验收继续遵守现有 evidence 延期清单；CI 阶段报告仍标记 `WINDOWS_X64_CI_STAGE` 和相应 `NOT_RUN`，不代表生产部署或 `WINDOWS_X64_FULL`。

## 已核验的故障来源

| 实际记录 | 根因与替代保障 |
| --- | --- |
| [PR #64 迁移失败](https://github.com/E9uuleus/YumpooPlatform/actions/runs/34177447631)，修复 `053d417` | 新增 V52 后六处仍断言 V51/固定数量；改为实际迁移清单，保留数据语义与篡改拒绝测试 |
| [PR #64 兼容失败](https://github.com/E9uuleus/YumpooPlatform/actions/runs/34177214304)，修复 `ff5a099` | 精确例外需要同步新哈希；类别专项又强制当前规范连到旧历史基线，正常演进不断触发刷新；拆开历史授权与当前比较 |
| [PR #63 资产失败](https://github.com/E9uuleus/YumpooPlatform/actions/runs/33961259083)，修复 `4fe6b58` | 源码包含断言要求 `54px`；当前契约改用 YAML 结构，视觉/交互仍由现有前端回归负责 |
| [PR #63 数据残留](https://github.com/E9uuleus/YumpooPlatform/actions/runs/33960883440)，修复 `fb12a0c` | 类别版本外键残留污染后续测试；该已有修复保留，全量后端回归继续执行，新编排另隔离进程环境 |

## 发布线工作流

| 工作流 | 触发 | 职责 |
| --- | --- | --- |
| [Main Promotion Policy](workflows/main-promotion-policy.yml) | 指向 `main` 的 PR | 只允许 `dev`/`hotfix/*`；汇总提交、迁移分类与契约变化；`deployment/windows/cd/` 有改动时列出文件并提醒发布前升级主机引擎；破坏性迁移要求 `release:breaking-migration` 标签 |
| [Prepare Release](workflows/prepare-release.yml) | `main` 推送 CI 成功 | 自动编号并创建草稿 Release，草稿阶段上传并核验来源清单、原包与 SBOM，上传前后各核验一次构建证明；不删除任何草稿 |
| [Windows Release](workflows/windows-release.yml) | Release 发布 | 只校验不可变 Release（含 SBOM 与构建证明）并部署，不写附件 |
| [Server Rollback](workflows/server-rollback.yml) | 手动，在上一版本 tag 上 | 提交只换代码、不动数据库的回退请求 |
| [Back-merge Main](workflows/back-merge.yml) | `main` 或 `dev` 推送 | `main` 含 `dev` 缺少的改动（热修复）时开回合并 PR，或把已有回合并分支快进到最新 `main` |
| [Prepare Desktop Release](workflows/prepare-desktop-release.yml) | `main` 推送 CI 成功，或在 `dev` 上手动运行 | 两个任务均执行受保护工作流提交中的发布工具，目标提交的版本与公钥只按精确 SHA 读取为 JSON；草稿任务上传并核验桌面产物、来源清单与 `release.json`；签名任务重新核对 CI attempt 和安装包构建证明后，在 `desktop-signing` 中签发 Ed25519 更新授权 |
| [Desktop Release](workflows/desktop-release.yml) | `desktop-v*` Release 发布 | 只读校验不可变 Release、构建证明与授权签名后，以 `desktop-publish` 请求写入主机更新源；独立并发组 |

这些工作流的结构与权限由 [release-workflows.test.mjs](../tools/ci/release-workflows.test.mjs) 解析 YAML 校验，发布脚本自身的回归由 `tools/release/*.test.mjs` 覆盖，二者都在 `ci:static` 中执行。

## 依赖与供应链

决策与取舍见[供应链基线](../.agents/notes/implemented/process/2026-10-16-supply-chain-baseline.md)和[仅自动提出安全更新](../.agents/notes/implemented/process/2026-10-18-dependabot-security-only.md)。

| 机制 | 触发 | 职责 |
| --- | --- | --- |
| [Dependabot](dependabot.yml) | 已知漏洞触发安全更新 | github-actions、npm（根 workspace）、Maven（`/backend`）只自动提出安全修复，指向默认分支 `dev`；小版本与补丁分组，必要的大版本修复单独 PR |
| [Supply Chain](workflows/supply-chain.yml) `Dependency Review` | 指向 `dev`/`main` 的 PR | 拒绝新引入的 moderate 及以上已知漏洞（runtime、development、unknown） |
| `Workflow Audit` | PR 与每周一 | 固定版本 zizmor 审计工作流与 Dependabot 配置；有发现即失败 |
| `OSV Scan` | 每周一 | 分别扫描 `dev` 与 `main` 的锁文件和 Maven 依赖 |
| `Release Rehearsal` | 每周一 | 运行 `main` 自身的发布工具做只读演练：已发布的头部按部署规则重新核验，未发布的头部执行全部草稿校验但不创建草稿；桌面更新源上的 `latest.yml`、`release.json` 必须与最新已发布桌面 Release 一致 |
| 构建证明 | `dev`/`main` 推送 CI | `Windows Delivery` 分两组签署 SLSA provenance：服务器 ZIP 与两份 SBOM；`out/desktop` 下的安装包、blockmap、`latest.yml`、桌面 SBOM 与 manifest。只有该任务有 `id-token`/`attestations` 写权限，PR 运行不签署 |
| Maven enforcer | `ci:backend` | `requireJavaVersion [21,)`、`requireReleaseDeps` |

本地复现：`uvx zizmor@1.30.1 .` 审计工作流；`pnpm sbom --sbom-format cyclonedx --filter @yumpoo/web-app --prod --lockfile-only` 查看 Web SBOM；`./mvnw generate-resources` 后在 `backend/target/classes/META-INF/sbom/` 查看后端 SBOM。

Dependabot 的 `open-pull-requests-limit: 0` 关闭常规版本更新；配置中保留的每周 schedule 是必填字段，不控制安全更新时效。安全更新仍需在仓库设置中启用，且不受版本更新数量限制或冷却期影响。常规升级、运行时结束支持或工具链迁移由维护任务按实际需要发起，不自动追到最新版。

Dependabot 生成的 PR 标题不带步骤编号，squash 合并时按提交规范补齐。工作流审计的确有理由的例外，用行内 `zizmor: ignore[...]` 注释并写明理由。每周检查失败时，若配置了 `WECOM_RELEASE_WEBHOOK` 会推送企业微信。

## 远端规则

2026-09-08 已读取规则集 `Protect dev`（ID `20779725`）：required checks 为 `M0 Portable Gate`、`M0 Windows x64 Gate`，要求分支最新、PR 对话解决，允许 PR 路径的维护者 bypass。两个状态名保留，因此启用本变更不需要切换远端 required checks。

远端设置没有在本变更中修改。当前 `required_approving_review_count=0`，无法强制至少一位其他评审者批准；如需技术上强制该要求，维护者应在上述规则集设为 `1`，并核对角色 `5` 的 PR bypass 是否符合团队约定。不能把文档中的“须审阅”当作已启用的远端限制。

`main` 发布线依赖以下远端设置，由维护者在切换时配置。仓库代码不会、也不能自行修改这些设置：

- **默认分支**：设为 `dev`。日常 PR、Dependabot 和定时任务都落在 `dev`；`workflow_run` 与 `workflow_dispatch` 使用 `dev` 上的工作流定义。
- **规则集 `Protect main`**：
  - 禁止删除和强推；
  - 必须经过 PR，只允许合并提交；
  - required checks 为 `M0 Portable Gate`、`M0 Windows x64 Gate`、`Main Promotion Policy`；
  - 不要求分支最新，原因是晋升 PR 的 head 是持续前进的 `dev`。
- **规则集 `Release tags`**：`refs/tags/server-v*` 与 `refs/tags/desktop-v*` 禁止更新和删除，只有管理员可以创建。
- **桌面发布渠道**：新建 Environment `desktop-signing`，部署分支只允许 `dev`（`workflow_run` 运行在默认分支），添加 secret `DESKTOP_UPDATE_SIGNING_KEY`；`wecom-dev` 的部署 tag 规则加入 `desktop-v*`。Environment 在被工作流首次引用时会自动创建且不带保护规则，所以要在第一次桌面版本递增之前配置好。
- **Immutable Releases 与 secret**：开启 Immutable Releases；添加仓库 secret `WECOM_RELEASE_WEBHOOK`。
- **供应链**：`Protect dev` 的 required checks 增加 `Dependency Review`、`Workflow Audit`；开启 Dependabot alerts 与 security updates；开启 CodeQL default setup（java-kotlin、javascript-typescript、actions）。
