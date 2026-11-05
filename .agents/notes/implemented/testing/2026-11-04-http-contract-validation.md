# Agent Note: HTTP 集成测试按 OpenAPI 校验响应

Status: implemented

## Problem

OpenAPI 是 Web 与桌面生成客户端的唯一来源，CI 只校验契约相对历史基线的兼容性，没有校验后端实现是否符合契约。响应字段、状态码或错误结构与 spec 不一致时，只能在运行时由前端发现。

## Decision

使用 [@HttpIntegrationTest](../../../../backend/src/test/java/com/yumpoo/platform/testing/HttpIntegrationTest.java) 的集成测试在服务端由测试专用 Filter 采集每个 `/api/v1` 响应，按 `contracts/openapi/yumpoo-v1.yaml` 校验状态码、响应头与正文；JUnit 扩展在每个用例结束后把违规作为失败报告。只校验响应，不校验请求，因为负向用例会故意发送非法请求。[OpenApiRouteInventoryIT](../../../../backend/src/test/java/com/yumpoo/platform/foundation/api/OpenApiRouteInventoryIT.java) 要求 Controller 路由与契约操作集合完全一致。

校验依赖 `swagger-request-validator-core`，只在测试范围。

校验器按契约生态的实际语义构造：

- 契约沿用 OAS 3.0 常见写法 `nullable: true` + 单个 `allOf: [$ref]` 表示可空引用。OAS 3.0.3 按字面不允许 null，但生成客户端、兼容性检查和服务端都把它当作可空。测试在构造校验器前，只把这一精确结构改写为「原引用或 null」的 `oneOf`；结构不符时直接失败，不放宽。正式契约和生成客户端都不经过这一步。
- 校验库会给有 `properties`、但未写 `additionalProperties` 的 schema 按 `false` 校验。因此 `allOf` 的扩展分支必须显式写 `additionalProperties: true`，闭合约束由基类承担；需要闭合的组合对象要展平成一个 schema。
- 测试专用控制器不计入路由清单，排除集合固定。契约中不存在的路径返回 404 不算违规，因为路由清单已保证生产路由都已登记。

## Alternatives considered

- 在每个 IT 的 HTTP 客户端里校验：27 个测试各自构造 `HttpClient`，需要逐个改写，且新测试容易漏掉。
- 由契约生成服务端接口（OpenAPI Generator 的 Spring 接口）：能在编译期约束签名，但要改写全部 Controller，并与现有幂等、If-Match 与错误处理约定冲突。
- 只做路由清单比对：无法发现字段与状态码不一致。
- 把可空引用改写进正式契约（`oneOf` 或展开枚举）：兼容性检查把这类改写判为不兼容，生成器也会产生新的局部枚举，只能在生成前再还原，契约与 SDK 输入会分成两套。
- 关闭校验库的 additionalProperties 注入：可以避免 allOf 冲突，但会放过约 36 个未显式闭合的 schema 上的多余字段。

## Consequences

新增或修改接口时，若 spec 与实现不一致，相关 HTTP IT 会失败；补充 spec 时仍受兼容性检查约束。未使用 `@HttpIntegrationTest` 的测试（探针、认证配置变体、独占数据库）不在校验范围。

改为 OpenAPI 3.1 后，可空引用可以在契约中直接表达，届时删除规范化步骤。
