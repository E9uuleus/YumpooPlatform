# Agent Note: OpenAPI 分域源与单文件生成物

Status: implemented

## Problem

REST 契约集中在一个超过万行的 YAML 文件中，业务域修改难以定位；兼容检查、精确哈希例外、客户端生成与交接包又都依赖这一单文件。直接让各消费方改读多个文件会扩大迁移面，并改变已有哈希和引用语义。

## Decision

`contracts/openapi/yumpoo-v1/` 是契约编辑入口，`bundle.yaml` 显式列出根文件、path 文件和 component 文件。`pnpm generate:openapi` 按清单顺序合并并提交 `contracts/openapi/yumpoo-v1.yaml`；生成器不改写 `$ref`。清单外文件、重复键和未知顶层键直接报错。`pnpm check:openapi-bundle` 与契约 CI 首步校验生成物逐字节同步。

path 按第一个 operation 的第一个 tag 分域。schema 从 operation 沿 parameters、requestBodies、responses、headers 和 schema 引用递归归属：只有一个域用到就放该域文件，多域共用或没有 operation 引用的放 `components/schemas/common.yaml`。其他 component section 各自单独成文件，`externalValue` 保持相对于生成文件位置的原值。

所有现有消费方继续读取单文件，因此兼容检查、哈希例外、lint、golden 样例、客户端生成、发布策略和交接包不需要改变接口。修改分域源后先生成单文件，再生成客户端；禁止手改生成物。

## Alternatives considered

- 继续编辑单个大文件：不增加工具，但难以定位业务域与共享 schema，未采用。
- 使用 Redocly bundle：可处理多文件引用，但可能重写 `$ref` 或引用组织；这里采用不改引用、顺序确定的直接合并。
- 让每个消费方自行读取分域文件：重复解析逻辑并扩大兼容迁移面，未采用。

## Consequences

清单与源文件必须一同维护；新增 schema 需确认它由单域独占还是跨域共享。单文件仍提交到 Git，评审可以检查源文件与生成物，CI 拒绝未同步结果。生成器只合并数据，不承担契约 lint 或兼容判断，这些检查继续由现有门禁负责。

首次拆分覆盖 151 个 path、349 个 schema。新旧 YAML 解析后递归排序对象键完全相等，重新生成客户端没有文件差异；合并顺序、重复键、未知键/清单外文件以及过期生成物由临时夹具测试覆盖。
