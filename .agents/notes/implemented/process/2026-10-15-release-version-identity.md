# Agent Note: 发布版本标识与运行时版本核对

Status: implemented

## Problem

产品版本此前没有可信来源：工作区 package.json 为 `0.0.0`，服务端 pom 为 `0.0.1-SNAPSHOT`，服务器包名带里程碑代号，build-info 的 `commit` 属性从未在 CI 注入，运维中心始终显示 `unknown`。`server-v*` 标签由人工命名。线上无法判断运行的是哪个发布、哪个提交，部署验收也无从核对。

## Decision

版本号就是 Release tag，在发布时确定，格式为 `server-vYYYY.MM.DD.N`，由 Prepare Release 按北京时间日期自动编号（见 [main 发布线决策](2026-10-15-main-release-line.md)）。构建产物只携带 commit，以保持「只构建一次」：

- [CI 编排](../../../../tools/ci/run.mjs)给 Maven 步骤传入 `-Dyumpoo.build.commit=<tested commit>`，build-info 的 `commit` 来自被测提交。
- [Web 构建](../../../../frontend/web-app/vite.config.ts)在 `index.html` 写入 `<meta name="yumpoo-commit">`。没有有效 commit 时写 `unknown`。

发布标签在运行时注入：

- 部署环境变量 `YUMPOO_DEPLOYMENT_RELEASE_TAG` 绑定到 `yumpoo.deployment.release-tag`，默认值为 `unreleased`。
- Actuator 只暴露 `health` 和 `info`；`info` 只输出 `release.tag` 和 build-info（见 [application.yml](../../../../backend/src/main/resources/application.yml)）。
- [Nginx 模板](../../../../deployment/windows/nginx/yumpoo-wecom.conf)以精确匹配公开 `/healthz`（readiness）和 `/version.json`（info），这两条规则位于 actuator 拒绝规则之前，其余 actuator 路径继续拒绝。维护状态下二者与站点一样返回 503。
- 运维中心的「版本」、启动日志 `app.started` 的 `release` 字段显示发布标签；Web 账户菜单显示版本和短 commit。

仓库内版本字段固定为占位值，由 [占位符检查](../../../../tools/release/version-placeholders.mjs)在 `ci:static` 中拒绝修改：

- 根目录、`packages/*`、`frontend/web-app` 的 package.json 固定为 `0.0.0`；
- 服务端 pom 固定为 `0.0.1-SNAPSHOT`，因为备份清单与历史证据已记录该值；
- `desktop-shell` 保留独立的桌面版本线。

## Alternatives considered

- 仓库内单一版本源，每次发布前提交 bump：产物自带可读版本，但每次发布多一个 PR 和人工步骤，也与「发布时决定版本」冲突。
- CI 构建时生成 CalVer：未发布的构建也会占用编号，版本号不连续。
- 新增 `/api/v1/system/version` 业务接口：需要扩展 OpenAPI 契约，并在会话过滤器中开匿名例外；Actuator `info` 已能提供同等信息，且不进入业务 API。
- 把 pom 改为 `0.0.0-SNAPSHOT` 以统一占位：备份清单与历史证据记录了现值，改动只会带来测试和证据漂移。

## Consequences

- JAR 和服务器包本身不含人类可读的版本号。tag、commit、CI run 之间的对应关系由 Release 附件中的来源清单和运行时注入共同提供。本地运行显示 `unreleased`。
- `/version.json` 公开 build 名称、构建时间和 commit。仓库本身是公开的，这不构成额外泄露。
- 生产 Nginx 由管理员维护的站点配置和部署引擎的受管 include 组成。模板中的探测路由需要写入受管 include 后才在生产生效；部署引擎的职责由[发布部署决策](2026-10-14-windows-release-deployment.md)负责。
