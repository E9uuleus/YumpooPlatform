# Agent Note: 依赖与供应链基线

Status: implemented

## Problem

[main 发布线](2026-10-15-main-release-line.md)解决了「发布哪一份字节」：发布包绑定 `main` 推送 CI 的 run、attempt 和摘要。但仍有四个缺口：

- 依赖只在开发者手动升级时更新，已知漏洞没有任何提醒；
- 工作流本身没有静态审计，权限或注入回归只能靠结构测试里列举的规则发现；
- 发布包里有哪些第三方组件，没有机器可读的清单；
- 「这份 ZIP 由本仓库 CI 构建」只能通过 GitHub Actions 产物 API 间接证明，脱离该 API（例如管理员在主机上核对下载的 ZIP）就无法验证。

## Decision

**依赖更新**：[Dependabot](../../../../.github/dependabot.yml) 覆盖 github-actions、npm（根 workspace）和 Maven（`/backend`）。

- 目标分支为 `dev`，每周一次，小版本与补丁按生态分组，大版本单独开 PR；
- 冷却期 7 天，避开刚发布就被撤回或投毒的版本。安全更新不受冷却期限制；
- 依赖 PR 和普通 PR 走同一套门禁，随下一次晋升发布。

**PR 检查**：[Supply Chain](../../../../.github/workflows/supply-chain.yml) 中有两个任务是 `dev` 的 required checks：

- `Dependency Review`：拒绝新引入的 moderate 及以上已知漏洞，runtime、development、unknown 三类依赖都检查；不在 PR 上写评论，因此不需要写权限；
- `Workflow Audit`：用固定版本的 zizmor 审计工作流和 Dependabot 配置，发现问题即失败，不上传 SARIF，因此不需要 `security-events: write`。确有理由的例外用行内 `zizmor: ignore[...]` 注释并写明理由，目前只有 Prepare Release 的 `workflow_run`。

**每周定时**：同一工作流每周一运行：

- `OSV Scan` 分别扫描 `dev`（下一次发布的内容）和 `main`（线上代码）；
- `Workflow Audit` 重跑一次，以发现新公布的有漏洞 Action；
- `Release Rehearsal` 只读演练发布链：检出 `main` 自身的发布工具，若 `main` 头部已发布，就按部署时的规则重新核验该 Release；若未发布，就执行草稿生成的全部校验，只预测版本号，不创建草稿；
- 任一失败时尽力推送企业微信。

**CodeQL**：使用仓库设置中的 default setup，覆盖 java-kotlin、javascript-typescript、actions。由维护者在设置中开启，仓库不保存 CodeQL 工作流。

**SBOM**：发布包附带两份 CycloneDX SBOM，作为 Release 附件。

- `yumpoo-server.cdx.json`：由 Spring Boot 父 POM 预配置的 cyclonedx-maven-plugin 在构建时生成，打入 JAR 的 `META-INF/sbom`；[打包脚本](../../../../tools/verification/package-m1-15-win.mjs)从已验证 handoff 的 JAR 中取出，[包校验](../../../../tools/verification/verify-m1-15-package.mjs)再与 ZIP 内 JAR 的内嵌清单比对；
- `yumpoo-web.cdx.json`：`pnpm sbom --prod --lockfile-only` 列出 `@yumpoo/web-app` 打包进 dist 的生产依赖；
- 两份 SBOM 的名称、大小和 SHA-256 记录在 `artifact-manifest.json`（schemaVersion 2），由[发布策略](../../../../tools/release/policy.mjs)与 ZIP 一起核验。

**构建证明**：[Platform CI](../../../../.github/workflows/m0-18-ci.yml) 的 `Windows Delivery` 在推送运行中用 `actions/attest-build-provenance` 为 ZIP 和两份 SBOM 签署 SLSA provenance。

- 只有这个任务获得 `id-token: write` 和 `attestations: write`；PR 运行（含 fork 和 Dependabot）不签署；
- [attestation.mjs](../../../../tools/release/attestation.mjs) 调用 `gh attestation verify`，限定签名工作流、`refs/heads/main`、源提交和 GitHub 托管 runner，再要求证明的 `invocationId` 等于 `provenance.json` 钉住的同一次 CI attempt；
- Prepare Release 在上传附件前、附件持久化后各核验一次；Windows Release、Server Rollback 和发布演练也在部署或演练前核验。这些任务只有 `attestations: read`。

**Maven enforcer**：`requireJavaVersion [21,)` 与 `requireReleaseDeps` 在 `validate` 阶段执行，后者拒绝 SNAPSHOT 依赖。

## Alternatives considered

- **Renovate 代替 Dependabot**：规则更灵活，但需要安装第三方 App 并授予写权限；Dependabot 是仓库内建能力，分组和冷却期已经够用。
- **只在 `main` 上签署构建证明**：PR 与 `dev` 合并永远不会执行签署步骤，第一次真正执行要等到晋升之后，签署失败会直接卡住发布。改为所有推送 CI 都签署，`dev` 合并就能验证签署链；验证侧只接受 `main`。
- **通过 Advanced Security 上传 zizmor 与 OSV 的 SARIF**：结果进入安全面板，但需要 `security-events: write`，且发现不会让 PR 检查失败。改为直接失败，并在日志中给出注解。
- **在发布草稿阶段重新生成 SBOM**：不必改动 CI 产物，但 SBOM 不再出自被测试的同一次构建，违背「只构建一次」。
- **新增 `provenance.json` schemaVersion 2 来携带 SBOM 和证明信息**：主机上的部署引擎会读取该文件，改动需要管理员升级引擎才能生效。SBOM 摘要改为记录在主机不读取的 `artifact-manifest.json` 中。
- **为旧格式 Release 保留兼容分支**：不需要。部署和回退都在目标 tag 上运行，校验代码始终来自 Release 自己的提交，旧版本由旧代码核验。

## Consequences

- Release 附件从 4 个变为 6 个；部署、回退和发布演练都要求 SBOM 齐全且证明有效，缺一即拒绝。主机上的部署引擎不变，无需升级。
- 签署依赖 Sigstore 公共实例：证明进入公开透明日志，包含仓库、工作流、提交和 run 编号。仓库是公开的，这不构成额外泄露。管理员可以在主机上用 `gh attestation verify` 独立核对下载的 ZIP。
- `pnpm sbom` 不列出 workspace 内部包（如 `@yumpoo/api-client`），它们属于第一方代码，且没有运行时依赖。Web SBOM 描述的是锁文件中的生产依赖，不是对 dist 的逐文件扫描。
- 依赖 PR 的标题由 Dependabot 生成，不带步骤编号；squash 合并时需按提交规范补齐。
- Dependabot 对 pnpm 11 锁文件的支持需要在首次运行后从 Dependabot 日志确认；不支持时 npm 更新会在日志中报错，不会生成错误的 PR。
- 发布演练只能看到已发布的 Release，草稿对只读令牌不可见。若 `main` 头部长期未发布，CI 产物过期（30 天）后演练会失败，这正是「草稿已无法重新生成」的信号。
- `Dependency Review`、`Workflow Audit` 需要维护者加入 `Protect dev` 的 required checks 才成为强制门禁；CodeQL default setup 和 Dependabot security updates 也属于仓库设置。
