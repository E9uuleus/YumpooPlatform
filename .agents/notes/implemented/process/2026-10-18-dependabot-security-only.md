# Agent Note: Dependabot 仅自动提出安全更新

Status: implemented

## Problem

[供应链基线](2026-10-16-supply-chain-baseline.md)同时启用了常规版本更新和安全更新。常规更新会仅因新版存在就生成 PR，包括 TypeScript 工具链迁移和与当前 Node.js 运行时不匹配的类型声明升级，增加评审与回归成本。用户要求只合并确有必要的依赖更新，需要保留漏洞修复能力，同时停止自动追新。

## Decision

本 Note 部分取代供应链基线中的自动依赖更新策略；原 Note 继续拥有漏洞门禁、定时扫描、SBOM、构建证明和发布验证。

- [Dependabot 配置](../../../../.github/dependabot.yml)继续覆盖 GitHub Actions、根 npm workspace 和 `/backend` Maven；每个生态设置 `open-pull-requests-limit: 0`，关闭常规版本更新 PR。GitHub 的安全更新具有独立数量限制，仍在仓库设置中启用。
- 不设置 `target-branch`：安全更新固定使用默认分支，省略该字段让同一配置中的安全分组等选项生效。仓库默认分支必须保持 `dev`，安全修复经 PR 门禁合入后随正常发布交付。
- 分组显式设置 `applies-to: security-updates`。小版本和补丁按生态及 npm 生产/开发依赖分组；需要跨大版本才能修复的漏洞继续生成独立 PR，不用全局 `ignore` 或依赖白名单屏蔽它们。
- 每周 `schedule` 仅保留为配置必填字段；7 天 `cooldown` 保留以满足工作流审计，并在恢复常规版本更新时继续提供冷却保护。安全更新由漏洞触发，不等待该周期，也不适用常规版本冷却期。
- 合并安全更新前确认受影响依赖、修复范围及兼容性，避免锁文件中的无关升级或降级。CI 通过是必要条件，不能单凭版本号较新决定合并。
- 常规升级、运行时结束支持、兼容性修复或工具链迁移按实际需要手动发起。Node.js 类型声明匹配实际运行时，不单独追到最新大版本。

选项语义以 GitHub 的 [Dependabot options reference](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference) 为依据；结构回归由 [release-workflows.test.mjs](../../../../tools/ci/release-workflows.test.mjs) 覆盖。

## Alternatives considered

- **保留每周常规更新和 7 天冷却**：冷却能避开刚发布就被撤回或投毒的版本，但仍会自动产生缺少实际需求的升级 PR，未满足只处理必要更新的维护偏好。
- **只禁止常规大版本更新**：小版本和补丁也不一定需要立即采用；对所有依赖增加忽略规则还可能阻止跨版本安全修复。
- **删除配置或关闭安全更新**：会失去安全 PR 的配置入口或自动修复能力。数量上限设为零只关闭常规版本更新，保留安全通道。
- **自动合并所有安全 PR**：漏洞修复仍可能改变 API、运行时要求或锁文件，因此继续经过代码评审和完整 CI。

## Consequences

- 常规版本升级不再自动占用 PR 队列；安全更新仍可能因已知漏洞产生多个 PR，需要按实际风险评审。
- 安全更新只覆盖 GitHub 能识别且存在可用修复方案的漏洞；保持某一运行时受支持、评估未登记的安全加固或处理自动修复失败仍是人工维护责任。
- 本策略不会消除既有漏洞告警，也不把开发依赖默认视为无风险；漏洞扫描与 Dependency Review 门禁继续覆盖原有范围。
- 默认分支及安全更新开关属于仓库设置，配置文件本身无法开启它们；实施时已确认默认分支为 `dev`，安全更新启用且未暂停。
