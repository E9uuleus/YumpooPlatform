# Agent Note: 依赖漏洞修复覆盖

Status: implemented

## Problem

2026-10-19 的 [Supply Chain](../../../../.github/workflows/supply-chain.yml) 定时运行中，`dev` 与 `main` 的 OSV Scan 都失败：13 个包共 54 个已知漏洞，其中 3 个 Critical 来自 Tomcat。所有漏洞都有修复版本，但有一部分无法靠常规升级获得：

- Spring Boot 4.1.1 管理的 `tomcat-embed-core` 11.0.24 和 Jackson 3.1.5 有漏洞，而 Boot 尚未发布带修复版本的补丁（Maven Central 上 4.1.x 最新仍是 4.1.1）；
- `basic-ftp` 和 `uuid` 的修复版本跨大版本，超出上游声明的依赖范围；
- `minimatch@3` 声明的范围允许 `brace-expansion` 1.1.21，但 pnpm 11 定向刷新时不重新解析这条依赖。

[供应链基线](2026-10-16-supply-chain-baseline.md)负责发现漏洞，[仅自动提出安全更新](2026-10-18-dependabot-security-only.md)负责自动修复 PR；Dependabot 无法修复上述情况，需要人工覆盖，并且要说明何时移除。

## Decision

**后端**：在 [backend/pom.xml](../../../../backend/pom.xml) 中用 Boot 公开的版本属性覆盖：

- `tomcat.version` 设为 11.0.27（修复下限是 11.0.25；取 11.x 补丁线中满 7 天的最新版）；
- `jackson-bom.version` 设为 3.1.7（3.1.8 发布于 2026-10-18，不满 7 天）。Jackson 2 注解仍由 `jackson-bom` 3.1.7 管理为 2.21，不需要单独覆盖。

不升级到 Boot 4.2 里程碑版：里程碑不是正式发布版本，不应进入生产。

**前端与工具链**：

- 依赖范围内可以修复的包，直接刷新锁文件：axios 1.20.0、`brace-expansion` 2.1.7 和 5.0.12、`fast-uri` 3.1.9 和 4.2.3、`ip-address` 10.7.4、`postcss-selector-parser` 7.1.6、`undici` 7.30.1；评审期间新公布的漏洞也按此处理：`dompurify` 3.4.17、`follow-redirects` 1.16.1（1.16.2 不满 7 天）；
- 依赖范围外或 pnpm 不重新解析的包，在 [pnpm-workspace.yaml](../../../../pnpm-workspace.yaml) 中按父包限定覆盖：`get-uri@8>basic-ftp` 设为 `^6.2.3`，`exceljs@4>uuid` 设为 `^11.1.1`，`minimatch@3>brace-expansion` 设为 `^1.1.21`。覆盖限定父包和大版本，父包升级后自然失效，不会影响其他依赖路径。

**版本选择**：只采用发布满 7 天的版本，与 Dependabot 的冷却期一致。`undici` 7.30.1 发布于 2026-10-15，修复当天公布的 8 个公告。它不满 7 天时，锁文件暂时固定在 7.30.0；满 7 天后，Dependency Review 已开始报出 7.30.0，于是升级到 7.30.1。

**移除条件**：

- Spring Boot 发布管理 Tomcat ≥ 11.0.25 且 Jackson ≥ 3.1.7 的补丁版本后，升级父 POM 并删除两个属性。2026-10-22 发布的 4.1.2 已管理 Tomcat 11.0.27 和 Jackson 3.1.7，满 7 天（2026-10-29）后即可升级；
- `get-uri` 或其上游 `proxy-agent`、`@openapitools/openapi-generator-cli` 改为依赖 `basic-ftp` 6 时，删除 `basic-ftp` 覆盖；
- `exceljs` 发布依赖 `uuid` ≥ 11.1.1 的版本，或项目不再使用 `exceljs` 时，删除 `uuid` 覆盖；
- 锁文件中不再有 `minimatch@3`，或重新解析能够得到 `brace-expansion` ≥ 1.1.21 时，删除对应覆盖。

## Alternatives considered

- **等待 Spring Boot 补丁版本**：线上版本暴露在 3 个 Critical Tomcat 漏洞下，等待周期不可控。属性覆盖是 Boot 文档支持的方式，可以随时撤回。
- **用 `<dependencyManagement>` 逐个固定 Tomcat 与 Jackson 构件**：需要列出 Tomcat 和 Jackson 的所有模块，容易漏掉；Boot 的版本属性一次覆盖整个 BOM。
- **全局 `brace-expansion`、`uuid` 覆盖**：会把同一包的其他大版本强行合并，可能破坏没有漏洞的依赖路径。改为按父包和大版本限定。
- **用 pnpm `minimumReleaseAge` 强制 7 天**：pnpm 11 会因锁文件中已有的较新条目（例如 `rolldown` 1.2.14、`undici` 6.29.1）拒绝整个解析，不适合只修复少数包的场景。改为在刷新后逐个核对发布时间。
- **不满 7 天时立即引入 `undici` 7.30.1**：冷却期内的风险较低，因为 `undici` 7 只是 Electron 下载器 `@electron/get` 的可选依赖，只在开发和打包时下载 Electron，不随产品发布。因此先采用 7.30.0，满 7 天后再升级。

## Consequences

- 后端 JAR 和 SBOM 中的 Tomcat、Jackson 版本不同于 Boot 4.1.1 的默认值；Boot 新补丁发布前，`dependency:list` 和 SBOM 会显示覆盖后的版本。
- `uuid` 覆盖只影响 Node.js 中加载的 `exceljs`（包括 Web 单元测试）。浏览器构建使用 `exceljs` 预打包的 `dist/exceljs.min.js`，其中仍内置 uuid 8 的代码。该公告只影响带输出缓冲区调用的 `v3()`、`v5()`、`v6()`；`exceljs` 只调用 `v4()`，因此浏览器端不受影响。
- `basic-ftp` 6 默认禁止单独的传输主机，以防 FTP 反弹攻击；它只是 OpenAPI 生成器代理链（`proxy-agent` → `pac-proxy-agent` → `get-uri`）的依赖，只有 PAC 文件地址使用 `ftp:` 时才会调用。
- 冷却期会让新公布的漏洞在修复版本满 7 天前继续报出；对开发和打包依赖，先采用满 7 天的最高版本，再在修复版本满 7 天后升级。
