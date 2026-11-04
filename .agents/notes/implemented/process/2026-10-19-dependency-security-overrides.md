# Agent Note: 依赖漏洞修复覆盖

Status: implemented

## Problem

2026-10-19 的 [Supply Chain](../../../../.github/workflows/supply-chain.yml) 定时运行中，`dev` 与 `main` 的 OSV Scan 都失败：13 个包共 54 个已知漏洞，其中 3 个 Critical 来自 Tomcat。部分漏洞无法靠常规升级获得修复，另有 npm 公告未识别 SheetJS 分发版本的误报：

- Spring Boot 4.1.1 管理的 `tomcat-embed-core` 11.0.24 和 Jackson 3.1.5 有漏洞，需要升级到已包含修复的 Boot 4.1.2；
- `basic-ftp` 和 `uuid` 的修复版本跨大版本，超出上游声明的依赖范围；
- `minimatch@3` 声明的范围允许 `brace-expansion` 1.1.21，但 pnpm 11 定向刷新时不重新解析这条依赖。

[供应链基线](2026-10-16-supply-chain-baseline.md)负责发现漏洞，[仅自动提出安全更新](2026-10-18-dependabot-security-only.md)负责自动修复 PR；Dependabot 无法修复上述情况，需要人工覆盖，并且要说明何时移除。

## Decision

**后端**：升级到 Spring Boot 4.1.2。它管理的 `tomcat-embed-core` 11.0.27 与 Jackson 3.1.7 已包含修复，因此不再在 [backend/pom.xml](../../../../backend/pom.xml) 覆盖版本属性。

不升级到 Boot 4.2 里程碑版：里程碑不是正式发布版本，不应进入生产。

**前端与工具链**：

- 依赖范围内可以修复的包，直接刷新锁文件：axios 1.21.0、`brace-expansion` 2.1.7 和 5.0.12、`fast-uri` 3.1.9 和 4.2.3、`ip-address` 10.7.5、`postcss-selector-parser` 7.1.7、`undici` 7.30.1；评审期间新公布的漏洞也按此处理：`dompurify` 3.4.17、`follow-redirects` 1.16.2；
- 依赖范围外或 pnpm 不重新解析的包，在 [pnpm-workspace.yaml](../../../../pnpm-workspace.yaml) 中按父包限定覆盖：`get-uri@8>basic-ftp` 设为 `^6.2.3`，`exceljs@4>uuid` 设为 `^11.1.1`，`minimatch@3>brace-expansion` 设为 `^1.1.21`。覆盖限定父包和大版本，父包升级后自然失效，不会影响其他依赖路径。

**OSV 豁免**：[osv-scanner.toml](../../../../osv-scanner.toml) 显式由定时扫描加载，以下条目均于 2027-02-01 到期：

- `GHSA-4r6h-8v6p-xvw6`：SheetJS 0.20.3 来自 `cdn.sheetjs.com`，已含 0.19.3 的原型污染修复；npm 公告没有已修复版本，属误报。
- `GHSA-5pgg-2g8v-p4x9`：同一 SheetJS 版本已含 0.20.2 的 ReDoS 修复，属 npm 公告误报。
- `GHSA-g3hv-75wg-m249`：minimatch 只在 11.x 修复且未回移；所用匹配模式均为代码常量，不来自用户输入，仅经构建工具链与 exceljs 的 Node 打包路径引入。
- `GHSA-3g3m-2fjr-6pw4`：glob 13.x 只在 14.x 修复；仅开发与构建工具链使用，模式均为代码常量。

SheetJS 不在 npm 注册表发布，Dependabot 无法提出更新，由维护者按 SheetJS 发布页人工跟进；到期或上游出现可用修复时删除对应条目。

**版本选择**：只采用发布满 7 天的版本，与 Dependabot 的冷却期一致。`undici` 7.30.1 发布于 2026-10-15，修复当天公布的 8 个公告。它不满 7 天时，锁文件暂时固定在 7.30.0；满 7 天后，Dependency Review 已开始报出 7.30.0，于是升级到 7.30.1。

**移除条件**：

- `get-uri` 或其上游 `proxy-agent`、`@openapitools/openapi-generator-cli` 改为依赖 `basic-ftp` 6 时，删除 `basic-ftp` 覆盖；
- `exceljs` 发布依赖 `uuid` ≥ 11.1.1 的版本，或项目不再使用 `exceljs` 时，删除 `uuid` 覆盖；
- 锁文件中不再有 `minimatch@3`，或重新解析能够得到 `brace-expansion` ≥ 1.1.21 时，删除对应覆盖。

## Alternatives considered

- **继续覆盖 Boot 属性**：初版用于等待上游补丁。Boot 4.1.2 已管理修复版本，升级父 POM 可恢复统一依赖管理，无需继续维护两个覆盖。
- **用 `<dependencyManagement>` 逐个固定 Tomcat 与 Jackson 构件**：需要列出 Tomcat 和 Jackson 的所有模块，容易漏掉；Boot 的版本属性一次覆盖整个 BOM。
- **全局 `brace-expansion`、`uuid` 覆盖**：会把同一包的其他大版本强行合并，可能破坏没有漏洞的依赖路径。改为按父包和大版本限定。
- **用 pnpm `minimumReleaseAge` 强制 7 天**：pnpm 11 会因锁文件中已有的较新条目（例如 `rolldown` 1.2.14、`undici` 6.29.1）拒绝整个解析，不适合只修复少数包的场景。改为在刷新后逐个核对发布时间。
- **不满 7 天时立即引入 `undici` 7.30.1**：冷却期内的风险较低，因为 `undici` 7 只是 Electron 下载器 `@electron/get` 的可选依赖，只在开发和打包时下载 Electron，不随产品发布。因此先采用 7.30.0，满 7 天后再升级。

## Consequences

- 后端 JAR 和 SBOM 中的 Tomcat、Jackson 版本沿用 Boot 4.1.2 的依赖管理。
- 豁免到期后 OSV 定时扫描会重新失败，提醒复核。
- `uuid` 覆盖只影响 Node.js 中加载的 `exceljs`（包括 Web 单元测试）。浏览器构建使用 `exceljs` 预打包的 `dist/exceljs.min.js`，其中仍内置 uuid 8 的代码。该公告只影响带输出缓冲区调用的 `v3()`、`v5()`、`v6()`；`exceljs` 只调用 `v4()`，因此浏览器端不受影响。
- `basic-ftp` 6 默认禁止单独的传输主机，以防 FTP 反弹攻击；它只是 OpenAPI 生成器代理链（`proxy-agent` → `pac-proxy-agent` → `get-uri`）的依赖，只有 PAC 文件地址使用 `ftp:` 时才会调用。
- 冷却期会让新公布的漏洞在修复版本满 7 天前继续报出；对开发和打包依赖，先采用满 7 天的最高版本，再在修复版本满 7 天后升级。
